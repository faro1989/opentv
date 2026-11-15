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
                    "OpenTV $version · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Thread: ${thread.name}\n\n" + error.stackTraceToString().take(6_000),
                )
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
                .setTitle("OpenTV ist abgestürzt / OpenTV crashed")
                .setMessage("Bitte ein Foto davon machen und schicken.\nPlease take a photo and report it.\n\n$text")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
