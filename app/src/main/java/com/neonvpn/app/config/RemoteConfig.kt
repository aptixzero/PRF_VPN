package com.neonvpn.app.config

import org.json.JSONObject

/**
 * The in-memory model of the remotely-controlled app settings that the ADMIN
 * PANEL ( https://github.com/prfgame/adminpanel ) publishes as a single JSON
 * file and the app fetches on every launch.
 *
 * Two things are controlled remotely so the operator can change them WITHOUT
 * shipping a new APK (true sync between panel ⇄ app):
 *
 *   1. [ad]      — the single in-app advertising banner on the Connect screen.
 *                  There is NO ad-network script anywhere in the app. The banner
 *                  is a plain, fully-styleable card (title + subtitle + optional
 *                  image + colors) and a configurable CLICK ACTION.
 *   2. [contact] — the "Contact Us" screen text + the Telegram id used by its
 *                  two buttons (Copy id / Send message).
 *
 * Everything has a sane built-in DEFAULT so a fresh install (or a moment when
 * the remote file can't be reached) still shows correct Persian copy.
 */
data class RemoteConfig(
    val version: Int,
    val ad: AdConfig,
    val contact: ContactConfig,
    /** Admin-controlled APP logo (live). Empty = use the built-in wordmark. */
    val appLogoUrl: String = "",
    /**
     * Admin-controlled "In-App Telegram Link" — the URL opened when the user taps
     * the Telegram icon shown to the LEFT of the "Professor VPN" title on the home
     * screen. Set in the admin panel's Contact tab ("In-App Telegram Link"). When
     * empty, the icon falls back to the contact Telegram URL so it always opens
     * something sensible. NO hardcoded Telegram link is used.
     */
    val inAppTelegramUrl: String = "",
    /**
     * Admin-curated SPONSOR configs shown in the Sponsor tab. These are RESTRICTED:
     * the user may only see the title/media/text and PING them — they can NOT copy,
     * view details, see the host/IP, reveal the raw link, or add them to My Configs.
     * Only vless/vmess raw links are accepted (others are dropped on parse).
     */
    val sponsor: SponsorConfig = SponsorConfig(),
    /**
     * v4.6 — DONATE / support entries shown on the (former Sponsor) Donate tab.
     * Each entry is a crypto address with a coin logo + copy button. Fully
     * admin-controlled from the panel.
     */
    val donate: DonateConfig = DonateConfig(),
    /**
     * v4.6 — the CTA button shown UNDER the connect button on Home. It is a plain
     * link button (label + url). Label defaults per-language in the UI; the panel
     * can override the label, url and enabled flag.
     */
    val homeCta: HomeCta = HomeCta(),
    /**
     * v4.6 — the bottom home banner (image or plain text) + its click url. The
     * image may be a remote URL (uploaded from the panel) — if blank, the banner
     * shows [homeBanner].text with [homeBanner].textColor.
     */
    val homeBanner: HomeBanner = HomeBanner()
) {

    /** A single crypto donation entry. */
    data class DonateItem(
        val id: String,          // "usdt" | "trx" | "ton" | "btc" | custom id
        val coin: String,        // display name, e.g. "USDT (TRC20)"
        val address: String,     // the wallet address (what Copy copies, verbatim)
        val logoUrl: String,     // optional remote logo url ("" → built-in per-coin icon)
        val network: String      // "usdt" | "trx" | "ton" | "btc" | "custom" (picks built-in icon)
    )

    data class DonateConfig(
        val enabled: Boolean = true,
        val heading: String = "",
        val note: String = "",
        val items: List<DonateItem> = emptyList()
    )

    /** The Home CTA (Telegram-channel) button under the connect pill. */
    data class HomeCta(
        val enabled: Boolean = true,
        val labelFa: String = "",     // "" → built-in "عضو کانال تلگرام شوید"
        val labelEn: String = "",     // "" → built-in "Join our Telegram channel"
        val url: String = ""          // "" → falls back to homeTelegramUrl
    )

    /** The bottom Home banner. */
    data class HomeBanner(
        val enabled: Boolean = true,
        val imageUrl: String = "",
        val text: String = "",
        val textColor: String = "#E6F2EC",
        val url: String = ""
    )

    /** A restricted sponsor entry. The raw link is kept ONLY so we can build a
     *  ping outbound internally — it is never surfaced to the UI. */
    data class SponsorItem(
        val id: String,
        val title: String,      // display name shown on the neon card
        val text: String,       // optional promo line
        val imageUrl: String,   // optional media (png/jpg/gif). empty = none
        val link: String,       // raw vless/vmess link (INTERNAL ONLY, never shown)
        val protocol: String    // "vless" | "vmess" (badge only)
    )

    /** The whole sponsor section. */
    data class SponsorConfig(
        val enabled: Boolean = true,
        val heading: String = "",          // optional section heading override
        val note: String = "",             // optional small note under the heading
        val items: List<SponsorItem> = emptyList()
    )
    /**
     * The Connect-screen ad card. Deliberately script-free — the operator types
     * a message (and optionally an image) in the panel; the app renders it.
     */
    data class AdConfig(
        val enabled: Boolean,
        val title: String,        // big line, e.g. "محل تبلیغ شما"
        val subtitle: String,     // small line under it
        val bgColor: String,      // "#RRGGBB" card background
        val textColor: String,    // "#RRGGBB" text color
        val imageUrl: String,     // optional banner image (png/jpg/gif). empty = none
        val action: String,       // "contact" | "url" | "none"
        val actionUrl: String     // used only when action == "url"
    )

    /** Contact-Us page content. */
    data class ContactConfig(
        val text: String,         // body paragraph
        val telegramId: String,   // "@mx_pr" (what the Copy button copies)
        val telegramUrl: String   // "https://t.me/mx_pr" (what Send opens)
    )

    /** Convenience: the live Telegram link the whole app should use. */
    val telegramUrl: String get() = contact.telegramUrl
    val telegramId: String get() = contact.telegramId

    /**
     * The Telegram URL the home-screen Telegram ICON opens. Prefers the dedicated
     * admin "In-App Telegram Link"; falls back to the contact Telegram URL so the
     * icon is never a dead tap. Never a hardcoded constant — always panel-driven.
     */
    val homeTelegramUrl: String
        get() = inAppTelegramUrl.ifBlank { contact.telegramUrl }

    companion object {
        /** Ad click actions. */
        const val ACTION_CONTACT = "contact"
        const val ACTION_URL = "url"
        const val ACTION_NONE = "none"

        /** Built-in defaults — exactly the copy requested for the empty ad slot. */
        fun default(): RemoteConfig = RemoteConfig(
            version = 0,
            ad = AdConfig(
                enabled = true,
                title = "محل تبلیغ شما",
                subtitle = "جهت ثبت تبلیغ با ما در ارتباط باشید",
                bgColor = "#11161C",
                textColor = "#E6F2EC",
                imageUrl = "",
                action = ACTION_CONTACT,
                actionUrl = ""
            ),
            contact = ContactConfig(
                text = "جهت ثبت تبلیغات و استعلام قیمت بنر به آیدی زیر در تلگرام پیام دهید:",
                telegramId = "@mx_pr",
                telegramUrl = "https://t.me/mx_pr"
            ),
            appLogoUrl = "",
            inAppTelegramUrl = "",
            sponsor = SponsorConfig(),
            donate = DonateConfig(),
            homeCta = HomeCta(),
            homeBanner = HomeBanner()
        )

        /** Resolve the CTA label for the given language ("fa" | "en"). */
        fun ctaLabelFor(cta: HomeCta, lang: String): String {
            return if (lang == "fa") cta.labelFa else cta.labelEn
        }

        /** Parse the panel's JSON. Any missing field falls back to the default. */
        fun parse(json: String): RemoteConfig {
            val def = default()
            return try {
                val o = JSONObject(json)
                val adO = o.optJSONObject("ad") ?: JSONObject()
                val cO = o.optJSONObject("contact") ?: JSONObject()
                // App banner lives under "appBanner" (separate from "websiteBanner").
                // Backwards-compatible: fall back to the legacy flat "ad" block.
                val appBannerO = o.optJSONObject("appBanner") ?: adO
                val logo = o.optJSONObject("appLogo")?.optString("url", "")
                    ?: o.optString("appLogoUrl", def.appLogoUrl)
                // "In-App Telegram Link" — accept either a nested object
                // {"inAppTelegram":{"url":"…"}} or a flat "inAppTelegramUrl".
                val inAppTg = o.optJSONObject("inAppTelegram")?.optString("url", "")
                    ?: o.optString("inAppTelegramUrl", def.inAppTelegramUrl)
                RemoteConfig(
                    version = o.optInt("version", def.version),
                    appLogoUrl = logo,
                    inAppTelegramUrl = inAppTg,
                    ad = AdConfig(
                        enabled = appBannerO.optBoolean("enabled", def.ad.enabled),
                        title = appBannerO.optString("title", def.ad.title).ifBlank { def.ad.title },
                        subtitle = appBannerO.optString("subtitle", def.ad.subtitle),
                        bgColor = appBannerO.optString("bgColor", def.ad.bgColor).ifBlank { def.ad.bgColor },
                        textColor = appBannerO.optString("textColor", def.ad.textColor).ifBlank { def.ad.textColor },
                        imageUrl = appBannerO.optString("imageUrl", def.ad.imageUrl),
                        action = appBannerO.optString("action", def.ad.action).ifBlank { def.ad.action },
                        actionUrl = appBannerO.optString("actionUrl", def.ad.actionUrl)
                    ),
                    contact = ContactConfig(
                        text = cO.optString("text", def.contact.text).ifBlank { def.contact.text },
                        telegramId = cO.optString("telegramId", def.contact.telegramId).ifBlank { def.contact.telegramId },
                        telegramUrl = cO.optString("telegramUrl", def.contact.telegramUrl).ifBlank { def.contact.telegramUrl }
                    ),
                    sponsor = parseSponsor(o.optJSONObject("sponsor"), def.sponsor),
                    donate = parseDonate(o.optJSONObject("donate"), def.donate),
                    homeCta = parseHomeCta(o.optJSONObject("homeCta"), def.homeCta),
                    homeBanner = parseHomeBanner(o.optJSONObject("homeBanner"), def.homeBanner)
                )
            } catch (_: Throwable) {
                def
            }
        }

        private fun parseDonate(o: JSONObject?, def: DonateConfig): DonateConfig {
            if (o == null) return def
            val arr = o.optJSONArray("items")
            val items = ArrayList<DonateItem>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    val addr = it.optString("address", "").trim()
                    if (addr.isBlank()) continue
                    val network = it.optString("network", "custom").ifBlank { "custom" }
                    items.add(
                        DonateItem(
                            id = it.optString("id", "d_$i").ifBlank { "d_$i" },
                            coin = it.optString("coin", network.uppercase()).ifBlank { network.uppercase() },
                            address = addr,
                            logoUrl = it.optString("logoUrl", ""),
                            network = network
                        )
                    )
                }
            }
            return DonateConfig(
                enabled = o.optBoolean("enabled", def.enabled),
                heading = o.optString("heading", def.heading),
                note = o.optString("note", def.note),
                items = items
            )
        }

        private fun parseHomeCta(o: JSONObject?, def: HomeCta): HomeCta {
            if (o == null) return def
            return HomeCta(
                enabled = o.optBoolean("enabled", def.enabled),
                labelFa = o.optString("labelFa", def.labelFa),
                labelEn = o.optString("labelEn", def.labelEn),
                url = o.optString("url", def.url)
            )
        }

        private fun parseHomeBanner(o: JSONObject?, def: HomeBanner): HomeBanner {
            if (o == null) return def
            return HomeBanner(
                enabled = o.optBoolean("enabled", def.enabled),
                imageUrl = o.optString("imageUrl", def.imageUrl),
                text = o.optString("text", def.text),
                textColor = o.optString("textColor", def.textColor).ifBlank { def.textColor },
                url = o.optString("url", def.url)
            )
        }

        /**
         * Parse the sponsor block. Supports the NEW shape (an `items` array of
         * restricted vless/vmess configs) and stays tolerant of the OLD single-card
         * shape (title/text/imageUrl/url) by treating it as an empty list.
         */
        private fun parseSponsor(o: JSONObject?, def: SponsorConfig): SponsorConfig {
            if (o == null) return def
            val enabled = o.optBoolean("enabled", def.enabled)
            val heading = o.optString("heading", def.heading)
            val note = o.optString("note", def.note)
            val arr = o.optJSONArray("items")
            val items = ArrayList<SponsorItem>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    val link = it.optString("link", "").trim()
                    if (link.isBlank()) continue
                    // Validate it really is a supported config; drop anything else.
                    val cfg = try { ConfigParser.parseSingleSafe(link) } catch (_: Throwable) { null }
                    if (cfg == null || (cfg.protocol != "vless" && cfg.protocol != "vmess")) continue
                    items.add(
                        SponsorItem(
                            id = it.optString("id", "sp_$i").ifBlank { "sp_$i" },
                            title = it.optString("title", "").ifBlank { "Sponsor ${i + 1}" },
                            text = it.optString("text", ""),
                            imageUrl = it.optString("imageUrl", ""),
                            link = link,
                            protocol = cfg.protocol
                        )
                    )
                }
            }
            return SponsorConfig(enabled = enabled, heading = heading, note = note, items = items)
        }
    }
}
