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

    /**
     * v4.7 — ADAPTIVE concurrency, LOWERED. Every probe spins up a throwaway
     * native Xray core (tens of MB each); the old ceiling of 16 simultaneous
     * cores exhausted native memory on 1–2 GB devices and was a major crash
     * source during Auto Test / PING ALL on big lists. Scaled from CPU cores:
     * 2 cores → 4, 4 cores → 6, 8+ cores → 8.
     */
    val MAX_CONCURRENCY: Int by lazy {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        (cores + 2).coerceIn(4, 8)
    }
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

    /**
     * v6.2 — OBSERVABLE SWEEP STATE. The UI subscribes to this to:
     *   • disable the PING ALL / per-row PING buttons while a sweep is running so
     *     rapid taps can't stack sweeps (the reported "I keep tapping ping and the
     *     app stacks and freezes" bug),
     *   • show the top progress bar (tested/total) the whole time a sweep is alive,
     *   • re-enable the buttons the instant the sweep completes.
     * Exposed as a StateFlow so re-subscription (tab switch) reads the live truth.
     */
    data class SweepState(
        val running: Boolean = false,
        val tested: Int = 0,
        val total: Int = 0
    )
    private val _sweep = MutableStateFlow(SweepState())
    val sweep: StateFlow<SweepState> = _sweep.asStateFlow()

    @Volatile private var sweepJob: Job? = null
    @Volatile private var lastSweepEndedAt = 0L
    @Volatile private var loadedBucket: String? = null

    /** v6.2 — true while ANY sweep (pingAll) is in flight. */
    val isSweepRunning: Boolean get() = sweepJob?.isActive == true

    /**
     * Hydrate the in-memory flow from the persisted [bucket] store once. Safe to
     * call from every fragment onViewCreated — only the first call per bucket
     * actually loads (so a fresh ping run isn't clobbered by stale disk data).
     */
    @Synchronized
    fun hydrate(ctx: Context, bucket: String) {
        // v5.6 — ALWAYS load the requested bucket. The old early-return skipped
        // re-loading whenever the singleton had already loaded a DIFFERENT bucket
        // (e.g. loaded "free", then user opens "my"), so persisted "my" pings were
        // never restored → looked like a reset. We now merge every bucket's saved
        // results into the shared content-keyed map. Live (in-memory) values win
        // over disk so an in-flight sweep is never clobbered by stale disk data.
        loadedBucket = bucket
        val store = PingStore(ctx, bucket)
        val saved = store.load()
        if (saved.isEmpty()) return
        val unstable = store.loadUnstable()
        val restored = saved.mapValues { (key, ms) ->
            when {
                ms <= 0L -> PingStatus.Unreachable
                key in unstable -> PingStatus.Unstable(ms)
                else -> PingStatus.Reachable(ms)
            }
        }
        // Merge: keep any live entry (Testing/fresh Reachable) over the disk copy,
        // but bring in every saved key that isn't already present.
        val merged = HashMap<String, PingStatus>(restored)
        merged.putAll(_statuses.value)   // live values overwrite restored ones
        _statuses.value = merged
    }

    /**
     * v5.6 — the flow is keyed by CONTENT ([ConfigParser.pingKey]) not the
     * ephemeral UUID, so a measured ping sticks to a config across restart / tab
     * switch / re-parse. UI adapters look statuses up with [statusOfConfig].
     */
    fun keyOf(cfg: ServerConfig): String = ConfigParser.pingKey(cfg)

    /** Latest known status for a raw content key (Idle if unknown). */
    fun statusOf(key: String): PingStatus = _statuses.value[key] ?: PingStatus.Idle

    /** Latest known status for a config, keyed by its stable content key. */
    fun statusOfConfig(cfg: ServerConfig): PingStatus =
        _statuses.value[keyOf(cfg)] ?: PingStatus.Idle

    /**
     * v4.0 — allow an external driver (the AutoTestEngine) to push a status into
     * the shared flow so the Free tab renders live spinners / results during an
     * automatic test run, exactly as a manual PING ALL would. Keyed by content.
     */
    fun setExternalStatus(cfg: ServerConfig, status: PingStatus) = setStatus(keyOf(cfg), status)

    /**
     * v6.2 — Ping a SINGLE config immediately (the per-row PING button). Runs on
     * the app scope so a tab switch can't cancel it. Per the brief, pressing PING
     * on a single config is ALWAYS allowed — even mid-sweep — because it is one
     * isolated probe, not a list sweep that could stack. It clears that ONE
     * config's old ping the moment the probe starts (showing "Pinging…") and only
     * writes the new result when the probe finishes.
     */
    fun pingOne(ctx: Context, cfg: ServerConfig, bucket: String) {
        val key = keyOf(cfg)
        appScope.launch {
            // Clear the old ping for THIS config and show the live "Pinging…"
            // state immediately so the user sees the row react to the tap.
            setStatus(key, PingStatus.Testing)
            val ms = probeWithRetry(cfg)
            applyResult(key, ms)
            persist(ctx, bucket)
        }
    }

    /**
     * v6.2 — Sweep the whole [configs] list with bounded concurrency.
     *
     * BEHAVIOUR (per the v6.2 brief):
     *   • If a sweep is ALREADY running, return false — the UI MUST disable the
     *     PING ALL button for the whole sweep so this branch is never reached by
     *     a spam-tap. This is the "don't let pings stack" fix: there is at most
     *     ONE sweep alive at any time.
     *   • The INSTANT a sweep starts, ALL existing ping results in the bucket are
     *     CLEARED and every config is marked `Testing` ("Pinging…"). The brief:
     *     "when I press ping (single or ping all), the old ping must be cleared
     *     and a new one taken and shown." So the row shows "Pinging…" in English
     *     while the probe is in flight, then the fresh result lands.
     *   • Progress is published to [sweep] (tested/total) so the top progress bar
     *     climbs honestly and the buttons stay disabled until tested == total.
     *   • Configs are probed in their ORIGINAL LIST ORDER (a bounded-concurrency
     *     pool still launches them in order, so early rows finish first) — the
     *     "auto test pings config 240 before config 1" bug is gone.
     *
     * @return true if a sweep was started, false if one is already running.
     */
    fun pingAll(ctx: Context, configs: List<ServerConfig>, bucket: String): Boolean {
        // Only one sweep at a time. The UI disables PING ALL while running, so a
        // spam-tap can never stack a second sweep on top of the first.
        val running = sweepJob?.isActive == true
        if (running) return false
        if (configs.isEmpty()) return false

        // Snapshot the ordered list of (key, cfg) so the sweep is stable even if
        // the UI mutates its list while we run (Auto Test churn, delete, etc.).
        val ordered = configs.map { keyOf(it) to it }

        sweepJob = appScope.launch {
            // CLEAR every previous ping for this bucket and show "Pinging…" for
            // every config in the list — the user pressed PING ALL, so a fresh
            // measurement is expected for each, not the stale old value.
            val cleared = HashMap<String, PingStatus>(_statuses.value)
            ordered.forEach { (k, _) -> cleared[k] = PingStatus.Testing }
            _statuses.value = cleared
            _sweep.value = SweepState(running = true, tested = 0, total = ordered.size)

            val tested = java.util.concurrent.atomic.AtomicInteger(0)
            withContext(Dispatchers.IO) {
                // Bounded concurrency, but launches happen in list order so the
                // first configs are probed first (the reported "config 240 is
                // pinged before config 1" bug is fixed).
                ordered.map { (key, cfg) ->
                    async {
                        gate.withPermit {
                            setStatus(key, PingStatus.Testing)
                            val ms = probeWithRetry(cfg)
                            applyResult(key, ms)
                            val n = tested.incrementAndGet()
                            _sweep.value = SweepState(running = true, tested = n, total = ordered.size)
                        }
                    }
                }.awaitAll()
            }
            persist(ctx, bucket)
            lastSweepEndedAt = System.currentTimeMillis()
            _sweep.value = SweepState(running = false, tested = ordered.size, total = ordered.size)
        }
        return true
    }

    /**
     * Cancel any running sweep (user pressed the v6.3 CANCEL button, or the list
     * was cleared).
     *
     * v6.3 — cancelling must be NON-DISRUPTIVE:
     *   • every result that was already measured stays exactly as it is, so the
     *     user keeps the pings they waited for;
     *   • every row that was still `Testing` is flipped back to `Idle` so no row
     *     is left spinning "Pinging…" forever after the sweep goes away;
     *   • the sweep state is published as stopped synchronously, so the UI
     *     re-enables PING ALL / SELECT / per-row PING immediately.
     */
    fun cancel() {
        sweepJob?.cancel()
        sweepJob = null
        // Clear the orphaned spinners without touching finished measurements.
        val snapshot = _statuses.value
        if (snapshot.values.any { it === PingStatus.Testing }) {
            val cleaned = HashMap<String, PingStatus>(snapshot.size)
            snapshot.forEach { (k, v) ->
                if (v !== PingStatus.Testing) cleaned[k] = v
            }
            _statuses.value = cleaned
        }
        _sweep.value = SweepState(running = false, tested = 0, total = 0)
    }

    /** Forget everything (used by "clear ping results"). */
    fun clear(ctx: Context, bucket: String) {
        cancel()
        _statuses.value = emptyMap()
        loadedBucket = null
        PingStore(ctx, bucket).clear()
    }

    /**
     * v4.7 — BOUND the in-memory status map. The Auto-Test engine churns
     * hundreds of configs per cycle; without pruning, the statuses map (and the
     * persisted PingStore mirror) grew without limit across cycles — a slow
     * memory leak that ended in the crash users saw exactly when the NEXT
     * 240-config batch was being appended. Callers pass the ids that are still
     * alive (current free list + My Configs); everything else is dropped.
     */
    @Synchronized
    fun prune(keepKeys: Set<String>) {
        val cur = _statuses.value
        // v5.6 — only prune when the map is clearly oversized vs. what we keep,
        // and NEVER shrink below a healthy floor so a transient small keep-set
        // (e.g. mid-reload) can't wipe results the user is still looking at.
        if (cur.size <= keepKeys.size || cur.size < PRUNE_FLOOR) return
        val pruned = cur.filterKeys { it in keepKeys }
        if (pruned.size != cur.size) _statuses.value = pruned
    }

    private const val PRUNE_FLOOR = 400

    // ---- internals -------------------------------------------------------

    /**
     * v4.3 — CRITICAL FIX. [Pinger.ping] is ALREADY hard-bounded internally
     * (PER_CONFIG_BUDGET_MS). The old code wrapped it in an OUTER 2 500 ms
     * timeout that fired before the inner work could finish, so EVERY config
     * reported unreachable. We now call ping directly (no shorter outer
     * timeout) and simply retry ONCE if the first attempt misses — flaky links
     * very often succeed on the second try.
     */
    private suspend fun probeWithRetry(cfg: ServerConfig): Long {
        val first = runCatching { Pinger.ping(cfg) }.getOrDefault(Pinger.UNREACHABLE)
        if (first > 0L) return first
        val retry = runCatching { Pinger.ping(cfg) }.getOrDefault(Pinger.UNREACHABLE)
        return if (retry > 0L) retry else Pinger.UNREACHABLE
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

    /**
     * v4.7 — @Synchronized: many Auto-Test / PING-ALL coroutines push statuses
     * concurrently; the old unguarded read-modify-write lost updates under
     * contention (rows stuck on "testing…" forever). A plain monitor makes
     * every write atomic without changing the flow semantics.
     */
    @Synchronized
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
