package com.neonvpn.app.service

import android.content.Context
import android.util.Log
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

    /** Round-trip delay test through the running core (ms), -1 on error.
     *  v4.4 — probes a CENSORED edge (Cloudflare), NOT Google. Google is open on
     *  every ISP so it can't tell a working tunnel from a broken one; Cloudflare's
     *  edge is filtered on many strong-filter ISPs, so a healthy reading here
     *  genuinely means the tunnel is still bypassing censorship. */
    fun measureDelay(url: String = "https://cp.cloudflare.com/generate_204"): Long {
        return try {
            controller?.measureDelay(url) ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * v4.7 — LIVE-CORE health probe, aligned with the repaired [Pinger] rule:
     * ONE real round-trip through the live core to a CENSORED endpoint (never
     * Google) proves the tunnel genuinely carries blocked traffic.
     *
     * Why one (not two): the v4.5/v4.6 "two independent censored endpoints"
     * requirement was the direct cause of the "nothing connects" bug — on
     * Iran's high-RTT, disrupted links a SECOND full round-trip very often
     * cannot complete inside the health window even on a perfectly working
     * node, so the connect path rejected every server and the watchdog tore
     * live sessions down. One confirmed round-trip to a filtered endpoint is
     * already an honest proof of bypass — and it exactly matches what the
     * per-config ping now reports, so "pings → connects" stays true.
     *
     * Returns the measured latency in ms, or -1 if no censored endpoint answers.
     */
    fun confirmedHealth(): Long {
        val c = controller ?: return -1
        for (url in HEALTH_PROBE_URLS) {
            val d = try { c.measureDelay(url) } catch (_: Throwable) { -1L }
            if (d in 1..8000) return d
        }
        return -1
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
         * v4.7 — censored-edge probe set the LIVE-core health check uses. These
         * MUST mirror Pinger.PROBE_URLS so the connect/watchdog verdict matches
         * exactly what the per-config ping reported (the "ping == connects" rule).
         * NO Google here — Google is open on every ISP and proves nothing.
         */
        private val HEALTH_PROBE_URLS = listOf(
            "https://cp.cloudflare.com/generate_204",        // Cloudflare edge (filtered, tiny 204)
            "https://www.cloudflare.com/cdn-cgi/trace",      // Cloudflare trace (filtered, tiny body)
            "https://core.telegram.org/robots.txt",          // Telegram (blocked target)
            "https://i.instagram.com/favicon.ico"            // Instagram (blocked target)
        )

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
        fun measureConfigDelay(configJson: String, url: String = "https://cp.cloudflare.com/generate_204"): Long {
            return try {
                Libv2ray.measureOutboundDelay(configJson, url)
            } catch (e: Throwable) {
                Log.w(TAG, "measureConfigDelay failed: ${e.message}")
                -1
            }
        }
    }
}
