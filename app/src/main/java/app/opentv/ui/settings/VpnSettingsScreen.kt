/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.ui.theme.Obsidian
import app.opentv.vpn.VpnController
import app.opentv.vpn.VpnProfile
import kotlinx.coroutines.launch

/**
 * VPN settings: pick the provider, fill in (or import) its WireGuard data, choose whether only
 * OpenTV or the whole box goes through the tunnel, and connect. Styled like the rest of the
 * Obsidian Stream UI: status card with a glowing dot, provider chips, glass input fields.
 */
@Composable
fun VpnSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vpn = remember { VpnController.get(context) }
    val status by vpn.status.collectAsState()
    val error by vpn.error.collectAsState()
    val saved by vpn.profile.collectAsState()
    val scope = rememberCoroutineScope()

    var draft by remember(saved) { mutableStateOf(saved) }
    var formError by remember { mutableStateOf<String?>(null) }

    fun saveDraft(): Boolean {
        val problem = vpn.validate(draft)
        formError = problem
        if (problem == null) vpn.save(draft)
        return problem == null
    }

    // Android asks once, system-wide, before any app may open a VPN. Connect right after a yes.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) scope.launch { vpn.connect(draft) }
        else Toast.makeText(context, context.getString(R.string.vpn_permission_denied), Toast.LENGTH_LONG).show()
    }

    fun connect() {
        if (!saveDraft()) return
        val ask = VpnService.prepare(context)
        if (ask != null) permission.launch(ask) else scope.launch { vpn.connect(draft) }
    }

    // Import the provider's .conf from storage (USB stick, Downloads…).
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        val parsed = text?.let { VpnController.parseConfig(it, draft) }
        if (parsed != null) {
            draft = parsed
            Toast.makeText(context, context.getString(R.string.vpn_imported), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.vpn_import_failed), Toast.LENGTH_LONG).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.vpn_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }
        Spacer(Modifier.height(16.dp))

        // ---- Status card -------------------------------------------------------------------
        val (dot, label) = when (status) {
            VpnController.Status.CONNECTED -> Obsidian.Cyan to stringResource(R.string.vpn_status_connected)
            VpnController.Status.CONNECTING -> Obsidian.Violet to stringResource(R.string.vpn_status_connecting)
            VpnController.Status.ERROR -> Obsidian.Magenta to stringResource(R.string.vpn_status_error)
            VpnController.Status.DISCONNECTED -> Obsidian.TextLow to stringResource(R.string.vpn_status_disconnected)
        }
        Row(
            Modifier
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Obsidian.Surface)
                .border(1.dp, if (status == VpnController.Status.CONNECTED) Obsidian.CyanGlow else Obsidian.Hairline, RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(dot))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                val sub = when {
                    status == VpnController.Status.ERROR && error != null -> error!!
                    saved.isComplete -> "${VpnController.PROVIDERS.firstOrNull { it.id == saved.provider }?.name ?: ""} · ${saved.endpoint}"
                    else -> stringResource(R.string.vpn_not_configured)
                }
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = Obsidian.TextMid)
            }
            if (status == VpnController.Status.CONNECTED || status == VpnController.Status.CONNECTING) {
                OutlinedButton(onClick = { scope.launch { vpn.disconnect() } }) { Text(stringResource(R.string.vpn_disconnect)) }
            } else {
                Button(onClick = { connect() }, enabled = draft.isComplete) { Text(stringResource(R.string.vpn_connect)) }
            }
        }

        Spacer(Modifier.height(22.dp))

        // ---- Provider ----------------------------------------------------------------------
        SectionTitle(stringResource(R.string.vpn_provider))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VpnController.PROVIDERS.forEach { p ->
                ProviderChip(p.name, selected = draft.provider == p.id) {
                    // Pre-fill the provider's DNS only when the user hasn't typed their own.
                    val keepDns = draft.dns.isNotBlank() && VpnController.PROVIDERS.none { it.dns == draft.dns }
                    draft = draft.copy(provider = p.id, dns = if (keepDns) draft.dns else p.dns)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(providerHint(draft.provider)),
            style = MaterialTheme.typography.bodyMedium,
            color = Obsidian.TextMid,
            modifier = Modifier.widthIn(max = 900.dp),
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { importFile.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.vpn_import_file)) }
            OutlinedButton(onClick = {
                val text = readClipboard(context)
                val parsed = text?.let { VpnController.parseConfig(it, draft) }
                if (parsed != null) draft = parsed
                Toast.makeText(
                    context,
                    context.getString(if (parsed != null) R.string.vpn_imported else R.string.vpn_import_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }) { Text(stringResource(R.string.vpn_import_clipboard)) }
        }

        Spacer(Modifier.height(20.dp))

        // ---- WireGuard fields --------------------------------------------------------------
        SectionTitle(stringResource(R.string.vpn_section_interface))
        VpnField(stringResource(R.string.vpn_private_key), draft.privateKey, secret = true) { draft = draft.copy(privateKey = it) }
        VpnField(stringResource(R.string.vpn_address), draft.address, hint = "10.64.12.34/32") { draft = draft.copy(address = it) }
        VpnField(stringResource(R.string.vpn_dns), draft.dns, hint = "10.64.0.1") { draft = draft.copy(dns = it) }
        Spacer(Modifier.height(12.dp))
        SectionTitle(stringResource(R.string.vpn_section_server))
        VpnField(stringResource(R.string.vpn_public_key), draft.publicKey) { draft = draft.copy(publicKey = it) }
        VpnField(stringResource(R.string.vpn_endpoint), draft.endpoint, hint = "de-fra-wg-001.example.net:51820") { draft = draft.copy(endpoint = it) }
        VpnField(stringResource(R.string.vpn_preshared), draft.presharedKey, secret = true) { draft = draft.copy(presharedKey = it) }
        VpnField(stringResource(R.string.vpn_allowed_ips), draft.allowedIps) { draft = draft.copy(allowedIps = it) }

        Spacer(Modifier.height(12.dp))
        SwitchRow(
            stringResource(R.string.vpn_only_app_title),
            stringResource(R.string.vpn_only_app_desc),
            draft.onlyThisApp,
        ) { draft = draft.copy(onlyThisApp = it) }
        SwitchRow(
            stringResource(R.string.vpn_autoconnect_title),
            stringResource(R.string.vpn_autoconnect_desc),
            draft.autoConnect,
        ) { draft = draft.copy(autoConnect = it) }
        SwitchRow(
            stringResource(R.string.vpn_kill_title),
            stringResource(R.string.vpn_kill_desc),
            draft.killSwitch,
        ) { draft = draft.copy(killSwitch = it) }

        formError?.let {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.vpn_invalid, it), color = Obsidian.Magenta, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (saveDraft()) Toast.makeText(context, context.getString(R.string.vpn_saved), Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.common_save)) }
            if (status != VpnController.Status.CONNECTED) {
                OutlinedButton(onClick = { connect() }, enabled = draft.isComplete) { Text(stringResource(R.string.vpn_save_connect)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.vpn_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = Obsidian.TextLow,
            modifier = Modifier.widthIn(max = 900.dp),
        )
    }
}

private fun providerHint(id: String): Int = when (id) {
    "mullvad" -> R.string.vpn_hint_mullvad
    "proton" -> R.string.vpn_hint_proton
    "nord" -> R.string.vpn_hint_nord
    "surfshark" -> R.string.vpn_hint_surfshark
    "ivpn" -> R.string.vpn_hint_ivpn
    "windscribe" -> R.string.vpn_hint_windscribe
    else -> R.string.vpn_hint_custom
}

private fun readClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Obsidian.Cyan,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun VpnField(
    label: String,
    value: String,
    hint: String = "",
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (hint.isNotEmpty()) Text(hint, color = Obsidian.TextLow) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(bottom = 8.dp),
    )
}

@Composable
private fun ProviderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected || focused) Obsidian.CyanTint else Obsidian.Surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) Obsidian.Cyan else if (selected) Obsidian.CyanGlow else Obsidian.Hairline, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Obsidian.Cyan))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected || focused) Color.White else Obsidian.TextMid,
        )
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Obsidian.Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = Obsidian.TextMid)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
