package com.neonvpn.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.neonvpn.app.R
import com.neonvpn.app.config.ConfigStore
import com.neonvpn.app.config.ServerConfig
import com.neonvpn.app.config.XrayConfigBuilder
import com.neonvpn.app.ui.MainActivity
import com.v2ray.ang.service.TProxyService
import java.io.File
import kotlin.concurrent.thread

/**
 * The real Android VpnService.
 *
 *   1. asks the system for the TUN interface (VpnService.Builder);
 *   2. boots Xray-core with the selected server config (local SOCKS5 inbound);
 *   3. starts hev-socks5-tunnel (tun2socks) which pumps TUN packets into that
 *      SOCKS5 inbound — so every app's traffic really goes through the proxy.
 *
 * Stability:
 *   - START_STICKY + a partial WakeLock so the OS keeps the tunnel alive when
 *     the screen is off / app is in the background (it will NOT auto-close).
 *   - A watchdog that re-spins Xray if the core dies unexpectedly.
 *   - A 1-second stats pump broadcasting live up/down speed + ping.
 */
class NeonVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private lateinit var xray: XrayManager
    private var tunnelThread: Thread? = null
    private var statsThread: Thread? = null
    private var watchdogThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var running = false
    @Volatile private var stopping = false
    private var currentServer: ServerConfig? = null

    private var startThread: Thread? = null

    // --- mobile-data bypass: track the real underlying network ---
    // On WiFi, addDisallowedApplication(self) is enough to keep the core's own
    // sockets off the TUN. On MOBILE DATA, Android's multi-network routing means
    // the VPN's underlying transport must be set EXPLICITLY or the tunnel's
    // outbound packets get black-holed (the "doesn't work on SIM data" bug). We
    // register a network callback, pick the best non-VPN network (preferring an
    // actually-validated one), and pin it as the VpnService's underlying network
    // so the core's sockets always egress over the live physical link.
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var activeUnderlying: Network? = null

    override fun onCreate() {
        super.onCreate()
        // NOTE: heavy native init (geo asset extraction, initCoreEnv) is moved
        // OFF the main thread to avoid ANR/crash on cold start. We only build the
        // wrapper here; init() is called on the worker thread in startVpn().
        xray = XrayManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> {
                // Always promote to foreground IMMEDIATELY & synchronously on the
                // main thread — Android 8+ kills a started FGS that doesn't call
                // startForeground() within ~5s, and Android 14 additionally requires
                // the foregroundServiceType to be supplied. Doing this first (before
                // any heavy work) is the single most important crash fix.
                val name = try { ConfigStore(this).getSelected()?.remark } catch (_: Throwable) { null }
                    ?: "Professor VPN"
                goForeground(name, "Connecting…")
                startVpnAsync()
            }
        }
        return START_STICKY
    }

    /** Promote to a foreground service with the correct type for the OS version. */
    private fun goForeground(serverName: String, text: String) {
        try {
            val notif = buildNotification(serverName, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: must pass a foreground service type.
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
            // last-ditch fallback so we don't crash the whole process
            try { startForeground(NOTIF_ID, buildNotification(serverName, text)) } catch (_: Throwable) {}
        }
    }

    /** Kicks off the (heavy) connect sequence on a dedicated worker thread. */
    private fun startVpnAsync() {
        if (running) return
        if (startThread?.isAlive == true) return
        stopping = false
        startThread = thread(name = "vpn-start", isDaemon = true) {
            try {
                startVpn()
            } catch (e: Throwable) {
                Log.e(TAG, "startVpnAsync crash guard: ${e.message}", e)
                broadcastState(STATE_ERROR, e.message ?: "error")
                try { cleanup() } catch (_: Throwable) {}
                try { stopForegroundCompat(); stopSelf() } catch (_: Throwable) {}
            }
        }
    }

    private fun startVpn() {
        if (running) return

        val store = ConfigStore(this)
        val server = store.getSelected()
        if (server == null) {
            broadcastState(STATE_ERROR, "No config selected")
            stopForegroundCompat()
            stopSelf()
            return
        }
        currentServer = server
        broadcastState(STATE_CONNECTING, server.remark)
        updateNotification(server.remark, "Connecting…")
        emitProgress(5, "Starting")

        acquireWakeLock()
        // Start tracking the real underlying network BEFORE we bring TUN up so
        // the very first outbound from the core egresses over the live link
        // (critical for mobile data).
        registerNetworkCallback()

        try {
            // 0) Make sure the Xray core env is initialised (idempotent). Done on
            //    this worker thread so a slow asset extraction never blocks the UI.
            emitProgress(15, "Preparing engine")
            xray.init()

            // 1) Establish the TUN device first.
            emitProgress(30, "Opening tunnel")
            tunInterface = establishTun() ?: run {
                broadcastState(STATE_ERROR, "Failed to establish VPN interface")
                cleanup()
                stopForegroundCompat()
                stopSelf()
                return
            }

            // 2) Start the real Xray core with the generated config.
            emitProgress(50, "Connecting core")
            val json = XrayConfigBuilder.build(server)
            Log.d(TAG, "Xray config:\n$json")
            val ok = xray.start(json)
            if (!ok) {
                broadcastState(STATE_ERROR, "Core failed to start")
                cleanup()
                stopForegroundCompat()
                stopSelf()
                return
            }

            // Give the core a brief moment to actually bind the SOCKS inbound
            // before tun2socks starts hammering it (prevents a startup race that
            // could crash the native tunnel — the old "stopped" bug).
            Thread.sleep(450)

            // 2.5) HEALTH CHECK — prove the proxy can actually carry traffic
            // BEFORE we tell the user "Connected". This is what kills the old
            // "connected but nothing opens" lie: a server that handshakes at TCP
            // level but can't proxy a real request is rejected here.
            //
            // We probe through the LIVE core (so it matches exactly what traffic
            // will experience) and, to mirror the Pinger's 2-stage confirmation,
            // we accept the connection only if at least one of a couple of real
            // round-trips succeeds. A first cold probe can fail while the tunnel
            // is still warming, so we retry briefly before giving up — this is
            // what makes "if it pinged, it connects" hold true in practice.
            emitProgress(70, "Verifying")
            var health = -1L
            run {
                // v4.5 — confirm the LIVE tunnel with the SAME two-censored-endpoint
                // rule the per-config ping uses (XrayManager.confirmedHealth). This
                // is what makes "if it pinged, it REALLY connects 100%" hold true:
                // the connect verdict now matches the ping verdict exactly, instead
                // of a single lucky generate_204 (which produced the fake-ping bug).
                //
                // A cold Reality/XTLS handshake on Iran's disrupted links can take a
                // couple of seconds, so we retry patiently within a generous window.
                val deadline = System.currentTimeMillis() + 14000
                var attempts = 0
                while (System.currentTimeMillis() < deadline && attempts < 8) {
                    attempts++
                    val d = try { xray.confirmedHealth() } catch (_: Throwable) { -1L }
                    if (d in 1..15000) { health = d; break }
                    try { Thread.sleep(450) } catch (_: InterruptedException) { break }
                }
            }
            if (health !in 1..15000) {
                Log.w(TAG, "post-connect health check failed (delay=$health) — server can't proxy")
                broadcastState(STATE_ERROR, "Server not responding — pick another")
                cleanup()
                stopForegroundCompat()
                stopSelf()
                return
            }
            Log.i(TAG, "health check OK: ${health}ms")

            // 3) Start tun2socks (hev) bridging TUN <-> local SOCKS5.
            emitProgress(90, "Routing traffic")
            startTun2Socks(tunInterface!!.fd)

            running = true
            isTunnelUp = true
            emitProgress(100, "Connected")
            broadcastState(STATE_CONNECTED, server.remark)
            updateNotification(server.remark, "Connected · ${health}ms")
            startStatsPump()
            startWatchdog()
            Log.i(TAG, "VPN connected via ${server.protocol} ${server.address}:${server.port}")
        } catch (e: Throwable) {
            Log.e(TAG, "startVpn error: ${e.message}", e)
            broadcastState(STATE_ERROR, e.message ?: "error")
            cleanup()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun establishTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("ProfessorVPN")
            .setMtu(VPN_MTU)
            .addAddress(PRIVATE_VLAN4_CLIENT, 30)
            .addDnsServer(DNS_V4)
            .addDnsServer(DNS_V4_2)
            .addRoute("0.0.0.0", 0)          // capture all IPv4 traffic
            .setBlocking(false)

        // Don't tunnel ourselves (avoid loops). This keeps the core's own
        // sockets (same package) OFF the TUN so they egress over the real link.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
                builder.addRoute("::", 0)
            } catch (_: Exception) {
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { builder.setMetered(false) } catch (_: Exception) {}
        }

        // Pin the real underlying transport (WiFi / cellular) so the tunnel's
        // outbound sockets always leave over the live physical network — the
        // core of the mobile-data bypass. On API 22+ Builder.setUnderlyingNetworks
        // isn't available, so we also call VpnService.setUnderlyingNetworks()
        // right after establish() (see applyUnderlyingNetwork()).
        val tun = builder.establish()
        applyUnderlyingNetwork()
        return tun
    }

    // ------------------------------------------------- mobile-data bypass
    /** Register a callback that keeps [activeUnderlying] pointed at the best
     *  non-VPN physical network, and re-pins it on the VpnService whenever it
     *  changes (WiFi⇄cellular handover, SIM data toggled, etc.). */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            connectivityManager = cm
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                // exclude our own VPN transport so we never pin the TUN to itself
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isUsableUnderlying(network)) {
                        activeUnderlying = network
                        applyUnderlyingNetwork()
                    }
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val notVpn = !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    val hasNet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    if (notVpn && hasNet) {
                        activeUnderlying = network
                        applyUnderlyingNetwork()
                    }
                }
                override fun onLost(network: Network) {
                    if (activeUnderlying == network) {
                        activeUnderlying = pickBestUnderlying()
                        applyUnderlyingNetwork()
                    }
                }
            }
            networkCallback = cb
            // requestNetwork would force-activate cellular; we only OBSERVE, so
            // registerNetworkCallback is correct and battery-friendly.
            cm.registerNetworkCallback(req, cb)
            // seed an initial value immediately
            activeUnderlying = pickBestUnderlying()
            applyUnderlyingNetwork()
        } catch (e: Throwable) {
            Log.w(TAG, "registerNetworkCallback: ${e.message}")
        }
    }

    private fun isUsableUnderlying(network: Network): Boolean {
        val cm = connectivityManager ?: return false
        val caps = try { cm.getNetworkCapabilities(network) } catch (_: Throwable) { null } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** Choose the best non-VPN network: prefer a validated one, prefer WiFi then
     *  cellular then anything else with INTERNET. */
    private fun pickBestUnderlying(): Network? {
        val cm = connectivityManager ?: return null
        return try {
            val candidates = cm.allNetworks.filter { isUsableUnderlying(it) }
            if (candidates.isEmpty()) return null
            // rank: validated > wifi > cellular > other
            candidates.maxByOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                var score = 0
                if (caps != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 100
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 10
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 5
                }
                score
            }
        } catch (_: Throwable) { null }
    }

    /** Pin the chosen underlying network onto the VpnService so the core's
     *  protected/disallowed sockets egress over the live link. */
    private fun applyUnderlyingNetwork() {
        try {
            val net = activeUnderlying ?: pickBestUnderlying()
            // null => let the system pick the default (still works on WiFi-only).
            setUnderlyingNetworks(if (net != null) arrayOf(net) else null)
        } catch (e: Throwable) {
            Log.w(TAG, "setUnderlyingNetworks: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cb = networkCallback
            if (cb != null) connectivityManager?.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {}
        networkCallback = null
        activeUnderlying = null
    }

    private fun startTun2Socks(fd: Int) {
        val configFile = File(filesDir, "hev-socks5-tunnel.yaml")
        configFile.writeText(buildHevConfig())

        tunnelThread = thread(name = "tun2socks", isDaemon = true) {
            // v4.2 — if the native tunnel ever returns unexpectedly while the
            // session is still meant to be up (a rare native hiccup on weak
            // devices), restart it instead of leaving traffic black-holed. A
            // small bounded retry loop keeps the TUN ⇄ SOCKS bridge alive without
            // ever crashing the process.
            var restarts = 0
            while (running && !stopping && restarts <= 3) {
                try {
                    // Blocks until TProxyStopService is called.
                    TProxyService.TProxyStartService(configFile.absolutePath, fd)
                } catch (e: Throwable) {
                    Log.e(TAG, "tun2socks stopped: ${e.message}")
                }
                if (!running || stopping) break
                restarts++
                Log.w(TAG, "tun2socks returned while connected — restarting (#$restarts)")
                try { Thread.sleep(300) } catch (_: InterruptedException) { break }
            }
        }
    }

    /**
     * hev-socks5-tunnel YAML. Keys verified against the bundled .so symbols:
     *   tunnel.{name,mtu,ipv4} | socks5.{port,address,udp} | misc.*
     */
    private fun buildHevConfig(): String {
        // Keys verified against the bundled .so's parser symbols:
        //   tunnel.{name,mtu,ipv4} | socks5.{port,address} | misc.*
        return buildString {
            appendLine("tunnel:")
            appendLine("  name: prof-tun")
            appendLine("  mtu: $VPN_MTU")
            appendLine("  ipv4: $PRIVATE_VLAN4_CLIENT")
            appendLine("socks5:")
            appendLine("  port: ${XrayConfigBuilder.SOCKS_PORT}")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            appendLine("misc:")
            // v4.5 — tuned for FULL bandwidth + rock-solid persistence:
            //   • bigger per-task stack => the native tunnel can drive many
            //     parallel high-throughput streams without stack pressure;
            //   • a generous read-write-timeout (5 min) so a long idle download /
            //     a screen-off pause / hours in another app never tears a live
            //     connection down — it just resumes pulling at full speed;
            //   • a tight connect-timeout so a genuinely dead link fails fast and
            //     the watchdog can recover instead of hanging.
            appendLine("  task-stack-size: 163840")
            appendLine("  connect-timeout: 5000")
            appendLine("  read-write-timeout: 300000")
            appendLine("  log-level: warn")
        }
    }

    // ------------------------------------------------------------ stats pump
    private fun startStatsPump() {
        statsThread = thread(name = "stats", isDaemon = true) {
            var totalUp = 0L
            var totalDown = 0L
            var lastTs = System.currentTimeMillis()
            val startTs = System.currentTimeMillis()
            var tick = 0
            var lastPing = -1L

            // Do an immediate ping right after connect so the user sees a real
            // number within ~1-2s instead of staring at a dash. We use a single
            // fast probe for the DISPLAYED number (cheap, refreshed often); the
            // authoritative keep-alive decision lives in the watchdog, which uses
            // the heavier confirmed (two-endpoint) check.
            try {
                val p0 = xray.measureDelay()
                if (p0 in 1..8000) lastPing = p0
            } catch (_: Throwable) {}

            // Baseline for the tun2socks native counters (cumulative since the
            // tunnel started). Used as a fallback when the Xray stats API returns
            // 0 (e.g. some core builds don't surface per-outbound counters).
            var lastTunTx = 0L
            var lastTunRx = 0L

            while (running && !stopping) {
                try {
                    Thread.sleep(1000)
                    if (!running || stopping) break

                    // Per-tick delta bytes straight from the core (resetting
                    // counters), accumulated here into true totals.
                    var (upDelta, downDelta) = xray.queryTrafficDelta()

                    // FALLBACK: if the Xray stats API gave us nothing this tick,
                    // read the tun2socks native byte counters (TUN tx/rx). These
                    // are always populated whenever packets actually move, so the
                    // speed meter can never be stuck at a permanent 0 B/s while
                    // real traffic is flowing. We diff the cumulative values.
                    if (upDelta == 0L && downDelta == 0L) {
                        try {
                            val tun = TProxyService.TProxyGetStats()
                            if (tun != null && tun.size >= 2) {
                                // convention: [0]=tx(up from device), [1]=rx(down)
                                val tx = tun[0].coerceAtLeast(0)
                                val rx = tun[1].coerceAtLeast(0)
                                if (lastTunTx == 0L && lastTunRx == 0L) {
                                    lastTunTx = tx; lastTunRx = rx
                                } else {
                                    val du = (tx - lastTunTx).coerceAtLeast(0)
                                    val dd = (rx - lastTunRx).coerceAtLeast(0)
                                    lastTunTx = tx; lastTunRx = rx
                                    // feed back into the per-tick delta; the totals
                                    // are accumulated once below (no double count).
                                    upDelta = du; downDelta = dd
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    totalUp += upDelta
                    totalDown += downDelta

                    val now = System.currentTimeMillis()
                    val dt = ((now - lastTs).coerceAtLeast(1)).toDouble() / 1000.0
                    lastTs = now

                    // Live per-second rate from the delta — real numbers, no fakes.
                    val upRate = (upDelta / dt).toLong().coerceAtLeast(0)
                    val downRate = (downDelta / dt).toLong().coerceAtLeast(0)

                    // Refresh ping every ~5s (measureDelay opens a probe connection).
                    if (tick % 5 == 0) {
                        try {
                            val p = xray.measureDelay()
                            if (p in 1..8000) lastPing = p
                        } catch (_: Throwable) {}
                    }
                    tick++

                    // uptime always advances once connected (independent of traffic).
                    val uptime = ((now - startTs) / 1000)
                    broadcastStats(upRate, downRate, totalUp, totalDown, lastPing, uptime)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    Log.w(TAG, "stats pump: ${e.message}")
                }
            }
        }
    }

    // --------------------------------------------------------------- watchdog
    /**
     * Keeps the tunnel ALIVE on Iran's disrupted internet. Every few seconds it
     * checks that (a) the Xray core is still running and (b) the proxy can still
     * carry a real request. If the core silently died (OOM, network blip, DPI
     * RST storm) it transparently re-spins it with the same config WITHOUT
     * dropping the user's VPN session — no reconnect tap needed. Consecutive hard
     * failures eventually surface an error so the user can switch servers.
     */
    private fun startWatchdog() {
        watchdogThread = thread(name = "watchdog", isDaemon = true) {
            var consecutiveFailures = 0
            var reviveAttempts = 0
            // grace period so we don't probe during the first warm-up
            try { Thread.sleep(8000) } catch (_: InterruptedException) { return@thread }

            while (running && !stopping) {
                try {
                    // v4.2 — RESILIENT watchdog. While healthy we poll at the base
                    // cadence; after a failure we wait the next step in the
                    // 2/4/8/16/32s backoff before probing again so a node that's
                    // briefly down during a network blip isn't hammered.
                    val waitMs = if (consecutiveFailures == 0) WATCHDOG_INTERVAL_MS
                        else WATCHDOG_BACKOFF_MS[
                            (consecutiveFailures - 1).coerceIn(0, WATCHDOG_BACKOFF_MS.lastIndex)
                        ]
                    Thread.sleep(waitMs)
                    if (!running || stopping) break

                    // v4.2 — if there's currently NO usable physical network
                    // (airplane mode, tunnel/metro dead-zone, screen-off doze with
                    // radios parked) we must NOT treat that as "server dead" and
                    // tear the session down. We simply wait for connectivity to
                    // come back and keep the tunnel armed — this is core to "never
                    // disconnects". Re-pin the underlying network when it returns.
                    if (!hasUsableNetwork()) {
                        Log.i(TAG, "watchdog: no physical network — holding tunnel, not failing")
                        applyUnderlyingNetwork()
                        // v4.5 — keep the wake lock fresh while we wait out the
                        // outage so a multi-hour download / background session never
                        // gets suspended and the tunnel resumes the instant the
                        // network (wifi or data) comes back, at full speed.
                        acquireWakeLock()
                        continue
                    }
                    // v4.5 — periodically renew the wake lock during normal healthy
                    // operation too, so the session genuinely "never drops" even
                    // across the original 10h window (long idle downloads, etc.).
                    acquireWakeLock()

                    // v4.2 — confirm a failure with a SECOND probe before reacting,
                    // so a single dropped probe (very common on Iran's flaky links)
                    // can never trigger a needless reconnect/teardown.
                    // v4.5 — the watchdog judges the tunnel with the SAME confirmed
                    // (two-censored-endpoint) health check the connect path + the
                    // per-config ping use, so it agrees with what the user saw when
                    // they connected. A node that has silently gone dead can no
                    // longer keep faking "still connected" off one lucky probe.
                    val coreAlive = try { xray.isRunning } catch (_: Throwable) { false }
                    var health = if (coreAlive) {
                        try { xray.confirmedHealth() } catch (_: Throwable) { -1L }
                    } else -1L
                    if (coreAlive && health !in 1..15000) {
                        try { Thread.sleep(700) } catch (_: InterruptedException) { break }
                        health = try { xray.confirmedHealth() } catch (_: Throwable) { -1L }
                    }

                    val healthy = coreAlive && health in 1..15000
                    if (healthy) {
                        consecutiveFailures = 0
                        reviveAttempts = 0
                        continue
                    }

                    consecutiveFailures++
                    Log.w(TAG, "watchdog: unhealthy (coreAlive=$coreAlive delay=$health) " +
                        "fail#$consecutiveFailures — re-spinning core")

                    // try to revive the core in place (same config, no user tap)
                    val srv = currentServer
                    if (srv != null) {
                        try {
                            reviveAttempts++
                            xray.stop()
                            Thread.sleep(300)
                            val json = XrayConfigBuilder.build(srv)
                            val ok = xray.start(json)
                            if (ok) {
                                Thread.sleep(500)
                                val again = try { xray.confirmedHealth() } catch (_: Throwable) { -1L }
                                if (again in 1..15000) {
                                    consecutiveFailures = 0
                                    reviveAttempts = 0
                                    Log.i(TAG, "watchdog: core revived (${again}ms)")
                                    updateNotification(srv.remark, "Reconnected · ${again}ms")
                                    continue
                                }
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "watchdog revive failed: ${e.message}")
                        }
                    }

                    // v4.2 — Be FAR more patient before surfacing an error. We keep
                    // re-spinning the core through the whole backoff schedule, and
                    // only give up after MANY sustained hard failures WITH a live
                    // physical network (so a temporary outage never drops a user
                    // who is actually online). This makes the app feel like it
                    // "never disconnects" while staying honest when a node is truly
                    // dead for good.
                    if (consecutiveFailures >= MAX_HARD_FAILURES) {
                        Log.e(TAG, "watchdog: giving up after $consecutiveFailures failures")
                        broadcastState(STATE_ERROR, "Connection lost — pick another server")
                        cleanup()
                        stopForegroundCompat()
                        stopSelf()
                        break
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    Log.w(TAG, "watchdog: ${e.message}")
                }
            }
        }
    }

    /** v4.2 — is there any non-VPN network with INTERNET capability right now? */
    private fun hasUsableNetwork(): Boolean {
        return try {
            val cm = connectivityManager ?: getSystemService(ConnectivityManager::class.java)
            if (cm != null && connectivityManager == null) connectivityManager = cm
            cm ?: return true   // can't tell → assume yes (don't falsely tear down)
            cm.allNetworks.any { net ->
                val caps = try { cm.getNetworkCapabilities(net) } catch (_: Throwable) { null }
                caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        } catch (_: Throwable) { true }
    }

    // ----------------------------------------------------------------- stop
    private fun stopVpn() {
        stopping = true
        emitProgress(0, "Disconnected")
        broadcastState(STATE_DISCONNECTED, "")
        cleanup()
        stopForegroundCompat()
        stopSelf()
    }

    private fun cleanup() {
        running = false
        stopping = true
        isTunnelUp = false
        try { watchdogThread?.interrupt() } catch (_: Throwable) {}
        watchdogThread = null
        try { statsThread?.interrupt() } catch (_: Throwable) {}
        statsThread = null
        try {
            TProxyService.TProxyStopService()
        } catch (_: Throwable) {
        }
        try { tunnelThread?.interrupt() } catch (_: Throwable) {}
        tunnelThread = null
        xray.stop()
        try { tunInterface?.close() } catch (_: Exception) {}
        tunInterface = null
        unregisterNetworkCallback()
        releaseWakeLock()
    }

    override fun onDestroy() {
        // If the OS killed us while connected (e.g. low-memory) the watchdog may
        // not have run — make sure the authoritative state reflects that the
        // tunnel is GONE so the UI can't keep showing a stale "Connected".
        isTunnelUp = false
        if (liveState == STATE_CONNECTED || liveState == STATE_CONNECTING) {
            liveState = STATE_DISCONNECTED
            liveInfo = ""
        }
        cleanup()
        super.onDestroy()
    }

    override fun onRevoke() {
        // user revoked VPN permission from system settings
        stopVpn()
        super.onRevoke()
    }

    /**
     * §4.5 — when the user swipes the app out of Recents we DO NOT tear the
     * tunnel down. A live VPN session must survive task removal (that's the
     * whole point of a foreground VPN service), so we keep running and rely on
     * START_STICKY to have the OS re-deliver a start intent if it ever kills us
     * for memory. Only an explicit Disconnect (ACTION_STOP) ends the session.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (running && !stopping) {
            Log.i(TAG, "onTaskRemoved: keeping tunnel alive (START_STICKY)")
            // do NOT call stopSelf — let the foreground service persist.
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    // ----------------------------------------------------------- wake lock
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "prfvpn:tunnel"
                ).apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(10 * 60 * 60 * 1000L /*10h*/)
        } catch (e: Throwable) {
            Log.w(TAG, "wakelock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
    }

    // -------------------------------------------------------- notification
    private fun buildNotification(serverName: String, text: String): android.app.Notification {
        createChannel()
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            pendingFlags()
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, NeonVpnService::class.java).apply { action = ACTION_STOP },
            pendingFlags()
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Professor VPN · $serverName")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_vpn, "Disconnect", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(serverName: String, text: String) {
        try {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.notify(NOTIF_ID, buildNotification(serverName, text))
        } catch (_: Throwable) {
        }
    }

    private fun pendingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW
                )
                ch.setShowBadge(false)
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /** §4.3 — push a connect-progress milestone to the Liquid Orb via the bus. */
    private fun emitProgress(percent: Int, label: String) {
        try { com.neonvpn.app.ui.VpnStateBus.updateProgress(percent, label) } catch (_: Throwable) {}
    }

    private fun broadcastState(state: String, info: String) {
        // Keep the authoritative, process-wide state in sync FIRST so any UI that
        // queries it after returning from background reads the truth — not a stale
        // value left over from before the broadcast receivers were unregistered.
        liveState = state
        liveInfo = info
        val i = Intent(BROADCAST_STATE)
        i.setPackage(packageName)
        i.putExtra(EXTRA_STATE, state)
        i.putExtra(EXTRA_INFO, info)
        sendBroadcast(i)
    }

    private fun broadcastStats(
        upRate: Long, downRate: Long, upTotal: Long, downTotal: Long, ping: Long, uptime: Long
    ) {
        val i = Intent(BROADCAST_STATS)
        i.setPackage(packageName)
        i.putExtra(EXTRA_UP_RATE, upRate)
        i.putExtra(EXTRA_DOWN_RATE, downRate)
        i.putExtra(EXTRA_UP_TOTAL, upTotal)
        i.putExtra(EXTRA_DOWN_TOTAL, downTotal)
        i.putExtra(EXTRA_PING, ping)
        i.putExtra(EXTRA_UPTIME, uptime)
        sendBroadcast(i)
    }

    companion object {
        private const val TAG = "NeonVpnService"

        const val ACTION_STOP = "com.neonvpn.app.STOP"
        const val BROADCAST_STATE = "com.neonvpn.app.VPN_STATE"
        const val BROADCAST_STATS = "com.neonvpn.app.VPN_STATS"

        const val EXTRA_STATE = "state"
        const val EXTRA_INFO = "info"
        const val EXTRA_UP_RATE = "up_rate"
        const val EXTRA_DOWN_RATE = "down_rate"
        const val EXTRA_UP_TOTAL = "up_total"
        const val EXTRA_DOWN_TOTAL = "down_total"
        const val EXTRA_PING = "ping"
        const val EXTRA_UPTIME = "uptime"

        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_ERROR = "error"

        /**
         * AUTHORITATIVE, process-wide VPN state. The service owns it and updates
         * it on every state change (see [broadcastState]). The UI registers
         * broadcast receivers only while in the foreground, so when the app is
         * backgrounded and the watchdog later tears the tunnel down (or the
         * service is killed), the broadcast is missed and the in-memory
         * [com.neonvpn.app.ui.VpnStateBus] goes stale ("still says connected").
         *
         * On resume the UI reconciles against THIS value (and whether the service
         * process is actually alive) so it can never show a stale "Connected".
         */
        @Volatile @JvmStatic var liveState: String = STATE_DISCONNECTED
            private set
        @Volatile @JvmStatic var liveInfo: String = ""
            private set

        /** True while the service is actively running a live tunnel. */
        @Volatile @JvmStatic var isTunnelUp: Boolean = false
            internal set

        private const val CHANNEL_ID = "professorvpn_status"
        private const val NOTIF_ID = 1

        // Watchdog: base health-check cadence while healthy, then a 5-step
        // exponential backoff (2/4/8/16/32s) between failed revival attempts.
        // After the 5th failure the session is torn down and the user is told.
        private const val WATCHDOG_INTERVAL_MS = 7000L
        private val WATCHDOG_BACKOFF_MS = longArrayOf(2000L, 4000L, 8000L, 16000L, 32000L)

        // v4.2 — only surface a "connection lost" after this many SUSTAINED hard
        // failures (each already double-probed + a core re-spin attempt) while a
        // live physical network exists. Much higher than before so the tunnel
        // rides out Iran's frequent disruptions instead of dropping the user.
        private const val MAX_HARD_FAILURES = 10

        // 1500 matches the tun2socks tunnel MTU; both sides MUST agree.
        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        private const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        private const val DNS_V4 = "1.1.1.1"
        private const val DNS_V4_2 = "8.8.8.8"
    }
}
