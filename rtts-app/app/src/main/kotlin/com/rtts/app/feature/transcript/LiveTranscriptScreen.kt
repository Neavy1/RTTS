package com.rtts.app.feature.transcript

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Switch
import com.rtts.app.pipeline.CaptureStage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rtts.app.RttsApplication
import com.rtts.app.audio.FileAudioSource
import com.rtts.app.data.TranscriptSegmentEntity
import com.rtts.app.pipeline.TranscriptionForegroundService
import com.rtts.app.ui.theme.RttsAmber
import com.rtts.app.ui.theme.colorForSpeaker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranscriptScreen() {
    var reviewing by rememberSaveable { mutableStateOf(false) }
    if (reviewing) {
        com.rtts.app.feature.review.ReviewScreen(onBack = { reviewing = false })
        return
    }
    val context = LocalContext.current
    val isRunning by TranscriptionForegroundService.isRunning.collectAsState()
    val stage by TranscriptionForegroundService.stage.collectAsState()
    val error by TranscriptionForegroundService.error.collectAsState()
    val audioSeconds by TranscriptionForegroundService.audioSeconds.collectAsState()
    val sourceLabel by TranscriptionForegroundService.sourceLabel.collectAsState()
    var listenOriginal by remember { mutableStateOf(false) }
    val sessionId by TranscriptionForegroundService.currentSessionId.collectAsState()
    val container = (context.applicationContext as RttsApplication).container

    val segments by produceState(initialValue = emptyList<TranscriptSegmentEntity>(), sessionId) {
        val id = sessionId
        if (id == null) {
            value = emptyList()
        } else {
            container.database.transcriptSegmentDao().observeForSession(id).collect { value = it }
        }
    }

    val backgroundPlayer = remember { BackgroundAudioPlayer(context) }
    DisposableEffect(Unit) { onDispose { backgroundPlayer.release() } }
    var pendingPlayback by remember { mutableStateOf<PlaybackSource?>(null) }
    LaunchedEffect(stage, listenOriginal) {
        if (stage == CaptureStage.TRANSCRIBING && listenOriginal) {
            pendingPlayback?.let { backgroundPlayer.play(it) }
        } else backgroundPlayer.stop()
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingPlayback = PlaybackSource.PickedFile(uri)
            startTranscriptionOfFile(context, uri)
        }
    }

    val player = remember { SegmentAudioPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val playingSegmentId by player.playingSegmentId

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RTTS  /  Radio a texto", fontWeight = FontWeight.Bold)
                        Text(
                            "Transcripción de comunicación aeronáutica",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(onClick = { reviewing = true }, enabled = !isRunning) {
                        Text("Revisión ATC", color = Color.White)
                    }
                    Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
                        Text("SIN CONEXIÓN", color = Color.White, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    Spacer(Modifier.width(20.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = isRunning && segments.isNotEmpty()) {
                LiveCaptionBar(segments.last())
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 1100.dp).fillMaxWidth().fillMaxHeight().padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(24.dp))
                SessionOverview(stage, error, sourceLabel, audioSeconds, segments.size)
                ControlBar(
                    isRunning = isRunning,
                    stage = stage,
                    listenOriginal = listenOriginal,
                    onListenOriginal = { listenOriginal = it },
                    onStart = {
                        pendingPlayback = PlaybackSource.Samples
                        startTranscriptionOfSamples(context)
                    },
                    onStop = { stopTranscription(context) },
                    onPickFile = { filePicker.launch(arrayOf("audio/*", "video/mp4", "video/*")) },
                )
                if (segments.isEmpty()) {
                    EmptyState(stage)
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Transcripciones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${segments.size} segmentos", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(segments, key = { it.id }) { segment ->
                            TranscriptCard(
                                segment = segment,
                                isPlaying = segment.id == playingSegmentId,
                                onPlayToggle = {
                                    listenOriginal = false
                                    backgroundPlayer.stop()
                                    player.toggle(segment)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pinned "now playing" caption for the most recent transmission, styled after aerodrome
 * signage (black-on-yellow) for maximum legibility at a glance -- inspired by how live-ATC
 * apps (e.g. ATC.app) surface the current transmission separately from the scrollable history.
 */
@Composable
private fun LiveCaptionBar(latest: TranscriptSegmentEntity) {
    Surface(color = RttsAmber, contentColor = Color.Black) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = Color.Black, shape = RoundedCornerShape(6.dp)) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        "ÚLTIMO",
                        color = RttsAmber,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "%02d:%02d".format((latest.startMs / 1000) / 60, (latest.startMs / 1000) % 60),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            TranscriptText(
                text = latest.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
        }
    }
}

private fun CaptureStage.title(): String = when (this) {
    CaptureStage.IDLE -> "Listo para transcribir"
    CaptureStage.PREPARING -> "Preparando reconocimiento"
    CaptureStage.TRANSCRIBING -> "Transcribiendo audio"
    CaptureStage.STOPPING -> "Cerrando la sesión"
    CaptureStage.COMPLETED -> "Sesión finalizada"
    CaptureStage.ERROR -> "No se pudo completar"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionOverview(stage: CaptureStage, error: String?, source: String, seconds: Long, count: Int) {
    val busy = stage in listOf(CaptureStage.PREPARING, CaptureStage.TRANSCRIBING, CaptureStage.STOPPING)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(stage.title(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(when (stage) {
                CaptureStage.PREPARING -> "El modelo se carga en la tablet. La primera carga puede tardar; puedes detenerla."
                CaptureStage.STOPPING -> "Terminando el segmento en curso y guardando la sesión."
                CaptureStage.ERROR -> error ?: "Vuelve a intentarlo con otro archivo."
                CaptureStage.IDLE -> "Elige una grabación o prueba los audios de radio incluidos."
                else -> source
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("%02d:%02d de audio recibido".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.labelLarge)
                Text("$count segmentos", style = MaterialTheme.typography.labelLarge)
                Text("Procesamiento local", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlBar(
    isRunning: Boolean, stage: CaptureStage,
    listenOriginal: Boolean, onListenOriginal: (Boolean) -> Unit,
    onStart: () -> Unit, onStop: () -> Unit, onPickFile: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isRunning) {
                Button(onClick = onStop, enabled = stage != CaptureStage.STOPPING,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (stage == CaptureStage.STOPPING) "Finalizando…" else "Detener y guardar")
                }
            } else {
                Button(onClick = onPickFile) {
                    Icon(Icons.Filled.FolderOpen, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Abrir grabación")
                }
                OutlinedButton(onClick = onStart) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Probar con radio")
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                Text("English ATC · fijo", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 12.dp)) {
                Switch(checked = listenOriginal, onCheckedChange = onListenOriginal)
                Text("Escuchar original", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (listenOriginal) Text("La reproducción puede adelantarse a la transcripción.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState(stage: CaptureStage) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
            Icon(Icons.Filled.Mic, null, Modifier.padding(20.dp).size(32.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(20.dp))
        Text(if (stage == CaptureStage.TRANSCRIBING) "Esperando el primer segmento" else "Cada transmisión, en su lugar",
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("El texto y su audio aparecerán aquí para que puedas revisarlos.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Text("ENGLISH ATC  ·  AUDIO EN TU TABLET", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TranscriptCard(
    segment: TranscriptSegmentEntity,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
) {
    val speakerColor = colorForSpeaker(segment.speakerLabel)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (isPlaying) it.border(2.dp, RttsAmber, MaterialTheme.shapes.medium) else it
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxWidth()
                    .background(speakerColor),
            )
            Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeakerBadge(label = if (segment.speakerLabel == "Desconocido") "Sin identificar" else segment.speakerLabel, color = speakerColor)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "%02d:%02d".format((segment.startMs / 1000) / 60, (segment.startMs / 1000) % 60),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(8.dp))
                    LanguageChip(segment.lang)
                    if (segment.audioFilePath != null) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = onPlayToggle,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                if (segment.text == "[INAUDIBLE]") Text("Revisar audio · no se obtuvo texto fiable",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                TranscriptText(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SpeakerBadge(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(20.dp).background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.take(1).uppercase(),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LanguageChip(lang: String) {
    val label = when (lang) {
        "es" -> "ES"
        "en" -> "EN"
        "" -> "?"
        else -> lang.uppercase()
    }
    Surface(
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Recognized words are immediately readable, including in the latest-segment bar. */
@Composable
private fun TranscriptText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    // Show recognized text immediately; animation must not hide words already available.
    Text(text = text, modifier = modifier, style = style, fontWeight = fontWeight, maxLines = maxLines)
}

/** What to play in the background for the capture that was just started. */
private sealed interface PlaybackSource {
    data object Samples : PlaybackSource
    data class PickedFile(val uri: Uri) : PlaybackSource
}

/**
 * Plays the original source audio in the background while the pipeline transcribes it, so an
 * operator hears the transmission as captions settle in. Independent from [SegmentAudioPlayer],
 * which replays one already-finished segment on demand -- starting one stops the other so they
 * never talk over each other.
 */
private class BackgroundAudioPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

    fun play(source: PlaybackSource) {
        stop()
        when (source) {
            is PlaybackSource.Samples -> {
                val player = MediaPlayer().apply { setOnErrorListener { _, _, _ -> true } }
                mediaPlayer = player
                playSampleSequence(player, FileAudioSource.DEFAULT_SAMPLES, 0)
            }
            is PlaybackSource.PickedFile -> {
                mediaPlayer = MediaPlayer().apply {
                    setOnErrorListener { _, _, _ -> true }
                    setOnCompletionListener { it.release() }
                    setDataSource(context, source.uri)
                    prepare()
                    start()
                }
            }
        }
    }

    private fun playSampleSequence(player: MediaPlayer, assets: List<String>, index: Int) {
        if (index >= assets.size) return
        context.assets.openFd(assets[index]).use { afd ->
            player.reset()
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
        player.setOnCompletionListener { playSampleSequence(player, assets, index + 1) }
        player.prepare()
        player.start()
    }

    fun stop() {
        mediaPlayer?.let {
            it.setOnCompletionListener(null)
            it.release()
        }
        mediaPlayer = null
    }

    fun release() = stop()
}

/**
 * Plays back one transcript segment's saved audio clip at a time, so an operator can listen
 * and verify a transmission against its transcription. Segment-level only (no word-level
 * highlighting) -- our STT engine doesn't return word timestamps.
 */
private class SegmentAudioPlayer {
    val playingSegmentId = mutableStateOf<Long?>(null)
    private var mediaPlayer: MediaPlayer? = null

    fun toggle(segment: TranscriptSegmentEntity) {
        val path = segment.audioFilePath ?: return
        if (playingSegmentId.value == segment.id) {
            stop()
            return
        }
        stop()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(path)
            setOnCompletionListener { stop() }
            setOnErrorListener { _, _, _ -> stop(); true }
            prepare()
            start()
        }
        playingSegmentId.value = segment.id
    }

    private fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
        playingSegmentId.value = null
    }

    fun release() = stop()
}

private fun startTranscriptionOfSamples(context: Context) {
    val intent = Intent(context, TranscriptionForegroundService::class.java).apply {
        putExtra(TranscriptionForegroundService.EXTRA_USE_FILE_SOURCE, true)
    }
    context.startForegroundService(intent)
}

private fun startTranscriptionOfFile(context: Context, uri: Uri) {
    context.contentResolver.takePersistableUriPermission(
        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
    )
    val intent = Intent(context, TranscriptionForegroundService::class.java).apply {
        putExtra(TranscriptionForegroundService.EXTRA_FILE_URI, uri.toString())
    }
    context.startForegroundService(intent)
}

private fun stopTranscription(context: Context) {
    val intent = Intent(context, TranscriptionForegroundService::class.java).apply {
        action = TranscriptionForegroundService.ACTION_STOP
    }
    context.startService(intent)
}
