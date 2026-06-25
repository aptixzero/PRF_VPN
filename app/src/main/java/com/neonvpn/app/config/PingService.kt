package com.neonvpn.app.config

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v3.8 §4.4 — APP-SCOPED ping engine (Kotlin object singleton).
 *
 * The old design ran the ping sweep inside each Fragment's
 * `viewLifecycleOwner.lifecycleScope`, so switching tabs (which destroys the
 * fragment view) cancelled the whole run and the half-finished results were
 * lost. PingService instead lives for the entire process: the sweep coroutine
 * is launched on [ProcessLifecycleOwner]'s scope, so it keeps running while the
 * user browses other tabs and only the UI re-subscribes to the [statuses] flow.
 *
 * Results are exposed as a single observable [StateFlow] of
 * `ConfigId -> PingStatus`, the single source of truth both tabs read. The map
 * is also mirrored into [PingStore] (per bucket) so it survives a full restart.
 *
 * Concurrency / timing (per brief):
 *   • A [Semaphore] of [MAX_CONCURRENCY] (16) bounds simultaneous probes so a
 *     huge list can't open thousands of sockets at once.
 *   • Each config gets a [PRIMARY_TIMEOUT_MS] (2500 ms) attempt; on miss it is
 *     retried once with a tighter [RETRY_TIMEOUT_MS] (1500 ms).
 *   • After a full sweep, [BACKOFF_MS] (4000 ms) idle before the next is allowed
 *     (rapid re-taps coalesce instead of stacking sweeps).
 *   • A node that flips reachable→unreachable between sweeps is demoted to
 *     [PingStatus.Unstable] rather than instantly shown green, so flapping nodes
 *     are visually distinct and sort below stable ones.
 */
object PingService {

    const val MAX_CONCURRENCY = 16
    const val PRIMARY_TIMEOUT_MS = 2_500L
    const val RETRY_TIMEOUT_MS = 1_500L
    const val BACKOFF_MS = 4_000L

    // Color thresholds (ms) shared by both list adapters so the UI is identical.
    const val GOOD_MS = 300L      // green
    const val OK_MS = 800L        // lime/amber boundary

    /** A single config's current ping state. */
    sealed class PingStatus {
        /** Never tested yet. */
        object Idle : PingStatus()
        /** A probe is currently in flight. */
        object Testing : PingStatus()
        /** Reachable with a confirmed latency (ms). */
        data class Reachable(val ms: Long) : PingStatus()
        /** Was reachable, just failed a sweep — flapping; keep last good [ms]. */
        data class Unstable(val ms: Long) : PingStatus()
        /** Confirmed unreachable. */
        object Unreachable : PingStatus()
    }

    private val appScope: CoroutineScope
        get() = ProcessLifecycleOwner.get().lifecycleScope

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(MAX_CONCURRENCY)

    private val _statuses = MutableStateFlow<Map<String, PingStatus>>(emptyMap())
    /** The single observable source of truth both tabs read (§4.4). */
    val statuses: StateFlow<Map<String, PingStatus>> = _statuses.asStateFlow()

    @Volatile private var sweepJob: Job? = null
    @Volatile private var lastSweepEndedAt = 0L
    @Volatile private var loadedBucket: String? = null

    /**
     * Hydrate the in-memory flow from the persisted [bucket] store once. Safe to
     * call from every fragment onViewCreated — only the first call per bucket
     * actually loads (so a fresh ping run isn't clobbered by stale disk data).
     */
    @Synchronized
    fun hydrate(ctx: Context, bucket: String) {
        if (loadedBucket == bucket && _statuses.value.isNotEmpty()) return
        loadedBucket = bucket
        val store = PingStore(ctx, bucket)
        val saved = store.load()
        if (saved.isEmpty()) return
        val unstable = store.loadUnstable()
        val restored = saved.mapValues { (id, ms) ->
            when {
                ms <= 0L -> PingStatus.Unreachable
                id in unstable -> PingStatus.Unstable(ms)
                else -> PingStatus.Reachable(ms)
            }
        }
        // Merge rather than replace so any live Testing/Reachable entries survive.
        _statuses.value = restored + _statuses.value
    }

    /** Latest known status for [id] (Idle if unknown). */
    fun statusOf(id: String): PingStatus = _statuses.value[id] ?: PingStatus.Idle

    /**
     * v4.0 — allow an external driver (the AutoTestEngine) to push a status into
     * the shared flow so the Free tab renders live spinners / results during an
     * automatic test run, exactly as a manual PING ALL would.
     */
    fun setExternalStatus(id: String, status: PingStatus) = setStatus(id, status)

    /**
     * Ping a SINGLE config immediately (the per-row PING button). Runs on the
     * app scope so a tab switch can't cancel it.
     */
    fun pingOne(ctx: Context, cfg: ServerConfig, bucket: String) {
        appScope.launch {
            setStatus(cfg.id, PingStatus.Testing)
            val ms = probeWithRetry(cfg)
            applyResult(cfg.id, ms)
            persist(ctx, bucket)
        }
    }

    /**
     * Sweep the whole [configs] list with bounded concurrency. Coalesces with an
     * in-flight sweep and honours the [BACKOFF_MS] cool-down between sweeps.
     *
     * @return true if a sweep was started, false if one is already running or we
     *         are still inside the backoff window.
     */
    fun pingAll(ctx: Context, configs: List<ServerConfig>, bucket: String): Boolean {
        val running = sweepJob?.isActive == true
        val sinceLast = System.currentTimeMillis() - lastSweepEndedAt
        if (running || sinceLast < BACKOFF_MS) return false

        sweepJob = appScope.launch {
            // Mark everything as testing up front so the whole list shows spinners.
            val testing = _statuses.value.toMutableMap()
            configs.forEach { testing[it.id] = PingStatus.Testing }
            _statuses.value = testing

            withContext(Dispatchers.IO) {
                configs.map { cfg ->
                    async {
                        gate.withPermit {
                            val ms = probeWithRetry(cfg)
                            applyResult(cfg.id, ms)
                        }
                    }
                }.awaitAll()
            }
            persist(ctx, bucket)
            lastSweepEndedAt = System.currentTimeMillis()
        }
        return true
    }

    /** Cancel any running sweep (e.g. user cleared the list). */
    fun cancel() {
        sweepJob?.cancel()
        sweepJob = null
    }

    /** Forget everything (used by "clear ping results"). */
    fun clear(ctx: Context, bucket: String) {
        cancel()
        _statuses.value = emptyMap()
        loadedBucket = null
        PingStore(ctx, bucket).clear()
    }

    // ---- internals -------------------------------------------------------

    /** 2500 ms attempt, then a single tighter 1500 ms retry on miss. */
    private suspend fun probeWithRetry(cfg: ServerConfig): Long {
        val first = withTimeoutOrNull(PRIMARY_TIMEOUT_MS) { Pinger.ping(cfg) }
        if (first != null && first > 0L) return first
        val retry = withTimeoutOrNull(RETRY_TIMEOUT_MS) { Pinger.ping(cfg) }
        return if (retry != null && retry > 0L) retry else Pinger.UNREACHABLE
    }

    /**
     * Fold a raw latency result into the flow, applying the unstable-demote rule:
     * a node that WAS reachable but just failed becomes [PingStatus.Unstable]
     * (keeps last-good ms) instead of jumping straight to Unreachable.
     */
    private fun applyResult(id: String, ms: Long) {
        val prev = _statuses.value[id]
        val next = when {
            ms > 0L -> PingStatus.Reachable(ms)
            prev is PingStatus.Reachable -> PingStatus.Unstable(prev.ms)
            prev is PingStatus.Unstable -> PingStatus.Unreachable
            else -> PingStatus.Unreachable
        }
        setStatus(id, next)
    }

    private fun setStatus(id: String, status: PingStatus) {
        _statuses.value = _statuses.value.toMutableMap().apply { put(id, status) }
    }

    /** Persist only the finished (Reachable/Unstable/Unreachable) results. */
    private fun persist(ctx: Context, bucket: String) {
        val map = _statuses.value.mapNotNull { (id, st) ->
            when (st) {
                is PingStatus.Reachable -> id to st.ms
                is PingStatus.Unstable -> id to st.ms
                PingStatus.Unreachable -> id to -1L
                else -> null
            }
        }.toMap()
        val unstable = _statuses.value.filterValues { it is PingStatus.Unstable }.keys
        val store = PingStore(ctx, bucket)
        store.save(map)
        store.saveUnstable(unstable)
    }

    // ---- UI helpers (shared by both list adapters) -----------------------

    /** ARGB text/dot color for a status (identical thresholds in both tabs). */
    fun colorOf(status: PingStatus): Int = when (status) {
        PingStatus.Idle, PingStatus.Testing -> 0xFF5C7A66.toInt()
        PingStatus.Unreachable -> 0xFFFF1E3C.toInt()
        is PingStatus.Unstable -> 0xFFFFC400.toInt()              // amber = flapping
        is PingStatus.Reachable -> when {
            status.ms < GOOD_MS -> 0xFF00FF66.toInt()             // green
            status.ms < OK_MS -> 0xFFCFFF00.toInt()               // lime
            else -> 0xFFFF8A3B.toInt()                            // orange
        }
    }

    /**
     * Sort weight (lower = higher in the list): stable-fast first, then
     * unstable (kept below stable of the same latency via +1ms bias), then
     * testing, then unknown, then unreachable last.
     */
    fun sortKey(status: PingStatus): Long = when (status) {
        is PingStatus.Reachable -> status.ms
        is PingStatus.Unstable -> status.ms + 1_000_000L          // below all stable
        PingStatus.Testing -> Long.MAX_VALUE - 2
        PingStatus.Idle -> Long.MAX_VALUE - 1
        PingStatus.Unreachable -> Long.MAX_VALUE
    }
}
