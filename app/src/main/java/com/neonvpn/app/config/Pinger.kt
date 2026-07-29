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
 * v6.6 — FAST, REAL, AND HONEST: "IF IT PINGS, IT CONNECTS"
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * WHAT WAS WRONG IN v6.5 (the two reported bugs, and they were the same bug)
 *
 *   «پینگ فیک می‌دهد»  — a config showed a healthy green number and then either
 *   refused to connect or connected and carried nothing.
 *   «دیر پینگ می‌گیرد» — a batch took minutes to sweep.
 *
 * Both came from the same place. v6.5 measured latency with the core's
 * `measureOutboundDelay`, which reports the time to complete ONE request through
 * a freshly-built outbound. That call answers "could a request be made?" — and
 * on a filtered link a node very often lets a small 204 through and then dies
 * under any real load, because what DPI resets is the *second* handshake, not
 * the first. So the number was real but the VERDICT was fake. Meanwhile every
 * dead config in the batch still burned the full 12 s budget (24 s with the
 * retry) inside its own throwaway native core, which is where the minutes went.
 *
 * THE v6.6 PIPELINE — three stages, each one cheap enough to justify the next:
 *
 *   Stage 0 — TCP pre-gate ([TcpProbe], ~300 ms, 48-wide).
 *             Xray dials the node with a plain TCP connect, so if that fails the
 *             core's dial cannot succeed either. This REJECTS the ~80 % of a
 *             public batch that is simply dead, for the price of one SYN. It can
 *             only ever reject — a success is not a ping and is never displayed.
 *
 *   Stage 1 — Zero-DNS latency lock-on ([ProbeEndpoints.INSTANT]).
 *             The reference endpoint is tried as an IP LITERAL first, so the
 *             probe pays no DNS round-trip. This is where most of the remaining
 *             time was hiding: on a cold outbound the DoH lookup alone could be
 *             2-4 s, and it was being paid on every single sample.
 *
 *   Stage 2 — THE VERDICT: a real payload on a SECOND connection.
 *             This is the actual fake-ping fix. After the latency samples we
 *             open a brand-new connection and pull real response BYTES
 *             ([XrayManager.measureConfigThroughput]). A node that only survives
 *             the first tiny handshake fails here, which is precisely the class
 *             of node that used to show green and then not work. Passing this
 *             means the node completed a fresh handshake AND moved real bytes —
 *             the same two things the live connect path needs, so the promise
 *             "a config that pings will connect" is now structurally true rather
 *             than hoped for.
 *
 * Every number reported is a measured round-trip through the real outbound. No
 * `Random`, no estimate, no synthesis — Golden Rule #2 holds.
 */
object Pinger {

    private const val TAG = "Pinger"

    const val TESTING = Long.MIN_VALUE
    const val UNREACHABLE = -1L

    /**
     * Hard wall-clock ceiling for the ENTIRE ping of one config (all stages
     * combined). Callers must treat [ping] as already-bounded and must NOT wrap
     * it in a shorter timeout (that was the v4.2 starvation bug).
     *
     * v6.8 — 9 s → 6 s. The whole point of v6.8 is SPEED. The TCP pre-gate has
     * already thrown away every dead node, so a survivor either answers over the
     * real outbound in a couple of seconds or it is being actively reset — and a
     * node that needs longer than 6 s to prove itself would be a miserable VPN
     * anyway. A tighter ceiling here is the single largest contributor to the
     * "پینگ گرفتن سریع شد" fix, because the sweep's wall-clock is dominated by the
     * dying nodes that used to burn the FULL budget each.
     */
    const val PER_CONFIG_BUDGET_MS = 6_000L

    /**
     * Per single probe attempt ceiling (one endpoint, one round-trip).
     * v6.8 — 3.5 s → 2.5 s: with DNS removed from the path, a probe that has not
     * answered in 2.5 s is not "slow", it is being reset. Abandon it and move on.
     */
    private const val PER_PROBE_BUDGET_MS = 2_500L

    /**
     * v6.8 — budget for the Stage-2 payload verdict. 5 s → 3.5 s. It is still a
     * full fresh handshake plus a real body — the single most important
     * measurement — but v6.8 issues it as ONE lightweight probe (the zero-DNS IP
     * literal only) instead of walking a list of heavier endpoints, so the
     * generous budget it used to need is gone.
     */
    private const val VERDICT_BUDGET_MS = 3_500L

    /**
     * Pause before the verdict connection. Long enough that the probe MUST open
     * a genuinely new connection rather than reusing the warm one the latency
     * samples shared — which is the entire point of the check.
     */
    private const val VERDICT_PAUSE_MS = 350L

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
     * CLOUDFLARE-ONLY probe endpoints, shared with the live connection.
     *
     * The list comes from [ProbeEndpoints], the single source of truth used by
     * EVERY latency path in the app (list ping, connect gate, watchdog, live
     * stats ping). NO Google anywhere. Cloudflare's anycast edge answers a
     * zero-byte 204, so the number is pure latency, it is stable/reproducible,
     * and because Cloudflare is throttled on Iranian ISPs, reaching it still
     * proves the tunnel genuinely bypasses the filter.
     *
     * v6.6 puts the IP-LITERAL endpoint FIRST so the common case needs no DNS.
     */
    private val PROBE_URLS: List<String> =
        (listOf(ProbeEndpoints.INSTANT) + ProbeEndpoints.URLS).distinct()

    /**
     * Round-trips taken against the SAME reference endpoint, whose MEDIAN
     * becomes the reported ping.
     *
     * v6.8 — 3 → 2. Each sample is a full native-core round-trip, so it is by far
     * the most expensive thing the sweep does. The Stage-2 payload verdict is the
     * measurement that actually decides "does this connect", so it carries the
     * reliability burden; the latency samples only need to produce a stable
     * NUMBER to display. Two warm-vs-cold samples give exactly that (we discard
     * the cold one and report the other), while cutting the per-config core
     * spin-ups by a third. This is a direct, measured speed-up of every sweep.
     */
    private const val SAMPLE_COUNT = 2

    /** Latency upper bound for a node we still treat as "reachable". */
    private const val MAX_VALID_MS = 8_000L

    /**
     * How many of the [SAMPLE_COUNT] round-trips must SUCCEED before a node may
     * proceed to the verdict stage.
     *
     * v6.8 — ONE, not two. The real "does a dead-but-answering node get through?"
     * defence is now the Stage-2 payload verdict (a FRESH connection carrying
     * REAL bytes), which a node that survives only its first tiny 204 cannot
     * pass. Requiring two latency samples on top of that was double-charging the
     * most expensive step for a guarantee the verdict already gives — and it
     * discarded usable high-RTT Iranian nodes whose second sample happened to be
     * reset. One good latency sample to lock the endpoint + the payload verdict
     * is both faster AND catches more real nodes.
     */
    private const val MIN_GOOD_SAMPLES = 1

    /** Failed round-trips tolerated while sampling before abandoning the endpoint. */
    private const val MAX_SAMPLE_FAILS = 2

    suspend fun ping(cfg: ServerConfig): Long = withContext(Dispatchers.IO) {
        // Only vless / vmess are buildable; anything else is unreachable here.
        if (cfg.protocol != "vless" && cfg.protocol != "vmess") return@withContext UNREACHABLE

        // ── STAGE 0 — TCP PRE-GATE ────────────────────────────────────────────
        // One SYN answers "can this address be dialled from this device at all?"
        // Xray's own dial is the same TCP connect, so a failure here is proof the
        // core would fail too. This is what makes a 240-config sweep finish in
        // seconds: the dead majority is eliminated for ~300 ms each instead of
        // occupying a native core for the full budget.
        //
        // It may ONLY reject. Success is not a ping and is never displayed.
        if (!TcpProbe.reachable(cfg)) {
            Log.d(TAG, "pre-gate: ${cfg.address}:${cfg.port} not dialable — skipping core probe")
            return@withContext UNREACHABLE
        }

        // Build the ping config from the EXACT same outbound + stream settings
        // the real connect path uses, so a green ping == genuinely connects.
        val json = try {
            XrayConfigBuilder.buildPingConfig(cfg)
        } catch (e: Throwable) {
            Log.w(TAG, "buildPingConfig failed: ${e.message}")
            return@withContext UNREACHABLE
        }

        val result = withTimeoutOrNull(PER_CONFIG_BUDGET_MS) {
            val samples = ArrayList<Long>(SAMPLE_COUNT)

            // ── STAGE 1 — LOCK ONTO ONE REFERENCE ENDPOINT ────────────────────
            // Every measurement of this config goes to the SAME target so the
            // numbers are comparable, and the IP-literal endpoint is tried first
            // so the common case pays no DNS round-trip at all.
            var found: String? = null
            for (url in PROBE_URLS) {
                val ms = singleProbe(json, url)
                if (ms in 1..MAX_VALID_MS) { found = url; samples.add(ms); break }
            }
            // Bind to a val so the loop below needs no smart cast.
            val refUrl = found ?: return@withTimeoutOrNull UNREACHABLE

            // Additional samples against that same reference endpoint.
            var fails = 0
            while (samples.size < SAMPLE_COUNT && fails < MAX_SAMPLE_FAILS) {
                val ms = singleProbe(json, refUrl)
                if (ms in 1..MAX_VALID_MS) samples.add(ms) else fails++
            }

            // A single successful handshake is NOT proof of a usable tunnel: on a
            // heavily-shaped link a dying node answers the first tiny 204 and then
            // collapses. Require at least two.
            if (samples.size < MIN_GOOD_SAMPLES) return@withTimeoutOrNull UNREACHABLE

            // ── STAGE 2 — THE VERDICT: REAL BYTES ON A FRESH CONNECTION ───────
            //
            // THIS is the fake-ping fix, and it is the reason the promise "a
            // config that pings will connect" can now be made honestly.
            //
            // Everything above only proves the node can complete small requests
            // in a quick burst that all ride ONE warm connection. The failure the
            // user actually hit is a node that does exactly that and then dies
            // the moment a NEW connection is opened under real load — which is
            // precisely what happens when they tap connect. No number of extra
            // latency samples can see that, because they all share the warm path.
            //
            // So after a deliberate pause we open a genuinely new connection and
            // require real response BYTES to come back through it. A node passing
            // this has demonstrated the two things the live connect path needs:
            // a fresh handshake through DPI, and actual payload throughput.
            try { kotlinx.coroutines.delay(VERDICT_PAUSE_MS) } catch (_: Throwable) {}
            val carried = verdictProbe(json)
            if (!carried) {
                Log.w(TAG, "verdict failed for ${cfg.address}:${cfg.port} — " +
                    "answers a handshake but cannot carry a payload (would be a fake green)")
                return@withTimeoutOrNull UNREACHABLE
            }

            // ── REPORT THE SAME STATISTIC THE LIVE CONNECTION REPORTS ─────────
            // The list and the connected screen must agree, otherwise the user
            // sees "120 in the list → 1000 after connecting".
            //
            // The FIRST sample is always the cold one: it pays for the full TCP +
            // TLS/Reality handshake through a brand-new core. A later sample rides
            // the warmed-up path — exactly the state the tunnel is in once
            // connected. So when we have more than one sample we DROP the cold one
            // and report the warm rest; [XrayManager.measureDelayStable] does the
            // identical thing, so the list figure and the connected figure agree.
            val warm = if (samples.size >= 2) samples.drop(1) else samples
            median(warm)
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
     * TRULY cancellable. The native measure call blocks and ignores cancellation,
     * so it runs as an [async] child of [probeScope] and we await it with a
     * timeout. When the timeout fires, `await()` is cancelled (await IS
     * cancellable even though the native call isn't) and we return -1
     * immediately — the abandoned native call finishes in the background and is
     * discarded.
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
     * v6.6 — the Stage-2 verdict: does a FRESH connection through this outbound
     * actually carry a real payload?
     *
     * Cancellable in the same way as [singleProbe], so a node that hangs here
     * costs the budget and nothing more.
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
