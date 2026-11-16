package com.rtts.app.feature.transcript

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtts.app.ui.theme.LocalRttsCabinColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ControlBar(running: Boolean, stopping: Boolean, hasSegments: Boolean, scale: Float,
    onListen: () -> Unit, onStop: () -> Unit, onMark: () -> Unit, onScale: (Float) -> Unit,
    onSamples: () -> Unit, onPickFile: () -> Unit) {
    val c = LocalRttsCabinColors.current
    var expanded by remember { mutableStateOf(false) }
    CabinPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = if (running) onStop else onListen, enabled = !stopping,
                    modifier = Modifier.heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (running) c.amber else c.green,
                        contentColor = if (running) c.onAmber else MaterialTheme.colorScheme.background)) {
                    Text(if (stopping) "Terminando…" else if (running) "Detener" else "Escuchar radio", fontSize = 21.sp)
                }
                OutlinedButton(onClick = onMark, enabled = hasSegments, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Marcar este momento")
                }
                OutlinedButton(onClick = { onScale(-.1f) }, enabled = scale > .85f,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = "Letra más pequeña" }) { Text("A−") }
                OutlinedButton(onClick = { onScale(.1f) }, enabled = scale < 1.5f,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = "Letra más grande" }) { Text("A+") }
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Más") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("Muestras de prueba") }, enabled = !running,
                            onClick = { expanded = false; onSamples() }, modifier = Modifier.heightIn(min = 48.dp))
                        DropdownMenuItem(text = { Text("Elegir archivo…") }, enabled = !running,
                            onClick = { expanded = false; onPickFile() }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            Text("Ayuda de escucha: no reemplaza la radio ni la autorización de la torre",
                fontSize = 12.sp, color = c.muted)
        }
    }
}
