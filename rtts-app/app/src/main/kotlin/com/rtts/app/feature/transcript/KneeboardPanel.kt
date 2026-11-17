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
import com.rtts.app.i18n.LocalStrings
import com.rtts.app.ui.theme.LocalRttsCabinColors

@Composable
internal fun KneeboardPanel(board: Kneeboard, callsign: String, onDefine: () -> Unit) {
    val strings = LocalStrings.current
    val c = LocalRttsCabinColors.current
    CabinPanel(Modifier.fillMaxWidth(), amber = true) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings.lastForMe, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.amber)
            if (callsign.isEmpty()) {
                TextButton(onClick = onDefine, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(strings.defineCallsign)
                }
            } else {
                Text(board.updatedAtEpochMs?.let { strings.updatedAt(utcTime(it)) } ?: strings.noInstructions,
                    color = c.muted, fontSize = 12.sp)
                val cells = listOf(strings.runway to board.pista, strings.altitude to board.altitud, strings.qnh to board.qnh,
                    strings.sequence to board.turno?.let(strings::sequenceLabel), strings.squawk to board.transpondedor, strings.wind to board.viento)
                for (row in cells.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, value) in row) Surface(modifier = Modifier.weight(1f),
                        color = c.panel2, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(label, color = c.muted, fontSize = 11.5.sp)
                            Text(value ?: strings.unavailable, color = c.text, fontSize = 26.sp,
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
    val strings = LocalStrings.current
    val c = LocalRttsCabinColors.current
    CabinPanel(Modifier.fillMaxWidth().heightIn(min = 150.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(strings.trafficNearby, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(strings.trafficComingSoon, color = c.text2)
        }
    }
}
