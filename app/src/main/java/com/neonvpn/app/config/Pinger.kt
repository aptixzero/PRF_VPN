package com.neonvpn.app.config

import android.util.Log
import com.neonvpn.app.service.XrayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * REAL end-to-end reachability test for a single [ServerConfig].
 *
 * v4.7 — THE PING WORKS AGAIN (and it is still 100% real).
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY v4.6 SHOWED NO PING FOR ANY CONFIG (the bug this fixes)
 *   v4.6 required TWO successes on TWO DISTINCT censored endpoints inside a
 *   9-second total budget with 4s per probe. On Iran's high-RTT links the first
 *   endpoint alone can eat 3-4s; a second full round-trip through a SECOND
 *   throwaway Xray core simply never fit the budget — so EVERY config, even a
 *   perfectly working one, came back "unreachable". On top of that the native
 *   `measureOutboundDelay` call is a BLOCKING JNI call that ignores coroutine
 *   cancellation, so the per-probe timeout silently didn't fire and one hung
 *   probe stalled the whole sweep.
 *
 * THE v4.7 RULE (real ping, no Google, realistic for Iran):
 *   • DO NOT test against Google — it is open everywhere and proves nothing.
 *     Every probe endpoint is a genuinely FILTERED target (Cloudflare edge,
 *     Telegram, Instagram) that only a working anti-censorship tunnel reaches.
 *   • ONE confirmed round-trip through the real outbound to a censored endpoint
 *     IS a real, honest ping — it is the same thing v2rayNG shows. We take the
 *     FIRST endpoint that answers and report ITS latency (a real measured
 *     round-trip, never a fake or random number).
 *   • The probe is made truly cancellable: the blocking native call runs in an
 *     `async` job that we await WITH a timeout, so a hung native probe can no
 *     longer freeze the sweep — we abandon it and move on.
 *
 * The probe always travels THROUGH the Xray outbound built by
 * [XrayConfigBuilder.buildPingConfig] — the SAME outbound + stream settings the
 * live connect path uses — so it reflects the real tunnel on any network
 * (Wi-Fi / mobile data / any ISP), never the local link.
 *
 * Returns the measured latency in ms, or [UNREACHABLE] (-1) if the server
 * cannot reach any censored endpoint.
 */
object Pinger {

    private const val TAG = "Pinger"

    const val TESTING = Long.MIN_VALUE
    const val UNREACHABLE = -1L

    /**
     * Hard wall-clock ceiling for the ENTIRE ping of one config (all probe
     * attempts combined). Callers must treat [ping] as already-bounded and must
     * NOT wrap it in a shorter timeout (that was the v4.2 starvation bug).
     */
    const val PER_CONFIG_BUDGET_MS = 12_000L

    /** Per single probe attempt ceiling (one endpoint, one round-trip). */
    private const val PER_PROBE_BUDGET_MS = 5_000L

    /**
     * v4.7 — dedicated scope for the blocking native probe calls. The native
     * `measureOutboundDelay` ignores coroutine cancellation, so each probe runs
     * as an [async] child here and the caller awaits it with a timeout: when the
     * timeout fires we ABANDON the still-blocking call (it finishes and is
     * discarded) instead of letting it stall the sweep. SupervisorJob so an
     * abandoned/failed probe never cancels anything else.
     */
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * v4.7 — CENSORSHIP-GATED probe endpoints (NO Google, ever).
     *
     * Every entry here is a site that a strong filter blocks and that a real
     * working proxy CAN reach. If a node can fetch these it genuinely bypasses
     * censorship, so a green ping is trustworthy — and the number shown is the
     * REAL measured round-trip through the tunnel, exactly what an Iranian user
     * will experience.
     *
     * Ordered FASTEST-FIRST so the common case (a working node) answers on the
     * very first, tiniest probe:
     *   1. Cloudflare edge 204 — a ~0-byte response, the fastest possible real
     *      round-trip; Cloudflare is throttled/filtered on Iranian ISPs, so
     *      reaching it proves the tunnel carries real TLS traffic.
     *   2. Cloudflare trace    — tiny text body, second confirmation candidate.
     *   3. Telegram            — classic blocked target, answers fast.
     *   4. Instagram           — blocked target, last resort.
     */
    private val PROBE_URLS = listOf(
        "https://cp.cloudflare.com/generate_204",        // Cloudflare edge (filtered, tiny 204)
        "https://www.cloudflare.com/cdn-cgi/trace",      // Cloudflare trace (filtered, tiny body)
        "https://core.telegram.org/robots.txt",          // Telegram (blocked target)
        "https://i.instagram.com/favicon.ico"            // Instagram (blocked target)
    )

    /**
     * v4.7 — ONE confirmed censored-endpoint round-trip == reachable. The v4.6
     * two-confirmation rule did not fit the time budget on Iranian links and
     * made EVERY config read "unreachable" (the "no ping at all" bug). A single
     * timed success through the real outbound to a FILTERED endpoint is already
     * a genuine proof of bypass — the same standard v2rayNG applies.
     */
    private const val REQUIRED_CONFIRMATIONS = 1

    /** Latency upper bound for a node we still treat as "reachable". */
    private const val MAX_VALID_MS = 8_000L

    suspend fun ping(cfg: ServerConfig): Long = withContext(Dispatchers.IO) {
        // Only vless / vmess are buildable; anything else is unreachable here.
        if (cfg.protocol != "vless" && cfg.protocol != "vmess") return@withContext UNREACHABLE

        // Build the ping config from the EXACT same outbound + stream settings
        // the real connect path uses, so a green ping == genuinely connects.
        val json = try {
            XrayConfigBuilder.buildPingConfig(cfg)
        } catch (e: Throwable) {
            Log.w(TAG, "buildPingConfig failed: ${e.message}")
            return@withContext UNREACHABLE
        }

        // Whole-config budget guards the sweep even if every probe times out.
        val result = withTimeoutOrNull(PER_CONFIG_BUDGET_MS) {
            val good = ArrayList<Long>(PROBE_URLS.size)

            // Probe censored endpoints one by one, fastest first. We stop the
            // moment we have the required confirmation(s) — for v4.7 that is the
            // FIRST real round-trip that succeeds.
            for (url in PROBE_URLS) {
                val ms = singleProbe(json, url)
                if (ms in 1..MAX_VALID_MS) {
                    good.add(ms)
                    if (good.size >= REQUIRED_CONFIRMATIONS) break
                }
            }

            if (good.size >= REQUIRED_CONFIRMATIONS) median(good) else UNREACHABLE
        }
        result ?: UNREACHABLE
    }

    /** Median of confirmed latencies → a realistic (not lucky best-case) number. */
    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return UNREACHABLE
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /**
     * One hard-wall-clock-capped proxied round-trip through [json] to [url].
     *
     * v4.7 — TRULY cancellable. The native measure call blocks and ignores
     * cancellation, so it runs as an [async] child of [probeScope] and we await
     * it with a timeout. When the timeout fires, `await()` is cancelled (await
     * IS cancellable even though the native call isn't) and we return -1
     * immediately — the abandoned native call finishes in the background and is
     * discarded. This is what un-freezes the sweep that v4.6 stalled.
     */
    private suspend fun singleProbe(json: String, url: String): Long {
        val deferred = probeScope.async {
            try {
                XrayManager.measureConfigDelay(json, url)
            } catch (e: Throwable) {
                Log.w(TAG, "core delay error: ${e.message}")
                -1L
            }
        }
        return withTimeoutOrNull(PER_PROBE_BUDGET_MS) { deferred.await() }
            ?: run { deferred.cancel(); -1L }
    }
}
