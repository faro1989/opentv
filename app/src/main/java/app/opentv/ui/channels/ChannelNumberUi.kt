/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import app.opentv.ui.theme.Obsidian

/**
 * "Kanalnummer zuweisen" — the design's channel-number sheet. A big neon read-out of the number
 * being typed, a live conflict check ("Kanal 042 ist frei" / "Belegt durch …"), a phone-style
 * keypad with a "next free number" key, and the two switches: swap on conflict, and let the guide
 * and favourites follow the new numbering. The remote's own digit keys type straight into it.
 */
@Composable
internal fun ChannelNumberDialog(
    channelName: String,
    currentNumber: Int?,
    hasCustomNumber: Boolean,
    findOccupant: suspend (Int) -> Channel?,
    suggestFree: suspend () -> Int,
    onSave: (number: Int, swap: Boolean, syncOrder: Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var digits by remember { mutableStateOf("") }
    var swap by remember { mutableStateOf(true) }
    var syncOrder by remember { mutableStateOf(true) }
    var occupant by remember { mutableStateOf<Channel?>(null) }
    var suggestion by remember { mutableIntStateOf(0) }
    val number = digits.toIntOrNull()?.takeIf { it > 0 }

    LaunchedEffect(Unit) { suggestion = runCatching { suggestFree() }.getOrDefault(0) }
    LaunchedEffect(number) { occupant = number?.let { runCatching { findOccupant(it) }.getOrNull() } }

    fun type(d: Char) { if (digits.length < 4) digits = (digits + d).trimStart('0') }
    fun back() { digits = digits.dropLast(1) }
    val unchanged = number != null && number == currentNumber && occupant == null
    val canSave = number != null && !unchanged && (occupant == null || swap)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints {
            val wide = maxWidth > 760.dp
            val shape = RoundedCornerShape(28.dp)
            Column(
                Modifier
                    .widthIn(max = if (wide) 900.dp else 420.dp)
                    .padding(16.dp)
                    .shadow(40.dp, shape, ambientColor = Obsidian.Cyan, spotColor = Obsidian.Cyan)
                    .clip(shape)
                    .background(Obsidian.Surface)
                    .border(1.dp, Obsidian.Hairline, shape)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val d = digitFor(e.key)
                        when {
                            d != null -> { type(d); true }
                            e.key == Key.Backspace || e.key == Key.Delete -> { back(); true }
                            else -> false
                        }
                    }
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
            ) {
                Header(channelName, currentNumber, onDismiss)
                Spacer(Modifier.height(16.dp))
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Column(Modifier.weight(1f)) {
                            ReadOut(digits, number, occupant, swap, ::back)
                            Spacer(Modifier.height(14.dp))
                            Options(swap, { swap = it }, syncOrder, { syncOrder = it })
                        }
                        Column(Modifier.weight(1f)) {
                            Keypad(suggestion, ::type, ::back) { digits = suggestion.toString() }
                        }
                    }
                } else {
                    ReadOut(digits, number, occupant, swap, ::back)
                    Spacer(Modifier.height(14.dp))
                    Keypad(suggestion, ::type, ::back) { digits = suggestion.toString() }
                    Spacer(Modifier.height(14.dp))
                    Options(swap, { swap = it }, syncOrder, { syncOrder = it })
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton(stringResource(R.string.common_cancel), null, filled = false, enabled = true, Modifier.weight(1f), onDismiss)
                    BigButton(
                        stringResource(R.string.chnum_save),
                        Icons.Filled.Check,
                        filled = true,
                        enabled = canSave,
                        modifier = Modifier.weight(1f),
                    ) { number?.let { onSave(it, swap, syncOrder) } }
                }
                if (hasCustomNumber) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.chnum_reset),
                        style = MaterialTheme.typography.labelLarge,
                        color = Obsidian.TextMid,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onReset)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(channelName: String, currentNumber: Int?, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Obsidian.CyanTint),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Dialpad, contentDescription = null, tint = Obsidian.Cyan) }
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.chnum_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Obsidian.TextHigh,
            modifier = Modifier.weight(1f),
        )
        RoundIconButton(Icons.Filled.Close, stringResource(R.string.common_cancel), onDismiss)
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CardBadge(initials(channelName), Obsidian.TextHigh, Obsidian.Elevated)
        Spacer(Modifier.width(10.dp))
        Text(
            channelName,
            style = MaterialTheme.typography.titleMedium,
            color = Obsidian.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        currentNumber?.let {
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.chnum_previous, "%03d".format(it)),
                style = MaterialTheme.typography.labelLarge,
                color = Obsidian.TextMid,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Obsidian.Elevated)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Obsidian.Hairline))
}

/** The neon "#042▍" read-out with its live conflict status underneath. */
@Composable
private fun ReadOut(digits: String, number: Int?, occupant: Channel?, swap: Boolean, onBack: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "cursorAlpha",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(20.dp, shape, ambientColor = Obsidian.Cyan, spotColor = Obsidian.Cyan)
            .clip(shape)
            .background(Obsidian.Canvas)
            .border(2.dp, Obsidian.CyanGlow, shape)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.chnum_new_assignment).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Obsidian.TextMid,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null, tint = Obsidian.TextMid, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.chnum_correct), style = MaterialTheme.typography.labelLarge, color = Obsidian.TextMid)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Obsidian.Cyan)) { append("#") }
                    withStyle(SpanStyle(color = if (digits.isEmpty()) Obsidian.TextLow else Obsidian.TextHigh)) {
                        append(if (digits.isEmpty()) "000" else digits.padStart(3, '0'))
                    }
                },
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 56.sp, letterSpacing = 4.sp),
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.width(6.dp))
            Box(Modifier.width(4.dp).height(52.dp).alpha(blink).background(Obsidian.Cyan, RoundedCornerShape(2.dp)))
        }
        Spacer(Modifier.height(10.dp))

        // Status pill: free / taken (with or without auto-swap) / still typing.
        val padded = number?.let { "%03d".format(it) }
        val (icon, tint, text, badge) = when {
            number == null -> Quad(Icons.Filled.Dialpad, Obsidian.TextLow, stringResource(R.string.chnum_type_hint), "")
            occupant == null -> Quad(Icons.Filled.CheckCircle, Obsidian.Cyan, stringResource(R.string.chnum_free, padded!!), stringResource(R.string.chnum_no_conflict))
            swap -> Quad(Icons.Filled.SwapHoriz, Obsidian.Violet, stringResource(R.string.chnum_taken, occupant.shownName), stringResource(R.string.chnum_auto_swap))
            else -> Quad(Icons.Filled.Warning, Obsidian.Magenta, stringResource(R.string.chnum_taken, occupant.shownName), stringResource(R.string.chnum_conflict))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.08f))
                .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                color = Obsidian.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badge.isNotEmpty()) CardBadge(badge.uppercase(), tint, Obsidian.Canvas)
        }
    }
}

private data class Quad(val icon: ImageVector, val tint: Color, val text: String, val badge: String)

@Composable
private fun Keypad(suggestion: Int, onDigit: (Char) -> Unit, onBack: () -> Unit, onSuggest: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                line.forEach { d -> KeyCapNum(Modifier.weight(1f), onClick = { onDigit(d) }) { DigitText(d.toString()) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyCapNum(Modifier.weight(1f), onClick = onSuggest, enabled = suggestion > 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Obsidian.Cyan, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.chnum_next_free, "%03d".format(suggestion)),
                        style = MaterialTheme.typography.labelMedium,
                        color = Obsidian.TextMid,
                    )
                }
            }
            KeyCapNum(Modifier.weight(1f), onClick = { onDigit('0') }) { DigitText("0") }
            KeyCapNum(Modifier.weight(1f), onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.chnum_correct), tint = Obsidian.TextHigh)
            }
        }
    }
}

@Composable
private fun DigitText(d: String) {
    Text(d, style = MaterialTheme.typography.headlineMedium, color = Obsidian.TextHigh)
}

@Composable
private fun KeyCapNum(
    modifier: Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .height(64.dp)
            .clip(shape)
            .background(if (focused) Obsidian.CyanTint else Obsidian.Elevated)
            .border(if (focused) 2.dp else 1.dp, if (focused) Obsidian.Cyan else Obsidian.Hairline, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Options(swap: Boolean, onSwap: (Boolean) -> Unit, sync: Boolean, onSync: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OptionRow(stringResource(R.string.chnum_swap_title), stringResource(R.string.chnum_swap_desc), swap, onSwap)
        OptionRow(stringResource(R.string.chnum_sync_title), stringResource(R.string.chnum_sync_desc), sync, onSync)
    }
}

@Composable
private fun OptionRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Obsidian.Elevated.copy(alpha = 0.6f))
            .border(1.dp, Obsidian.Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Obsidian.TextHigh)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = Obsidian.TextMid)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Obsidian.Cyan,
                checkedThumbColor = Obsidian.Canvas,
            ),
        )
    }
}

@Composable
private fun BigButton(
    label: String,
    icon: ImageVector?,
    filled: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val bg = when {
        !enabled -> Obsidian.Elevated.copy(alpha = 0.5f)
        filled -> Obsidian.Cyan
        focused -> Obsidian.CyanTint
        else -> Obsidian.Elevated
    }
    val fg = if (filled && enabled) Obsidian.Canvas else if (enabled) Obsidian.TextHigh else Obsidian.TextLow
    Row(
        modifier
            .height(60.dp)
            .then(if (filled && enabled) Modifier.shadow(16.dp, shape, ambientColor = Obsidian.Cyan, spotColor = Obsidian.Cyan) else Modifier)
            .clip(shape)
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Obsidian.Hairline, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(50))
            .background(if (focused) Obsidian.Cyan else Obsidian.Elevated)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = if (focused) Obsidian.Canvas else Obsidian.TextHigh) }
}

private fun digitFor(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}
