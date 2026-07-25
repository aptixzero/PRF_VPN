package com.neonvpn.app.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Central place for app-level user preferences that affect the whole UI:
 *   • language (Persian / English)   — applied per-Context via [wrap]
 *   • theme    (dark / light)        — the dark "black" theme is the DEFAULT
 *   • first-run flag                 — drives the language picker dialog
 *
 * Everything is stored in a tiny SharedPreferences file so it survives restarts
 * and is read synchronously (cheap) before any Activity inflates its views.
 */
object AppPrefs {

    private const val FILE = "neonvpn_prefs"
    private const val KEY_LANG = "app_lang"          // "fa" | "en"
    private const val KEY_THEME = "app_theme"        // "dark" | "light"
    private const val KEY_LANG_CHOSEN = "lang_chosen" // user picked a language at least once

    const val LANG_FA = "fa"
    const val LANG_EN = "en"
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"

    // v3.8 — opt-in "remember pasted configs" toggle (OFF by default).
    private const val KEY_PASTE_HISTORY = "paste_history_enabled"

    // v5.6 — sticky "Auto Test is ON" flag. Persisted so that if the OS kills the
    // process during a long screen-off session, Auto Test resumes on relaunch.
    private const val KEY_AUTOTEST_ON = "autotest_on"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --------------------------------------------------------------- language
    /** Saved language code, defaulting to Persian (project is Iran-first). */
    fun getLanguage(ctx: Context): String =
        prefs(ctx).getString(KEY_LANG, LANG_FA) ?: LANG_FA

    fun setLanguage(ctx: Context, lang: String) {
        prefs(ctx).edit().putString(KEY_LANG, lang).putBoolean(KEY_LANG_CHOSEN, true).apply()
    }

    /** Has the user already been asked / chosen a language at least once? */
    fun isLanguageChosen(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LANG_CHOSEN, false)

    // ------------------------------------------------------------------ theme
    /** Saved theme; DEFAULT is the dark/black gaming theme as required. */
    fun getTheme(ctx: Context): String =
        prefs(ctx).getString(KEY_THEME, THEME_DARK) ?: THEME_DARK

    fun setTheme(ctx: Context, theme: String) {
        prefs(ctx).edit().putString(KEY_THEME, theme).apply()
    }

    fun isDark(ctx: Context): Boolean = getTheme(ctx) != THEME_LIGHT

    // -------------------------------------------------------- paste history
    /**
     * Whether the "remember pasted configs" history is enabled.
     * v4.8 — DEFAULT ON. The user wants "Paste From Clipboard" to also scan the
     * accumulated history of previously-pasted configs (e.g. they copied 100
     * configs over time) and add every vless/vmess found. Android does not expose
     * the OS clipboard HISTORY to apps for privacy reasons, so the app keeps its
     * own local, on-device history of everything ever pasted in-app and merges it
     * into each paste. This is now enabled by default so the behaviour "just works".
     */
    fun isPasteHistoryEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PASTE_HISTORY, true)

    fun setPasteHistoryEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PASTE_HISTORY, enabled).apply()
    }

    // ---------------------------------------------------------- auto test flag
    /** Whether Auto Test was left ON (so it can auto-resume after a process kill). */
    fun isAutoTestOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTOTEST_ON, false)

    fun setAutoTestOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTOTEST_ON, on).apply()
    }

    // ------------------------------------------------------- v6.3 announcement
    /** Id of the last announcement the user explicitly closed. */
    private const val KEY_NOTICE_SEEN = "notice_seen_id"

    /**
     * True when the user already dismissed exactly this announcement. A NEW
     * announcement (different id) always reappears, so the operator can push a
     * fresh message at any time and it will be shown again.
     */
    fun isNoticeDismissed(ctx: Context, id: String): Boolean {
        if (id.isBlank()) return false
        return prefs(ctx).getString(KEY_NOTICE_SEEN, "") == id
    }

    fun dismissNotice(ctx: Context, id: String) {
        if (id.isBlank()) return
        prefs(ctx).edit().putString(KEY_NOTICE_SEEN, id).apply()
    }

    // ---------------------------------------------------------------- locale
    /**
     * Wrap a base [Context] so all resources resolve in the chosen language.
     * Call from every Activity's [android.content.ContextWrapper.attachBaseContext].
     */
    fun wrap(base: Context): Context {
        val lang = getLanguage(base)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        // RTL/LTR layout direction follows the locale automatically.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }
}
