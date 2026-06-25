package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * v4.0 CONFIG FEED — `aptixzero/con_new`.
 *
 * The deprecated `prfgame/CC_new` feed is dead. The app now reads its free
 * configs EXCLUSIVELY from the project's new collection:
 *
 *   https://github.com/aptixzero/con_new
 *     ├─ configs_000.txt   (up to ~2200 mixed vless/vmess lines)
 *     ├─ configs_001.txt
 *     ├─ …
 *     └─ configs_121.txt   (3-digit zero-padded, 122 files at v4.0)
 *
 * Discovery & resilience:
 *  • The file COUNT is discovered at runtime via the GitHub trees API and cached
 *    in SharedPreferences ([KEY_FILE_COUNT]) with a 24 h TTL. On API failure we
 *    fall back to the last cached value, or to [FALLBACK_FILE_COUNT] (= the live
 *    count at the time of the v3.8 implementation).
 *  • Each file is fetched through a CDN fallback chain (raw → jsDelivr → gitcdn)
 *    with an OkHttp-free HttpURLConnection (the project does not depend on OkHttp
 *    in this module; the on-disk OkHttp cache lives in [FeedCache]).
 *
 * Lazy / bounded:
 *  • Only ONE file is held in memory at a time.
 *  • Parsing stops early once 2200 candidate lines have been scanned in a file.
 *
 * This object owns the low-level fetch + per-file parse + count discovery.
 * [FreeConfigSource] sits on top and owns the deterministic 120-per-press cursor.
 */
object CcNewFeed {

    private const val TAG = "CcNewFeed"

    const val REPO = "aptixzero/con_new"
    const val BRANCH = "main"

    /** Max candidate lines scanned per file (the feed publishes up to ~2200). */
    const val MAX_LINES_PER_FILE = 2200

    /**
     * Live file count at the time of the v4.0 implementation, discovered via
     *   GET https://api.github.com/repos/aptixzero/con_new/git/trees/main
     * (configs_000.txt … configs_121.txt → 122 files). Used as a last-resort
     * fallback when the API is unreachable AND nothing is cached. NOT hardcoded
     * as the only source of truth — the live count is preferred and cached.
     */
    const val FALLBACK_FILE_COUNT = 122

    private const val PREFS = "cc_new_feed"
    private const val KEY_FILE_COUNT = "cc_new_file_count"
    private const val KEY_FILE_COUNT_AT = "cc_new_file_count_at"
    private const val FILE_COUNT_TTL_MS = 24L * 60 * 60 * 1000   // 24 h

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 3-digit zero-padded file name for [index]. */
    fun fileName(index: Int): String = "configs_%03d.txt".format(index)

    /**
     * Resolve the catalog file count. Uses the cached value while it is fresh
     * (< 24 h); otherwise re-discovers via the GitHub trees API and re-caches.
     * Always returns at least 1 so callers never divide by zero.
     */
    suspend fun fileCount(ctx: Context): Int = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        val cached = p.getInt(KEY_FILE_COUNT, -1)
        val cachedAt = p.getLong(KEY_FILE_COUNT_AT, 0L)
        val fresh = cached > 0 && (System.currentTimeMillis() - cachedAt) < FILE_COUNT_TTL_MS
        if (fresh) return@withContext cached

        val discovered = discoverFileCount()
        if (discovered > 0) {
            p.edit().putInt(KEY_FILE_COUNT, discovered)
                .putLong(KEY_FILE_COUNT_AT, System.currentTimeMillis())
                .apply()
            return@withContext discovered
        }
        // API failed: keep using last cached value if we have one, else fall back.
        if (cached > 0) cached else FALLBACK_FILE_COUNT
    }

    /** Best-effort: count `configs_NNN.txt` entries in the repo tree. -1 on failure. */
    private fun discoverFileCount(): Int {
        return try {
            val u = "https://api.github.com/repos/$REPO/git/trees/$BRANCH"
            val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ProfessorVPN/4.0")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (conn.responseCode !in 200..299) return -1
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(txt).optJSONArray("tree") ?: return -1
            val re = Regex("""^configs_(\d{3})\.txt$""")
            var maxIdx = -1
            var count = 0
            for (i in 0 until arr.length()) {
                val path = arr.optJSONObject(i)?.optString("path", "") ?: continue
                val m = re.matchEntire(path) ?: continue
                count++
                val idx = m.groupValues[1].toIntOrNull() ?: continue
                if (idx > maxIdx) maxIdx = idx
            }
            // The catalog is contiguous configs_000..configs_(N-1); prefer (maxIdx+1)
            // when present, otherwise the raw count.
            when {
                maxIdx >= 0 -> maxIdx + 1
                count > 0 -> count
                else -> -1
            }
        } catch (e: Throwable) {
            Log.w(TAG, "discoverFileCount failed: ${e.message}")
            -1
        }
    }

    /**
     * Fetch + parse one `configs_NNN.txt` into the ordered list of valid
     * vless/vmess raw links (verbatim, after the parsing filter). Blanks, comment
     * lines and unsupported schemes are skipped. At most [MAX_LINES_PER_FILE]
     * candidate lines are scanned. Returns null if the file is unreachable.
     *
     * @return list of raw links in FILE ORDER (the `rawLink`s, not parsed objects)
     */
    suspend fun parseFile(ctx: Context, index: Int): List<String>? = withContext(Dispatchers.IO) {
        val body = FeedCache.fetch(ctx, mirrorChain(index)) ?: return@withContext null
        val out = ArrayList<String>(256)
        var scanned = 0
        for (rawLine in body.lineSequence()) {
            if (scanned >= MAX_LINES_PER_FILE) break
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith("//")) continue
            scanned++
            // Keep only vless/vmess; ConfigParser.parseSingleSafe validates the link.
            val cfg = try { ConfigParser.parseSingleSafe(line) } catch (_: Throwable) { null }
                ?: continue
            if (cfg.protocol != "vless" && cfg.protocol != "vmess") continue
            if (cfg.address.isBlank() || cfg.port !in 1..65535 || cfg.userId.isBlank()) continue
            // Store the ORIGINAL link verbatim (never rewrite the payload / #remark).
            out.add(line)
        }
        out
    }

    /**
     * CDN fallback mirror chain for one file, in priority order:
     *   1. raw.githubusercontent.com   (origin)
     *   2. cdn.jsdelivr.net/gh         (edge-cached, survives DPI in Iran)
     *   3. gitcdn.link/cdn             (secondary mirror)
     */
    fun mirrorChain(index: Int): List<String> {
        val name = fileName(index)
        return listOf(
            "https://raw.githubusercontent.com/$REPO/$BRANCH/$name",
            "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/$name",
            "https://gitcdn.link/cdn/$REPO/$BRANCH/$name"
        )
    }
}
