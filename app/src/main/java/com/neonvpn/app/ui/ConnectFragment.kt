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

    // v4.6 — Telegram-channel CTA button (replaces the old protocol tags).
    private var homeCta: View? = null
    private var homeCtaLabel: TextView? = null
    @Volatile private var ctaUrl: String = ""

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

    // v6.3 — the small square behind the server name now holds the COUNTRY FLAG
    // of whatever server we are connected to. Rendered as a Unicode regional-
    // indicator emoji so there is no image download and therefore no lag/freeze.
    private var flagText: TextView? = null
    /** The host whose flag is currently being resolved (dedupes async callbacks). */
    @Volatile private var flagHost: String = ""

    // v6.3 — in-app announcement card ("اعلان Professor Vpn").
    private var noticeCard: View? = null
    private var noticeTitle: TextView? = null
    private var noticeText: TextView? = null
    private var noticeAccent: View? = null

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
            bindCta(cfg)
            bindNotice(cfg)
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

        homeCta = view.findViewById(R.id.home_cta)
        homeCtaLabel = view.findViewById(R.id.home_cta_label)

        statConnection = view.findViewById(R.id.stat_connection_value)
        statData = view.findViewById(R.id.stat_data_value)
        statIp = view.findViewById(R.id.stat_ip_value)

        downloadSpeed = view.findViewById(R.id.download_speed)
        downloadTotal = view.findViewById(R.id.download_total)
        uploadSpeed = view.findViewById(R.id.upload_speed)
        uploadTotal = view.findViewById(R.id.upload_total)
        pingValue = view.findViewById(R.id.ping_value)
        uptimeValue = view.findViewById(R.id.uptime_value)

        flagText = view.findViewById(R.id.flag_text)

        noticeCard = view.findViewById(R.id.notice_card)
        noticeTitle = view.findViewById(R.id.notice_title)
        noticeText = view.findViewById(R.id.notice_text)
        noticeAccent = view.findViewById(R.id.notice_accent)
        view.findViewById<View?>(R.id.notice_close)?.setOnClickListener { dismissNotice() }
        bindNotice(RemoteConfigStore.current())

        // §4.2 — Telegram icon opens the admin-configured "In-App Telegram Link".
        telegramUrl = RemoteConfigStore.cachedTelegramUrl(requireContext())
        telegramIcon?.setOnClickListener { openTelegram() }
        viewLifecycleOwner.lifecycleScope.launch {
            RemoteConfigStore.refreshTelegramThrottled(requireContext())
        }

        // Connect pill: animated press → toggle. The globe is the visualizer,
        // the pill is the action button (matches the UI sheet).
        connectPill.setOnClickListener { animatePillPress(); onTap() }

        // The server card still navigates to My Configs.
        view.findViewById<View?>(R.id.server_selector)?.setOnClickListener {
            (activity as? MainActivity)?.showConfigsTab()
        }
        // v6.3 — the quick-action row is now DISPLAY-ONLY except Settings.
        // auto connect / kill switch / protocol are indicators, not buttons, so
        // no click listener is attached to them (and the layout marks them
        // non-clickable + non-focusable so they never show a ripple).
        view.findViewById<View?>(R.id.qa_settings)?.setOnClickListener {
            (activity as? MainActivity)?.openSettings()
        }

        // v4.6 — Telegram-channel CTA button under the connect pill.
        homeCta?.setOnClickListener { openCta() }
        bindCta(RemoteConfigStore.current())

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

    /**
     * v6.3 — smoother state changes on the pill and the status line.
     *
     * The old code swapped the pill background and the status text INSTANTLY, so
     * every transition (idle → connecting → connected) was a hard flicker that
     * made the whole screen feel cheap next to the animated globe. We now
     * cross-fade the pill whenever its background actually changes, and give the
     * status text a short fade so the words don't just pop.
     */
    private var pillBgRes: Int = 0

    private fun setPillBackground(res: Int) {
        if (pillBgRes == res) return
        pillBgRes = res
        connectPill.animate().cancel()
        connectPill.animate()
            .alpha(0.55f)
            .setDuration(110)
            .withEndAction {
                connectPill.setBackgroundResource(res)
                connectPill.animate().alpha(1f).setDuration(170).start()
            }
            .start()
    }

    /** Fade the status label when its text changes (no fade on a repeat). */
    private fun setStatus(text: String, color: Int) {
        statusText.setTextColor(color)
        if (statusText.text?.toString() == text) return
        statusText.animate().cancel()
        statusText.animate()
            .alpha(0f)
            .setDuration(90)
            .withEndAction {
                statusText.text = text
                statusText.animate().alpha(1f).setDuration(150).start()
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
        // v5.6 — guarantee the connect animation is running when we return to the
        // foreground (fixes the "animation freezes after closing & reopening").
        if (::globe.isInitialized) globe.resumeAnimation()
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
                    setRequestProperty("User-Agent", "ProfessorVPN/4.1")
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

    /**
     * v6.3 — STOP BUTTON RELIABILITY (UI half).
     *
     * `startService()` can throw on Android 8+ if the process happens to be in
     * the background at that exact instant, and the old code let that exception
     * escape — the tap then did nothing at all and the pill stayed on
     * "Disconnect". Now:
     *
     *   • the intent is delivered defensively (foreground-service variant as a
     *     fallback), so the STOP command always reaches the service;
     *   • the UI is repainted to DISCONNECTED unconditionally, so even in the
     *     worst case the button visibly responds to the very first tap instead
     *     of appearing frozen;
     *   • the shared state bus is corrected too, so another tab can't restore a
     *     stale "Connected".
     */
    private fun stopVpn() {
        val ctx = context ?: return
        val intent = Intent(ctx, NeonVpnService::class.java).apply {
            action = NeonVpnService.ACTION_STOP
        }
        val delivered = runCatching { ctx.startService(intent); true }.getOrDefault(false)
        if (!delivered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Background-start restriction: the FGS variant is still allowed for
            // a service that is already running in the foreground.
            runCatching { ctx.startForegroundService(intent) }
        }
        // Always reflect the user's intent right away.
        VpnStateBus.state = NeonVpnService.STATE_DISCONNECTED
        VpnStateBus.info = ""
        render(NeonVpnService.STATE_DISCONNECTED, "")
    }

    private fun render(state: String, info: String) {
        val selected = store.getSelected()
        serverText.text = selected?.remark ?: getString(R.string.no_config)
        updateProtoTabs(selected?.protocol)
        // v6.3 — the flag square only ever shows something while CONNECTED.
        renderFlag(state, selected?.address, selected?.remark)

        when (state) {
            NeonVpnService.STATE_CONNECTED -> {
                setStatus(getString(R.string.connected), themeColor(R.attr.appAccentGreen))
                globe.setState(GlobeConnectView.State.CONNECTED)
                connectLabel.text = getString(R.string.tap_to_disconnect)
                setPillBackground(R.drawable.connect_pill_connected)
                statConnection?.text = getString(R.string.connected)
            }
            NeonVpnService.STATE_CONNECTING -> {
                setStatus(getString(R.string.connecting), 0xFFFFB020.toInt())
                globe.setState(GlobeConnectView.State.CONNECTING)
                connectLabel.text = getString(R.string.connecting)
                setPillBackground(R.drawable.connect_pill_connecting)
                statConnection?.text = getString(R.string.connecting)
            }
            NeonVpnService.STATE_ERROR -> {
                setStatus(
                    if (info.isNotBlank()) "ERROR · $info" else getString(R.string.error),
                    themeColor(R.attr.appAccentRed)
                )
                globe.setState(GlobeConnectView.State.ERROR)
                connectLabel.text = getString(R.string.tap_to_connect)
                setPillBackground(R.drawable.connect_pill_idle)
                statConnection?.text = getString(R.string.error)
                resetStats()
            }
            else -> {
                setStatus(getString(R.string.disconnected), themeColor(R.attr.appTextSecondary))
                globe.setState(GlobeConnectView.State.IDLE)
                connectLabel.text = getString(R.string.tap_to_connect)
                setPillBackground(R.drawable.connect_pill_idle)
                statConnection?.text = getString(R.string.disconnected)
                resetStats()
            }
        }
    }

    /** v4.6 — the protocol segmented tags were removed; this is now a no-op. */
    private fun updateProtoTabs(protocol: String?) { /* removed in v4.6 */ }

    // ------------------------------------------------------------------
    // v6.3 — COUNTRY FLAG on the Home server tile
    // ------------------------------------------------------------------

    /**
     * Paint the small square behind the server name.
     *
     * Rules from the brief:
     *  • default (not connected) → EMPTY square,
     *  • connected → the flag of the country the server sits in,
     *  • must work for ANY country IP,
     *  • must never lag or freeze the UI.
     *
     * To guarantee "no lag" we do the paint in two phases: an instantaneous,
     * zero-I/O cache read on the main thread, and — only if that misses — a
     * fire-and-forget background lookup whose result is applied later. The UI
     * thread therefore never blocks on DNS or HTTP.
     */
    private fun renderFlag(state: String, host: String?, remark: String?) {
        val tv = flagText ?: return
        val connected = state == NeonVpnService.STATE_CONNECTED
        if (!connected || host.isNullOrBlank()) {
            flagHost = ""
            tv.text = ""
            return
        }
        flagHost = host
        val ctx = context ?: return
        // Phase 1 — instant (memory/disk cache + offline text heuristics).
        tv.text = com.neonvpn.app.util.CountryFlags.cachedFlagFor(ctx, host, remark ?: "")
        // Phase 2 — background resolve when we still don't know the country.
        if (tv.text.isNullOrEmpty()) {
            com.neonvpn.app.util.CountryFlags.resolveAsync(
                ctx.applicationContext, host, remark ?: ""
            ) { flag ->
                val v = flagText ?: return@resolveAsync
                // Ignore stale callbacks (user switched server meanwhile).
                if (flagHost != host) return@resolveAsync
                if (VpnStateBus.state != NeonVpnService.STATE_CONNECTED) return@resolveAsync
                v.post { if (isAdded && flagHost == host) v.text = flag }
            }
        }
    }

    // ------------------------------------------------------------------
    // v6.3 — in-app announcement ("اعلان Professor Vpn")
    // ------------------------------------------------------------------

    /**
     * Show the operator's announcement. Per the brief the card NEVER says the
     * message came from an admin panel — it is titled "اعلان Professor Vpn"
     * followed by the message body verbatim.
     */
    private fun bindNotice(cfg: RemoteConfig) {
        val card = noticeCard ?: return
        val n = cfg.notice
        val ctx = context ?: return
        if (!n.hasContent || com.neonvpn.app.util.AppPrefs.isNoticeDismissed(ctx, n.id)) {
            card.visibility = View.GONE
            return
        }
        noticeTitle?.text = n.title.ifBlank { com.neonvpn.app.config.NoticeConfig.DEFAULT_TITLE }
        noticeText?.text = n.text
        val accent = try { android.graphics.Color.parseColor(n.color) } catch (_: Throwable) { 0 }
        if (accent != 0) {
            noticeAccent?.setBackgroundColor(accent)
            noticeTitle?.setTextColor(accent)
        }
        card.visibility = View.VISIBLE
    }

    /** Remember this announcement id so it is not forced on the user again. */
    private fun dismissNotice() {
        val ctx = context ?: return
        com.neonvpn.app.util.AppPrefs.dismissNotice(ctx, RemoteConfigStore.current().notice.id)
        noticeCard?.visibility = View.GONE
    }

    /** Bind the Home CTA button (label per-language + admin url). */
    private fun bindCta(cfg: RemoteConfig) {
        val cta = cfg.homeCta
        if (!cta.enabled) { homeCta?.visibility = View.GONE; return }
        homeCta?.visibility = View.VISIBLE
        val lang = com.neonvpn.app.util.AppPrefs.getLanguage(requireContext())
        val isFa = lang == com.neonvpn.app.util.AppPrefs.LANG_FA
        val custom = if (isFa) cta.labelFa else cta.labelEn
        homeCtaLabel?.text = if (custom.isNotBlank()) custom else getString(R.string.join_telegram)
        ctaUrl = cta.url.ifBlank { cfg.homeTelegramUrl }
    }

    /** Open the CTA link (admin url, falling back to the in-app Telegram link). */
    private fun openCta() {
        val url = ctaUrl.ifBlank { RemoteConfigStore.cachedTelegramUrl(requireContext()) }.trim()
        if (url.isBlank()) return
        try {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (_: Throwable) { }
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
