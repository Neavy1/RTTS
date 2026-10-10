package com.rtts.app.feature.review

import android.content.Context
import android.util.AtomicFile
import com.rtts.app.audio.PcmWavReader
import com.rtts.app.data.TranscriptSegmentEntity
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Corrections are kept separate from immutable recognizer output; no Room migration needed. */
class ReviewStore(private val context: Context) {
    private val folder = File(context.filesDir, "atc-reviews").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("atc-review", Context.MODE_PRIVATE)
    private fun file(id: Long) = AtomicFile(File(folder, "$id.json"))

    fun read(id: Long): JSONObject = if (file(id).baseFile.exists())
        file(id).openRead().use { JSONObject(it.bufferedReader().readText()) } else JSONObject()

    fun save(id: Long, value: JSONObject) {
        require(value.optString("status") in listOf("pending", "verified", "unintelligible", "no_speech"))
        if (value.optString("status") in listOf("verified", "no_speech")) {
            require(value.optString("reviewer").isNotBlank()) { "Indica quién revisó el audio" }
        }
        if (value.optString("status") == "verified") {
            require(value.optString("text").isNotBlank()) { "Escribe lo que se pronunció" }
            require(value.optString("language") in listOf("es", "en", "mixed")) { "Elige el idioma" }
        }
        if (value.optString("status") == "no_speech") require(value.optString("text").isBlank()) { "Sin voz requiere texto vacío" }
        value.put("updated_at_epoch_ms", System.currentTimeMillis())
        val atomic = file(id)
        val stream = atomic.startWrite()
        try {
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    fun markSession(id: Long, origin: String) {
        check(preferences.edit().putString("source-$id", origin).commit())
    }

    private fun installationId(): String = preferences.getString("installation", null) ?: UUID.randomUUID().toString().also {
        check(preferences.edit().putString("installation", it).commit())
    }

    /** Build a complete private ZIP first; a missing/corrupt audio fails the entire export. */
    fun export(segments: List<TranscriptSegmentEntity>): File {
        require(segments.isNotEmpty()) { "No hay registros para exportar" }
        val deviceId = installationId()
        val destination = File.createTempFile("rtts-atc-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                val lines = StringBuilder()
                for (segment in segments) {
                    val audio = segment.audioFilePath?.let(::File)
                    require(audio != null && audio.isFile) { "Falta el audio del registro ${segment.id}; no se exportó el conjunto" }
                    var sampleCount = 0L
                    audio.inputStream().use { input ->
                        val reader = PcmWavReader(input)
                        while (true) {
                            val samples = reader.readSamples(16000)
                            if (samples.isEmpty()) break
                            sampleCount += samples.size
                        }
                    }
                    require(sampleCount > 0) { "Audio vacío: ${segment.id}" }
                    val relative = "audio/$deviceId-${segment.id}.wav"
                    val hash = MessageDigest.getInstance("SHA-256")
                    zip.putNextEntry(ZipEntry(relative))
                    audio.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            hash.update(buffer, 0, count)
                            zip.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                    val review = read(segment.id)
                    val row = JSONObject()
                        .put("schema", "rtts.atc.review.v1")
                        .put("id", "$deviceId-${segment.id}")
                        .put("audio", relative)
                        .put("audio_sha256", hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                        .put("sample_rate", 16000).put("channels", 1).put("duration_s", sampleCount / 16000.0)
                        .put("source_group", "$deviceId-session-${segment.sessionId}")
                        .put("source_kind", preferences.getString("source-${segment.sessionId}", "legacy_unknown"))
                        .put("start_s", segment.startMs / 1000.0)
                        .put("created_at_epoch_ms", segment.createdAtEpochMs)
                        .put("original_text", segment.text).put("detected_language", segment.lang)
                        .put("text", review.optString("text"))
                        .put("display_text", review.optString("display_text"))
                        .put("status", review.optString("status", "pending"))
                        .put("language", review.optString("language", "en"))
                        .put("reviewer", review.optString("reviewer"))
                        .put("notes", review.optString("notes"))
                        .put("noisy", review.optBoolean("noisy"))
                        .put("clipped", review.optBoolean("clipped"))
                        .put("reviewed_at_epoch_ms", review.optLong("updated_at_epoch_ms", 0))
                    lines.append(row).append('\n')
                }
                zip.putNextEntry(ZipEntry("records.jsonl"))
                zip.write(lines.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write("RTTS ATC review v1\nAudio: PCM16 mono 16000 Hz.\nrecords.jsonl: original_text is immutable; text is the spoken reference; display_text is presentation only.\nOnly status=verified may be considered for supervised speech training after review. pending/unintelligible are not labels.\nsource_kind=spoken_example is a prompted recording, not operational radio.\nKeep source_group together when assigning dataset splits. No split is assigned by the application.\n".toByteArray())
                zip.closeEntry()
            }
            return destination
        } catch (e: Exception) { destination.delete(); throw e }
    }
}
