package com.neonvpn.app.service

import android.content.Context
import android.util.Log
import com.neonvpn.app.config.ProbeEndpoints
import com.neonvpn.app.config.XrayConfigBuilder
import libv2ray.CoreController
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import java.io.File

/**
 * Thin wrapper around the real Xray-core engine (libv2ray.aar).
 *
 * Verified API (from libv2ray.aar classes.jar):
 *   Libv2ray.initCoreEnv(String, String): void
 *   Libv2ray.checkVersionX(): String
 *   Libv2ray.newCoreController(CoreCallbackHandler): CoreController
 *   CoreController.startLoop(String, int): void   // throws on failure
 *   CoreController.stopLoop(): void
 *   CoreController.measureDelay(String): long
 *   CoreController.queryStats(String tag, String link): long
 *   CoreController.isRunning (getIsRunning): boolean
 */
class XrayManager(private val context: Context) {

    @Volatile private var controller: CoreController? = null

    /** v6.5 — serialises start/stop so sessions can never overlap. */
    private val lifecycleLock = Any()

    @Volatile var isRunning: Boolean = false
        private set

    fun init() {
        // Idempotent — safe to call from the splash screen AND the VPN service.
        synchronized(initLock) {
            if (initialized) return
            try {
                val assetDir = context.filesDir.absolutePath
                extractAsset("geoip.dat")
                extractAsset("geosite.dat")
                Libv2ray.initCoreEnv(assetDir, "")
                cachedVersion = safeVersion()
                initialized = true
                Log.i(TAG, "Xray version: $cachedVersion")
            } catch (e: Throwable) {
                Log.e(TAG, "init failed: ${e.message}", e)
            }
        }
    }

    private fun safeVersion(): String = try {
        Libv2ray.checkVersionX()
    } catch (_: Throwable) {
        "?"
    }

    /**
     * v6.5 — the CONFIG-SWITCH fix.
     *
     * v6.4 opened with `if (isRunning) return true`. That single line is the
     * reason switching to a different config without killing the app did
     * nothing: the service asked the core to start the NEW json, the core said
     * "already running" and happily kept serving the OLD outbound — or, once
     * the previous session had half-torn-down, kept a dead controller. From
     * v6.5 a start ALWAYS lands on a freshly created controller, and any
     * previous one is stopped and drained first (the local SOCKS/api ports must
     * be free before the new core can bind them, otherwise startLoop throws
     * "address already in use" and the connect fails silently).
     *
     * Serialised on [lifecycleLock] so a stop can never interleave a start.
     */
    fun start(configJson: String): Boolean {
        synchronized(lifecycleLock) {
            // Always begin from a clean slate — never reuse a live controller.
            if (controller != null || isRunning) {
                Log.i(TAG, "start(): previous core still present, stopping it first")
                stopLocked()
            }
            // Give the OS a moment to release the local listener sockets
            // (10808/10809) the old core owned. Without this the new core can
            // fail to bind and the user sees "connect does nothing".
            waitForLocalPortsFree()
            return try {
                val handler = object : CoreCallbackHandler {
                    override fun startup(): Long = 0L
                    override fun shutdown(): Long = 0L
                    override fun onEmitStatus(l: Long, s: String?): Long = 0L
                }
                val c = Libv2ray.newCoreController(handler)
                // mode 1 == run with the supplied config json. startLoop is void and
                // throws if the core can't parse / bind, so a clean return == started.
                c.startLoop(configJson, 1)
                controller = c
                isRunning = try { c.isRunning } catch (_: Throwable) { true }
                totalUp = 0L
                totalDown = 0L
                Log.i(TAG, "Xray core started, running=$isRunning")
                isRunning
            } catch (e: Throwable) {
                Log.e(TAG, "start failed: ${e.message}", e)
                isRunning = false
                try { controller?.stopLoop() } catch (_: Throwable) {}
                controller = null
                false
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) { stopLocked() }
    }

    private fun stopLocked() {
        try {
            controller?.stopLoop()
        } catch (e: Throwable) {
            Log.e(TAG, "stop failed: ${e.message}", e)
        } finally {
            controller = null
            isRunning = false
            totalUp = 0L
            totalDown = 0L
        }
    }

    /**
     * v6.5 — blocks (briefly) until the core's local inbound ports are actually
     * free again. `stopLoop()` returns before the listener sockets are reaped,
     * so a fast reconnect used to hit EADDRINUSE. We probe rather than sleep a
     * fixed amount, so a healthy device reconnects in ~50 ms instead of always
     * paying a worst-case delay.
     */
    private fun waitForLocalPortsFree(timeoutMs: Long = 3000) {
        val ports = intArrayOf(XrayConfigBuilder.SOCKS_PORT, XrayConfigBuilder.API_PORT)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ports.all { isPortFree(it) }) return
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
        }
        Log.w(TAG, "local ports still busy after ${timeoutMs}ms — starting anyway")
    }

    private fun isPortFree(port: Int): Boolean = try {
        java.net.ServerSocket().use { s ->
            s.reuseAddress = true
            s.bind(java.net.InetSocketAddress("127.0.0.1", port))
            true
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Live traffic counters for the proxy outbound, in bytes.
     * Returns Pair(uplinkBytes, downlinkBytes). These are *cumulative* since the
     * core started; the caller computes a delta to get a per-second rate.
     */
    fun queryTraffic(): Pair<Long, Long> {
        val c = controller ?: return totalUp to totalDown
        return try {
            // libv2ray's queryStats RESETS the counter on each read (it returns
            // the bytes accumulated *since the previous query*). We therefore add
            // each reading to a running total to get true cumulative bytes — this
            // is exactly how v2rayNG drives its traffic meter, and is what fixes
            // the "download/upload always 0" bug.
            val up = c.queryStats("outbound>>>${XrayConfigBuilder.PROXY_TAG}>>>traffic>>>uplink", "")
                .coerceAtLeast(0)
            val down = c.queryStats("outbound>>>${XrayConfigBuilder.PROXY_TAG}>>>traffic>>>downlink", "")
                .coerceAtLeast(0)
            totalUp += up
            totalDown += down
            totalUp to totalDown
        } catch (_: Throwable) {
            totalUp to totalDown
        }
    }

    /** Bytes that flowed since the last [queryTraffic] call (per-tick delta). */
    fun queryTrafficDelta(): Pair<Long, Long> {
        val c = controller ?: return 0L to 0L
        return try {
            val up = c.queryStats("outbound>>>${XrayConfigBuilder.PROXY_TAG}>>>traffic>>>uplink", "")
                .coerceAtLeast(0)
            val down = c.queryStats("outbound>>>${XrayConfigBuilder.PROXY_TAG}>>>traffic>>>downlink", "")
                .coerceAtLeast(0)
            totalUp += up
            totalDown += down
            up to down
        } catch (_: Throwable) {
            0L to 0L
        }
    }

    @Volatile private var totalUp = 0L
    @Volatile private var totalDown = 0L

    /**
     * FAST liveness probe through the running core (ms), -1 on error.
     *
     * v6.4 — CLOUDFLARE ONLY. Previously this defaulted to Google
     * (`gstatic.com/generate_204`), while the per-config list ping used
     * Cloudflare. Two different edges = two different numbers, which is exactly
     * why a config advertising 120 ms displayed ~1000 ms once connected. Every
     * probe in the app now goes to the same Cloudflare endpoints
     * ([ProbeEndpoints.URLS]).
     *
     * This variant answers "is the tunnel alive right now?" as cheaply as
     * possible (first endpoint that responds wins) and is what the health check
     * and the watchdog use. For the number the USER SEES, use [measureDelayStable].
     */
    fun measureDelay(url: String = ProbeEndpoints.INSTANT): Long {
        val c = controller ?: return -1
        val d0 = try { c.measureDelay(url) } catch (_: Throwable) { -1L }
        if (d0 in 1..15000) return d0
        for (u in HEALTH_PROBE_URLS) {
            if (u == url) continue
            val d = try { c.measureDelay(u) } catch (_: Throwable) { -1L }
            if (d in 1..15000) return d
        }
        return -1
    }

    /**
     * v6.6 — the ZERO-DNS liveness probe used by the connect gate.
     *
     * This is the measurement that removed the reported «۳۰ ثانیه صبر کردن».
     * [measureDelay]'s old default was a NAMED host, so the very first probe
     * after a connect had to resolve it — and on a cold tunnel that resolution is
     * itself a full DoH round-trip through the brand-new outbound, commonly 2-4 s
     * and up to 10 s when shaped. The gate paid that before it was allowed to say
     * "Connected", and if it timed out it paid it AGAIN on the next attempt.
     *
     * Asking an IP literal removes the resolver from the path entirely, so the
     * first proof of life typically lands in a few hundred milliseconds. It is
     * the same real round-trip through the same live outbound — only the DNS step
     * is gone.
     */
    fun measureDelayInstant(): Long {
        val c = controller ?: return -1
        val d = try { c.measureDelay(ProbeEndpoints.INSTANT) } catch (_: Throwable) { -1L }
        return if (d in 1..15000) d else -1
    }

    /**
     * v6.4 — the DISPLAYED ping, measured exactly the way [com.neonvpn.app.config.Pinger]
     * measures the list ping so the two numbers agree.
     *
     * The rule (identical on both sides):
     *   • the same Cloudflare reference endpoint,
     *   • several consecutive round-trips,
     *   • the FIRST (cold) sample discarded, median of the warm rest reported.
     *
     * Without this, the live figure was a single cold sample taken while the
     * tunnel was busy carrying the user's video traffic — the single worst
     * possible measurement, and the direct source of the "120 → 1000" jump.
     *
     * Returns -1 when the tunnel genuinely cannot reach the endpoint.
     */
    fun measureDelayStable(samples: Int = 3): Long {
        val c = controller ?: return -1
        // Lock onto the first Cloudflare endpoint that answers at all, then take
        // every sample against THAT one so the numbers are comparable.
        var ref: String? = null
        val got = ArrayList<Long>(samples + 1)
        for (u in HEALTH_PROBE_URLS) {
            val d = try { c.measureDelay(u) } catch (_: Throwable) { -1L }
            if (d in 1..15000) { ref = u; got.add(d); break }
        }
        val refUrl = ref ?: return -1
        var fails = 0
        while (got.size < samples + 1 && fails < 2) {
            val d = try { c.measureDelay(refUrl) } catch (_: Throwable) { -1L }
            if (d in 1..15000) got.add(d) else fails++
        }
        if (got.isEmpty()) return -1
        // Drop the cold first sample when we have enough warm ones.
        val warm = if (got.size >= 3) got.drop(1) else got
        val sorted = warm.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /**
     * v6.4 — fetch the Cloudflare `/cdn-cgi/trace` body THROUGH the live tunnel.
     *
     * Used to learn the real EGRESS IP and its country after connecting, so the
     * UI can show the IP the internet actually sees and the flag of the country
     * that IP belongs to (instead of guessing from the server hostname).
     *
     * Runs over the core's own SOCKS inbound, on a caller-supplied background
     * thread. Returns null when it cannot be fetched.
     */
    fun fetchTraceThroughTunnel(): String? {
        for (url in ProbeEndpoints.TRACE_URLS) {
            val body = runCatching { socksGet(url) }.getOrNull()
            if (!body.isNullOrBlank() && body.contains("ip=")) return body
        }
        return null
    }

    private fun socksGet(urlStr: String): String? {
        val proxy = java.net.Proxy(
            java.net.Proxy.Type.SOCKS,
            java.net.InetSocketAddress("127.0.0.1", XrayConfigBuilder.SOCKS_PORT)
        )
        val conn = (java.net.URL(urlStr).openConnection(proxy) as java.net.HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ProfessorVPN/6.8 (Android)")
            setRequestProperty("Accept", "*/*")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun extractAsset(name: String) {
        val outFile = File(context.filesDir, name)
        if (outFile.exists() && outFile.length() > 0) return
        try {
            context.assets.open(name).use { input ->
                outFile.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "asset $name not bundled separately (may be inside aar): ${e.message}")
        }
    }

    companion object {
        private const val TAG = "XrayManager"

        /**
         * v6.4 — the live-core probe set is now EXACTLY the same Cloudflare-only
         * list the per-config ping uses ([ProbeEndpoints.URLS]). v6.3 listed
         * Google first here, which made the connected ping describe a completely
         * different route from the one the list measured — the "120 in the list,
         * 1000 after connecting" bug. NO Google anywhere in the app.
         */
        // v6.6 — the zero-DNS IP-literal endpoint is tried FIRST everywhere, so no
        // probe in the app ever pays a resolver round-trip in the common case.
        private val HEALTH_PROBE_URLS: List<String> =
            (listOf(ProbeEndpoints.INSTANT) + ProbeEndpoints.URLS).distinct()

        private val initLock = Any()
        @Volatile private var initialized = false
        @Volatile private var cachedVersion: String = "?"

        /** Returns the Xray core version captured during init() (or "?"). */
        fun cachedVersion(): String = cachedVersion

        /**
         * Static delay measurement for a single outbound config WITHOUT bringing
         * the whole VPN up — used by the per-config "ping" buttons in the UI.
         * Builds a minimal full config that routes through the given outbound and
         * times a request to a generate_204 endpoint.
         */
        fun measureConfigDelay(configJson: String, url: String = ProbeEndpoints.PRIMARY): Long {
            return try {
                Libv2ray.measureOutboundDelay(configJson, url)
            } catch (e: Throwable) {
                Log.w(TAG, "measureConfigDelay failed: ${e.message}")
                -1
            }
        }

        /**
         * v6.6 — THE PAYLOAD VERDICT. The measurement that makes
         * *"if it pings, it connects"* structurally true instead of merely hoped
         * for.
         *
         * ─────────────────────────────────────────────────────────────────────
         * WHY A LATENCY PROBE IS NOT ENOUGH (the «پینگ فیک» bug)
         * ─────────────────────────────────────────────────────────────────────
         * [measureConfigDelay] against a zero-byte 204 answers a very narrow
         * question: "did ONE tiny request complete?" On a filtered link that is
         * routinely YES for a node that is nonetheless useless, because Iranian
         * DPI typically admits the first handshake of a flow and resets the
         * *next* one, and because a node at its connection limit accepts one
         * trivial request and then stops. Those nodes are exactly the ones that
         * showed a healthy green number and then failed the moment the user
         * tapped connect.
         *
         * ─────────────────────────────────────────────────────────────────────
         * WHAT THIS DOES DIFFERENTLY
         * ─────────────────────────────────────────────────────────────────────
         * Every `measureOutboundDelay` call constructs its OWN core instance, so
         * calling it here necessarily opens a **brand-new connection** — it
         * cannot ride the warm path the latency samples shared. And the URLs
         * below return a **real response body**, which the core reads to
         * completion before returning a delay. So a success here proves two
         * things at once, and they are the same two things the live connect path
         * requires:
         *
         *   1. the node completed a FRESH handshake through DPI, and
         *   2. actual payload bytes traversed the tunnel.
         *
         * The endpoints are ordered zero-DNS first ([ProbeEndpoints.INSTANT], an
         * IP literal whose `/cdn-cgi/trace` body is a few hundred real bytes),
         * then the Cloudflare speed edge with an explicit byte count for a
         * heavier confirmation when a name lookup is affordable.
         *
         * @return true when real response bytes came back over a fresh connection.
         */
        fun measureConfigThroughput(configJson: String): Boolean {
            // v6.8 — ONE lightweight probe, not a walk of up to three heavy ones.
            //
            // WHY THIS IS THE BIGGEST SPEED FIX IN v6.8: every call to
            // `measureOutboundDelay` constructs its OWN throwaway native Xray core
            // (tens of MB). v6.6 looped over three payload URLs here, so a node
            // that only answered the LAST of them paid THREE full core spin-ups —
            // on top of the two latency samples — for a single config. With the
            // deep-probe gate only 6–8 wide, that is exactly where the "بالای ۵
            // دقیقه" wait came from.
            //
            // The zero-DNS IP-literal endpoint already returns a REAL response
            // body (~350 B of `/cdn-cgi/trace`), so completing it proves both of
            // the things the verdict must prove — a fresh handshake through DPI
            // AND real payload bytes moving — in one core, with no DNS step. That
            // is the whole guarantee; the extra endpoints only ever added cost.
            val d = try {
                Libv2ray.measureOutboundDelay(configJson, ProbeEndpoints.INSTANT)
            } catch (e: Throwable) {
                Log.w(TAG, "throughput probe failed: ${e.message}")
                -1L
            }
            // A valid delay means the core completed the request AND read the
            // response body — i.e. real bytes moved through the outbound.
            return d in 1..10_000
        }
    }
}
