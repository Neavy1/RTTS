package com.rtts.app

import android.app.Instrumentation
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import com.rtts.app.audio.WavFileWriter
import com.rtts.app.data.TranscriptSegmentEntity
import com.rtts.app.feature.review.ReviewStore
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Device smoke test of real JSON, AtomicFile and ZIP APIs; isolated from user reviews. */
class ReviewSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val key = "qa-${UUID.randomUUID()}"
            val root = File(targetContext.cacheDir, key).apply { mkdirs() }
            val isolated = object : ContextWrapper(targetContext) {
                override fun getFilesDir() = root
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$key-$name", mode)
            }
            val store = ReviewStore(isolated)
            val audio = File(root, "sample.wav")
            WavFileWriter.write(audio, FloatArray(16000))
            val segment = TranscriptSegmentEntity(id = 1, sessionId = 1, startMs = 0, endMs = 1000,
                speakerLabel = "test", text = "original immutable", lang = "en", createdAtEpochMs = 1,
                audioFilePath = audio.absolutePath)
            check(runCatching { store.save(1, JSONObject().put("status", "verified").put("text", "")) }.isFailure)
            store.save(1, JSONObject().put("status", "no_speech").put("text", "").put("language", "unknown")
                .put("reviewer", "AUTOMATED TEST ONLY").put("notes", "Synthetic zero samples; do not use for speech training"))
            check(ReviewStore(isolated).read(1).optString("status") == "no_speech")
            store.markSession(1, "qa_synthetic")
            val zip = store.export(listOf(segment))
            ZipFile(zip).use { archive ->
                val json = archive.getInputStream(archive.getEntry("records.jsonl")).bufferedReader().readText().trim()
                val row = JSONObject(json)
                check(row.getString("original_text") == "original immutable")
                check(row.getString("text") == "")
                check(row.getString("source_kind") == "qa_synthetic")
                check(row.getDouble("duration_s") == 1.0)
                check(archive.getEntry(row.getString("audio")) != null)
            }
            val artifact = File(targetContext.getExternalFilesDir(null), "qa/review-smoke.zip")
            artifact.parentFile?.mkdirs(); zip.copyTo(artifact, overwrite = true); zip.delete()
            check(runCatching { store.export(listOf(segment.copy(audioFilePath = "/missing.wav"))) }.isFailure)
            result.putString("stream", "PASS: persistence, validation, original text, WAV, ZIP and missing-audio failure\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
