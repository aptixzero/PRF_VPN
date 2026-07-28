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

    // ─────────────────────────────────────────────────────────────────────────
    // v6.6 — ZERO-DNS PROBING (this is what removes seconds from every connect)
    // ─────────────────────────────────────────────────────────────────────────
    //
    // WHY: `measureOutboundDelay` and the device-path probe both start by
    // RESOLVING the probe hostname. On a cold tunnel that resolution is itself a
    // full round-trip through the brand-new outbound (DoH to Cloudflare), so the
    // FIRST probe after connecting paid DNS + TCP + TLS + request — routinely
    // 2-4 s on an Iranian mobile link, and up to 10 s when the DoH handshake was
    // shaped. That cost was charged *before* the UI was allowed to say
    // "Connected", which is the whole of the reported «۳۰ ثانیه باید صبر کنیم».
    //
    // Two fixes, both here:
    //   1. [INSTANT] is an IP-LITERAL URL — `https://1.1.1.1/…`. There is no
    //      hostname, so there is NO DNS step at all. Cloudflare serves a
    //      certificate with `1.1.1.1` in its SANs, so TLS validates normally.
    //      The connect gate fires this one FIRST, which is why the first proof
    //      of life now lands in a few hundred milliseconds.
    //   2. [HOSTS] is injected into the Xray `dns.hosts` block, so the named
    //      Cloudflare probe hosts resolve from a static table instead of paying
    //      a DoH round-trip. Only Cloudflare's own documented anycast addresses
    //      are used, and TLS still validates against the real SNI, so this is a
    //      pure latency win with no security trade-off.

    /**
     * The zero-DNS probe: an IP-literal Cloudflare endpoint. Needs no resolver,
     * so it is the cheapest possible "is this tunnel alive?" question and it is
     * always the first probe the connect gate asks.
     */
    const val INSTANT: String = "https://1.1.1.1/cdn-cgi/trace"

    /**
     * Static resolution table for the named Cloudflare probe hosts, injected as
     * the Xray `dns.hosts` block. Values are Cloudflare's own documented anycast
     * addresses:
     *
     *   • `cp.cloudflare.com` → 162.159.36.1 / 162.159.46.1 — the addresses
     *     Cloudflare publishes for its captive-portal endpoint.
     *   • `one.one.one.one`   → 1.1.1.1 / 1.0.0.1 — the resolver anycast pair.
     *
     * `www.cloudflare.com` and `speed.cloudflare.com` are deliberately NOT
     * pinned: they live on ordinary Cloudflare CDN ranges that rotate, so a
     * hardcoded address there would eventually go stale. They are only ever
     * reached as third/fourth fallbacks, where one DNS lookup costs nothing.
     */
    val HOSTS: Map<String, List<String>> = linkedMapOf(
        "cp.cloudflare.com" to listOf("162.159.36.1", "162.159.46.1"),
        "one.one.one.one" to listOf("1.1.1.1", "1.0.0.1")
    )

    /**
     * Cloudflare resolver addresses used for the DoH servers in the Xray DNS
     * block. IP-literal so the resolver itself never needs a resolver.
     */
    val DOH_URLS: List<String> = listOf(
        "https://1.1.1.1/dns-query",
        "https://1.0.0.1/dns-query"
    )

    /** Plain-53 Cloudflare fallbacks (used only if DoH is shaped). */
    val DNS_PLAIN: List<String> = listOf("1.1.1.1", "1.0.0.1")
}
