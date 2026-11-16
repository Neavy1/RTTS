package com.rtts.app.feature.transcript

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtts.app.extract.Kneeboard
import com.rtts.app.ui.theme.LocalRttsCabinColors

@Composable
internal fun KneeboardPanel(board: Kneeboard, callsign: String, onDefine: () -> Unit) {
    val c = LocalRttsCabinColors.current
    CabinPanel(Modifier.fillMaxWidth(), amber = true) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Lo último para mí", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.amber)
            if (callsign.isEmpty()) {
                TextButton(onClick = onDefine, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Define mi indicativo para ver aquí mis instrucciones")
                }
            } else {
                Text(board.updatedAtEpochMs?.let { "Actualizado ${utcTime(it)} UTC" } ?: "Sin instrucciones para mi indicativo",
                    color = c.muted, fontSize = 12.sp)
                val cells = listOf("Pista" to board.pista, "Altitud" to board.altitud, "QNH" to board.qnh,
                    "Turno" to board.turno, "Transp." to board.transpondedor, "Viento" to board.viento)
                for (row in cells.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, value) in row) Surface(modifier = Modifier.weight(1f),
                        color = c.panel2, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(label, color = c.muted, fontSize = 11.5.sp)
                            Text(value ?: "—", color = c.text, fontSize = 26.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TrafficPanel() {
    val c = LocalRttsCabinColors.current
    CabinPanel(Modifier.fillMaxWidth().heightIn(min = 150.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Tráfico cerca", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("Disponible con internet en una próxima versión", color = c.text2)
        }
    }
}
