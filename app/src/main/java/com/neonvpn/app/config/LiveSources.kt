package com.neonvpn.app.config

/**
 * LIVE SOURCES — the direct, live-updating public feeds the app reads its free
 * configs from. 70 feeds: **35 VLESS + 35 VMESS**.
 *
 * ── LAYOUT / CONTRACT (load-bearing) ────────────────────────────────────────
 *  • The list is STRICTLY ALTERNATING: index 0 = VLESS, index 1 = VMESS,
 *    index 2 = VLESS, index 3 = VMESS … [FreeConfigSource] and
 *    [ConnectivityProbe] both rely on VLESS at even indices, VMESS at odd.
 *  • Every feed updates on its own cadence (by-the-minute / hourly / daily), so
 *    the app never bundles a static config — it pulls whatever is live.
 *  • Only `vless://` and `vmess://` are kept. Even where a feed also carries
 *    trojan / ss / hysteria, [ConfigParser] drops everything else.
 *  • 51 feeds are plain "one link per line" text and 19 are base64-wrapped
 *    subscription blobs; [SourceFetcher.extractLinks] handles both (line scan
 *    first, base64 decoder only as a fallback).
 *
 * ── ZERO INTERMEDIARIES (v6.9) ──────────────────────────────────────────────
 *  Every URL below is the ORIGIN (`raw.githubusercontent.com`). There is no
 *  jsDelivr / Fastly / gcore mirror chain and no CORS or GitHub proxy anywhere
 *  in the fetch path — see [SourceFetcher] and [DirectHttp], which pins
 *  `Proxy.NO_PROXY` and resolves through Cloudflare DoH only.
 *
 * ── HOW THE POOL IS CONSUMED (v6.9) ─────────────────────────────────────────
 *  Auto Test no longer hunts for the *best* feed. [ConnectivityProbe] opens 14
 *  feeds of EACH kind simultaneously and the FIRST usable body per kind wins;
 *  [FreeConfigSource] then collects 120 vless + 120 vmess (= 240) per press in
 *  parallel waves, always advancing its cursor so the next press reads the NEXT
 *  feeds rather than re-reading an exhausted one.
 *
 * ── HEALTH (verified live for v6.9) ─────────────────────────────────────────
 *  All 70 feeds return a usable body: ~765 000 vless + ~253 000 vmess links
 *  (~1.02 M total) — roughly 4 000 presses' worth of unique configs.
 *  Four dead entries were repaired in v6.9:
 *    • OpenRay moved `output/protocol/` → `output/kind/` (both kinds were 404).
 *    • Delta-Kronecker stopped publishing a vmess file at all (permanent 404)
 *      → replaced with V2RayAggregator's merged subscription.
 *    • V2Hub2's vless split is 0 bytes upstream — HTTP 200 with an EMPTY body,
 *      which is worse than a 404 because the race counts it as reachable
 *      → replaced with Delta-Kronecker's SNI vless list.
 */
object LiveSources {

    enum class Kind { VLESS, VMESS }

    data class Src(val url: String, val kind: Kind)

    /**
     * All 70 sources, strictly alternating VLESS / VMESS.
     * Order is load-bearing: [FreeConfigSource] and the Auto-Test probe rely on
     * VLESS living at even indices and VMESS at odd indices.
     */
    val ALL: List<Src> = listOf(
        Src("https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/nyeinkokoaung404/V2ray-Configs/main/Splitted-By-Protocol/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/nyeinkokoaung404/V2ray-Configs/main/Splitted-By-Protocol/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/coldwater-10/V2ray-Config-Lite/main/Splitted-By-Protocol/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/coldwater-10/V2ray-Config-Lite/main/Splitted-By-Protocol/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/coldwater-10/V2ray-Config/main/Splitted-By-Protocol/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/coldwater-10/V2ray-Config/main/Splitted-By-Protocol/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/barry-far/V2ray-config/main/Splitted-By-Protocol/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/barry-far/V2ray-config/main/Splitted-By-Protocol/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/filtered/subs/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/filtered/subs/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/SoliSpirit/v2ray-configs/refs/heads/main/Protocols/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/SoliSpirit/v2ray-configs/refs/heads/main/Protocols/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/refs/heads/main/vless_configs.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/refs/heads/main/vmess_configs.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/V2RayRoot/V2RayConfig/main/Config/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/V2RayRoot/V2RayConfig/main/Config/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/Kwinshadow/TelegramV2rayCollector/main/sublinks/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/Kwinshadow/TelegramV2rayCollector/main/sublinks/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/sevcator/5ubscrpt10n/main/protocols/vl.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/sevcator/5ubscrpt10n/main/protocols/vm.txt", Kind.VMESS),
        // v6.9 — OpenRay moved `output/protocol/` to `output/kind/`; the old paths
        // returned HTTP 404, so these two slots were dead weight in every race.
        // Re-verified live: vless.txt = 7 269 links, vmess.txt = 685 links.
        Src("https://raw.githubusercontent.com/sakha1370/OpenRay/refs/heads/main/output/kind/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/sakha1370/OpenRay/refs/heads/main/output/kind/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/MahanKenway/Freedom-V2Ray/main/configs/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/MahanKenway/Freedom-V2Ray/main/configs/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/iboxz/free-v2ray-collector/main/main/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/iboxz/free-v2ray-collector/main/main/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/Kolandone/v2raycollector/main/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/Kolandone/v2raycollector/main/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/hamedcode/port-based-v2ray-configs/main/sub/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/hamedcode/port-based-v2ray-configs/main/sub/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/Danialsamadi/v2go/main/Splitted-By-Protocol/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/Danialsamadi/v2go/main/Splitted-By-Protocol/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/protocols/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/protocols/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/F0rc3Run/F0rc3Run/refs/heads/main/splitted-by-protocol/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/F0rc3Run/F0rc3Run/refs/heads/main/splitted-by-protocol/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/ShatakVPN/ConfigForge-V2Ray/main/configs/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/ShatakVPN/ConfigForge-V2Ray/main/configs/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/Delta-Kronecker/V2ray-Config/refs/heads/main/config/protocols/vless.txt", Kind.VLESS),
        // v6.9 — Delta-Kronecker publishes NO vmess file at all any more (only
        // vless), so the old vmess URL was a permanent 404. Replaced with
        // V2RayAggregator's merged subscription, verified live at 2 529 vmess links.
        Src("https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/Argh94/V2RayAutoConfig/refs/heads/main/configs/Vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/Argh94/V2RayAutoConfig/refs/heads/main/configs/Vmess.txt", Kind.VMESS),
        // v6.9 — V2Hub2's `Split/Normal/vless` (and `Split/Base64/vless`) are both
        // 0 bytes upstream: HTTP 200 with an EMPTY body, which is worse than a 404
        // because the race treats it as a live source that yields nothing.
        // Replaced with Delta-Kronecker's SNI vless list, verified live at 2 161
        // links. The V2Hub2 vmess file is still healthy (815 KB) and stays.
        Src("https://raw.githubusercontent.com/Delta-Kronecker/V2ray-Config/refs/heads/main/config/sni/protocols/vless_sni.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/coldwater-10/V2Hub2/main/Split/Normal/vmess", Kind.VMESS),
        Src("https://raw.githubusercontent.com/Farid-Karimi/Config-Collector/main/vless_iran.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/Farid-Karimi/Config-Collector/main/vmess_iran.txt", Kind.VMESS),

        // ── v6.7 ADDITIONS ──────────────────────────────────────────────────
        // 20 extra feeds appended (indices 50..69). Every URL below was verified
        // LIVE with a real HTTP GET before being added; each returns a non-empty
        // body containing real `vless://` / `vmess://` links (several are
        // base64-wrapped — [SourceFetcher] already decodes those).
        // The original 50 are untouched: v6.7 ONLY grows the pool.
        // The strict VLESS/VMESS alternation continues here too.
        Src("https://raw.githubusercontent.com/mheidari98/.proxy/main/vless", Kind.VLESS),
        Src("https://raw.githubusercontent.com/mheidari98/.proxy/main/vmess", Kind.VMESS),
        Src("https://raw.githubusercontent.com/Leon406/SubCrawler/main/sub/share/vless", Kind.VLESS),
        Src("https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/splitted/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/Surfboardv2ray/Proxy-sorter/main/submerge/converted.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/Surfboardv2ray/Proxy-sorter/main/submerge/converted.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/ALIILAPRO/v2rayNG-Config/main/server.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/ALIILAPRO/v2rayNG-Config/main/server.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/mfuu/v2ray/master/v2ray", Kind.VMESS),
        Src("https://raw.githubusercontent.com/4n0nymou3/multi-proxy-config-fetcher/main/configs/proxy_configs.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/ts-sf/fly/main/v2", Kind.VMESS),
        Src("https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list_raw.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list_raw.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/Kwinshadow/TelegramV2rayCollector/main/sublinks/mix.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/v2ray.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/nyeinkokoaung404/V2ray-Configs/main/All_Configs_Sub.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/barry-far/V2ray-Config/main/All_Configs_Sub.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/super-sub.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/super-sub.txt", Kind.VMESS)
    )

    /** Even indices → VLESS feeds. */
    val VLESS: List<Src> = ALL.filter { it.kind == Kind.VLESS }

    /** Odd indices → VMESS feeds. */
    val VMESS: List<Src> = ALL.filter { it.kind == Kind.VMESS }

    val COUNT: Int get() = ALL.size
}
