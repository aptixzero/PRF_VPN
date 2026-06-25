package com.neonvpn.app.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.neonvpn.app.R
import com.neonvpn.app.config.ConfigStore
import com.neonvpn.app.config.RemoteConfig
import com.neonvpn.app.config.RemoteConfigStore
import com.neonvpn.app.service.NeonVpnService
import com.neonvpn.app.ui.widget.GlobeConnectView
import com.neonvpn.app.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * v4.0 HOME / CONNECT screen — matches the UI sheet (screens 02/03/04).
 *
 * Centrepiece is the animated [GlobeConnectView] (a rotating violet wireframe
 * globe that turns emerald-green with a check shield when connected). The big
 * TAP TO CONNECT pill below it toggles the real VpnService on/off — it is
 * 350ms-debounced and serialised through a single Mutex-guarded state-machine
 * coroutine (§4.5) so rapid taps can never spawn overlapping start/stop work.
 *
 * Every speed / ping / data / uptime number comes straight from the live Xray
 * core via [NeonVpnService] — there are NO random / fake values anywhere.
 */
class ConnectFragment : Fragment() {

    private lateinit var store: ConfigStore
    private lateinit var statusText: TextView
    private lateinit var serverText: TextView
    private lateinit var globe: GlobeConnectView
    private lateinit var connectPill: FrameLayout
    private lateinit var connectLabel: TextView
    private var appLogo: ImageView? = null
    private var telegramIcon: View? = null

    // Protocol segmented tabs (cosmetic selector reflecting the active config).
    private var protoVless: TextView? = null
    private var protoVmess: TextView? = null
    private var protoXray: TextView? = null

    // Connection / data-usage / ip summary row.
    private var statConnection: TextView? = null
    private var statData: TextView? = null
    private var statIp: TextView? = null

    private lateinit var downloadSpeed: TextView
    private lateinit var downloadTotal: TextView
    private lateinit var uploadSpeed: TextView
    private lateinit var uploadTotal: TextView
    private lateinit var pingValue: TextView
    private lateinit var uptimeValue: TextView

    // §4.5 — a SINGLE state-machine coroutine consumes toggle intents through a
    // CONFLATED channel (latest-tap-wins) guarded by a Mutex.
    private val toggleChannel = Channel<Unit>(Channel.CONFLATED)
    private val toggleMutex = Mutex()
    private var lastTapMs = 0L
    private val tapDebounceMs = 350L

    @Volatile private var telegramUrl: String = ""

    private val remoteListener: (RemoteConfig) -> Unit = { cfg ->
        activity?.runOnUiThread {
            bindLogo(cfg.appLogoUrl)
            telegramUrl = cfg.homeTelegramUrl
        }
    }

    private val listener: (String, String) -> Unit = { state, info ->
        activity?.runOnUiThread { render(state, info) }
    }
    private val statsListener: (VpnStats) -> Unit = { s ->
        activity?.runOnUiThread { renderStats(s) }
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                actuallyStartVpn()
            } else {
                render(NeonVpnService.STATE_DISCONNECTED, "Permission denied")
            }
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_connect, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = ConfigStore(requireContext())
        statusText = view.findViewById(R.id.status_text)
        serverText = view.findViewById(R.id.server_text)
        globe = view.findViewById(R.id.globe)
        connectPill = view.findViewById(R.id.connect_control)
        connectLabel = view.findViewById(R.id.connect_label)
        appLogo = view.findViewById(R.id.app_logo)
        telegramIcon = view.findViewById(R.id.telegram_icon)

        protoVless = view.findViewById(R.id.proto_vless)
        protoVmess = view.findViewById(R.id.proto_vmess)
        protoXray = view.findViewById(R.id.proto_xray)

        statConnection = view.findViewById(R.id.stat_connection_value)
        statData = view.findViewById(R.id.stat_data_value)
        statIp = view.findViewById(R.id.stat_ip_value)

        downloadSpeed = view.findViewById(R.id.download_speed)
        downloadTotal = view.findViewById(R.id.download_total)
        uploadSpeed = view.findViewById(R.id.upload_speed)
        uploadTotal = view.findViewById(R.id.upload_total)
        pingValue = view.findViewById(R.id.ping_value)
        uptimeValue = view.findViewById(R.id.uptime_value)

        // §4.2 — Telegram icon opens the admin-configured "In-App Telegram Link".
        telegramUrl = RemoteConfigStore.cachedTelegramUrl(requireContext())
        telegramIcon?.setOnClickListener { openTelegram() }
        viewLifecycleOwner.lifecycleScope.launch {
            RemoteConfigStore.refreshTelegramThrottled(requireContext())
        }

        // Connect pill: animated press → toggle. The globe is the visualizer,
        // the pill is the action button (matches the UI sheet).
        connectPill.setOnClickListener { animatePillPress(); onTap() }

        // Quick-action buttons navigate / hint (functional but lightweight).
        view.findViewById<View?>(R.id.server_selector)?.setOnClickListener {
            (activity as? MainActivity)?.showConfigsTab()
        }
        view.findViewById<View?>(R.id.qa_settings)?.setOnClickListener {
            (activity as? MainActivity)?.openSettings()
        }
        view.findViewById<View?>(R.id.qa_protocol)?.setOnClickListener {
            (activity as? MainActivity)?.showConfigsTab()
        }

        startToggleStateMachine()

        maybeAskNotifications()
    }

    /** A short, springy scale-down/up so the big pill feels physical when tapped. */
    private fun animatePillPress() {
        connectPill.animate().cancel()
        connectPill.animate()
            .scaleX(0.94f).scaleY(0.94f)
            .setDuration(90)
            .withEndAction {
                connectPill.animate()
                    .scaleX(1f).scaleY(1f)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .setDuration(220)
                    .start()
            }
            .start()
    }

    private fun onTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapMs < tapDebounceMs) return
        lastTapMs = now
        toggleChannel.trySend(Unit)
    }

    private fun startToggleStateMachine() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                for (ignored in toggleChannel) {
                    toggleMutex.withLock { onToggle() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        synchronized(VpnStateBus.listeners) { VpnStateBus.listeners.add(listener) }
        synchronized(VpnStateBus.statsListeners) { VpnStateBus.statsListeners.add(statsListener) }
        RemoteConfigStore.addListener(remoteListener)
        VpnStateBus.reconcileWithService()
        render(VpnStateBus.state, VpnStateBus.info)
        renderStats(VpnStateBus.stats)
        telegramUrl = RemoteConfigStore.cachedTelegramUrl(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            RemoteConfigStore.refreshTelegramThrottled(requireContext())
        }
    }

    override fun onPause() {
        super.onPause()
        synchronized(VpnStateBus.listeners) { VpnStateBus.listeners.remove(listener) }
        synchronized(VpnStateBus.statsListeners) { VpnStateBus.statsListeners.remove(statsListener) }
        RemoteConfigStore.removeListener(remoteListener)
    }

    private fun bindLogo(url: String) {
        val iv = appLogo ?: return
        if (url.isBlank()) { iv.visibility = View.GONE; return }
        CoroutineScope(Dispatchers.IO).launch {
            val bmp = try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000; readTimeout = 9000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "ProfessorVPN/4.0")
                }
                c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (_: Throwable) { null }
            withContext(Dispatchers.Main) {
                if (isAdded && bmp != null) {
                    iv.setImageBitmap(bmp); iv.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun openTelegram() {
        val url = telegramUrl.ifBlank { RemoteConfigStore.cachedTelegramUrl(requireContext()) }.trim()
        if (url.isBlank()) return
        try {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (_: Throwable) { }
    }

    private fun onToggle() {
        val current = VpnStateBus.state
        if (current == NeonVpnService.STATE_CONNECTED ||
            current == NeonVpnService.STATE_CONNECTING
        ) {
            stopVpn()
            return
        }

        val selected = store.getSelected()
        if (selected == null) {
            statusText.text = getString(R.string.no_config)
            globe.setState(GlobeConnectView.State.IDLE)
            return
        }

        try {
            val prepare = VpnService.prepare(requireContext())
            if (prepare != null) {
                vpnPermissionLauncher.launch(prepare)
            } else {
                actuallyStartVpn()
            }
        } catch (e: Throwable) {
            render(NeonVpnService.STATE_ERROR, "Cannot request VPN permission")
        }
    }

    private fun actuallyStartVpn() {
        try {
            render(NeonVpnService.STATE_CONNECTING, store.getSelected()?.remark ?: "")
            val intent = Intent(requireContext(), NeonVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
        } catch (e: Throwable) {
            render(NeonVpnService.STATE_ERROR, "Failed to start VPN service")
        }
    }

    private fun stopVpn() {
        val intent = Intent(requireContext(), NeonVpnService::class.java).apply {
            action = NeonVpnService.ACTION_STOP
        }
        requireContext().startService(intent)
        render(NeonVpnService.STATE_DISCONNECTED, "")
    }

    private fun render(state: String, info: String) {
        val selected = store.getSelected()
        serverText.text = selected?.remark ?: getString(R.string.no_config)
        updateProtoTabs(selected?.protocol)

        when (state) {
            NeonVpnService.STATE_CONNECTED -> {
                statusText.text = getString(R.string.connected)
                statusText.setTextColor(themeColor(R.attr.appAccentGreen))
                globe.setState(GlobeConnectView.State.CONNECTED)
                connectLabel.text = getString(R.string.tap_to_disconnect)
                connectPill.setBackgroundResource(R.drawable.connect_pill_connected)
                statConnection?.text = getString(R.string.connected)
            }
            NeonVpnService.STATE_CONNECTING -> {
                statusText.text = getString(R.string.connecting)
                statusText.setTextColor(0xFFFFB020.toInt())
                globe.setState(GlobeConnectView.State.CONNECTING)
                connectLabel.text = getString(R.string.connecting)
                connectPill.setBackgroundResource(R.drawable.connect_pill_connecting)
                statConnection?.text = getString(R.string.connecting)
            }
            NeonVpnService.STATE_ERROR -> {
                statusText.text = if (info.isNotBlank()) "ERROR · $info" else getString(R.string.error)
                statusText.setTextColor(themeColor(R.attr.appAccentRed))
                globe.setState(GlobeConnectView.State.ERROR)
                connectLabel.text = getString(R.string.tap_to_connect)
                connectPill.setBackgroundResource(R.drawable.connect_pill_idle)
                statConnection?.text = getString(R.string.error)
                resetStats()
            }
            else -> {
                statusText.text = getString(R.string.disconnected)
                statusText.setTextColor(themeColor(R.attr.appTextSecondary))
                globe.setState(GlobeConnectView.State.IDLE)
                connectLabel.text = getString(R.string.tap_to_connect)
                connectPill.setBackgroundResource(R.drawable.connect_pill_idle)
                statConnection?.text = getString(R.string.disconnected)
                resetStats()
            }
        }
    }

    /** Highlight the segmented tab matching the selected config's protocol. */
    private fun updateProtoTabs(protocol: String?) {
        val p = protocol?.lowercase() ?: ""
        val active = themeColor(R.attr.appTextPrimary)
        val dim = themeColor(R.attr.appTextSecondary)
        val white = 0xFFFFFFFF.toInt()
        val isVless = p.contains("vless")
        val isVmess = p.contains("vmess")
        val isXray = !isVless && !isVmess && p.isNotEmpty()
        protoVless?.isSelected = isVless
        protoVmess?.isSelected = isVmess
        protoXray?.isSelected = isXray || (!isVless && !isVmess)
        protoVless?.setTextColor(if (isVless) white else dim)
        protoVmess?.setTextColor(if (isVmess) white else dim)
        protoXray?.setTextColor(if (protoXray?.isSelected == true) white else dim)
        if (protocol == null) { protoVless?.isSelected = true; protoVless?.setTextColor(white); protoXray?.isSelected = false; protoXray?.setTextColor(dim) }
    }

    private fun renderStats(s: VpnStats) {
        val connected = VpnStateBus.state == NeonVpnService.STATE_CONNECTED
        if (!connected) return
        downloadSpeed.text = Format.speed(s.downRate)
        uploadSpeed.text = Format.speed(s.upRate)
        downloadTotal.text = Format.size(s.downTotal)
        uploadTotal.text = Format.size(s.upTotal)
        pingValue.text = Format.ping(s.ping)
        uptimeValue.text = Format.duration(s.uptime)
        statData?.text = Format.size(s.downTotal + s.upTotal)
    }

    private fun resetStats() {
        downloadSpeed.text = "0 B/s"
        uploadSpeed.text = "0 B/s"
        downloadTotal.text = "0 B"
        uploadTotal.text = "0 B"
        pingValue.text = getString(R.string.value_dash)
        uptimeValue.text = "00:00:00"
        statData?.text = "0 B"
        statIp?.text = getString(R.string.value_dash)
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        return if (requireContext().theme.resolveAttribute(attr, tv, true)) tv.data
        else 0xFF00FF66.toInt()
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
