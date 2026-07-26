package com.neonvpn.app.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * v6.3 — the admin-controlled DOWNLOAD LINKS + NOTICE models.
 *
 * Both blocks are published by the admin panel inside the same `app_config.json`
 * the app already fetches, so there is no new endpoint and no new network call:
 * the existing [RemoteConfigStore] refresh picks them up automatically.
 *
 * ── DOWNLOAD LINKS ──────────────────────────────────────────────────────────
 * The panel's new "لینک‌های دانلود" tab lets the operator add any number of
 * download entries (title + URL + optional note). The app renders them in a
 * SCROLLABLE list on the "Download Links" drawer page, each row with a COPY
 * button so the user can paste the URL into a browser.
 *
 * ── NOTICE ──────────────────────────────────────────────────────────────────
 * The panel's new "اعلان‌ها" tab is a single text box. Whatever the operator
 * types is shown in the app as an announcement. Per the brief the app must
 * present it as an announcement **from Professor VPN** — it must never say
 * "sent from the admin panel". The [NoticeConfig.title] therefore defaults to
 * "اعلان Professor Vpn" and the body is rendered underneath it verbatim.
 */
data class DownloadLinksConfig(
    val enabled: Boolean = true,
    /** Optional heading shown above the list ("" → the built-in string). */
    val heading: String = "",
    /** Optional short note under the heading. */
    val note: String = "",
    val items: List<DownloadItem> = emptyList()
) {
    /** A single download entry the user can copy / open. */
    data class DownloadItem(
        val id: String,
        /** What the row is called, e.g. "دانلود مستقیم نسخه 6.3". */
        val title: String,
        /** The actual URL the COPY button puts on the clipboard. */
        val url: String,
        /** Optional extra line under the URL (size, mirror name, …). */
        val note: String = ""
    )

    companion object {
        fun parse(o: JSONObject?, def: DownloadLinksConfig): DownloadLinksConfig {
            if (o == null) return def
            val arr: JSONArray? = o.optJSONArray("items")
            val items = ArrayList<DownloadItem>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    val url = it.optString("url", "").trim()
                    if (url.isBlank()) continue
                    items.add(
                        DownloadItem(
                            id = it.optString("id", "dl_$i").ifBlank { "dl_$i" },
                            title = it.optString("title", "").ifBlank { "Download ${i + 1}" },
                            url = url,
                            note = it.optString("note", "")
                        )
                    )
                }
            }
            return DownloadLinksConfig(
                enabled = o.optBoolean("enabled", def.enabled),
                heading = o.optString("heading", def.heading),
                note = o.optString("note", def.note),
                items = items
            )
        }
    }
}

/**
 * v6.5 — "لینک پشت بارکد" (THE LINK BEHIND THE BARCODE).
 *
 * Published by the admin panel's new section of the same name: the operator
 * pastes a link, presses "ساخت بارکد", and the panel writes it here. The app
 * then renders THAT link — and nothing else — into the QR code on the Download
 * Links page, so anyone who scans it with any phone camera is taken straight to
 * the operator's URL in their BROWSER.
 *
 * Why this block exists at all (the v6.4 bug):
 *   v6.4 had no panel field. It built the QR payload itself by taking the first
 *   download link and appending `?bt=1&v=…`. The `bt=1` marker exists so that
 *   when OUR app opens the link it starts the Bluetooth hand-off — but it also
 *   meant the QR never contained a clean, operator-chosen URL, and the app
 *   registers a `professorvpn://get` deep link for the same page, so a scan
 *   could be captured by the app instead of opening a browser. Now the operator
 *   decides exactly what the barcode resolves to, and the payload is used
 *   VERBATIM.
 */
data class QrLinkConfig(
    /** Master switch. When false the app falls back to the download link. */
    val enabled: Boolean = true,
    /**
     * The exact URL the barcode must resolve to. Used verbatim — the app never
     * appends tracking or hand-off parameters to it, because anything extra is
     * what stopped the old code from being a plain browser link.
     */
    val url: String = "",
    /** Optional caption rendered under the barcode in the app. */
    val caption: String = ""
) {
    /** True when the operator has published a usable http(s) link. */
    val hasLink: Boolean
        get() = enabled && normalizedUrl().isNotBlank()

    /**
     * The scan-safe form of [url].
     *
     * A QR code is only useful if the scanner recognises the payload as a web
     * address, and phone cameras only offer "open in browser" for an explicit
     * scheme. Operators very often paste `example.com/x` without one, so we add
     * `https://` when it is missing rather than silently producing a barcode
     * that scans as meaningless text.
     */
    fun normalizedUrl(): String {
        val raw = url.trim()
        if (raw.isBlank()) return ""
        val lower = raw.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> raw
            // Anything with an explicit non-web scheme (tg:, mailto:, …) is the
            // operator's deliberate choice and is passed through untouched.
            Regex("^[a-z][a-z0-9+.\\-]*:").containsMatchIn(lower) -> raw
            else -> "https://$raw"
        }
    }

    companion object {
        fun parse(o: JSONObject?, def: QrLinkConfig): QrLinkConfig {
            if (o == null) return def
            return QrLinkConfig(
                enabled = o.optBoolean("enabled", def.enabled),
                // Accept both `url` and `link` so a hand-edited config or an
                // older panel build still works.
                url = o.optString("url", "").ifBlank { o.optString("link", def.url) },
                caption = o.optString("caption", def.caption)
            )
        }
    }
}

/**
 * The in-app announcement published from the panel's "اعلان‌ها" tab.
 *
 * IMPORTANT (per the brief): the app must present this as an announcement from
 * **Professor VPN**, never as "a message from the admin panel". That is why the
 * title is a branded constant and the operator only supplies the body text.
 */
data class NoticeConfig(
    val enabled: Boolean = false,
    /** Branded header. Defaults to the required "اعلان Professor Vpn". */
    val title: String = DEFAULT_TITLE,
    /** The operator's message body. Blank ⇒ nothing is shown. */
    val text: String = "",
    /**
     * Bumped by the panel on every publish. The app remembers the last id the
     * user dismissed so a given announcement is only forced on them once, while
     * a NEW announcement always reappears.
     */
    val id: String = "",
    /** Optional accent colour for the notice card. */
    val color: String = "#8A3FFC"
) {
    /** Nothing to show unless it is enabled AND actually has a body. */
    val hasContent: Boolean get() = enabled && text.isNotBlank()

    companion object {
        const val DEFAULT_TITLE = "اعلان Professor Vpn"

        fun parse(o: JSONObject?, def: NoticeConfig): NoticeConfig {
            if (o == null) return def
            val text = o.optString("text", def.text)
            return NoticeConfig(
                enabled = o.optBoolean("enabled", def.enabled),
                // Even if the panel sends a custom title we keep the branded
                // prefix requirement satisfied by falling back to the constant.
                title = o.optString("title", "").ifBlank { DEFAULT_TITLE },
                text = text,
                // A stable id lets the app tell a re-published notice apart from
                // the one the user already dismissed. When the panel doesn't send
                // one we derive it from the text so editing the message re-shows it.
                id = o.optString("id", "").ifBlank { text.hashCode().toString() },
                color = o.optString("color", def.color).ifBlank { def.color }
            )
        }
    }
}
