/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import android.app.Application
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.StreamKind
import app.opentv.data.model.shownName
import app.opentv.ui.theme.Obsidian
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The draft behind "Kanalnummern & Reihenfolge". Everything — order, numbers, hide, lock and the
 * three numbering switches — is edited on an in-memory copy; "Änderungen speichern" writes it in
 * one transaction, "Abbrechen" throws it away. One [Entry] is one logical channel (all its quality
 * variants move, hide and renumber together, like everywhere else in the app).
 */
class ChannelOrderViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val settings = graph.settings
    private val dao = graph.database.channels()

    data class Entry(
        val key: String,
        val channels: List<Channel>,
        val categoryId: String?,
        val providerNumber: Int?,
        val customNumber: Int?,
        val hidden: Boolean,
        val locked: Boolean,
    ) {
        val primary: Channel get() = channels.first()
        val name: String get() = primary.shownName
        val lockKey: String get() = "${primary.sourceId}:${primary.streamId}"
    }

    enum class Sort { IMPORT, NUMBER, NAME }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    private var original: List<Entry> = emptyList()

    val categories = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val loading = MutableStateFlow(true)
    val query = MutableStateFlow("")
    val category = MutableStateFlow<String?>(null)
    val movingKey = MutableStateFlow<String?>(null)

    val lcn = MutableStateFlow(settings.lcnNumbers.value)
    val forceCustom = MutableStateFlow(settings.forceCustomNumbers.value)
    val closeGaps = MutableStateFlow(settings.closeNumberGaps.value)

    init { load() }

    private fun load() = viewModelScope.launch {
        loading.value = true
        val (list, cats) = withContext(Dispatchers.IO) {
            val locked = settings.lockedChannels.value
            val groups = LinkedHashMap<String, MutableList<Channel>>()
            for (c in dao.allForManager(null)) {
                groups.getOrPut(c.groupKey.ifEmpty { "id${c.id}" }) { mutableListOf() } += c
            }
            val built = groups.map { (key, chans) ->
                val sorted = chans.sortedByDescending { it.qualityRank }
                val p = sorted.first()
                Entry(key, sorted, p.categoryId, p.number, p.customNumber, p.hidden, "${p.sourceId}:${p.streamId}" in locked)
            }
            val cats = graph.catalogRepository.observeCategories(StreamKind.LIVE).first()
                .distinctBy { it.id }.map { it.id to it.name }
            built to cats
        }
        _entries.value = list
        original = list
        categories.value = cats
        loading.value = false
    }

    /** The number this entry shows under the draft's switches (mirrors NumberingPolicy). */
    fun numberOf(e: Entry): Int? {
        val provider = e.providerNumber.takeIf { lcn.value }
        return if (forceCustom.value) e.customNumber ?: provider else provider ?: e.customNumber
    }

    fun visible(all: List<Entry>, q: String, cat: String?): List<Entry> = all.filter { e ->
        (cat == null || e.categoryId == cat) &&
            (q.isBlank() || e.name.contains(q, ignoreCase = true) || e.primary.name.contains(q, ignoreCase = true))
    }

    private fun update(key: String, f: (Entry) -> Entry) {
        _entries.value = _entries.value.map { if (it.key == key) f(it) else it }
    }

    /** One step up/down among the entries currently on screen (search / category respected). */
    fun move(key: String, delta: Int) {
        val all = _entries.value.toMutableList()
        val shown = visible(all, query.value, category.value)
        val at = shown.indexOfFirst { it.key == key }
        val neighbour = shown.getOrNull(at + delta) ?: return
        val i = all.indexOfFirst { it.key == key }
        val j = all.indexOfFirst { it.key == neighbour.key }
        all[i] = all[j].also { all[j] = all[i] }
        _entries.value = all
    }

    fun moveToEdge(key: String, top: Boolean) {
        val all = _entries.value.toMutableList()
        val e = all.firstOrNull { it.key == key } ?: return
        all.remove(e)
        if (top) all.add(0, e) else all.add(e)
        _entries.value = all
    }

    fun sortBy(mode: Sort) {
        _entries.value = when (mode) {
            // Import order: the order the playlist first arrived in (row ids are assigned in import order).
            Sort.IMPORT -> _entries.value.sortedBy { e -> e.channels.minOf { it.id } }
            Sort.NUMBER -> _entries.value.sortedWith(compareBy(nullsLast<Int>()) { numberOf(it) })
            Sort.NAME -> _entries.value.sortedBy { it.name.lowercase() }
        }
    }

    fun occupantOf(key: String, n: Int): Channel? =
        _entries.value.firstOrNull { it.key != key && numberOf(it) == n }?.primary

    fun nextFree(): Int {
        val used = _entries.value.mapNotNull { numberOf(it) }.toHashSet()
        var n = 1
        while (n in used) n++
        return n
    }

    fun setNumber(key: String, n: Int, swap: Boolean, syncOrder: Boolean) {
        val me = _entries.value.firstOrNull { it.key == key } ?: return
        val old = numberOf(me)
        val occupant = _entries.value.firstOrNull { it.key != key && numberOf(it) == n }
        if (occupant != null && swap) update(occupant.key) { it.copy(customNumber = old ?: nextFree()) }
        update(key) { it.copy(customNumber = n) }
        if (syncOrder) sortBy(Sort.NUMBER)
    }

    fun resetNumber(key: String) = update(key) { it.copy(customNumber = null) }
    fun toggleHidden(key: String) = update(key) { it.copy(hidden = !it.hidden) }
    fun toggleLocked(key: String) = update(key) { it.copy(locked = !it.locked) }

    /** "Zurücksetzen": provider numbers and the playlist's own order again. Still only a draft. */
    fun resetAll() {
        _entries.value = _entries.value.map { it.copy(customNumber = null) }
        sortBy(Sort.IMPORT)
    }

    val dirty: Boolean get() = _entries.value != original ||
        lcn.value != settings.lcnNumbers.value ||
        forceCustom.value != settings.forceCustomNumbers.value ||
        closeGaps.value != settings.closeNumberGaps.value

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        var list = _entries.value
        // "Lücken schließen": 1…n in the saved order, so a deleted channel leaves no hole.
        if (closeGaps.value) list = list.mapIndexed { i, e -> e.copy(customNumber = i + 1) }
        val before = original.associateBy { it.key }
        withContext(Dispatchers.IO) {
            graph.database.withTransaction {
                list.forEachIndexed { index, e ->
                    val old = before[e.key]
                    e.channels.forEach { c ->
                        if (c.sortIndex != index) dao.setSortIndex(c.id, index)
                        if (old == null || old.customNumber != e.customNumber) dao.setCustomNumber(c.id, e.customNumber)
                        if (old == null || old.hidden != e.hidden) dao.setHidden(c.id, e.hidden)
                    }
                }
            }
        }
        settings.setNumbering(lcn.value, forceCustom.value, closeGaps.value)
        // Locks for channels that aren't in this list (another source, say) are kept as they were.
        val keysHere = list.map { it.lockKey }.toSet()
        settings.setLockedChannels(
            settings.lockedChannels.value.filterNot { it in keysHere }.toSet() + list.filter { it.locked }.map { it.lockKey },
        )
        onDone()
    }
}

/**
 * "Kanalnummern & Reihenfolge" — the design's channel-order manager: numbering rules (LCN, own
 * numbers win, close gaps), search with voice input, category chips, quick sorts (playlist order,
 * 1–999, A–Z) and the channel cards themselves — drag handle, number (tap for the number pad),
 * logo, quality, lock, show/hide and a ⋮ menu. Remote: OK on ⠿ picks a card up, ↑/↓ carries it.
 */
@Composable
fun ChannelOrderScreen(onBack: () -> Unit, vm: ChannelOrderViewModel = viewModel()) {
    val context = LocalContext.current
    val all by vm.entries.collectAsState()
    val loading by vm.loading.collectAsState()
    val q by vm.query.collectAsState()
    val cat by vm.category.collectAsState()
    val cats by vm.categories.collectAsState()
    val moving by vm.movingKey.collectAsState()
    val lcn by vm.lcn.collectAsState()
    val force by vm.forceCustom.collectAsState()
    val gaps by vm.closeGaps.collectAsState()
    val shown = remember(all, q, cat, lcn, force) { vm.visible(all, q, cat) }
    var numberFor by remember { mutableStateOf<ChannelOrderViewModel.Entry?>(null) }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.query.value = it }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Obsidian.Canvas)) {
        val wide = maxWidth > 900.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 32.dp else 16.dp, vertical = 20.dp)) {
            // ---- Header ----------------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Obsidian.CyanTint), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.FormatListNumbered, contentDescription = null, tint = Obsidian.Cyan)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.order_title), style = MaterialTheme.typography.headlineSmall, color = Obsidian.TextHigh)
                    Text(stringResource(R.string.order_subtitle), style = MaterialTheme.typography.bodyMedium, color = Obsidian.TextMid)
                }
                ChipButton(Icons.Filled.Refresh, stringResource(R.string.order_reset)) { vm.resetAll() }
                Spacer(Modifier.width(10.dp))
                IconCircle(Icons.Filled.Close, stringResource(R.string.common_cancel), onBack)
            }
            Spacer(Modifier.height(16.dp))

            val controls: @Composable () -> Unit = {
                Column(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Obsidian.Surface)
                        .border(1.dp, Obsidian.Hairline, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    RuleSwitch(stringResource(R.string.order_lcn), stringResource(R.string.order_lcn_desc), if (lcn) stringResource(R.string.order_lcn_badge) else null, lcn) { vm.lcn.value = it }
                    RuleSwitch(stringResource(R.string.order_force), stringResource(R.string.order_force_desc), null, force) { vm.forceCustom.value = it }
                    RuleSwitch(stringResource(R.string.order_gaps), stringResource(R.string.order_gaps_desc), null, gaps) { vm.closeGaps.value = it }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = q,
                    onValueChange = { vm.query.value = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.order_search_hint), color = Obsidian.TextLow) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Obsidian.TextMid) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = stringResource(R.string.order_voice),
                            tint = Obsidian.TextMid,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                                runCatching {
                                    voice.launch(
                                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                                    )
                                }.onFailure { Toast.makeText(context, context.getString(R.string.order_no_voice), Toast.LENGTH_SHORT).show() }
                            }.padding(8.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(stringResource(R.string.order_all, all.size), cat == null) { vm.category.value = null }
                    cats.forEach { (id, name) -> FilterPill(name, cat == id) { vm.category.value = id } }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.order_tools).uppercase(), style = MaterialTheme.typography.labelSmall, color = Obsidian.TextLow, modifier = Modifier.weight(1f))
                    ChipButton(null, stringResource(R.string.order_sort_import)) { vm.sortBy(ChannelOrderViewModel.Sort.IMPORT) }
                    ChipButton(null, "# 1–999") { vm.sortBy(ChannelOrderViewModel.Sort.NUMBER) }
                    ChipButton(null, "A–Z") { vm.sortBy(ChannelOrderViewModel.Sort.NAME) }
                }
            }

            val list: @Composable (Modifier) -> Unit = { mod ->
                if (loading) {
                    Box(mod, contentAlignment = Alignment.Center) { Text(stringResource(R.string.order_loading), color = Obsidian.TextMid) }
                } else {
                    val state = rememberLazyListState()
                    LaunchedEffect(all, moving) {
                        val i = shown.indexOfFirst { it.key == moving }
                        if (i >= 0) {
                            val vis = state.layoutInfo.visibleItemsInfo
                            if (i <= (vis.firstOrNull()?.index ?: 0) || i >= (vis.lastOrNull()?.index ?: 0)) {
                                state.animateScrollToItem((i - 1).coerceAtLeast(0))
                            }
                        }
                    }
                    LazyColumn(mod, state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(shown, key = { it.key }) { e ->
                            OrderRow(
                                entry = e,
                                number = vm.numberOf(e),
                                moving = moving == e.key,
                                onPick = { vm.movingKey.value = if (moving == e.key) null else e.key },
                                onStep = { vm.move(e.key, it) },
                                onDrop = { vm.movingKey.value = null },
                                onNumber = { numberFor = e },
                                onLock = { vm.toggleLocked(e.key) },
                                onHide = { vm.toggleHidden(e.key) },
                                onTop = { vm.moveToEdge(e.key, top = true) },
                                onBottom = { vm.moveToEdge(e.key, top = false) },
                                onResetNumber = { vm.resetNumber(e.key) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }

            if (wide) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Column(Modifier.width(400.dp).verticalScroll(rememberScrollState())) { controls() }
                    list(Modifier.weight(1f).fillMaxSize())
                }
            } else {
                controls()
                Spacer(Modifier.height(10.dp))
                list(Modifier.weight(1f).fillMaxWidth())
            }

            // ---- Footer ----------------------------------------------------------------
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FooterButton(stringResource(R.string.common_cancel), filled = false, Modifier.weight(1f), onBack)
                FooterButton(stringResource(R.string.order_save), filled = true, Modifier.weight(1f)) {
                    vm.save {
                        Toast.makeText(context, context.getString(R.string.order_saved), Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                }
            }
        }
    }

    numberFor?.let { e ->
        ChannelNumberDialog(
            channelName = e.name,
            currentNumber = vm.numberOf(e),
            hasCustomNumber = e.customNumber != null,
            findOccupant = { vm.occupantOf(e.key, it) },
            suggestFree = { vm.nextFree() },
            onSave = { n, swap, sync -> vm.setNumber(e.key, n, swap, sync); numberFor = null },
            onReset = { vm.resetNumber(e.key); numberFor = null },
            onDismiss = { numberFor = null },
        )
    }
}

@Composable
private fun OrderRow(
    entry: ChannelOrderViewModel.Entry,
    number: Int?,
    moving: Boolean,
    onPick: () -> Unit,
    onStep: (Int) -> Unit,
    onDrop: () -> Unit,
    onNumber: () -> Unit,
    onLock: () -> Unit,
    onHide: () -> Unit,
    onTop: () -> Unit,
    onBottom: () -> Unit,
    onResetNumber: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focusInside by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val stepPx = with(LocalDensity.current) { 64.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    val shape = RoundedCornerShape(14.dp)
    val active = focusInside || moving
    Row(
        modifier
            .fillMaxWidth()
            .then(if (moving) Modifier.scale(1.02f) else Modifier)
            .clip(shape)
            .background(if (active) Obsidian.Elevated else Obsidian.Surface)
            .border(
                if (active) 2.dp else 1.dp,
                when { moving -> Obsidian.Violet; focusInside -> Obsidian.CyanGlow; else -> Obsidian.Hairline },
                shape,
            )
            .onFocusChanged { focusInside = it.hasFocus }
            .onPreviewKeyEvent { e ->
                if (!moving) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) when (e.key) {
                    Key.DirectionUp -> onStep(-1)
                    Key.DirectionDown -> onStep(1)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back, Key.Escape -> onDrop()
                    else -> Unit
                }
                true
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ⠿ — tap (or OK) to pick up for the remote; long-press and drag on touch screens.
        RowIcon(
            Icons.Filled.DragIndicator,
            stringResource(R.string.favlist_drag),
            tint = if (moving) Obsidian.Violet else Obsidian.TextLow,
            bg = if (moving) Obsidian.Violet.copy(alpha = 0.25f) else Color.Transparent,
            modifier = Modifier.pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { drag = 0f; onPick() },
                    onDragEnd = onDrop,
                    onDragCancel = onDrop,
                    onDrag = { change, amount ->
                        change.consume()
                        drag += amount.y
                        if (drag > stepPx) { onStep(1); drag -= stepPx }
                        if (drag < -stepPx) { onStep(-1); drag += stepPx }
                    },
                )
            },
            onClick = onPick,
        )
        Spacer(Modifier.width(6.dp))
        // Number box — OK opens the number pad. A pencil marks the user's own number.
        var numFocus by remember { mutableStateOf(false) }
        Box {
            Text(
                number?.let { "%03d".format(it) } ?: "–",
                style = MaterialTheme.typography.titleSmall,
                color = if (active) Obsidian.Cyan else Obsidian.TextHigh,
                modifier = Modifier
                    .width(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Obsidian.Canvas)
                    .border(if (numFocus) 2.dp else 1.dp, if (numFocus || active) Obsidian.Cyan else Obsidian.Hairline, RoundedCornerShape(10.dp))
                    .onFocusChanged { numFocus = it.isFocused }
                    .clickable(onClick = onNumber)
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (entry.customNumber != null) {
                Icon(
                    Icons.Filled.Edit, contentDescription = null, tint = Obsidian.Cyan,
                    modifier = Modifier.size(12.dp).align(Alignment.TopEnd),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(width = 52.dp, height = 36.dp).clip(RoundedCornerShape(8.dp)).background(Obsidian.Canvas)
                .border(1.dp, Obsidian.Hairline, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(entry.name), style = MaterialTheme.typography.labelMedium, color = Obsidian.TextHigh)
            AsyncImage(model = entry.primary.logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (entry.hidden) Obsidian.TextLow else Obsidian.TextHigh,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                entry.primary.qualityLabel.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(6.dp))
                    CardBadge(it.uppercase(), Obsidian.Cyan, Obsidian.CyanTint)
                }
            }
            Text(
                if (moving) stringResource(R.string.favlist_moving_hint)
                else if (entry.hidden) stringResource(R.string.order_hidden_label)
                else entry.primary.name,
                style = MaterialTheme.typography.bodySmall,
                color = if (moving) Obsidian.Violet else Obsidian.TextLow,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        RowIcon(
            if (entry.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            stringResource(R.string.order_lock),
            tint = if (entry.locked) Obsidian.Magenta else Obsidian.TextLow,
            onClick = onLock,
        )
        RowIcon(
            if (entry.hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            stringResource(R.string.order_visible),
            tint = if (entry.hidden) Obsidian.TextLow else Obsidian.TextMid,
            onClick = onHide,
        )
        Box {
            RowIcon(Icons.Filled.MoreVert, stringResource(R.string.order_more), tint = Obsidian.TextMid) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chnum_menu)) }, onClick = { menu = false; onNumber() })
                DropdownMenuItem(text = { Text(stringResource(R.string.order_to_top)) }, onClick = { menu = false; onTop() })
                DropdownMenuItem(text = { Text(stringResource(R.string.order_to_bottom)) }, onClick = { menu = false; onBottom() })
                if (entry.customNumber != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chnum_reset)) }, onClick = { menu = false; onResetNumber() })
                }
            }
        }
    }
}

@Composable
private fun RowIcon(
    icon: ImageVector,
    description: String,
    tint: Color,
    bg: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Obsidian.CyanTint else bg)
            .border(if (focused) 2.dp else 0.dp, if (focused) Obsidian.Cyan else Color.Transparent, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun RuleSwitch(title: String, desc: String, badge: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Obsidian.TextHigh)
                badge?.let { Spacer(Modifier.width(8.dp)); CardBadge(it.uppercase(), Obsidian.Cyan, Obsidian.CyanTint) }
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Obsidian.TextMid)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Obsidian.Cyan, checkedThumbColor = Obsidian.Canvas),
        )
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape)
            .background(if (selected || focused) Obsidian.CyanTint else Obsidian.Surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) Obsidian.Cyan else if (selected) Obsidian.CyanGlow else Obsidian.Hairline, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) { Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Obsidian.Cyan)); Spacer(Modifier.width(8.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected || focused) Obsidian.TextHigh else Obsidian.TextMid, maxLines = 1)
    }
}

@Composable
private fun ChipButton(icon: ImageVector?, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (focused) Obsidian.CyanTint else Obsidian.Elevated)
            .border(if (focused) 2.dp else 1.dp, if (focused) Obsidian.Cyan else Obsidian.Hairline, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = Obsidian.TextHigh, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = Obsidian.TextHigh, maxLines = 1)
    }
}

@Composable
private fun IconCircle(icon: ImageVector, description: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(50))
            .background(if (focused) Obsidian.Cyan else Obsidian.Elevated)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = if (focused) Obsidian.Canvas else Obsidian.TextHigh) }
}

@Composable
private fun FooterButton(label: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.height(56.dp).clip(RoundedCornerShape(16.dp))
            .background(if (filled) Obsidian.Cyan else Obsidian.Elevated)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Obsidian.Hairline, RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.titleMedium, color = if (filled) Obsidian.Canvas else Obsidian.TextHigh) }
}
