/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import app.opentv.MainActivity
import java.io.File

/**
 * Keeps the last crash on disk and shows it on the next start, so a user without adb can
 * photograph the cause and report it.
 */
object CrashReport {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                file(app).writeText(
                    "Zapora $version · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Thread: ${thread.name}\n\n" + error.stackTraceToString().take(6_000),
                )
            }
            // Always-on: reopen StreamFlow a moment after the crash instead of leaving the TV on the launcher.
            if (KeepAlive.shouldRun(app)) runCatching {
                val restart = android.app.PendingIntent.getActivity(
                    app, 4711,
                    android.content.Intent(app, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                val am = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 2_000, restart)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun showIfAny(activity: Activity) {
        val f = file(activity)
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrDefault("")
        f.delete()
        if (text.isBlank()) return
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("Zapora ist abgestürzt / Zapora crashed")
                .setMessage("Bitte ein Foto davon machen und schicken.\nPlease take a photo and report it.\n\n$text")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
