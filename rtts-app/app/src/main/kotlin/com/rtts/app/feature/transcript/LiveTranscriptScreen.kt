package com.rtts.app.feature.transcript

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rtts.app.RttsApplication
import com.rtts.app.data.TranscriptSegmentEntity
import com.rtts.app.pipeline.TranscriptionForegroundService
import com.rtts.app.ui.theme.RttsAmber
import com.rtts.app.ui.theme.colorForSpeaker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranscriptScreen() {
    val context = LocalContext.current
    val isRunning by TranscriptionForegroundService.isRunning.collectAsState()
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

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startTranscriptionOfFile(context, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RTTS", fontWeight = FontWeight.Bold)
                        Text(
                            "Transcripción de comunicación aeronáutica",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ControlBar(
                isRunning = isRunning,
                onStart = { startTranscriptionOfSamples(context) },
                onStop = { stopTranscription(context) },
                onPickFile = { filePicker.launch(arrayOf("audio/*", "video/mp4", "video/*")) },
            )

            if (segments.isEmpty()) {
                EmptyState(isRunning)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(segments) { segment -> TranscriptCard(segment) }
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
                        "EN VIVO",
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
            Text(
                latest.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ControlBar(
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPickFile: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isRunning) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Detener")
                }
                RecordingIndicator()
            } else {
                Button(onClick = onStart) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Muestras de prueba")
                }
                OutlinedButton(onClick = onPickFile) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Elegir archivo…")
                }
            }
        }
    }
}

@Composable
private fun RecordingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(MaterialTheme.colorScheme.error, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text("Procesando…", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(8.dp))
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun EmptyState(isRunning: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            if (isRunning) "Escuchando… el texto aparecerá aquí a medida que se detecten transmisiones."
            else "Inicia una captura para ver la transcripción en vivo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun TranscriptCard(segment: TranscriptSegmentEntity) {
    val speakerColor = colorForSpeaker(segment.speakerLabel)
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    SpeakerBadge(label = segment.speakerLabel, color = speakerColor)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "%02d:%02d".format((segment.startMs / 1000) / 60, (segment.startMs / 1000) % 60),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(8.dp))
                    LanguageChip(segment.lang)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    segment.text,
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
