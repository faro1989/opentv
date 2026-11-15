/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.ui.theme.Obsidian

/**
 * Sign-in after "Log out". Nothing was deleted: entering the provider login of a saved account
 * simply unlocks the app again, with every list, setting and recording as it was.
 */
@Composable
fun LoginScreen(
    onSignIn: (String, String) -> Boolean,
    onSignedIn: () -> Unit,
    accounts: List<app.opentv.data.model.Source> = emptyList(),
    onPickAccount: (app.opentv.data.model.Source) -> Unit = {},
) {
    val context = LocalContext.current
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    // Logged out means logged out: Back leaves the app rather than slipping past the sign-in.
    BackHandler { (context as? android.app.Activity)?.finish() }

    fun submit() {
        if (onSignIn(user, pass)) onSignedIn() else error = true
    }

    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(72.dp))
            Text("Zapora", style = MaterialTheme.typography.headlineMedium, color = Obsidian.Cyan)
            Text(
                stringResource(R.string.login_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Saved accounts (their login data were kept): tap one to sign straight back in.
            if (accounts.isNotEmpty()) {
                Text(
                    stringResource(R.string.login_saved),
                    style = MaterialTheme.typography.titleSmall,
                    color = Obsidian.Cyan,
                    modifier = Modifier.fillMaxWidth(),
                )
                accounts.forEach { acc ->
                    AccountCard(acc, autoFocus = acc == accounts.first()) { onPickAccount(acc); onSignedIn() }
                }
            }
            Spacer(Modifier.height(4.dp))
            // One tap signs in with the saved account — no typing.
            Button(
                onClick = { accounts.firstOrNull()?.let { onPickAccount(it); onSignedIn() } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.login_button))
            }
        }
    }
}

@Composable
private fun AccountCard(source: app.opentv.data.model.Source, autoFocus: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    val user = source.username?.takeIf { it.isNotBlank() } ?: source.macAddress ?: ""
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .then(if (autoFocus) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Obsidian.CyanTint else Obsidian.Surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) Obsidian.Cyan else Obsidian.Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(Obsidian.VioletDeep),
            contentAlignment = Alignment.Center,
        ) {
            Text(source.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White)
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (user.isNotBlank()) {
                Text(user, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Text("→", style = MaterialTheme.typography.titleLarge, color = Obsidian.Cyan)
    }
    if (autoFocus) androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
