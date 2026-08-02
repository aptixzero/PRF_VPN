package com.neonvpn.app.net

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v6.9 — THE ONE HTTP CLIENT FOR EVERY FETCH IN THE APP.
 * **NO PROXIES. NO INTERMEDIARIES. NO THIRD-PARTY MIRRORS.**
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE RULE THIS FILE ENFORCES
 * ─────────────────────────────────────────────────────────────────────────────
 * Every HTTP request the app makes goes straight to the origin it names. There
 * is no relay, no CORS bridge, no text-extraction service and no borrowed CDN
 * anywhere in the path, and `.proxy(Proxy.NO_PROXY)` below makes that structural
 * rather than a promise — OkHttp cannot even inherit a system/carrier HTTP proxy
 * through this client.
 *
 * The reason a direct GitHub fetch fails on an Iranian ISP is almost always
 * **DNS poisoning**, so we attack that directly instead of routing around it:
 * hostnames resolve through **Cloudflare DoH** ([CfDns]) — the one external
 * service the brief permits — and OkHttp then dials the TRUE origin address with
 * correct SNI and full certificate validation. Encrypted lookup, authenticated
 * origin, one hop, nobody in the middle.
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

    private const val UA = "ProfessorVPN/7 (Android)"

    /**
     * The shared client. Built lazily and reused for the whole process so the
     * connection pool and TLS sessions actually pay off.
     *
     * v6.9 — TIMEOUTS CUT ROUGHLY IN HALF, and this is a deliberate, load-bearing
     * change rather than a tweak. The reported bug is «بالای ۵ دقیقه باید صبر
     * کنیم تا کانفیگ‌ها را از منابع بگیرد». On a filtered link most feed fetches
     * either answer within about a second or never answer at all — the middle
     * ground barely exists. v6.8's 15 s call timeout therefore did nothing except
     * make every DEAD feed cost fifteen seconds of the user's life, and with
     * dozens of feeds walked per run that is exactly where the five minutes went.
     *
     * A 7 s call ceiling still comfortably covers a healthy high-RTT Iranian
     * mobile fetch (measured: 0.4–2.5 s for these files) while capping the cost of
     * a dead one at less than half of what it was. Combined with the parallel
     * fetching in [com.neonvpn.app.config.FreeConfigSource] and the negative cache
     * in [com.neonvpn.app.config.SourceFetcher], the worst case collapses from
     * minutes to seconds.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Cloudflare DoH resolution — defeats ISP DNS poisoning without a proxy.
            .dns(CfDns)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            // Reuse connections aggressively: feed fetching is many small GETs to
            // a handful of hosts, so pooling is the single biggest speed win here.
            // v6.9 — pool widened 8 → 24 because v6.9 fetches feeds in PARALLEL;
            // a pool smaller than the fan-out would serialise what we just made
            // concurrent.
            .connectionPool(okhttp3.ConnectionPool(24, 5, TimeUnit.MINUTES))
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
