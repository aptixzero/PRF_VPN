package com.neonvpn.app.net

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v6.6 — THE ONE HTTP CLIENT FOR EVERY FETCH IN THE APP. **NO PROXIES.**
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT CHANGED AND WHY
 * ─────────────────────────────────────────────────────────────────────────────
 * v6.5 fetched feeds and panel config with a bare `HttpURLConnection` and, on
 * failure, retried the same URL through a chain of public reverse proxies
 * (`r.jina.ai`, `api.allorigins.win`, `ghproxy.net`, …). Per the v6.6 brief those
 * proxies are gone: they are third-party servers in the middle of the config
 * supply chain, they are rate-limited and usually blocked from Iran anyway, and
 * waiting for each to time out was costing tens of seconds.
 *
 * The replacement fixes the real problem instead of routing around it. The reason
 * a direct GitHub fetch fails on an Iranian ISP is almost always **DNS
 * poisoning** — so we resolve through **Cloudflare DoH** ([CfDns]) and connect
 * DIRECTLY to the true origin, with correct SNI and full certificate validation.
 * Encrypted lookup, authenticated origin, no middleman, one hop.
 *
 * Also here, and equally important for the "it's slow" complaint:
 *
 *   • **Connection + TLS session reuse.** One shared client with a real
 *     connection pool, so the second fetch to a host skips the whole handshake.
 *     v6.5 opened (and threw away) a fresh connection for every single URL.
 *   • **HTTP/2** where the origin supports it, so the many small feed fetches
 *     multiplex over one connection instead of serialising.
 *   • **Tight, honest timeouts.** A stalled host is abandoned in seconds rather
 *     than blocking a slot for the better part of a minute.
 */
object DirectHttp {

    private const val TAG = "DirectHttp"

    private const val UA = "ProfessorVPN/6.8 (Android)"

    /**
     * The shared client. Built lazily and reused for the whole process so the
     * connection pool and TLS sessions actually pay off.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Cloudflare DoH resolution — defeats ISP DNS poisoning without a proxy.
            .dns(CfDns)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            // Reuse connections aggressively: feed fetching is many small GETs to
            // a handful of hosts, so pooling is the single biggest speed win here.
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            // Explicitly NO proxy — ever. Also stops OkHttp from inheriting a
            // system/HTTP proxy that some carriers inject.
            .proxy(java.net.Proxy.NO_PROXY)
            .build()
    }

    /**
     * GET [url] directly and return the body, or null on any failure.
     *
     * Exception-safe by contract: a single dead source can never crash the batch
     * builder or the auto-test engine.
     */
    fun get(url: String, cacheControl: String? = null): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .apply { if (cacheControl != null) header("Cache-Control", cacheControl) }
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "HTTP ${resp.code} for $url")
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "GET failed for $url: ${e.message}")
            null
        }
    }
}
