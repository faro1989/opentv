/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.shownName
import app.opentv.data.parser.displayTitle
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.VodViewModel
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Channel search with an on-screen keyboard.
 *
 * Android TV's default text field punts to "type on your phone", which is useless if your phone
 * isn't to hand. This screen draws its own d-pad keyboard so search works with the remote alone,
 * and shows matching channels live as you type.
 */
@Composable
fun SearchScreen(
    onPlayChannel: (Channel) -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onBack: () -> Unit,
    viewModel: ChannelsViewModel = viewModel(),
    vodViewModel: VodViewModel = viewModel(),
) {
    var query by remember { mutableStateOf("") }
    val channelResults by viewModel.searchResults.collectAsState()
    val movieResults by vodViewModel.movieResults.collectAsState()
    val seriesResults by vodViewModel.seriesResults.collectAsState()
    val anyResults = channelResults.isNotEmpty() || movieResults.isNotEmpty() || seriesResults.isNotEmpty()

    LaunchedEffect(query) {
        viewModel.setSearchQuery(query)
        vodViewModel.setVodSearchQuery(query)
    }
    BackHandler { onBack() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val isTv = remember { app.opentv.isRunningOnTelevision(context) }
    val graph = remember { app.opentv.core.ServiceLocator.get(context) }
    val activeRecordings by graph.recordingRepository.observeActive().collectAsState(initial = emptyList())
    val recordScope = androidx.compose.runtime.rememberCoroutineScope()
    val resultsFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    // Categories ("Rubriken") whose name matches the search, e.g. "iran" → "IRAN | ALL". Picking one
    // lists every channel in it on the right.
    val allCategories by viewModel.visibleCategoryGroups.collectAsState()
    val categoryRows by viewModel.rows.collectAsState()
    var pickedCategory by remember { mutableStateOf<ChannelsViewModel.CategoryGroup?>(null) }
    val matchingCategories = remember(query, allCategories) {
        val q = query.trim()
        if (q.length < 2) emptyList() else allCategories.filter { it.label.contains(q, ignoreCase = true) }
    }
    LaunchedEffect(query) { pickedCategory = null }

    val categoryColumn: @Composable (Modifier) -> Unit = { mod ->
        if (matchingCategories.isNotEmpty()) {
            Column(mod) {
                SectionHeader(stringResource(R.string.search_categories) + "  ·  ${matchingCategories.size}")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(matchingCategories, key = { "cat-" + it.key }) { group ->
                        CategoryChip(group.label, selected = pickedCategory?.key == group.key) {
                            pickedCategory = group
                            viewModel.selectCategory(group.key)
                            keyboard?.hide()
                        }
                    }
                }
            }
        }
    }

    // Record now / stop, right from a search result.
    val onRecord: (ChannelsViewModel.Row) -> Unit = { row ->
        val active = activeRecordings.firstOrNull { it.channelId == row.primary.id }
        if (active != null) {
            graph.recordingEngine.stop(active.id)
            android.widget.Toast.makeText(context, context.getString(R.string.search_rec_stopped), android.widget.Toast.LENGTH_SHORT).show()
        } else {
            recordScope.launch { runCatching { graph.recordingEngine.startChannel(row.primary, row.now) } }
            android.widget.Toast.makeText(context, context.getString(R.string.search_rec_started) + ": " + row.primary.shownName, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val results: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            val picked = pickedCategory
            when {
                picked != null -> LazyColumn(
                    Modifier.focusRequester(resultsFocus),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item { SectionHeader(picked.label + "  ·  ${categoryRows.size}") }
                    items(categoryRows, key = { "pc${it.key}" }) { row ->
                        SearchResultRow(
                            row = row,
                            recording = activeRecordings.any { it.channelId == row.primary.id },
                            onClick = { onPlayChannel(row.primary) },
                            onRecord = { onRecord(row) },
                        )
                    }
                }
                query.isBlank() -> Hint(stringResource(R.string.search_start_hint))
                query.trim().length < 2 -> Hint(stringResource(R.string.common_keep_typing))
                !anyResults && matchingCategories.isEmpty() -> Hint(stringResource(R.string.search_no_results, query))
                !anyResults -> Hint(stringResource(R.string.search_pick_category))
                else -> LazyColumn(
                    Modifier.focusRequester(resultsFocus),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (channelResults.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.common_channels) + "  ·  ${channelResults.size}") }
                        items(channelResults, key = { "c${it.key}" }) { row ->
                            SearchResultRow(
                                row = row,
                                recording = activeRecordings.any { it.channelId == row.primary.id },
                                onClick = { onPlayChannel(row.primary) },
                                onRecord = { onRecord(row) },
                            )
                        }
                    }
                    if (movieResults.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.nav_movies) + "  ·  ${movieResults.size}") }
                        items(movieResults, key = { "m${it.id}" }) { movie ->
                            VodResultRow(movie.displayTitle, movie.posterUrl, movie.year?.toString()) { onPlayMovie(movie) }
                        }
                    }
                    if (seriesResults.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.nav_shows) + "  ·  ${seriesResults.size}") }
                        items(seriesResults, key = { "s${it.id}" }) { show ->
                            VodResultRow(show.displayTitle, show.posterUrl, show.year?.toString()) { onOpenSeries(show) }
                        }
                    }
                }
            }
        }
    }

    if (!isTv) {
        // Phone / tablet: a normal search field with the system keyboard and its search key.
        val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) {
            // Wait for the screen to settle, then focus the field and raise the keyboard explicitly.
            kotlinx.coroutines.delay(250)
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(60) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_field_hint)) },
                    leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            androidx.compose.material3.IconButton(onClick = { query = "" }) {
                                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = stringResource(R.string.kbd_clear))
                            }
                        }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                        viewModel.setSearchQuery(query)
                        vodViewModel.setVodSearchQuery(query)
                        keyboard?.hide()
                    }),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.TextButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (matchingCategories.isNotEmpty()) {
                    categoryColumn(Modifier.width(130.dp).fillMaxHeight())
                    Spacer(Modifier.width(10.dp))
                }
                results(Modifier.weight(1f).fillMaxHeight())
            }
        }
        return
    }

    // TV: the remote-friendly on-screen keyboard on the left, results on the right.
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_search), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(20.dp))
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    "🔍  " + query.ifEmpty { stringResource(R.string.search_type_name) },
                    style = MaterialTheme.typography.titleLarge,
                    color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(20.dp))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxSize()) {
            OnScreenKeyboard(
                onKey = { if (query.length < 40) query += it },
                onSpace = { if (query.length < 40) query += " " },
                onBackspace = { query = query.dropLast(1) },
                onClear = { query = "" },
                onSearch = {
                    viewModel.setSearchQuery(query)
                    vodViewModel.setVodSearchQuery(query)
                    if (anyResults) runCatching { resultsFocus.requestFocus() }
                },
            )

            Spacer(Modifier.width(28.dp))

            if (matchingCategories.isNotEmpty()) {
                categoryColumn(Modifier.width(220.dp).fillMaxHeight())
                Spacer(Modifier.width(16.dp))
            }
            results(Modifier.weight(1f).fillMaxSize())
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        color = if (selected || focused) app.opentv.ui.theme.Obsidian.Canvas else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected || focused) app.opentv.ui.theme.Obsidian.Cyan else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun VodResultRow(name: String, posterUrl: String?, subtitle: String?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = posterUrl,
            contentDescription = null,
            modifier = Modifier.size(width = 34.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SearchResultRow(row: ChannelsViewModel.Row, onClick: () -> Unit, recording: Boolean = false, onRecord: () -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = row.primary.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.primary.shownName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.now?.let { "${searchTime(it.startUtcMillis)}  ${it.title}" } ?: stringResource(R.string.guide_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        // Record now (or stop) — focusable on its own, d-pad RIGHT from the row reaches it.
        var recFocused by remember { mutableStateOf(false) }
        Row(
            Modifier
                .onFocusChanged { recFocused = it.isFocused }
                .clip(RoundedCornerShape(50))
                .background(
                    when {
                        recFocused -> app.opentv.ui.theme.Obsidian.Magenta
                        recording -> app.opentv.ui.theme.Obsidian.Magenta.copy(alpha = 0.25f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .clickable(onClick = onRecord)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (recFocused) Color.White else app.opentv.ui.theme.Obsidian.Magenta))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (recording) R.string.search_record_stop else R.string.search_record),
                style = MaterialTheme.typography.labelLarge,
                color = if (recFocused) Color.White else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private val searchTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun searchTime(utcMillis: Long): String = searchTimeFormat.format(Date(utcMillis))
