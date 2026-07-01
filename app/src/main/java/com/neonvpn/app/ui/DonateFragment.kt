package com.neonvpn.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.neonvpn.app.R
import com.neonvpn.app.config.RemoteConfig
import com.neonvpn.app.config.RemoteConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * v4.6 — DONATE / SUPPORT tab (replaces the old Sponsor tab entirely).
 *
 * Renders the admin-controlled [RemoteConfig.donate] list. Each row is a crypto
 * donation option: [coin logo] · [coin + address] · [copy]. Copy places the
 * EXACT address on the clipboard (no extra whitespace / characters).
 *
 * Fully panel-driven: whatever the operator enables in the panel appears here.
 */
class DonateFragment : Fragment() {

    private lateinit var heading: TextView
    private lateinit var note: TextView
    private lateinit var emptyView: View
    private lateinit var adapter: DonateAdapter

    private val listener: (RemoteConfig) -> Unit = { cfg ->
        activity?.runOnUiThread { if (isAdded) bind(cfg) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_donate, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        heading = view.findViewById(R.id.donate_heading)
        note = view.findViewById(R.id.donate_note)
        emptyView = view.findViewById(R.id.empty_view)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = DonateAdapter(mutableListOf()) { item -> copyAddress(item) }
        recycler.adapter = adapter

        RemoteConfigStore.addListener(listener)   // fires immediately with current
    }

    override fun onDestroyView() {
        super.onDestroyView()
        RemoteConfigStore.removeListener(listener)
    }

    private fun bind(cfg: RemoteConfig) {
        val d = cfg.donate
        if (d.heading.isNotBlank()) heading.text = d.heading
        if (d.note.isNotBlank()) note.text = d.note
        val items = if (d.enabled) d.items else emptyList()
        adapter.items = items.toMutableList()
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Copy the address VERBATIM (trimmed of any accidental surrounding space). */
    private fun copyAddress(item: RemoteConfig.DonateItem) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clean = item.address.trim()
        cm.setPrimaryClip(ClipData.newPlainText(item.coin, clean))
        Toast.makeText(requireContext(), R.string.donate_copied, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ adapter
    class DonateAdapter(
        var items: MutableList<RemoteConfig.DonateItem>,
        val onCopy: (RemoteConfig.DonateItem) -> Unit
    ) : RecyclerView.Adapter<DonateAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.coin_logo)
            val name: TextView = v.findViewById(R.id.coin_name)
            val address: TextView = v.findViewById(R.id.coin_address)
            val copy: View = v.findViewById(R.id.btn_copy)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_donate, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.coin
            holder.address.text = item.address
            holder.copy.setOnClickListener { onCopy(item) }
            bindLogo(holder.logo, item)
        }

        private fun builtInIcon(network: String): Int = when (network.lowercase()) {
            "usdt", "trc20", "erc20" -> R.drawable.ic_coin_usdt
            "trx", "tron" -> R.drawable.ic_coin_trx
            "ton" -> R.drawable.ic_coin_ton
            "btc", "bitcoin" -> R.drawable.ic_coin_btc
            else -> R.drawable.ic_coin_usdt
        }

        private fun bindLogo(iv: ImageView, item: RemoteConfig.DonateItem) {
            // built-in first (instant), then override with the remote logo if any.
            iv.setImageResource(builtInIcon(item.network))
            val url = item.logoUrl.trim()
            if (url.isBlank()) return
            CoroutineScope(Dispatchers.IO).launch {
                val bmp = try {
                    val c = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 7000; readTimeout = 9000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "ProfessorVPN/4.6")
                    }
                    c.inputStream.use { BitmapFactory.decodeStream(it) }
                } catch (_: Throwable) { null }
                withContext(Dispatchers.Main) {
                    if (bmp != null && iv.isAttachedToWindow) iv.setImageBitmap(bmp)
                }
            }
        }
    }
}
