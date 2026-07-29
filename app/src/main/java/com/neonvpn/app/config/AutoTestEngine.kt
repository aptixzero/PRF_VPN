package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * v6.3 — AUTO TEST continuous engine (NEVER-STALL rewrite).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT WAS BROKEN BEFORE v6.3 (the exact reported bug)
 * ─────────────────────────────────────────────────────────────────────────────
 * "Auto Test adds 240 configs twice, then on the third round the list stays
 *  EMPTY and it just sits there switched on but never adds anything again —
 *  especially after the internet gets weak."
 *
 * Three concrete defects caused it:
 *
 *  1. **The engine lived on `ProcessLifecycleOwner.lifecycleScope`.** That scope
 *     is tied to the PROCESS LIFECYCLE OWNER; when the app is backgrounded /
 *     the screen goes off, `lifecycleScope` for the process owner moves to a
 *     non-RESUMED state and (critically) is CANCELLED if the owner is ever
 *     destroyed. Worse, `lifecycleScope.launch` inherits `Dispatchers.Main`
 *     and a `LifecycleCoroutineScope`, so any hiccup there silently killed the
 *     loop while `AutoTestNotifier` still showed "Auto Test is ON". The engine
 *     now owns a **plain, forever-alive `CoroutineScope(SupervisorJob() +
 *     Dispatchers.Default)`** so screen-off / app-in-background / weak network
 *     can never stop it.
 *
 *  2. **A hard `delay(4_000); continue` retry loop on empty batches.** When the
 *     internet went weak, `FreeConfigSource.nextBatch` returned an empty list
 *     with `reachedSource == false`, so the loop entered a silent 4-second
 *     retry ring FOREVER, never surfacing the fact that it had stalled and
 *     never widening its search. Now there is a **bounded, escalating recovery
 *     ladder** (see [recoverEmptyBatch]) that: waits with growing backoff,
 *     clears the source bond so a DIFFERENT feed is tried, resets the dedup
 *     memory, and — after [MAX_EMPTY_STREAK] consecutive genuinely-empty
 *     rounds — **automatically switches Auto Test OFF** exactly as the brief
 *     requires ("if it hits a problem and stops adding, turn Auto Test off").
 *
 *  3. **No cycle heartbeat / stall watchdog.** If a native probe hung (the
 *     blocking `measureOutboundDelay` JNI call ignores cancellation), the whole
 *     chunk `awaitAll()` could block indefinitely and the engine looked "on"
 *     while doing nothing. Every cycle now stamps [lastProgressAt]; a
 *     **supervisor coroutine** watches that stamp and force-restarts the loop
 *     if it goes stale for [STALL_TIMEOUT_MS], so the engine self-heals instead
 *     of wedging.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE v6.3 CONTRACT
 * ─────────────────────────────────────────────────────────────────────────────
 *   • Runs continuously: screen off, app backgrounded, weak/flapping internet.
 *   • Tests configs **one after another, in list order**, and copies every
 *     config that returns a real ping into My Configs the instant it passes.
 *   • Never lets the memory/cache grow without bound (seen-set + status map are
 *     both hard-capped and pruned every cycle).
 *   • If it genuinely cannot make progress it **turns itself OFF** and clears
 *     the notification, so the user is never left staring at an "ON" badge that
 *     does nothing.
 *   • STOP always works — [stop] cancels the scope's children synchronously and
 *     flips the persisted flag, and the notifier is cleared unconditionally.
 */
object AutoTestEngine {

    private const val TAG = "AutoTestEngine"

    /** How many configs we fetch + test per cycle (120 vless + 120 vmess). */
    const val BATCH = FreeConfigSource.BATCH_PER_PRESS   // 240

    /**
     * v4.7 — ADAPTIVE concurrency, LOWERED. Every probe spins a throwaway
     * native Xray core; running up to 10 at once exhausted native memory on
     * low-RAM phones and crashed the engine right at the list-2 / list-3
     * transition. 2 cores → 3, 4 cores → 5, 8+ cores → 6.
     */
    private val MAX_CONCURRENCY: Int by lazy {
        // v6.8 — 3–6 → 5–10. Auto Test now pays far fewer throwaway native cores
        // per config (v6.8 Pinger: 2 latency samples + 1 single-shot verdict,
        // down from up to 5), and the wide TCP triage already dropped the dead
        // majority before this deep wave ever runs, so a bigger window turns the
        // survivors into My-Configs entries much faster without the low-RAM
        // crashes the old tiny ceiling was guarding against. Slightly below the
        // manual PING-ALL ceiling because Auto Test runs unattended for hours.
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        (cores + 3).coerceIn(5, 10)
    }

    /**
     * v4.7 — hard cap for the in-memory dedup set. It is re-seeded from the
     * bounded [SeenConfigStore] when it overflows, so a multi-hour Auto Test
     * session can no longer grow it without limit.
     */
    private const val MAX_SEEN_KEYS = 12_000

    /**
     * v6.7 — THE ACCEPTANCE BAR, LOWERED FROM 8 000 ms TO 2 500 ms.
     *
     * THE BUG THIS FIXES, in the user's words: «پینگ‌هایی که می‌دهد بالای ۲۵۰
     * است — خیلی ضعیف، اصلا نمیشه استفاده کرد» and «الان هر کانفیگی پینگ بالا
     * می‌دهد و نمی‌شود به آن وصل شد».
     *
     * Both are the same defect, and it was here. v6.6 copied a config into My
     * Configs if it answered in EIGHT SECONDS. Eight seconds is not a working
     * VPN — it is a node that technically completes a handshake and then makes
     * every page load feel broken. Because Auto Test ran unattended for hours,
     * the user's list filled up with exactly those nodes, so every config they
     * tapped was slow. The engine was faithfully doing what it was told; it was
     * told the wrong thing.
     *
     * 2 500 ms is the honest ceiling for "this is worth keeping". It is well
     * above a good Iranian-link tunnel ping (which lands in the 80–400 ms band
     * on the nodes this build now surfaces first), so nothing genuinely usable
     * is thrown away, while everything that would produce the reported misery is
     * refused. Nodes are additionally sorted ascending on the way in
     * ([flushWorking]), so the FAST ones sit at the top of the list.
     *
     * This is a stricter filter than [Pinger]'s own MAX_VALID_MS, never a looser
     * one — Auto Test may only ever accept a SUBSET of what a manual ping
     * accepts, so it can never mark something good that a manual ping would
     * reject.
     */
    private const val WORKING_MAX_MS = 2_500L

    /**
     * v6.7 — the "this is a genuinely good node" mark. Purely informational: it
     * decides nothing, it is only used to tell the user how many of the accepted
     * nodes are actually fast, in the notification.
     */
    private const val FAST_MS = 400L

    /**
     * v6.3 — how many CONSECUTIVE cycles may come back with zero fresh configs
     * before we conclude the engine cannot make progress and switch Auto Test
     * OFF (per the brief). Each of those cycles already went through the full
     * escalating recovery ladder, so reaching this count means the device is
     * genuinely unable to reach any feed.
     */
    private const val MAX_EMPTY_STREAK = 6

    /**
     * v6.3 — hard ceiling for one whole cycle (search + test the batch). If a
     * cycle exceeds this the batch is abandoned and the loop moves on, so a
     * hung native probe can never freeze Auto Test forever.
     */
    private const val CYCLE_BUDGET_MS = 12L * 60 * 1000     // 12 minutes

    /**
     * v6.3 — hard ceiling for ONE chunk of concurrent probes. Bounds the
     * `awaitAll()` so a single wedged JNI call cannot stall the whole batch.
     */
    private const val CHUNK_BUDGET_MS = 90_000L

    /** v6.3 — search phase ceiling; a dead feed must not hang the cycle. */
    private const val SEARCH_BUDGET_MS = 90_000L

    /**
     * v6.3 — if no cycle heartbeat lands for this long the supervisor considers
     * the loop wedged and force-restarts it. Comfortably longer than a normal
     * cycle so a slow-but-alive run is never interrupted.
     */
    private const val STALL_TIMEOUT_MS = 15L * 60 * 1000     // 15 minutes

    /** v6.3 — how often the stall supervisor checks the heartbeat. */
    private const val SUPERVISOR_TICK_MS = 60_000L

    /**
     * v6.7 — width of the triage wave. These are bare sockets (no native core,
     * no TLS, a few hundred bytes each), so unlike [MAX_CONCURRENCY] this can be
     * an order of magnitude wider with no memory risk. It is what lets a whole
     * 240-config batch be triaged in about two seconds.
     */
    private val TRIAGE_CONCURRENCY: Int = TcpProbe.MAX_CONCURRENCY

    /**
     * v6.7 — ceiling for the whole triage wave. At 48-wide with a 2.2 s connect
     * timeout, 240 configs need ~5 worst-case rounds, so this cannot be reached
     * by anything but a pathological link — in which case we fall back to the
     * untriaged batch rather than losing it.
     */
    private const val TRIAGE_BUDGET_MS = 20_000L

    data class Progress(
        val running: Boolean = false,
        val cycle: Int = 0,
        val phase: String = "",          // "Searching" | "Testing x/y" | "Idle"
        val testedInBatch: Int = 0,
        val batchSize: Int = 0,
        val workingFound: Int = 0,       // total working configs saved this session
        val lastWorkingMs: Long = -1L,
        /**
         * v6.7 — how many of [workingFound] measured at or below [FAST_MS].
         * A real count of real measurements; the UI/notification uses it to show
         * the user that the engine is producing LOW-ping configs, not just any
         * configs that happened to answer.
         */
        val fastFound: Int = 0,
        /**
         * v6.3 — set when the engine turned ITSELF off because it could not make
         * progress (weak/no internet for many consecutive cycles). The UI reads
         * it to show an honest message instead of a silently-dead "ON" badge.
         */
        val autoStopped: Boolean = false
    )

    // A crash on any test coroutine is logged and swallowed — never propagated.
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.w(TAG, "auto-test coroutine threw (swallowed): ${e.message}")
    }

    /**
     * v6.3 — **THE KEY FIX.** A plain, process-lifetime scope that is NOT tied to
     * any Android lifecycle. The old `ProcessLifecycleOwner.lifecycleScope` was
     * cancelled/starved when the app went to the background or the screen turned
     * off — the direct cause of "Auto Test is on but stopped adding configs".
     * SupervisorJob means one failing probe never cancels its siblings.
     */
    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + crashGuard
    )

    private val gate = Semaphore(MAX_CONCURRENCY)

    /** Serialises the (rare) bulk free-list rewrites this engine performs. */
    private val storeMutex = Mutex()

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * Atomically fold a transform into the progress flow. A plain `synchronized`
     * read-modify-write so the many concurrent test coroutines never lose an
     * update (lost-update race in the old `_progress.value = _progress.value...`).
     */
    private fun updateProgress(transform: (Progress) -> Progress) {
        synchronized(_progress) { _progress.value = transform(_progress.value) }
    }

    @Volatile private var job: Job? = null
    @Volatile private var supervisorJob: Job? = null

    /** v6.3 — heartbeat: wall-clock of the last real forward progress. */
    private val lastProgressAt = AtomicLong(0L)

    /** v6.3 — kept so the stall supervisor can restart the loop by itself. */
    @Volatile private var appContext: Context? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * RE-ENTRANCY GUARD. Every mutation of [job] (start / stop / restart) goes
     * through this monitor so two rapid Auto-Test presses can never leave two
     * loops running or wedge the engine in a half-stopped state. Plain lock,
     * never held across a suspend point.
     */
    private val lifecycleLock = Any()

    /** Mark forward progress (used by the stall supervisor). */
    private fun beat() = lastProgressAt.set(System.currentTimeMillis())

    /**
     * Cancel any running loop and start a fresh one. Used by the Auto-Test page
     * so pressing AUTO TEST again (even mid-run) always kicks off a brand-new
     * search+add cycle instead of getting stuck.
     */
    fun restart(ctx: Context) {
        synchronized(lifecycleLock) {
            job?.cancel()
            job = null
        }
        start(ctx)
    }

    /** Start the continuous loop. No-op if already running. */
    fun start(ctx: Context) {
        synchronized(lifecycleLock) {
            if (isRunning) return
            startLocked(ctx.applicationContext)
        }
    }

    /** Actually build + launch the loop. MUST be called while holding the lock. */
    private fun startLocked(ctx: Context) {
        val appCtx = ctx.applicationContext
        appContext = appCtx

        // Remember Auto Test is ON so it survives a process kill (long screen-off
        // session). NeonApp re-arms it on next launch if still set.
        runCatching { com.neonvpn.app.util.AppPrefs.setAutoTestOn(appCtx, true) }

        val freeStore = FreeConfigStore(appCtx)
        val myStore = ConfigStore(appCtx)

        runCatching { AutoTestNotifier.show(appCtx, "در حال تست خودکار کانفیگ‌ها…") }

        beat()
        job = engineScope.launch {
            var cycle = 0
            val totalWorking = AtomicInteger(0)
            /** v6.7 — of those, how many measured at or below [FAST_MS]. */
            val totalFast = AtomicInteger(0)
            var emptyStreak = 0
            _progress.value = Progress(running = true, cycle = 0, phase = "Starting…")

            // ---- dedup memory (persistent + bounded) --------------------------
            val seenKeys = HashSet<String>()
            runCatching { seenKeys.addAll(SeenConfigStore.load(appCtx)) }
            runCatching { freeStore.get().forEach { seenKeys.add(ConfigParser.dedupKey(it)) } }
            runCatching { myStore.getServers().forEach { seenKeys.add(ConfigParser.dedupKey(it)) } }

            runCatching { FreeConfigSource.ensureFreshState(appCtx) }
            runCatching {
                if (SeenConfigStore.load(appCtx).isEmpty()) seenKeys.clear()
            }

            while (isActive) {
                cycle++
                beat()

                // Keep the dedup memory bounded across a long session.
                if (seenKeys.size > MAX_SEEN_KEYS) {
                    runCatching {
                        seenKeys.clear()
                        seenKeys.addAll(SeenConfigStore.load(appCtx))
                        freeStore.get().forEach { seenKeys.add(ConfigParser.dedupKey(it)) }
                        myStore.getServers().forEach { seenKeys.add(ConfigParser.dedupKey(it)) }
                    }
                }

                // ================= 1) SEARCH =================================
                updateProgress {
                    it.copy(
                        running = true, cycle = cycle, phase = "Searching…",
                        testedInBatch = 0, batchSize = 0, autoStopped = false
                    )
                }

                var fresh = fetchBatch(appCtx, seenKeys)
                if (!isActive) break

                // v6.3 — ESCALATING RECOVERY instead of the old silent 4s retry
                // ring. This is what makes Auto Test survive a weak/flapping
                // link: it widens the search, drops the sticky source bond, and
                // resets the dedup memory before conceding.
                if (fresh.isEmpty()) {
                    fresh = recoverEmptyBatch(appCtx, myStore, seenKeys, emptyStreak)
                }

                if (fresh.isEmpty()) {
                    emptyStreak++
                    beat()
                    Log.w(TAG, "cycle $cycle produced no configs (streak=$emptyStreak)")
                    updateProgress {
                        it.copy(phase = "No configs — retrying ($emptyStreak/$MAX_EMPTY_STREAK)")
                    }
                    runCatching {
                        AutoTestNotifier.show(
                            appCtx,
                            "اینترنت ضعیف است — تلاش مجدد ($emptyStreak/$MAX_EMPTY_STREAK)"
                        )
                    }

                    if (emptyStreak >= MAX_EMPTY_STREAK) {
                        // Per the brief: if it hits a problem and can no longer
                        // add configs, TURN AUTO TEST OFF (don't sit there "on"
                        // doing nothing).
                        Log.e(TAG, "auto-stopping: $emptyStreak consecutive empty cycles")
                        withContext(NonCancellable) { autoStop(appCtx) }
                        break
                    }

                    // Backoff grows with the streak but is capped so we resume
                    // fast the moment the link recovers.
                    delay((3_000L * emptyStreak).coerceAtMost(20_000L))
                    continue
                }

                emptyStreak = 0
                beat()

                // ================= 1b) v6.7 TRIAGE: FAST FIRST ===============
                //
                // «باید تند تند پینگ بگیرد و کانفیگ‌های پینگ پایین بدهد»
                //
                // Before v6.7 the batch went straight into the deep prober in
                // whatever order the feed printed it. With only 3–6 native-core
                // permits, a 240-config batch meant the user waited many minutes
                // and the nodes that could have given a 100 ms ping were tested
                // last — if the cycle even reached them.
                //
                // So we now spend ~2 seconds up front on a wide wave of bare TCP
                // handshakes ([triageBatch]) that does two things:
                //   • DROPS the configs that accept no connection at all. On a
                //     public feed that is most of them, and every one dropped is
                //     a multi-second deep probe we never have to run.
                //   • ORDERS the survivors by their real measured handshake time,
                //     which is a hard lower bound on their tunnel ping, so the
                //     nearest nodes are deep-probed FIRST and land in My Configs
                //     within seconds of pressing Auto Test.
                //
                // Reject-and-order only: no number produced here is ever shown,
                // and the accept decision still belongs entirely to [Pinger].
                updateProgress { it.copy(phase = "Triage ${fresh.size}…") }
                val triaged = runCatching { triageBatch(fresh) }.getOrDefault(fresh)
                if (triaged.isNotEmpty()) fresh = triaged
                beat()

                // Replace (not append) so the free list never grows without bound.
                runCatching { storeMutex.withLock { freeStore.replaceAll(fresh) } }

                // Drop ping states of rows that no longer exist so the shared
                // statuses map cannot grow forever across cycles.
                runCatching {
                    val keep = HashSet<String>(fresh.size + 64)
                    fresh.forEach { keep.add(PingService.keyOf(it)) }
                    myStore.getServers().forEach { keep.add(PingService.keyOf(it)) }
                    PingService.prune(keep)
                }

                // ================= 2) TEST ===================================
                updateProgress {
                    it.copy(
                        phase = "Testing 0/${fresh.size}",
                        testedInBatch = 0, batchSize = fresh.size
                    )
                }

                val workingThisBatch = java.util.concurrent.ConcurrentLinkedQueue<ServerConfig>()
                val tested = AtomicInteger(0)

                // v6.3 — the whole test phase is wrapped in a wall-clock budget so
                // a wedged native probe can never freeze the engine. Configs are
                // probed strictly IN LIST ORDER using a bounded sliding window
                // (chunk = MAX_CONCURRENCY), and EACH CHUNK has its own timeout.
                withTimeoutOrNull(CYCLE_BUDGET_MS) {
                    withContext(Dispatchers.IO + crashGuard) {
                        val chunkSize = MAX_CONCURRENCY
                        var i = 0
                        while (i < fresh.size && isActive) {
                            val end = (i + chunkSize).coerceAtMost(fresh.size)
                            val chunk = ArrayList(fresh.subList(i, end))

                            // Bounded chunk: if a probe hangs, we abandon the whole
                            // chunk and continue with the next one.
                            withTimeoutOrNull(CHUNK_BUDGET_MS) {
                                chunk.map { cfg ->
                                    async {
                                        runCatching {
                                            gate.withPermit {
                                                if (!isActive) return@withPermit
                                                PingService.setExternalStatus(
                                                    cfg, PingService.PingStatus.Testing
                                                )
                                                val ms = probeWithRetry(cfg)
                                                if (ms in 1..WORKING_MAX_MS) {
                                                    PingService.setExternalStatus(
                                                        cfg, PingService.PingStatus.Reachable(ms)
                                                    )
                                                    workingThisBatch.add(cfg.copy())
                                                    val total = totalWorking.incrementAndGet()
                                                    // v6.7 — count the genuinely
                                                    // fast ones separately so the
                                                    // notification reports real
                                                    // quality, not just a tally.
                                                    val fast = if (ms <= FAST_MS)
                                                        totalFast.incrementAndGet()
                                                    else totalFast.get()
                                                    updateProgress {
                                                        it.copy(
                                                            workingFound = total,
                                                            lastWorkingMs = ms,
                                                            fastFound = fast
                                                        )
                                                    }
                                                    runCatching {
                                                        AutoTestNotifier.show(
                                                            appCtx,
                                                            "اتو تست روشن است · $total کانفیگ سالم" +
                                                                " ($fast کم‌پینگ) · آخرین: ${ms}ms"
                                                        )
                                                    }
                                                } else {
                                                    PingService.setExternalStatus(
                                                        cfg, PingService.PingStatus.Unreachable
                                                    )
                                                }
                                            }
                                        }
                                        val n = tested.incrementAndGet()
                                        beat()
                                        updateProgress {
                                            it.copy(
                                                phase = "Testing $n/${fresh.size}",
                                                testedInBatch = n
                                            )
                                        }
                                        // Flush working configs into My Configs LIVE.
                                        if (workingThisBatch.isNotEmpty()) {
                                            flushWorking(myStore, workingThisBatch)
                                        }
                                    }
                                }.awaitAll()
                            } ?: run {
                                // Chunk timed out: mark everything still "Testing"
                                // in it as unreachable so no row is left spinning.
                                Log.w(TAG, "chunk timed out — abandoning ${chunk.size} probes")
                                chunk.forEach { cfg ->
                                    runCatching {
                                        if (PingService.statusOfConfig(cfg) is
                                                PingService.PingStatus.Testing
                                        ) {
                                            PingService.setExternalStatus(
                                                cfg, PingService.PingStatus.Unreachable
                                            )
                                        }
                                    }
                                }
                                beat()
                            }
                            i = end
                        }
                    }
                }

                if (!isActive) break

                // Flush any remaining working configs from this batch.
                flushWorking(myStore, workingThisBatch)
                beat()

                // ================= 3) CLEAN UP ===============================
                // Keep only the reachable rows in the free list (the working ones
                // are already in My Configs; the dead ones are dropped so the next
                // cycle starts from a small, clean list).
                runCatching {
                    storeMutex.withLock {
                        val reachable = freeStore.get().filter {
                            PingService.statusOfConfig(it) is PingService.PingStatus.Reachable
                        }
                        freeStore.replaceAll(reachable)
                    }
                }

                updateProgress {
                    it.copy(phase = "Cycle $cycle done · ${totalWorking.get()} working")
                }
                delay(1_200)
            }
        }

        job?.invokeOnCompletion {
            updateProgress { it.copy(running = false, phase = "Stopped") }
        }

        startStallSupervisor()
    }

    /**
     * v6.3 — STALL SUPERVISOR. Watches the [lastProgressAt] heartbeat; if the
     * loop is supposed to be running but has produced no forward progress for
     * [STALL_TIMEOUT_MS], it force-restarts it. This is the last line of defence
     * against a wedged native call leaving Auto Test "on but dead".
     */
    private fun startStallSupervisor() {
        if (supervisorJob?.isActive == true) return
        supervisorJob = engineScope.launch {
            while (isActive) {
                delay(SUPERVISOR_TICK_MS)
                val ctx = appContext ?: continue
                if (!isRunning) continue
                val since = System.currentTimeMillis() - lastProgressAt.get()
                if (since > STALL_TIMEOUT_MS) {
                    Log.e(TAG, "stall detected (${since}ms without progress) — restarting engine")
                    beat()
                    runCatching { restart(ctx) }
                }
            }
        }
    }

    /**
     * v6.7 — THE FAST TRIAGE WAVE.
     *
     * Runs one bare TCP handshake per config, [TRIAGE_CONCURRENCY]-wide, and
     * returns only the configs that answered, sorted ASCENDING by their real
     * measured handshake time.
     *
     * WHY IT IS SOUND: Xray dials the node with exactly this TCP connect, so a
     * config that refuses one here cannot possibly be used — dropping it is
     * correct and costs ~300 ms instead of the several seconds a deep probe
     * would burn proving the same thing. And because the tunnel round trip
     * physically contains this round trip, the measured time is a hard LOWER
     * BOUND on the ping the node could ever report, which makes it the right key
     * to sort by when the goal is "find the low-ping nodes first".
     *
     * WHAT IT MAY NOT DO: it never produces a displayed ping and never marks a
     * config as working. Those remain [Pinger]'s job alone, so the pipeline
     * still cannot show a number it did not measure through the real tunnel.
     *
     * Falls back to the input list unchanged on timeout, so a slow link degrades
     * to the old behaviour instead of losing the batch.
     */
    private suspend fun triageBatch(batch: List<ServerConfig>): List<ServerConfig> {
        if (batch.isEmpty()) return batch
        val gate = Semaphore(TRIAGE_CONCURRENCY)
        val measured = withTimeoutOrNull(TRIAGE_BUDGET_MS) {
            withContext(Dispatchers.IO + crashGuard) {
                batch.map { cfg ->
                    async {
                        val ms = gate.withPermit {
                            runCatching { TcpProbe.connectMs(cfg) }
                                .getOrDefault(TcpProbe.UNREACHABLE)
                        }
                        cfg to ms
                    }
                }.awaitAll()
            }
        } ?: return batch

        val live = measured.filter { it.second > 0L }.sortedBy { it.second }
        // Everything refused a connection. That is almost always a momentary
        // network drop rather than 240 simultaneously dead nodes, so we hand the
        // batch back untouched and let the deep prober have the final say.
        if (live.isEmpty()) return batch

        // Mark the rejects so the Free list shows the truth immediately instead
        // of leaving rows blank until a later sweep touches them.
        measured.filter { it.second <= 0L }.forEach { (cfg, _) ->
            runCatching { PingService.setExternalStatus(cfg, PingService.PingStatus.Unreachable) }
        }

        Log.i(TAG, "triage: ${live.size}/${batch.size} dialable, " +
            "fastest door=${live.first().second}ms slowest=${live.last().second}ms")
        return live.map { it.first }
    }

    /**
     * v6.3 — one bounded search attempt. Returns the fresh configs or an empty
     * list; never throws, never blocks forever.
     */
    private suspend fun fetchBatch(
        ctx: Context,
        seenKeys: MutableSet<String>
    ): List<ServerConfig> {
        val batch = withTimeoutOrNull(SEARCH_BUDGET_MS) {
            runCatching {
                FreeConfigSource.nextBatch(ctx = ctx, startIndex = 0, seenKeys = seenKeys) { _, _, _ -> }
            }.getOrNull()
        }
        return batch?.configs ?: emptyList()
    }

    /**
     * v6.3 — THE ESCALATING RECOVERY LADDER (replaces the old silent 4-second
     * retry ring that made Auto Test die on a weak link).
     *
     * Step 1 — short settle + plain retry. Handles a momentary blip.
     * Step 2 — DROP THE STICKY SOURCE BOND and retry. The bonded feed may have
     *          gone dark; unbonding makes the next press walk to a DIFFERENT
     *          feed instead of hammering the dead one forever. This alone fixes
     *          the majority of "stopped adding after two rounds" reports.
     * Step 3 — RESET THE DEDUP MEMORY (keeping only what the user actually holds)
     *          and retry. Handles "every live config is already in the seen set",
     *          which looks identical to being offline but is not.
     *
     * Returns the first non-empty result, or an empty list if every step failed.
     */
    private suspend fun recoverEmptyBatch(
        ctx: Context,
        myStore: ConfigStore,
        seenKeys: MutableSet<String>,
        streak: Int
    ): List<ServerConfig> {
        // --- Step 1: settle and retry plainly -------------------------------
        updateProgress { it.copy(phase = "Weak link — retrying…") }
        delay((1_500L * (streak + 1)).coerceAtMost(8_000L))
        fetchBatch(ctx, seenKeys).let { if (it.isNotEmpty()) return it }

        // --- Step 2: unbond the sticky source, try a different feed ----------
        updateProgress { it.copy(phase = "Switching source…") }
        runCatching { ConnectedSourceStore.clear(ctx) }
        delay(800)
        fetchBatch(ctx, seenKeys).let { if (it.isNotEmpty()) return it }

        // --- Step 3: reset the dedup memory, re-serve the live configs -------
        updateProgress { it.copy(phase = "Refreshing memory…") }
        runCatching {
            seenKeys.clear()
            SeenConfigStore.performReset(ctx)
            // Keep only what the user actually holds so we don't immediately
            // re-add configs already in My Configs.
            myStore.getServers().forEach { seenKeys.add(ConfigParser.dedupKey(it)) }
        }
        delay(800)
        return fetchBatch(ctx, seenKeys)
    }

    /**
     * v6.3 — the engine turns ITSELF off after too many fruitless cycles, exactly
     * as the brief demands. The persisted "auto test on" flag is cleared so it
     * does NOT silently resume on the next launch, the notification is replaced
     * with an honest one-shot message, and the progress flow reports
     * `autoStopped = true` so the UI can flip the button back to AUTO TEST.
     */
    private fun autoStop(ctx: Context) {
        runCatching { com.neonvpn.app.util.AppPrefs.setAutoTestOn(ctx, false) }
        runCatching { AutoTestNotifier.clear(ctx) }
        updateProgress {
            it.copy(
                running = false,
                autoStopped = true,
                phase = "Auto Test stopped — weak connection"
            )
        }
        synchronized(lifecycleLock) {
            job?.cancel()
            job = null
        }
    }

    /** Stop the loop (CANCEL button). Always works, even mid-probe. */
    fun stop() {
        val ctx = appContext
        synchronized(lifecycleLock) {
            job?.cancel()
            job = null
        }
        updateProgress { it.copy(running = false, phase = "Stopped", autoStopped = false) }
        runCatching {
            val c = ctx ?: com.neonvpn.app.NeonApp.instance
            AutoTestNotifier.clear(c)
            // User explicitly cancelled: clear the sticky flag so it does NOT
            // auto-resume on next launch.
            com.neonvpn.app.util.AppPrefs.setAutoTestOn(c, false)
        }
    }

    /**
     * Drain queued working configs into My Configs in one guarded write.
     *
     * v6.7 — the drained group is written FASTEST-FIRST, using each config's
     * real measured ping as already recorded in [PingService] by the probe that
     * accepted it. No new measurement and no invented value: we simply read back
     * the number that was actually measured moments ago and order by it.
     *
     * Why it matters: the user's very first tap should land on the best node in
     * the list, and the default selection below picks the first row.
     */
    private suspend fun flushWorking(
        myStore: ConfigStore,
        queue: java.util.concurrent.ConcurrentLinkedQueue<ServerConfig>
    ) {
        val drained = ArrayList<ServerConfig>()
        while (true) { val c = queue.poll() ?: break; drained.add(c) }
        if (drained.isEmpty()) return

        val sorted = runCatching {
            drained.sortedBy { cfg ->
                when (val st = PingService.statusOfConfig(cfg)) {
                    is PingService.PingStatus.Reachable -> st.ms
                    is PingService.PingStatus.Unstable -> st.ms
                    // Unknown ordering key → keep it after the measured ones
                    // rather than pretending it is fast.
                    else -> Long.MAX_VALUE
                }
            }
        }.getOrDefault(drained)

        runCatching {
            storeMutex.withLock {
                myStore.addServers(sorted)
                if (myStore.getSelectedId() == null) {
                    myStore.getServers().firstOrNull()?.let { myStore.setSelectedId(it.id) }
                }
            }
        }
    }

    /**
     * IDENTICAL to the manual ping path. [Pinger.ping] is already hard-bounded
     * internally, so we call it directly (NO shorter outer timeout — that nested
     * timeout was the v4.2 bug that made every config fail) and retry once on a
     * miss, exactly like PingService.probeWithRetry.
     */
    private suspend fun probeWithRetry(cfg: ServerConfig): Long {
        val first = runCatching { Pinger.ping(cfg) }.getOrDefault(Pinger.UNREACHABLE)
        if (first > 0L) return first
        val retry = runCatching { Pinger.ping(cfg) }.getOrDefault(Pinger.UNREACHABLE)
        return if (retry > 0L) retry else Pinger.UNREACHABLE
    }
}
