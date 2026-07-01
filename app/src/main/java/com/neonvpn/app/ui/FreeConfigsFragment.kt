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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        job?.cancel()
        // NOTE: do NOT stop AutoTestEngine here — it is app-scoped and must keep
        // running across tab switches until the user explicitly cancels it.
    }

    /** Render live ping statuses from the app-scoped PingService (§4.4). */
    private fun observePingStatuses() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PingService.statuses.collect { map ->
                    adapter.applyStatuses(map)
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
        // v4.5 — CRASH FIX. The old code reassigned `adapter.items = fresh` and
        // THEN called applyStatuses(), which itself recomputed a DiffUtil pass off
        // the freshly-swapped list. Doing the swap outside a notify cycle while the
        // RecyclerView was mid-layout (very common during Auto-Test churn) left the
        // adapter's item count out of sync with the RecyclerView → the
        // "Inconsistency detected" crash on the 1→2→3 list transition. We now:
        //   • bail if the RecyclerView is currently computing layout / animating
        //     (re-post shortly after), and
        //   • drive the whole list change through applyStatuses' guarded DiffUtil
        //     by seeding the adapter with the fresh rows in the SAME guarded block.
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
            // Seed the adapter with the fresh rows, then let the guarded DiffUtil
            // in applyStatuses reconcile + re-sort in one atomic, crash-proof pass.
            adapter.items = ArrayList(fresh)
            adapter.applyStatuses(PingService.statuses.value)
            refreshEmpty()
            updateListHeader()
        }
    }

    // --------------------------------------------------------------- search
    private fun onSearchClicked() {
        if (AutoTestEngine.isRunning) { toast(getString(R.string.autotest_running)); return }
        if (busy) {
            job?.cancel()
            setBusy(false)
            btnSearchLabel.text = getString(R.string.start_search)
            hideProgressDelayed()
            return
        }
        startSearch()
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
                adapter.items.addAll(batch.configs)
                freeStore.replaceAll(adapter.items)
                adapter.notifyDataSetChanged()
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
    /** v4.0 — toggle the continuous Auto-Test engine (START ⇄ CANCEL). */
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
        // v4.6 — open the fast connectivity-probe page. It finds the first working
        // vless+vmess, saves them, then starts the continuous engine itself. The
        // engine banner + the CANCEL state below reflect it once it's running.
        runCatching {
            startActivity(android.content.Intent(requireContext(), AutoTestActivity::class.java))
        }
    }

    // ------------------------------------------------------------- ping all
    private fun pingAll() {
        if (busy || AutoTestEngine.isRunning) return
        if (adapter.items.isEmpty()) { toast(getString(R.string.free_empty)); return }

        val started = PingService.pingAll(requireContext(), adapter.items.toList(), PingStore.FREE)
        if (!started) { toast(getString(R.string.testing_ping)); return }

        setBusy(true)
        val total = adapter.items.size
        showProgress(0, getString(R.string.testing_ping))
        job = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && isAdded) {
                val map = PingService.statuses.value
                val done = adapter.items.count {
                    val s = map[it.id]
                    s != null && s != PingService.PingStatus.Testing
                }
                showProgress(done * 100 / total.coerceAtLeast(1), "Tested ${done.coerceAtMost(total)}/$total")
                val anyTesting = adapter.items.any { map[it.id] == PingService.PingStatus.Testing }
                if (!anyTesting && done >= total) break
                kotlinx.coroutines.delay(250)
            }
            persistCurrentOrder()
            setBusy(false)
            hideProgressDelayed()
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
        toast(getString(R.string.copied_to_my))
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
