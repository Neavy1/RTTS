package com.rtts.app.feature.transcript

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import com.rtts.app.data.TranscriptSegmentEntity
import com.rtts.app.extract.*
import com.rtts.app.pipeline.CaptureStats
import com.rtts.app.ui.theme.LocalCabinFontScale
import com.rtts.app.ui.theme.LocalRttsCabinColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun utcTime(epochMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(epochMs))

@Composable
internal fun CabinScreen(
    segments: List<TranscriptSegmentEntity>, sessionStartEpochMs: Long?,
    isRunning: Boolean, isStopping: Boolean, isLive: Boolean,
    captureStats: CaptureStats, captureError: String?, permissionDenied: Boolean,
    playingSegmentId: Long?, onListen: () -> Unit, onStop: () -> Unit,
    onSamples: () -> Unit, onPickFile: () -> Unit,
    onPlay: (TranscriptSegmentEntity) -> Unit,
    onBookmark: (TranscriptSegmentEntity, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(CABIN_PREFERENCES, Context.MODE_PRIVATE) }
    var radioLanguage by remember { mutableStateOf(prefs.radioLanguage()) }
    var editLanguage by remember { mutableStateOf(false) }
    var callsign by remember { mutableStateOf(prefs.getString("callsign", "").orEmpty()) }
    var scale by remember { mutableStateOf(prefs.getFloat("font_scale", 1f).let {
        if (it.isFinite()) it.coerceIn(.85f, 1.5f) else 1f
    }) }
    var editCallsign by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var onlyMine by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val c = LocalRttsCabinColors.current
    val transmissions = remember(segments, sessionStartEpochMs) {
        segments.map { KneeboardTransmission(it.text, sessionStartEpochMs?.plus(it.startMs) ?: it.createdAtEpochMs) }
    }
    val board = remember(transmissions, callsign) { buildKneeboard(transmissions, callsign) }
    val visible = remember(segments, onlyMine, callsign) {
        if (onlyMine) segments.filter { isAddressedTo(it.text, callsign) } else segments
    }
    val listState = rememberLazyListState()
    CompositionLocalProvider(
        LocalCabinFontScale provides scale,
        LocalDensity provides Density(density.density, density.fontScale * scale),
    ) {
        Surface(color = c.background, contentColor = c.text) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val wide = maxWidth >= 840.dp
                LaunchedEffect(visible.lastOrNull()?.id, onlyMine, callsign, wide) {
                    if (visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex + if (wide) 0 else 3)
                }
                val defineCallsign = { draft = callsign; editCallsign = true }
                // On compact screens a single lazy list lets the header and board scroll away.
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (wide) {
                        StatusStrip(isRunning, isStopping, isLive, captureStats, captureError,
                            permissionDenied, callsign, defineCallsign, radioLanguage, { editLanguage = true })
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CabinPanel(Modifier.weight(1.5f).fillMaxHeight()) {
                                TranscriptHeader(onlyMine) { onlyMine = it }
                                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                                    if (visible.isEmpty()) item { TranscriptEmpty(isRunning, onlyMine) }
                                    items(visible, key = { it.id }) { segment ->
                                        TransmissionCard(segment, callsign,
                                            sessionStartEpochMs?.plus(segment.startMs) ?: segment.createdAtEpochMs,
                                            segment.id == playingSegmentId, { onPlay(segment) },
                                            { onBookmark(segment, !segment.bookmarked) }, showLanguage = radioLanguage.isEmpty())
                                    }
                                }
                            }
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                KneeboardPanel(board, callsign, defineCallsign)
                                TrafficPanel()
                            }
                        }
                    } else {
                        // Header items precede transmissions, so scrolling uses the real item offset.
                        LazyColumn(state = listState, modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { StatusStrip(isRunning, isStopping, isLive, captureStats, captureError,
                                permissionDenied, callsign, defineCallsign, radioLanguage, { editLanguage = true }) }
                            item { KneeboardPanel(board, callsign, defineCallsign) }
                            item { TranscriptHeader(onlyMine) { onlyMine = it } }
                            if (visible.isEmpty()) item { TranscriptEmpty(isRunning, onlyMine) }
                            items(visible, key = { it.id }) { segment ->
                                TransmissionCard(segment, callsign,
                                    sessionStartEpochMs?.plus(segment.startMs) ?: segment.createdAtEpochMs,
                                    segment.id == playingSegmentId, { onPlay(segment) },
                                    { onBookmark(segment, !segment.bookmarked) }, showLanguage = radioLanguage.isEmpty())
                            }
                        }
                    }
                    ControlBar(isRunning, isStopping, segments.isNotEmpty(), scale,
                        onListen, onStop, { segments.lastOrNull()?.let { onBookmark(it, true) } },
                        { delta ->
                            scale = (scale + delta).coerceIn(.85f, 1.5f)
                            prefs.edit().putFloat("font_scale", scale).apply()
                        }, onSamples, onPickFile)
                }
            }
        }
        if (editLanguage) AlertDialog(
            onDismissRequest = { editLanguage = false },
            title = { Text("Idioma de la radio") },
            text = {
                Column {
                    if (isRunning) Text("El cambio se aplicará a la siguiente captura.")
                    Column(Modifier.selectableGroup()) {
                        listOf("en" to "Inglés", "es" to "Español", "" to "Automático").forEach { (value, label) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                    selected = radioLanguage == value,
                                    role = Role.RadioButton,
                                    onClick = {
                                        radioLanguage = value
                                        prefs.edit().putString(RADIO_LANGUAGE_KEY, value).apply()
                                    },
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = radioLanguage == value, onClick = null)
                                Text(label, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editLanguage = false },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Cerrar") } },
        )
        if (editCallsign) AlertDialog(
            onDismissRequest = { editCallsign = false },
            title = { Text("Mi indicativo") },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it },
                singleLine = true, label = { Text("Indicativo (por ejemplo HK-2947)") }) },
            confirmButton = { TextButton(onClick = {
                callsign = normalizeCallsign(draft)
                prefs.edit().putString("callsign", callsign).apply()
                editCallsign = false
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { editCallsign = false },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancelar") } },
        )
    }
}

@Composable
internal fun CabinPanel(modifier: Modifier = Modifier, amber: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    val c = LocalRttsCabinColors.current
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = c.panel,
        contentColor = c.text, border = BorderStroke(1.dp, if (amber) c.amber else c.line)) {
        Column(content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptHeader(onlyMine: Boolean, onFilter: (Boolean) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Lo que se oye", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 12.dp))
        FilterChip(selected = !onlyMine, onClick = { onFilter(false) }, label = { Text("Todo") },
            modifier = Modifier.heightIn(min = 48.dp))
        FilterChip(selected = onlyMine, onClick = { onFilter(true) }, label = { Text("Solo para mí") },
            modifier = Modifier.heightIn(min = 48.dp))
    }
}

@Composable
private fun TranscriptEmpty(running: Boolean, filtered: Boolean) {
    Text(if (filtered) "Aún no hay transmisiones para mi indicativo."
        else if (running) "Escuchando… el texto aparecerá aquí."
        else "Inicia una captura para ver la transcripción.", modifier = Modifier.padding(16.dp))
}
