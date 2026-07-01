package com.neonvpn.app.config

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * v4.6 — resilient single-source fetcher for the [LiveSources] feeds.
 *
 * Iranian mobile links frequently throttle / DNS-poison `raw.githubusercontent.com`,
 * so a direct GET silently times out. We therefore try the origin URL first and,
 * on failure, retry through a chain of edge-cached CDNs / reverse proxies that
 * stay reachable inside Iran. The first mirror that returns a usable body wins.
 *
 * Every call is exception-safe and returns `null` on total failure — one dead
 * source can never crash the batch builder or the Auto-Test probe.
 */
object SourceFetcher {

    private const val TAG = "SourceFetcher"

    /** Fetch a source URL (with mirror fallback). Returns the body or null. */
    suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        for (candidate in mirrorCandidates(url)) {
            val body = try { fetchOne(candidate) } catch (e: Throwable) {
                Log.w(TAG, "fetch failed for $candidate: ${e.message}"); null
            }
            if (!body.isNullOrBlank()) return@withContext body
        }
        null
    }

    /**
     * Parse a fetched source body into ONLY the vless/vmess raw links that match
     * the requested [kind]. Blank / comment lines and unsupported schemes are
     * dropped. The ORIGINAL link is kept verbatim (payload never rewritten).
     */
    fun extractLinks(body: String, kind: LiveSources.Kind, limit: Int = Int.MAX_VALUE): List<String> {
        val want = if (kind == LiveSources.Kind.VLESS) "vless" else "vmess"
        val out = ArrayList<String>(256)
        // Some feeds publish a base64 subscription blob rather than one link per
        // line. parseMany handles both, so we run it first and fall back to a
        // line scan when it yields nothing.
        val parsed = try { ConfigParser.parseMany(body) } catch (_: Throwable) { emptyList() }
        if (parsed.isNotEmpty()) {
            for (cfg in parsed) {
                if (out.size >= limit) break
                if (cfg.protocol != want) continue
                if (cfg.address.isBlank() || cfg.port !in 1..65535 || cfg.userId.isBlank()) continue
                if (cfg.rawLink.isNotBlank()) out.add(cfg.rawLink)
            }
            if (out.isNotEmpty()) return out
        }
        for (rawLine in body.lineSequence()) {
            if (out.size >= limit) break
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val cfg = try { ConfigParser.parseSingleSafe(line) } catch (_: Throwable) { null } ?: continue
            if (cfg.protocol != want) continue
            if (cfg.address.isBlank() || cfg.port !in 1..65535 || cfg.userId.isBlank()) continue
            out.add(line)
        }
        return out
    }

    private fun mirrorCandidates(urlStr: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(urlStr)
        val rawPrefix = "https://raw.githubusercontent.com/"
        if (urlStr.startsWith(rawPrefix)) {
            val rest = urlStr.substring(rawPrefix.length)
            // strip a possible /refs/heads/ segment for jsDelivr (@branch form)
            val parts = rest.split('/')
            if (parts.size >= 4) {
                val user = parts[0]; val repo = parts[1]
                var branchIdx = 2
                var branch = parts[2]
                if (parts.size >= 5 && parts[2] == "refs" && parts[3] == "heads") {
                    branch = parts[4]; branchIdx = 4
                }
                val path = parts.drop(branchIdx + 1).joinToString("/")
                out.add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path")
                out.add("https://fastly.jsdelivr.net/gh/$user/$repo@$branch/$path")
                out.add("https://gcore.jsdelivr.net/gh/$user/$repo@$branch/$path")
                out.add("https://cdn.statically.io/gh/$user/$repo/$branch/$path")
            }
        }
        // Generic reverse-proxy mirrors for any origin host.
        out.add("https://ghproxy.net/$urlStr")
        out.add("https://gh.api.99988866.xyz/$urlStr")
        out.add("https://cors.isomorphic-git.org/$urlStr")
        out.add("https://r.jina.ai/$urlStr")
        out.add("https://api.allorigins.win/raw?url=" + urlEncode(urlStr))
        return out.toList()
    }

    private fun urlEncode(s: String): String =
        try { java.net.URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }

    private fun fetchOne(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 9000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "v2rayNG/1.8.5 (Android)")
            setRequestProperty("Accept", "*/*")
        }
        if (conn is HttpsURLConnection) { /* default trust is fine for public CDNs */ }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) { Log.w(TAG, "HTTP $code for $urlStr"); return null }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }
}
