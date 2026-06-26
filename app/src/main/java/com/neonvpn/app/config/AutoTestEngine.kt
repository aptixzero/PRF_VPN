package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/**
 * v4.0 — AUTO TEST continuous engine (CRASH-PROOF rewrite).
 *
 * Behaviour (per the v4.0 brief):
 *   • Acts on behalf of the user: it presses "search" itself, fetching the NEXT
 *     batch (120) of unique configs from [FreeConfigSource], appending them to
 *     the Free Configs list.
 *   • Then it automatically pings ALL of them with bounded concurrency.
 *   • As soon as a config returns a real ping (reachable), it is moved into
 *     My Configs ([ConfigStore]) — the user sees working configs accumulate.
 *   • Configs that don't ping are dropped from the free list.
 *   • The whole cycle repeats forever until the user taps CANCEL.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY THE OLD VERSION CRASHED & WHAT CHANGED
 * ─────────────────────────────────────────────────────────────────────────────
 * The previous engine had three crash sources that all fired "after a few
 * tests":
 *   1. 16 concurrent coroutines each called `myStore.addServers()` — a
 *      read-modify-write on SharedPreferences with apply(). They raced, which on
 *      some devices CORRUPTED the prefs XML (→ hard crash on next read) and at
 *      best silently dropped configs.
 *   2. `freeStore.get()/replaceAll()` was written from the engine while the
 *      Free-tab collector ALSO wrote it on the main thread → interleaved writes.
 *   3. `_progress.value = _progress.value.copy(...)` from 16 coroutines is a
 *      lost-update race; combined with the fragment reload it could throw
 *      ConcurrentModificationException while iterating the list.
 *
 * The fixes:
 *   • A dedicated [SupervisorJob] scope + a process-wide [CoroutineExceptionHandler]
 *     so ONE failing probe can never tear the whole loop (or process) down.
 *   • Every probe runs inside runCatching {} + withTimeoutOrNull — a malformed
 *     config or a thrown probe is mapped to "Unreachable", never an exception.
 *   • Working configs are collected per-batch and written to My Configs in a
 *     SINGLE guarded [ConfigStore.addServers] call (the store now serialises
 *     writes with commit() under a lock).
 *   • Progress is updated atomically via [MutableStateFlow.update].
 *   • All store writes go through the now thread-safe [ConfigStore] /
 *     [FreeConfigStore] (synchronized + commit()).
 *   • Concurrency capped at [MAX_CONCURRENCY] (8) — within the brief's "5–8".
 */
object AutoTestEngine {

    private const val TAG = "AutoTestEngine"

    /** How many configs we fetch + test per cycle. */
    const val BATCH = 120

    /** Concurrent probes while testing a batch (brief: 5–8 simultaneously). */
    private const val MAX_CONCURRENCY = 8
    private const val PRIMARY_TIMEOUT_MS = 2_500L
    private const val RETRY_TIMEOUT_MS = 1_500L

    /** A node is "working" if its confirmed latency is at or below this. */
    private const val WORKING_MAX_MS = 1_500L

    data class Progress(
        val running: Boolean = false,
        val cycle: Int = 0,
        val phase: String = "",          // "Searching" | "Testing x/y" | "Idle"
        val testedInBatch: Int = 0,
        val batchSize: Int = 0,
        val workingFound: Int = 0,       // total working configs saved this session
        val lastWorkingMs: Long = -1L
    )

    // A crash on any test coroutine is logged and swallowed — never propagated.
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.w(TAG, "auto-test coroutine threw (swallowed): ${e.message}")
    }

    /**
     * Dedicated supervised scope. SupervisorJob means a child failure does not
     * cancel its siblings or the parent loop. App-scoped lifecycle keeps it alive
     * across tab switches; only [stop] ends it.
     */
    private val engineScope: CoroutineScope
        get() = ProcessLifecycleOwner.get().lifecycleScope

    private val gate = Semaphore(MAX_CONCURRENCY)
    /** Serialises the (rare) bulk free-list rewrites this engine performs. */
    private val storeMutex = Mutex()

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * Atomically fold a transform into the progress flow. We use a plain
     * `synchronized` read-modify-write (instead of the `Flow.update` extension)
     * so we don't depend on any specific kotlinx-coroutines version's API and so
     * the many concurrent test coroutines never lose an update (lost-update race
     * was a subtle bug in the old `_progress.value = _progress.value.copy()`).
     */
    private fun updateProgress(transform: (Progress) -> Progress) {
        synchronized(_progress) { _progress.value = transform(_progress.value) }
    }

    @Volatile private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** Start the continuous loop. No-op if already running. */
    fun start(ctx: Context) {
        if (isRunning) return
        val appCtx = ctx.applicationContext
        val freeStore = FreeConfigStore(appCtx)
        val myStore = ConfigStore(appCtx)

        // SupervisorJob + crashGuard => the loop survives any single failure.
        job = engineScope.launch(SupervisorJob() + Dispatchers.Default + crashGuard) {
            var cycle = 0
            val totalWorking = AtomicInteger(0)
            _progress.value = Progress(running = true, cycle = 0, phase = "Starting…")

            // dedup keys for the rolling free list (snapshot under store lock).
            val seenKeys = HashSet<String>()
            runCatching {
                freeStore.get().forEach { seenKeys.add(ConfigParser.dedupKey(it)) }
            }

            runCatching { FreeConfigSource.ensureFreshState(appCtx) }

            while (isActive) {
                cycle++
                // ---- 1) SEARCH: pull the next batch + append to the free list ----
                updateProgress {
                    it.copy(running = true, cycle = cycle, phase = "Searching…",
                        testedInBatch = 0, batchSize = 0)
                }

                val batch = runCatching {
                    FreeConfigSource.nextBatch(
                        ctx = appCtx,
                        startIndex = 0,
                        seenKeys = seenKeys
                    ) { _, _, _ -> }
                }.getOrNull()

                if (!isActive) break

                val fresh = batch?.configs ?: emptyList()
                if (fresh.isEmpty()) {
                    // Feed temporarily unreachable — wait and retry the cycle.
                    updateProgress { it.copy(phase = "Feed unreachable — retrying…") }
                    delay(4_000)
                    continue
                }

                // Append to the free list (visible in the Free tab) — guarded.
                runCatching {
                    storeMutex.withLock {
                        val freeList = freeStore.get()
                        freeList.addAll(fresh)
                        freeStore.replaceAll(freeList)
                    }
                }

                // ---- 2) TEST: ping everything in this fresh batch ----
                updateProgress {
                    it.copy(phase = "Testing 0/${fresh.size}",
                        testedInBatch = 0, batchSize = fresh.size)
                }

                // Thread-safe collector for working configs found this batch.
                val workingThisBatch = java.util.concurrent.ConcurrentLinkedQueue<ServerConfig>()
                val tested = AtomicInteger(0)

                withContext(Dispatchers.IO + crashGuard) {
                    fresh.map { cfg ->
                        async {
                            // Each test is fully isolated: a thrown probe / malformed
                            // config can NEVER crash the batch or the loop.
                            runCatching {
                                gate.withPermit {
                                    if (!isActive) return@withPermit
                                    PingService.setExternalStatus(cfg.id, PingService.PingStatus.Testing)
                                    val ms = probeWithRetry(cfg)
                                    if (ms in 1..WORKING_MAX_MS) {
                                        PingService.setExternalStatus(
                                            cfg.id, PingService.PingStatus.Reachable(ms)
                                        )
                                        // queue working config; persisted in bulk below
                                        workingThisBatch.add(cfg.copy())
                                        val total = totalWorking.incrementAndGet()
                                        updateProgress {
                                            it.copy(workingFound = total, lastWorkingMs = ms)
                                        }
                                    } else {
                                        PingService.setExternalStatus(
                                            cfg.id, PingService.PingStatus.Unreachable
                                        )
                                    }
                                }
                            }
                            val n = tested.incrementAndGet()
                            updateProgress {
                                it.copy(phase = "Testing $n/${fresh.size}", testedInBatch = n)
                            }

                            // ---- 3) MOVE working configs into My Configs LIVE ----
                            // Flush every few hits so the user sees them accumulate,
                            // but in a SINGLE guarded write (no per-config races).
                            if (workingThisBatch.size >= FLUSH_EVERY) {
                                flushWorking(myStore, workingThisBatch)
                            }
                        }
                    }.awaitAll()
                }

                if (!isActive) break

                // Flush any remaining working configs from this batch.
                flushWorking(myStore, workingThisBatch)

                // ---- 4) CLEAN UP: drop the non-working configs from the free list ----
                runCatching {
                    storeMutex.withLock {
                        // We wipe the whole free batch each cycle (working ones already
                        // copied to My Configs) so the list keeps cycling fresh configs.
                        val current = freeStore.get()
                        if (current.size >= BATCH) {
                            freeStore.clear()
                        } else {
                            // keep only the reachable ones (sorted nicely by the tab).
                            val reachable = current.filter {
                                PingService.statusOf(it.id) is PingService.PingStatus.Reachable
                            }
                            freeStore.replaceAll(reachable.toMutableList())
                        }
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
    }

    /** Stop the loop (CANCEL button). */
    fun stop() {
        job?.cancel()
        job = null
        updateProgress { it.copy(running = false, phase = "Stopped") }
    }

    /** How many working configs to accumulate before flushing to My Configs. */
    private const val FLUSH_EVERY = 3

    /** Drain queued working configs into My Configs in one guarded write. */
    private suspend fun flushWorking(
        myStore: ConfigStore,
        queue: java.util.concurrent.ConcurrentLinkedQueue<ServerConfig>
    ) {
        val drained = ArrayList<ServerConfig>()
        while (true) { val c = queue.poll() ?: break; drained.add(c) }
        if (drained.isEmpty()) return
        runCatching {
            storeMutex.withLock {
                myStore.addServers(drained)
                if (myStore.getSelectedId() == null) {
                    myStore.getServers().firstOrNull()?.let { myStore.setSelectedId(it.id) }
                }
            }
        }
    }

    private suspend fun probeWithRetry(cfg: ServerConfig): Long {
        val first = runCatching {
            withTimeoutOrNull(PRIMARY_TIMEOUT_MS) { Pinger.ping(cfg) }
        }.getOrNull()
        if (first != null && first > 0L) return first
        val retry = runCatching {
            withTimeoutOrNull(RETRY_TIMEOUT_MS) { Pinger.ping(cfg) }
        }.getOrNull()
        return if (retry != null && retry > 0L) retry else Pinger.UNREACHABLE
    }
}
