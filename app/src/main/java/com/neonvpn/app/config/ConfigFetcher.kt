package com.neonvpn.app.config

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Downloads the bundled [ConfigSources] one by one, parses every line/blob into
 * [ServerConfig]s (handling base64 subscriptions, concatenated links and
 * emoji/symbol noise via [ConfigParser]), de-duplicates across ALL sources and
 * stops as soon as [ConfigSources.TARGET_COUNT] unique configs are collected.
 *
 * Progress is streamed back through [onProgress] so the UI can animate a
 * filling bar (0..target). Network and parse errors on any single source are
 * swallowed so one dead URL never aborts the whole search.
 */
object ConfigFetcher {

    private const val TAG = "ConfigFetcher"

    data class Result(val configs: List<ServerConfig>, val sourcesTried: Int)

    /**
     * @param target          how many unique configs to collect (default 80)
     * @param onProgress      (collected, target, statusMessage) — called on a
     *                        background thread; marshal to UI yourself.
     */
    /** How many configs are allowed to share the same server location (IP /
     *  SNI host + port). Public lists often publish 10-20 clones of one server;
     *  beyond this cap they're just duplicates that waste the user's time. */
    const val MAX_PER_LOCATION = 3

    /** Max sources downloaded at the SAME time. Bounded so a low-RAM phone and a
     *  fragile mobile link aren't overwhelmed, but high enough that the whole
     *  source set is pulled in a few concurrent waves instead of one-by-one —
     *  this is the main "free configs load fast" win. */
    private const val FETCH_CONCURRENCY = 6

    suspend fun collect(
        target: Int = ConfigSources.TARGET_COUNT,
        onProgress: (collected: Int, target: Int, status: String) -> Unit = { _, _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        // FRESHNESS-AWARE ORDER. Sources are grouped into tiers (0 = freshest,
        // higher = older). Within each tier we cheaply probe each source's
        // `Last-Modified` header and prefer the genuinely-newest one — so
        // whichever mirror was updated most recently is preferred automatically.
        onProgress(0, target, "Ranking sources by freshness…")
        val sources = orderSourcesByFreshness(ConfigSources.SOURCES_TIERED)
        if (sources.isEmpty()) return@withContext Result(emptyList(), 0)

        // ---- PARALLEL DOWNLOAD ----
        // Old behaviour fetched every source sequentially: one slow mirror stalled
        // the whole search for many seconds. Now we download up to
        // FETCH_CONCURRENCY sources at once (bounded by a semaphore) and keep the
        // freshness ORDER of the results, so the merge below is identical to the
        // sequential one — just dramatically faster.
        val done = AtomicInteger(0)
        val gate = Semaphore(FETCH_CONCURRENCY)
        val bodies: List<String?> = coroutineScope {
            sources.map { url ->
                async(Dispatchers.IO) {
                    if (!coroutineContext.isActive) return@async null
                    val body = gate.withPermit {
                        try { fetch(url) } catch (e: Throwable) {
                            Log.w(TAG, "fetch failed for $url: ${e.message}"); null
                        }
                    }
                    val n = done.incrementAndGet()
                    onProgress(0, target, "Fetching sources… $n/${sources.size}")
                    body
                }
            }.awaitAll()
        }

        // ---- MERGE in freshness order ----
        val collected = LinkedHashMap<String, ServerConfig>()   // dedupKey -> cfg
        val locationCounts = HashMap<String, Int>()             // loc -> kept count
        var sourcesTried = 0
        var serverIndex = 0                                     // generic naming

        for (body in bodies) {
            if (!coroutineContext.isActive) break
            if (collected.size >= target) break
            if (body.isNullOrBlank()) continue
            sourcesTried++

            val parsed = try {
                ConfigParser.parseMany(body)
            } catch (e: Throwable) {
                Log.w(TAG, "parse failed: ${e.message}")
                emptyList()
            }

            for (cfg in parsed) {
                if (collected.size >= target) break
                val key = ConfigParser.dedupKey(cfg)
                if (collected.containsKey(key)) continue        // exact duplicate

                // Cap per-location: never add more than MAX_PER_LOCATION configs
                // that resolve to the same IP / SNI host + port.
                val loc = ConfigParser.locationKey(cfg)
                val have = locationCounts[loc] ?: 0
                if (have >= MAX_PER_LOCATION) continue

                // NORMALISE THE NAME. Public feeds embed channel / provider /
                // source branding in the remark (e.g. "🔥 @somechannel | DE").
                // We strip ALL of that and assign a neutral generic label so the
                // source is never exposed in the app: Server 1, Server 2, …
                // v5.1 — the name is ALSO baked into the link's #remark fragment
                // (via [ConfigParser.rewriteRemark]) so it travels with the config
                // and stays "Server N" when copied into ANY other v2ray client.
                serverIndex++
                val name = "$GENERIC_PREFIX $serverIndex"
                val relinked = ConfigParser.rewriteRemark(cfg.rawLink, name)
                collected[key] = cfg.copy(remark = name, rawLink = relinked)
                locationCounts[loc] = have + 1
                onProgress(collected.size, target, "Found ${collected.size}/$target configs")
            }
        }

        Result(collected.values.toList(), sourcesTried)
    }

    /** Generic, brand-free server label prefix. */
    const val GENERIC_PREFIX = "Server"

    /** Re-number a list so names are always sequential Server 1..N (used after a
     *  batch is collected / after sorting so the visible labels stay clean).
     *  v5.1 — also rewrites the name into each link's #remark so it survives a
     *  copy → paste into any other v2ray client. */
    fun renumber(list: List<ServerConfig>): List<ServerConfig> =
        list.mapIndexed { i, c ->
            val name = "$GENERIC_PREFIX ${i + 1}"
            c.copy(remark = name, rawLink = ConfigParser.rewriteRemark(c.rawLink, name))
        }

    /**
     * BATCHED / STREAMING collect — built for a predictable, stable Auto Test.
     *
     * Instead of returning the whole pool at once, this collects unique configs
     * and emits them in small [batchSize] batches through [onBatch] as soon as
     * each batch is ready (already renamed to generic Server N and de-duplicated
     * / per-location capped). The caller stores each batch immediately and can
     * keep the UI responsive, then start deeper testing only once enough configs
     * are collected.
     *
     * This is single-shot and deterministic: it ranks sources, downloads them in
     * bounded parallel waves, then walks the merged results in freshness order,
     * flushing a batch every [batchSize] configs and a final partial batch at the
     * end. No randomness in the collection path, so one press always makes
     * forward progress.
     *
     * @return the full ordered list collected (also already delivered via batches)
     */
    suspend fun collectBatched(
        target: Int = ConfigSources.TARGET_COUNT,
        batchSize: Int = 15,
        onProgress: (collected: Int, target: Int) -> Unit = { _, _ -> },
        onBatch: suspend (batch: List<ServerConfig>, totalSoFar: Int) -> Unit = { _, _ -> }
    ): List<ServerConfig> = withContext(Dispatchers.IO) {
        val sources = orderSourcesByFreshness(ConfigSources.SOURCES_TIERED)
        if (sources.isEmpty()) return@withContext emptyList()

        val done = AtomicInteger(0)
        val gate = Semaphore(FETCH_CONCURRENCY)
        val bodies: List<String?> = coroutineScope {
            sources.map { url ->
                async(Dispatchers.IO) {
                    if (!coroutineContext.isActive) return@async null
                    val body = gate.withPermit {
                        try { fetch(url) } catch (_: Throwable) { null }
                    }
                    done.incrementAndGet()
                    body
                }
            }.awaitAll()
        }

        val collected = LinkedHashMap<String, ServerConfig>()
        val locationCounts = HashMap<String, Int>()
        var serverIndex = 0
        var pending = ArrayList<ServerConfig>(batchSize)

        for (body in bodies) {
            if (!coroutineContext.isActive) break
            if (collected.size >= target) break
            if (body.isNullOrBlank()) continue

            val parsed = try { ConfigParser.parseMany(body) } catch (_: Throwable) { emptyList() }
            for (cfg in parsed) {
                if (collected.size >= target) break
                val key = ConfigParser.dedupKey(cfg)
                if (collected.containsKey(key)) continue
                val loc = ConfigParser.locationKey(cfg)
                val have = locationCounts[loc] ?: 0
                if (have >= MAX_PER_LOCATION) continue

                serverIndex++
                val name = "$GENERIC_PREFIX $serverIndex"
                val relinked = ConfigParser.rewriteRemark(cfg.rawLink, name)
                val named = cfg.copy(remark = name, rawLink = relinked)
                collected[key] = named
                locationCounts[loc] = have + 1
                pending.add(named)
                onProgress(collected.size, target)

                if (pending.size >= batchSize) {
                    onBatch(ArrayList(pending), collected.size)
                    pending = ArrayList(batchSize)
                }
            }
        }
        if (pending.isNotEmpty()) onBatch(ArrayList(pending), collected.size)
        collected.values.toList()
    }

    /**
     * Order sources so the FRESHEST come first. We respect the static tier
     * (tier 0 = newest feeds, higher = older fallbacks) as the primary key, and
     * within each tier we sort by the source's real `Last-Modified` timestamp
     * (probed with a cheap HEAD request, best-effort). Sources whose freshness
     * we can't determine keep their declared order. A tiny shuffle inside the
     * same (tier, freshness-bucket) keeps the mix varied between runs.
     *
     * This is what makes the app "use whichever link is newer/up-to-date" — if
     * two equivalent feeds exist, the one updated most recently is tried first.
     */
    private suspend fun orderSourcesByFreshness(
        tiered: List<ConfigSources.Source>
    ): List<String> = withContext(Dispatchers.IO) {
        // Probe Last-Modified for ALL sources CONCURRENTLY (cheap HEAD, each hard
        // time-boxed) so ranking takes ~one HEAD round-trip instead of summing
        // them — a slow host can no longer stall the freshness ranking.
        val withTime = coroutineScope {
            tiered.map { src ->
                async(Dispatchers.IO) {
                    val ts = try {
                        withTimeoutOrNull(2_500L) { lastModifiedMillis(src.url) }
                    } catch (_: Throwable) { null } ?: -1L
                    Triple(src.url, src.tier, ts)
                }
            }.awaitAll()
        }
        // §4.8 — deterministic per-run rotation seed (NO RNG; Golden Rule #2 and
        // the NoRandomInStatsTest forbid kotlin/java Random in core/ and ui/).
        // A coarse time bucket rotates the order of equally-fresh sources between
        // runs without ever fabricating data.
        val rotation = System.currentTimeMillis() / 60_000L
        withTime
            // primary: tier (0 first). secondary: newer Last-Modified first
            // (unknown = -1 sinks to the bottom of its tier). tertiary: a stable
            // rotation hash so equally-fresh sources cycle between runs.
            .sortedWith(
                compareBy<Triple<String, Int, Long>> { it.second }
                    .thenByDescending { it.third }
                    .thenBy { (it.first.hashCode().toLong() xor rotation) }
            )
            .map { it.first }
    }

    /**
     * Cheap HEAD request to read `Last-Modified`; returns epoch millis or -1.
     *
     * v6.6 — issued through the shared proxy-free, DoH-resolving client so the
     * freshness probe uses the SAME resolved address and the SAME pooled
     * connection the subsequent GET will use. Previously this opened its own
     * `HttpURLConnection` via the (poisoned) system resolver, so on a filtered
     * link the HEAD failed, every source reported "unknown freshness", and the
     * ordering logic that is supposed to prefer the freshest feed was blind.
     */
    private fun lastModifiedMillis(urlStr: String): Long {
        return try {
            val req = okhttp3.Request.Builder()
                .url(urlStr)
                .head()
                .header("User-Agent", "ProfessorVPN/7 (Android)")
                .build()
            com.neonvpn.app.net.DirectHttp.client.newCall(req).execute().use { resp ->
                val lm = resp.headers.getDate("Last-Modified")?.time ?: -1L
                if (lm > 0) lm else (resp.headers.getDate("Date")?.time ?: -1L)
            }
        } catch (_: Throwable) {
            -1L
        }
    }

    /**
     * v6.9 — fetch a source URL **DIRECTLY, and only directly.**
     *
     * There is exactly ONE candidate: the origin. v6.8 still appended a
     * third-party content CDN here as a "fallback"; the v6.9 brief forbids any
     * intermediary in the config path, and the fallback was also a pure cost —
     * on a link where the origin is unreachable the mirror almost always was too,
     * so the user paid a second full timeout to learn nothing.
     *
     * What makes the DIRECT fetch actually work on an Iranian ISP is
     * [com.neonvpn.app.net.CfDns]: the dominant block is DNS poisoning, so we
     * resolve over encrypted Cloudflare DoH and dial the true origin address with
     * full certificate validation. No third party ever sees or can alter a config.
     */
    private fun fetch(urlStr: String): String? = try {
        com.neonvpn.app.net.DirectHttp.get(urlStr)
    } catch (e: Throwable) {
        Log.w(TAG, "fetch failed for $urlStr: ${e.message}")
        null
    }
}
