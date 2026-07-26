package com.v2ray.ang.service

/**
 * JNI bridge to libhev-socks5-tunnel.so (tun2socks).
 *
 * IMPORTANT: the native library registers its JNI methods (via RegisterNatives
 * in JNI_OnLoad) against the *exact* class name
 *   com/v2ray/ang/service/TProxyService
 * with methods TProxyStartService / TProxyStopService / TProxyGetStats.
 * That is why this single class lives in the com.v2ray.ang.service package —
 * it must match the symbols baked into the prebuilt .so. The rest of the app
 * lives under com.neonvpn.app.
 */
object TProxyService {

    /**
     * v4.1: whether the native tun2socks library loaded successfully. We load it
     * defensively (instead of an unguarded `System.loadLibrary` in init{}) so a
     * missing / ABI-mismatched .so can NEVER take the whole process down with an
     * ExceptionInInitializerError the first time this class is touched (a real
     * launch-crash source on exotic devices). If it fails to load, the VPN core
     * still works through the Xray SOCKS inbound; only the native byte-counter
     * fallback is unavailable.
     */
    @JvmStatic
    @Volatile
    var nativeAvailable: Boolean = false
        private set

    init {
        nativeAvailable = try {
            System.loadLibrary("hev-socks5-tunnel")
            true
        } catch (t: Throwable) {
            android.util.Log.e("TProxyService", "native load failed: ${t.message}")
            false
        }
    }

    /** No-op that just forces the native library (and its JNI_OnLoad) to load
     *  eagerly — called from the splash screen so the first connect is warm.
     *  Fully crash-safe: any class-init failure is swallowed. */
    @JvmStatic
    fun touch() {
        // Referencing nativeAvailable forces class init (the load above) to run,
        // but any failure has already been caught inside init{}.
        if (!nativeAvailable) android.util.Log.w("TProxyService", "native unavailable")
    }

    /**
     * v6.5 — RUN-STATE TRACKING (the reconnect / "stale cache" fix).
     *
     * The native library is a PROCESS-WIDE singleton with one global session
     * table. Calling TProxyStartService() while a previous session is still
     * tearing down leaves the tunnel wedged: the first connect works, the 2nd
     * and 3rd silently carry no traffic (the classic "it shows a ping, I press
     * connect, nothing attaches" bug). Nothing in v6.4 prevented that overlap.
     *
     * From v6.5 every start/stop goes through [startBlocking] / [stopAndWait],
     * which serialise on one lock and never let two sessions coexist.
     */
    private val nativeLock = Any()

    @JvmStatic
    @Volatile
    var nativeRunning: Boolean = false
        private set

    /** Monotonic id of the current native session; bumped on every start. */
    @JvmStatic
    @Volatile
    var sessionId: Long = 0L
        private set

    /**
     * Starts the tun2socks loop and BLOCKS until the tunnel stops (that is the
     * native contract). Guaranteed to run at most one session at a time: if a
     * previous session is still alive it is stopped and drained first.
     *
     * @return true when the loop actually ran, false when the native library is
     *         unavailable or another thread already owns the session.
     */
    @JvmStatic
    fun startBlocking(configPath: String, fd: Int): Boolean {
        if (!nativeAvailable) {
            android.util.Log.w("TProxyService", "startBlocking: native unavailable")
            return false
        }
        // Never overlap sessions — drain any leftover first.
        if (nativeRunning) {
            android.util.Log.w("TProxyService", "startBlocking: draining previous session")
            stopAndWait(1500)
        }
        val mySession: Long
        synchronized(nativeLock) {
            nativeRunning = true
            sessionId += 1
            mySession = sessionId
        }
        return try {
            TProxyStartService(configPath, fd)
            true
        } catch (t: Throwable) {
            android.util.Log.e("TProxyService", "startBlocking failed: ${t.message}")
            false
        } finally {
            synchronized(nativeLock) {
                // Only clear the flag if we still own the session (a newer start
                // may already have taken over).
                if (sessionId == mySession) nativeRunning = false
            }
        }
    }

    /**
     * Idempotent stop that WAITS for the native loop to actually return before
     * handing control back. Without this wait the next connect races the old
     * session's teardown — the root cause of the reconnect bug.
     *
     * @param timeoutMs how long to wait for the loop to unwind.
     * @return true when the session is confirmed down.
     */
    @JvmStatic
    fun stopAndWait(timeoutMs: Long = 2500): Boolean {
        if (!nativeAvailable) return true
        // NOTE: deliberately does NOT hold nativeLock while polling — the
        // finishing start-thread needs that lock to clear nativeRunning.
        try { TProxyStopService() } catch (t: Throwable) {
            android.util.Log.w("TProxyService", "stop threw: ${t.message}")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (nativeRunning && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(25) } catch (_: InterruptedException) { break }
        }
        if (nativeRunning) {
            android.util.Log.w("TProxyService", "native loop did not unwind in ${timeoutMs}ms; forcing flag")
            // The loop is unresponsive; clear the flag so a fresh session can be
            // created on a brand-new fd rather than blocking the user forever.
            synchronized(nativeLock) { nativeRunning = false }
        }
        return true
    }

    /** Starts the tun2socks loop. Blocks until the tunnel stops. */
    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    external fun TProxyStopService()

    @JvmStatic
    external fun TProxyGetStats(): LongArray?
}
