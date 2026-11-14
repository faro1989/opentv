/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.sync.BackupManager
import app.opentv.ui.theme.Obsidian
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** "Backup & Wiederherstellen": everything in one password-encrypted file, and back again. */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            message = runCatching { BackupManager.export(context, uri, password) }
                .fold({ context.getString(R.string.backup_exported) }, { context.getString(R.string.recset_status_failed, it.message ?: "") })
            busy = false
        }
    }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            message = runCatching { BackupManager.import(context, uri, password) }
                .fold({ context.getString(R.string.backup_imported, it) }, { context.getString(R.string.recset_status_failed, it.message ?: "") })
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.backup_desc), color = Obsidian.TextMid, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 820.dp))
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier.widthIn(max = 820.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Obsidian.Surface).padding(18.dp),
        ) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.backup_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.backup_password_hint), color = Obsidian.TextLow, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !busy && password.length >= 8, onClick = {
                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                    exportTo.launch("opentv-backup-$stamp.otvb")
                }) { Text(stringResource(R.string.backup_export)) }
                OutlinedButton(enabled = !busy && password.isNotEmpty(), onClick = { importFrom.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.backup_import))
                }
            }
            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Obsidian.Cyan, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
