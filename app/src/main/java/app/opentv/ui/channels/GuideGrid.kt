/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import app.opentv.data.model.shownNumber
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.theme.Obsidian
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The programme guide: channels down the left, a scrolling time-line to the right, with
 * each programme drawn as a block whose width is its duration. This is the "grid" a TV guide
 * is supposed to be — you can see what is on now, what is next, and read across the evening.
 *
 * ## How it lays out without a custom Layout
 *
 * Every row and the time header share one [horizontalScroll] state, so scrolling any of them
 * scrolls all of them in lock-step and the columns stay time-aligned. Within a row, blocks
 * are placed left to right at [MINUTE] width per minute; a leading spacer covers any gap
 * before the first programme, and gaps between programmes get their own spacer. No absolute
 * positioning, no measuring pass — just widths, which is cheap enough for a lazy list of
 * hundreds of channels on a weak TV box.
 */
@Composable
fun GuideGrid(
    rows: List<ChannelsViewModel.Row>,
    windowStartMillis: Long,
    selectedKey: Any?,
    onSelectRow: (ChannelsViewModel.Row) -> Unit,
    onFocusRow: (ChannelsViewModel.Row) -> Unit,
    onProgramme: (ChannelsViewModel.Row, Programme) -> Unit = { _, _ -> },
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit = {},
    // Returns true if it handled the key (rail was hidden, so consume it); false to let normal
    // left-navigation carry focus into the already-visible rail.
    onExitLeftFromChannel: () -> Boolean = { false },
    dayOffset: Int = 0,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val now = System.currentTimeMillis()
    // Jumping to another day resets the horizontal scroll to that day's start; a within-day time
    // drift (dayOffset unchanged) leaves the user's scroll position untouched.
    androidx.compose.runtime.LaunchedEffect(dayOffset) { scroll.scrollTo(0) }

    Column(modifier.fillMaxSize()) {
        // No pager buttons: the programme blocks are d-pad focusable, so moving right along a row
        // scrolls the whole timeline (header and every row in lock-step) on its own.
        TimeHeader(windowStartMillis, scroll)

        LazyColumn(
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                GuideRow(
                    row = row,
                    windowStartMillis = windowStartMillis,
                    nowMillis = now,
                    scroll = scroll,
                    isSelected = row.key == selectedKey,
                    onSelect = { onSelectRow(row) },
                    onFocus = { onFocusRow(row) },
                    onProgramme = { programme -> onProgramme(row, programme) },
                    onToggleFavourite = { onToggleFavourite(row) },
                    onExitLeft = onExitLeftFromChannel,
                )
            }
        }
    }
}

/**
 * The live channels as a plain vertical list instead of the time-grid — one channel per row with
 * its logo, name, and now/next line. This is the "list mode instead of tiles" some users prefer on
 * a busy line-up. It keeps the exact same focus/key contract as [GuideGrid] so [HomeScreen] can
 * swap between the two with nothing else changing: focus follows the d-pad (updating the preview),
 * OK opens the channel menu, and LEFT from a row asks the host to reopen the category rail.
 */
@Composable
fun ChannelList(
    rows: List<ChannelsViewModel.Row>,
    selectedKey: Any?,
    onSelectRow: (ChannelsViewModel.Row) -> Unit,
    onFocusRow: (ChannelsViewModel.Row) -> Unit,
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit = {},
    onExitLeftFromChannel: () -> Boolean = { false },
    // Drag & drop reordering — only offered while browsing one of the user's own lists.
    canReorder: Boolean = false,
    movingKey: Any? = null,
    onStartMove: (ChannelsViewModel.Row) -> Unit = {},
    onMoveStep: (ChannelsViewModel.Row, Int) -> Unit = { _, _ -> },
    onEndMove: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    val listState = rememberLazyListState()
    // Keep the card being dragged on screen as it travels up or down the list.
    LaunchedEffect(rows, movingKey) {
        val index = rows.indexOfFirst { it.key == movingKey }
        if (index >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: 0
            val last = visible.lastOrNull()?.index ?: 0
            if (index <= first || index >= last) listState.animateScrollToItem((index - 1).coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            ChannelListRow(
                row = row,
                nowMillis = now,
                isSelected = row.key == selectedKey,
                onSelect = { onSelectRow(row) },
                onFocus = { onFocusRow(row) },
                onToggleFavourite = { onToggleFavourite(row) },
                onExitLeft = onExitLeftFromChannel,
                canReorder = canReorder,
                moving = canReorder && row.key == movingKey,
                onStartMove = { onStartMove(row) },
                onMoveStep = { delta -> onMoveStep(row, delta) },
                onEndMove = onEndMove,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/**
 * One channel as a StreamFlow-style card: number, logo tile, name with a quality badge, the live
 * programme, "Folgt …", a percentage and a hairline cyan progress bar. The focused card gets the
 * neon treatment — cyan ring, soft glow, cyan accent edge on the left and a cyan channel number.
 */
@Composable
private fun ChannelListRow(
    row: ChannelsViewModel.Row,
    nowMillis: Long,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onFocus: () -> Unit,
    onToggleFavourite: () -> Unit,
    onExitLeft: () -> Boolean,
    canReorder: Boolean = false,
    moving: Boolean = false,
    onStartMove: () -> Unit = {},
    onMoveStep: (Int) -> Unit = {},
    onEndMove: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val active = focused || isSelected || moving
    val shape = RoundedCornerShape(14.dp)
    // Touch drag: one step per card-height of finger travel.
    val stepPx = with(LocalDensity.current) { 96.dp.toPx() }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val nowProg = row.now
    val progress = nowProg?.progressAt(nowMillis)?.coerceIn(0f, 1f)

    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .then(if (moving) Modifier.scale(1.02f) else Modifier)
            .then(
                if (focused || moving) {
                    val glow = if (moving) Obsidian.Violet else Obsidian.Cyan
                    Modifier.shadow(if (moving) 26.dp else 18.dp, shape, ambientColor = glow, spotColor = glow)
                } else Modifier,
            )
            .clip(shape)
            .background(if (active) Obsidian.Elevated else Obsidian.Surface)
            .border(
                width = if (focused || moving) 2.dp else 1.dp,
                color = when {
                    moving -> Obsidian.Violet
                    focused -> Obsidian.Cyan
                    isSelected -> Obsidian.CyanGlow
                    else -> Obsidian.Hairline
                },
                shape = shape,
            )
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent moving
                when {
                    // Picked up: the remote's up/down carries the card, OK or Back puts it down.
                    moving -> {
                        when (e.key) {
                            Key.DirectionUp -> onMoveStep(-1)
                            Key.DirectionDown -> onMoveStep(1)
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back, Key.Escape -> onEndMove()
                            else -> Unit
                        }
                        true
                    }
                    // LEFT from a row reopens the collapsed rail, exactly like the grid's channel column.
                    e.key == Key.DirectionLeft -> onExitLeft()
                    else -> false
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onSelect),
    ) {
        // The cyan accent edge on the active card.
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(4.dp)
                    .height(64.dp)
                    .background(Obsidian.Cyan, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)),
            )
        }
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.primary.shownNumber?.let { "%03d".format(it) } ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (active) Obsidian.Cyan else Obsidian.TextMid,
                    maxLines = 1,
                    modifier = Modifier.width(44.dp),
                )
                // Logo tile — the channel's initials stand in when the provider has no logo.
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Obsidian.Canvas)
                        .border(1.dp, Obsidian.Hairline, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        initials(row.primary.shownName),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (active) Obsidian.Cyan else Obsidian.TextHigh,
                    )
                    AsyncImage(
                        model = row.primary.logoUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.primary.shownName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Obsidian.TextHigh,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        val badge = row.primary.qualityLabel.takeIf { it.isNotBlank() }
                            ?: if (row.variants.size > 1) stringResource(R.string.guide_qualities_count, row.variants.size) else null
                        badge?.let {
                            Spacer(Modifier.width(8.dp))
                            CardBadge(it.uppercase(), Obsidian.Cyan, Obsidian.CyanTint)
                        }
                        if (nowProg != null) {
                            Spacer(Modifier.width(6.dp))
                            CardBadge(stringResource(R.string.ui_live_badge), Color.White, Obsidian.Magenta.copy(alpha = 0.85f))
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    if (moving) {
                        Text(
                            stringResource(R.string.favlist_moving_hint),
                            style = MaterialTheme.typography.labelLarge,
                            color = Obsidian.Violet,
                            maxLines = 1,
                        )
                    }
                    Text(
                        nowProg?.title ?: stringResource(R.string.guide_no_info),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (active) Obsidian.TextHigh else Obsidian.TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    row.next?.let { next ->
                        Text(
                            stringResource(R.string.ui_next_short, clockFormat.format(Date(next.startUtcMillis)), next.title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Obsidian.TextLow,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (canReorder) {
                    // Drag handle: long-press and drag on touch screens; a tap picks the card up for
                    // the remote. Moving shows a violet "↕" so it's obvious the card is in hand.
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (moving) Obsidian.Violet.copy(alpha = 0.25f) else Color.Transparent)
                            .pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragAccum = 0f; onStartMove() },
                                    onDragEnd = { onEndMove() },
                                    onDragCancel = { onEndMove() },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragAccum += amount.y
                                        if (dragAccum > stepPx) { onMoveStep(1); dragAccum -= stepPx }
                                        if (dragAccum < -stepPx) { onMoveStep(-1); dragAccum += stepPx }
                                    },
                                )
                            }
                            .clickable { if (moving) onEndMove() else onStartMove() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.DragIndicator,
                            contentDescription = stringResource(R.string.favlist_drag),
                            tint = if (moving) Obsidian.Violet else Obsidian.TextLow,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    IconButton(onClick = onToggleFavourite, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = if (row.primary.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite)
                            else stringResource(R.string.common_favourite),
                            tint = if (row.primary.favourite) Obsidian.Cyan else Obsidian.TextLow,
                        )
                    }
                    if (progress != null) {
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) Obsidian.Cyan else Obsidian.TextMid,
                        )
                    }
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (active) Obsidian.Cyan else Obsidian.TextLow),
                    )
                }
            }
        }
    }
}

/** A tiny uppercase pill — FHD, 4K, LIVE … — in the design's label-sm style. */
@Composable
internal fun CardBadge(text: String, fg: Color, bg: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** "Sky Sport News" → "SSN", "ZDF HD" → "ZDF": the stand-in shown until (or instead of) a logo. */
internal fun initials(name: String): String {
    val words = name.split(' ', '-', '|').filter { it.isNotBlank() && !it.equals("HD", true) && !it.equals("FHD", true) }
    if (words.isEmpty()) return "TV"
    return if (words.size == 1) words[0].take(4).uppercase()
    else words.take(3).joinToString("") { it.first().uppercase() }
}

@Composable
private fun TimeHeader(windowStartMillis: Long, scroll: androidx.compose.foundation.ScrollState) {
    Row(Modifier.fillMaxWidth().height(28.dp)) {
        // Empty corner above the channel column.
        Box(Modifier.width(CHANNEL_COLUMN))
        Row(
            Modifier
                .horizontalScroll(scroll)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // A label every half hour across the whole window.
            repeat(HOURS_IN_WINDOW * 2) { i ->
                val slotStart = windowStartMillis + i * HALF_HOUR_MS
                Box(
                    Modifier.width(HALF_HOUR_WIDTH).height(28.dp).padding(start = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        clockFormat.format(Date(slotStart)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    row: ChannelsViewModel.Row,
    windowStartMillis: Long,
    nowMillis: Long,
    scroll: androidx.compose.foundation.ScrollState,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onFocus: () -> Unit,
    onProgramme: (Programme) -> Unit,
    onToggleFavourite: () -> Unit = {},
    onExitLeft: () -> Boolean = { false },
) {
    // Focus (the d-pad border) just moves the highlight. Selection (the filled cell) is the
    // channel the preview is playing — it only changes when you press OK.
    var focused by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {

        // ---- Fixed channel cell (does not scroll) --------------------------------------
        Row(
            Modifier
                .width(CHANNEL_COLUMN)
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                )
                .then(
                    if (focused) Modifier.border(
                        2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp),
                    ) else Modifier,
                )
                // LEFT from this leftmost column asks the host to reopen the collapsed category
                // rail; onExitLeft consumes the key only when it handled it (rail was hidden), so
                // moving left onto programme blocks and into an already-visible rail still works.
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) onExitLeft()
                    else false
                }
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                }
                .clickable(onClick = onSelect)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            row.primary.shownNumber?.let { num ->
                Text(
                    "$num",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(28.dp),
                )
            }
            AsyncImage(
                model = row.primary.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.primary.shownName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.variants.size > 1) {
                    Text(
                        stringResource(R.string.guide_qualities_count, row.variants.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            // Favourite toggle. Phone/tablet users had no visible way to favourite from the guide
            // (only the Channel Manager) — this puts a tappable star on every row; it stays d-pad
            // focusable so it works from a TV remote too.
            IconButton(
                onClick = onToggleFavourite,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (row.primary.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite)
                    else stringResource(R.string.common_favourite),
                    tint = if (row.primary.favourite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(3.dp))

        // ---- Scrolling programme strip -------------------------------------------------
        Row(Modifier.horizontalScroll(scroll)) {
            val programmes = row.programmes
            if (programmes.isEmpty()) {
                // Honest placeholder spanning the window, so a guide-less channel reads as
                // "no guide" rather than as a suspiciously empty gap.
                Box(
                    Modifier
                        .width(HALF_HOUR_WIDTH * HOURS_IN_WINDOW.toFloat() * 2)
                        .fillMaxSize()
                        .padding(end = 3.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        stringResource(R.string.guide_no_info),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                var cursor = windowStartMillis
                // A programme almost over (or a very short one) would otherwise be a sliver too
                // thin to read its name — the "Poi n..." problem. We floor each block at
                // [MIN_BLOCK_WIDTH] so the title stays legible, then carry the borrowed width as
                // `debt` and repay it out of the following gaps, so everything downstream stays
                // aligned with the time header instead of drifting right.
                var debt = 0.dp
                for (programme in programmes) {
                    // Clamp to the window's left edge; a programme that started earlier is
                    // drawn from `windowStart` so its block does not push everything right.
                    val start = programme.startUtcMillis.coerceAtLeast(windowStartMillis)
                    val gap = widthFor(cursor, start)
                    val repaid = minOf(debt, gap)
                    debt -= repaid
                    val spacer = gap - repaid
                    if (spacer > 0.dp) {
                        Spacer(Modifier.width(spacer))
                    }
                    val end = programme.endUtcMillis
                    val isNow = nowMillis in programme.startUtcMillis until end
                    val trueWidth = widthFor(start, end)
                    val drawnWidth = maxOf(trueWidth, MIN_BLOCK_WIDTH)
                    debt += drawnWidth - trueWidth
                    ProgrammeBlock(
                        title = programme.title,
                        width = drawnWidth,
                        isNow = isNow,
                        progress = if (isNow) programme.progressAt(nowMillis) else 0f,
                        onClick = { onProgramme(programme) },
                    )
                    cursor = end
                }
            }
        }
    }
}

@Composable
private fun ProgrammeBlock(title: String, width: Dp, isNow: Boolean, progress: Float, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(width)
            .fillMaxSize()
            .padding(end = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.primary
                    isNow -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .then(
                if (focused || isNow) Modifier.border(
                    if (focused) 2.dp else 1.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(6.dp),
                ) else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                focused -> MaterialTheme.colorScheme.onPrimary
                isNow -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp),
        )
        // How far through the current programme we are — a live fill along the bottom edge.
        if (isNow && progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Minutes between two instants, as guide width. Never negative; tiny slots stay legible. */
private fun widthFor(fromMillis: Long, toMillis: Long): Dp {
    val minutes = ((toMillis - fromMillis) / 60_000L).coerceAtLeast(0)
    return (minutes * MINUTE_DP).dp
}

// A weak TV box will happily scroll this; the whole strip is ~12h * 60 * 4dp = ~2880dp wide.
private const val MINUTE_DP = 4f
private const val HOURS_IN_WINDOW = 24
private const val HALF_HOUR_MS = 30 * 60 * 1000L
private val CHANNEL_COLUMN = 220.dp
private val ROW_HEIGHT = 64.dp
// Floor for a programme block so a nearly-finished or very short show still shows its name.
private val MIN_BLOCK_WIDTH = 72.dp
private val HALF_HOUR_WIDTH: Dp = (30 * MINUTE_DP).dp

private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
