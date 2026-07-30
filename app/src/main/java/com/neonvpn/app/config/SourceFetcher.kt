package com.neonvpn.app.config

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v6.9 — DIRECT-ONLY source fetcher. **ZERO INTERMEDIARIES.**
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT v6.9 REMOVED, AND WHY
 * ─────────────────────────────────────────────────────────────────────────────
 * The v6.9 brief is explicit: «لطفا از سایت واسط یا پروکسی استفاده نکن … و کلا
 * این آدرس رو حذف کن … درون برنامه نباید از پروکسی استفاده کنی، نباید از واسط
 * استفاده کنی».
 *
 * So this file now contains exactly ONE candidate per source: **the origin URL
 * itself**. Nothing else. There is no proxy, no CORS bridge, no text-extraction
 * relay, and — new in v6.9 — no third-party CDN mirror either. v6.8 still kept
 * `cdn.jsdelivr.net` as a "not really a proxy" fallback; it is still somebody
 * else's server sitting between the user and the config list, and it still cost
 * a full extra timeout on every dead source. Both reasons are enough to delete
 * it, so it is gone.
 *
 * The only thing standing between the app and the origin is
 * [com.neonvpn.app.net.DirectHttp], which is not an intermediary at all: it
 * resolves the hostname over encrypted **Cloudflare DoH** ([com.neonvpn.app.net.CfDns])
 * — the one outside service the brief explicitly permits — and then dials the
 * REAL origin address itself, with correct SNI and full certificate validation.
 * That defeats the actual blocking mechanism on Iranian ISPs (DNS poisoning)
 * without handing anybody the config stream.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY v6.9 IS DRAMATICALLY FASTER HERE
 * ─────────────────────────────────────────────────────────────────────────────
 *  1. **One candidate, not two.** A dead source now costs ONE timeout, not two.
 *  2. **A short-lived body cache** ([cache]). The Auto-Test probe, the batch
 *     builder and the engine all read the same handful of feeds within seconds
 *     of each other. v6.8 re-downloaded each of them every single time. v6.9
 *     serves the second and third read from memory instantly, which removes
 *     most of the wall-clock from the "collect 240 configs" phase.
 *  3. **A negative cache** ([failed]). A feed that just failed is not retried
 *     for [FAIL_TTL_MS]; there is no point paying its timeout again 3 seconds
 *     later, and that repeated payment is precisely what made the connection
 *     test crawl.
 *  4. **Parsed-link memoisation** ([linkCache]). `extractLinks` used to re-parse
 *     a multi-megabyte feed body for every caller. Now the extracted link list
 *     is cached per (body, kind).
 *
 * Every call is exception-safe and returns `null` on total failure — one dead
 * source can never crash the batch builder or the Auto-Test probe.
 */
object SourceFetcher {

    private const val TAG = "SourceFetcher"

    /** How long a successfully-fetched body may be reused from memory. */
    private const val BODY_TTL_MS = 90_000L

    /** How long a failed source is skipped before we try it again. */
    private const val FAIL_TTL_MS = 30_000L

    private class Cached(val body: String, val at: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Parsed-link memo. Key is `kind + '|' + body.length + '|' + body.hashCode()`
     * so it is cheap to compute and effectively collision-free for our use.
     */
    private val linkCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** Bound the memo so a long Auto-Test session cannot grow it forever. */
    private const val MAX_LINK_CACHE = 96

    /**
     * Fetch a source URL. Returns the body or null.
     *
     * v6.9 — served from the in-memory cache when we already downloaded it in the
     * last [BODY_TTL_MS], and skipped outright when it failed in the last
     * [FAIL_TTL_MS]. Both are pure speed: the verdicts are identical.
     */
    suspend fun fetch(url: String, allowCache: Boolean = true): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        if (allowCache) {
            cache[url]?.let { c ->
                if (now - c.at < BODY_TTL_MS && c.body.isNotBlank()) return@withContext c.body
            }
            failed[url]?.let { at ->
                if (now - at < FAIL_TTL_MS) return@withContext null
            }
        }

        // ONE candidate: the origin. No mirrors, no proxies, no relays.
        val body = try {
            com.neonvpn.app.net.DirectHttp.get(url)
        } catch (e: Throwable) {
            Log.w(TAG, "fetch failed for $url: ${e.message}")
            null
        }

        if (body.isNullOrBlank()) {
            failed[url] = now
            return@withContext null
        }
        failed.remove(url)
        cache[url] = Cached(body, now)
        body
    }

    /**
     * Parse a fetched source body into ONLY the vless/vmess raw links that match
     * the requested [kind]. Blank / comment lines and unsupported schemes are
     * dropped. The ORIGINAL link is kept verbatim (payload never rewritten).
     *
     * v6.9 — memoised. The same feed body is handed to this function by the
     * connection test, the batch builder and the engine within a few seconds;
     * re-parsing a multi-megabyte body three times was pure waste.
     */
    fun extractLinks(body: String, kind: LiveSources.Kind, limit: Int = Int.MAX_VALUE): List<String> {
        val memoKey = "${kind.name}|${body.length}|${body.hashCode()}"
        linkCache[memoKey]?.let { cached ->
            return if (cached.size <= limit) cached else cached.subList(0, limit)
        }
        val out = extractLinksUncached(body, kind)
        if (linkCache.size > MAX_LINK_CACHE) linkCache.clear()
        linkCache[memoKey] = out
        return if (out.size <= limit) out else out.subList(0, limit)
    }

    private fun extractLinksUncached(body: String, kind: LiveSources.Kind): List<String> {
        val want = if (kind == LiveSources.Kind.VLESS) "vless" else "vmess"
        val out = ArrayList<String>(512)
        // v6.9 — FAST PATH FIRST. The overwhelming majority of these feeds are
        // plain "one link per line" text, and a line scan is far cheaper than the
        // full base64/subscription decoder. So we scan lines first and only fall
        // back to parseMany (which handles base64-wrapped subscription blobs) when
        // the line scan found nothing. v6.8 did it the other way round and paid the
        // heavy decoder on every single feed.
        val marker = "$want://"
        if (body.contains(marker)) {
            for (rawLine in body.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
                if (!line.startsWith(marker, ignoreCase = true)) continue
                val cfg = try { ConfigParser.parseSingleSafe(line) } catch (_: Throwable) { null } ?: continue
                if (cfg.protocol != want) continue
                if (cfg.address.isBlank() || cfg.port !in 1..65535 || cfg.userId.isBlank()) continue
                out.add(line)
            }
            if (out.isNotEmpty()) return out
        }

        // Slow path: base64 subscription blob / mixed content.
        val parsed = try { ConfigParser.parseMany(body) } catch (_: Throwable) { emptyList() }
        for (cfg in parsed) {
            if (cfg.protocol != want) continue
            if (cfg.address.isBlank() || cfg.port !in 1..65535 || cfg.userId.isBlank()) continue
            if (cfg.rawLink.isNotBlank()) out.add(cfg.rawLink)
        }
        return out
    }

    /** Drop every cached body / failure note (used on a material network change). */
    fun invalidate() {
        cache.clear()
        failed.clear()
        linkCache.clear()
    }
}
