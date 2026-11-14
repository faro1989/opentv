/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.ui.theme.Obsidian

/**
 * Settings lock: when the parental PIN is set and "protect settings" is on, everything behind
 * this gate (settings, VPN, storage & cloud, channel order, phone remote) asks for the PIN once per
 * app session. Five wrong tries lock the pad for 30 seconds. Remote digits and the on-screen pad work.
 */
@Composable
fun PinGate(onCancel: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = remember { ServiceLocator.get(context).settings }
    val pinSet by settings.pinIsSet.collectAsState()
    val locked by settings.settingsLocked.collectAsState()
    val unlocked by settings.settingsUnlocked.collectAsState()
    if (!pinSet || !locked || unlocked) { content(); return }

    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableIntStateOf(0) }
    var blockedUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(blockedUntil) {
        while (System.currentTimeMillis() < blockedUntil) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) }
        now = System.currentTimeMillis()
    }
    val blocked = now < blockedUntil
    fun type(d: Char) {
        if (blocked || pin.length >= 8) return
        pin += d
        if (pin.length >= 4 && settings.verifyPin(pin)) settings.setSettingsUnlocked(true)
        else if (pin.length >= 8) { pin = ""; wrong++ }
    }
    fun submitWrong() { pin = ""; wrong++; if (wrong % 5 == 0) blockedUntil = System.currentTimeMillis() + 30_000 }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Obsidian.Canvas).onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val d = when (e.key) {
                Key.Zero, Key.NumPad0 -> '0'; Key.One, Key.NumPad1 -> '1'; Key.Two, Key.NumPad2 -> '2'
                Key.Three, Key.NumPad3 -> '3'; Key.Four, Key.NumPad4 -> '4'; Key.Five, Key.NumPad5 -> '5'
                Key.Six, Key.NumPad6 -> '6'; Key.Seven, Key.NumPad7 -> '7'; Key.Eight, Key.NumPad8 -> '8'
                Key.Nine, Key.NumPad9 -> '9'; else -> null
            }
            when {
                d != null -> { type(d); true }
                e.key == Key.Back || e.key == Key.Escape -> { onCancel(); true }
                else -> false
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔒", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.pingate_title), style = MaterialTheme.typography.headlineSmall, color = Obsidian.TextHigh)
            Text(
                if (blocked) stringResource(R.string.pingate_blocked, ((blockedUntil - now) / 1000).coerceAtLeast(1))
                else if (wrong > 0) stringResource(R.string.pingate_wrong) else stringResource(R.string.pingate_hint),
                color = if (wrong > 0) Obsidian.Magenta else Obsidian.TextMid,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4.coerceAtLeast(pin.length)) { i ->
                    Box(Modifier.size(16.dp).clip(RoundedCornerShape(50)).background(if (i < pin.length) Obsidian.Cyan else Obsidian.Elevated))
                }
            }
            Spacer(Modifier.height(20.dp))
            val keys = listOf("123", "456", "789", "C0✓")
            keys.forEachIndexed { r, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier) {
                    line.forEachIndexed { c, ch ->
                        var focused by remember { mutableStateOf(false) }
                        Box(
                            Modifier.size(width = 84.dp, height = 60.dp)
                                .then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (focused) Obsidian.CyanTint else Obsidian.Elevated)
                                .border(if (focused) 2.dp else 1.dp, if (focused) Obsidian.Cyan else Obsidian.Hairline, RoundedCornerShape(14.dp))
                                .onFocusChanged { focused = it.isFocused }
                                .clickable {
                                    when (ch) {
                                        'C' -> pin = ""
                                        '✓' -> if (pin.isNotEmpty() && !settings.verifyPin(pin)) submitWrong()
                                        else -> type(ch)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(if (ch == 'C') "⌫" else ch.toString(), style = MaterialTheme.typography.headlineSmall, color = Obsidian.TextHigh) }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.width(1.dp))
            Text(
                stringResource(R.string.common_cancel),
                color = Obsidian.TextMid,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onCancel),
            )
        }
    }
}
