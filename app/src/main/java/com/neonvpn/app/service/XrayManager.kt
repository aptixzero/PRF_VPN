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

    private var controller: CoreController? = null

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

    fun start(configJson: String): Boolean {
        if (isRunning) return true
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

    fun stop() {
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
    fun measureDelay(url: String = ProbeEndpoints.PRIMARY): Long {
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
            setRequestProperty("User-Agent", "ProfessorVPN/6.4 (Android)")
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
        private val HEALTH_PROBE_URLS = ProbeEndpoints.URLS

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
    }
}
