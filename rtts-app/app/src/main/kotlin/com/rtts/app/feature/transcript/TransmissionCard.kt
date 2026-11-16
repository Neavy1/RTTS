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
import com.rtts.app.extract.extractInstruction
import com.rtts.app.extract.isAddressedTo
import com.rtts.app.ui.theme.LocalRttsCabinColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TransmissionCard(segment: TranscriptSegmentEntity, callsign: String, epochMs: Long,
    playing: Boolean, onPlay: () -> Unit, onBookmark: () -> Unit) {
    val c = LocalRttsCabinColors.current
    val mine = remember(segment.text, callsign) { isAddressedTo(segment.text, callsign) }
    val data = remember(segment.text) { extractInstruction(segment.text) }
    Surface(color = if (mine) c.amberSoft else c.panel, contentColor = c.text,
        border = BorderStroke(1.dp, if (playing) c.magenta else c.line)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(if (mine) c.amber else c.line))
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(utcTime(epochMs), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = c.muted)
                    Text(if (mine) "Para mí" else "Radio", color = if (mine) c.amber else c.cyan,
                        fontWeight = FontWeight.SemiBold)
                }
                Text(segment.text, fontSize = 19.sp, lineHeight = 26.6.sp, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Pista" to data.pista, "Altitud" to data.altitud, "QNH" to data.qnh,
                        "Frecuencia" to data.frecuencia, "Transp." to data.transpondedor,
                        "Viento" to data.viento, "Turno" to data.turno).forEach { (label, value) ->
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
                        contentDescription = if (playing) "Pausar transmisión" else "Volver a oír esta transmisión",
                        tint = if (segment.audioFilePath == null) c.muted else if (playing) c.magenta else c.cyan)
                }
                IconToggleButton(checked = segment.bookmarked, onCheckedChange = { onBookmark() },
                    modifier = Modifier.size(48.dp)) {
                    Icon(if (segment.bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = if (segment.bookmarked) "Quitar marca de esta transmisión" else "Marcar esta transmisión",
                        tint = if (segment.bookmarked) c.amber else c.text2)
                }
            }
        }
    }
}
