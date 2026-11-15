/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.player.StreamDiagnostics
import app.opentv.player.StreamDiagnostics.Link
import app.opentv.player.StreamDiagnostics.Verdict
import app.opentv.ui.theme.Obsidian
import kotlinx.coroutines.delay

/**
 * The stream-diagnosis panel: a glass card over the picture, refreshed every second, with the
 * picture (resolution, frame rate, codec, bitrate), sound, the line (throughput vs. what the stream
 * needs, buffer, stalls, dropped frames), where it comes from (server, Wi-Fi/LAN, VPN) — and on top
 * a plain-language verdict saying where a stutter comes from and what to try.
 */
@Composable
fun DiagnosticsPanel(diagnostics: StreamDiagnostics, modifier: Modifier = Modifier) {
    var s by remember { mutableStateOf<StreamDiagnostics.Snapshot?>(null) }
    LaunchedEffect(diagnostics) {
        while (true) { s = runCatching { diagnostics.snapshot() }.getOrNull(); delay(1_000) }
    }
    val snap = s ?: return
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier
            .widthIn(max = 460.dp).fillMaxWidth()
            .clip(shape)
            .background(Obsidian.Canvas.copy(alpha = 0.92f))
            .border(1.dp, Obsidian.CyanGlow, shape)
            .padding(18.dp),
    ) {
        Text(stringResource(R.string.diag_title).uppercase(), style = MaterialTheme.typography.labelMedium, color = Obsidian.TextMid)
        Spacer(Modifier.height(8.dp))

        // ---- Verdict --------------------------------------------------------------------------
        val (color, title, advice) = when (snap.verdict) {
            Verdict.STARTING -> Triple(Obsidian.TextMid, R.string.diag_v_starting, R.string.diag_a_starting)
            Verdict.OK -> Triple(Obsidian.Cyan, R.string.diag_v_ok, R.string.diag_a_ok)
            Verdict.PROVIDER -> Triple(Obsidian.Magenta, R.string.diag_v_provider, R.string.diag_a_provider)
            Verdict.WIFI -> Triple(Obsidian.Magenta, R.string.diag_v_wifi, R.string.diag_a_wifi)
            Verdict.VPN -> Triple(Obsidian.Violet, R.string.diag_v_vpn, R.string.diag_a_vpn)
            Verdict.CONNECTION -> Triple(Obsidian.Magenta, R.string.diag_v_connection, R.string.diag_a_connection)
            Verdict.DEVICE -> Triple(Obsidian.Violet, R.string.diag_v_device, R.string.diag_a_device)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.10f))
                .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = Obsidian.TextHigh)
                Text(stringResource(advice), style = MaterialTheme.typography.bodySmall, color = Obsidian.TextMid)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- Picture & sound ------------------------------------------------------------------
        TileRow(
            Tile(stringResource(R.string.diag_resolution), if (snap.height > 0) "${snap.width}×${snap.height}${quality(snap.height)}" else "–"),
            Tile(stringResource(R.string.diag_framerate), if (snap.frameRate > 0) "%.0f fps".format(snap.frameRate) else "–"),
            Tile(stringResource(R.string.diag_codec), snap.videoCodec ?: "–"),
        )
        TileRow(
            Tile(stringResource(R.string.diag_bitrate), mbps(snap.videoBitrate)),
            Tile(stringResource(R.string.diag_audio), listOfNotNull(snap.audioCodec, channels(snap.audioChannels)).joinToString(" · ").ifEmpty { "–" }),
            Tile(stringResource(R.string.diag_dropped), "${snap.droppedFrames}"),
        )

        // ---- Line ---------------------------------------------------------------------------
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.diag_throughput_vs), style = MaterialTheme.typography.labelSmall, color = Obsidian.TextLow)
        Spacer(Modifier.height(4.dp))
        val ratio = snap.headroom.coerceIn(0f, 3f) / 3f
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.08f))) {
            Box(
                Modifier.fillMaxWidth(ratio.coerceAtLeast(0.02f)).fillMaxHeight()
                    .background(if (snap.headroom >= 1.3f || snap.videoBitrate == 0L) Obsidian.Cyan else Obsidian.Magenta),
            )
            // The 1.0× mark: below it the line can't keep up with the stream.
            Box(Modifier.fillMaxWidth(1f / 3f).fillMaxHeight()) { Box(Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Color.White)) }
        }
        Text(
            if (snap.videoBitrate > 0 && snap.throughput > 0) stringResource(R.string.diag_headroom, mbps(snap.throughput), "%.1f".format(snap.headroom))
            else mbps(snap.throughput),
            style = MaterialTheme.typography.bodySmall, color = Obsidian.TextMid,
        )
        Spacer(Modifier.height(8.dp))
        TileRow(
            Tile(stringResource(R.string.diag_buffer), "%.1f s".format(snap.bufferMs / 1000f)),
            Tile(stringResource(R.string.diag_stalls), "${snap.stalls}"),
            Tile(
                stringResource(R.string.diag_network),
                when (snap.link) {
                    Link.ETHERNET -> "LAN"
                    Link.WIFI -> "WLAN" + (snap.wifiRssi?.let { " $it dBm" + signal(it) } ?: "")
                    Link.MOBILE -> stringResource(R.string.diag_mobile)
                    Link.OTHER -> "–"
                    Link.NONE -> stringResource(R.string.diag_offline)
                },
            ),
        )
        TileRow(
            Tile(stringResource(R.string.diag_server), snap.server ?: "–", weight = 2f),
            Tile("VPN", if (snap.vpnOn) stringResource(R.string.diag_on) else stringResource(R.string.diag_off)),
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.diag_close_hint), style = MaterialTheme.typography.labelSmall, color = Obsidian.TextLow)
    }
}

private data class Tile(val label: String, val value: String, val weight: Float = 1f)

@Composable
private fun TileRow(vararg tiles: Tile) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tiles.forEach { t ->
            Column(
                Modifier.weight(t.weight).clip(RoundedCornerShape(10.dp)).background(Obsidian.Elevated.copy(alpha = 0.8f))
                    .border(1.dp, Obsidian.Hairline, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text(t.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Obsidian.TextMid, maxLines = 1)
                Text(t.value, style = MaterialTheme.typography.titleSmall, color = Obsidian.Cyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun mbps(bps: Long): String = if (bps <= 0) "–" else "%.1f Mbit/s".format(bps / 1_000_000.0)
private fun quality(h: Int) = when { h >= 2000 -> " 4K"; h >= 1000 -> " FHD"; h >= 700 -> " HD"; else -> " SD" }
private fun channels(n: Int) = when (n) { 0 -> null; 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$n ch" }
private fun signal(rssi: Int) = when { rssi >= -55 -> " ▂▄▆█"; rssi >= -65 -> " ▂▄▆"; rssi >= -72 -> " ▂▄"; else -> " ▂" }
