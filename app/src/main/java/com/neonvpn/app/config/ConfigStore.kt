package com.neonvpn.app.config

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists the list of saved servers and the currently selected one.
 */
class ConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("neonvpn_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getServers(): MutableList<ServerConfig> = synchronized(LOCK) {
        val json = prefs.getString(KEY_SERVERS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<ServerConfig>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveServers(list: List<ServerConfig>) = synchronized(LOCK) {
        // commit() (not apply()) so a burst of concurrent writers can't interleave
        // and lose entries; the whole read-modify-write is guarded by LOCK.
        prefs.edit().putString(KEY_SERVERS, gson.toJson(list)).commit()
    }

    /**
     * Thread-safe additive merge. The ENTIRE read-modify-write is performed under
     * [LOCK] so dozens of concurrent Auto-Test coroutines can each add their
     * working config without clobbering each other (the old apply()/separate
     * get()+save() pattern raced and silently dropped configs — and on some
     * devices corrupted the prefs file, which is what crashed Auto Test).
     */
    fun addServers(newOnes: List<ServerConfig>): Int = synchronized(LOCK) {
        if (newOnes.isEmpty()) return 0
        val current = getServersLocked()

        // Build the existing per-location counts + a fast dedup set so a paste of
        // many configs (which may contain 10+ clones of one server) never bloats
        // the list with duplicates. We allow at most MAX_PER_LOCATION configs
        // that resolve to the same IP / SNI host + port.
        val locationCounts = HashMap<String, Int>()
        val dedupKeys = HashSet<String>()
        for (c in current) {
            locationCounts.merge(ConfigParser.locationKey(c), 1, Int::plus)
            dedupKeys.add(ConfigParser.dedupKey(c))
        }

        var added = 0
        for (s in newOnes) {
            // de-dup by stable identity (more reliable than rawLink, which can
            // differ only by remark for the very same endpoint)
            val key = ConfigParser.dedupKey(s)
            if (key in dedupKeys) continue
            if (current.any { it.rawLink == s.rawLink }) continue

            val loc = ConfigParser.locationKey(s)
            val have = locationCounts[loc] ?: 0
            if (have >= ConfigFetcher.MAX_PER_LOCATION) continue

            current.add(s)
            dedupKeys.add(key)
            locationCounts[loc] = have + 1
            added++
        }
        if (added > 0) saveServersLocked(current)
        return added
    }

    // Internal helpers used while already holding LOCK (avoid re-entrant sync).
    private fun getServersLocked(): MutableList<ServerConfig> {
        val json = prefs.getString(KEY_SERVERS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<ServerConfig>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveServersLocked(list: List<ServerConfig>) {
        prefs.edit().putString(KEY_SERVERS, gson.toJson(list)).commit()
    }

    fun removeServer(id: String) = synchronized(LOCK) {
        val current = getServersLocked()
        current.removeAll { it.id == id }
        saveServersLocked(current)
        if (getSelectedId() == id) {
            setSelectedId(current.firstOrNull()?.id)
        }
    }

    /** Remove many configs at once (group delete). Returns count removed. */
    fun removeServers(ids: Set<String>): Int = synchronized(LOCK) {
        if (ids.isEmpty()) return 0
        val current = getServersLocked()
        val before = current.size
        current.removeAll { it.id in ids }
        saveServersLocked(current)
        if (getSelectedId() in ids) {
            setSelectedId(current.firstOrNull()?.id)
        }
        return before - current.size
    }

    fun getSelectedId(): String? = prefs.getString(KEY_SELECTED, null)

    fun setSelectedId(id: String?) {
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    fun getSelected(): ServerConfig? {
        val id = getSelectedId() ?: return getServers().firstOrNull()
        return getServers().firstOrNull { it.id == id } ?: getServers().firstOrNull()
    }

    companion object {
        private const val KEY_SERVERS = "servers"
        private const val KEY_SELECTED = "selected_id"
        // Process-wide monitor guarding every read-modify-write of the servers
        // list. Shared by every ConfigStore instance (they all wrap the same
        // SharedPreferences file) so concurrent writers serialise correctly.
        private val LOCK = Any()
    }
}
