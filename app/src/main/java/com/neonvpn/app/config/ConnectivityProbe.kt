package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * v4.6 — FAST "Auto Test" connectivity probe.
 *
 * When the user taps AUTO TEST a small page opens with just a progress bar. That
 * page runs THIS probe, which answers one question quickly:
 *
 *   "Which of the 50 live sources can this user actually reach right now, and can
 *    we find a WORKING vless + a WORKING vmess through them?"
 *
 * Behaviour (per the v4.6 brief):
 *   • It scans the [LiveSources] a few at a time (bounded concurrency) rather than
 *     all 50 at once, so it's fast and light.
 *   • As soon as it finds ONE reachable VLESS and ONE reachable VMESS config
 *     (confirmed by the same censored-endpoint [Pinger] the real connect uses),
 *     it STOPS immediately and returns them — the page closes and the engine uses
 *     them. It does NOT keep scanning the rest.
 *   • It is hard time-boxed ([BUDGET_MS]) so it can never hang the page.
 *   • Fully exception-safe: a dead source / malformed line never crashes it.
 */
object ConnectivityProbe {

    private const val TAG = "ConnectivityProbe"

    /** Whole-probe wall-clock ceiling — the page never waits longer than this. */
    const val BUDGET_MS = 20_000L

    /** How many source feeds we fetch at once. */
    private const val FETCH_CONCURRENCY = 4

    /** How many candidate configs we ping at once. */
    private const val PING_CONCURRENCY = 6

    data class Result(
        val vless: ServerConfig?,
        val vmess: ServerConfig?
    ) {
        val ok: Boolean get() = vless != null || vmess != null
    }

    /**
     * Run the probe. Emits coarse progress (0..100) through [onProgress] for the
     * page's bar. Returns the first working vless/vmess pair it can confirm.
     */
    suspend fun probe(
        ctx: Context,
        onProgress: (percent: Int) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val res = withTimeoutOrNull(BUDGET_MS) { runProbe(ctx, onProgress) }
        res ?: Result(null, null)
    }

    private suspend fun runProbe(
        ctx: Context,
        onProgress: (Int) -> Unit
    ): Result = coroutineScope {
        onProgress(2)

        // Interleave sources so we hit vless / vmess feeds early. Walk in pairs.
        val vlessSrc = LiveSources.VLESS
        val vmessSrc = LiveSources.VMESS
        val pairs = maxOf(vlessSrc.size, vmessSrc.size)

        // Thread-safe result holders (java.util.concurrent atomic refs).
        val foundVless = java.util.concurrent.atomic.AtomicReference<ServerConfig?>(null)
        val foundVmess = java.util.concurrent.atomic.AtomicReference<ServerConfig?>(null)

        val fetchGate = Semaphore(FETCH_CONCURRENCY)
        val pingGate = Semaphore(PING_CONCURRENCY)
        val progressStep = AtomicInteger(2)

        // Test a handful of candidate links from one source; set found* on success.
        suspend fun testSource(src: LiveSources.Src, isVless: Boolean) {
            if ((isVless && foundVless.get() != null) || (!isVless && foundVmess.get() != null)) return
            val body = fetchGate.withPermit {
                if (!coroutineContext.isActive) return
                try { SourceFetcher.fetch(src.url) } catch (_: Throwable) { null }
            } ?: return
            val links = try { SourceFetcher.extractLinks(body, src.kind, limit = 24) } catch (_: Throwable) { emptyList() }
            // Ping a few candidates concurrently; first reachable wins.
            coroutineScope {
                for (link in links) {
                    if ((isVless && foundVless.get() != null) || (!isVless && foundVmess.get() != null)) break
                    launch {
                        if ((isVless && foundVless.get() != null) || (!isVless && foundVmess.get() != null)) return@launch
                        val cfg = try { ConfigParser.parseSingleSafe(link) } catch (_: Throwable) { null } ?: return@launch
                        val ms = pingGate.withPermit {
                            if (!coroutineContext.isActive) return@withPermit -1L
                            try { Pinger.ping(cfg) } catch (_: Throwable) { -1L }
                        }
                        if (ms in 1..8_000L) {
                            if (isVless) foundVless.compareAndSet(null, cfg)
                            else foundVmess.compareAndSet(null, cfg)
                        }
                    }
                }
            }
            val p = progressStep.addAndGet(3).coerceAtMost(95)
            onProgress(p)
        }

        // Walk source pairs until we have BOTH a vless and a vmess (or time out).
        outer@ for (i in 0 until pairs) {
            if (foundVless.get() != null && foundVmess.get() != null) break@outer
            val jobs = ArrayList<kotlinx.coroutines.Deferred<Unit>>()
            if (i < vlessSrc.size && foundVless.get() == null) {
                jobs.add(async { testSource(vlessSrc[i], isVless = true) })
            }
            if (i < vmessSrc.size && foundVmess.get() == null) {
                jobs.add(async { testSource(vmessSrc[i], isVless = false) })
            }
            jobs.forEach { runCatching { it.await() } }
        }

        onProgress(100)
        Log.i(TAG, "probe done: vless=${foundVless.get() != null}, vmess=${foundVmess.get() != null}")
        Result(foundVless.get(), foundVmess.get())
    }
}
