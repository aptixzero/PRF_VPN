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

    /** Starts the tun2socks loop. Blocks until the tunnel stops. */
    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    external fun TProxyStopService()

    @JvmStatic
    external fun TProxyGetStats(): LongArray?
}
