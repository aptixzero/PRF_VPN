package com.neonvpn.app.config

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resilient single-source fetcher for the [LiveSources] feeds.
 *
 * v6.6 — **PROXY-FREE.** Iranian ISPs mostly block
 * `raw.githubusercontent.com` by **DNS poisoning**, not by blocking the address,
 * so the fix is to learn the true address over an encrypted channel rather than
 * to hand the request to somebody else's server. Fetches go through
 * [com.neonvpn.app.net.DirectHttp], which resolves via Cloudflare DoH
 * ([com.neonvpn.app.net.CfDns]) and connects straight to the origin with full
 * certificate validation. The old chain of public reverse proxies
 * (`r.jina.ai`, `allorigins`, `ghproxy`, …) is gone — see [mirrorCandidates].
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

    /**
     * v6.6 — **NO PROXIES.** The candidate list is now the origin plus GitHub's
     * own read-only CDN, and nothing else.
     *
     * WHAT WAS REMOVED AND WHY: v6.5 appended `ghproxy.net`,
     * `gh.api.99988866.xyz`, `cors.isomorphic-git.org`, `r.jina.ai` and
     * `api.allorigins.win`. Those are third-party reverse proxies that read (and
     * could rewrite) every config before it reached the user, they are heavily
     * rate-limited, and they are themselves blocked or throttled from Iran — so
     * the "fallback" usually failed *after* burning a full timeout each. Five dead
     * candidates at ~9 s apiece is most of a minute per source, which is exactly
     * the reported slowness.
     *
     * WHAT REPLACES THEM: the real blocker on Iranian ISPs is DNS poisoning of
     * `raw.githubusercontent.com`, and [com.neonvpn.app.net.CfDns] defeats that
     * directly by resolving over encrypted Cloudflare DoH and dialling the true
     * origin with full certificate validation. So the ORIGIN itself now succeeds
     * in the case that used to need a proxy.
     *
     * `cdn.jsdelivr.net` is kept as the single fallback. It is not a proxy: it is
     * GitHub's widely-used immutable CDN, serving the same repository file over
     * its own anycast edge, and it stays reachable when the GitHub apex is
     * throttled. One fallback that works beats five that don't.
     */
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
            }
        }
        return out.toList()
    }

    /**
     * v6.6 — fetched through the shared [com.neonvpn.app.net.DirectHttp] client:
     * Cloudflare-DoH resolution (beats DNS poisoning), a real connection pool and
     * TLS session reuse (so the 2nd..Nth source fetch skips the handshake), and
     * absolutely no proxy in the path.
     */
    private fun fetchOne(urlStr: String): String? =
        com.neonvpn.app.net.DirectHttp.get(urlStr)
}
