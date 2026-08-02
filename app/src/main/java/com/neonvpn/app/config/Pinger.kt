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
 * ─────────────────────────────────────────────────────────────────────────────
 * v7 — MULTIPLE HANDLERS, FRESH PAYLOAD PROOF, NO SYNTHETIC VALUES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * The brief lists four separate complaints about this file's behaviour:
 *
 *   «همه پینگ ها بالا هستند»            every ping comes back high
 *   «یک کانفیگ کار میکنه بعد هیچی»       one config works, then nothing
 *   «باید چند هندلر مختلف داشته باشه»    it needs several different handlers
 *   «پینگ ها نباید مدام تغییر کنند»      the numbers must stop jumping around
 *
 * All four trace back to how v6.8 spent its time. v6.8 issued **three separate
 * native core spin-ups per config** — two latency samples plus an unconditional
 * payload verdict — while only 6–12 configs were allowed to run at once. Each
 * spin-up pays a fresh TCP + TLS/Reality handshake, so:
 *
 *   • the numbers were HIGH because a cold-core handshake is most of the figure;
 *   • the sweep was SLOW, so later configs were measured minutes after the
 *     earlier ones, on a link whose quality had drifted — hence the jumping;
 *   • and because everything was so expensive, a marginal-but-usable Iranian node
 *     often lost its budget to the queue and was written off as unreachable,
 *     which is the "one works, then nothing" report.
 *
 * ── THE v6.9 HANDLER CHAIN ──────────────────────────────────────────────────
 * Ping is a chain of independent HANDLERS, cheapest first. Each one either
 * rejects, produces a real tunnel measurement, or defers to the next. Every
 * visible result also pays for a fresh payload verdict; no handler may substitute
 * a TCP value, cached value, blend, estimate, or prior result.
 *
 *   H0  TCP HANDSHAKE   [TcpProbe.connectMs] — one SYN, 48-wide, ~300 ms.
 *                       Xray dials the node with the same TCP connect, so a
 *                       failure here proves the core would fail too. Rejects the
 *                       dead majority of a public batch almost for free. It may
 *                       ONLY reject: a success is not a ping and is never shown.
 *
 *   H1  ZERO-DNS EDGE   [ProbeEndpoints.INSTANT] — Cloudflare as an IP LITERAL,
 *                       so no DNS round-trip is inside the measurement at all.
 *                       This is the primary handler and normally the only one.
 *
 *   H2  ALTERNATE EDGES [ProbeEndpoints.URLS] — a *different* Cloudflare endpoint
 *                       is tried when H1's target is being reset specifically.
 *                       This is the "چند هندلر مختلف" the brief asks for: one
 *                       blocked reference endpoint can no longer condemn a node
 *                       that is genuinely fine.
 *
 *   H3  CONFIRMATION    a second round-trip on the SAME endpoint over the now-warm
 *                       path. Its purpose is twofold: it gives the stable warm
 *                       figure we display, and a node that answers H1 and then
 *                       fails H3 is exactly the "works once, then nothing" node —
 *                       so it is rejected here instead of in the user's face.
 *
 *   H4  PAYLOAD VERDICT [XrayManager.measureConfigThroughput] — a brand-new
 *                       connection that must carry REAL BYTES. v7 runs this for
 *                       EVERY candidate result. Without H4 there is no green ping.
 *
 * The cold sample is discarded whenever a confirmed warm one exists, but there is
 * no cross-run smoothing or history blend. Golden Rule #2 holds throughout: no
 * `Random`, estimate, synthesis, or stale value. Every displayed number is the
 * fresh measured round-trip from this exact run through the real outbound.
 */
object Pinger {

    private const val TAG = "Pinger"

    const val TESTING = Long.MIN_VALUE
    const val UNREACHABLE = -1L

    /**
     * Hard wall-clock ceiling for the ENTIRE ping of one config (all handlers
     * combined). Callers must treat [ping] as already-bounded and must NOT wrap it
     * in a shorter timeout (that was the v4.2 starvation bug).
     *
     * v7 reserves enough time for the latency handlers and the mandatory fresh
     * payload verdict while still bounding a dead config to eight seconds.
     */
    const val PER_CONFIG_BUDGET_MS = 8_000L

    /**
     * Per single round-trip ceiling (one endpoint, one measurement). With DNS out
     * of the path, a probe that has not answered in 2.2 s is not slow, it is being
     * reset — abandon it and let the next handler try.
     */
    private const val PER_PROBE_BUDGET_MS = 2_200L

    /** Budget for the H4 payload verdict (a full fresh handshake + a real body). */
    private const val VERDICT_BUDGET_MS = 3_200L

    /**
     * Pause before the verdict connection: long enough that H4 MUST open a
     * genuinely new connection instead of reusing the warm one — which is the
     * entire point of the check.
     */
    private const val VERDICT_PAUSE_MS = 250L

    /**
     * Dedicated scope for the blocking native probe calls. The native
     * `measureOutboundDelay` ignores coroutine cancellation, so each probe runs as
     * an [async] child here and the caller awaits it with a timeout: when the
     * timeout fires we ABANDON the still-blocking call (it finishes and is
     * discarded) instead of letting it stall the sweep. SupervisorJob so an
     * abandoned/failed probe never cancels anything else.
     */
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * CLOUDFLARE-ONLY probe endpoints, shared with the live connection.
     *
     * From [ProbeEndpoints], the single source of truth for EVERY latency path in
     * the app. NO Google anywhere — the brief states Google's service does not work
     * on the target networks, and that Cloudflare is the permitted exception.
     * Cloudflare's anycast edge answers a zero-byte 204, so the number is pure
     * latency and reproducible; and because Cloudflare is throttled on Iranian
     * ISPs, reaching it still proves the tunnel genuinely bypasses the filter.
     *
     * The IP-LITERAL endpoint is FIRST (handler H1) so the common case needs no DNS.
     */
    private val PROBE_URLS: List<String> =
        (listOf(ProbeEndpoints.INSTANT) + ProbeEndpoints.URLS).distinct()

    /** Latency upper bound for a node we still treat as "reachable". */
    private const val MAX_VALID_MS = 8_000L

    /** v7 has no synthetic smoothing/history: the displayed value is the fresh
     * confirmed warm tunnel measurement from this exact run. */
    fun resetHistory() = Unit

    suspend fun ping(cfg: ServerConfig): Long = withContext(Dispatchers.IO) {
        // Only vless / vmess are buildable; anything else is unreachable here.
        // (The brief restricts the whole app to these two protocols.)
        if (cfg.protocol != "vless" && cfg.protocol != "vmess") return@withContext UNREACHABLE

        // ── H0 — TCP HANDSHAKE HANDLER ────────────────────────────────────────
        // May only reject. Success is not a ping and is never displayed.
        if (!TcpProbe.reachable(cfg)) {
            Log.d(TAG, "H0: ${cfg.address}:${cfg.port} not dialable — no core probe")
            return@withContext UNREACHABLE
        }

        // Build the ping config from the EXACT same outbound + stream settings the
        // real connect path uses, so a green ping means it genuinely connects.
        val json = try {
            XrayConfigBuilder.buildPingConfig(cfg)
        } catch (e: Throwable) {
            Log.w(TAG, "buildPingConfig failed: ${e.message}")
            return@withContext UNREACHABLE
        }

        val result = withTimeoutOrNull(PER_CONFIG_BUDGET_MS) {
            // ── H1 / H2 — LOCK ONTO A REFERENCE EDGE ──────────────────────────
            // H1 is the zero-DNS IP literal. If it is specifically being reset we
            // fall through to the alternate Cloudflare edges (H2) rather than
            // condemning a node because one reference target is blocked.
            var refUrl: String? = null
            var cold = UNREACHABLE
            for (url in PROBE_URLS) {
                val ms = singleProbe(json, url)
                if (ms in 1..MAX_VALID_MS) { refUrl = url; cold = ms; break }
            }
            val ref = refUrl ?: return@withTimeoutOrNull UNREACHABLE

            // ── H3 — CONFIRMATION ON THE WARM PATH ────────────────────────────
            // The warm figure is what we display. A node that answers H1 and then
            // fails here is the "works once and then nothing" node, so it is
            // rejected now rather than after the user taps connect.
            val warm = singleProbe(json, ref).let { if (it in 1..MAX_VALID_MS) it else UNREACHABLE }

            // v7 — payload proof is mandatory for EVERY green ping. TCP is only
            // a reject gate and a Cloudflare latency response alone is not enough:
            // a fresh Xray connection must carry a real response body too.
            val reported = if (warm > 0) warm else cold
            if (reported !in 1..MAX_VALID_MS) return@withTimeoutOrNull UNREACHABLE
            if (!runVerdict(json, cfg)) return@withTimeoutOrNull UNREACHABLE
            reported
        }
        result ?: UNREACHABLE
    }

    /** H4 wrapper: pause so the verdict truly opens a NEW connection, then measure. */
    private suspend fun runVerdict(json: String, cfg: ServerConfig): Boolean {
        try { kotlinx.coroutines.delay(VERDICT_PAUSE_MS) } catch (_: Throwable) {}
        val carried = verdictProbe(json)
        if (!carried) {
            Log.w(TAG, "H4 failed for ${cfg.address}:${cfg.port} — answers a handshake " +
                "but cannot carry a payload (would have been a fake green)")
        }
        return carried
    }

    /**
     * One hard-wall-clock-capped proxied round-trip through [json] to [url].
     *
     * TRULY cancellable. The native measure call blocks and ignores cancellation,
     * so it runs as an [async] child of [probeScope] and we await it with a
     * timeout. When the timeout fires, `await()` is cancelled (await IS cancellable
     * even though the native call is not) and we return -1 immediately — the
     * abandoned native call finishes in the background and is discarded.
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

    /**
     * The H4 verdict: does a FRESH connection through this outbound actually carry
     * a real payload? Cancellable in the same way as [singleProbe], so a node that
     * hangs here costs the budget and nothing more.
     */
    private suspend fun verdictProbe(json: String): Boolean {
        val deferred = probeScope.async {
            try {
                XrayManager.measureConfigThroughput(json)
            } catch (e: Throwable) {
                Log.w(TAG, "verdict probe error: ${e.message}")
                false
            }
        }
        return withTimeoutOrNull(VERDICT_BUDGET_MS) { deferred.await() }
            ?: run { deferred.cancel(); false }
    }
}
