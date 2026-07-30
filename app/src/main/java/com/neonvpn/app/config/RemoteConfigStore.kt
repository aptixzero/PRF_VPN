package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetches + caches the [RemoteConfig] published by the admin panel.
 *
 * SYNC MODEL (panel ⇄ app):
 *   The admin panel ( prfgame/adminpanel ) lets the operator edit the ad banner + contact
 *   text and, when they press "Apply", it writes a single JSON file. The app
 *   downloads that file on every launch; when the operator changes something and
 *   re-publishes, the next app launch (or pull-to-refresh) reflects it instantly.
 *   A locally-cached copy is used immediately so the UI is never blank while the
 *   network request is in flight, and the app still works fully offline.
 *
 *   The JSON is hosted at [REMOTE_URL]. It is fetched through the same resilient
 *   mirror chain used for configs so it loads even on disrupted Iranian links.
 */
object RemoteConfigStore {

    private const val TAG = "RemoteConfigStore"
    private const val PREFS = "remote_config"
    private const val KEY_JSON = "json"

    // §4.2 — the resolved in-app Telegram URL is mirrored to its own pref key so
    // the home screen can render the correct link INSTANTLY on cold start (before
    // the full JSON is parsed) and a background refresh keeps it current.
    private const val KEY_TELEGRAM_URL = "pref_telegram_url"
    private const val TELEGRAM_REFRESH_THROTTLE_MS = 60_000L
    @Volatile private var lastTelegramRefreshMs = 0L

    /**
     * Where the panel publishes the live settings file. This is the RAW github
     * pages / repo URL of the admin panel's generated config. The operator edits
     * it in the panel and commits; the app reads it here.
     *
     * NOTE: kept as the admin panel's published settings file. Mirror fallbacks
     * (jsDelivr etc.) are appended automatically so it loads inside Iran.
     */
    const val REMOTE_URL =
        "https://raw.githubusercontent.com/aptixzero/PRF_VPN/main/adminpanel/app_config.json"

    @Volatile private var cached: RemoteConfig = RemoteConfig.default()
    @Volatile private var loadedOnce = false

    private val listeners = mutableListOf<(RemoteConfig) -> Unit>()

    fun current(): RemoteConfig = cached

    fun addListener(l: (RemoteConfig) -> Unit) {
        synchronized(listeners) { listeners.add(l) }
        l(cached)
    }

    fun removeListener(l: (RemoteConfig) -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }

    /** Load the cached copy synchronously (instant) — call early in App.onCreate. */
    fun loadCache(context: Context) {
        if (loadedOnce) return
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val json = sp.getString(KEY_JSON, null)
            if (!json.isNullOrBlank()) {
                cached = RemoteConfig.parse(json)
            }
        } catch (_: Throwable) {
        } finally {
            loadedOnce = true
        }
    }

    /**
     * §4.2 — the cached in-app Telegram URL, read from its own pref key so the
     * home-screen icon has a correct link the instant the view is created (even
     * before the full remote JSON is fetched/parsed). Falls back to the resolved
     * value of the in-memory config when the pref hasn't been written yet.
     */
    fun cachedTelegramUrl(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = sp.getString(KEY_TELEGRAM_URL, null)
        return if (!v.isNullOrBlank()) v else cached.homeTelegramUrl
    }

    private fun cacheTelegramUrl(context: Context, url: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_TELEGRAM_URL, url).apply()
        } catch (_: Throwable) {}
    }

    /**
     * §4.2 — refresh the config (and thus the Telegram link) but no more than
     * once per [TELEGRAM_REFRESH_THROTTLE_MS]. Call from onResume so returning to
     * the foreground picks up an operator change without hammering the network.
     */
    suspend fun refreshTelegramThrottled(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastTelegramRefreshMs < TELEGRAM_REFRESH_THROTTLE_MS) return
        lastTelegramRefreshMs = now
        try { refresh(context) } catch (_: Throwable) {}
    }

    /** Fetch the latest settings from the panel and notify listeners on change.
     *  Cache-busted on every call so a fresh Publish from the panel is picked up
     *  immediately (no stale CDN copy) and the UI updates without an app restart. */
    suspend fun refresh(context: Context): RemoteConfig = withContext(Dispatchers.IO) {
        loadCache(context)
        val bust = REMOTE_URL + "?t=" + System.currentTimeMillis()
        val body = withTimeoutOrNull(9_000L) { fetchWithMirrors(bust) }
        if (!body.isNullOrBlank()) {
            try {
                val parsed = RemoteConfig.parse(body)
                cached = parsed
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_JSON, body).apply()
                // §4.2 — mirror the resolved Telegram link to its own pref key.
                cacheTelegramUrl(context, parsed.homeTelegramUrl)
                notifyListeners(parsed)
            } catch (e: Throwable) {
                Log.w(TAG, "parse remote config failed: ${e.message}")
            }
        }
        cached
    }

    private fun notifyListeners(cfg: RemoteConfig) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { try { it(cfg) } catch (_: Throwable) {} }
    }

    /**
     * v6.9 — **ORIGIN ONLY.** Every third-party mirror host was deleted from this
     * function.
     *
     * The v6.9 brief forbids any intermediary or proxy in the app's network path,
     * and this was the last place still holding a list of them. It was also slow:
     * on a link where the origin is blocked, the mirrors were normally blocked
     * too, so the panel fetch paid three or four extra full timeouts before giving
     * up — on the SPLASH SCREEN, which is why the app used to feel sluggish before
     * it had even opened.
     *
     * The direct fetch works because [com.neonvpn.app.net.CfDns] resolves the host
     * over encrypted Cloudflare DoH and dials the true origin, which defeats the
     * DNS poisoning that was the real reason a direct fetch failed.
     */
    private fun fetchWithMirrors(urlStr: String): String? {
        // Cache-bust so a freshly published panel config is never served stale.
        val bust = "t=" + System.currentTimeMillis()
        val url = if (urlStr.contains("?")) "$urlStr&$bust" else "$urlStr?$bust"
        val b = try { fetchOne(url) } catch (e: Throwable) {
            Log.w(TAG, "fetch failed $url: ${e.message}"); null
        }
        return if (!b.isNullOrBlank() && b.contains("{")) b else null
    }

    /**
     * v6.9 — routed through the shared, proxy-free, intermediary-free client so
     * the panel config benefits from Cloudflare-DoH resolution (works on a
     * poisoned link) and from connection reuse.
     */
    private fun fetchOne(urlStr: String): String? =
        com.neonvpn.app.net.DirectHttp.get(urlStr, cacheControl = "no-cache")
}
