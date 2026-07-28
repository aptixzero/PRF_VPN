package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * On-disk HTTP cache for the [CcNewFeed] config files.
 *
 * Backed by an OkHttp [Cache] (10 MB) at `cacheDir/feed-cache/`:
 *   • 6 h `max-age` — within that window the body is served straight from disk.
 *   • Revalidation with `ETag` / `If-None-Match`. On `304 Not Modified` OkHttp
 *     refreshes the freshness window and replays the cached body (no re-download).
 *   • No-network / origin-down → fall back to the cached body and serve STALE
 *     (`onlyIfCached` + a very large `maxStale`) so the Free tab still loads.
 *
 * A single shared client is reused so the cache + connection pool are shared
 * across every file fetch.
 */
object FeedCache {

    private const val TAG = "FeedCache"
    private const val CACHE_SIZE_BYTES = 10L * 1024 * 1024   // 10 MB
    private const val MAX_AGE_SECONDS = 6 * 60 * 60          // 6 h fresh window
    private const val MAX_STALE_SECONDS = 30 * 24 * 60 * 60  // serve up to 30d stale offline

    @Volatile private var client: OkHttpClient? = null

    private fun client(ctx: Context): OkHttpClient {
        client?.let { return it }
        return synchronized(this) {
            client ?: build(ctx).also { client = it }
        }
    }

    private fun build(ctx: Context): OkHttpClient {
        val dir = File(ctx.cacheDir, "feed-cache")
        val cache = Cache(dir, CACHE_SIZE_BYTES)
        return OkHttpClient.Builder()
            .cache(cache)
            // v6.6 — resolve through Cloudflare DoH and NEVER through a proxy, for
            // the same reason as every other fetch in the app: the real blocker on
            // an Iranian ISP is DNS poisoning of the origin, so an encrypted lookup
            // plus a direct, certificate-validated connection is both safer and
            // faster than handing the request to a third-party mirror.
            .dns(com.neonvpn.app.net.CfDns)
            .proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .followRedirects(true)
            // Force a 6 h max-age + ETag revalidation on every cached response even
            // when the origin omits Cache-Control (raw.githubusercontent.com does).
            .addNetworkInterceptor { chain ->
                val resp = chain.proceed(chain.request())
                resp.newBuilder()
                    .header("Cache-Control", "public, max-age=$MAX_AGE_SECONDS")
                    .removeHeader("Pragma")
                    .build()
            }
            .build()
    }

    /**
     * Fetch the first reachable URL in [urls], using the disk cache transparently.
     * On total network failure, retries each URL with `onlyIfCached` so a stale
     * cached body is served when offline. Returns null only if nothing — neither
     * network nor cache — yields a usable body.
     */
    fun fetch(ctx: Context, urls: List<String>): String? {
        val c = client(ctx)
        // 1) normal path (cache → revalidate → network) for each mirror in turn
        for (u in urls) {
            val body = tryGet(c, u, onlyIfCached = false)
            if (body != null && looksUsable(body)) return body
        }
        // 2) offline fallback: serve stale cache if any mirror has one
        for (u in urls) {
            val body = tryGet(c, u, onlyIfCached = true)
            if (body != null && looksUsable(body)) {
                Log.w(TAG, "serving STALE cached feed for $u (offline fallback)")
                return body
            }
        }
        return null
    }

    private fun tryGet(c: OkHttpClient, url: String, onlyIfCached: Boolean): String? {
        return try {
            val cc = if (onlyIfCached) {
                CacheControl.Builder()
                    .onlyIfCached()
                    .maxStale(MAX_STALE_SECONDS, TimeUnit.SECONDS)
                    .build()
            } else {
                CacheControl.Builder()
                    .maxAge(MAX_AGE_SECONDS, TimeUnit.SECONDS)
                    .build()
            }
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "ProfessorVPN/3.8 (Android)")
                .cacheControl(cc)
                .build()
            c.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (e: Throwable) {
            if (!onlyIfCached) Log.w(TAG, "fetch failed $url: ${e.message}")
            null
        }
    }

    private fun looksUsable(body: String): Boolean =
        body.isNotBlank() && (body.contains("vless://") || body.contains("vmess://"))
}
