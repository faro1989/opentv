/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import app.opentv.R
import app.opentv.data.model.shownName
import app.opentv.data.model.shownNumber
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.theme.Obsidian
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The detail pane at the top of the guide.
 *
 * As focus moves down the channel list, [row] follows it and the pane shows that channel's
 * logo alongside what's on now, how far through it is, what's next, and a synopsis when the
 * guide carries one — the "info on the EPG" people ask for before they'll judge a guide.
 * Pressing it plays the channel full-screen.
 *
 * ## Inline video
 * When [previewPlayer] is non-null the highlighted channel plays, muted, inside the card. The
 * earlier version of this that locked up cheap boxes ran a *second* decoder behind the
 * full-screen one; here there is only ever this single, muted, debounced player — and the
 * caller ([HomeScreen]) stops it before handing off to full-screen and whenever the screen is
 * backgrounded, so the device never has two decoders alive at once. Low-end boxes can turn the
 * whole thing off in settings, in which case [previewPlayer] is null and this falls back to the
 * logo. The logo stays behind the video as the shutter, so a buffering or failed stream still
 * shows something rather than a black hole.
 */
@OptIn(UnstableApi::class)
@Composable
fun GuidePreview(
    row: ChannelsViewModel.Row?,
    nowMillis: Long,
    onWatch: () -> Unit,
    onRefresh: () -> Unit,
    onAddSource: () -> Unit,
    previewPlayer: ExoPlayer?,
    isRecording: Boolean = false,
    onRecord: () -> Unit = {},
    dayLabel: String = "",
    canGoPrevDay: Boolean = false,
    onPrevDay: () -> Unit = {},
    onNextDay: () -> Unit = {},
    /** Live measurements of the preview stream, when it plays — fills the Bitrate/Server tiles. */
    stats: app.opentv.player.StreamDiagnostics.Snapshot? = null,
    /** Phone: a full-width 16:9 video only, without the side detail column. */
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .fillMaxWidth()
            .then(if (compact) Modifier else Modifier.height(250.dp))
            .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 6.dp else 12.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Obsidian.Surface, Obsidian.Canvas)))
            .border(1.dp, Obsidian.Hairline, shape)
            .padding(12.dp),
    ) {
        // ---- Video card ---------------------------------------------------------------------
        Box(
            Modifier
                .then(if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(1.dp, Obsidian.CyanGlow, RoundedCornerShape(14.dp))
                .clickable(onClick = onWatch),
            contentAlignment = Alignment.Center,
        ) {
            // Logo sits behind everything as the fallback / shutter.
            if (row != null) {
                AsyncImage(
                    model = row.primary.logoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.5f),
                )
            }

            // Live video on top of the logo when preview playback is on. The transparent
            // shutter means the logo behind shows through until the first frame arrives.
            if (previewPlayer != null && row != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            player = previewPlayer
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    update = { it.player = previewPlayer },
                )
            }

            // Bottom scrim so the badges stay readable over bright pictures.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))),
            )
            if (row?.now != null) {
                Text(
                    stringResource(R.string.ui_live_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Obsidian.Magenta)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            row?.primary?.qualityLabel?.takeIf { it.isNotBlank() }?.let { q ->
                Text(
                    q.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Obsidian.Cyan,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .border(1.dp, Obsidian.CyanGlow, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            // Cyan "watch" pill — the design's primary action.
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Obsidian.Cyan)
                    .padding(start = 8.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Obsidian.Canvas, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.guide_watch_label), color = Obsidian.Canvas, style = MaterialTheme.typography.labelLarge)
            }
        }

        if (!compact) {
        Spacer(Modifier.width(20.dp))

        // ---- Now / next detail ------------------------------------------------------------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (row != null) {
                    // Violet channel chip + number, like "SKY SPORT 1 HD  Kanal 001".
                    Text(
                        row.primary.shownName.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Obsidian.VioletDeep)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    row.primary.shownNumber?.let { num ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.ui_channel_number, "%03d".format(num)),
                            style = MaterialTheme.typography.labelLarge,
                            color = Obsidian.TextMid,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // ---- Day navigation (Sky Q-style): ‹ Today › ----------------------------
                if (dayLabel.isNotEmpty()) {
                    IconButton(onClick = onPrevDay, enabled = canGoPrevDay) {
                        Icon(
                            Icons.Default.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.guide_prev_day),
                            tint = if (canGoPrevDay) Obsidian.TextHigh else Obsidian.TextHigh.copy(alpha = 0.3f),
                        )
                    }
                    Text(dayLabel, style = MaterialTheme.typography.labelLarge, color = Obsidian.Cyan, maxLines = 1)
                    IconButton(onClick = onNextDay) {
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = stringResource(R.string.guide_next_day))
                    }
                }
                if (row != null) {
                    IconButton(onClick = onRecord) {
                        // Magenta — the design's colour for recording — and the glyph switches to a
                        // stop square while a recording runs, so a press visibly does something.
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = if (isRecording) stringResource(R.string.guide_cd_stop_recording) else stringResource(R.string.guide_cd_record_now),
                            tint = Obsidian.Magenta,
                        )
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.guide_cd_refresh))
                }
                IconButton(onClick = onAddSource) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.guide_cd_add_source))
                }
            }

            if (row == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    Text(
                        stringResource(R.string.guide_highlight_hint),
                        color = Obsidian.TextMid,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                return@Column
            }

            Spacer(Modifier.height(6.dp))
            val nowProg = row.now
            Text(
                nowProg?.title ?: row.primary.shownName,
                style = MaterialTheme.typography.headlineMedium,
                color = Obsidian.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            if (nowProg != null) {
                val p = nowProg.progressAt(nowMillis).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.12f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(p)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Obsidian.Cyan),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Text(
                        "${formatTime(nowProg.startUtcMillis)} – ${formatTime(nowProg.endUtcMillis)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Obsidian.TextMid,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.ui_minutes_left, ((nowProg.endUtcMillis - nowMillis) / 60_000L).coerceAtLeast(0L)),
                        style = MaterialTheme.typography.labelLarge,
                        color = Obsidian.Cyan,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.guide_no_info_channel),
                    style = MaterialTheme.typography.titleMedium,
                    color = Obsidian.TextMid,
                )
            }

            Spacer(Modifier.weight(1f))
            // Three glass info tiles, as in the design — filled with what the guide really knows.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile(
                    stringResource(R.string.ui_tile_next),
                    row.next?.let { "${formatTime(it.startUtcMillis)}  ${it.title}" } ?: "–",
                    Modifier.weight(2f),
                )
                // With the preview playing: real Bitrate and Server (the design's tiles). Otherwise
                // what the catalogue knows: quality label and catch-up.
                val bitrate = stats?.videoBitrate?.takeIf { it > 0 }
                InfoTile(
                    stringResource(if (bitrate != null) R.string.diag_bitrate else R.string.ui_tile_quality),
                    bitrate?.let { "%.1f Mbit/s".format(it / 1_000_000.0) } ?: row.primary.qualityLabel.ifBlank {
                        if (row.variants.size > 1) stringResource(R.string.guide_qualities_count, row.variants.size) else "–"
                    },
                    Modifier.weight(1f),
                )
                val server = stats?.server
                InfoTile(
                    stringResource(if (server != null) R.string.diag_server else R.string.ui_tile_catchup),
                    server ?: if (row.primary.tvArchive) stringResource(R.string.ui_catchup_days, row.primary.tvArchiveDays) else "–",
                    Modifier.weight(1f),
                )
            }
        }
        }
    }
}

/** A small frosted tile: uppercase caption over a cyan value — the design's BITRATE / SERVER cards. */
@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Obsidian.Elevated.copy(alpha = 0.8f))
            .border(1.dp, Obsidian.Hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Obsidian.TextMid, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = Obsidian.Cyan,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val previewTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(utcMillis: Long): String = previewTimeFormat.format(Date(utcMillis))
