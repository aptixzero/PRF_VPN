package com.neonvpn.app.config

/**
 * Bundled list of public V2Ray / Xray subscription & raw-config sources.
 *
 * These are fetched automatically by the "Free Configs" screen when the user
 * taps START SEARCH — the user is never asked to pick a source.
 *
 * IMPORTANT (project policy): this client ONLY supports **VLESS** and **VMESS**.
 * Any trojan / shadowsocks / ssr / grpc-only entries inside a source are
 * silently dropped by [ConfigParser]. The sources below are curated VLESS /
 * VMESS feeds (some are MIXED feeds — the parser keeps only vless + vmess).
 * GitHub `/blob/` page URLs are normalised to their raw form.
 *
 * Each entry may return either:
 *   - plain text with one share-link per line (vless:// / vmess://), or
 *   - a base64-encoded subscription blob (handled by [ConfigParser]).
 *
 * FRESHNESS POLICY (new):
 *   Sources are grouped into freshness tiers. Newer / more-frequently-updated
 *   feeds (tier 0) are tried FIRST so the user gets the freshest configs; the
 *   older "Lite" feeds (10ium) sit in the LAST tier as a fallback. At runtime
 *   [ConfigFetcher] additionally probes each source's `Last-Modified` header and
 *   prefers the genuinely-newest sources within the same tier, so whichever
 *   mirror was updated most recently wins automatically.
 */
object ConfigSources {

    /**
     * A source URL + its freshness tier. Lower [tier] = fresher / higher
     * priority (fetched first). Within the same tier the runtime Last-Modified
     * probe decides the order.
     */
    data class Source(val url: String, val tier: Int)

    /** Ordered, de-duplicated sources. Tier 0 = freshest, higher = older. */
    val SOURCES_TIERED: List<Source> = buildList {

        // ===== TIER 0 — newest / most up-to-date public feeds =====
        // (All verified live & updated *today, by-the-minute* on 2026-06-11.)

        // barry-far/V2ray-Config — split-by-protocol, regenerated continuously
        // (last commit minutes ago). Large, fresh vless + vmess pools.
        add(Source("https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vless.txt", 0))
        add(Source("https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vmess.txt", 0))

        // ALIILAPRO/v2rayNG-Config — single curated server list, refreshed
        // every few minutes (mixed vless/vmess; parser keeps the supported two).
        add(Source("https://raw.githubusercontent.com/ALIILAPRO/v2rayNG-Config/main/server.txt", 0))

        // Surfboardv2ray/Proxy-sorter — base64 subscription merged + sorted,
        // rebuilt continuously (vless + vmess). ConfigParser decodes base64.
        add(Source("https://raw.githubusercontent.com/Surfboardv2ray/Proxy-sorter/main/submerge/converted.txt", 0))

        // Epodonios/v2ray-configs — big "All_Configs" aggregate, updated hourly.
        add(Source("https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt", 0))

        // ebrasha free-v2ray-public-list — refreshed very frequently.
        add(Source("https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/refs/heads/main/vmess_configs.txt", 0))
        add(Source("https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/refs/heads/main/vless_configs.txt", 0))

        // v6.9 — the third-party paste host that v6.8 used here was DELETED.
        // The brief forbids ANY intermediary/relay host inside the app; every
        // remaining source below is a first-party GitHub raw origin.

        // ===== TIER 1 — established, regularly-updated aggregators =====
        add(Source("https://raw.githubusercontent.com/V2RayRoot/V2RayConfig/main/Config/vless.txt", 1))
        add(Source("https://raw.githubusercontent.com/V2RayRoot/V2RayConfig/main/Config/vmess.txt", 1))
        add(Source("https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vless.txt", 1))
        add(Source("https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vmess.txt", 1))
        add(Source("https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt", 1))
        add(Source("https://raw.githubusercontent.com/MahanKenway/Freedom-V2Ray/main/configs/vless.txt", 1))
        add(Source("https://raw.githubusercontent.com/DukeMehdi/FreeList-V2ray-Configs/refs/heads/main/Configs/VLESS-DukeMehdi-Configs.txt", 1))
        add(Source("https://raw.githubusercontent.com/DukeMehdi/FreeList-V2ray-Configs/refs/heads/main/Configs/VMESS-DukeMehdi-Configs.txt", 1))
        add(Source("https://raw.githubusercontent.com/MrRabbitson/RabbitProxyZ-proxy-list/refs/heads/main/lite.txt", 1))
        add(Source("https://github.com/roosterkid/openproxylist/blob/main/V2RAY.txt", 1))
        add(Source("https://raw.githubusercontent.com/pog7x/vpn-configs/refs/heads/master/githubmirror/1.txt", 1))
        add(Source("https://raw.githubusercontent.com/pog7x/vpn-configs/refs/heads/master/githubmirror/2.txt", 1))
        add(Source("https://raw.githubusercontent.com/pog7x/vpn-configs/refs/heads/master/githubmirror/3.txt", 1))
        add(Source("https://raw.githubusercontent.com/pog7x/vpn-configs/refs/heads/master/githubmirror/7.txt", 1))
        add(Source("https://raw.githubusercontent.com/ClvEnT/Free-V2ray-Conifgs/refs/heads/main/%DA%A9%D8%A7%D9%86%D9%81%DB%8C%DA%AF.txt", 1))

        // ===== TIER 2 — OLD "Lite" / legacy feeds (use LAST, as requested) =====
        // 10ium collectors — older snapshots, kept only as a last-resort fallback.
        add(Source("https://raw.githubusercontent.com/10ium/V2rayCollectorLite/main/vmess_iran.txt", 2))
        add(Source("https://raw.githubusercontent.com/10ium/V2rayCollectorLite/main/vless_iran.txt", 2))
        add(Source("https://raw.githubusercontent.com/10ium/V2rayCollector/main/vmess_iran.txt", 2))
        add(Source("https://raw.githubusercontent.com/10ium/V2rayCollector/main/vless_iran.txt", 2))
    }
        .map { it.copy(url = normaliseRaw(it.url)) }
        .distinctBy { it.url }
        .sortedBy { it.tier }

    /** Flat list (kept for any caller that just wants the URLs, tier order). */
    val SOURCES: List<String> = SOURCES_TIERED.map { it.url }

    /** Turn a GitHub `/blob/` web URL into its `raw.githubusercontent.com` form. */
    private fun normaliseRaw(url: String): String {
        val u = url.trim()
        if (u.startsWith("https://github.com/") && u.contains("/blob/")) {
            return u.replace("https://github.com/", "https://raw.githubusercontent.com/")
                .replace("/blob/", "/")
        }
        return u
    }

    /** How many fresh configs a single START SEARCH run should collect. */
    const val TARGET_COUNT = 80
}
