package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * v6.7 — THE REAL, FAST, RANKED "Auto Test" connectivity probe.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT THE USER REPORTED ABOUT v6.6 AND WHAT IT ACTUALLY MEANT
 * ─────────────────────────────────────────────────────────────────────────────
 * «می‌گردد رندوم منبع پیدا می‌کند اضافه می‌کند، خیلی طول می‌کشد پینگ بدهد و
 *  پینگ‌هایی که می‌دهد بالای ۲۵۰ است»
 *
 * The word "random" is the important one, and the user was describing a REAL
 * defect even though there is no `Random()` anywhere in this code. Phase 1 used
 * to walk the feeds ONE AT A TIME and stop at the FIRST one that answered. That
 * makes the winner an accident of list order and of whichever feed happened to
 * respond first — from the user's seat it is indistinguishable from a coin toss,
 * and it is why every run bonded to a different, often mediocre, feed.
 *
 * Worse, the "connection test" tested only whether a TEXT FILE could be
 * downloaded. Reaching `raw.githubusercontent.com` says nothing at all about
 * whether the SERVERS inside that file work for this user — so the 0→60 % bar
 * was measuring the wrong thing entirely, and the batch it then produced was
 * full of nodes that had never been checked against the user's own link.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE v6.7 PHASE 1 (0 → 60 %) — WHAT IT NOW REALLY DOES
 * ─────────────────────────────────────────────────────────────────────────────
 * Per the brief: «باید از ۰ تا ۶۰ درصد اتصال کاربر رو با منابع بررسی کنی … کدام
 * منابع واقعا وصل می‌شوند و کدام پینگ بهتری می‌دهند … سریع باشد.»
 *
 *   STEP A — FETCH MANY FEEDS AT ONCE (not one after another).
 *            [FEED_CONCURRENCY] feeds are opened in parallel through the
 *            proxy-free, DoH-resolved [com.neonvpn.app.net.DirectHttp] client,
 *            and each fetch is TIMED. A feed that does not answer inside
 *            [FEED_BUDGET_MS] is dropped. This alone removes most of the wait:
 *            v6.6 paid the slowest feed's latency serially, over and over.
 *
 *   STEP B — MEASURE THE FEED'S SERVERS AGAINST *THIS USER'S* LINK.
 *            From each reachable feed we take a small, evenly-spread
 *            [SAMPLES_PER_SOURCE] sample of its nodes and measure a REAL TCP
 *            handshake to each one from this device ([TcpProbe.connectMs]).
 *            These are cheap bare sockets, so they run [PROBE_CONCURRENCY]-wide
 *            and the whole ranking finishes in seconds.
 *
 *            This is the measurement the brief actually asked for: it answers
 *            "which sources genuinely CONNECT for me, and which ones are
 *            FASTER for me", using nothing but this device's own internet.
 *
 *   STEP C — RANK AND BOND TO THE BEST FEED, per kind.
 *            Score = median handshake latency of the samples that connected,
 *            penalised when few of them connected (see [scoreOf]). Lowest score
 *            wins and becomes the sticky bond, so PHASE 2 pulls its 240 configs
 *            from the feed measured to be the fastest and most reliable FOR THIS
 *            USER — which is what finally kills the "everything is above 250 ms"
 *            complaint at its source.
 *
 * Every value used here is measured on the spot from the user's own connection.
 * No `Random`, no estimate, no cached guess, no proxy — Golden Rules #2 and the
 * v6.6 no-proxy rule both hold.
 *
 *   PHASE 2 — ADD CONFIGS (60 % → 100 %):
 *     Using the WINNING sources chosen above, we pull a full fresh 240 batch
 *     (120 vless + 120 vmess). The bar climbs 60→100 as configs are actually
 *     collected, then the page adds them and closes.
 *
 * NO FALSE "connection error": we only fail when NOT ONE feed could be opened
 * and not one node answered (a genuine offline state), and even then the caller
 * simply finishes quietly and lets the background engine keep trying.
 *
 * Fully exception-safe: a dead source / malformed line never crashes it.
 */
object ConnectivityProbe {

    private const val TAG = "ConnectivityProbe"

    /**
     * Whole-probe wall-clock ceiling. v6.7 — 240 s → 120 s. Phase 1 is now
     * parallel and hard-bounded, so needing minutes means the link is dead, and
     * making the user watch a dead bar for four minutes helps nobody.
     */
    const val BUDGET_MS = 120_000L

    /**
     * v6.7 — ceiling for ONE ranking round (fetch feeds + measure their nodes).
     * Everything inside is parallel, so this is a generous ceiling, not a
     * target: a healthy link finishes a round in a few seconds.
     */
    private const val ROUND_BUDGET_MS = 25_000L

    /** Pause between phase-1 retries while the internet is fully down. */
    private const val RETRY_PAUSE_MS = 1_500L

    /** Boundary between the connection-test phase and the config-collect phase. */
    private const val PHASE1_END = 60

    /**
     * v6.7 — how many feeds of ONE kind are fetched (in parallel) per round.
     * Enough to give the ranking real choice, small enough to stay light on a
     * mobile link. With 35 feeds per kind this samples a solid slice each round.
     */
    private const val CANDIDATES_PER_KIND = 10

    /** v6.7 — how many feed fetches may be in flight at once. */
    private const val FEED_CONCURRENCY = 10

    /** v6.7 — per-feed fetch ceiling. A feed slower than this is not usable. */
    private const val FEED_BUDGET_MS = 9_000L

    /**
     * v6.7 — how many nodes we actually handshake per feed. Sampled EVENLY
     * across the whole feed (not the first N, which are usually the same stale
     * entries in every list), so the score describes the feed as a whole.
     */
    private const val SAMPLES_PER_SOURCE = 6

    /** v6.7 — how many bare-socket handshakes run at once across all feeds. */
    private const val PROBE_CONCURRENCY = 40

    /** v6.7 — ceiling for the whole measure-the-nodes step of one round. */
    private const val RANK_BUDGET_MS = 12_000L

    /**
     * v6.7 — a feed must have at least this many of its samples connect before
     * we are willing to bond to it. One lucky node out of six is not a working
     * source, and bonding to it is how v6.6 ended up serving dead batches.
     */
    private const val MIN_LIVE_SAMPLES = 2

    /**
     * How many configs we aim to collect during the probe — the SAME 240-per-press
     * batch used everywhere so the first fill is a full fresh batch.
     */
    const val TARGET = FreeConfigSource.BATCH_PER_PRESS   // 240

    /** Minimum configs to consider the probe a success (a weak link may yield few). */
    private const val MIN_SUCCESS = 1

    data class Result(
        /** The fresh batch of configs collected from the reachable source. */
        val configs: List<ServerConfig> = emptyList(),
        /** True if at least one source feed was opened during the probe. */
        val reachedSource: Boolean = configs.isNotEmpty()
    ) {
        /** We have something to add whenever we collected at least one config. */
        val ok: Boolean get() = configs.size >= MIN_SUCCESS
    }

    /**
     * v6.7 — one feed's MEASURED verdict. Every field is an observation taken
     * from this device during this run; nothing is carried over or guessed.
     */
    private data class SourceScore(
        val index: Int,
        val url: String,
        /** Nodes we handshaked from this feed. */
        val sampled: Int,
        /** How many of them actually accepted a TCP connection. */
        val live: Int,
        /** Median measured handshake latency of the live ones (ms), -1 if none. */
        val medianMs: Long,
        /** Measured time the feed body itself took to download (ms). */
        val fetchMs: Long
    ) {
        val usable: Boolean get() = live >= MIN_LIVE_SAMPLES && medianMs > 0L
    }

    /**
     * Run the probe. Emits real progress (0..100) through [onProgress]:
     *   0..60  → live connection test + ranking of the source feeds
     *   60..100 → collecting the fresh batch from the winning source
     *
     * @param seenKeys optional dedup memory so we don't hand back configs the user
     *                 already has; collected keys are added so the caller can persist.
     */
    suspend fun probe(
        ctx: Context,
        seenKeys: MutableSet<String>? = null,
        onProgress: (percent: Int) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val res = withTimeoutOrNull(BUDGET_MS) { runProbe(ctx, seenKeys, onProgress) }
        res ?: Result(emptyList(), reachedSource = false)
    }

    private suspend fun runProbe(
        ctx: Context,
        seenKeys: MutableSet<String>?,
        onProgress: (Int) -> Unit
    ): Result = coroutineScope {
        // Seed dedup memory: everything the user already has so we never hand back
        // a duplicate. Reuse the caller's set (and mutate it) when given.
        val seen = seenKeys ?: HashSet<String>().also { s ->
            runCatching { s.addAll(SeenConfigStore.load(ctx)) }
            runCatching { ConfigStore(ctx).getServers().forEach { s.add(ConfigParser.dedupKey(it)) } }
        }

        // v6.7 — the ranking runs MANY probes in parallel, so progress is now
        // reported from several threads at once. An AtomicInteger with a CAS loop
        // keeps the bar strictly monotonic (it may never go backwards) without a
        // lock and without losing an update to a race.
        val lastPct = java.util.concurrent.atomic.AtomicInteger(0)
        fun emit(p: Int) {
            val clamped = p.coerceIn(0, 100)
            while (true) {
                val cur = lastPct.get()
                if (clamped <= cur) return
                if (lastPct.compareAndSet(cur, clamped)) { onProgress(clamped); return }
            }
        }
        emit(1)

        // Honour first-launch / 30-day reset state.
        runCatching { FreeConfigSource.ensureFreshState(ctx) }

        // ── PHASE 1 (0..60 %): REAL, PARALLEL, RANKED CONNECTION TEST ─────────
        // The bar advances as genuine network work completes. If a whole round
        // finds nothing reachable (link fully down) we HOLD the bar where it is,
        // pause, and re-test — the bar never sprints to 60 % on a dead link and
        // never freezes forever either. Bounded by BUDGET_MS.
        var connTest = ConnTest(reached = false)
        var round = 0
        while (coroutineContext.isActive) {
            round++
            connTest = runCatching {
                withTimeoutOrNull(ROUND_BUDGET_MS) {
                    testConnectivity(ctx, round) { pct -> emit(pct) }
                } ?: ConnTest(reached = false)
            }.getOrDefault(ConnTest(reached = false))

            if (connTest.reached) break

            if (!coroutineContext.isActive) break
            emit(lastPct.get())           // re-assert current value (never regress)
            delay(RETRY_PAUSE_MS)
        }
        if (connTest.reached) emit(PHASE1_END)

        // Link fully down for the whole budget (or cancelled): do NOT run phase 2
        // and do NOT force the bar to 100 %. Finish quietly; the caller shows no
        // error and the background engine keeps retrying.
        if (!connTest.reached) {
            Log.i(TAG, "probe: no source reached (offline) — bar held at ${lastPct.get()}")
            return@coroutineScope Result(emptyList(), reachedSource = false)
        }

        // ── PHASE 2 (60..100 %): collect the fresh 240 batch from the BEST src ─
        // FreeConfigSource.nextBatch honours the sticky bond that phase 1 just
        // set to the MEASURED-FASTEST feed, so the batch comes from the source
        // this user's own connection performs best against.
        val batch = runCatching {
            FreeConfigSource.nextBatch(
                ctx = ctx,
                startIndex = 0,
                seenKeys = seen
            ) { added, target, _ ->
                if (!coroutineContext.isActive) return@nextBatch
                val frac = if (target > 0) (added.toDouble() / target) else 0.0
                val pct = PHASE1_END + (frac * (99 - PHASE1_END)).toInt()
                emit(pct.coerceIn(PHASE1_END, 99))
            }
        }.getOrNull()

        var configs = batch?.configs ?: emptyList()

        // v6.0 — REPEAT-USE FIX. If the bonded source served only already-seen
        // configs, `configs` comes back empty while the feed is perfectly
        // reachable. Clear the dedup memory (keep only what the user actually
        // holds) and pull again so a repeat Auto Test always re-serves the live
        // configs instead of coming back empty.
        if (configs.isEmpty() && (batch?.reachedSource == true || connTest.reached)) {
            runCatching {
                seenKeys?.clear()
                SeenConfigStore.performReset(ctx)
                val fresh = seenKeys ?: HashSet()
                runCatching { ConfigStore(ctx).getServers().forEach { fresh.add(ConfigParser.dedupKey(it)) } }
                val retry = FreeConfigSource.nextBatch(
                    ctx = ctx,
                    startIndex = 0,
                    seenKeys = fresh
                ) { added, target, _ ->
                    val frac = if (target > 0) (added.toDouble() / target) else 0.0
                    val pct = PHASE1_END + (frac * (99 - PHASE1_END)).toInt()
                    emit(pct.coerceIn(PHASE1_END, 99))
                }
                configs = retry.configs
            }
        }

        // ── v6.7: HAND BACK THE FASTEST CONFIGS FIRST ────────────────────────
        // The batch is measured again by the engine/list ping, but the ORDER it
        // is added in decides which rows the user sees (and which the sweep
        // measures) first. Ordering by the real handshake time we can observe
        // right now costs a couple of seconds of bare sockets and means the top
        // of the user's list is the nearby nodes, not whatever the feed happened
        // to print first. Reject-only + order-only: no number here is displayed.
        if (configs.isNotEmpty()) {
            configs = runCatching { orderByProximity(configs) }.getOrDefault(configs)
        }

        emit(100)
        onProgress(100)

        val reached = configs.isNotEmpty() || connTest.reached || (batch?.reachedSource == true)
        Log.i(TAG, "probe done: reached=$reached, collected=${configs.size}")
        Result(configs, reachedSource = reached)
    }

    private data class ConnTest(val reached: Boolean)

    /**
     * v6.7 — THE PHASE-1 REAL CONNECTION TEST.
     *
     * For each kind: fetch [CANDIDATES_PER_KIND] feeds IN PARALLEL, measure a
     * spread of each feed's nodes with real TCP handshakes from this device, and
     * bond to the feed that scores best. Returns reached=true if any feed was
     * opened AND at least one of its nodes answered.
     *
     * The candidate window rotates with [round] so a retry after a failed round
     * inspects DIFFERENT feeds instead of re-testing the same dead ones.
     */
    private suspend fun testConnectivity(
        ctx: Context,
        round: Int,
        emit: (Int) -> Unit
    ): ConnTest = coroutineScope {
        var anyReached = false

        suspend fun rankKind(
            kind: LiveSources.Kind,
            sources: List<LiveSources.Src>,
            bandStart: Int,
            bandEnd: Int,
            setBond: (Int) -> Unit
        ): Boolean {
            if (sources.isEmpty()) return false
            val ceil = (bandEnd - 1).coerceAtLeast(bandStart)

            // Rotate the window each round so retries look at fresh feeds.
            val offset = ((round - 1) * CANDIDATES_PER_KIND) % sources.size
            val candidates = (0 until minOf(CANDIDATES_PER_KIND, sources.size))
                .map { (offset + it) % sources.size }

            // ── STEP A: fetch the candidate feeds IN PARALLEL, timing each ────
            val feedGate = Semaphore(FEED_CONCURRENCY)
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val fetched = candidates.map { idx ->
                async {
                    val src = sources[idx]
                    val t0 = System.nanoTime()
                    val body = withTimeoutOrNull(FEED_BUDGET_MS) {
                        feedGate.withPermit {
                            runCatching { SourceFetcher.fetch(src.url) }.getOrNull()
                        }
                    }
                    val fetchMs = (System.nanoTime() - t0) / 1_000_000L
                    // Real work finished → move the bar. Fetching is roughly the
                    // first half of a round, so it owns the first half of the band.
                    val n = done.incrementAndGet()
                    val half = bandStart + (ceil - bandStart) / 2
                    emit((bandStart + n * (half - bandStart) / candidates.size)
                        .coerceIn(bandStart, half))
                    if (body.isNullOrBlank()) return@async null
                    val links = runCatching {
                        SourceFetcher.extractLinks(body, kind)
                    }.getOrDefault(emptyList())
                    if (links.isEmpty()) return@async null
                    Triple(idx, src.url, links) to fetchMs
                }
            }.awaitAll().filterNotNull()

            if (fetched.isEmpty()) return false
            // We opened at least one feed. That is not yet "reached" — v6.6's
            // mistake was stopping right here. A downloadable text file proves
            // nothing about whether its servers work for this user.

            // ── STEP B: MEASURE EACH FEED'S NODES AGAINST THIS USER'S LINK ────
            val probeGate = Semaphore(PROBE_CONCURRENCY)
            val probed = java.util.concurrent.atomic.AtomicInteger(0)
            val totalProbes = fetched.sumOf {
                minOf(SAMPLES_PER_SOURCE, it.first.third.size)
            }.coerceAtLeast(1)

            val scores = withTimeoutOrNull(RANK_BUDGET_MS) {
                fetched.map { (feed, fetchMs) ->
                    val (idx, url, links) = feed
                    async {
                        // Sample EVENLY across the feed, not the first N: the head
                        // of these lists is usually the same stale block in every
                        // feed, so head-sampling would score them all alike.
                        val take = minOf(SAMPLES_PER_SOURCE, links.size)
                        val step = (links.size / take).coerceAtLeast(1)
                        val picks = (0 until take).map { links[(it * step) % links.size] }

                        val rtts = picks.map { link ->
                            async {
                                probeGate.withPermit {
                                    val cfg = runCatching {
                                        ConfigParser.parseSingleSafe(link)
                                    }.getOrNull()
                                    val ms = if (cfg == null) TcpProbe.UNREACHABLE
                                             else runCatching { TcpProbe.connectMs(cfg) }
                                                 .getOrDefault(TcpProbe.UNREACHABLE)
                                    // Real work finished → move the bar through
                                    // the second half of the band.
                                    val n = probed.incrementAndGet()
                                    val half = bandStart + (ceil - bandStart) / 2
                                    emit((half + n * (ceil - half) / totalProbes)
                                        .coerceIn(half, ceil))
                                    ms
                                }
                            }
                        }.awaitAll()

                        val live = rtts.filter { it > 0L }
                        SourceScore(
                            index = idx,
                            url = url,
                            sampled = picks.size,
                            live = live.size,
                            medianMs = median(live),
                            fetchMs = fetchMs
                        )
                    }
                }.awaitAll()
            } ?: emptyList()

            // ── STEP C: BOND TO THE MEASURED BEST FEED ───────────────────────
            val usable = scores.filter { it.usable }
            val winner = usable.minByOrNull { scoreOf(it) }
                // Nothing cleared the reliability bar: fall back to the feed with
                // the most live nodes, but ONLY if something answered at all. A
                // feed where nothing connected is never bonded to.
                ?: scores.filter { it.live > 0 }.maxByOrNull { it.live }
                ?: return false

            setBond(winner.index)
            Log.i(TAG, "phase1 $kind winner=#${winner.index} " +
                "median=${winner.medianMs}ms live=${winner.live}/${winner.sampled} " +
                "fetch=${winner.fetchMs}ms (of ${scores.size} measured feeds)")
            emit(bandEnd)
            return true
        }

        val vlessOk = rankKind(
            LiveSources.Kind.VLESS, LiveSources.VLESS,
            bandStart = 2, bandEnd = 32
        ) { idx -> ConnectedSourceStore.setVlessSource(ctx, idx) }
        if (vlessOk) anyReached = true

        val vmessOk = rankKind(
            LiveSources.Kind.VMESS, LiveSources.VMESS,
            bandStart = 32, bandEnd = PHASE1_END
        ) { idx -> ConnectedSourceStore.setVmessSource(ctx, idx) }
        if (vmessOk) anyReached = true

        ConnTest(reached = anyReached)
    }

    /**
     * v6.7 — the ranking function, built only from measured values.
     *
     * Base score is the MEDIAN measured handshake latency (a median, not a mean,
     * so one unlucky sample cannot decide a feed's fate). It is then scaled by
     * the hit rate: a feed where 2 of 6 nodes answered is three times worse than
     * an equally-fast feed where all 6 did, because the user will spend the rest
     * of the session walking past its corpses. Finally the feed's own measured
     * download time is added with a small weight — a feed that takes 8 s to load
     * makes every future batch slow, and that is real observed cost too.
     *
     * Lower is better. Nothing here is a displayed ping; it only picks a feed.
     */
    private fun scoreOf(s: SourceScore): Long {
        val hitRate = s.live.toDouble() / s.sampled.coerceAtLeast(1)
        val reliability = if (hitRate <= 0.0) 100.0 else (1.0 / hitRate)
        return (s.medianMs * reliability).toLong() + (s.fetchMs / 10L)
    }

    /** Median of measured latencies, or -1 when there are none. */
    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return -1L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /**
     * v6.7 — order a freshly-collected batch by REAL measured proximity so the
     * fastest nodes are at the top of the user's list and are the first ones the
     * ping sweep measures.
     *
     * Nodes that do not answer a TCP connection at all are kept, but pushed to
     * the back rather than dropped: this function is an ordering aid, and the
     * authoritative accept/reject decision belongs to [Pinger] alone.
     */
    private suspend fun orderByProximity(configs: List<ServerConfig>): List<ServerConfig> =
        coroutineScope {
            val gate = Semaphore(PROBE_CONCURRENCY)
            val measured = withTimeoutOrNull(RANK_BUDGET_MS) {
                configs.map { cfg ->
                    async {
                        val ms = gate.withPermit {
                            runCatching { TcpProbe.connectMs(cfg) }
                                .getOrDefault(TcpProbe.UNREACHABLE)
                        }
                        cfg to ms
                    }
                }.awaitAll()
            } ?: return@coroutineScope configs

            // Live nodes ascending by measured handshake; unreachable ones last,
            // in their original order.
            val live = measured.filter { it.second > 0L }.sortedBy { it.second }.map { it.first }
            val dead = measured.filter { it.second <= 0L }.map { it.first }
            live + dead
        }
}
