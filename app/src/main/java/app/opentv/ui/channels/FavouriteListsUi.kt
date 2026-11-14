/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.opentv.R
import app.opentv.data.model.FavouriteList

/**
 * "Add to list" for one channel: every user list with a ✓ when the channel is already in it —
 * OK toggles — plus "New list…" which creates a list and drops the channel straight in.
 */
@Composable
internal fun AddToListDialog(
    channelName: String,
    lists: List<FavouriteList>,
    containing: Set<Long>,
    onToggle: (listId: Long, isIn: Boolean) -> Unit,
    onCreate: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        ListNameDialog(
            title = stringResource(R.string.favlist_new_title),
            initial = "",
            onConfirm = { onCreate(it); naming = false; onDismiss() },
            onDismiss = { naming = false },
        )
        return
    }
    ListDialogFrame(onDismiss) {
        Text(
            stringResource(R.string.favlist_add_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            channelName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            lists.forEach { list ->
                val isIn = list.id in containing
                ListActionRow((if (isIn) "✓  " else "     ") + list.name, primary = isIn) {
                    onToggle(list.id, isIn)
                }
            }
            ListActionRow("+  " + stringResource(R.string.favlist_new), primary = lists.isEmpty()) { naming = true }
            ListActionRow(stringResource(R.string.common_done)) { onDismiss() }
        }
    }
}

/** Create, rename and delete the user's lists — opened from "Manage lists" in the rail. */
@Composable
internal fun ManageListsDialog(
    lists: List<FavouriteList>,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf<FavouriteList?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val current = picked

    when {
        creating -> ListNameDialog(
            title = stringResource(R.string.favlist_new_title),
            initial = "",
            onConfirm = { onCreate(it); creating = false },
            onDismiss = { creating = false },
        )
        current != null && renaming -> ListNameDialog(
            title = stringResource(R.string.favlist_rename),
            initial = current.name,
            onConfirm = { onRename(current.id, it); renaming = false; picked = null },
            onDismiss = { renaming = false },
        )
        current != null && confirmDelete -> ListDialogFrame({ confirmDelete = false }) {
            Text(
                stringResource(R.string.favlist_delete_confirm, current.name),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(14.dp))
            ListActionRow(stringResource(R.string.favlist_delete), primary = true) {
                onDelete(current.id); confirmDelete = false; picked = null
            }
            ListActionRow(stringResource(R.string.common_cancel)) { confirmDelete = false }
        }
        current != null -> ListDialogFrame({ picked = null }) {
            Text(current.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            ListActionRow(stringResource(R.string.favlist_rename)) { renaming = true }
            ListActionRow(stringResource(R.string.favlist_delete)) { confirmDelete = true }
            ListActionRow(stringResource(R.string.common_cancel)) { picked = null }
        }
        else -> ListDialogFrame(onDismiss) {
            Text(
                stringResource(R.string.favlist_manage),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (lists.isEmpty()) {
                    Text(
                        stringResource(R.string.favlist_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                lists.forEach { list -> ListActionRow(list.name) { picked = list } }
                ListActionRow("+  " + stringResource(R.string.favlist_new), primary = true) { creating = true }
                ListActionRow(stringResource(R.string.common_done)) { onDismiss() }
            }
        }
    }
}

/**
 * Name entry that works with nothing but a remote: the shared [OnScreenKeyboard]. The keyboard
 * types capitals, so the result is tidied to "Title Case" — "SPORT UK" becomes "Sport Uk".
 */
@Composable
internal fun ListNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(640.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                text.ifEmpty { "…" },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(14.dp))
            OnScreenKeyboard(
                onKey = { text = tidyName(text + it) },
                onSpace = { text += " " },
                onBackspace = { text = text.dropLast(1) },
                onClear = { text = "" },
            )
            Spacer(Modifier.height(14.dp))
            ListActionRow(stringResource(R.string.common_save), primary = true) {
                if (text.isNotBlank()) onConfirm(text.trim())
            }
            ListActionRow(stringResource(R.string.common_cancel)) { onDismiss() }
        }
    }
}

private fun tidyName(raw: String): String =
    raw.split(" ").joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.titlecase() }
    }

@Composable
private fun ListDialogFrame(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
    }
}

/** Same look as the guide's channel-menu rows, so the list dialogs feel native to the app. */
@Composable
internal fun ListActionRow(label: String, primary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        primary -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
