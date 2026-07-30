package com.neonvpn.app.ui

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.neonvpn.app.R
import com.neonvpn.app.config.AutoTestEngine
import com.neonvpn.app.config.ConfigParser
import com.neonvpn.app.config.ConfigStore
import com.neonvpn.app.config.ConnectivityProbe
import com.neonvpn.app.config.FreeConfigSource
import com.neonvpn.app.config.FreeConfigStore
import com.neonvpn.app.config.PingService
import com.neonvpn.app.config.PingStore
import com.neonvpn.app.config.SeenConfigStore
import com.neonvpn.app.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v6.9 — AUTO TEST connectivity page.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE THREE BUGS THIS PAGE HAD, AND HOW v6.9 FIXES THEM
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * 1. «روی 30 درصد میمونه و بالای 5 دقیقه طول میکشه» — it parked at 30 % for
 *    minutes. That was [ConnectivityProbe]'s doing (it ranked sources by ping in
 *    two sequential halves); the probe is now a parallel RACE that finishes in
 *    seconds. This page additionally refuses to sit silently: it names its
 *    current phase on screen, so progress is always legible.
 *
 * 2. «کانفیگ ها به لیست free اضافه نمیشوند» — nothing reached the Free list.
 *    v6.8 only wrote to the store when the probe returned a non-empty list, so
 *    ANY hiccup in the collect phase meant the user got an empty tab and no
 *    explanation. v6.9 adds a RESCUE pass: if the probe reached a source but came
 *    back empty, we go straight to [FreeConfigSource.nextBatch] ourselves before
 *    giving up. And a failed press never wipes the existing list any more.
 *
 * 3. «روند پینگ گرفتن رو نمیتونم ببینم» — the pinging was invisible. This page
 *    now STARTS the ping sweep itself, synchronously, before it closes
 *    ([PingService.pingAll] publishes its running state on the calling thread as
 *    of v6.9), so the Free tab already shows a live, climbing ping progress bar
 *    the instant this page disappears.
 *
 * ── THE FLOW ────────────────────────────────────────────────────────────────
 *   0 % →  60 %  a REAL connection test: every candidate feed of both kinds is
 *                opened AT ONCE and the FIRST reachable VLESS source and FIRST
 *                reachable VMESS source win and are bonded. Per the brief we do
 *                NOT hunt for the best ping here.
 *   60 % → 100 % 120 VLESS + 120 VMESS are collected from those sources, written
 *                to FREE CONFIGS, and their ping sweep is started.
 *
 * My Configs is NEVER touched here. It is the user's permanent bucket (manual
 * pastes + the configs that actually ping, which [AutoTestEngine] copies in one
 * by one after they pass). Dumping raw configs there was the pre-6.1 bug.
 *
 * There is NO false "connection error". Every step is guarded so this page can
 * never crash the app.
 */
class AutoTestActivity : BaseActivity() {

    private lateinit var bar: ProgressBar
    private lateinit var percent: TextView
    private lateinit var status: TextView
    private var probeJob: Job? = null
    private var barAnimator: ObjectAnimator? = null
    @Volatile private var finished = false

    /** Ceiling for the rescue collect pass, so a rescue can't hang the page. */
    private val rescueBudgetMs = 25_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_test)
        bar = findViewById(R.id.probe_bar)
        percent = findViewById(R.id.probe_percent)
        status = findViewById(R.id.probe_status)
        bar.max = 100

        findViewById<TextView>(R.id.btn_probe_cancel).setOnClickListener {
            finishSafely()
        }

        startProbe()
    }

    /**
     * Publish the current phase so the user can always see WHAT is happening.
     * A bare bar makes a slow phase indistinguishable from a freeze — which is
     * exactly how the "stuck at 30 %" report started.
     */
    private fun setPhase(resId: Int) {
        runOnUiThread { if (!isFinishing) runCatching { status.setText(resId) } }
    }

    private fun startProbe() {
        probeJob = lifecycleScope.launch {
            // Shared dedup memory (persistent seen-set + everything already in My
            // Configs) so the probe never hands back a config the user already has.
            // The probe mutates this and we persist it once the batch lands.
            val seenKeys = withContext(Dispatchers.IO) {
                val s = HashSet<String>()
                runCatching { s.addAll(SeenConfigStore.load(applicationContext)) }
                runCatching {
                    ConfigStore(applicationContext).getServers()
                        .forEach { s.add(ConfigParser.dedupKey(it)) }
                }
                s
            }

            setPhase(R.string.autotest_phase_sources)

            val result = try {
                ConnectivityProbe.probe(applicationContext, seenKeys) { p ->
                    runOnUiThread {
                        if (!isFinishing) {
                            animateBarTo(p)
                            percent.text = "$p%"
                            // Name the phase from the bar's own position, so the two
                            // can never disagree with each other.
                            runCatching {
                                status.setText(
                                    if (p < 55) R.string.autotest_phase_sources
                                    else R.string.autotest_phase_collect
                                )
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                ConnectivityProbe.Result(emptyList(), reachedSource = false)
            }

            // ── RESCUE PASS (v6.9) ───────────────────────────────────────────
            // The single most-complained-about symptom was an Auto Test that
            // finished and left the Free tab empty. If the probe proved the user
            // CAN reach a source but its collect phase came back with nothing, the
            // right answer is to collect ourselves rather than shrug — the network
            // is demonstrably up, so a batch is obtainable.
            var configs = result.configs
            if (configs.isEmpty() && result.reachedSource) {
                setPhase(R.string.autotest_phase_retry)
                configs = withContext(Dispatchers.IO) {
                    runCatching {
                        withTimeoutOrNull(rescueBudgetMs) {
                            FreeConfigSource.nextBatch(
                                applicationContext, 0, seenKeys
                            ) { added, target, _ ->
                                val pct = 60 + if (target > 0) (added * 36 / target) else 0
                                runOnUiThread {
                                    if (!isFinishing) {
                                        animateBarTo(pct)
                                        percent.text = "$pct%"
                                    }
                                }
                            }.configs
                        }
                    }.getOrNull() ?: emptyList()
                }
            }

            // Place the collected batch into FREE CONFIGS (never My Configs) so it
            // is present the moment this page closes. We NEVER show a "connection
            // error": whether or not we collected anything we start the background
            // engine (so configs keep arriving as an unstable link recovers) and
            // close quietly.
            val addedCount = if (configs.isNotEmpty()) {
                setPhase(R.string.autotest_phase_ping)
                runCatching { saveResult(configs, seenKeys) }.getOrDefault(0)
            } else 0

            animateBarTo(100)
            runOnUiThread { if (!isFinishing) percent.text = "100%" }

            // ── START THE PING SWEEP HERE, VISIBLY ───────────────────────────
            // «روند پینگ گرفتن رو نمیتونم ببینم». As of v6.9 `pingAll` publishes
            // `running = true` synchronously on the calling thread, so starting it
            // here means the Free tab is ALREADY showing a live ping progress bar
            // by the time this page finishes. The engine below picks the sweep up
            // rather than starting a competing one (only one sweep can exist).
            if (addedCount > 0) {
                runCatching {
                    val fresh: List<ServerConfig> = withContext(Dispatchers.IO) {
                        FreeConfigStore(applicationContext).get()
                    }
                    if (fresh.isNotEmpty()) {
                        PingService.pingAll(applicationContext, fresh, PingStore.FREE)
                    }
                }
            }

            // Always (RE)start the continuous engine — it pings the fresh Free batch
            // and copies ONLY the configs that actually ping into My Configs, 240 at
            // a time. restart() (not start()) means re-opening this page can never
            // wedge the engine or leave two loops fighting.
            runCatching { AutoTestEngine.restart(applicationContext) }

            if (addedCount > 0) {
                runOnUiThread { toast(getString(R.string.probe_saved, addedCount)) }
            }
            finishSafely()
        }
    }

    private fun toast(msg: String) {
        runCatching {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Place the freshly-probed batch into FREE CONFIGS (NOT My Configs).
     *
     * A fresh Auto Test REPLACES the previous FREE list with the new 240 batch —
     * the Free tab shows exactly the batch currently under test. Configs are
     * numbered sequentially "Server N", baked into the link's #remark so the number
     * survives being copied into another client.
     *
     * v6.9 — the old ping results for this bucket are dropped with
     * [PingStore.clear] rather than [PingService.clear]. `PingService.clear` also
     * calls `cancel()`, which killed the sweep we are about to start and reset the
     * shared status map for BOTH tabs — one of the reasons pings appeared to vanish.
     *
     * @return how many configs were actually placed in the Free list.
     */
    private suspend fun saveResult(
        configs: List<ServerConfig>,
        seenKeys: MutableSet<String>
    ): Int = withContext(Dispatchers.IO) {
        if (configs.isEmpty()) return@withContext 0
        val freeStore = FreeConfigStore(applicationContext)

        // Number them "Server 1..N" and bake the name into the raw link's remark.
        val named = ArrayList<ServerConfig>(configs.size)
        var n = 0
        for (cfg in configs) {
            n++
            val name = "Server $n"
            named.add(
                cfg.copy(
                    remark = name,
                    rawLink = ConfigParser.rewriteRemark(cfg.rawLink, name)
                )
            )
        }

        // REPLACE the previous Free batch with this brand-new one.
        runCatching { freeStore.replaceAll(named) }

        // Drop the PERSISTED badges of the batch that just went away, without
        // touching the live in-memory map or cancelling anything.
        runCatching { PingStore(applicationContext, PingStore.FREE).clear() }

        // Ping history is per-config; a brand-new batch has no history worth
        // smoothing against, so start its numbers clean.
        runCatching { com.neonvpn.app.config.Pinger.resetHistory() }

        // Persist the dedup memory so the NEXT batch prefers fresh configs.
        runCatching { SeenConfigStore.save(applicationContext, seenKeys) }
        named.size
    }

    /**
     * Tween the ProgressBar from its current value to [target] so motion is
     * buttery-smooth even when the probe reports progress in discrete jumps.
     * Never goes backwards.
     */
    private fun animateBarTo(target: Int) {
        val clamped = target.coerceIn(0, 100)
        if (clamped <= bar.progress) return
        barAnimator?.cancel()
        val anim = ObjectAnimator.ofInt(bar, "progress", bar.progress, clamped)
        anim.duration = if (clamped >= 100) 260L else 380L
        anim.interpolator = DecelerateInterpolator()
        barAnimator = anim
        anim.start()
    }

    private fun finishSafely() {
        if (finished) return
        finished = true
        runCatching { probeJob?.cancel() }
        runCatching { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { barAnimator?.cancel() }
        runCatching { probeJob?.cancel() }
    }
}
