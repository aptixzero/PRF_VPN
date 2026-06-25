package com.neonvpn.app.config

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v4.0 — AUTO TEST continuous engine.
 *
 * Behaviour (per the v4.0 brief):
 *   • Acts on behalf of the user: it presses "search" itself, fetching the NEXT
 *     batch (120) of unique configs from [FreeConfigSource] (aptixzero/con_new),
 *     appending them to the Free Configs list.
 *   • Then it automatically pings ALL of them with bounded concurrency.
 *   • As soon as a config returns a real ping (reachable), it is moved LIVE into
 *     My Configs ([ConfigStore]) — the user sees working configs accumulate in
 *     real time.
 *   • Configs that don't ping (and stay at the bottom of the list) are dropped.
 *   • When the Free list grows toward ~120 it is wiped and a fresh search runs.
 *   • The whole cycle repeats forever until the user taps CANCEL.
 *
 * It is APP-SCOPED (runs on [ProcessLifecycleOwner]) so switching tabs never
 * cancels it — only the explicit [stop] call (the CANCEL button) ends it.
 *
 * The engine reuses the real [Pinger] (a genuine 2-stage proxied probe) so
 * "working" means the config actually responds — never a fake/random value.
 */
object AutoTestEngine {

    /** How many configs we fetch + test per cycle. */
    const val BATCH = 120

    /** Concurrent probes while testing a batch. */
    private const val MAX_CONCURRENCY = 16
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

    private val appScope get() = ProcessLifecycleOwner.get().lifecycleScope
    private val gate = Semaphore(MAX_CONCURRENCY)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    @Volatile private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** Start the continuous loop. No-op if already running. */
    fun start(ctx: Context) {
        if (isRunning) return
        val appCtx = ctx.applicationContext
        val freeStore = FreeConfigStore(appCtx)
        val myStore = ConfigStore(appCtx)

        job = appScope.launch {
            var cycle = 0
            var totalWorking = 0
            _progress.value = Progress(running = true, cycle = 0, phase = "Starting…")

            // dedup keys for the rolling free list
            val seenKeys = HashSet<String>()
            freeStore.get().forEach { seenKeys.add(ConfigParser.dedupKey(it)) }

            try {
                FreeConfigSource.ensureFreshState(appCtx)
            } catch (_: Throwable) {}

            while (isActive) {
                cycle++
                // ---- 1) SEARCH: pull the next batch + append to the free list ----
                _progress.value = _progress.value.copy(
                    running = true, cycle = cycle, phase = "Searching…",
                    testedInBatch = 0, batchSize = 0
                )

                val batch = try {
                    FreeConfigSource.nextBatch(
                        ctx = appCtx,
                        startIndex = 0,
                        seenKeys = seenKeys
                    ) { _, _, _ -> }
                } catch (_: Throwable) {
                    null
                }

                if (!isActive) break

                val fresh = batch?.configs ?: emptyList()
                if (fresh.isEmpty()) {
                    // Feed temporarily unreachable — wait and retry the cycle.
                    _progress.value = _progress.value.copy(phase = "Feed unreachable — retrying…")
                    delay(4_000)
                    continue
                }

                // Append to the free list (visible to the user in the Free tab).
                val freeList = freeStore.get()
                freeList.addAll(fresh)
                freeStore.replaceAll(freeList)

                // ---- 2) TEST: ping everything in this fresh batch ----
                _progress.value = _progress.value.copy(
                    phase = "Testing 0/${fresh.size}",
                    testedInBatch = 0, batchSize = fresh.size
                )

                val workingThisBatch = java.util.Collections.synchronizedList(ArrayList<ServerConfig>())
                val tested = java.util.concurrent.atomic.AtomicInteger(0)

                withContext(Dispatchers.IO) {
                    fresh.map { cfg ->
                        async {
                            gate.withPermit {
                                if (!isActive) return@withPermit
                                // live "testing" status so the Free tab shows spinners
                                PingService.setExternalStatus(cfg.id, PingService.PingStatus.Testing)
                                val ms = probeWithRetry(cfg)
                                if (ms in 1..WORKING_MAX_MS) {
                                    PingService.setExternalStatus(cfg.id, PingService.PingStatus.Reachable(ms))
                                    // ---- 3) MOVE working config to My Configs LIVE ----
                                    val saved = cfg.copy()
                                    myStore.addServers(listOf(saved))
                                    if (myStore.getSelectedId() == null) {
                                        myStore.getServers().firstOrNull()?.let { myStore.setSelectedId(it.id) }
                                    }
                                    workingThisBatch.add(saved)
                                    totalWorking++
                                    _progress.value = _progress.value.copy(
                                        workingFound = totalWorking,
                                        lastWorkingMs = ms
                                    )
                                } else {
                                    PingService.setExternalStatus(cfg.id, PingService.PingStatus.Unreachable)
                                }
                                val n = tested.incrementAndGet()
                                _progress.value = _progress.value.copy(
                                    phase = "Testing $n/${fresh.size}",
                                    testedInBatch = n
                                )
                            }
                        }
                    }.awaitAll()
                }

                if (!isActive) break

                // ---- 4) CLEAN UP: drop the non-working configs from the free list ----
                // (the working ones were already copied to My Configs). We wipe the
                // whole free batch and let the next cycle bring 120 brand-new ones.
                val workingKeys = workingThisBatch.map { ConfigParser.dedupKey(it) }.toHashSet()
                val remaining = freeStore.get().filter {
                    ConfigParser.dedupKey(it) in workingKeys
                }.toMutableList()

                // Keep the free list bounded; once it crosses ~BATCH, clear it so
                // the list keeps cycling fresh configs (per brief).
                if (remaining.size >= BATCH) {
                    freeStore.clear()
                    // keep dedup keys so wrapped duplicates aren't re-added immediately
                } else {
                    freeStore.replaceAll(remaining)
                }

                // Small breather so the UI repaints and we don't hammer the network.
                _progress.value = _progress.value.copy(phase = "Cycle $cycle done · $totalWorking working")
                delay(1_200)
            }
        }
        job?.invokeOnCompletion {
            _progress.value = _progress.value.copy(running = false, phase = "Stopped")
        }
    }

    /** Stop the loop (CANCEL button). */
    fun stop() {
        job?.cancel()
        job = null
        _progress.value = _progress.value.copy(running = false, phase = "Stopped")
    }

    private suspend fun probeWithRetry(cfg: ServerConfig): Long {
        val first = withTimeoutOrNull(PRIMARY_TIMEOUT_MS) { Pinger.ping(cfg) }
        if (first != null && first > 0L) return first
        val retry = withTimeoutOrNull(RETRY_TIMEOUT_MS) { Pinger.ping(cfg) }
        return if (retry != null && retry > 0L) retry else Pinger.UNREACHABLE
    }
}
