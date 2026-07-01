package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

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

    /** Try the origin first, then a couple of edge mirrors that survive in Iran. */
    private fun fetchWithMirrors(urlStr: String): String? {
        val candidates = mutableListOf(urlStr)
        // github pages → jsDelivr mirror of the same repo file
        // https://<user>.github.io/<repo>/<path>  →  cdn.jsdelivr.net/gh/<user>/<repo>@main/<path>
        try {
            val u = URL(urlStr)
            when {
                // https://<user>.github.io/<repo>/<path> → jsDelivr mirror
                u.host.endsWith("github.io") -> {
                    val user = u.host.substringBefore(".github.io")
                    val parts = u.path.trimStart('/').split('/')
                    if (parts.isNotEmpty()) {
                        val repo = parts[0]
                        val path = parts.drop(1).joinToString("/")
                        candidates.add("https://cdn.jsdelivr.net/gh/$user/$repo@main/$path")
                        candidates.add("https://fastly.jsdelivr.net/gh/$user/$repo@main/$path")
                    }
                }
                // https://raw.githubusercontent.com/<user>/<repo>/<branch>/<path>
                //   → jsDelivr mirror of the same file (survives DPI better in Iran)
                u.host == "raw.githubusercontent.com" -> {
                    val p = u.path.trimStart('/').split('/')
                    if (p.size >= 4) {
                        val user = p[0]; val repo = p[1]; val branch = p[2]
                        val path = p.drop(3).joinToString("/")
                        candidates.add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path")
                        candidates.add("https://fastly.jsdelivr.net/gh/$user/$repo@$branch/$path")
                        candidates.add("https://gcore.jsdelivr.net/gh/$user/$repo@$branch/$path")
                    }
                }
            }
        } catch (_: Throwable) {}
        candidates.add("https://r.jina.ai/$urlStr")

        // Ensure every candidate is cache-busted so a fresh Publish is never
        // served from a stale CDN edge copy.
        val bust = "t=" + System.currentTimeMillis()
        val busted = candidates.map { c ->
            if (c.contains("?")) "$c&$bust" else "$c?$bust"
        }

        for (c in busted) {
            val b = try { fetchOne(c) } catch (e: Throwable) {
                Log.w(TAG, "fetch failed $c: ${e.message}"); null
            }
            if (!b.isNullOrBlank() && b.contains("{")) return b
        }
        return null
    }

    private fun fetchOne(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4500
            readTimeout = 6000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ProfessorVPN/2.9 (Android)")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }
}
