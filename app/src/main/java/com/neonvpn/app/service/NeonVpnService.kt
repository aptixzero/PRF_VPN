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
            // before we probe it / tun2socks starts hammering it (prevents a
            // startup race that could crash the native tunnel).
            Thread.sleep(450)

            // 2.5) v5.5 — HEALTH CHECK through the LIVE core BEFORE reporting
            // "Connected". This is the proven approach that actually WORKED: the
            // config now dials the server with real TLS-hello fragmentation (see
            // XrayConfigBuilder), so a green health check here genuinely means the
            // tunnel carries traffic — not the "connected but nothing works" lie of
            // v4.6–v5.4. We probe with a lenient window and a couple of retries so
            // a cold-but-good tunnel is never falsely rejected. NO extra "real
            // bytes" gate (that over-aggressive v5.2 check false-rejected good
            // servers and is exactly what we're removing).
            emitProgress(80, "Verifying")
            var health = -1L
            run {
                val deadline = System.currentTimeMillis() + 8000
                var attempts = 0
                while (System.currentTimeMillis() < deadline && attempts < 4) {
                    attempts++
                    val d = try { xray.measureDelay() } catch (_: Throwable) { -1L }
                    if (d in 1..15000) { health = d; break }
                    try { Thread.sleep(500) } catch (_: InterruptedException) { break }
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

            // 2.6) Start tun2socks (hev) bridging TUN <-> local SOCKS5 so ALL
            // device traffic now flows through the verified tunnel. We flip
            // `running` TRUE *before* starting the tunnel thread — its keep-alive
            // loop guards on `running`, so setting it afterwards would race and the
            // native TProxyStartService could be skipped entirely (a real cause of
            // "connected but no traffic"). Order matters here.
            running = true
            isTunnelUp = true
            emitProgress(92, "Routing traffic")
            startTun2Socks(tunInterface!!.fd)

            emitProgress(100, "Connected")
            broadcastState(STATE_CONNECTED, server.remark)
            updateNotification(server.remark, "Connected · ${health}ms")
            startStatsPump()
            startWatchdog()
            // v6.4 — learn the REAL egress IP + country through the live tunnel.
            resolveExitIdentityAsync(server)
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

        // ── v6.4 — DO NOT ROUTE IPv6 INTO THE TUN ─────────────────────────────
        // v6.3 captured `::/0` as well. That was actively harmful: the Xray
        // config now resolves IPv4-only (`queryStrategy: UseIPv4`) and virtually
        // no free public node relays IPv6 at all, so every v6 packet an app sent
        // was swallowed by the TUN and silently dropped. Dual-stack apps like
        // Instagram try IPv6 FIRST (Happy Eyeballs), so each new connection paid
        // a timeout before falling back — the stalls that pile up into "it works
        // for a minute then freezes".
        //
        // By NOT adding a v6 address/route, the system reports no IPv6
        // connectivity to apps while the VPN is up, so they go straight to IPv4
        // and everything stays fast and responsive. On Android 10+ we can state
        // this explicitly, which makes the fallback instant.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { builder.allowFamily(android.system.OsConstants.AF_INET) } catch (_: Exception) {}
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
            // ── v6.4 — tun2socks side of the "freezes after a minute" fix ─────
            //
            //   • read-write-timeout 300000 → 60000. This was the single most
            //     damaging value. hev holds one native session per TCP flow, and
            //     a 5-MINUTE timeout meant every socket Instagram finished with
            //     stayed parked for five more minutes. A minute of scrolling
            //     easily opens a few hundred flows, so the session table filled
            //     up and NEW flows could no longer be created — the feed froze
            //     while the tunnel itself was still perfectly healthy. Toggling
            //     the VPN flushed the whole table, which is exactly why a manual
            //     reconnect always "fixed" it. 60s keeps long-poll / websocket /
            //     download connections comfortably alive while reclaiming dead
            //     ones fast enough that the table can never saturate.
            //   • max-session-count caps the table explicitly, so even a
            //     pathological app cannot exhaust it.
            //   • limit-nofile raises the fd ceiling so a busy session
            //     (many parallel media streams) never hits EMFILE.
            //   • udp-read-write-timeout is short: QUIC is blocked upstream, so
            //     the only UDP left is DNS-ish and should be reaped quickly.
            //   • a tight connect-timeout so a genuinely dead link fails fast and
            //     the watchdog can recover instead of hanging.
            appendLine("  task-stack-size: 81920")
            appendLine("  connect-timeout: 5000")
            appendLine("  read-write-timeout: 60000")
            appendLine("  udp-read-write-timeout: 20000")
            appendLine("  max-session-count: 1024")
            appendLine("  limit-nofile: 65535")
            appendLine("  log-level: warn")
        }
    }

    // ------------------------------------------------- v6.4 exit identity
    /**
     * v6.4 — discover the tunnel's REAL exit IP and the country that IP belongs
     * to, by fetching Cloudflare's `/cdn-cgi/trace` **through the live tunnel**.
     *
     * The brief asks that after connecting, the app shows the IP of the country
     * it actually gave you. v6.3 guessed the country from the SERVER hostname,
     * which is wrong surprisingly often — a node's entry address and its exit
     * address frequently sit in different countries, and many nodes are fronted
     * by a CDN whose address says nothing about the exit. The only correct
     * answer is to ask the internet what it sees, which is precisely what the
     * trace endpoint returns (`ip=…` and `loc=…`, ~200 bytes over Cloudflare —
     * the same edge every probe in v6.4 uses).
     *
     * Runs on a detached daemon thread with retries: right after connect the
     * tunnel may need a moment to settle, and on a weak link the first attempt
     * can miss. It never blocks anything and never fails loudly — if it cannot
     * resolve, the UI simply keeps the hostname-derived guess.
     */
    private fun resolveExitIdentityAsync(server: ServerConfig) {
        thread(name = "exit-ip", isDaemon = true) {
            var attempt = 0
            while (running && !stopping && attempt < 4) {
                attempt++
                try { Thread.sleep(if (attempt == 1) 1200L else 4000L) } catch (_: InterruptedException) { return@thread }
                if (!running || stopping) return@thread
                val body = try { xray.fetchTraceThroughTunnel() } catch (_: Throwable) { null }
                if (body.isNullOrBlank()) continue

                var ip = ""
                var loc = ""
                for (line in body.lineSequence()) {
                    when {
                        line.startsWith("ip=") -> ip = line.substring(3).trim()
                        line.startsWith("loc=") -> loc = line.substring(4).trim().uppercase()
                    }
                }
                if (ip.isBlank() && loc.isBlank()) continue

                // Cache the country against the server host so a reconnect to the
                // same node paints the correct flag instantly, with zero I/O —
                // this is what makes it work even when the internet is weak.
                if (loc.length == 2) {
                    runCatching {
                        com.neonvpn.app.util.CountryFlags.rememberCode(this, server.address, loc)
                    }
                }
                runCatching {
                    com.neonvpn.app.ui.VpnStateBus.updateExit(
                        com.neonvpn.app.ui.ExitIdentity(ip = ip, countryCode = loc)
                    )
                }
                Log.i(TAG, "exit identity: ip=$ip loc=$loc")
                return@thread
            }
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
                val p0 = xray.measureDelayStable()
                if (p0 in 1..8000) lastPing = p0
            } catch (_: Throwable) {}

            // Baseline for the tun2socks native counters (cumulative since the
            // tunnel started). Used as a fallback when the Xray stats API returns
            // 0 (e.g. some core builds don't surface per-outbound counters).
            // v4.9 — the baseline is seeded from the FIRST reading (a sentinel of
            // -1 means "not yet seeded") so it can never mistake a genuine 0 for
            // "unseeded" and lose a real delta.
            var lastTunTx = -1L
            var lastTunRx = -1L

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
                    // v4.9 — always REFRESH the tun baseline every tick (even when
                    // the Xray API DID report bytes) so that if the API later drops
                    // to 0 mid-session the fallback delta is correct and doesn't
                    // spike from a stale baseline.
                    var tunTx = -1L
                    var tunRx = -1L
                    try {
                        val tun = TProxyService.TProxyGetStats()
                        if (tun != null && tun.size >= 2) {
                            tunTx = tun[0].coerceAtLeast(0)   // [0]=tx (up from device)
                            tunRx = tun[1].coerceAtLeast(0)   // [1]=rx (down)
                        }
                    } catch (_: Throwable) {}

                    if (upDelta == 0L && downDelta == 0L && tunTx >= 0 && tunRx >= 0) {
                        if (lastTunTx < 0 || lastTunRx < 0) {
                            lastTunTx = tunTx; lastTunRx = tunRx
                        } else {
                            upDelta = (tunTx - lastTunTx).coerceAtLeast(0)
                            downDelta = (tunRx - lastTunRx).coerceAtLeast(0)
                        }
                    }
                    // keep the baseline current regardless of which source we used
                    if (tunTx >= 0) lastTunTx = tunTx
                    if (tunRx >= 0) lastTunRx = tunRx

                    totalUp += upDelta
                    totalDown += downDelta

                    val now = System.currentTimeMillis()
                    val dt = ((now - lastTs).coerceAtLeast(1)).toDouble() / 1000.0
                    lastTs = now

                    // Live per-second rate from the delta — real numbers, no fakes.
                    val upRate = (upDelta / dt).toLong().coerceAtLeast(0)
                    val downRate = (downDelta / dt).toLong().coerceAtLeast(0)

                    // Refresh ping every ~5s (measureDelay opens a probe connection).
                    // v4.8 — SMOOTHED so the number the user sees is stable instead
                    // of jumping around every refresh. We keep an exponential moving
                    // average of the real measured round-trips; a single noisy sample
                    // only nudges the displayed value rather than replacing it, and a
                    // transient miss (-1) does NOT wipe the last good ping to a dash.
                    // v6.4 — the DISPLAYED ping is now measured exactly the way
                    // the per-config list ping measures it (same Cloudflare
                    // endpoint, cold sample dropped, median of the warm rest),
                    // so "120 in the list" no longer becomes "1000 once
                    // connected". Refreshed every ~8s rather than ~5s because
                    // measureDelayStable takes several round-trips and we do not
                    // want the probe itself competing with the user's traffic.
                    if (tick % 8 == 0) {
                        try {
                            val p = xray.measureDelayStable()
                            if (p in 1..8000) {
                                lastPing = if (lastPing <= 0) p
                                    else ((lastPing * 2 + p) / 3)   // EMA, weight last
                            }
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
            // v6.4 — end-to-end (device-path) stall detection counters.
            var pathChecks = 0
            var pathFailures = 0
            // v4.8 — LONGER grace period (20s). The old 8s grace meant the watchdog
            // started probing while a cold Reality/XTLS tunnel was still stabilising
            // on Iran's disrupted links; a couple of early misses then triggered a
            // needless core re-spin that momentarily black-holed traffic — this is a
            // direct cause of the "works, then drops/stalls ~10-20s after connect"
            // report. Giving the fresh tunnel a full 20s to settle before the first
            // health probe removes that self-inflicted disruption.
            try { Thread.sleep(20000) } catch (_: InterruptedException) { return@thread }

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

                    // v4.8 — confirm a failure with MULTIPLE probes before reacting.
                    // On Iran's flaky links a single dropped probe is extremely
                    // common on a perfectly-working tunnel, so reacting to one (or
                    // even two) misses caused needless core re-spins that briefly
                    // black-holed the user's traffic — the "drops for a moment after
                    // ~10-20s" bug. We now use the FAST single-endpoint probe and
                    // only declare the tunnel unhealthy if THREE probes in a row all
                    // fail (with a short pause between each). A tunnel that answers
                    // even one of three probes is considered alive and left untouched.
                    val coreAlive = try { xray.isRunning } catch (_: Throwable) { false }
                    var health = -1L
                    if (coreAlive) {
                        var miss = 0
                        while (miss < 3) {
                            val d = try { xray.measureDelay() } catch (_: Throwable) { -1L }
                            if (d in 1..8000) { health = d; break }
                            miss++
                            if (miss < 3) { try { Thread.sleep(600) } catch (_: InterruptedException) { break } }
                        }
                    }

                    var healthy = coreAlive && health in 1..8000

                    // ── v6.4 — THE END-TO-END STALL DETECTOR ─────────────────
                    // The bug this exists for: *"it works for a minute, then
                    // Instagram freezes; I toggle the VPN once and it works
                    // again."*
                    //
                    // Every check above only proves the CORE can still dial out.
                    // In the freeze scenario it always could — the core was fine
                    // and the outbound was fine. What had actually broken was the
                    // full device path: TUN → tun2socks → SOCKS5 inbound → core.
                    // hev's session table had filled with parked sockets, so new
                    // flows could no longer be created. `measureDelay` bypasses
                    // that entire path (it dials straight from the core), which
                    // is exactly why the watchdog never noticed and why only a
                    // manual reconnect helped.
                    //
                    // So we now periodically push a REAL request through the
                    // SOCKS5 inbound — the same socket tun2socks feeds — which is
                    // the identical path a user's app takes. If the core is
                    // healthy but that path is dead, the tunnel is silently
                    // frozen: we rebuild the tun2socks bridge in place, which
                    // flushes the stale session table and restores traffic
                    // WITHOUT the user ever touching the connect button. That is
                    // the "toggle off and on" the user was doing by hand, done
                    // automatically in about a second.
                    if (healthy) {
                        pathChecks++
                        if (pathChecks % PATH_CHECK_EVERY == 0) {
                            if (!probeDevicePath()) {
                                pathFailures++
                                Log.w(TAG, "watchdog: core healthy but DEVICE PATH dead " +
                                    "(#$pathFailures) — tunnel is silently frozen")
                                if (pathFailures >= 2) {
                                    pathFailures = 0
                                    healthy = false
                                    restartTun2Socks()
                                    // Give the rebuilt bridge a moment, then
                                    // re-verify before doing anything drastic.
                                    try { Thread.sleep(1200) } catch (_: InterruptedException) { break }
                                    if (probeDevicePath()) {
                                        Log.i(TAG, "watchdog: device path restored by tun2socks restart")
                                        consecutiveFailures = 0
                                        reviveAttempts = 0
                                        continue
                                    }
                                }
                            } else {
                                pathFailures = 0
                            }
                        }
                    }

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
                                val again = try { xray.measureDelay() } catch (_: Throwable) { -1L }
                                if (again in 1..8000) {
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

    // ─────────────────────────── v6.4 device-path probe + self-heal ──────────

    /**
     * v6.4 — prove the FULL device path still carries traffic.
     *
     * Sends a real HTTP request through the **local SOCKS5 inbound** — the exact
     * socket `tun2socks` feeds every app's packets into. This is deliberately
     * different from [XrayManager.measureDelay], which dials straight out of the
     * core and therefore cannot see a wedged tun2socks session table.
     *
     * Cloudflare's zero-byte 204 is the target (same endpoint policy as every
     * other probe in v6.4), so the check costs almost nothing and never competes
     * with the user's bandwidth.
     *
     * @return true when real bytes came back through the device path.
     */
    private fun probeDevicePath(): Boolean {
        return try {
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", XrayConfigBuilder.SOCKS_PORT)
            )
            val conn = (java.net.URL(com.neonvpn.app.config.ProbeEndpoints.PRIMARY)
                .openConnection(proxy) as java.net.HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("User-Agent", "ProfessorVPN/6.4 (Android)")
                setRequestProperty("Connection", "close")
            }
            try {
                val code = conn.responseCode
                // 204 (or any 2xx/3xx) means the whole chain answered.
                code in 200..399
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "device-path probe failed: ${e.message}")
            false
        }
    }

    /**
     * v6.4 — rebuild ONLY the tun2socks bridge, keeping the TUN interface, the
     * Xray core and the user's session fully intact.
     *
     * This is the automatic equivalent of the manual "disconnect + reconnect"
     * the user was performing to un-freeze Instagram — except it takes about a
     * second, the VPN never drops, no permission dialog appears, and the UI
     * never leaves the Connected state. Stopping the native service releases the
     * entire stale session table; starting it again gives us a clean one.
     */
    private fun restartTun2Socks() {
        val fd = try { tunInterface?.fd } catch (_: Throwable) { null } ?: return
        Log.w(TAG, "restarting tun2socks bridge to clear a stalled session table")
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { tunnelThread?.interrupt() } catch (_: Throwable) {}
        tunnelThread = null
        try { Thread.sleep(250) } catch (_: InterruptedException) { return }
        if (!running || stopping) return
        try { startTun2Socks(fd) } catch (e: Throwable) {
            Log.w(TAG, "tun2socks restart failed: ${e.message}")
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

    /** Guards against two overlapping STOP requests racing each other. */
    @Volatile private var stopThread: Thread? = null

    /**
     * v6.3 — STOP BUTTON RELIABILITY FIX.
     *
     * The reported bug: *"sometimes the STOP button doesn't work."*
     *
     * Root cause: [onStartCommand] runs on the MAIN thread, and the old
     * `stopVpn()` called [cleanup] directly from there. [cleanup] performs
     * several BLOCKING native calls — `TProxyService.TProxyStopService()`,
     * `xray.stop()`, closing the TUN fd, thread interrupts + implicit joins.
     * When the tunnel was mid-handshake or the core was wedged on a dead socket
     * those calls can block for many seconds. The result was that:
     *
     *   • the main thread froze, so the UI could not repaint (the button looked
     *     "dead" and taps were swallowed);
     *   • if it blocked past the ANR window, the whole service could be killed
     *     by the system BEFORE the disconnect was broadcast, leaving the UI
     *     stuck on "Connected";
     *   • a second STOP tap re-entered `stopVpn()` and fought the first one.
     *
     * The fix has three parts:
     *
     *   1. **Flip the state FIRST, synchronously.** `stopping = true`,
     *      `running = false` and the DISCONNECTED broadcast all happen before
     *      any blocking work, so the UI reacts to the very first tap instantly
     *      and every loop (watchdog / stats / tunnel) sees the stop flag at once.
     *   2. **Do the teardown on a WORKER thread.** The main thread returns from
     *      `onStartCommand` immediately, so there is no ANR and no frozen UI.
     *   3. **Idempotent + always-terminating.** A second STOP while one is in
     *      flight is a no-op instead of a race, and `stopSelf()` is called from
     *      a `finally` so the service dies even if a native call throws.
     */
    private fun stopVpn() {
        // (1) Make the stop visible IMMEDIATELY — before any blocking work.
        stopping = true
        running = false
        isTunnelUp = false
        emitProgress(0, "Disconnected")
        broadcastState(STATE_DISCONNECTED, "")

        // (3) Idempotent: ignore a second tap while a teardown is in flight.
        if (stopThread?.isAlive == true) return

        // (2) Blocking native teardown OFF the main thread.
        stopThread = thread(name = "vpn-stop", isDaemon = true) {
            try {
                cleanup()
            } catch (e: Throwable) {
                Log.w(TAG, "stopVpn cleanup: ${e.message}")
            } finally {
                // The service MUST die even if a native call misbehaved.
                try { stopForegroundCompat() } catch (_: Throwable) {}
                try { stopSelf() } catch (_: Throwable) {}
            }
        }
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
        // v6.3 — when the STOP path already spawned the worker teardown, do NOT
        // run cleanup() again here: onDestroy is on the MAIN thread and a second
        // pass through the blocking native calls is exactly what used to hang the
        // UI after pressing STOP. The worker thread finishes the job.
        if (stopThread?.isAlive != true) {
            try { cleanup() } catch (e: Throwable) { Log.w(TAG, "onDestroy cleanup: ${e.message}") }
        }
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

        /**
         * v6.4 — run the heavier END-TO-END device-path probe every Nth healthy
         * watchdog tick (~every 35s at the 7s base cadence). Frequent enough to
         * catch a silent freeze long before the user notices a stuck video,
         * cheap enough that it never competes with real traffic.
         */
        private const val PATH_CHECK_EVERY = 5
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
