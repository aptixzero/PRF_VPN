package com.neonvpn.app.config

/**
 * v4.6 LIVE SOURCES — the 50 direct, live-updating public feeds the app now
 * reads its free configs from (replacing the dead `aptixzero/con_new` GitHub
 * mirror). These are the EXACT 50 links supplied for v4.6, in order.
 *
 * ── LAYOUT / CONTRACT ───────────────────────────────────────────────────────
 *  • The list is STRICTLY ALTERNATING: index 0 = a VLESS feed, index 1 = a VMESS
 *    feed, index 2 = VLESS, index 3 = VMESS … (25 vless + 25 vmess = 50).
 *  • Every feed updates on its own cadence (by-the-minute / hourly / daily). The
 *    app therefore never bundles a static config; it pulls whatever is live.
 *  • The app ONLY keeps `vless://` and `vmess://` links. Even if a feed happens
 *    to contain trojan / ss / etc., [ConfigParser] drops everything else.
 *
 * ── WHY WE DON'T SCAN ALL 50 AT ONCE ────────────────────────────────────────
 *  Scanning 50 large feeds simultaneously is slow and heavy on an Iranian
 *  mobile link. Instead the batch builder ([FreeConfigSource]) walks the sources
 *  a FEW at a time, VLESS-then-VMESS, collecting 120 vless + 120 vmess (=240)
 *  per press, and remembers where it stopped so the next press continues from
 *  the next sources. The Auto-Test connectivity probe similarly stops at the
 *  FIRST reachable vless/vmess pair instead of testing all 50.
 */
object LiveSources {

    enum class Kind { VLESS, VMESS }

    data class Src(val url: String, val kind: Kind)

    /**
     * The 50 v4.6 sources, alternating VLESS / VMESS exactly as supplied.
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
        Src("https://raw.githubusercontent.com/sakha1370/OpenRay/refs/heads/main/output/protocol/vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/sakha1370/OpenRay/refs/heads/main/output/protocol/vmess.txt", Kind.VMESS),
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
        Src("https://raw.githubusercontent.com/Delta-Kronecker/V2ray-Config/refs/heads/main/config/protocols/vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/Argh94/V2RayAutoConfig/refs/heads/main/configs/Vless.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/Argh94/V2RayAutoConfig/refs/heads/main/configs/Vmess.txt", Kind.VMESS),
        Src("https://raw.githubusercontent.com/coldwater-10/V2Hub2/main/Split/Normal/vless", Kind.VLESS),
        Src("https://raw.githubusercontent.com/coldwater-10/V2Hub2/main/Split/Normal/vmess", Kind.VMESS),
        Src("https://raw.githubusercontent.com/Farid-Karimi/Config-Collector/main/vless_iran.txt", Kind.VLESS),
        Src("https://raw.githubusercontent.com/Farid-Karimi/Config-Collector/main/vmess_iran.txt", Kind.VMESS)
    )

    /** Even indices → VLESS feeds. */
    val VLESS: List<Src> = ALL.filter { it.kind == Kind.VLESS }

    /** Odd indices → VMESS feeds. */
    val VMESS: List<Src> = ALL.filter { it.kind == Kind.VMESS }

    val COUNT: Int get() = ALL.size
}
