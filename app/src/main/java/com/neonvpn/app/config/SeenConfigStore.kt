package com.neonvpn.app.config

import android.content.Context

/**
 * v4.6 — PERSISTENT dedup memory (bounded, never bloats the cache).
 *
 * The app must "remember" every free config it has already added so a later
 * batch never re-adds a duplicate — WITHOUT the SharedPreferences growing without
 * bound. We therefore store only the compact canonical dedup key
 * ([ConfigParser.dedupKey]) of each config, and cap the set at [MAX_KEYS]
 * (oldest keys evicted FIFO). At ~40 bytes/key, [MAX_KEYS]=40 000 is ~1.6 MB —
 * comfortably small, and enough to cover roughly a month of Auto-Test churn.
 *
 * ── 30-DAY RESET ────────────────────────────────────────────────────────────
 *  Because the upstream feeds rotate continuously, after [RESET_INTERVAL_MS]
 *  (30 days) the whole free-config pipeline resets: the seen-set is cleared and
 *  the source cursor + Server-N counter restart from the first source, so a
 *  long-running install starts pulling fresh configs from the top again. The
 *  user's own saved My-Configs are NEVER touched by this reset.
 */
object SeenConfigStore {

    private const val PREFS = "pv_seen_v46"
    private const val KEY_SET = "seen_keys"
    private const val KEY_ORDER = "seen_order"      // insertion order for FIFO eviction
    private const val KEY_RESET_AT = "seen_reset_at"

    const val MAX_KEYS = 40_000
    const val RESET_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000   // 30 days

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Load the in-memory seen-set (a mutable copy). */
    @Synchronized
    fun load(ctx: Context): MutableSet<String> {
        val stored = prefs(ctx).getStringSet(KEY_SET, emptySet()) ?: emptySet()
        return HashSet(stored)
    }

    /**
     * Persist [keys] as the new seen-set, capped at [MAX_KEYS] (FIFO eviction of
     * the oldest keys). Called after each batch so the memory survives restarts.
     */
    @Synchronized
    fun save(ctx: Context, keys: Collection<String>) {
        val list = if (keys.size > MAX_KEYS) keys.toList().takeLast(MAX_KEYS) else keys.toList()
        prefs(ctx).edit().putStringSet(KEY_SET, HashSet(list)).apply()
    }

    /** Whether it is time to run the 30-day reset. */
    @Synchronized
    fun shouldReset(ctx: Context): Boolean {
        val p = prefs(ctx)
        val at = p.getLong(KEY_RESET_AT, 0L)
        if (at == 0L) {
            // first ever run: stamp now, do not reset.
            p.edit().putLong(KEY_RESET_AT, System.currentTimeMillis()).apply()
            return false
        }
        return System.currentTimeMillis() - at >= RESET_INTERVAL_MS
    }

    /** Clear the seen-set and restamp the reset clock. */
    @Synchronized
    fun performReset(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_SET)
            .remove(KEY_ORDER)
            .putLong(KEY_RESET_AT, System.currentTimeMillis())
            .apply()
    }
}
