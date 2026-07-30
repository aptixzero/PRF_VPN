package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v6.9 FREE-CONFIG SOURCE — reads directly from the 70 LIVE feeds in [LiveSources].
 *
 * ── WHAT v6.9 CHANGED, AND WHY ──────────────────────────────────────────────
 * The v6.8 implementation walked source feeds **strictly one at a time**. On an
 * Iranian mobile link where most GitHub raw hosts are slow or dead, filling the
 * 120/120 quota meant up to 12 sequential fetches PER KIND, each able to burn a
 * full timeout — worst case several minutes, which is exactly the ">5 minutes
 * and stuck at 30%" the brief complains about.
 *
 * v6.9 fetches sources in **PARALLEL WAVES** ([WAVE] feeds at a time). A wave
 * costs one timeout, not eight, so a press that used to take minutes now takes
 * seconds. Waves keep launching until the quota is full or the source list is
 * exhausted.
 *
 * ── THE "NEXT 240 NEVER ARRIVES" BUG ────────────────────────────────────────
 * v6.8 bonded the user to ONE source and then re-read that same feed on every
 * subsequent press. Once every link in that feed was already in [SeenConfigStore]
 * the press returned EMPTY forever, and [AutoTestEngine] eventually auto-stopped.
 * v6.9 fixes it three ways:
 *   1. The bond is only a *hint*: it is placed FIRST in the first wave, but the
 *      wave always contains other feeds too, so a stale bond can never starve us.
 *   2. The cursor ALWAYS advances past every source consumed by this press, so
 *      the next press reads different feeds by construction.
 *   3. If a press under-fills while the network was demonstrably reachable, the
 *      dedup memory is recycled ([SeenConfigStore.performReset]) and ONE retry
 *      pass runs. That guarantees a non-empty next-240 as long as any feed is up.
 *
 * ── 240-PER-PRESS: 120 VLESS + 120 VMESS ────────────────────────────────────
 * Every batch collects up to [VLESS_PER_PRESS] (=120) unique VLESS and
 * [VMESS_PER_PRESS] (=120) unique VMESS configs and INTERLEAVES them, so the
 * resulting list is an even half-and-half mix. Both kinds are now collected
 * CONCURRENTLY (they used to run one after the other).
 *
 * ── MEMORY + 30-DAY RESET ───────────────────────────────────────────────────
 * Dedup is by canonical key ([ConfigParser.dedupKey]) persisted in
 * [SeenConfigStore] so a config that was ever added is never re-added — while the
 * stored set stays bounded. After 30 days the whole pipeline resets; the user's
 * saved My-Configs are never touched.
 *
 * ── NEUTRAL NAMING ──────────────────────────────────────────────────────────
 * Configs are renamed to a generic "Server N" (monotonic, persisted). The real
 * feed name / channel branding is NEVER shown.
 *
 * ── NO INTERMEDIARIES ───────────────────────────────────────────────────────
 * Every fetch goes through [SourceFetcher] → [com.neonvpn.app.net.DirectHttp],
 * which dials the ORIGIN directly with `Proxy.NO_PROXY` and Cloudflare-DoH name
 * resolution. No mirror, no CDN forwarder, no relay.
 */
object FreeConfigSource {

    private const val TAG = "FreeConfigSource"

    const val VLESS_PER_PRESS = 120
    const val VMESS_PER_PRESS = 120

    /** Total configs a single press yields. */
    const val BATCH_PER_PRESS = VLESS_PER_PRESS + VMESS_PER_PRESS   // 240

    private const val PREFS = "free_live_v46"
    private const val KEY_VLESS_CURSOR = "vless_src_cursor"
    private const val KEY_VMESS_CURSOR = "vmess_src_cursor"
    private const val KEY_NAME_COUNTER = "name_counter"
    private const val KEY_SEEDED = "seeded"

    /**
     * How many feeds of one kind are fetched CONCURRENTLY. 8 in-flight requests is
     * comfortable for OkHttp's 24-connection pool (two kinds × 8 = 16) and turns
     * "eight sequential timeouts" into "one timeout".
     */
    private const val WAVE = 8

    /**
     * Max source feeds walked per kind in one press. v6.9 — raised to 24 (there
     * are 35 feeds per kind) because waves make extra feeds nearly free: a wave
     * of 8 dead feeds costs the same wall-clock as one dead feed.
     */
    private const val MAX_SOURCES_PER_PRESS = 24

    /** Wall-clock ceiling for one whole wave. Keeps a press bounded and snappy. */
    private const val WAVE_BUDGET_MS = 7_000L

    /** Wall-clock ceiling for collecting one kind (all its waves together). */
    private const val KIND_BUDGET_MS = 22_000L

    data class Batch(
        val configs: List<ServerConfig>,   // already renamed Server N, interleaved
        val foundRaw: Int,
        val reachedEnd: Boolean,
        /**
         * True if AT LEAST ONE source feed was successfully opened during this
         * press (regardless of whether it yielded NEW configs). Lets a caller tell
         * "empty because offline" (false) apart from "empty because everything we
         * saw was already-seen" (true).
         */
        val reachedSource: Boolean = false
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Ensure first-launch state + honour the 30-day reset. */
    suspend fun ensureFreshState(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_SEEDED, false)) {
            p.edit().putInt(KEY_VLESS_CURSOR, 0).putInt(KEY_VMESS_CURSOR, 0)
                .putInt(KEY_NAME_COUNTER, 0).putBoolean(KEY_SEEDED, true).apply()
        }
        // 30-day reset: clear seen memory + restart cursors from the first source.
        if (SeenConfigStore.shouldReset(ctx)) {
            SeenConfigStore.performReset(ctx)
            p.edit().putInt(KEY_VLESS_CURSOR, 0).putInt(KEY_VMESS_CURSOR, 0)
                .putInt(KEY_NAME_COUNTER, 0).apply()
            ConnectedSourceStore.clear(ctx)
            Log.i(TAG, "30-day reset performed — restarting from source #1")
        }
    }

    fun peekNextServerNumber(ctx: Context): Int = prefs(ctx).getInt(KEY_NAME_COUNTER, 0) + 1

    /**
     * Pull the next 120 VLESS + 120 VMESS unique configs, INTERLEAVED.
     *
     * @param seenKeys in-memory dedup set (seeded from [SeenConfigStore]); this
     *                 method also persists the union back to [SeenConfigStore].
     */
    suspend fun nextBatch(
        ctx: Context,
        startIndex: Int = 0,                  // legacy, ignored
        seenKeys: MutableSet<String>,
        onChunk: (addedThisPress: Int, target: Int, status: String) -> Unit = { _, _, _ -> }
    ): Batch = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        var serverIndex = p.getInt(KEY_NAME_COUNTER, 0).coerceAtLeast(0)

        onChunk(0, BATCH_PER_PRESS, "Loading configs…")

        val vlessOut = ArrayList<ServerConfig>(VLESS_PER_PRESS)
        val vmessOut = ArrayList<ServerConfig>(VMESS_PER_PRESS)
        val reached = java.util.concurrent.atomic.AtomicBoolean(false)

        // The sticky bond is a HINT only — it is tried first inside the first
        // wave, but never alone, so an exhausted bond cannot starve the press.
        val bondedVless = ConnectedSourceStore.vlessSource(ctx)
        val bondedVmess = ConnectedSourceStore.vmessSource(ctx)
        val vlessStart = p.getInt(KEY_VLESS_CURSOR, 0)
        val vmessStart = p.getInt(KEY_VMESS_CURSOR, 0)

        val vlessHitSrc = java.util.concurrent.atomic.AtomicInteger(-1)
        val vmessHitSrc = java.util.concurrent.atomic.AtomicInteger(-1)

        // ── Both kinds in PARALLEL (v6.8 ran them back-to-back) ─────────────
        var vlessCursor = vlessStart
        var vmessCursor = vmessStart
        coroutineScope {
            val a = async {
                vlessCursor = collectKind(
                    LiveSources.VLESS, vlessStart, bondedVless,
                    VLESS_PER_PRESS, seenKeys, vlessOut, reached, vlessHitSrc
                ) { got -> onChunk(got + vmessOut.size, BATCH_PER_PRESS, "Collecting… ${got + vmessOut.size}/$BATCH_PER_PRESS") }
            }
            val b = async {
                vmessCursor = collectKind(
                    LiveSources.VMESS, vmessStart, bondedVmess,
                    VMESS_PER_PRESS, seenKeys, vmessOut, reached, vmessHitSrc
                ) { got -> onChunk(got + vlessOut.size, BATCH_PER_PRESS, "Collecting… ${got + vlessOut.size}/$BATCH_PER_PRESS") }
            }
            listOf(a, b).awaitAll()
        }

        onChunk(vlessOut.size + vmessOut.size, BATCH_PER_PRESS,
            "Found ${vlessOut.size + vmessOut.size} configs")

        // ── RECYCLE-AND-RETRY: guarantees the "next 240" actually arrives ────
        // If we under-filled badly while the feeds were demonstrably reachable,
        // every fresh link we could see was already in the seen-memory. Recycle
        // that memory once and run a second pass so the user still gets configs
        // instead of an empty list + an auto-stopped engine.
        val got = vlessOut.size + vmessOut.size
        if (reached.get() && got < BATCH_PER_PRESS / 2) {
            Log.i(TAG, "under-filled ($got/$BATCH_PER_PRESS) with feeds reachable → recycling seen-memory and retrying")
            onChunk(got, BATCH_PER_PRESS, "Refreshing pool…")
            SeenConfigStore.performReset(ctx)
            seenKeys.clear()
            // Keep what we already collected out of the retry's way.
            vlessOut.forEach { seenKeys.add(ConfigParser.dedupKey(it)) }
            vmessOut.forEach { seenKeys.add(ConfigParser.dedupKey(it)) }
            ConnectedSourceStore.clear(ctx)
            SourceFetcher.invalidate()
            coroutineScope {
                val a = async {
                    vlessCursor = collectKind(
                        LiveSources.VLESS, vlessCursor, -1,
                        VLESS_PER_PRESS, seenKeys, vlessOut, reached, vlessHitSrc
                    ) { g -> onChunk(g + vmessOut.size, BATCH_PER_PRESS, "Refreshing… ${g + vmessOut.size}/$BATCH_PER_PRESS") }
                }
                val b = async {
                    vmessCursor = collectKind(
                        LiveSources.VMESS, vmessCursor, -1,
                        VMESS_PER_PRESS, seenKeys, vmessOut, reached, vmessHitSrc
                    ) { g -> onChunk(g + vlessOut.size, BATCH_PER_PRESS, "Refreshing… ${g + vlessOut.size}/$BATCH_PER_PRESS") }
                }
                listOf(a, b).awaitAll()
            }
        }

        // Bond to the first source that yielded configs this press (hint for the
        // NEXT press's first wave only — it is never used exclusively).
        if (vlessHitSrc.get() >= 0) ConnectedSourceStore.setVlessSource(ctx, vlessHitSrc.get())
        if (vmessHitSrc.get() >= 0) ConnectedSourceStore.setVmessSource(ctx, vmessHitSrc.get())

        // Interleave vless / vmess so the mix is even (vless, vmess, vless …).
        val interleaved = ArrayList<ServerConfig>(vlessOut.size + vmessOut.size)
        val max = maxOf(vlessOut.size, vmessOut.size)
        for (i in 0 until max) {
            if (i < vlessOut.size) interleaved.add(vlessOut[i])
            if (i < vmessOut.size) interleaved.add(vmessOut[i])
        }

        // Assign monotonic Server N names in final (interleaved) order. The name is
        // ALSO baked into the link's #remark so it survives being copied elsewhere.
        val named = interleaved.map { cfg ->
            serverIndex++
            val name = "${ConfigFetcher.GENERIC_PREFIX} $serverIndex"
            val relinked = ConfigParser.rewriteRemark(cfg.rawLink, name)
            cfg.copy(remark = name, rawLink = relinked)
        }

        p.edit()
            .putInt(KEY_VLESS_CURSOR, vlessCursor)
            .putInt(KEY_VMESS_CURSOR, vmessCursor)
            .putInt(KEY_NAME_COUNTER, serverIndex)
            .apply()
        SeenConfigStore.save(ctx, seenKeys)

        onChunk(named.size, BATCH_PER_PRESS, "Added ${named.size} configs")
        Log.i(TAG, "press: +${named.size} (vless=${vlessOut.size}, vmess=${vmessOut.size}, " +
            "vlessCursor→$vlessCursor, vmessCursor→$vmessCursor, reached=${reached.get()})")
        Batch(named, named.size, reachedEnd = false, reachedSource = reached.get())
    }

    /**
     * Fill [out] with up to [need] unique configs of one [kind], fetching feeds in
     * PARALLEL WAVES of [WAVE] starting at [startCursor] (wrapping), visiting at
     * most [MAX_SOURCES_PER_PRESS] feeds. Returns the NEXT cursor to resume from —
     * always past every source this call consumed, so the following press reads
     * different feeds.
     *
     * @param bondHint if >= 0, this source index is placed FIRST in the first wave.
     * @param hitSrc   set to the index of the first source that yields a fresh config.
     */
    private suspend fun collectKind(
        sources: List<LiveSources.Src>,
        startCursor: Int,
        bondHint: Int,
        need: Int,
        seenKeys: MutableSet<String>,
        out: MutableList<ServerConfig>,
        reached: java.util.concurrent.atomic.AtomicBoolean,
        hitSrc: java.util.concurrent.atomic.AtomicInteger?,
        onProgress: (Int) -> Unit
    ): Int {
        if (sources.isEmpty()) return startCursor
        val n = sources.size
        var cursor = ((startCursor % n) + n) % n
        var walked = 0
        val deadline = System.currentTimeMillis() + KIND_BUDGET_MS

        // Build the visiting order: bond hint first (if any), then the cursor walk.
        val order = ArrayList<Int>(MAX_SOURCES_PER_PRESS)
        if (bondHint in 0 until n) order.add(bondHint)
        var c = cursor
        while (order.size < MAX_SOURCES_PER_PRESS.coerceAtMost(n)) {
            if (c !in order) order.add(c)
            c = (c + 1) % n
            if (order.size >= n) break
        }

        var oi = 0
        while (out.size < need && oi < order.size && System.currentTimeMillis() < deadline) {
            val slice = ArrayList<Int>(WAVE)
            while (slice.size < WAVE && oi < order.size) { slice.add(order[oi]); oi++ }
            if (slice.isEmpty()) break

            // ── ONE WAVE: all these feeds are fetched at the same time ───────
            val bodies: List<Pair<Int, String?>> = withTimeoutOrNull(WAVE_BUDGET_MS) {
                coroutineScope {
                    slice.map { idx ->
                        async {
                            val body = try { SourceFetcher.fetch(sources[idx].url) } catch (_: Throwable) { null }
                            idx to body
                        }
                    }.awaitAll()
                }
            } ?: emptyList()

            // Drain the wave in visiting order so results stay deterministic.
            for ((idx, body) in bodies.sortedBy { order.indexOf(it.first) }) {
                walked++
                if (body.isNullOrBlank()) continue
                reached.set(true)
                if (out.size >= need) continue
                val src = sources[idx]
                val links = try { SourceFetcher.extractLinks(body, src.kind) } catch (_: Throwable) { emptyList() }
                val before = out.size
                for (link in links) {
                    if (out.size >= need) break
                    val cfg = try { ConfigParser.parseSingleSafe(link) } catch (_: Throwable) { null } ?: continue
                    if (cfg.protocol != "vless" && cfg.protocol != "vmess") continue
                    val key = ConfigParser.dedupKey(cfg)
                    if (!seenKeys.add(key)) continue
                    out.add(cfg)
                }
                if (hitSrc != null && out.size > before && hitSrc.get() < 0) hitSrc.set(idx)
            }
            onProgress(out.size)
            // Resume AFTER the furthest source this wave touched.
            cursor = ((slice.maxOrNull() ?: cursor) + 1) % n
        }
        Log.d(TAG, "collectKind: ${out.size}/$need from $walked feeds, next cursor=$cursor")
        return cursor
    }
}
