/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import app.opentv.data.model.Source
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Signed-in state. Logging out deletes nothing — it only locks the app behind the sign-in screen
 * until the user enters a provider login that matches one of the saved accounts.
 */
object Session {
    private const val PREFS = "zapora_session"
    private const val KEY_LOGGED_OUT = "logged_out"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLoggedOut(context: Context) = prefs(context).getBoolean(KEY_LOGGED_OUT, false)

    fun logOut(context: Context) { prefs(context).edit().putBoolean(KEY_LOGGED_OUT, true).commit() }

    fun logIn(context: Context) { prefs(context).edit().putBoolean(KEY_LOGGED_OUT, false).commit() }

    /** Username/password of a source: its own fields, or those inside an M3U `get.php` link. */
    private fun credentialsOf(source: Source): Pair<String, String>? {
        val u = source.username
        val p = source.password
        if (!u.isNullOrBlank() && !p.isNullOrBlank()) return u to p
        val url = source.url.toHttpUrlOrNull() ?: return null
        val qu = url.queryParameter("username")
        val qp = url.queryParameter("password")
        return if (!qu.isNullOrBlank() && !qp.isNullOrBlank()) qu to qp else null
    }

    fun matches(sources: List<Source>, username: String, password: String): Boolean {
        val user = username.trim()
        val pass = password.trim()
        if (user.isEmpty()) return false
        return sources.any { s ->
            val c = credentialsOf(s)
            when {
                c != null -> c.first == user && c.second == pass
                // MAC portals have no password: the MAC address is the login.
                !s.macAddress.isNullOrBlank() -> s.macAddress.equals(user, ignoreCase = true)
                else -> false
            }
        }
    }
}
