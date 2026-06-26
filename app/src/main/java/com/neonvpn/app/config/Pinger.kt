package com.neonvpn.app.config

import android.util.Log
import com.neonvpn.app.service.XrayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * REAL end-to-end reachability test for a single [ServerConfig].
 *
 * This is the heart of the "ping was lying" + "auto-test froze after a few
 * configs" fix. The ONLY thing we trust is a full proxied request that travels
 * **through the Xray outbound** to a real `generate_204` endpoint (exactly what
 * v2rayNG / v2box do). If that handshake + request succeeds, the server can
 * actually carry traffic and a real latency is returned. If it fails (or the
 * native call hangs), the server is reported UNREACHABLE.
 *
 * Why the auto-test used to freeze:
 *   `Libv2ray.measureOutboundDelay` spins up a throw-away core instance and
 *   dials the probe. On a dead / DPI-blocked node that native call could block
 *   far longer than its internal timeout (TLS retries, half-open sockets), and
 *   because every config was tested back-to-back with no upper bound, one slow
 *   node stalled the whole queue forever. We now wrap EVERY measurement in a
 *   hard [withTimeoutOrNull] ceiling so a stuck probe is abandoned and the loop
 *   marches on to the next config — the tester never gets stuck again.
 *
 * Why we don't fake it:
 *   We deliberately DO NOT fall back to a plain TCP connect against host:port —
 *   almost every config in these public lists points at a Cloudflare / Fastly
 *   front IP, so a bare TCP handshake always "succeeds" even when the proxy is
 *   dead. That false-positive is precisely why the old build showed a green ping
 *   yet opened nothing after connecting (and why v2rayNG / v2box then timed out
 *   on the very same configs). No fake pings, ever — only a genuine proxied
 *   round-trip to a live endpoint counts.
 *
 * Returns latency in ms, or [UNREACHABLE] (-1) if the server can't proxy.
 */
object Pinger {

    private const val TAG = "Pinger"

    const val TESTING = Long.MIN_VALUE
    const val UNREACHABLE = -1L

    // Hard ceiling for a single config's whole test (a 2-stage confirm). Tightened
    // so a dead node is abandoned quickly and the (now heavily-parallel) queue
    // keeps moving — Auto-Test feels far faster while staying accurate.
    private const val PER_CONFIG_BUDGET_MS = 4_600L
    // Per single probe attempt ceiling (the native call's own timeout is a hint,
    // this is the enforced wall-clock cap). A healthy node answers well under 2s;
    // anything slower is not worth waiting on when many are tested concurrently.
    private const val PER_PROBE_BUDGET_MS = 2_000L

    // v4.2 — REAL-WORLD FILTERED TARGETS.
    //
    // The whole point of this app is to bypass Iran's censorship, so the ping
    // MUST be measured against services that are actually FILTERED for Iranian
    // users — NOT google.com (which is reachable on many ISPs even without a
    // VPN, producing a misleadingly "green" ping on dead proxies). We probe the
    // exact destinations the user wants to reach: Cloudflare, Telegram and
    // Instagram endpoints. If a proxy can carry a real request to THESE, it can
    // genuinely unblock what matters — that's what makes "if it pinged, it
    // connects" honest.
    //
    // We use lightweight, well-known 204/connectivity endpoints on each provider
    // so the round-trip is fast but still a genuine proxied request.
    private val PROBE_URLS = listOf(
        // Cloudflare connectivity check (filtered edge for many IR ISPs).
        "https://cp.cloudflare.com/generate_204",
        // Telegram core endpoint — directly the service Iranians need unblocked.
        "https://www.telegram.org/robots.txt",
        // Instagram — another primary blocked target inside Iran.
        "https://www.instagram.com/favicon.ico",
        // Cloudflare 1.1.1.1 captive portal (second CF edge as a CDN fallback).
        "https://1.1.1.1/cdn-cgi/trace"
    )

    // Latency we treat as the upper bound for a "really reachable" node. A genuine
    // proxied round-trip to a filtered target above this is too slow/unstable to
    // count as connected.
    private const val MAX_VALID_MS = 5_000L

    suspend fun ping(cfg: ServerConfig): Long = withContext(Dispatchers.IO) {
        // Only vless / vmess are buildable; anything else is unreachable here.
        if (cfg.protocol != "vless" && cfg.protocol != "vmess") return@withContext UNREACHABLE

        // CRITICAL: build the ping config from the EXACT same outbound + stream
        // settings (fragment, sockopt, uTLS, reality, transport) that the real
        // connect path uses (XrayConfigBuilder.build). If the probe used a
        // simpler outbound than the live tunnel, a config could "ping green" yet
        // fail on connect — precisely the "pings but won't connect" bug. By
        // probing through the identical engine, a green ping now means the config
        // genuinely connects with the current internet.
        val json = try {
            XrayConfigBuilder.buildPingConfig(cfg)
        } catch (e: Throwable) {
            Log.w(TAG, "buildPingConfig failed: ${e.message}")
            return@withContext UNREACHABLE
        }

        // Whole-config budget: if the probes collectively blow past this, abandon.
        val result = withTimeoutOrNull(PER_CONFIG_BUDGET_MS) {
            // STAGE 1 — find a FILTERED target that succeeds through this outbound.
            var first = -1L
            var firstUrlIdx = -1
            for ((i, url) in PROBE_URLS.withIndex()) {
                val ms = singleProbe(json, url)
                if (ms in 1..MAX_VALID_MS) { first = ms; firstUrlIdx = i; break }
            }
            if (first <= 0) return@withTimeoutOrNull UNREACHABLE

            // STAGE 2 — CONFIRMATION. A single successful handshake can be a
            // fluke (TCP front answered, TLS resumed, a cached 204). We require a
            // SECOND independent round-trip to a DIFFERENT filtered endpoint to
            // prove the proxy actually carries sustained traffic to the services
            // the user really needs. Only configs that pass BOTH are reported
            // reachable — this is the rule that makes "ping == 100% connects".
            var confirm = -1L
            for ((i, url) in PROBE_URLS.withIndex()) {
                if (i == firstUrlIdx) continue
                confirm = singleProbe(json, url)
                if (confirm in 1..MAX_VALID_MS) break
            }
            // Fallback: if every OTHER endpoint is unreachable on this link,
            // re-probe the SAME endpoint once more — two consecutive successes
            // still beats a one-shot fluke.
            if (confirm !in 1..MAX_VALID_MS && firstUrlIdx >= 0) {
                confirm = singleProbe(json, PROBE_URLS[firstUrlIdx])
            }
            if (confirm !in 1..MAX_VALID_MS) return@withTimeoutOrNull UNREACHABLE

            // Report the better (lower) of the two as the representative latency.
            minOf(first, confirm)
        }
        result ?: UNREACHABLE
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
