package com.neonvpn.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.neonvpn.app.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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

        view.findViewById<View>(R.id.btn_paste).setOnClickListener { pasteFromClipboard() }
        btnSelectMode.setOnClickListener { toggleSelectMode() }
        btnSelectAll.setOnClickListener { selectAll() }
        btnCopySel.setOnClickListener { copySelected() }
        btnDeleteSel.setOnClickListener { deleteSelected() }
        btnPingAll.setOnClickListener { pingAll() }

        refresh()
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
                    adapter.applyStatuses(map)
                }
            }
        }
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

        // Opt-in history: record this paste (only vless/vmess substrings) and pull
        // previously-stored links in as a fallback merge source.
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

    private fun selectServer(cfg: ServerConfig) {
        if (adapter.selectMode) {
            adapter.toggleChecked(cfg.id)
            updateActionLabels()
            return
        }
        store.setSelectedId(cfg.id)
        adapter.selectedId = cfg.id
        adapter.notifyDataSetChanged()
        toast(getString(R.string.selected, cfg.remark))
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
        val started = PingService.pingAll(requireContext(), adapter.items.toList(), PingStore.MY)
        if (started) toast(getString(R.string.testing_ping))
    }

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
        adapter.items = store.getServers()
        adapter.selectedId = store.getSelectedId()
        adapter.notifyDataSetChanged()
        // Keep the fastest-first ordering after a list reload.
        adapter.applyStatuses(PingService.statuses.value)
        emptyView.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
        updateActionLabels()
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

    // v3.8 §4.4 — live ping statuses pushed from PingService.statuses.
    var statuses: Map<String, PingStatus> = emptyMap()
        private set

    /**
     * Re-render with the latest ping statuses and re-sort fastest-first via a
     * DiffUtil pass (stable ids → only changed/moved rows animate). Selection
     * order in select-mode is left untouched to avoid surprising the user.
     *
     * v4.5 — CRASH-PROOF. The Auto-Test engine and the manual ping flow both
     * push status maps onto the main thread, and the engine ALSO reloads the
     * whole list (`items = fresh`) between batches. If a DiffUtil pass starts
     * reading `oldItems` and then a second writer swaps `items` mid-flight, the
     * RecyclerView's internal bookkeeping desyncs → the infamous
     * "Inconsistency detected. Invalid view holder adapter position" crash that
     * fired exactly when Auto-Test moved from list 1 → 2 → 3. We now:
     *   • take an IMMUTABLE snapshot of the old list up-front,
     *   • compute the new sorted list off that snapshot,
     *   • swap `items` BEFORE dispatching the diff (so the adapter and the diff
     *     agree on the new contents), and
     *   • wrap the whole thing in runCatching + a full notifyDataSetChanged
     *     fallback so even a genuinely racy state can never crash the process.
     */
    fun applyStatuses(map: Map<String, PingStatus>) {
        val old = statuses
        statuses = map
        if (selectMode) { runCatching { notifyDataSetChanged() }; return }
        runCatching {
            // Immutable snapshot of the current rows for a consistent diff.
            val oldItems: List<ServerConfig> = ArrayList(items)
            val sorted = oldItems.sortedBy { PingService.sortKey(map[it.id] ?: PingStatus.Idle) }
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(
                object : androidx.recyclerview.widget.DiffUtil.Callback() {
                    override fun getOldListSize() = oldItems.size
                    override fun getNewListSize() = sorted.size
                    override fun areItemsTheSame(o: Int, n: Int) = oldItems[o].id == sorted[n].id
                    override fun areContentsTheSame(o: Int, n: Int): Boolean {
                        val id = sorted[n].id
                        // Same row only "unchanged" if its status didn't change.
                        return oldItems[o].id == id && old[id] == map[id]
                    }
                }
            )
            items = sorted.toMutableList()
            diff.dispatchUpdatesTo(this)
        }.onFailure {
            // Last-ditch: drop animations, just resync the whole list. Never throw.
            runCatching {
                items = items.sortedBy { PingService.sortKey(map[it.id] ?: PingStatus.Idle) }
                    .toMutableList()
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
        holder.name.text = cfg.remark
        holder.badge.text = cfg.protocol.uppercase()

        // ping result text (never reveal host/IP to protect the config)
        val status = statuses[cfg.id] ?: PingStatus.Idle
        holder.detail.text = when (status) {
            PingStatus.Idle -> "tap PING to test"
            PingStatus.Testing -> "testing…"
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
        holder.ping.setOnClickListener { onPing(cfg, holder.bindingAdapterPosition) }
    }
}
