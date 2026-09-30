package com.rtts.app.feature.review

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Foreground-screen-only recording, bounded to 30 s. Does not run Whisper or label itself. */
class ExampleRecorder {
    @Volatile private var stopped = false
    fun stop() { stopped = true }

    suspend fun record(onSeconds: (Int) -> Unit): FloatArray {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "El dispositivo no admite grabación a 16 kHz" }
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "No se pudo abrir el micrófono" }
            record.startRecording()
            val buffer = ShortArray(3200)
            val samples = FloatArray(16000 * 30)
            var offset = 0
            while (!stopped && offset < samples.size) {
                currentCoroutineContext().ensureActive()
                val count = record.read(buffer, 0, minOf(buffer.size, samples.size - offset))
                check(count > 0) { "No se pudo leer el micrófono" }
                for (i in 0 until count) samples[offset++] = buffer[i] / 32768f
                onSeconds(offset / 16000)
            }
            return samples.copyOf(offset)
        } finally {
            try { record.stop() } catch (_: IllegalStateException) { }
            record.release()
        }
    }
}
