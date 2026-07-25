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
