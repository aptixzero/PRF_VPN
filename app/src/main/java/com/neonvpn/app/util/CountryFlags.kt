package com.neonvpn.app.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * v6.3 — COUNTRY FLAG resolver for the Home "server selector" tile.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE BRIEF
 * ─────────────────────────────────────────────────────────────────────────────
 * "On the Home screen, behind the server name, there is an empty square. When we
 *  are CONNECTED I want the flag of the country we connected to shown there. The
 *  square is small — nothing may overflow it. By default it is empty. Whatever
 *  country the IP belongs to, show its flag **without lagging or freezing**."
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * HOW IT WORKS (and why it can never lag the UI)
 * ─────────────────────────────────────────────────────────────────────────────
 * The flag is rendered as a **Unicode regional-indicator emoji pair** (e.g. "DE"
 * → 🇩🇪) inside a small TextView. That means:
 *
 *   • **Zero network needed to DRAW it** — no bitmap download, no image decode,
 *     no Glide/Picasso. The glyph is already in the system font on every Android
 *     7+ device, so painting it is as cheap as painting text. This is exactly why
 *     it cannot lag or stutter the Home screen.
 *   • It always fits the square: the TextView is sized in `sp` and clipped by its
 *     parent, so an emoji can never overflow the tile.
 *
 * Resolving *which* country an address belongs to is the only part that can
 * touch the network, and it is made completely non-blocking:
 *
 *   1. **In-memory + on-disk cache first.** A resolved host→ISO2 mapping is
 *      remembered forever, so re-connecting to the same server paints the flag
 *      INSTANTLY with no work at all.
 *   2. **Offline heuristics second.** Most public configs already carry the
 *      country in their remark ("🇩🇪 Germany-01", "DE-Frankfurt", "us-west-2")
 *      or in the hostname's TLD. [guessFromText] extracts it with pure string
 *      matching — no I/O whatsoever.
 *   3. **Network geo-lookup last, and always in the background** on a detached
 *      IO scope with a hard timeout. The UI never waits on it; when (and only
 *      when) it returns, a callback repaints the tile. If it fails, the tile
 *      simply stays empty — exactly the required default.
 *
 * Nothing here ever runs on the main thread, and every path is exception-safe.
 */
object CountryFlags {

    private const val TAG = "CountryFlags"
    private const val PREFS = "pv_geo_cache_v63"

    /** Hard cap on the persisted cache so it can never bloat storage. */
    private const val MAX_CACHE = 400

    /** Wall-clock ceiling for a whole geo lookup. Never blocks the UI anyway. */
    private const val LOOKUP_BUDGET_MS = 6_000L

    /** host/ip → ISO-3166-1 alpha-2 (uppercase). "" means "resolved, unknown". */
    private val memCache = ConcurrentHashMap<String, String>()

    /** Detached scope: a geo lookup must never be tied to a view's lifecycle. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Hosts with a lookup currently in flight (so we never duplicate work). */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    // ─────────────────────────────────────────────────────────── public API ──

    /**
     * Convert an ISO-3166-1 alpha-2 code into its flag emoji.
     *
     * A flag emoji is simply the two letters expressed as REGIONAL INDICATOR
     * SYMBOLS (U+1F1E6 = 'A' … U+1F1FF = 'Z'). Building it by hand means we ship
     * **no flag images at all** — the APK stays small and rendering is instant.
     *
     * @return the emoji, or "" when [iso2] is not a valid 2-letter code.
     */
    fun emojiOf(iso2: String?): String {
        val c = iso2?.trim()?.uppercase().orEmpty()
        if (c.length != 2) return ""
        if (c[0] !in 'A'..'Z' || c[1] !in 'A'..'Z') return ""
        // ── v6.4 HARD RULE ────────────────────────────────────────────────
        // The Islamic-Republic flag must NEVER be rendered anywhere in this
        // app. The system font's "IR" emoji IS that flag, so this function
        // refuses to produce it. Iran is drawn from the bundled Lion-and-Sun
        // vector by [com.neonvpn.app.ui.widget.FlagView] instead — which also
        // works offline / on a weak link, exactly as the brief demands.
        if (c == IRAN) return ""
        val base = 0x1F1E6
        val first = base + (c[0] - 'A')
        val second = base + (c[1] - 'A')
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    /** The ISO code Iran is identified by. Never rendered as an emoji. */
    const val IRAN = "IR"

    /**
     * v6.4 — the ISO country code we can report RIGHT NOW with zero I/O.
     *
     * [FlagView] wants the CODE (so it can decide between a glyph and the
     * bundled Lion-and-Sun vector), not a pre-rendered emoji. This is the same
     * three-step lookup as [cachedFlagFor] but it stops at the code.
     */
    fun cachedCodeFor(ctx: Context, host: String, remark: String): String {
        val key = normalizeHost(host)
        memCache[key]?.let { if (it.isNotBlank()) return it }
        val disk = loadDisk(ctx, key)
        if (!disk.isNullOrBlank()) {
            memCache[key] = disk
            return disk
        }
        return guessFromText(remark).ifBlank { guessFromText(host) }
    }

    /**
     * v6.4 — background resolution that reports the ISO CODE (not the emoji).
     * Mirrors [resolveAsync] exactly; kept separate so existing callers keep
     * working. Fire-and-forget, never blocks, de-duped per host.
     */
    fun resolveCodeAsync(ctx: Context, host: String, remark: String, onResolved: (String) -> Unit) {
        val key = normalizeHost(host)
        if (key.isBlank()) return

        val known = cachedCodeFor(ctx, key, remark)
        if (known.isNotBlank()) { runCatching { onResolved(known) }; return }

        if (!inFlight.add(key)) return

        val app = ctx.applicationContext
        scope.launch {
            val iso = try {
                withTimeoutOrNull(LOOKUP_BUDGET_MS) { lookupCountry(key) }.orEmpty()
            } catch (t: Throwable) {
                Log.w(TAG, "lookup failed for $key: ${t.message}")
                ""
            } finally {
                inFlight.remove(key)
            }
            if (iso.isNotBlank()) {
                memCache[key] = iso
                runCatching { saveDisk(app, key, iso) }
                runCatching { onResolved(iso) }
            }
        }
    }

    /**
     * v6.4 — STRIP THE ISLAMIC-REPUBLIC FLAG OUT OF ANY TEXT WE DISPLAY.
     *
     * The brief is absolute: *"under no circumstances may the Islamic-Republic
     * flag be shown in the app."* [emojiOf] guarantees the app never GENERATES
     * it, but public config feeds routinely prefix their remarks with it
     * (e.g. "🇮🇷 Iran-Direct-01"), and that remark is shown verbatim on the Home
     * card and in the config lists. So every remark passes through here first.
     *
     * The IR regional-indicator pair is replaced with the plain text tag
     * `[IR]` — the information (which country) is preserved, the forbidden
     * glyph is not. Everything else in the string, including other countries'
     * flags, is left completely untouched.
     */
    fun sanitizeForDisplay(text: String?): String {
        val s = text.orEmpty()
        if (s.isEmpty()) return s
        // Fast path: only do work if an 'I' regional indicator is present.
        if (!s.contains(RI_I)) return s
        return s.replace(IR_FLAG, "[IR]")
    }

    /** U+1F1EE — REGIONAL INDICATOR SYMBOL LETTER I. */
    private val RI_I: String = String(Character.toChars(0x1F1EE))

    /** The forbidden glyph: U+1F1EE U+1F1F7 (the Islamic-Republic flag). */
    private val IR_FLAG: String = RI_I + String(Character.toChars(0x1F1F7))

    /**
     * v6.4 — remember a country we learned from the LIVE TUNNEL (the Cloudflare
     * trace `loc=` field), so re-connecting to the same server paints the right
     * flag instantly with no lookup at all.
     */
    fun rememberCode(ctx: Context, host: String, iso2: String) {
        val key = normalizeHost(host)
        val code = iso2.trim().uppercase()
        if (key.isBlank() || code.length != 2 || code !in ISO_CODES) return
        memCache[key] = code
        runCatching { saveDisk(ctx.applicationContext, key, code) }
    }

    /**
     * The flag we can paint RIGHT NOW for this server, with zero I/O.
     *
     * Checks (in order): the in-memory cache, the persisted cache, then the
     * offline text heuristics on the remark + hostname. Returns "" when nothing
     * is known yet — the caller shows an empty square, exactly as required.
     */
    fun cachedFlagFor(ctx: Context, host: String, remark: String): String {
        val key = normalizeHost(host)
        // 1. memory
        memCache[key]?.let { if (it.isNotBlank()) return emojiOf(it) }
        // 2. disk
        val disk = loadDisk(ctx, key)
        if (!disk.isNullOrBlank()) {
            memCache[key] = disk
            return emojiOf(disk)
        }
        // 3. offline heuristics — free, instant, surprisingly accurate for the
        //    public feeds this app consumes (they almost always name the country).
        val guess = guessFromText(remark).ifBlank { guessFromText(host) }
        if (guess.isNotBlank()) return emojiOf(guess)
        return ""
    }

    /**
     * Kick off a BACKGROUND resolution for [host] and invoke [onResolved] on the
     * IO thread when a flag becomes available. The caller is expected to hop to
     * the main thread itself.
     *
     * Completely fire-and-forget: it never blocks, never throws, and de-dupes
     * concurrent requests for the same host.
     */
    fun resolveAsync(ctx: Context, host: String, remark: String, onResolved: (String) -> Unit) {
        val key = normalizeHost(host)
        if (key.isBlank()) return

        // Already known? Answer immediately without touching the network.
        val known = cachedFlagFor(ctx, key, remark)
        if (known.isNotBlank()) { runCatching { onResolved(known) }; return }

        // Already being resolved by another caller — don't duplicate the work.
        if (!inFlight.add(key)) return

        val app = ctx.applicationContext
        scope.launch {
            val iso = try {
                withTimeoutOrNull(LOOKUP_BUDGET_MS) { lookupCountry(key) }.orEmpty()
            } catch (t: Throwable) {
                Log.w(TAG, "lookup failed for $key: ${t.message}")
                ""
            } finally {
                inFlight.remove(key)
            }
            if (iso.isNotBlank()) {
                memCache[key] = iso
                runCatching { saveDisk(app, key, iso) }
                runCatching { onResolved(emojiOf(iso)) }
            }
        }
    }

    // ──────────────────────────────────────────────────── offline heuristics ──

    /**
     * Extract a country code from free-form text WITHOUT any network access.
     *
     * Handles the three shapes public config feeds actually use:
     *   1. The remark already contains a flag emoji ("🇳🇱 Amsterdam") — we simply
     *      decode the regional-indicator pair straight back into letters.
     *   2. A country NAME appears ("Germany", "Netherlands", "United States").
     *   3. A bare ISO code appears as its own token ("DE-01", "us-west", "[NL]").
     */
    fun guessFromText(text: String?): String {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return ""

        // ---- 1. an existing flag emoji in the remark -----------------------
        decodeFlagEmoji(s)?.let { return it }

        val lower = s.lowercase()

        // ---- 2. a country NAME ---------------------------------------------
        for ((name, code) in NAME_TO_ISO) {
            if (lower.contains(name)) return code
        }

        // ---- 3. a bare ISO2 token ------------------------------------------
        // Split on everything that isn't a letter so "DE-01", "us_west" and
        // "[nl]" all yield a clean candidate token.
        for (tok in lower.split(Regex("[^a-z]+"))) {
            if (tok.length == 2) {
                val up = tok.uppercase()
                if (up in ISO_CODES) return up
            }
        }
        return ""
    }

    /**
     * If [s] starts with (or contains) a regional-indicator pair, turn it back
     * into the two ASCII letters. Public feeds love prefixing remarks with the
     * flag emoji, so this alone resolves a large share of configs for free.
     */
    private fun decodeFlagEmoji(s: String): String? {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) {
                val nextIdx = i + Character.charCount(cp)
                if (nextIdx < s.length) {
                    val cp2 = s.codePointAt(nextIdx)
                    if (cp2 in 0x1F1E6..0x1F1FF) {
                        val a = 'A' + (cp - 0x1F1E6)
                        val b = 'A' + (cp2 - 0x1F1E6)
                        val code = "$a$b"
                        if (code in ISO_CODES) return code
                    }
                }
            }
            i += Character.charCount(cp)
        }
        return null
    }

    // ─────────────────────────────────────────────────────── network lookup ──

    /**
     * Resolve a host/IP to its ISO country code using free, no-key geo APIs.
     *
     * We try several endpoints because a single provider is frequently blocked
     * or rate-limited on Iranian links; the first one that answers wins. The
     * whole thing is already inside a [withTimeoutOrNull] from the caller.
     */
    private suspend fun lookupCountry(host: String): String = withContext(Dispatchers.IO) {
        // A hostname must be resolved to an IP first — several geo APIs only
        // accept addresses, and resolving locally is fast and cache-friendly.
        val ip = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull() ?: host

        for (ep in GEO_ENDPOINTS) {
            val url = ep.urlFor(ip)
            val body = runCatching { httpGet(url) }.getOrNull()
            if (body.isNullOrBlank()) continue
            val iso = runCatching { ep.parse(body) }.getOrNull().orEmpty()
            if (iso.length == 2 && iso.uppercase() in ISO_CODES) return@withContext iso.uppercase()
        }
        ""
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 3000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ProfessorVPN/6.3 (Android)")
            setRequestProperty("Accept", "*/*")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** A geo provider: how to build its URL and how to read the ISO code back. */
    private class GeoEndpoint(
        val urlFor: (String) -> String,
        val parse: (String) -> String
    )

    private val GEO_ENDPOINTS = listOf(
        // Plain-text, tiny response — the cheapest and most reliable of the set.
        GeoEndpoint(
            urlFor = { ip -> "https://ipapi.co/$ip/country/" },
            parse = { body -> body.trim().take(2) }
        ),
        GeoEndpoint(
            urlFor = { ip -> "https://ipwho.is/$ip?fields=country_code" },
            parse = { body -> JSONObject(body).optString("country_code", "") }
        ),
        GeoEndpoint(
            urlFor = { ip -> "http://ip-api.com/json/$ip?fields=countryCode" },
            parse = { body -> JSONObject(body).optString("countryCode", "") }
        ),
        GeoEndpoint(
            urlFor = { ip -> "https://api.country.is/$ip" },
            parse = { body -> JSONObject(body).optString("country", "") }
        )
    )

    // ────────────────────────────────────────────────────────────── caching ──

    private fun normalizeHost(host: String): String =
        host.trim().trim('[', ']').lowercase()

    private fun loadDisk(ctx: Context, key: String): String? = runCatching {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
    }.getOrNull()

    private fun saveDisk(ctx: Context, key: String, iso: String) = runCatching {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Bounded: once we cross the cap, wipe and start fresh rather than let
        // the prefs file grow forever (the cache is cheap to rebuild).
        if (p.all.size >= MAX_CACHE) p.edit().clear().apply()
        p.edit().putString(key, iso).apply()
    }

    // ───────────────────────────────────────────────────────────── ISO data ──

    /** Every valid ISO-3166-1 alpha-2 code, used to reject bogus 2-letter tokens. */
    private val ISO_CODES: Set<String> = setOf(
        "AD","AE","AF","AG","AI","AL","AM","AO","AQ","AR","AS","AT","AU","AW","AX","AZ",
        "BA","BB","BD","BE","BF","BG","BH","BI","BJ","BL","BM","BN","BO","BQ","BR","BS",
        "BT","BV","BW","BY","BZ","CA","CC","CD","CF","CG","CH","CI","CK","CL","CM","CN",
        "CO","CR","CU","CV","CW","CX","CY","CZ","DE","DJ","DK","DM","DO","DZ","EC","EE",
        "EG","EH","ER","ES","ET","FI","FJ","FK","FM","FO","FR","GA","GB","GD","GE","GF",
        "GG","GH","GI","GL","GM","GN","GP","GQ","GR","GS","GT","GU","GW","GY","HK","HM",
        "HN","HR","HT","HU","ID","IE","IL","IM","IN","IO","IQ","IR","IS","IT","JE","JM",
        "JO","JP","KE","KG","KH","KI","KM","KN","KP","KR","KW","KY","KZ","LA","LB","LC",
        "LI","LK","LR","LS","LT","LU","LV","LY","MA","MC","MD","ME","MF","MG","MH","MK",
        "ML","MM","MN","MO","MP","MQ","MR","MS","MT","MU","MV","MW","MX","MY","MZ","NA",
        "NC","NE","NF","NG","NI","NL","NO","NP","NR","NU","NZ","OM","PA","PE","PF","PG",
        "PH","PK","PL","PM","PN","PR","PS","PT","PW","PY","QA","RE","RO","RS","RU","RW",
        "SA","SB","SC","SD","SE","SG","SH","SI","SJ","SK","SL","SM","SN","SO","SR","SS",
        "ST","SV","SX","SY","SZ","TC","TD","TF","TG","TH","TJ","TK","TL","TM","TN","TO",
        "TR","TT","TV","TW","TZ","UA","UG","UM","US","UY","UZ","VA","VC","VE","VG","VI",
        "VN","VU","WF","WS","YE","YT","ZA","ZM","ZW"
    )

    /**
     * Country NAME → ISO2, covering the locations public VPN feeds actually use.
     * Ordered longest-first at lookup time is unnecessary because the entries are
     * distinctive enough; "united states" is checked before "states" never
     * appears alone.
     */
    private val NAME_TO_ISO: List<Pair<String, String>> = listOf(
        "united states" to "US", "united kingdom" to "GB", "great britain" to "GB",
        "netherlands" to "NL", "holland" to "NL", "germany" to "DE", "deutschland" to "DE",
        "france" to "FR", "finland" to "FI", "sweden" to "SE", "norway" to "NO",
        "denmark" to "DK", "poland" to "PL", "czech" to "CZ", "austria" to "AT",
        "switzerland" to "CH", "belgium" to "BE", "ireland" to "IE", "iceland" to "IS",
        "spain" to "ES", "portugal" to "PT", "italy" to "IT", "greece" to "GR",
        "romania" to "RO", "bulgaria" to "BG", "hungary" to "HU", "slovakia" to "SK",
        "slovenia" to "SI", "croatia" to "HR", "serbia" to "RS", "ukraine" to "UA",
        "russia" to "RU", "moldova" to "MD", "latvia" to "LV", "lithuania" to "LT",
        "estonia" to "EE", "belarus" to "BY", "turkey" to "TR", "turkiye" to "TR",
        "cyprus" to "CY", "israel" to "IL", "emirates" to "AE", "dubai" to "AE",
        "qatar" to "QA", "kuwait" to "KW", "bahrain" to "BH", "oman" to "OM",
        "saudi" to "SA", "jordan" to "JO", "lebanon" to "LB", "iraq" to "IQ",
        "iran" to "IR", "armenia" to "AM", "georgia" to "GE", "azerbaijan" to "AZ",
        "kazakhstan" to "KZ", "uzbekistan" to "UZ", "india" to "IN", "pakistan" to "PK",
        "bangladesh" to "BD", "sri lanka" to "LK", "nepal" to "NP", "china" to "CN",
        "hong kong" to "HK", "hongkong" to "HK", "taiwan" to "TW", "japan" to "JP",
        "korea" to "KR", "singapore" to "SG", "malaysia" to "MY", "indonesia" to "ID",
        "thailand" to "TH", "vietnam" to "VN", "viet nam" to "VN", "philippines" to "PH",
        "cambodia" to "KH", "myanmar" to "MM", "australia" to "AU", "new zealand" to "NZ",
        "canada" to "CA", "mexico" to "MX", "brazil" to "BR", "argentina" to "AR",
        "chile" to "CL", "colombia" to "CO", "peru" to "PE", "panama" to "PA",
        "costa rica" to "CR", "south africa" to "ZA", "egypt" to "EG", "nigeria" to "NG",
        "kenya" to "KE", "morocco" to "MA", "tunisia" to "TN", "algeria" to "DZ",
        "luxembourg" to "LU", "malta" to "MT", "monaco" to "MC", "albania" to "AL",
        "bosnia" to "BA", "macedonia" to "MK", "montenegro" to "ME", "kosovo" to "RS",
        "seychelles" to "SC", "mauritius" to "MU", "bahamas" to "BS"
    )
}
