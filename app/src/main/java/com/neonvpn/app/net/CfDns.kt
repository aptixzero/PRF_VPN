package com.neonvpn.app.net

import android.util.Log
import okhttp3.Dns
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * v6.6 — CLOUDFLARE DNS-over-HTTPS RESOLVER. **NO PROXIES, ANYWHERE.**
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY THIS REPLACES THE OLD PROXY MIRROR CHAINS
 * ─────────────────────────────────────────────────────────────────────────────
 * Up to v6.5 every feed / panel fetch that failed was retried through a chain of
 * public reverse proxies — `r.jina.ai`, `api.allorigins.win`, `ghproxy.net`,
 * `cors.isomorphic-git.org`, `gh.api.99988866.xyz`. The explicit instruction for
 * v6.6 is that those must go, and they deserve to:
 *
 *   • they are third-party servers that see and can rewrite every byte of the
 *     configs the user is about to route their traffic through;
 *   • they are rate-limited, frequently down, and slow — a dead proxy still had
 *     to time out before the next was tried, so a fetch could take 30-60 s;
 *   • most are themselves blocked or throttled from Iran, so the fallback that
 *     was supposed to rescue a blocked fetch usually failed as well.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT WE DO INSTEAD — ATTACK THE ACTUAL BLOCKING MECHANISM
 * ─────────────────────────────────────────────────────────────────────────────
 * The dominant way `raw.githubusercontent.com` is blocked on Iranian ISPs is
 * **DNS poisoning**: the ISP resolver returns a bogus/blackhole address, so the
 * connection never reaches GitHub. The host itself is usually perfectly
 * reachable *if you learn its real address*.
 *
 * So instead of routing content through somebody else's server, we simply ask
 * **Cloudflare** (`1.1.1.1`, by IP literal, over HTTPS) for the real address and
 * connect there directly:
 *
 *   1. the DoH query is encrypted, so the ISP cannot see or forge the answer;
 *   2. it is addressed to an IP literal, so it needs no resolver to bootstrap;
 *   3. OkHttp then dials the returned address while still presenting the correct
 *      SNI and validating the real certificate — an ordinary, direct, fully
 *      authenticated TLS connection to the true origin.
 *
 * The content therefore comes straight from the origin, nobody in the middle can
 * tamper with it, and it is fast because there is no extra hop. This is the same
 * technique every serious anti-censorship client uses, and it needs no proxy.
 *
 * Answers are cached in memory for [TTL_MS] so a batch of fetches to the same
 * host pays for one lookup.
 */
object CfDns : Dns {

    private const val TAG = "CfDns"

    /** Cloudflare DoH JSON endpoints, IP-literal so no bootstrap lookup is needed. */
    private val DOH_ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query",
        "https://1.0.0.1/dns-query"
    )

    private const val TTL_MS = 10 * 60 * 1000L

    private data class Entry(val addrs: List<InetAddress>, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        // An IP literal needs no resolution at all.
        if (isIpLiteral(hostname)) {
            return try {
                listOf(InetAddress.getByName(hostname))
            } catch (e: Throwable) {
                throw UnknownHostException(hostname)
            }
        }

        cache[hostname]?.let { e ->
            if (System.currentTimeMillis() - e.at < TTL_MS && e.addrs.isNotEmpty()) return e.addrs
        }

        // 1) Cloudflare DoH first — this is what defeats ISP DNS poisoning.
        val viaDoh = resolveViaDoh(hostname)
        if (viaDoh.isNotEmpty()) {
            cache[hostname] = Entry(viaDoh, System.currentTimeMillis())
            return viaDoh
        }

        // 2) Fall back to the system resolver. On an unfiltered link (or a link
        //    where DoH itself is blocked) this is perfectly fine, so we must not
        //    fail hard just because the DoH query didn't get through.
        val viaSystem = try {
            Dns.SYSTEM.lookup(hostname).filter { it.address.size == 4 }
                .ifEmpty { Dns.SYSTEM.lookup(hostname) }
        } catch (e: Throwable) {
            emptyList()
        }
        if (viaSystem.isNotEmpty()) {
            cache[hostname] = Entry(viaSystem, System.currentTimeMillis())
            return viaSystem
        }
        throw UnknownHostException(hostname)
    }

    /**
     * Ask Cloudflare for the A records of [hostname] over HTTPS.
     *
     * Uses the JSON DoH API (`Accept: application/dns-json`) so no wire-format
     * DNS encoder is needed and the whole resolver stays dependency-free.
     * IPv4-only, matching the app-wide `UseIPv4` policy (almost no free node
     * relays IPv6, so an AAAA answer is a guaranteed stall).
     */
    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        for (base in DOH_ENDPOINTS) {
            try {
                val url = java.net.URL("$base?name=" + enc(hostname) + "&type=A")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/dns-json")
                    setRequestProperty("User-Agent", "ProfessorVPN/6.6 (Android)")
                }
                var body: String? = null
                try {
                    if (conn.responseCode in 200..299) {
                        body = conn.inputStream.bufferedReader().use { it.readText() }
                    }
                } finally {
                    runCatching { conn.disconnect() }
                }
                if (body == null) continue

                val out = ArrayList<InetAddress>(4)
                val answers = JSONObject(body).optJSONArray("Answer") ?: continue
                for (i in 0 until answers.length()) {
                    val a = answers.optJSONObject(i) ?: continue
                    // type 1 == A record. Ignore CNAME (5) chain entries.
                    if (a.optInt("type") != 1) continue
                    val ip = a.optString("data").trim()
                    if (ip.isBlank() || !isIpLiteral(ip)) continue
                    runCatching {
                        // getAllByName on a literal never touches the network.
                        out.add(InetAddress.getByName(ip))
                    }
                }
                if (out.isNotEmpty()) {
                    Log.d(TAG, "DoH resolved $hostname -> ${out.map { it.hostAddress }}")
                    return out
                }
            } catch (e: Throwable) {
                Log.w(TAG, "DoH lookup failed for $hostname via $base: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun enc(s: String): String =
        try { java.net.URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }

    private fun isIpLiteral(s: String): Boolean {
        if (s.isBlank()) return false
        // IPv4 dotted quad
        if (s.count { it == '.' } == 3 && s.all { it.isDigit() || it == '.' }) {
            return s.split('.').all { p -> p.isNotEmpty() && (p.toIntOrNull() ?: -1) in 0..255 }
        }
        // IPv6 (rough but sufficient — only used to skip resolution)
        return s.contains(':')
    }

    /** Drop every cached answer (used when connectivity changes materially). */
    fun clearCache() = cache.clear()
}
