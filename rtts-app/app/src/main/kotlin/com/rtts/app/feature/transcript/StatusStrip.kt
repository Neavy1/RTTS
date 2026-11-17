package com.rtts.app.feature.transcript

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtts.app.pipeline.CaptureStats
import com.rtts.app.i18n.LocalStrings
import com.rtts.app.ui.theme.LocalRttsCabinColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusStrip(running: Boolean, stopping: Boolean, live: Boolean,
    stats: CaptureStats, error: String?, permissionDenied: Boolean,
    callsign: String, onCallsign: () -> Unit, radioLanguage: String, onLanguage: () -> Unit) {
    val strings = LocalStrings.current
    val c = LocalRttsCabinColors.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    CabinPanel(Modifier.fillMaxWidth()) {
        FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text(strings.appName, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(strings.radioToText, color = c.muted, fontSize = 12.sp)
            }
            Surface(color = if (running) c.green.copy(alpha = .14f) else c.panel2,
                shape = MaterialTheme.shapes.large) {
                Text(when { stopping -> strings.finishing; running -> strings.listening; else -> strings.paused },
                    color = if (running) c.green else c.text2, modifier = Modifier.padding(12.dp))
            }
            Column {
                Text(strings.inputLevel, color = c.muted, fontSize = 12.sp)
                Canvas(Modifier.size(70.dp, 26.dp).semantics {
                    contentDescription = strings.inputLevel
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        if (running && !stopping) stats.inputLevel.coerceIn(0f, 1f) else 0f, 0f..1f)
                }) {
                    val level = if (running && !stopping) stats.inputLevel.coerceIn(0f, 1f) else 0f
                    for (i in 0..9) {
                        val h = size.height * (.2f + .8f * (i + 1) / 10f)
                        drawRect(if (level * 10 > i) c.green else c.line,
                            Offset(i * size.width / 10, size.height - h), Size(size.width / 10 - 2.dp.toPx(), h))
                    }
                }
            }
            Column {
                Text(strings.input, color = c.muted, fontSize = 12.sp)
                Text(if (!running) strings.noCapture else if (live) strings.radioAudio else strings.fileSamples, color = c.text2)
            }
            TextButton(onClick = onLanguage, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Column {
                    Text(strings.language, color = c.muted, fontSize = 12.sp)
                    Text(strings.radioLanguageLabel(radioLanguage), color = c.amber, fontSize = 17.sp)
                }
            }
            Column {
                Text(strings.utcTime, color = c.muted, fontSize = 12.sp)
                Text(utcTime(now), fontFamily = FontFamily.Monospace, fontSize = 17.sp)
            }
            TextButton(onClick = onCallsign, modifier = Modifier.heightIn(min = 48.dp)) {
                Column {
                    Text(strings.myCallsign, color = c.muted, fontSize = 12.sp)
                    Text(callsign.ifEmpty { strings.tapToSet }, color = c.amber, fontSize = 17.sp)
                }
            }
        }
        if (permissionDenied) Text(strings.microphonePermission,
            color = c.red, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        error?.let { Text(strings.captureFailed, color = c.red, modifier = Modifier.padding(12.dp)) }
        if (stopping) Text(strings.finishingTranscript, color = c.text2,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        if (stats.queuedSeconds > 0.0 || stats.droppedChunks > 0) {
            Text(strings.queueStatus("%.1f".format(stats.queuedSeconds), stats.droppedChunks),
                color = if (stats.droppedChunks > 0) c.red else c.text2,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
        }
    }
}
