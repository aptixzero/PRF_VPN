package com.neonvpn.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global uncaught-exception handler.
 *
 * Why this exists: the app used to hard-crash the FIRST time the user tapped
 * CONNECT — typically the moment the VPN-permission consent dialog returned, on
 * a thread that threw before any try/catch could see it. A raw uncaught
 * exception there kills the whole process with the ugly "Professor VPN keeps
 * stopping" system dialog.
 *
 * This handler catches EVERY uncaught throwable on EVERY thread, writes a
 * timestamped stack trace to the app's private files dir (so it can be inspected
 * later), and then:
 *   • for the VPN worker / tun2socks / watchdog threads, it SWALLOWS the crash
 *     and lets the process keep living (the VpnService already has its own
 *     crash-guards + auto-recovery, so the UI survives), and
 *   • for the main thread, it logs and defers to the previous default handler so
 *     Android still does the right thing rather than leaving a frozen black UI.
 *
 * The net effect: tapping CONNECT can no longer take the whole app down — at
 * worst a single connection attempt fails gracefully and the user can retry.
 */
class CrashHandler private constructor(
    private val appContext: Context,
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val trace = StringWriter().also { sw ->
                PrintWriter(sw).use { e.printStackTrace(it) }
            }.toString()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            Log.e(TAG, "Uncaught on thread '${t.name}':\n$trace")
            saveCrash(stamp, t.name, sanitize(trace))
        } catch (_: Throwable) {
            // never let the crash handler itself crash
        }

        // v4.7 — OOM first-aid. The "crash when the next 240-config batch is
        // appended" bug ultimately surfaced as OutOfMemoryError on low-RAM
        // phones. When ANY thread hits OOM we immediately shed the biggest
        // shedable loads (stop the Auto-Test engine, drop cached ping states,
        // force a GC) so the process has headroom to survive instead of dying.
        if (isOom(e)) {
            try {
                Log.w(TAG, "OutOfMemory detected — shedding load (stop AutoTest, prune caches)")
                runCatching { com.neonvpn.app.config.AutoTestEngine.stop() }
                runCatching { com.neonvpn.app.config.PingService.prune(emptySet()) }
                runCatching { System.gc() }
            } catch (_: Throwable) {}
        }

        // Threads we know how to survive: keep the process alive so the user's
        // app/UI doesn't die from a background connection / test hiccup. v4.2
        // widens this net so EVERY background worker (coroutine dispatchers,
        // OkHttp, the Auto-Test engine, ping sweeps, timers, GL/animation
        // threads, etc.) is swallowed — the process only ever dies from a true
        // main-thread crash, which we then recover from by relaunching.
        val name = t.name
        val survivable = name in SURVIVABLE_THREADS ||
            name.startsWith("vpn-") ||
            name.startsWith("tun2socks") ||
            name.startsWith("stats") ||
            name.startsWith("watchdog") ||
            name.startsWith("DefaultDispatcher") ||
            name.startsWith("Default Dispatcher") ||
            name.startsWith("kotlinx.coroutines") ||
            name.startsWith("pool-") ||
            name.startsWith("OkHttp") ||
            name.startsWith("Okio") ||
            name.startsWith("AsyncTask") ||
            name.startsWith("Thread-") ||
            name.startsWith("GLThread") ||
            name.startsWith("RenderThread") ||
            name.startsWith("queued-work") ||
            name.startsWith("Timer-") ||
            name.contains("Worker", ignoreCase = true) ||
            name.contains("ping", ignoreCase = true) ||
            name.contains("autotest", ignoreCase = true) ||
            // Catch-all: any thread that is NOT the main/UI thread is safe to
            // swallow — a background crash must never take the whole app down.
            !isMainThread(t)

        if (survivable) {
            Log.w(TAG, "swallowed crash on survivable thread '${t.name}' — process kept alive")
            return
        }

        // ── MAIN / UI thread crash ──────────────────────────────────────────
        // v4.1: the previous behaviour deferred to the platform default handler,
        // which surfaced the dreaded "Professor VPN keeps stopping" dialog and
        // left the user with an app that "won't open". Instead we now schedule a
        // clean relaunch of the SplashActivity a moment from now and then kill
        // the current (broken) process. To the user this looks like the app
        // simply reopening itself rather than dying — far friendlier, and it
        // recovers from a one-off bad state (e.g. a corrupt cached config, a
        // transient inflation failure) without the user having to do anything.
        //
        // A guard prevents a crash-loop: if we relaunched very recently we let
        // the platform handler take over so we don't spin forever.
        val now = System.currentTimeMillis()
        val recentlyRelaunched = (now - lastRelaunchAt) < RELAUNCH_GUARD_MS
        if (!recentlyRelaunched) {
            lastRelaunchAt = now
            val scheduled = try { scheduleRestart() } catch (_: Throwable) { false }
            if (scheduled) {
                Log.w(TAG, "main-thread crash — relaunching app cleanly")
                // Tear down THIS broken process; the AlarmManager will reopen us.
                try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Throwable) {}
                kotlin.system.exitProcess(10)
                return
            }
        }

        // Fallback: defer to the platform default so Android can clean up.
        previous?.uncaughtException(t, e)
    }

    /**
     * Schedule a one-shot relaunch of the app (SplashActivity) ~400ms in the
     * future via AlarmManager, so it survives the imminent process kill. Returns
     * true if the alarm was queued.
     */
    private fun scheduleRestart(): Boolean {
        val pm = appContext.packageManager ?: return false
        val launch = pm.getLaunchIntentForPackage(appContext.packageName) ?: return false
        launch.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        val flags = android.app.PendingIntent.FLAG_ONE_SHOT or
            (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = android.app.PendingIntent.getActivity(appContext, 0xC2A5, launch, flags)
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            ?: return false
        am.set(
            android.app.AlarmManager.RTC,
            System.currentTimeMillis() + 400L,
            pi
        )
        return true
    }

    private fun saveCrash(stamp: String, thread: String, trace: String) {
        try {
            // §4.5 — sanitized crash logs live in filesDir/logs/.
            val dir = File(appContext.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            // keep only the latest few logs
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach {
                try { it.delete() } catch (_: Throwable) {}
            }
            File(dir, "crash_${System.currentTimeMillis()}.txt")
                .writeText("[$stamp] thread=$thread\n\n$trace")
        } catch (_: Throwable) {
        }
    }

    /**
     * §4.5 privacy rule — never persist anything that could leak a user's
     * server. Strips config share-links, IPv4/IPv6 literals, UUIDs and bare
     * host:port pairs from a stack trace before it is written to disk.
     */
    private fun sanitize(raw: String): String {
        var s = raw
        s = LINK_RX.replace(s, "[redacted-link]")
        s = UUID_RX.replace(s, "[redacted-uuid]")
        s = IPV4_RX.replace(s, "[redacted-ip]")
        s = IPV6_RX.replace(s, "[redacted-ip6]")
        s = HOSTPORT_RX.replace(s, "[redacted-host]")
        return s
    }

    /** True if [e] is an OutOfMemoryError anywhere in its cause chain. */
    private fun isOom(e: Throwable): Boolean {
        var cur: Throwable? = e
        var hops = 0
        while (cur != null && hops < 8) {
            if (cur is OutOfMemoryError) return true
            cur = cur.cause
            hops++
        }
        return false
    }

    /** True only for the app's main/UI (Looper) thread. */
    private fun isMainThread(t: Thread): Boolean = try {
        t === android.os.Looper.getMainLooper().thread
    } catch (_: Throwable) {
        // If we can't tell, err on the side of treating it as main so we don't
        // accidentally swallow a real UI crash without the recovery relaunch.
        t.id == 1L
    }

    companion object {
        private const val TAG = "CrashHandler"
        private val SURVIVABLE_THREADS = setOf("vpn-start", "tun2socks", "stats", "watchdog")

        // Crash-loop guard: if the main thread crashed and we relaunched less
        // than this long ago, don't relaunch again (let the platform handle it)
        // so a deterministic startup crash can't spin the process forever.
        private const val RELAUNCH_GUARD_MS = 10_000L
        @Volatile private var lastRelaunchAt = 0L

        // Sanitization patterns (see sanitize()).
        private val LINK_RX = Regex("""\b(?:vless|vmess|trojan|ss)://[^\s'"<>]+""")
        private val UUID_RX = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
        private val IPV4_RX = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
        private val IPV6_RX = Regex("""\b(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\b""")
        private val HOSTPORT_RX = Regex("""\b[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}:\d{2,5}\b""")

        fun install(context: Context) {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(context.applicationContext, prev)
            )
        }
    }
}
