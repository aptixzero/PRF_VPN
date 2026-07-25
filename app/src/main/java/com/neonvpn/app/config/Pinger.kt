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
     * v6.4 — CLOUDFLARE-ONLY probe endpoints, shared with the live connection.
     *
     * This list now comes from [ProbeEndpoints], the single source of truth used
     * by EVERY latency path in the app (list ping, post-connect health check,
     * watchdog, live stats ping). Before v6.4 the list pinged Cloudflare while
     * the live connection pinged **Google**, which is precisely why a config
     * that advertised 120 ms read ~1000 ms the second you connected to it: two
     * different edges, two different routes, two different statistics.
     *
     * NO Google anywhere. Cloudflare's anycast edge answers a zero-byte 204, so
     * the number is pure latency, it is stable/reproducible, and because
     * Cloudflare is throttled on Iranian ISPs, reaching it still proves the
     * tunnel genuinely bypasses the filter.
     */
    private val PROBE_URLS = ProbeEndpoints.URLS

    /**
     * v4.7 — ONE confirmed censored-endpoint round-trip == reachable. The v4.6
     * two-confirmation rule did not fit the time budget on Iranian links and
     * made EVERY config read "unreachable" (the "no ping at all" bug). A single
     * timed success through the real outbound to a FILTERED endpoint is already
     * a genuine proof of bypass — the same standard v2rayNG applies.
     */
    private const val REQUIRED_CONFIRMATIONS = 1

    /**
     * v6.4 — number of round-trips taken against the SAME reference endpoint,
     * whose MEDIAN becomes the reported ping. Raised 3 → 4: with the endpoint
     * now identical to the live connection's (Cloudflare only), one extra warm
     * sample is what makes the list number and the connected number line up
     * instead of differing by an order of magnitude.
     */
    private const val SAMPLE_COUNT = 4

    /** Latency upper bound for a node we still treat as "reachable". */
    private const val MAX_VALID_MS = 8_000L

    /**
     * v6.3 — how many of the [SAMPLE_COUNT] round-trips must SUCCEED before we
     * are willing to call a node reachable and show a green ping.
     *
     * Two (not one) is the whole fix for "it pings 150 ms but the connection
     * doesn't work". A dead-but-answering node reliably passes ONE tiny 204 and
     * then stops; it almost never passes two in a row. Requiring two costs a few
     * hundred milliseconds per config and removes nearly all the false greens.
     */
    private const val MIN_GOOD_SAMPLES = 2

    /**
     * v6.3 — how many failed round-trips we tolerate while sampling before
     * giving up on the reference endpoint. Raised from 2 to 3 so a single
     * hiccup on a weak mobile link doesn't discard an otherwise good node.
     */
    private const val MAX_SAMPLE_FAILS = 3

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

        // v4.8 — STABLE, REPRODUCIBLE PING (fixes "same config pings 90ms now,
        // no-ping 2 minutes later"). The old code took the FIRST endpoint that
        // answered and reported ITS single round-trip. That number swung wildly
        // because (a) different endpoints have very different latencies and (b) a
        // single sample on a jittery link is noisy — so the SAME node could look
        // fast, then slow, then dead on consecutive taps.
        //
        // The new rule:
        //   1. Lock onto ONE reference endpoint — the first censored endpoint that
        //      answers at all (tried fastest-first). This keeps every measurement
        //      of this config against the SAME target, so numbers are comparable.
        //   2. Take up to [SAMPLE_COUNT] quick round-trips to that endpoint and
        //      report their MEDIAN — a realistic, jitter-resistant latency instead
        //      of one lucky/unlucky sample.
        //   3. If the primary endpoint stops answering mid-sampling, fall back to
        //      the next censored endpoint so a momentary block on one target does
        //      not falsely report the whole node as dead.
        // Result: if a node pings 90ms, it keeps pinging ~90ms; a node that is
        // genuinely down reads UNREACHABLE consistently.
        val result = withTimeoutOrNull(PER_CONFIG_BUDGET_MS) {
            val samples = ArrayList<Long>(SAMPLE_COUNT)

            // find the reference endpoint (first that answers), fastest-first.
            var refUrl: String? = null
            for (url in PROBE_URLS) {
                val ms = singleProbe(json, url)
                if (ms in 1..MAX_VALID_MS) { refUrl = url; samples.add(ms); break }
            }
            if (refUrl == null) return@withTimeoutOrNull UNREACHABLE

            // gather additional samples against the SAME reference endpoint.
            var fails = 0
            while (samples.size < SAMPLE_COUNT && fails < MAX_SAMPLE_FAILS) {
                val ms = singleProbe(json, refUrl)
                if (ms in 1..MAX_VALID_MS) samples.add(ms) else fails++
            }

            // ── v6.3 QUALITY GATE ────────────────────────────────────────────
            // The v6.2 rule ("one confirmed round-trip == reachable") is exactly
            // what produced the reported bug: "sometimes it shows ping 100–200
            // but when we connect it doesn't work at all, or is very weak."
            //
            // A single successful handshake is NOT proof of a usable tunnel. On
            // a heavily-shaped link a dying node very often answers the first
            // tiny 204 and then collapses — so it advertised a pretty 150 ms and
            // was useless the moment real traffic started.
            //
            // From v6.3 a node must SUSTAIN the tunnel: at least
            // [MIN_GOOD_SAMPLES] separate round-trips have to succeed against
            // the SAME endpoint. That single change filters out the "pings but
            // won't carry traffic" nodes before the user ever taps connect.
            if (samples.size < MIN_GOOD_SAMPLES) return@withTimeoutOrNull UNREACHABLE

            // ── v6.4 — REPORT THE SAME STATISTIC THE LIVE CONNECTION REPORTS ──
            // The list and the connected screen must agree, otherwise the user
            // sees "120 in the list → 1000 after connecting" (the reported bug).
            //
            // The very FIRST sample is always the cold one: it pays for the full
            // TCP + TLS/Reality handshake through a brand-new core. Every later
            // sample rides the warmed-up path — which is exactly the state the
            // tunnel is in once you are connected. So when we have enough
            // samples we DROP the cold one and take the median of the warm rest;
            // that is precisely how [XrayManager.measureDelay] now reports the
            // live number, so the two figures finally describe the same thing.
            val warm = if (samples.size >= 3) samples.drop(1) else samples
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
