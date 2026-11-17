package com.rtts.app.feature.transcript

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtts.app.data.TranscriptSegmentEntity
import com.rtts.app.extract.*
import com.rtts.app.i18n.LocalStrings
import com.rtts.app.ui.theme.LocalRttsCabinColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TransmissionCard(segment: TranscriptSegmentEntity, callsign: String, epochMs: Long,
    playing: Boolean, onPlay: () -> Unit, onBookmark: () -> Unit, showLanguage: Boolean = false,
    catalog: List<String> = callsignCatalog(), sessionDesignators: Map<String, String> = emptyMap()) {
    val strings = LocalStrings.current
    val c = LocalRttsCabinColors.current
    val mine = remember(segment.text, callsign) { isAddressedTo(segment.text, callsign) }
    val data = remember(segment.text) { extractInstruction(segment.text) }
    val resolved = remember(segment.text, catalog, sessionDesignators) {
        findCallsigns(segment.text).map { resolveCallsign(it, catalog, sessionDesignators) }
            .filter { it.confianza != Confianza.DESCONOCIDA }
    }
    Surface(color = if (mine) c.amberSoft else c.panel, contentColor = c.text,
        border = BorderStroke(1.dp, if (playing) c.magenta else c.line)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(if (mine) c.amber else c.line))
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(utcTime(epochMs), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = c.muted)
                    if (showLanguage && segment.lang in listOf("en", "es")) {
                        Surface(color = c.panel2, shape = MaterialTheme.shapes.small) {
                            Text(strings.radioLanguageLabel(segment.lang), fontSize = 11.sp, color = c.muted,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    Text(if (mine) strings.forMe else strings.radio, color = if (mine) c.amber else c.cyan,
                        fontWeight = FontWeight.SemiBold)
                }
                Text(segment.text, fontSize = 19.sp, lineHeight = 26.6.sp, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val callsignChips = resolved.map {
                        val corrected = it.confianza in listOf(Confianza.PARECIDA, Confianza.POR_SESION) &&
                            it.texto != it.original
                        strings.callsign to (if (corrected) strings.correctedCallsign(it.texto) else it.texto)
                    }
                    (callsignChips + listOf(strings.runway to data.pista, strings.altitude to data.altitud, strings.qnh to data.qnh,
                        strings.frequency to data.frecuencia, strings.squawk to data.transpondedor,
                        strings.wind to data.viento, strings.sequence to data.turno?.let(strings::sequenceLabel))).forEach { (label, value) ->
                        if (value != null) Surface(color = c.panel2, shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, c.line)) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(label, color = c.muted, fontSize = 12.sp)
                                Text(value, color = c.text, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(vertical = 6.dp)) {
                IconButton(onClick = onPlay, enabled = segment.audioFilePath != null, modifier = Modifier.size(48.dp)) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) strings.pauseTransmission else strings.replayTransmission,
                        tint = if (segment.audioFilePath == null) c.muted else if (playing) c.magenta else c.cyan)
                }
                IconToggleButton(checked = segment.bookmarked, onCheckedChange = { onBookmark() },
                    modifier = Modifier.size(48.dp)) {
                    Icon(if (segment.bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = if (segment.bookmarked) strings.unmarkTransmission else strings.markTransmission,
                        tint = if (segment.bookmarked) c.amber else c.text2)
                }
            }
        }
    }
}
