/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.pairing.QrCodes
import app.opentv.pairing.RemoteServer
import app.opentv.ui.theme.Obsidian

/**
 * "Handy als Fernbedienung": a big QR code to scan, the readable address, an on/off switch and
 * "pair again" (new key — every phone paired before is disconnected).
 */
@Composable
fun PhoneRemoteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val server = remember { RemoteServer.get(context) }
    val pairing by server.pairing.collectAsState()
    var enabled by remember { mutableStateOf(server.enabled) }
    LaunchedEffect(Unit) { activity?.let { server.start(it) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.remote_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box(
                Modifier.size(300.dp).clip(RoundedCornerShape(20.dp)).background(Color.White)
                    .border(2.dp, Obsidian.CyanGlow, RoundedCornerShape(20.dp)).padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                val url = pairing?.url
                val bmp = remember(url) { url?.let { QrCodes.render(it, 720) } }
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), contentDescription = stringResource(R.string.remote_qr_cd), modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        stringResource(if (enabled) R.string.remote_no_network else R.string.remote_off),
                        color = Color.Black,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Column(Modifier.widthIn(max = 520.dp)) {
                Text(stringResource(R.string.remote_how_title), style = MaterialTheme.typography.titleLarge, color = Obsidian.TextHigh)
                Spacer(Modifier.height(8.dp))
                listOf(R.string.remote_step1, R.string.remote_step2, R.string.remote_step3).forEachIndexed { i, res ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text("${i + 1}", color = Obsidian.Cyan, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(26.dp))
                        Text(stringResource(res), color = Obsidian.TextMid, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(10.dp))
                pairing?.let {
                    Text(stringResource(R.string.remote_address, "${it.host}:${it.port}"), color = Obsidian.TextLow, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                Text("🔒 " + stringResource(R.string.remote_secure), color = Obsidian.Cyan, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(Obsidian.Surface).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.remote_enable), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.remote_enable_desc), style = MaterialTheme.typography.bodySmall, color = Obsidian.TextMid)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { v -> enabled = v; activity?.let { server.setEnabled(v, it) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = Obsidian.Cyan, checkedThumbColor = Obsidian.Canvas),
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { activity?.let { server.repair(it) } }, enabled = enabled) {
                    Text(stringResource(R.string.remote_repair))
                }
            }
        }
    }
}
