package com.neonvpn.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.neonvpn.app.R
import com.neonvpn.app.config.AutoTestEngine
import com.neonvpn.app.config.ConfigParser
import com.neonvpn.app.config.ConfigStore
import com.neonvpn.app.config.FreeConfigSource
import com.neonvpn.app.config.FreeConfigStore
import com.neonvpn.app.config.PingService
import com.neonvpn.app.config.PingStore
import com.neonvpn.app.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v4.0 "Free Configs" tab — rebuilt around [FreeConfigSource] (aptixzero/con_new)
 * and the new [AutoTestEngine].
 *
 * Actions (matching the UI sheet):
 *   • START SEARCH  → pulls the NEXT 120 unique vless/vmess configs and APPENDS
 *                     them to the list (renamed "Server N"). Acts as STOP while a
 *                     manual search is in flight.
 *   • PING ALL      → real, color-coded proxied ping of every config; results
 *                     re-sort (fast up, dead down) and persist.
 *   • AUTO TEST     → starts the continuous engine: it auto-searches, auto-pings,
 *                     moves the WORKING ones into My Configs live, drops the dead
 *                     ones, wipes + re-searches, and loops forever. The button
 *                     becomes a CANCEL button while running.
 *   • DELETE ALL    → clears the whole free list.
 *   • SELECT        → multi-select toolbar (Select All / Copy / Delete).
 *   • per row       → copy / delete / ping a single config, or tap to save it
 *                     into My Configs and select it.
 */
class FreeConfigsFragment : Fragment() {

    private lateinit var freeStore: FreeConfigStore
    private lateinit var myStore: ConfigStore
    private lateinit var adapter: ServerAdapter

    private lateinit var btnSearch: View
    private lateinit var btnSearchLabel: TextView
    private lateinit var btnPingAll: TextView
    private lateinit var btnAutoTest: TextView
    private lateinit var btnSelect: TextView
    private lateinit var btnDeleteAll: TextView
    private lateinit var selectBar: View
    private lateinit var btnSelectAll: TextView
    private lateinit var btnCopySel: TextView
    private lateinit var btnDeleteSel: TextView
    private lateinit var progressBox: View
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var emptyView: View
    private lateinit var listHeader: TextView

    private var job: Job? = null
    @Volatile private var busy = false

    /** dedup keys of everything currently in the list (survives appends). */
    private val seenKeys = HashSet<String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_free_configs, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        freeStore = FreeConfigStore(requireContext())
        myStore = ConfigStore(requireContext())

        btnSearch = view.findViewById(R.id.btn_search)
        btnSearchLabel = view.findViewById(R.id.btn_search_label)
        btnPingAll = view.findViewById(R.id.btn_ping_all)
        btnAutoTest = view.findViewById(R.id.btn_auto_test)
        btnSelect = view.findViewById(R.id.btn_select)
        btnDeleteAll = view.findViewById(R.id.btn_delete_all)
        selectBar = view.findViewById(R.id.select_bar)
        btnSelectAll = view.findViewById(R.id.btn_select_all)
        btnCopySel = view.findViewById(R.id.btn_copy_sel)
        btnDeleteSel = view.findViewById(R.id.btn_delete_sel)
        progressBox = view.findViewById(R.id.progress_box)
        progressBar = view.findViewById(R.id.search_progress)
        progressLabel = view.findViewById(R.id.progress_label)
        emptyView = view.findViewById(R.id.empty_view)
        listHeader = view.findViewById(R.id.list_header)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        val initial = freeStore.get()
        adapter = ServerAdapter(
            items = initial,
            selectedId = null,
            onSelect = { cfg -> onRowTap(cfg) },
            onDelete = { cfg -> deleteOne(cfg) },
            onCopy = { cfg -> copyOne(cfg) },
            onPing = { cfg, pos -> pingOne(cfg, pos) }
        )
        recycler.adapter = adapter

        seenKeys.clear()
        // v4.6 — seed dedup memory from the PERSISTENT seen-set (bounded, survives
        // restarts) + everything already in the list + saved My-Configs, so a
        // manual search never re-adds a config the user has already seen/added.
        runCatching { seenKeys.addAll(com.neonvpn.app.config.SeenConfigStore.load(requireContext())) }
        initial.forEach { seenKeys.add(ConfigParser.dedupKey(it)) }
        runCatching { myStore.getServers().forEach { seenKeys.add(ConfigParser.dedupKey(it)) } }

        PingService.hydrate(requireContext(), PingStore.FREE)
        observePingStatuses()
        observeSweep()
        observeAutoTest()

        btnSearch.setOnClickListener { onSearchClicked() }
        btnPingAll.setOnClickListener { pingAll() }
        btnAutoTest.setOnClickListener { onAutoTestClicked() }
        btnSelect.setOnClickListener { toggleSelectMode() }
        btnDeleteAll.setOnClickListener { deleteAll() }
        btnSelectAll.setOnClickListener { selectAllToggle() }
        btnCopySel.setOnClickListener { copySelected() }
        btnDeleteSel.setOnClickListener { deleteSelected() }

        refreshEmpty()
        updateListHeader()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try { FreeConfigSource.ensureFreshState(requireContext()) } catch (_: Throwable) {}
        }

        // v5.7 — NO automatic pinging. Pings are taken ONLY when the user asks
        // for them (per-row PING button or PING ALL). Opening / switching to this
        // tab, START SEARCH, screen off/on and relaunch must NEVER start a ping
        // sweep on their own, and must NEVER clear existing results. The persisted
        // (content-keyed) results are simply re-rendered.
    }

    /**
     * v6.2 — OBSERVE the app-scoped sweep state. While a PING ALL sweep is
     * running we SHOW the top progress bar (Pinging… x/y) and DISABLE the PING
     * ALL / SEARCH / SELECT / DELETE ALL / per-row PING controls so rapid taps
     * can't stack sweeps (the reported "ping keeps stacking and the app
     * freezes" bug). The moment the sweep completes the bar hides and the
     * buttons re-enable. The sweep runs on the app scope, so even after a tab
     * switch the buttons stay disabled until the sweep actually finishes.
     */
    private fun observeSweep() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PingService.sweep.collect { s ->
                    if (!isAdded) return@collect
                    if (s.running) {
                        // ── v6.9: THE PING PROCESS IS NOW VISIBLE ────────────
                        // «روند پینگ گرفتن رو نمیتونم ببینم». This bar used to move
                        // only for a MANUAL ping-all; the automatic Auto-Test sweep
                        // — which is what actually pings the 240 free configs —
                        // published nothing, so the tab looked frozen for minutes.
                        // AutoTestEngine now feeds the same flow, and the label
                        // reports a live count plus a percentage.
                        val pct = if (s.total > 0) (s.tested * 100 / s.total) else 0
                        showProgress(pct, "Pinging… ${s.tested}/${s.total}  ·  $pct%")
                        // Only a MANUAL sweep locks the controls. A background Auto
                        // Test must leave the buttons usable, otherwise the user is
                        // frozen out of their own list for as long as it runs.
                        val manual = PingService.isManualSweepRunning
                        val a = if (manual) 0.4f else 1f
                        btnPingAll.isEnabled = !manual
                        btnPingAll.alpha = a
                        btnSearch.isEnabled = !manual
                        btnSearch.alpha = a
                        btnSelect.isEnabled = !manual
                        btnSelect.alpha = a
                        btnDeleteAll.isEnabled = !manual
                        btnDeleteAll.alpha = a
                        adapter.pingButtonsEnabled = !manual
                    } else {
                        // Don't clobber an in-flight Auto-Test progress display.
                        if (!AutoTestEngine.isRunning) hideProgressDelayed()
                        btnPingAll.isEnabled = true
                        btnPingAll.alpha = 1f
                        btnSearch.isEnabled = true
                        btnSearch.alpha = 1f
                        btnSelect.isEnabled = true
                        btnSelect.alpha = 1f
                        btnDeleteAll.isEnabled = true
                        btnDeleteAll.alpha = 1f
                        adapter.pingButtonsEnabled = true
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        job?.cancel()
        // NOTE: do NOT stop AutoTestEngine here — it is app-scoped and must keep
        // running across tab switches until the user explicitly cancels it.
        // The PingService sweep is ALSO app-scoped, so an in-flight manual ping
        // survives this view being destroyed (the results still persist).
    }

    /** v5.9 — true while a manual PING ALL sweep is running on the Free tab. */
    @Volatile private var pingAllInFlight = false

    /** Render live ping statuses from the app-scoped PingService (§4.4). */
    private fun observePingStatuses() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PingService.statuses.collect { map ->
                    // v6.0 — during a manual PING ALL sweep, RE-SORT the INSTANT
                    // each ping lands so the lowest-ping configs pin to the top
                    // immediately. Auto-scroll to the top only when the user was
                    // already there, so a reorder never yanks a user who scrolled
                    // down. (During Auto Test the engine owns the list ordering, so
                    // we leave that path untouched.)
                    if (pingAllInFlight && !AutoTestEngine.isRunning) {
                        val atTop = isAtTop()
                        adapter.submitList(ArrayList(adapter.items), map)
                        if (atTop) scrollToTop()
                    } else {
                        adapter.applyStatuses(map)
                    }
                    // Persist the (re-sorted) order — but NOT while Auto Test is
                    // running, because the engine owns the free store then and a
                    // double-writer races (this was a crash source). Snapshot the
                    // list defensively before handing it to the store.
                    if (!AutoTestEngine.isRunning && adapter.items.isNotEmpty()) {
                        freeStore.replaceAll(ArrayList(adapter.items))
                    }
                }
            }
        }
    }

    /** Last time we did a (relatively expensive) full reload during Auto Test. */
    @Volatile private var lastReloadAt = 0L

    /** v4.0 — reflect the continuous Auto-Test engine's progress in the UI. */
    private fun observeAutoTest() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AutoTestEngine.progress.collect { p ->
                    if (!isAdded) return@collect
                    if (p.running) {
                        btnAutoTest.text = getString(R.string.autotest_cancel)
                        showProgress(
                            if (p.batchSize > 0) (p.testedInBatch * 100 / p.batchSize) else 0,
                            "${p.phase} · ${p.workingFound} working → My Configs"
                        )
                        // The engine appended fresh configs to the free store on disk;
                        // reload them so the user sees the list churn live — but THROTTLE
                        // it (≤ every 700 ms) so we don't rebuild the RecyclerView on every
                        // single status tick (that thrashing was a jank/crash risk).
                        val now = System.currentTimeMillis()
                        if (now - lastReloadAt >= 700L) {
                            lastReloadAt = now
                            reloadFromStore()
                        }
                    } else {
                        if (btnAutoTest.text == getString(R.string.autotest_cancel)) {
                            btnAutoTest.text = getString(R.string.auto_test)
                            hideProgressDelayed()
                            reloadFromStore()
                            // v6.3 — the engine gave up by itself (it could not add
                            // configs even after the full recovery ladder). The brief
                            // requires Auto Test to TURN ITSELF OFF in that case
                            // rather than sit there "pointlessly ON" with an empty
                            // list, so tell the user plainly why the button flipped.
                            if (p.autoStopped) toast(getString(R.string.autotest_auto_stopped))
                        }
                    }
                }
            }
        }
    }

    private fun reloadFromStore() {
        // Fully defensive: a store read / adapter swap during heavy Auto-Test
        // churn must never bubble an exception onto the main thread.
        //
        // v4.7 — DEFINITIVE CRASH FIX for the "next 240 batch" transition. The
        // v4.5 code still swapped `adapter.items` BEFORE running the DiffUtil
        // pass, so the diff's "old list" was already the new list while the
        // RecyclerView was displaying the previous one — row counts desynced →
        // "Inconsistency detected. Invalid view holder adapter position". The
        // adapter now exposes submitList(), which computes the diff against the
        // list ACTUALLY displayed and swaps + dispatches atomically. We also
        // still defer while the RecyclerView is mid-layout for extra safety.
        runCatching {
            if (!isAdded) return
            val rv = view?.findViewById<RecyclerView>(R.id.recycler)
            if (rv != null && (rv.isComputingLayout || rv.isAnimating)) {
                // Defer until the RecyclerView is idle so we never mutate the
                // backing list while it is being read for layout.
                rv.post { runCatching { reloadFromStore() } }
                return
            }
            val fresh = freeStore.get()
            seenKeys.clear()
            fresh.forEach { seenKeys.add(ConfigParser.dedupKey(it)) }
            // v5.9 — remember whether the user is parked at the top BEFORE the
            // swap so we can keep them there. The healthy (pinging) configs are
            // pinned to the top, so keeping the user at the top means they always
            // watch the configs that respond, never dragged to the dead ones.
            val atTop = isAtTop()
            // Atomic, crash-proof swap + diff in ONE pass.
            adapter.submitList(fresh, PingService.statuses.value)
            refreshEmpty()
            updateListHeader()
            if (atTop) scrollToTop()
        }
    }

    /** True if the list is scrolled to (or very near) the top. */
    private fun isAtTop(): Boolean {
        val rv = view?.findViewById<RecyclerView>(R.id.recycler) ?: return true
        val lm = rv.layoutManager as? LinearLayoutManager ?: return true
        return lm.findFirstVisibleItemPosition() <= 1
    }

    // --------------------------------------------------------------- search
    /**
     * v6.0 — Manual SEARCH now ALSO opens the connectivity-test page (per the v6
     * brief: "when the user taps Search — manually — or Auto Test, a connection
     * test page must open"). The page runs the real two-phase probe (test the
     * connection to the sources 0..60 %, then add configs 60..100 % from the
     * reached source) and closes itself, adding the configs behind the scenes.
     */
    private fun onSearchClicked() {
        if (adapter.selectMode) toggleSelectMode()
        runCatching {
            startActivity(android.content.Intent(requireContext(), AutoTestActivity::class.java))
        }
    }

    private fun startSearch() {
        if (adapter.selectMode) toggleSelectMode()
        setBusy(true)
        btnSearchLabel.text = getString(R.string.searching)
        showProgress(0, getString(R.string.searching))

        job = viewLifecycleOwner.lifecycleScope.launch {
            try { FreeConfigSource.ensureFreshState(requireContext()) } catch (_: Throwable) {}

            val batch = FreeConfigSource.nextBatch(
                ctx = requireContext(),
                startIndex = adapter.items.size,
                seenKeys = seenKeys
            ) { added, target, status ->
                val pct = if (target > 0) (added * 100 / target) else 0
                activity?.runOnUiThread { if (isAdded) showProgress(pct, status) }
            }
            if (!isActive || !isAdded) return@launch

            if (batch.configs.isNotEmpty()) {
                // v5.7 — APPEND the fresh configs to the END of the list and do
                // NOT re-sort or auto-ping. New rows land untested at the bottom;
                // the user's current scroll position (and any previously-pinged
                // rows already pinned at the top) stays exactly where it was. A
                // ping is taken only when the user presses PING ALL / a row PING.
                val merged = ArrayList(adapter.items).apply { addAll(batch.configs) }
                freeStore.replaceAll(merged)
                adapter.appendItems(batch.configs)
                refreshEmpty()
                updateListHeader()
            }

            showProgress(
                100,
                when {
                    batch.configs.isEmpty() && batch.foundRaw == 0 ->
                        getString(R.string.feed_unreachable_offline)
                    batch.configs.isEmpty() -> getString(R.string.search_none)
                    else -> getString(R.string.search_done, batch.configs.size)
                }
            )
            if (batch.configs.isEmpty() && batch.foundRaw == 0) {
                toast(getString(R.string.feed_unreachable_offline))
            }
            setBusy(false)
            btnSearchLabel.text = getString(R.string.start_search)
            hideProgressDelayed()
        }
    }

    // ------------------------------------------------------------ auto test
    /**
     * v6.0 — AUTO TEST. If the engine is already running the button acts as CANCEL
     * (stops the continuous loop). Otherwise it opens the connectivity-test page,
     * which runs the two-phase probe and starts the continuous engine itself.
     *
     * The engine's own re-entrancy guard ([AutoTestEngine.restart]) means the user
     * can open this page as many times as they like — even mid-run — without the
     * engine getting stuck or two loops fighting (the reported "stops adding after
     * a couple of tries" bug).
     */
    private fun onAutoTestClicked() {
        if (AutoTestEngine.isRunning) {
            AutoTestEngine.stop()
            btnAutoTest.text = getString(R.string.auto_test)
            hideProgressDelayed()
            toast(getString(R.string.autotest_stop))
            return
        }
        if (busy) { job?.cancel(); setBusy(false); btnSearchLabel.text = getString(R.string.start_search) }
        if (adapter.selectMode) toggleSelectMode()
        runCatching {
            startActivity(android.content.Intent(requireContext(), AutoTestActivity::class.java))
        }
    }

    // ------------------------------------------------------------- ping all
    private fun pingAll() {
        // v6.9 — the guards here were the "Ping all does nothing" bug on this tab.
        // v6.8 returned silently when the Auto-Test engine was running OR when
        // `isSweepRunning` was true (which the engine's own progress also makes
        // true), so during the normal state of the app — Auto Test on — the button
        // was a no-op with no feedback whatsoever. A manual request now wins: it is
        // only refused by another MANUAL sweep, and every refusal says something.
        if (busy) return
        if (PingService.isManualSweepRunning) { toast(getString(R.string.testing_ping)); return }
        if (adapter.items.isEmpty()) { toast(getString(R.string.free_empty)); return }

        val started = PingService.pingAll(requireContext(), adapter.items.toList(), PingStore.FREE)
        if (!started) { toast(getString(R.string.testing_ping)); return }

        setBusy(true)
        // v5.9 — pin healthy configs to the top live and keep the user at the top.
        pingAllInFlight = true
        scrollToTop()
        showProgress(0, "Pinging… 0/${adapter.items.size}")
        job = viewLifecycleOwner.lifecycleScope.launch {
            // The app-scoped sweep state is the single source of truth: it flips
            // running=false the moment all probes resolve. We just wait for it.
            // v6.9 — wait on the MANUAL sweep specifically, and note that `pingAll`
            // now publishes running = true synchronously, so this loop can no longer
            // fall through on its first check (the v6.8 race that ended a sweep's UI
            // treatment before it had begun).
            while (isActive && isAdded && PingService.isManualSweepRunning) {
                kotlinx.coroutines.delay(250)
            }
            // v5.7 — sort ONCE at the end of a manual sweep (not on every tick),
            // pinning the pinging configs to the TOP, then SNAP the view back to
            // the top so the user stays exactly where the healthy servers are —
            // never dragged down to the dead ones at the bottom.
            pingAllInFlight = false
            if (isAdded) {
                adapter.sortByPing()
                persistCurrentOrder()
                scrollToTop()
            }
            setBusy(false)
            hideProgressDelayed()
        }
    }

    /** v5.7 — keep the user pinned to the top (where the healthy servers are). */
    private fun scrollToTop() {
        runCatching {
            val rv = view?.findViewById<RecyclerView>(R.id.recycler) ?: return
            rv.post { runCatching { rv.scrollToPosition(0) } }
        }
    }

    private fun persistCurrentOrder() = freeStore.replaceAll(adapter.items)

    // ----------------------------------------------------------- select mode
    private fun toggleSelectMode() {
        if (busy) return
        val enable = !adapter.selectMode
        adapter.selectMode = enable
        if (!enable) adapter.checked.clear()
        selectBar.visibility = if (enable) View.VISIBLE else View.GONE
        btnSelect.text = getString(if (enable) R.string.select_done else R.string.select_mode)
        adapter.notifyDataSetChanged()
    }

    private fun selectAllToggle() {
        val allChecked = adapter.checked.size == adapter.items.size && adapter.items.isNotEmpty()
        adapter.checked.clear()
        if (!allChecked) adapter.items.forEach { adapter.checked.add(it.id) }
        adapter.notifyDataSetChanged()
    }

    private fun copySelected() {
        val sel = adapter.items.filter { it.id in adapter.checked }
        if (sel.isEmpty()) { toast(getString(R.string.free_empty)); return }
        val text = sel.joinToString("\n") { it.rawLink }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("configs", text))
        toast(getString(R.string.copy_one))
    }

    private fun deleteSelected() {
        val ids = adapter.checked.toSet()
        if (ids.isEmpty()) { toast(getString(R.string.free_empty)); return }
        adapter.items.removeAll { it.id in ids }
        rebuildSeenKeys()
        adapter.checked.clear()
        freeStore.replaceAll(adapter.items)
        adapter.notifyDataSetChanged()
        refreshEmpty()
        updateListHeader()
        toast(getString(R.string.deleted_all))
    }

    private fun rebuildSeenKeys() {
        seenKeys.clear()
        adapter.items.forEach { seenKeys.add(ConfigParser.dedupKey(it)) }
    }

    // -------------------------------------------------------- per-row actions
    private fun onRowTap(cfg: ServerConfig) {
        if (adapter.selectMode) {
            adapter.toggleChecked(cfg.id)
        } else {
            saveToMyConfigs(cfg)
        }
    }

    private fun pingOne(cfg: ServerConfig, position: Int) {
        PingService.pingOne(requireContext(), cfg, PingStore.FREE)
    }

    private fun deleteOne(cfg: ServerConfig) {
        adapter.items.removeAll { it.id == cfg.id }
        rebuildSeenKeys()
        adapter.notifyDataSetChanged()
        freeStore.replaceAll(adapter.items)
        refreshEmpty()
        updateListHeader()
    }

    private fun copyOne(cfg: ServerConfig) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("config", cfg.rawLink))
        toast(getString(R.string.copy_one))
    }

    private fun saveToMyConfigs(cfg: ServerConfig) {
        myStore.addServers(listOf(cfg))
        val stored = myStore.getServers().firstOrNull { it.rawLink == cfg.rawLink }
        if (stored != null) myStore.setSelectedId(stored.id)
        adapter.selectedId = cfg.id
        adapter.notifyDataSetChanged()

        // ── v6.9: RAPID SWITCHING FROM THE FREE TAB TOO ──────────────────────
        // «باید بتونم سریع بین کانفیگ ها جابجا بشم». If a tunnel is already up,
        // picking a free config now switches to it immediately rather than only
        // saving it and leaving the user to walk home → disconnect → connect.
        // NeonVpnService treats a start intent during a live session as a config
        // switch, serialised on its single session thread and guarded by a
        // generation counter, so tapping several configs quickly is safe: every
        // superseded request is dropped and no half-open session survives.
        val state = VpnStateBus.state
        val live = state == com.neonvpn.app.service.NeonVpnService.STATE_CONNECTED ||
            state == com.neonvpn.app.service.NeonVpnService.STATE_CONNECTING
        if (!live) {
            toast(getString(R.string.copied_to_my))
            return
        }
        runCatching {
            val ctx = requireContext()
            VpnStateBus.update(
                com.neonvpn.app.service.NeonVpnService.STATE_CONNECTING, cfg.remark
            )
            val intent = android.content.Intent(
                ctx, com.neonvpn.app.service.NeonVpnService::class.java
            )
            val ok = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
                true
            }.getOrDefault(false)
            if (!ok) runCatching { ctx.startService(intent) }
            toast(getString(R.string.switching_to, cfg.remark))
        }.onFailure { toast(getString(R.string.copied_to_my)) }
    }

    private fun deleteAll() {
        if (busy) return
        if (AutoTestEngine.isRunning) AutoTestEngine.stop()
        if (adapter.selectMode) toggleSelectMode()
        freeStore.clear()
        adapter.items = mutableListOf()
        seenKeys.clear()
        PingService.clear(requireContext(), PingStore.FREE)
        adapter.notifyDataSetChanged()
        refreshEmpty()
        updateListHeader()
        btnAutoTest.text = getString(R.string.auto_test)
        toast(getString(R.string.deleted_all))
    }

    // ------------------------------------------------------------- helpers
    private fun setBusy(b: Boolean) {
        busy = b
        btnPingAll.isEnabled = !b
        btnSelect.isEnabled = !b
        btnDeleteAll.isEnabled = !b
        btnPingAll.alpha = if (b) 0.4f else 1f
        btnSelect.alpha = if (b) 0.4f else 1f
        btnDeleteAll.alpha = if (b) 0.4f else 1f
    }

    private fun showProgress(pct: Int, label: String) {
        progressBox.visibility = View.VISIBLE
        progressBar.progress = pct.coerceIn(0, 100)
        progressLabel.text = label
    }

    private fun hideProgressDelayed() {
        progressBox.postDelayed({
            if (isAdded && !busy && !AutoTestEngine.isRunning) progressBox.visibility = View.GONE
        }, 1500)
    }

    private fun refreshEmpty() {
        emptyView.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateListHeader() {
        listHeader.text = "AVAILABLE CONFIGS (${adapter.items.size})"
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
