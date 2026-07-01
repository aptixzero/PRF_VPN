package com.neonvpn.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.neonvpn.app.R
import com.neonvpn.app.service.NeonVpnService
import com.neonvpn.app.util.AppPrefs

class MainActivity : BaseActivity() {

    // Reuse any fragment the FragmentManager already restored (after a config
    // change) instead of blindly creating a fresh one — otherwise we'd end up
    // with duplicate/detached instances and lost state.
    private val connectFragment: ConnectFragment by lazy {
        supportFragmentManager.findFragmentByTag(TAG_CONNECT) as? ConnectFragment ?: ConnectFragment()
    }
    private val configsFragment: ConfigsFragment by lazy {
        supportFragmentManager.findFragmentByTag(TAG_CONFIGS) as? ConfigsFragment ?: ConfigsFragment()
    }
    private val freeFragment: FreeConfigsFragment by lazy {
        supportFragmentManager.findFragmentByTag(TAG_FREE) as? FreeConfigsFragment ?: FreeConfigsFragment()
    }
    private val sponsorFragment: DonateFragment by lazy {
        supportFragmentManager.findFragmentByTag(TAG_SPONSOR) as? DonateFragment ?: DonateFragment()
    }

    private lateinit var drawer: DrawerLayout

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(NeonVpnService.EXTRA_STATE) ?: return
            val info = intent.getStringExtra(NeonVpnService.EXTRA_INFO) ?: ""
            VpnStateBus.update(state, info)
        }
    }

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            VpnStateBus.updateStats(
                VpnStats(
                    upRate = intent.getLongExtra(NeonVpnService.EXTRA_UP_RATE, 0),
                    downRate = intent.getLongExtra(NeonVpnService.EXTRA_DOWN_RATE, 0),
                    upTotal = intent.getLongExtra(NeonVpnService.EXTRA_UP_TOTAL, 0),
                    downTotal = intent.getLongExtra(NeonVpnService.EXTRA_DOWN_TOTAL, 0),
                    ping = intent.getLongExtra(NeonVpnService.EXTRA_PING, -1),
                    uptime = intent.getLongExtra(NeonVpnService.EXTRA_UPTIME, 0)
                )
            )
        }
    }

    private var activeFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v4.1: the whole wiring is guarded. A failure while wiring tabs / the
        // drawer must not crash the home screen — at worst a control is inert.
        try {
            setupUi(savedInstanceState)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "setup failed: ${e.message}", e)
        }
        // v4.2 — politely request notification permission on Android 13+ so the
        // VPN status + the "Auto Test is ON" banner can actually appear. Fully
        // guarded: a denial just means no banner, never a crash.
        try { maybeRequestNotificationPermission() } catch (_: Throwable) {}
    }

    private fun maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(perm), 9701)
            }
        }
    }

    private fun setupUi(savedInstanceState: Bundle?) {
        // Consume any pending ui_dirty flag that may have been set BEFORE this
        // activity was (re)created so we don't recreate ourselves in a loop.
        clearUiDirty()
        setContentView(R.layout.activity_main)

        drawer = findViewById(R.id.drawer_layout)
        // Drawer opens from the END (right). Lock it shut to swipe so only the
        // hamburger toggles it.
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        // Add all four fragments ONCE and switch between them with hide/show
        // instead of replace(). The fragments (and their adapter state, in-flight
        // ping results, scroll position) are never destroyed on a tab switch.
        val fm = supportFragmentManager
        if (fm.findFragmentByTag(TAG_CONNECT) == null) {
            fm.beginTransaction().apply {
                add(R.id.fragment_container, connectFragment, TAG_CONNECT)
                add(R.id.fragment_container, configsFragment, TAG_CONFIGS).hide(configsFragment)
                add(R.id.fragment_container, freeFragment, TAG_FREE).hide(freeFragment)
                add(R.id.fragment_container, sponsorFragment, TAG_SPONSOR).hide(sponsorFragment)
            }.commitNow()
            activeFragment = connectFragment
        } else {
            activeFragment = connectFragment
            fm.beginTransaction().apply {
                hide(configsFragment); hide(freeFragment); hide(sponsorFragment); show(connectFragment)
            }.commitNow()
        }

        findViewById<android.view.View>(R.id.nav_connect).setOnClickListener {
            switchTo(connectFragment); highlightTab(TAB_CONNECT)
        }
        findViewById<android.view.View>(R.id.nav_configs).setOnClickListener {
            switchTo(configsFragment); highlightTab(TAB_CONFIGS)
        }
        findViewById<android.view.View>(R.id.nav_free).setOnClickListener {
            switchTo(freeFragment); highlightTab(TAB_FREE)
        }
        findViewById<android.view.View>(R.id.nav_sponsor).setOnClickListener {
            switchTo(sponsorFragment); highlightTab(TAB_SPONSOR)
        }
        highlightTab(TAB_CONNECT)

        // ----- Hamburger menu (top-right) -----
        findViewById<android.view.View>(R.id.btn_menu).setOnClickListener {
            if (drawer.isDrawerOpen(GravityCompat.END)) drawer.closeDrawer(GravityCompat.END)
            else { refreshDrawerValues(); drawer.openDrawer(GravityCompat.END) }
        }
        wireDrawerRows()
    }

    private fun wireDrawerRows() {
        findViewById<android.view.View>(R.id.menu_theme).setOnClickListener {
            // toggle dark <-> light
            val next = if (AppPrefs.isDark(this)) AppPrefs.THEME_LIGHT else AppPrefs.THEME_DARK
            AppPrefs.setTheme(this, next)
            drawer.closeDrawer(GravityCompat.END)
            recreate()
        }
        findViewById<android.view.View>(R.id.menu_language).setOnClickListener {
            // toggle fa <-> en
            val next = if (AppPrefs.getLanguage(this) == AppPrefs.LANG_FA) AppPrefs.LANG_EN else AppPrefs.LANG_FA
            AppPrefs.setLanguage(this, next)
            drawer.closeDrawer(GravityCompat.END)
            recreate()
        }
        findViewById<android.view.View>(R.id.menu_settings).setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.menu_contact).setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, ContactActivity::class.java))
        }
    }

    /** Show current theme/language selections inside the drawer rows. */
    private fun refreshDrawerValues() {
        findViewById<TextView>(R.id.menu_theme_value)?.text =
            getString(if (AppPrefs.isDark(this)) R.string.theme_dark else R.string.theme_light)
        findViewById<TextView>(R.id.menu_language_value)?.text =
            getString(if (AppPrefs.getLanguage(this) == AppPrefs.LANG_FA) R.string.lang_fa else R.string.lang_en)
    }

    /** Public so child fragments can jump the user back to the connect screen. */
    fun goToConnect() {
        switchTo(connectFragment)
        highlightTab(TAB_CONNECT)
    }

    /** Public: jump to the My Configs tab (used by the Home server-selector card). */
    fun showConfigsTab() {
        switchTo(configsFragment)
        highlightTab(TAB_CONFIGS)
    }

    /** Public: open the Settings screen (used by the Home quick-action button). */
    fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun highlightTab(which: Int) {
        findViewById<TextView>(R.id.nav_connect).isSelected = which == TAB_CONNECT
        findViewById<TextView>(R.id.nav_configs).isSelected = which == TAB_CONFIGS
        findViewById<TextView>(R.id.nav_free).isSelected = which == TAB_FREE
        findViewById<TextView>(R.id.nav_sponsor).isSelected = which == TAB_SPONSOR
    }

    /** Hide the current fragment and show the requested one — no destroy/recreate. */
    private fun switchTo(f: Fragment) {
        if (activeFragment === f) return
        supportFragmentManager.beginTransaction().apply {
            activeFragment?.let { hide(it) }
            show(f)
        }.commit()
        activeFragment = f
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.END)) {
            drawer.closeDrawer(GravityCompat.END)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        // If theme/language were changed elsewhere (e.g. SettingsActivity), rebuild.
        if (isUiDirty()) {
            clearUiDirty()
            recreate()
        }
    }

    override fun onStart() {
        super.onStart()
        registerR(stateReceiver, NeonVpnService.BROADCAST_STATE)
        registerR(statsReceiver, NeonVpnService.BROADCAST_STATS)
        // Re-sync with the authoritative service state the moment we're back in
        // the foreground: any state change made while backgrounded (tunnel lost,
        // service killed) is reflected immediately, so the UI is never stale.
        VpnStateBus.reconcileWithService()
        // Anonymous, privacy-first online heartbeat (counts only — no PII).
        try { com.neonvpn.app.stats.UserStatsReporter.pulse(this) } catch (_: Throwable) {}
    }

    private fun registerR(r: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(r, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(statsReceiver) } catch (_: Exception) {}
    }

    private fun isUiDirty(): Boolean =
        getSharedPreferences("neonvpn_prefs", MODE_PRIVATE).getBoolean("ui_dirty", false)

    private fun clearUiDirty() {
        getSharedPreferences("neonvpn_prefs", MODE_PRIVATE).edit().putBoolean("ui_dirty", false).apply()
    }

    companion object {
        private const val TAB_CONNECT = 0
        private const val TAB_CONFIGS = 1
        private const val TAB_FREE = 2
        private const val TAB_SPONSOR = 3
        private const val TAG_CONNECT = "f_connect"
        private const val TAG_CONFIGS = "f_configs"
        private const val TAG_FREE = "f_free"
        private const val TAG_SPONSOR = "f_sponsor"
    }
}
