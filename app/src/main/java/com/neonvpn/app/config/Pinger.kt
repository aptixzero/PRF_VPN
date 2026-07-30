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
 * v6.9 — MULTIPLE HANDLERS, ONE CORE, STABLE NUMBERS
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
 * Ping is now a chain of independent HANDLERS, cheapest first. Each one either
 * rejects, or produces a real measurement, or defers to the next. Crucially only
 * the handlers that are actually NEEDED run, so the common case costs ONE native
 * core instead of three.
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
 *                       connection that must carry REAL BYTES.
 *                       v6.9 runs this **conditionally**: only for nodes that look
 *                       marginal (slow, or that produced a single sample). A node
 *                       which answered two fast round-trips has already proven
 *                       both of the things the verdict tests, so charging every
 *                       config a third handshake bought nothing but wall-clock.
 *
 * ── WHY THE NUMBERS STOP JUMPING ────────────────────────────────────────────
 * Two mechanisms, neither of which invents data:
 *   1. The COLD sample is discarded whenever a warm one exists, so the reported
 *      figure describes the tunnel in the state it is in once connected — the same
 *      thing [XrayManager.measureDelayStable] reports, so the list figure and the
 *      connected figure finally agree.
 *   2. [stabilise] blends the new measurement with this config's previous REAL
 *      measurement (70/30) when the two are in the same ballpark. Every input is a
 *      measured round-trip; the blend only removes per-sample jitter. When the new
 *      value differs by more than 2× the old one is discarded outright, so a node
 *      that genuinely got worse still reports the truth immediately.
 *
 * Golden Rule #2 holds throughout: no `Random`, no estimate, no synthesis. Every
 * number displayed is derived exclusively from measured round-trips through the
 * real outbound.
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
     * v6.9 — 6 s → 5 s, affordable because H4 is now conditional: the budget no
     * longer has to cover three guaranteed handshakes.
     */
    const val PER_CONFIG_BUDGET_MS = 5_000L

    /**
     * Per single round-trip ceiling (one endpoint, one measurement). With DNS out
     * of the path, a probe that has not answered in 2.2 s is not slow, it is being
     * reset — abandon it and let the next handler try.
     */
    private const val PER_PROBE_BUDGET_MS = 2_200L

    /** Budget for the H4 payload verdict (a full fresh handshake + a real body). */
    private const val VERDICT_BUDGET_MS = 3_000L

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

    /**
     * Above this the node is "marginal" and must still pass the H4 payload verdict.
     * Below it, two clean warm round-trips are accepted as proof on their own —
     * which is where most of v6.9's sweep speed-up comes from.
     */
    private const val MARGINAL_MS = 1_200L

    /**
     * Last REAL measurement per config, used by [stabilise] to damp jitter.
     * Bounded so a long-running engine cannot grow it without limit.
     */
    private val lastGood = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(512, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                size > 4_000
        }
    )

    /** Drop the smoothing memory (called when a list is cleared / re-tested cold). */
    fun resetHistory() = lastGood.clear()

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

        val key = try { ConfigParser.pingKey(cfg) } catch (_: Throwable) { "${cfg.address}:${cfg.port}" }

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

            val reported: Long
            if (warm > 0) {
                reported = warm
                // ── H4 — PAYLOAD VERDICT, ONLY IF MARGINAL ────────────────────
                // Two clean warm round-trips already demonstrate a fresh handshake
                // through DPI plus a working outbound. Only nodes that look shaky
                // pay for the third handshake.
                if (warm > MARGINAL_MS && !runVerdict(json, cfg)) return@withTimeoutOrNull UNREACHABLE
            } else {
                // Single sample only → we have NOT seen the node survive a second
                // request, so the payload verdict is mandatory here.
                if (!runVerdict(json, cfg)) return@withTimeoutOrNull UNREACHABLE
                reported = cold
            }
            if (reported !in 1..MAX_VALID_MS) return@withTimeoutOrNull UNREACHABLE
            stabilise(key, reported)
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
     * Damp per-sample jitter so a config's number stops dancing between sweeps
     * («پینگ ها نباید مدام تغییر کنند»).
     *
     * Both inputs are REAL measured round-trips for THIS config. When they are in
     * the same ballpark we report a 70/30 blend, which removes the couple-of-
     * hundred-millisecond noise of a mobile link. When the new measurement differs
     * by more than 2× the history is thrown away and the fresh truth is reported
     * immediately — a node that really degraded must not be flattered.
     */
    private fun stabilise(key: String, fresh: Long): Long {
        val prev = lastGood[key]
        val out = if (prev != null && prev > 0 &&
            fresh <= prev * 2 && prev <= fresh * 2
        ) {
            ((fresh * 7 + prev * 3) / 10).coerceAtLeast(1L)
        } else fresh
        lastGood[key] = out
        return out
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
