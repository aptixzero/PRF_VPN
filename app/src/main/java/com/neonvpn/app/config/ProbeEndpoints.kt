package com.neonvpn.app.config

/**
 * v6.4 — THE SINGLE SOURCE OF TRUTH FOR EVERY LATENCY PROBE IN THE APP.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY THIS FILE EXISTS (the v6.3 bug it fixes)
 * ─────────────────────────────────────────────────────────────────────────────
 * The reported bug: *"a config shows ping 120 in the list, but the moment we
 * connect to it the ping jumps to 1000."*
 *
 * The cause was that the app measured latency against **two different sets of
 * endpoints with two different statistics**:
 *
 *   • `Pinger` (the list) probed a mixed set (Cloudflare, Telegram, Instagram)
 *     and reported `max(median, mean)`;
 *   • `XrayManager.measureDelay()` (the live number after connecting) probed
 *     **Google** (`gstatic.com/generate_204`) first.
 *
 * Google is answered by a *different* edge, over a different path, and on an
 * Iranian link it is routinely throttled — so the two numbers could never
 * agree. On top of that the live probe took ONE cold sample (a brand-new TLS
 * handshake through a tunnel that is simultaneously carrying the user's video
 * traffic), which is the worst possible sample, while the list took a median of
 * three warm ones.
 *
 * From v6.4 **every** probe in the app — the per-config list ping, the
 * post-connect health check, the watchdog and the live stats ping — uses:
 *
 *   1. the SAME endpoint list, and it is **Cloudflare only** (never Google);
 *   2. the SAME statistic (median of warm round-trips, cold sample discarded).
 *
 * Cloudflare is the right choice for this app because:
 *   • `cp.cloudflare.com/generate_204` returns a ZERO-byte 204 — the smallest,
 *     purest round-trip measurable, so the number is latency and nothing else;
 *   • Cloudflare's anycast edge is present in/next to the region, so the RTT is
 *     stable and reproducible instead of swinging with Google's routing;
 *   • it is genuinely throttled/filtered on Iranian ISPs, so reaching it still
 *     proves the tunnel really bypasses the filter (Golden Rule: a green ping
 *     must mean the config actually works).
 */
object ProbeEndpoints {

    /**
     * Cloudflare-only probe endpoints, fastest first. NO Google, ever.
     *
     *   1. `cp.cloudflare.com/generate_204`  — zero-byte 204, the cheapest real
     *      round-trip that exists. This is the reference endpoint.
     *   2. `1.1.1.1/cdn-cgi/trace`           — Cloudflare's own resolver edge by
     *      IP (no DNS needed at all), used when the first host is momentarily
     *      poisoned/blocked.
     *   3. `www.cloudflare.com/cdn-cgi/trace`— tiny text body on the main edge.
     *   4. `speed.cloudflare.com/__down?bytes=0` — zero-byte download endpoint,
     *      last-resort confirmation on the speed edge.
     */
    val URLS: List<String> = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://1.1.1.1/cdn-cgi/trace",
        "https://www.cloudflare.com/cdn-cgi/trace",
        "https://speed.cloudflare.com/__down?bytes=0"
    )

    /** The endpoint every "one quick probe" path should use. */
    val PRIMARY: String = URLS[0]

    /**
     * Cloudflare trace endpoint that reports the EXIT IP and its country
     * (`ip=` / `loc=` lines) in a ~200-byte body. v6.4 uses it, through the live
     * tunnel, to show the real egress IP + the correct country flag after
     * connecting — instead of guessing the country from the server hostname.
     */
    const val TRACE_URL: String = "https://cp.cloudflare.com/cdn-cgi/trace"

    /** Secondary trace endpoints, tried in order if [TRACE_URL] is unreachable. */
    val TRACE_URLS: List<String> = listOf(
        "https://cp.cloudflare.com/cdn-cgi/trace",
        "https://1.1.1.1/cdn-cgi/trace",
        "https://www.cloudflare.com/cdn-cgi/trace"
    )
}
