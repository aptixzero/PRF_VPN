package com.neonvpn.app.ui

import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.neonvpn.app.R
import com.neonvpn.app.config.AutoTestEngine
import com.neonvpn.app.config.ConfigParser
import com.neonvpn.app.config.ConfigStore
import com.neonvpn.app.config.ConnectivityProbe
import com.neonvpn.app.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4.6 — AUTO TEST connectivity page.
 *
 * A deliberately minimal screen: a title + a progress bar (no per-source text).
 * On open it runs the fast [ConnectivityProbe] which scans the 50 live sources a
 * few at a time and STOPS at the FIRST reachable vless + vmess pair. Those are
 * saved into My Configs (auto-selected), the page closes, and the continuous
 * [AutoTestEngine] is started to keep filling My Configs 240-at-a-time in the
 * background.
 *
 * Every step is guarded so the page can never crash the app.
 */
class AutoTestActivity : BaseActivity() {

    private lateinit var bar: ProgressBar
    private lateinit var percent: TextView
    private var probeJob: Job? = null
    @Volatile private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_test)
        bar = findViewById(R.id.probe_bar)
        percent = findViewById(R.id.probe_percent)

        findViewById<TextView>(R.id.btn_probe_cancel).setOnClickListener {
            finishSafely()
        }

        startProbe()
    }

    private fun startProbe() {
        probeJob = lifecycleScope.launch {
            val result = try {
                ConnectivityProbe.probe(applicationContext) { p ->
                    runOnUiThread {
                        if (!isFinishing) {
                            bar.progress = p
                            percent.text = "$p%"
                        }
                    }
                }
            } catch (_: Throwable) {
                ConnectivityProbe.Result(null, null)
            }

            // Save whatever working pair we found straight into My Configs.
            if (result.ok) {
                runCatching { saveResult(result) }
            }

            // Kick off the continuous engine to keep filling My Configs.
            runCatching { AutoTestEngine.start(applicationContext) }

            // Close the page and return to Home — the engine keeps working.
            finishSafely()
        }
    }

    private suspend fun saveResult(result: ConnectivityProbe.Result) = withContext(Dispatchers.IO) {
        val store = ConfigStore(applicationContext)
        val toAdd = ArrayList<ServerConfig>(2)
        // Number them generically continuing from the free-source counter isn't
        // needed here; just give neutral Server labels.
        var n = store.getServers().size
        result.vless?.let { n++; toAdd.add(it.copy(remark = "Server $n")) }
        result.vmess?.let { n++; toAdd.add(it.copy(remark = "Server $n")) }
        if (toAdd.isNotEmpty()) {
            store.addServers(toAdd)
            if (store.getSelectedId() == null) {
                store.getServers().firstOrNull()?.let { store.setSelectedId(it.id) }
            }
        }
    }

    private fun finishSafely() {
        if (finished) return
        finished = true
        runCatching { probeJob?.cancel() }
        runCatching { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { probeJob?.cancel() }
    }
}
