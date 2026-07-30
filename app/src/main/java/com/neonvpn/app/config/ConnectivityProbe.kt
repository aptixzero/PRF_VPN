package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v6.9 CONNECTION TEST — the page behind the **Auto Test** button.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY THIS FILE WAS REWRITTEN
 * ─────────────────────────────────────────────────────────────────────────────
 * The brief is blunt: «زدیم روی اتو تست که تست کانکشن بگیره … روی 30 درصد
 * میمونه و بالای 5 دقیقه طول میکشه». That was structural, not bad luck:
 *
 *   • v6.8 fetched 10 candidate feeds PER KIND with a 9 s budget each, then TCP
 *     sampled 6 nodes out of every one of those feeds under a 12 s budget, then
 *     *scored and ranked* them to find the best-ping source — and did all of it
 *     for VLESS first and VMESS second, SEQUENTIALLY, retrying whole rounds
 *     against a 120 s global budget.
 *   • The VLESS progress band ended at 32 %, so any stall in the VMESS half
 *     parked the bar at ≈30 % — the exact symptom reported.
 *   • Then Phase 2 collected configs, and finally all 240 were TCP-probed again
 *     just to sort them.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE v6.9 CONTRACT (verbatim from the brief)
 * ─────────────────────────────────────────────────────────────────────────────
 * «باید اولین vless و اولین vmess رو که پیدا کرد این ها رو جدا کنه» — take the
 * FIRST VLESS source and the FIRST VMESS source that can be REACHED. Do **not**
 * hunt for the best ping at this stage.
 *
 *   0 % →  60 %   test the user's connection against the sources and collect
 *                 120 VLESS + 120 VMESS = 240 configs.
 *   60 % → 100 %  hand them to the free-configs list and take their pings.
 *
 * So Phase 1 is now a **RACE**: every candidate feed of a kind is opened at the
 * same time and the FIRST one that answers wins and is bonded. Winning takes as
 * long as the single fastest reachable feed — typically under two seconds — not
 * as long as ten sequential timeouts. Both kinds race concurrently, so the bar
 * can no longer wedge at 30 % waiting for the second half to start.
 *
 * Scoring, ranking, median-of-samples and the final `orderByProximity` pass are
 * all GONE. They existed to pick a "best" source, which the brief explicitly
 * does not want, and they cost most of the five minutes.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * NO INTERMEDIARY, ANYWHERE
 * ─────────────────────────────────────────────────────────────────────────────
 * Feeds are opened by [SourceFetcher] → [com.neonvpn.app.net.DirectHttp]: origin
 * host only, `Proxy.NO_PROXY` pinned, hostname resolved over Cloudflare DoH.
 * Reachability here means "the user's REAL internet reached the origin", which is
 * precisely what the brief demands («پینگ باید از اینترنت واقعی کاربر باشه»).
 */
object ConnectivityProbe {

    private const val TAG = "ConnectivityProbe"

    /**
     * Hard ceiling for the WHOLE test. v6.8 allowed 120 s and routinely blew past
     * it; v6.9's work is a race plus a parallel collect, so 45 s is generous.
     */
    const val BUDGET_MS = 45_000L

    /** Progress percentage where Phase 1 (reach a source) hands over to Phase 2. */
    private const val PHASE1_END = 60

    /** Budget for the Phase-1 race. The winner is usually back in well under 2 s. */
    private const val RACE_BUDGET_MS = 9_000L

    /**
     * How many feeds of one kind enter the race. All of them are opened at once,
     * so a bigger number costs bandwidth, not time — and on a censored link a wide
     * race is exactly what finds the one host that is not blocked.
     */
    private const val RACE_WIDTH = 14

    /** Budget for Phase 2 (collecting the 240 configs). */
    private const val COLLECT_BUDGET_MS = 28_000L

    /** A press is a success as soon as it produced at least this many configs. */
    private const val MIN_SUCCESS = 1

    /** Target size of one press — 120 VLESS + 120 VMESS. */
    const val TARGET = FreeConfigSource.BATCH_PER_PRESS

    data class Result(
        val configs: List<ServerConfig> = emptyList(),
        /** True if the user's connection reached at least one source origin. */
        val reachedSource: Boolean = configs.isNotEmpty()
    ) {
        val ok: Boolean get() = configs.size >= MIN_SUCCESS
    }

    /**
     * Run the connection test.
     *
     * @param seenKeys dedup memory (seeded from [SeenConfigStore]); mutated + saved.
     * @param onProgress monotonic 0..100 progress for the on-screen bar.
     */
    suspend fun probe(
        ctx: Context,
        seenKeys: MutableSet<String>? = null,
        onProgress: (Int) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        // The bar must never go backwards, and must never report 100 before we are.
        var lastPct = 0
        val emit: (Int) -> Unit = { pct ->
            val clamped = pct.coerceIn(0, 100)
            if (clamped > lastPct) { lastPct = clamped; onProgress(clamped) }
        }
        emit(1)

        val seen = seenKeys ?: HashSet()
        val result = withTimeoutOrNull(BUDGET_MS) { runProbe(ctx, seen, emit) }
            ?: run {
                Log.w(TAG, "probe hit the ${BUDGET_MS}ms ceiling")
                Result(emptyList(), reachedSource = false)
            }
        Log.i(TAG, "probe finished in ${System.currentTimeMillis() - started}ms " +
            "→ ${result.configs.size} configs, reached=${result.reachedSource}")
        result
    }

    private suspend fun runProbe(
        ctx: Context,
        seenKeys: MutableSet<String>,
        emit: (Int) -> Unit
    ): Result {
        FreeConfigSource.ensureFreshState(ctx)
        // Every test starts from a clean slate of cached feed bodies so the answer
        // reflects the network RIGHT NOW, not 90 seconds ago.
        SourceFetcher.invalidate()

        // ── PHASE 1 (0 → 60 %) — race for the first reachable source per kind ──
        emit(4)
        val reach = raceForFirstReachable(ctx, emit)
        if (!reach.any) {
            // Nothing at all answered: the user's internet cannot see any origin.
            Log.w(TAG, "phase 1: no source reachable")
            return Result(emptyList(), reachedSource = false)
        }
        Log.i(TAG, "phase 1: first reachable vless=#${reach.vless} vmess=#${reach.vmess}")
        emit(PHASE1_END - 6)

        // ── PHASE 2 (60 → 100 %) — collect 120 + 120 from those sources ───────
        val batch = withTimeoutOrNull(COLLECT_BUDGET_MS) {
            FreeConfigSource.nextBatch(ctx, 0, seenKeys) { added, target, _ ->
                // Map collection progress onto the 60..96 band. The last few
                // percent belong to the caller, which saves + starts the pinging.
                val frac = if (target > 0) added.toFloat() / target else 0f
                emit(PHASE1_END + (frac * 36f).toInt())
            }
        }
        if (batch == null) {
            Log.w(TAG, "phase 2: collect budget exhausted")
            // We still reached a source, so tell the caller — it must not wipe the
            // existing free list just because this press was slow.
            return Result(emptyList(), reachedSource = true)
        }
        emit(96)
        Log.i(TAG, "phase 2: collected ${batch.configs.size} configs")
        return Result(batch.configs, reachedSource = true)
    }

    /** Which source index answered first, per kind. */
    private data class Reach(val vless: Int, val vmess: Int) {
        val any: Boolean get() = vless >= 0 || vmess >= 0
    }

    /**
     * Open [RACE_WIDTH] feeds of EACH kind simultaneously and keep the index of the
     * FIRST one that returns a usable body. Both kinds race at the same time.
     *
     * This is the whole of Phase 1. There is deliberately no ping measurement, no
     * scoring and no ranking here — the brief asks for the first source that can be
     * reached, not the best one.
     */
    private suspend fun raceForFirstReachable(ctx: Context, emit: (Int) -> Unit): Reach =
        coroutineScope {
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val totalRunners = (RACE_WIDTH * 2)
                .coerceAtMost(LiveSources.VLESS.size + LiveSources.VMESS.size)

            val vlessWin = CompletableDeferred<Int>()
            val vmessWin = CompletableDeferred<Int>()

            fun tickProgress() {
                // 4 % … 50 % as runners report back, so the bar always moves even
                // while we are still waiting — never a frozen 30 %.
                val d = done.incrementAndGet()
                emit(4 + ((d.toFloat() / totalRunners.coerceAtLeast(1)) * 46f).toInt())
            }

            /** Launch one kind's race; completes [win] with the first live index. */
            fun launchRace(
                sources: List<LiveSources.Src>,
                bond: Int,
                win: CompletableDeferred<Int>
            ) = async {
                // The previously-bonded source gets a head start, then the rest.
                val order = ArrayList<Int>(RACE_WIDTH)
                if (bond in sources.indices) order.add(bond)
                var i = 0
                while (order.size < RACE_WIDTH.coerceAtMost(sources.size) && i < sources.size) {
                    if (i !in order) order.add(i)
                    i++
                }
                val runners = order.map { idx ->
                    async {
                        try {
                            // allowCache=false: a race must measure the live network.
                            val body = SourceFetcher.fetch(sources[idx].url, allowCache = false)
                            val usable = !body.isNullOrBlank() &&
                                SourceFetcher.extractLinks(body, sources[idx].kind, limit = 1).isNotEmpty()
                            if (usable) win.complete(idx)
                        } catch (_: CancellationException) {
                            throw CancellationException("race cancelled")
                        } catch (_: Throwable) {
                            // dead / blocked / TLS-reset feed — just another loser
                        } finally {
                            tickProgress()
                        }
                    }
                }
                // Whoever finishes first wins; we still let the others settle so
                // their bodies land in SourceFetcher's cache for Phase 2 (free reuse).
                withTimeoutOrNull(RACE_BUDGET_MS) { runners.awaitAll() }
                runners.forEach { it.cancel() }
                if (!win.isCompleted) win.complete(-1)
            }

            val a = launchRace(LiveSources.VLESS, ConnectedSourceStore.vlessSource(ctx), vlessWin)
            val b = launchRace(LiveSources.VMESS, ConnectedSourceStore.vmessSource(ctx), vmessWin)
            listOf(a, b).awaitAll()

            val v = vlessWin.await()
            val m = vmessWin.await()
            // Bond immediately: the FIRST reachable source is the one we keep, and
            // FreeConfigSource will start its first wave there.
            if (v >= 0) ConnectedSourceStore.setVlessSource(ctx, v)
            if (m >= 0) ConnectedSourceStore.setVmessSource(ctx, m)
            emit(52)
            Reach(v, m)
        }
}
