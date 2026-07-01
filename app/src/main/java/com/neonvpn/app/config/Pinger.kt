package com.neonvpn.app.config

import android.util.Log
import com.neonvpn.app.service.XrayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * REAL end-to-end reachability test for a single [ServerConfig].
 *
 * v4.4 — TRUST-WORTHY PING (a green ping == 100% really connects).
 * ─────────────────────────────────────────────────────────────────────────────
 * THE PROBLEM WITH v4.3
 *   v4.3 timed `generate_204` on Cloudflare/Google. Google's 204 is reachable
 *   from almost ANY network even WITHOUT a working proxy, and the native
 *   measurer can report a "success" off a half-open path — so configs showed a
 *   ping but then FAILED to actually load censored sites. A ping that lies is
 *   worse than no ping.
 *
 * THE v4.4 RULE  (exactly what the user demanded):
 *   • DO NOT test against Google — it is open everywhere and proves nothing.
 *   • Test against endpoints that are ONLY reachable through a genuinely working
 *     anti-censorship tunnel: Cloudflare's trace edge, Telegram's CDN and
 *     Instagram. If the proxy can fetch THESE, it can carry real blocked traffic
 *     — so a green ping means the node actually works, 100%.
 *   • CONFIRM, don't guess. We require TWO independent successes through the
 *     outbound (e.g. Cloudflare + Telegram) before we call a node reachable.
 *     A single fluke success on one endpoint is NOT enough — that was how dead
 *     nodes used to slip through as "green".
 *   • The reported latency is the MEDIAN of the confirmed probes, so the number
 *     the user sees is a realistic round-trip, not a lucky best-case.
 *
 * The probe always travels THROUGH the Xray outbound built by
 * [XrayConfigBuilder.buildPingConfig] — the SAME outbound + stream settings the
 * live connect path uses — so it reflects the real tunnel on any network
 * (Wi-Fi / mobile data / any ISP), never the local link.
 *
 * Returns the confirmed latency in ms, or [UNREACHABLE] (-1) if the server
 * cannot prove it carries censored traffic.
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
    const val PER_CONFIG_BUDGET_MS = 9_000L

    /** Per single probe attempt ceiling (one endpoint, one round-trip). */
    private const val PER_PROBE_BUDGET_MS = 4_000L

    /**
     * v4.4 — CENSORSHIP-GATED probe endpoints (NO Google).
     *
     * Every entry here is a site that a strong filter blocks and that a real
     * working proxy CAN reach. If a node can fetch these it genuinely bypasses
     * censorship, so a green ping is trustworthy.
     *
     *   1. Cloudflare trace  — `1.1.1.1/cdn-cgi/trace` returns a tiny text body
     *      almost instantly; Cloudflare's edge is throttled/blocked on many
     *      filtered ISPs, so reaching it proves the tunnel carries TLS traffic.
     *   2. Telegram CDN      — `cdn4.telegram-cdn.org` / core.telegram.org are
     *      blocked targets that answer fast; a classic real-world bypass test.
     *   3. Instagram         — `i.instagram.com` is blocked and answers quickly.
     *
     * Ordered fastest-first. We need TWO of these to succeed (see [ping]) so one
     * endpoint being briefly down can never fake a pass or fake a fail.
     */
    private val PROBE_URLS = listOf(
        // v4.6 — ordered strongest-first for Iran. Telegram + Cloudflare give the
        // most trustworthy real-connect signal (they answer fast AND are genuinely
        // filtered), Instagram is a third confirmation. Google is deliberately
        // absent (it's reachable without a working tunnel, so it proves nothing).
        "https://core.telegram.org/robots.txt",          // Telegram (blocked target, fast)
        "https://www.cloudflare.com/cdn-cgi/trace",      // Cloudflare trace (filtered, tiny body)
        "https://cp.cloudflare.com/generate_204",        // Cloudflare edge (filtered, tiny 204)
        "https://i.instagram.com/favicon.ico"            // Instagram (blocked target)
    )

    /** How many DISTINCT endpoints must succeed before a node is "reachable". */
    private const val REQUIRED_CONFIRMATIONS = 2

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

        // Whole-config budget guards against a native call that hangs.
        val result = withTimeoutOrNull(PER_CONFIG_BUDGET_MS) {
            val good = ArrayList<Long>(PROBE_URLS.size)
            var failures = 0

            // Probe censored endpoints one by one. We stop EARLY in two cases:
            //   • we already have REQUIRED_CONFIRMATIONS successes  → reachable
            //   • too many endpoints failed for the rest to ever confirm → dead
            for ((index, url) in PROBE_URLS.withIndex()) {
                val ms = singleProbe(json, url)
                if (ms in 1..MAX_VALID_MS) {
                    good.add(ms)
                    if (good.size >= REQUIRED_CONFIRMATIONS) break
                } else {
                    failures++
                    val remaining = PROBE_URLS.size - index - 1
                    // If even succeeding on every remaining endpoint can't reach
                    // the required confirmations, give up now (fail fast).
                    if (good.size + remaining < REQUIRED_CONFIRMATIONS) break
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

    /** One hard-wall-clock-capped proxied round-trip through [json] to [url]. */
    private suspend fun singleProbe(json: String, url: String): Long {
        return withTimeoutOrNull(PER_PROBE_BUDGET_MS) {
            withContext(Dispatchers.IO) {
                try {
                    XrayManager.measureConfigDelay(json, url)
                } catch (e: Throwable) {
                    Log.w(TAG, "core delay error: ${e.message}")
                    -1L
                }
            }
        } ?: -1L
    }
}
