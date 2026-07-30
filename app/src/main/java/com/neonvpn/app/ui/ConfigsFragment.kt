package com.neonvpn.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.neonvpn.app.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.neonvpn.app.config.AutoTestEngine
import com.neonvpn.app.config.ConfigParser
import com.neonvpn.app.config.ConfigStore
import com.neonvpn.app.config.PasteHistoryStore
import com.neonvpn.app.config.PingService
import com.neonvpn.app.config.PingService.PingStatus
import com.neonvpn.app.config.PingStore
import com.neonvpn.app.config.ServerConfig
import com.neonvpn.app.config.XrayConfigBuilder
import com.neonvpn.app.service.XrayManager
import com.neonvpn.app.util.AppPrefs
import com.neonvpn.app.util.Format
import kotlinx.coroutines.launch

class ConfigsFragment : Fragment() {

    private lateinit var store: ConfigStore
    private lateinit var adapter: ServerAdapter
    private lateinit var emptyView: View

    private lateinit var btnSelectMode: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnCopySel: TextView
    private lateinit var btnDeleteSel: TextView
    private lateinit var btnPingAll: TextView

    /** v6.2 — top sweep progress bar (Pinging… x/y). Shown only during a sweep. */
    private lateinit var sweepBox: View
    private lateinit var sweepBar: ProgressBar
    private lateinit var sweepLabel: TextView

    /** v6.3 — CANCEL button shown above the sweep bar while pinging all. */
    private var btnCancelPing: TextView? = null

    /** v5.9 — true while a manual PING ALL sweep is running (pins healthy top). */
    @Volatile private var pingAllInFlight = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_configs, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = ConfigStore(requireContext())
        emptyView = view.findViewById(R.id.empty_view)
        btnSelectMode = view.findViewById(R.id.btn_select_mode)
        btnSelectAll = view.findViewById(R.id.btn_select_all)
        btnCopySel = view.findViewById(R.id.btn_copy_sel)
        btnDeleteSel = view.findViewById(R.id.btn_delete_sel)
        btnPingAll = view.findViewById(R.id.btn_ping_all)
        sweepBox = view.findViewById(R.id.sweep_box)
        sweepBar = view.findViewById(R.id.sweep_progress)
        sweepLabel = view.findViewById(R.id.sweep_label)
        btnCancelPing = view.findViewById(R.id.btn_cancel_ping)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ServerAdapter(
            items = store.getServers(),
            selectedId = store.getSelectedId(),
            onSelect = { cfg -> selectServer(cfg) },
            onDelete = { cfg -> deleteServer(cfg) },
            onCopy = { cfg -> copyOne(cfg) },
            onPing = { cfg, pos -> pingOne(cfg, pos) }
        )
        recycler.adapter = adapter

        // v3.8 §4.4 — hydrate the APP-SCOPED PingService from disk, then observe
        // its single StateFlow so results stay live across tab switches.
        PingService.hydrate(requireContext(), PingStore.MY)
        observePingStatuses()
        observeSweep()
        // v5.9 — reflect the continuous Auto-Test engine LIVE in My Configs so the
        // user watches working configs accumulate here (throttled), while staying
        // pinned to the top where the healthy servers are.
        observeAutoTest()

        view.findViewById<View>(R.id.btn_paste).setOnClickListener { pasteFromClipboard() }
        btnSelectMode.setOnClickListener { toggleSelectMode() }
        btnSelectAll.setOnClickListener { selectAll() }
        btnCopySel.setOnClickListener { copySelected() }
        btnDeleteSel.setOnClickListener { deleteSelected() }
        btnPingAll.setOnClickListener { pingAll() }
        // v6.3 — stop the running Ping-All cleanly. PingService.cancel() is
        // co-operative: in-flight probes finish/abort on their own and the
        // already-measured results stay in the list, so nothing is disrupted.
        btnCancelPing?.setOnClickListener { cancelPingAll() }

        refresh()
    }

    /**
     * v6.2 — OBSERVE the app-scoped sweep state. While a PING ALL sweep is
     * running we SHOW the top progress bar and DISABLE the PING ALL + SELECT +
     * per-row PING controls so rapid taps can't stack sweeps (the reported
     * "ping keeps stacking and the app freezes" bug). The moment the sweep
     * completes the bar hides and the buttons re-enable. This is the single
     * source of truth that pins the buttons' enabled state, so re-subscribing
     * after a tab switch reads the live truth (a sweep running on another tab
     * keeps the buttons disabled here too).
     */
    private fun observeSweep() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PingService.sweep.collect { s ->
                    if (!isAdded) return@collect
                    if (s.running) {
                        // ── v6.9: THE PING PROGRESS BAR ──────────────────────
                        // The brief: «در صفحه My Configs نوار پیشرفت پینگ وجود
                        // نداره». It existed in the layout but only a MANUAL sweep
                        // ever published into this flow, so during the automatic
                        // run — when the user is actually waiting on pings — the
                        // bar never appeared. AutoTestEngine now publishes here too
                        // (PingService.publishExternalSweep), so this block renders
                        // both kinds of run with a real, climbing count.
                        sweepBox.visibility = View.VISIBLE
                        sweepBar.max = s.total.coerceAtLeast(1)
                        sweepBar.progress = s.tested
                        val pct = if (s.total > 0) s.tested * 100 / s.total else 0
                        sweepLabel.text = "Pinging… ${s.tested}/${s.total}  ·  $pct%"
                        // Only a MANUAL sweep may lock the controls. A background
                        // Auto Test must leave PING ALL usable, otherwise the user
                        // can never re-ping while Auto Test is on.
                        val manual = PingService.isManualSweepRunning
                        btnPingAll.isEnabled = !manual
                        btnPingAll.alpha = if (manual) 0.4f else 1f
                        btnSelectMode.isEnabled = !manual
                        btnSelectMode.alpha = if (manual) 0.4f else 1f
                        adapter.pingButtonsEnabled = !manual
                        // The Cancel button is live for the whole sweep.
                        btnCancelPing?.isEnabled = true
                        btnCancelPing?.alpha = 1f
                    } else {
                        sweepBox.visibility = View.GONE
                        btnPingAll.isEnabled = true
                        btnPingAll.alpha = 1f
                        btnSelectMode.isEnabled = true
                        btnSelectMode.alpha = 1f
                        adapter.pingButtonsEnabled = true
                        pingAllInFlight = false
                    }
                }
            }
        }
    }

    /**
     * v6.3 — stop the running PING ALL sweep.
     *
     * [PingService.cancel] is co-operative: the sweep coroutine notices the
     * cancellation between probes, publishes `running = false`, and leaves every
     * result it already measured intact. That means pressing Cancel is safe at
     * any moment — no half-written store, no stuck spinners, and the buttons
     * re-enable through the normal [observeSweep] path.
     */
    private fun cancelPingAll() {
        btnCancelPing?.isEnabled = false
        btnCancelPing?.alpha = 0.5f
        runCatching { PingService.cancel() }
        pingAllInFlight = false
        if (isAdded) {
            android.widget.Toast.makeText(
                requireContext(), R.string.ping_cancelled, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Subscribe to [PingService.statuses] for the lifetime of the view. The
     * sweep itself runs on the app scope, so flipping tabs never cancels it —
     * we just re-render whatever the flow currently holds.
     */
    private fun observePingStatuses() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PingService.statuses.collect { map ->
                    // v6.0 — while a manual PING ALL is in flight, RE-SORT the list
                    // the INSTANT each ping lands so the configs with the LOWEST
                    // ping are pinned to the top immediately (the v6 brief: "the
                    // moment a config gives a low ping it is pinned to the top").
                    // We always re-sort during the sweep; we only auto-scroll the
                    // user back to the top when they were ALREADY at the top, so a
                    // reorder never yanks a user who has scrolled down themselves.
                    if (pingAllInFlight) {
                        val atTop = isAtTop()
                        adapter.submitList(store.getServers(), map)
                        if (atTop) scrollToTop()
                    } else {
                        adapter.applyStatuses(map)
                    }
                }
            }
        }
    }

    /** Last time we reloaded My Configs from the store during Auto Test. */
    @Volatile private var lastAutoReloadAt = 0L

    /**
     * v5.9 — mirror the continuous Auto-Test engine into My Configs LIVE. The
     * engine flushes newly-working configs into the store; we reload them here
     * (throttled) so the user sees them appear, keep the healthy ones pinned to
     * the top, and keep the user parked at the top.
     */
    private fun observeAutoTest() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AutoTestEngine.progress.collect { p ->
                    if (!isAdded || !p.running) return@collect
                    val now = System.currentTimeMillis()
                    if (now - lastAutoReloadAt < 700L) return@collect
                    lastAutoReloadAt = now
                    val atTop = isAtTop()
                    runCatching {
                        adapter.selectedId = store.getSelectedId()
                        adapter.submitList(store.getServers(), PingService.statuses.value)
                        emptyView.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
                    }
                    // Keep the user at the top (where the healthy configs are) as
                    // the engine appends new ones, so they never get flung to the
                    // bottom while more configs are being added.
                    if (atTop) scrollToTop()
                }
            }
        }
    }

    /** True if the list is scrolled to (or very near) the top. */
    private fun isAtTop(): Boolean {
        val rv = view?.findViewById<RecyclerView>(R.id.recycler) ?: return true
        val lm = rv.layoutManager as? LinearLayoutManager ?: return true
        val first = lm.findFirstVisibleItemPosition()
        return first <= 1
    }

    // -------------------------------------------------------- paste / select
    /**
     * v3.8 SMART CLIPBOARD PASTE.
     *
     * Reads the primary clip and iterates EVERY [ClipData.Item] (not just item 0),
     * coercing each to text, so configs spread across a multi-item clip are all
     * captured. The merged text is parsed by [ConfigParser.parseMany] (vless/vmess
     * only — rule 4), de-duplicated against My Configs, and — when the opt-in paste
     * history is enabled — merged with previously-pasted links as a fallback source.
     */
    private fun pasteFromClipboard() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            toast(getString(R.string.clipboard_empty)); return
        }

        // Concatenate EVERY clip item's text (multi-item clips were missed before).
        val clipText = buildString {
            for (i in 0 until clip.itemCount) {
                val t = clip.getItemAt(i).coerceToText(requireContext())?.toString().orEmpty()
                if (t.isNotBlank()) { append(t); append('\n') }
            }
        }
        if (clipText.isBlank()) { toast(getString(R.string.clipboard_empty)); return }

        // v4.8 — SCAN THE FULL CLIPBOARD HISTORY. Android does not let an app read
        // the OS-level clipboard history for privacy reasons, so the app maintains
        // its own on-device history of every vless/vmess link it has ever pasted.
        // On each paste we (1) record the new clip's links, then (2) merge the
        // ENTIRE accumulated history with the current clip and parse them all — so
        // if the user copied 100 configs over time, every vless/vmess among them is
        // detected and added. Enabled by default (see AppPrefs.isPasteHistoryEnabled).
        val history = PasteHistoryStore(requireContext())
        val mergedText = if (AppPrefs.isPasteHistoryEnabled(requireContext())) {
            history.append(clipText)
            (clipText + "\n" + history.links().joinToString("\n"))
        } else {
            clipText
        }

        val parsed = ConfigParser.parseMany(mergedText)
        if (parsed.isEmpty()) { toast(getString(R.string.no_valid_config)); return }
        val added = store.addServers(parsed)
        if (store.getSelectedId() == null) {
            store.getServers().firstOrNull()?.let { store.setSelectedId(it.id) }
        }

        // Y = count of OTHER (unsupported) scheme tokens in the merged text, minus
        // the imported count. Uses a generic scheme-token regex over the input.
        val ignored = countIgnored(mergedText, added)
        toast(getString(R.string.imported_ignored, added, ignored))
        refresh()
    }

    /**
     * Count unsupported configs the user pasted. We count every `scheme://` token
     * in the merged text via a scheme-token regex, then subtract the number we
     * actually imported. Clamped to >= 0.
     */
    private fun countIgnored(text: String, imported: Int): Int {
        val tokens = Regex("""\b([a-z][a-z0-9+.\-]*)://""")
            .findAll(text.lowercase())
            .count { it.groupValues[1] != "http" && it.groupValues[1] != "https" }
        return (tokens - imported).coerceAtLeast(0)
    }

    /**
     * v6.9 — TAP A CONFIG = SWITCH TO IT, INSTANTLY.
     *
     * THE BUG THIS FIXES: «باید بتونم سریع بین کانفیگ ها جابجا بشم» — the user must
     * be able to move between configs rapidly. v6.8 only wrote the new selection to
     * the store: to actually change server the user had to go back to the home tab,
     * tap Disconnect, wait for the teardown, and tap Connect again. Three gestures
     * and a wait for something that should be one tap, which is why switching felt
     * impossible rather than merely slow.
     *
     * [com.neonvpn.app.service.NeonVpnService] has been able to handle this since
     * v6.5: a start intent delivered while a tunnel is up is treated as a CONFIG
     * SWITCH, serialised on its single session thread and tagged with a generation
     * counter, so the old session is torn down and the new one raised without the
     * two ever interleaving. Superseded requests are dropped by generation, so
     * hammering several configs in a row is safe — only the last one survives, and
     * no half-open session is left behind. The capability was simply never wired to
     * this tap.
     *
     * When nothing is connected we behave exactly as before (select only), so
     * tapping a row never starts a tunnel the user did not ask for.
     */
    private fun selectServer(cfg: ServerConfig) {
        if (adapter.selectMode) {
            adapter.toggleChecked(cfg.id)
            updateActionLabels()
            return
        }
        store.setSelectedId(cfg.id)
        adapter.selectedId = cfg.id
        adapter.notifyDataSetChanged()

        val state = VpnStateBus.state
        val live = state == com.neonvpn.app.service.NeonVpnService.STATE_CONNECTED ||
            state == com.neonvpn.app.service.NeonVpnService.STATE_CONNECTING
        if (!live) {
            toast(getString(R.string.selected, cfg.remark))
            return
        }

        // Already connected → hand the switch straight to the service.
        runCatching {
            val ctx = requireContext()
            VpnStateBus.update(
                com.neonvpn.app.service.NeonVpnService.STATE_CONNECTING, cfg.remark
            )
            val intent = android.content.Intent(ctx, com.neonvpn.app.service.NeonVpnService::class.java)
            // Deliver defensively in both directions: startForegroundService is
            // required on O+ when the service is down but throws if the process is
            // considered background at that instant, while startService works when
            // the service is already up (which is exactly the switch case). Trying
            // one and falling back to the other means a switch tap is never lost.
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
        }.onFailure {
            toast(getString(R.string.selected, cfg.remark))
        }
    }

    private fun deleteServer(cfg: ServerConfig) {
        store.removeServer(cfg.id)
        refresh()
    }

    // -------------------------------------------------------- select mode
    private fun toggleSelectMode() {
        adapter.selectMode = !adapter.selectMode
        adapter.checked.clear()
        val on = adapter.selectMode
        btnSelectMode.text = if (on) getString(R.string.cancel) else getString(R.string.select_mode)
        btnSelectAll.visibility = if (on) View.VISIBLE else View.GONE
        btnCopySel.visibility = if (on) View.VISIBLE else View.GONE
        btnDeleteSel.visibility = if (on) View.VISIBLE else View.GONE
        btnPingAll.visibility = if (on) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
        updateActionLabels()
    }

    private fun selectAll() {
        val all = adapter.items.map { it.id }.toMutableSet()
        if (adapter.checked.size == all.size) adapter.checked.clear()
        else adapter.checked.addAll(all)
        adapter.notifyDataSetChanged()
        updateActionLabels()
    }

    private fun updateActionLabels() {
        val n = adapter.checked.size
        btnCopySel.text = if (n > 0) "${getString(R.string.copy_selected)} $n" else getString(R.string.copy_selected)
        btnDeleteSel.text = if (n > 0) "${getString(R.string.delete_selected)} $n" else getString(R.string.delete_selected)
    }

    private fun copySelected() {
        val sel = adapter.items.filter { it.id in adapter.checked }
        if (sel.isEmpty()) { toast(getString(R.string.nothing_selected)); return }
        copyToClipboard(sel.joinToString("\n") { it.rawLink })
        toast(getString(R.string.copied_n, sel.size))
    }

    private fun deleteSelected() {
        if (adapter.checked.isEmpty()) { toast(getString(R.string.nothing_selected)); return }
        val n = store.removeServers(adapter.checked.toSet())
        adapter.checked.clear()
        toast(getString(R.string.deleted_n, n))
        refresh()
    }

    // -------------------------------------------------------- copy single
    private fun copyOne(cfg: ServerConfig) {
        copyToClipboard(cfg.rawLink)
        toast(getString(R.string.copy_one))
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("configs", text))
    }

    // -------------------------------------------------------- ping (§4.4)
    // Both handlers just delegate to the app-scoped PingService; the StateFlow
    // observer renders the result, so a tab switch can't lose an in-flight run.
    private fun pingOne(cfg: ServerConfig, position: Int) {
        PingService.pingOne(requireContext(), cfg, PingStore.MY)
    }

    private fun pingAll() {
        if (adapter.items.isEmpty()) return
        // v6.9 — guard on the MANUAL sweep only. v6.8 used `isSweepRunning`, which
        // is also true while the background Auto Test is pinging, so tapping PING
        // ALL during an Auto Test returned here silently and the user saw nothing
        // happen at all — the reported «Ping all یهو میپره و هیچی پینگ نمیگیره».
        if (PingService.isManualSweepRunning) return
        val started = PingService.pingAll(requireContext(), adapter.items.toList(), PingStore.MY)
        if (!started) {
            // Should be unreachable (we just checked), but never leave the tap
            // unexplained — a silent no-op is what made this feel broken.
            toast(getString(R.string.testing_ping))
            return
        }
        if (started) {
            toast(getString(R.string.testing_ping))
            // v5.9 — pin the healthy configs to the top the INSTANT each ping
            // lands (see observePingStatuses), keeping the user parked at the top.
            pingAllInFlight = true
            // Snap to top immediately so the user starts watching the configs that
            // will pin as they succeed.
            scrollToTop()
            viewLifecycleOwner.lifecycleScope.launch {
                // Wait until the app-scoped sweep reports it is DONE. The sweep
                // state is the single source of truth (it flips running=false the
                // instant all probes resolve), so this loop is exact, not a guess.
                // v6.9 — `pingAll` publishes running = true synchronously before it
                // returns, so this loop can no longer exit on its very first check
                // (the v6.8 race that made a sweep look like it never happened).
                while (isResumedSafe() && PingService.isManualSweepRunning) {
                    kotlinx.coroutines.delay(250)
                }
                pingAllInFlight = false
                if (::adapter.isInitialized && isAdded) {
                    // Final authoritative sort: healthy (lowest ping) at the top,
                    // dead / non-pinging at the bottom, and persist that order.
                    adapter.sortByPing()
                    store.reorder(adapter.items.map { it.id })
                    // Keep the user with the working servers at the top.
                    scrollToTop()
                }
            }
        }
    }

    /** v5.7 — keep the user pinned to the top (where the healthy servers are). */
    private fun scrollToTop() {
        runCatching {
            val rv = view?.findViewById<RecyclerView>(R.id.recycler) ?: return
            rv.post { runCatching { rv.scrollToPosition(0) } }
        }
    }

    private fun isResumedSafe(): Boolean = isAdded && view != null

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // When this tab becomes visible again (e.g. user saved a config from the
        // Free tab), reload the list and re-render the latest live ping statuses.
        if (!hidden && ::adapter.isInitialized) {
            adapter.applyStatuses(PingService.statuses.value)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && !isHidden) refresh()
    }

    private fun refresh() {
        // v4.7 — atomic submitList (same crash fix as the Free tab): the diff is
        // computed against the rows actually displayed, so a live Auto-Test flush
        // into My Configs while this tab is visible can never desync the
        // RecyclerView ("Inconsistency detected" crash).
        runCatching {
            adapter.selectedId = store.getSelectedId()
            adapter.submitList(store.getServers(), PingService.statuses.value)
            emptyView.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
            updateActionLabels()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}

// ----------------------------------------------------------- RecyclerView
class ServerAdapter(
    var items: MutableList<ServerConfig>,
    var selectedId: String?,
    val onSelect: (ServerConfig) -> Unit,
    val onDelete: (ServerConfig) -> Unit,
    val onCopy: (ServerConfig) -> Unit,
    val onPing: (ServerConfig, Int) -> Unit
) : RecyclerView.Adapter<ServerAdapter.VH>() {

    var selectMode: Boolean = false
    val checked = mutableSetOf<String>()

    // v6.2 — when false (a PING ALL sweep is running on this tab), the per-row
    // PING icon buttons are disabled so a spam-tap can't stack single probes on
    // top of the running sweep.
    var pingButtonsEnabled: Boolean = true
        set(value) {
            field = value
            runCatching { notifyDataSetChanged() }
        }

    // v3.8 §4.4 — live ping statuses pushed from PingService.statuses.
    var statuses: Map<String, PingStatus> = emptyMap()
        private set

    /**
     * v5.6 — STABLE ORDER. Live ping status updates now refresh row content
     * IN PLACE and NEVER re-sort/re-order the list. This fixes the long-standing
     * complaint that pinging a config (or the auto-ping / auto-test churn) yanked
     * the user's position around — "I ping the top item and it jumps to the
     * bottom, or the scroll flies to the end every few seconds". The list order
     * is now controlled ONLY by an explicit user action (Sort button /
     * pingAll completion / list reload), not by every status tick.
     *
     * The persisted ping value (Reachable/Unstable ms) is the single source of
     * truth for the little coloured badge, so the badge survives tab switches,
     * app restarts and screen off/on exactly as before — it just no longer moves
     * the row.
     *
     * CRASH-PROOF: we update only the rows whose status actually changed via
     * notifyItemChanged (stable ids), never a structural reorder, so the
     * "Inconsistency detected" class of crashes cannot happen from a status tick.
     */
    fun applyStatuses(map: Map<String, PingStatus>) {
        val old = statuses
        statuses = map
        runCatching {
            // Refresh only the rows whose status text/color actually changed.
            // Keyed by CONTENT (pingKey) so a re-parsed copy of the same config
            // still shows its persisted ping.
            for (i in items.indices) {
                val k = ConfigParser.pingKey(items[i])
                if (old[k] != map[k]) notifyItemChanged(i)
            }
        }.onFailure {
            runCatching { notifyDataSetChanged() }
        }
    }

    /**
     * Explicit, user-triggered re-sort (fastest-first). Called after a manual
     * PING ALL finishes and on a full list reload — NOT on every live tick.
     */
    fun sortByPing() {
        submitList(ArrayList(items), statuses)
    }

    /**
     * v5.7 — APPEND new rows to the END of the list WITHOUT re-sorting and
     * WITHOUT touching the existing rows' positions. This keeps the user's scroll
     * position stable when they press "START SEARCH": fresh, untested configs
     * simply appear at the bottom, and any previously-pinged rows already pinned
     * at the top stay put. Uses a targeted range insert so the RecyclerView does
     * not scroll or rebind unrelated rows.
     */
    fun appendItems(newOnes: List<ServerConfig>) {
        if (newOnes.isEmpty()) return
        runCatching {
            val start = items.size
            items.addAll(newOnes)
            notifyItemRangeInserted(start, newOnes.size)
        }.onFailure {
            runCatching { notifyDataSetChanged() }
        }
    }

    /**
     * v4.7 — SINGLE crash-proof entry point for EVERY list change (status
     * updates AND full reloads from the store).
     *
     * The v4.5/v4.6 crash on the "next 240 batch" transition happened because
     * reloadFromStore() swapped `adapter.items` to the fresh list FIRST and only
     * THEN ran applyStatuses(): the DiffUtil "old list" was the fresh list, but
     * the RecyclerView was still displaying the PREVIOUS list with a different
     * row count → dispatched diff deltas didn't match the displayed state →
     * "Inconsistency detected. Invalid view holder adapter position" crash.
     *
     * submitList() fixes it structurally: the diff's old list is ALWAYS the list
     * the adapter is actually displaying right now, and `items` is only swapped
     * together with dispatching that consistent diff. A full fallback to
     * notifyDataSetChanged() guarantees no throwable can ever escape.
     */
    fun submitList(newItems: List<ServerConfig>, map: Map<String, PingStatus> = statuses) {
        val old = statuses
        statuses = map
        if (selectMode) {
            runCatching {
                items = newItems.toMutableList()
                notifyDataSetChanged()
            }
            return
        }
        runCatching {
            // Old list == what the RecyclerView is displaying RIGHT NOW.
            val oldItems: List<ServerConfig> = ArrayList(items)
            val sorted = newItems.sortedBy {
                PingService.sortKey(map[ConfigParser.pingKey(it)] ?: PingStatus.Idle)
            }
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(
                object : androidx.recyclerview.widget.DiffUtil.Callback() {
                    override fun getOldListSize() = oldItems.size
                    override fun getNewListSize() = sorted.size
                    override fun areItemsTheSame(o: Int, n: Int) = oldItems[o].id == sorted[n].id
                    override fun areContentsTheSame(o: Int, n: Int): Boolean {
                        if (oldItems[o].id != sorted[n].id) return false
                        val k = ConfigParser.pingKey(sorted[n])
                        // Same row only "unchanged" if its status didn't change.
                        return old[k] == map[k]
                    }
                }
            )
            items = sorted.toMutableList()
            diff.dispatchUpdatesTo(this)
        }.onFailure {
            // Last-ditch: drop animations, just resync the whole list. Never throw.
            runCatching {
                items = newItems.sortedBy {
                    PingService.sortKey(map[ConfigParser.pingKey(it)] ?: PingStatus.Idle)
                }.toMutableList()
                notifyDataSetChanged()
            }
        }
    }

    fun toggleChecked(id: String) {
        if (id in checked) checked.remove(id) else checked.add(id)
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) notifyItemChanged(idx)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: View = v.findViewById(R.id.card)
        val check: View = v.findViewById(R.id.check)
        val name: TextView = v.findViewById(R.id.name)
        val detail: TextView = v.findViewById(R.id.detail)
        val badge: TextView = v.findViewById(R.id.badge)
        val delete: ImageView = v.findViewById(R.id.delete)
        val copy: ImageView = v.findViewById(R.id.copy)
        val ping: ImageView = v.findViewById(R.id.ping)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cfg = items[position]
        // v6.4 — public feeds often prefix a remark with the Islamic-Republic
        // flag emoji, which this app must NEVER display. sanitizeForDisplay
        // replaces that one glyph with the plain "[IR]" tag and leaves every
        // other country's flag untouched.
        holder.name.text = com.neonvpn.app.util.CountryFlags.sanitizeForDisplay(cfg.remark)
        holder.badge.text = cfg.protocol.uppercase()

        // ping result text (never reveal host/IP to protect the config)
        // v6.2 — the per-row detail uses ENGLISH status text ("Pinging…" while a
        // probe is in flight, the measured ms once it lands, "unreachable" if it
        // fails) so it matches the brief exactly. The old ping is cleared the
        // instant a new ping starts (see PingService), so a fresh ping always
        // replaces the stale value instead of sitting on top of it.
        val status = statuses[ConfigParser.pingKey(cfg)] ?: PingStatus.Idle
        holder.detail.text = when (status) {
            PingStatus.Idle -> "tap PING to test"
            PingStatus.Testing -> "Pinging…"
            PingStatus.Unreachable -> "✕ unreachable"
            is PingStatus.Unstable -> "● ${Format.ping(status.ms)} (unstable)"
            is PingStatus.Reachable -> "● ${Format.ping(status.ms)}"
        }
        holder.detail.setTextColor(PingService.colorOf(status))

        val isChecked = cfg.id in checked
        if (selectMode) {
            holder.check.visibility = View.VISIBLE
            holder.check.isActivated = isChecked
            holder.delete.visibility = View.GONE
            holder.copy.visibility = View.GONE
            holder.ping.visibility = View.GONE
            holder.card.isActivated = isChecked
        } else {
            holder.check.visibility = View.GONE
            holder.delete.visibility = View.VISIBLE
            holder.copy.visibility = View.VISIBLE
            holder.ping.visibility = View.VISIBLE
            holder.card.isActivated = (cfg.id == selectedId)
        }

        holder.card.setOnClickListener { onSelect(cfg) }
        holder.delete.setOnClickListener { onDelete(cfg) }
        holder.copy.setOnClickListener { onCopy(cfg) }
        // v6.2 — disable the per-row PING icon while a sweep is running so taps
        // can't stack single probes on top of the in-flight PING ALL.
        holder.ping.isEnabled = pingButtonsEnabled
        holder.ping.alpha = if (pingButtonsEnabled) 1f else 0.4f
        holder.ping.setOnClickListener {
            if (pingButtonsEnabled) onPing(cfg, holder.bindingAdapterPosition)
        }
    }
}
