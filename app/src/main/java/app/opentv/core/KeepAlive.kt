/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.opentv.MainActivity
import app.opentv.R

/**
 * Always-on mode. StreamFlow keeps running until the user leaves it through "Exit" — not when the
 * TV is switched off, the box goes to standby, Android tidies up memory, or the app crashes:
 *
 *  - a small foreground service keeps the process alive, so Android doesn't kill it in standby;
 *  - when the screen comes back on, StreamFlow is brought back to the front (with "resume last
 *    channel" on, the last channel starts again);
 *  - after a box restart it starts on its own;
 *  - after a crash it reopens itself within a couple of seconds (see [CrashReport]).
 *
 * Only the user's own "Exit" ends it; the next normal launch switches it back on.
 */
object KeepAlive {
    private const val PREFS = "streamflow_keepalive"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_EXITED = "user_exited"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    /** True while StreamFlow should stay up: always-on is enabled and the user hasn't chosen Exit. */
    fun shouldRun(context: Context) = isEnabled(context) && !prefs(context).getBoolean(KEY_EXITED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) start(context) else stop(context)
    }

    /** The app was opened: always-on applies again. Call from the activity. */
    fun onAppOpened(context: Context) {
        prefs(context).edit().putBoolean(KEY_EXITED, false).commit()
        if (isEnabled(context)) start(context)
    }

    /** The user chose Exit: stop for good until the next launch. */
    fun onUserExit(context: Context) {
        prefs(context).edit().putBoolean(KEY_EXITED, true).commit()
        stop(context)
    }

    fun start(context: Context) {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java)) }
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
    }

    fun bringToFront(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
        }
    }
}

class KeepAliveService : Service() {

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // TV on again: show StreamFlow, not the launcher.
            if (KeepAlive.shouldRun(context)) KeepAlive.bringToFront(context)
        }
    }
    private var registered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            ContextCompat.registerReceiver(this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!KeepAlive.shouldRun(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }
            .onFailure { stopSelf() }
        // If Android kills the process anyway, it brings this service (and so the app) back.
        return START_STICKY
    }

    override fun onDestroy() {
        if (registered) runCatching { unregisterReceiver(screenOn) }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.keepalive_channel), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_opentv_logo)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.keepalive_running))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private companion object {
        const val CHANNEL = "keepalive"
        const val NOTIFICATION_ID = 7701
    }
}

/** Box restarted: start again if always-on is on. Activity launch works up to Android 9 (Mi Box). */
class KeepAliveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!KeepAlive.shouldRun(context)) return
        KeepAlive.start(context)
        KeepAlive.bringToFront(context)
    }
}
