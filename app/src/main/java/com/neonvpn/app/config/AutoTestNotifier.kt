package com.neonvpn.app.config

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.neonvpn.app.R

/**
 * v4.2 — AUTO TEST status notification.
 *
 * The brief requires that whenever Auto Test is running, a banner/notification
 * appears at the TOP of the user's phone telling them "Auto Test is ON and
 * testing". This object owns a single ongoing notification that is posted when
 * the engine starts a run and cleared when it stops. It is intentionally
 * lightweight (no foreground service of its own — the engine already runs in an
 * app-scoped coroutine) and every call is wrapped so a notification failure can
 * never crash the test loop, even on stripped-down / weak devices that block
 * notifications.
 */
object AutoTestNotifier {

    private const val TAG = "AutoTestNotifier"
    private const val CHANNEL_ID = "professorvpn_autotest"
    private const val NOTIF_ID = 4242

    /** Post (or update) the ongoing "Auto Test running" heads-up notification. */
    fun show(ctx: Context, text: String) {
        runCatching {
            val app = ctx.applicationContext
            ensureChannel(app)
            val mgr = app.getSystemService(NotificationManager::class.java) ?: return
            mgr.notify(NOTIF_ID, build(app, text))
        }.onFailure { Log.w(TAG, "show failed: ${it.message}") }
    }

    /** Remove the notification when Auto Test stops. */
    fun clear(ctx: Context) {
        runCatching {
            val app = ctx.applicationContext
            val mgr = app.getSystemService(NotificationManager::class.java) ?: return
            mgr.cancel(NOTIF_ID)
        }.onFailure { Log.w(TAG, "clear failed: ${it.message}") }
    }

    private fun build(ctx: Context, text: String): android.app.Notification {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: Intent()
        val pi = PendingIntent.getActivity(
            ctx, 0xA77E, launch,
            (PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.autotest_notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_ping)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            // HIGH so it surfaces as a heads-up banner at the top of the screen
            // the first time, then quietly stays in the shade while testing.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Auto Test",
                // HIGH importance => heads-up banner appears at the top of the
                // screen when Auto Test starts (the requested top-of-screen alert).
                NotificationManager.IMPORTANCE_HIGH
            )
            ch.setShowBadge(false)
            ch.enableVibration(false)
            mgr.createNotificationChannel(ch)
        }
    }
}
