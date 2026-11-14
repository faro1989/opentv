/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.recording.CloudClient
import app.opentv.recording.RecordingStorage
import app.opentv.recording.StorageManager
import app.opentv.sync.CloudSync
import app.opentv.ui.theme.Obsidian
import kotlinx.coroutines.launch

/**
 * "Speicher & Cloud" — the design's storage hub in one place: how full the device is and what
 * OpenTV uses it for, where recordings go, the cloud the user signed in to, cross-device sync
 * through that cloud, the sync rules, and a "sync now" button with the last result.
 */
@Composable
fun StorageCloudScreen(onBack: () -> Unit, onOpenRecordingSettings: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = graph.settings
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf<StorageManager.Breakdown?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { stats = runCatching { StorageManager.breakdown(context) }.getOrNull() }

    val target by settings.recordingTarget.collectAsState()
    val usbLabel by settings.usbFolderLabel.collectAsState()
    val cloudUser by settings.cloudUser.collectAsState()
    val cloudUrl by settings.cloudUrl.collectAsState()
    val cloudProvider by settings.cloudProvider.collectAsState()
    val wifiOnly by settings.syncWifiOnly.collectAsState()
    val autoUpload by settings.cloudAutoUpload.collectAsState()
    val liveSync by settings.cloudLiveSync.collectAsState()
    val autoCleanup by settings.autoCleanup.collectAsState()
    val lastSync by settings.lastCloudSync.collectAsState()
    val lastOk by settings.lastCloudSyncOk.collectAsState()
    var syncing by remember { mutableStateOf(false) }
    val connected = cloudUrl.isNotBlank() && cloudUser.isNotBlank()
    val providerName = CloudClient.PROVIDERS.firstOrNull { it.id == cloudProvider }?.name ?: "WebDAV"

    fun connect(providerId: String) {
        val preset = CloudClient.PROVIDERS.first { it.id == providerId }
        settings.setCloudConfig(providerId, preset.url, "", "", settings.cloudFolder.value, settings.cloudKeepLocal.value)
        settings.setRecordingTarget(AppSettings.RecordingTarget.CLOUD)
        onOpenRecordingSettings()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.store_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }
        Spacer(Modifier.height(18.dp))

        // ---- Device storage overview -------------------------------------------------------
        Glass {
            val s = stats
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Storage, contentDescription = null, tint = Obsidian.Cyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.store_internal).uppercase(), style = MaterialTheme.typography.labelMedium, color = Obsidian.TextMid, modifier = Modifier.weight(1f))
                if (s != null) Pill(stringResource(R.string.store_percent_used, (s.usedFraction * 100).toInt()), Obsidian.Cyan)
            }
            Spacer(Modifier.height(10.dp))
            if (s == null) {
                Text(stringResource(R.string.recset_storage_reading), color = Obsidian.TextMid)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(gb(s.usedBytes), style = MaterialTheme.typography.displaySmall, color = Obsidian.TextHigh)
                    Spacer(Modifier.width(8.dp))
                    Text("/ ${gb(s.totalBytes)} GB " + stringResource(R.string.store_used), style = MaterialTheme.typography.titleMedium, color = Obsidian.TextMid, modifier = Modifier.padding(bottom = 6.dp).weight(1f))
                    Text(stringResource(R.string.store_free, gb(s.freeBytes)), style = MaterialTheme.typography.labelLarge, color = Obsidian.Cyan, modifier = Modifier.padding(bottom = 8.dp))
                }
                Spacer(Modifier.height(10.dp))
                val other = (s.usedBytes - s.recordingsBytes - s.uploadQueueBytes - s.guideBytes - s.imageCacheBytes - s.otherCacheBytes).coerceAtLeast(0)
                val parts = listOf(
                    Triple(stringResource(R.string.store_part_recordings), s.recordingsBytes, Obsidian.Cyan),
                    Triple(stringResource(R.string.store_part_upload), s.uploadQueueBytes, Obsidian.Violet),
                    Triple(stringResource(R.string.store_part_guide), s.guideBytes, Obsidian.Magenta),
                    Triple(stringResource(R.string.store_part_images), s.imageCacheBytes + s.otherCacheBytes, Color(0xFF94A3B8)),
                )
                // Segmented bar: OpenTV's own parts in colour, everything else on the device dimmed.
                Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.06f))) {
                    val total = s.totalBytes.coerceAtLeast(1).toFloat()
                    parts.forEach { (_, bytes, color) ->
                        val w = bytes / total
                        if (w > 0.002f) Box(Modifier.weight(w).fillMaxHeight().background(color))
                    }
                    val w = other / total
                    if (w > 0.002f) Box(Modifier.weight(w).fillMaxHeight().background(Color.White.copy(alpha = 0.18f)))
                    val rest = s.freeBytes / total
                    if (rest > 0.002f) Spacer(Modifier.weight(rest))
                }
                Spacer(Modifier.height(12.dp))
                parts.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        pair.forEach { (label, bytes, color) ->
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(label, style = MaterialTheme.typography.bodySmall, color = Obsidian.TextMid)
                                    Text("${gb(bytes)} GB", style = MaterialTheme.typography.titleSmall, color = Obsidian.TextHigh)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                SmallButton(stringResource(R.string.store_clear_images)) {
                    scope.launch {
                        val freed = StorageManager.clearImageCache(context)
                        Toast.makeText(context, context.getString(R.string.store_cleared, gb(freed)), Toast.LENGTH_SHORT).show()
                        refresh++
                    }
                }
            }
        }

        // ---- Where recordings go -----------------------------------------------------------
        Section(stringResource(R.string.store_local_location), stats?.let { stringResource(R.string.store_ready, gb(it.freeBytes)) })
        Glass {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Obsidian.Elevated), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = Obsidian.Cyan)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.store_target_folder).uppercase(), style = MaterialTheme.typography.labelSmall, color = Obsidian.TextLow)
                    Text(
                        when (target) {
                            AppSettings.RecordingTarget.INTERNAL -> RecordingStorage.internalDir(context).absolutePath
                            AppSettings.RecordingTarget.USB -> "USB / SD · ${usbLabel ?: "–"}"
                            AppSettings.RecordingTarget.SMB -> "NAS · ${settings.smbHost.value}/${settings.smbShare.value}/${settings.smbFolder.value}"
                            AppSettings.RecordingTarget.CLOUD -> "☁ $providerName · /${settings.cloudFolder.value}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Obsidian.TextHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SmallButton(stringResource(R.string.store_change_folder), onClick = onOpenRecordingSettings)
            Spacer(Modifier.height(6.dp))
            RuleRow(
                null,
                stringResource(R.string.store_cleanup_title),
                stringResource(R.string.store_cleanup_desc, settings.cleanupThresholdGb),
                autoCleanup,
            ) { settings.setAutoCleanup(it) }
        }

        // ---- Sync across devices — through the user's own cloud, no account of ours --------
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Obsidian.VioletDeep.copy(alpha = 0.18f))
                .border(1.dp, Obsidian.Violet.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                .padding(18.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudSync, contentDescription = null, tint = Color(0xFFDBB8FF))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.store_sync_title), style = MaterialTheme.typography.titleMedium, color = Obsidian.TextHigh)
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.store_sync_desc), style = MaterialTheme.typography.bodyMedium, color = Obsidian.TextMid)
                Spacer(Modifier.height(10.dp))
                if (connected) {
                    Text(
                        stringResource(R.string.store_sync_connected, providerName, cloudUser, settings.syncDeviceId.take(8)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Obsidian.Cyan,
                    )
                } else {
                    SmallButton(stringResource(R.string.store_sync_connect), filled = true, onClick = onOpenRecordingSettings)
                }
            }
        }

        // ---- Cloud providers ---------------------------------------------------------------
        Section(stringResource(R.string.store_providers), if (connected) stringResource(R.string.store_one_connected) else null)
        Glass {
            if (connected) {
                ProviderRow(Icons.Filled.Cloud, providerName, cloudUser, connectedState = true, action = stringResource(R.string.store_disconnect)) {
                    settings.disconnectCloud()
                    CloudSync.schedule(context)
                }
                Spacer(Modifier.height(8.dp))
            }
            listOf(
                Triple("gdrive_koofr", stringResource(R.string.store_p_gdrive), stringResource(R.string.store_p_via_koofr)),
                Triple("dropbox_koofr", stringResource(R.string.store_p_dropbox), stringResource(R.string.store_p_via_koofr)),
                Triple("nextcloud", stringResource(R.string.store_p_webdav), stringResource(R.string.store_p_webdav_desc)),
            ).filter { !connected || it.first != cloudProvider }.forEach { (id, name, desc) ->
                ProviderRow(Icons.Filled.Cloud, name, desc, connectedState = false, action = stringResource(R.string.store_connect)) {
                    connect(id)
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(stringResource(R.string.store_providers_note), style = MaterialTheme.typography.bodySmall, color = Obsidian.TextLow)
        }

        // ---- Sync rules --------------------------------------------------------------------
        Section(stringResource(R.string.store_rules), null)
        Glass {
            RuleRow(Icons.Filled.Wifi, stringResource(R.string.store_rule_wifi), stringResource(R.string.store_rule_wifi_desc), wifiOnly) {
                settings.setSyncWifiOnly(it); CloudSync.schedule(context)
            }
            RuleRow(Icons.Filled.Cloud, stringResource(R.string.store_rule_upload), stringResource(R.string.store_rule_upload_desc, providerName), autoUpload) {
                settings.setCloudAutoUpload(it)
            }
            RuleRow(Icons.Filled.Sync, stringResource(R.string.store_rule_live), stringResource(R.string.store_rule_live_desc), liveSync) {
                settings.setCloudLiveSync(it); CloudSync.schedule(context)
            }
        }

        // ---- Sync now ----------------------------------------------------------------------
        Spacer(Modifier.height(18.dp))
        var focused by remember { mutableStateOf(false) }
        Row(
            Modifier
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .height(60.dp)
                .shadow(18.dp, RoundedCornerShape(16.dp), ambientColor = Obsidian.Cyan, spotColor = Obsidian.Cyan)
                .clip(RoundedCornerShape(16.dp))
                .background(if (connected) Obsidian.Cyan else Obsidian.Elevated)
                .border(if (focused) 2.dp else 0.dp, Color.White, RoundedCornerShape(16.dp))
                .onFocusChanged { focused = it.isFocused }
                .clickable(enabled = connected && !syncing) {
                    syncing = true
                    scope.launch {
                        val r = CloudSync(graph).sync(context)
                        syncing = false
                        refresh++
                        val msg = when (r) {
                            is CloudSync.Result.Success -> context.getString(R.string.store_sync_done, r.merged, r.peers, r.uploadsQueued)
                            is CloudSync.Result.Failed -> context.getString(R.string.recset_status_failed, r.message ?: "")
                            CloudSync.Result.NotConfigured -> context.getString(R.string.store_sync_connect)
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Sync, contentDescription = null, tint = if (connected) Obsidian.Canvas else Obsidian.TextLow)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(if (syncing) R.string.store_syncing else R.string.store_sync_now),
                style = MaterialTheme.typography.titleMedium,
                color = if (connected) Obsidian.Canvas else Obsidian.TextLow,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (lastSync == 0L) stringResource(R.string.store_never_synced)
            else stringResource(
                if (lastOk) R.string.store_last_sync_ok else R.string.store_last_sync_failed,
                DateUtils.getRelativeTimeSpanString(lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = Obsidian.TextMid,
            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(),
        )
    }
}

private fun gb(bytes: Long): String = String.format("%.1f", bytes / (1024.0 * 1024.0 * 1024.0))

@Composable
private fun Glass(content: @Composable () -> Unit) {
    Column(
        Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Obsidian.Surface)
            .border(1.dp, Obsidian.Hairline, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) { content() }
}

@Composable
private fun Section(title: String, trailing: String?) {
    Row(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Obsidian.TextHigh, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Obsidian.Cyan) }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun SmallButton(label: String, filled: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (filled) Obsidian.Canvas else Obsidian.TextHigh,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) Obsidian.Cyan else if (focused) Obsidian.CyanTint else Obsidian.Elevated)
            .border(if (focused) 2.dp else 1.dp, if (focused) Obsidian.Cyan else Obsidian.Hairline, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun ProviderRow(icon: ImageVector, name: String, desc: String, connectedState: Boolean, action: String, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Obsidian.Elevated.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Obsidian.Canvas), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (connectedState) Obsidian.Cyan else Obsidian.TextMid)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = Obsidian.TextHigh)
                if (connectedState) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Obsidian.Cyan))
                }
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Obsidian.TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        SmallButton(action, onClick = onAction)
    }
}

@Composable
private fun RuleRow(icon: ImageVector?, title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Obsidian.Cyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Obsidian.TextHigh)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Obsidian.TextMid)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Obsidian.Cyan, checkedThumbColor = Obsidian.Canvas),
        )
    }
}
