package com.rtts.app.feature.review

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rtts.app.RttsApplication
import com.rtts.app.audio.WavFileWriter
import com.rtts.app.data.SessionEntity
import com.rtts.app.data.TranscriptSegmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val database = (context.applicationContext as RttsApplication).container.database
    val store = remember { ReviewStore(context) }
    val scope = rememberCoroutineScope()
    val allSegments = remember(database) { database.transcriptSegmentDao().observeAll() }
    val segments by allSegments.collectAsState(initial = emptyList())
    var revision by remember { mutableIntStateOf(0) }
    var reviews by remember { mutableStateOf<Map<Long, JSONObject>>(emptyMap()) }
    var selected by remember { mutableStateOf<TranscriptSegmentEntity?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    BackHandler { if (!busy) onBack() }
    var recorder by remember { mutableStateOf<ExampleRecorder?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    var preparedZip by rememberSaveable { mutableStateOf<File?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { recorder?.stop(); player?.release(); player = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); recorder?.stop(); player?.release() }
    }
    LaunchedEffect(segments, revision) {
        try { reviews = withContext(Dispatchers.IO) { segments.associate { it.id to store.read(it.id) } } }
        catch (e: Exception) { message = "No se pudieron leer las revisiones: ${e.message}" }
    }
    fun play(segment: TranscriptSegmentEntity, speed: Float = 1f, loop: Boolean = false) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(requireNotNull(segment.audioFilePath))
                setOnCompletionListener { it.release(); player = null }
                setOnErrorListener { p, _, _ -> p.release(); player = null; message = "No se pudo reproducir el audio"; true }
                isLooping = loop
                prepare()
                playbackParams = playbackParams.setSpeed(speed)
                start()
            }
        } catch (e: Exception) { player?.release(); player = null; message = "Audio no disponible: ${e.message}" }
    }
    fun recordExample() {
        if (busy) return
        player?.release(); player = null
        val capture = ExampleRecorder()
        recorder = capture; busy = true; seconds = 0
        message = "Pronuncia el ejemplo. Se guardará sin transcripción verificada (máximo 30 s)."
        scope.launch {
            try {
                val samples = withContext(Dispatchers.IO) { capture.record { seconds = it } }
                require(samples.size >= 16000) { "Graba al menos un segundo" }
                withContext(NonCancellable + Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val id = database.sessionDao().insert(SessionEntity(userId = 0, startedAtEpochMs = now - samples.size * 1000L / 16000,
                        endedAtEpochMs = now))
                    store.markSession(id, "spoken_example")
                    val file = File(context.filesDir, "examples/$id.wav")
                    WavFileWriter.write(file, samples)
                    database.transcriptSegmentDao().insert(TranscriptSegmentEntity(sessionId = id, startMs = 0,
                        endMs = samples.size * 1000L / 16000, speakerLabel = "Ejemplo grabado", text = "", lang = "",
                        createdAtEpochMs = now, audioFilePath = file.absolutePath))
                }
                message = "Ejemplo guardado. Abre Corregir, escucha y escribe lo pronunciado."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "No se pudo guardar: ${e.message}" }
            finally { recorder = null; busy = false }
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recordExample() else message = "Permite el micrófono para grabar un ejemplo."
    }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val zip = preparedZip
        preparedZip = null
        if (uri == null || zip == null) { zip?.delete(); busy = false; message = "Exportación cancelada o interrumpida. Puedes reintentar." }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "No se pudo abrir el destino" }.use { output ->
                        zip.inputStream().use { it.copyTo(output) }
                    }
                }
                message = "ZIP exportado. Puedes adjuntarlo en nuestra conversación."
            } catch (e: Exception) { message = "Falló la exportación; elimina el archivo parcial y reintenta: ${e.message}" }
            finally { zip.delete(); busy = false }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Revisión ATC") }, navigationIcon = {
        TextButton(onClick = onBack, enabled = !busy) { Text("Volver") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Escucha el audio y guarda lo que realmente se dijo. El texto del modelo se conserva sin cambios.")
            Text("${segments.size} registros · ${reviews.values.count { it.optString("status") == "verified" }} verificados")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !busy && segments.isNotEmpty(), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            preparedZip = withContext(Dispatchers.IO) { store.export(segments.toList()) }
                            destination.launch("RTTS-ATC-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip")
                        } catch (e: Exception) { preparedZip?.delete(); preparedZip = null; busy = false; message = "No se pudo exportar: ${e.message}" }
                    }
                }) { Text("Exportar registros ZIP") }
                OutlinedButton(enabled = !busy || recorder != null, onClick = {
                    if (recorder != null) recorder?.stop()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recordExample()
                    else microphone.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(if (recorder != null) "Detener y guardar · ${seconds}s" else "Grabar ejemplo") }
            }
            Text("El ZIP contiene todos los registros, audios, correcciones y nombres de revisor. Tú eliges dónde guardarlo; la app no lo envía.", style = MaterialTheme.typography.bodySmall)
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (segments.isEmpty()) item { Text("Importa un audio en la pantalla principal o graba un ejemplo para empezar.") }
                items(segments, key = { it.id }) { segment ->
                    val review = reviews[segment.id]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Sesión ${segment.sessionId} · Registro ${segment.id} · ${reviewStatus(review?.optString("status") ?: "pending")}")
                            Text("Modelo: ${segment.text.ifBlank { "Sin texto reconocido" }}")
                            if (!review?.optString("text").isNullOrBlank()) Text("Corrección: ${review?.optString("text")}")
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(enabled = !busy && segment.audioFilePath != null, onClick = { play(segment) }) { Text("Escuchar") }
                                TextButton(enabled = !busy, onClick = { selected = segment }) { Text("Corregir") }
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.let { segment ->
        ReviewEditor(segment, reviews[segment.id] ?: JSONObject(), onPlay = { speed, loop -> play(segment, speed, loop) },
            onDismiss = { selected = null }, onSave = { value ->
                withContext(Dispatchers.IO) { store.save(segment.id, value) }
                revision++; selected = null; message = "Corrección guardada en este dispositivo."
            })
    }
}

private fun reviewStatus(value: String) = when (value) {
    "verified" -> "Verificado"; "unintelligible" -> "Ininteligible"; "no_speech" -> "Sin voz"; else -> "Pendiente"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewEditor(segment: TranscriptSegmentEntity, initial: JSONObject, onPlay: (Float, Boolean) -> Unit,
                         onDismiss: () -> Unit, onSave: suspend (JSONObject) -> Unit) {
    var text by rememberSaveable(segment.id) { mutableStateOf(initial.optString("text")) }
    var display by rememberSaveable(segment.id) { mutableStateOf(initial.optString("display_text")) }
    var language by rememberSaveable(segment.id) { mutableStateOf("en") }
    var reviewer by rememberSaveable(segment.id) { mutableStateOf(initial.optString("reviewer")) }
    var notes by rememberSaveable(segment.id) { mutableStateOf(initial.optString("notes")) }
    var status by rememberSaveable(segment.id) { mutableStateOf(initial.optString("status", "pending")) }
    var noisy by rememberSaveable(segment.id) { mutableStateOf(initial.optBoolean("noisy")) }
    var clipped by rememberSaveable(segment.id) { mutableStateOf(initial.optBoolean("clipped")) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var playbackSpeed by rememberSaveable(segment.id) { mutableFloatStateOf(1f) }
    var loopPlayback by rememberSaveable(segment.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Revisar registro ${segment.id}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Modelo: ${segment.text.ifBlank { "Sin transcripción" }}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPlay(playbackSpeed, loopPlayback) }) { Text("Escuchar audio") }
                if (text.isBlank() && segment.text.isNotBlank() && segment.text != "[INAUDIBLE]") {
                    TextButton(onClick = { text = segment.text; status = "pending" }) { Text("Usar modelo como borrador") }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                listOf(.75f, 1f, 1.25f).forEach { value ->
                    FilterChip(selected = playbackSpeed == value, onClick = { playbackSpeed = value },
                        label = { Text("${value}×") })
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = loopPlayback, onCheckedChange = { loopPlayback = it })
                    Text("Repetir")
                }
            }
            OutlinedTextField(text, { text = it; status = "pending" }, label = { Text("Lo pronunciado, palabra por palabra") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedTextField(display, { display = it }, label = { Text("Cómo mostrarlo (opcional, p. ej. 121.5)") }, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("en" to "English ATC").forEach { (id, label) ->
                    FilterChip(selected = language == id, onClick = { language = id }, label = { Text(label) })
                }
            }
            OutlinedTextField(reviewer, { reviewer = it }, label = { Text("Revisado por") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notas: indicativos, frecuencias, dudas…") }, modifier = Modifier.fillMaxWidth())
            Row { Checkbox(noisy, { noisy = it }); Text("Ruido"); Checkbox(clipped, { clipped = it }); Text("Audio cortado") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("pending", "verified", "unintelligible", "no_speech").forEach { id ->
                    FilterChip(selected = status == id, onClick = { status = id }, label = { Text(reviewStatus(id)) })
                }
            }
            Text("Marca Verificado solo después de escuchar y corregir. Guardar no entrena automáticamente el modelo.")
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancelar") } },
        confirmButton = { Button(enabled = !saving, onClick = {
            saving = true
            scope.launch {
                try { onSave(JSONObject().put("text", text.trim()).put("display_text", display.trim()).put("language", language)
                    .put("reviewer", reviewer.trim()).put("notes", notes.trim()).put("status", status).put("noisy", noisy).put("clipped", clipped)) }
                catch (e: Exception) { error = e.message ?: "No se pudo guardar" }
                finally { saving = false }
            }
        }) { Text(if (saving) "Guardando…" else "Guardar revisión") } })
}
