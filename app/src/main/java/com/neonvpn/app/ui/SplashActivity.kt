package com.neonvpn.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.neonvpn.app.R
import com.neonvpn.app.service.XrayManager
import com.neonvpn.app.util.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run boot screen. Two jobs:
 *
 *  A. On the VERY FIRST launch (no language chosen yet) it shows a language
 *     picker (Persian / English). The choice is persisted and the activity is
 *     recreated so the rest of the app boots in the selected language/locale.
 *
 *  B. It PREPARES the prerequisites the VPN engine needs **before** the user can
 *     ever tap CONNECT — so the connect path is already warmed up and can't crash
 *     on a cold/uninitialised core:
 *       1. Extract bundled geoip/geosite assets to filesDir.
 *       2. Initialise the real Xray core env (Libv2ray.initCoreEnv) once, off the
 *          UI thread.
 *       3. Warm the tun2socks native library (its JNI_OnLoad).
 *
 * IMPORTANT: the splash NEVER fetches / loads any configs. On first launch BOTH
 * lists (My Configs AND Free Configs) stay EMPTY.
 */
class SplashActivity : BaseActivity() {

    private lateinit var bar: ProgressBar
    private lateinit var pct: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        bar = findViewById(R.id.boot_progress)
        pct = findViewById(R.id.boot_percent)
        status = findViewById(R.id.boot_status)

        // Premium, restrained startup motion: a soft title fade + gentle breathing
        // glow. No matrix / binary rain / falling characters / noisy background.
        val titleView = findViewById<View>(R.id.boot_title)
        titleView.alpha = 0f
        titleView.scaleX = 0.94f
        titleView.scaleY = 0.94f
        titleView.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(620)
            .start()
        ValueAnimator.ofFloat(0.78f, 1f).apply {
            duration = 1500
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { titleView.alpha = it.animatedValue as Float }
            start()
        }

        if (!AppPrefs.isLanguageChosen(this)) {
            askLanguageThenBoot()
        } else {
            prepareEverything()
        }
    }

    /** First-run only: ask the user for their preferred language. */
    private fun askLanguageThenBoot() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lang_pick_title)
            .setMessage(R.string.lang_pick_message)
            .setCancelable(false)
            .setPositiveButton(R.string.lang_fa) { d, _ ->
                d.dismiss()
                AppPrefs.setLanguage(this, AppPrefs.LANG_FA)
                recreate()
            }
            .setNegativeButton(R.string.lang_en) { d, _ ->
                d.dismiss()
                AppPrefs.setLanguage(this, AppPrefs.LANG_EN)
                recreate()
            }
            .show()
    }

    private fun setProgress(target: Int, msg: String) {
        runOnUiThread {
            status.text = msg
            ObjectAnimator.ofInt(bar, "progress", bar.progress, target).apply {
                duration = 460
                interpolator = android.view.animation.DecelerateInterpolator()
                start()
            }
            pct.text = "$target%"
        }
    }

    private fun prepareEverything() {
        // v4.1: the ENTIRE boot routine is wrapped so a failure in any single
        // step (native init, prefs, animation) can NEVER strand the user on the
        // splash screen or crash the app. Whatever happens, we always proceed to
        // MainActivity — the heavy native prep is a "nice to have" warm-up, not a
        // hard prerequisite (the VPN service re-inits the core lazily on connect).
        lifecycleScope.launch {
            try {
                setProgress(10, "")
                delay(140)

                // ---- heavy native preparation on a background thread ----
                runCatching {
                    withContext(Dispatchers.IO) {
                        val xray = XrayManager(applicationContext)
                        runOnUiThread { setProgress(34, "") }
                        try { xray.init() } catch (_: Throwable) {}
                        runOnUiThread { setProgress(58, "") }
                        XrayManager.cachedVersion()
                    }
                }

                // warm tun2socks .so so the first connect is hot
                runCatching {
                    withContext(Dispatchers.IO) {
                        try { com.v2ray.ang.service.TProxyService.touch() } catch (_: Throwable) {}
                    }
                }
                setProgress(78, "")
                delay(140)

                runCatching {
                    val prefs = getSharedPreferences("neonvpn_boot", MODE_PRIVATE)
                    prefs.edit().putBoolean("first_run_done", true).apply()
                }

                setProgress(92, "")
                delay(160)
                setProgress(100, "")
                delay(220)
            } catch (_: Throwable) {
                // Swallow ANYTHING — we still navigate below.
            } finally {
                goToMain()
            }
        }
    }

    /** Navigate to MainActivity exactly once, fully guarded. */
    private fun goToMain() {
        if (isFinishing || navigated) return
        navigated = true
        try {
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } catch (_: Throwable) {
        } finally {
            try { finish() } catch (_: Throwable) {}
        }
    }

    @Volatile private var navigated = false
}
