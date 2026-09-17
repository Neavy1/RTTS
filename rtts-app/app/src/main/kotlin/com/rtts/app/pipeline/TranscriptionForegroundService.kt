package com.rtts.app.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rtts.app.RttsApplication
import com.rtts.app.asr.SherpaOnnxSttEngine
import com.rtts.app.benchmark.BenchmarkRunner
import com.rtts.app.audio.AnalogLineInAudioSource
import com.rtts.app.audio.AudioSource
import com.rtts.app.audio.FileAudioSource
import com.rtts.app.audio.UriAudioSource
import com.rtts.app.audio.WavFileWriter
import com.rtts.app.data.SessionEntity
import com.rtts.app.data.TranscriptSegmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val NOTIFICATION_CHANNEL_ID = "rtts_transcription"
private const val NOTIFICATION_ID = 1

class TranscriptionForegroundService : LifecycleService() {

    private var sttEngine: SherpaOnnxSttEngine? = null
    private var audioSource: AudioSource? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_BENCHMARK -> {
                runBenchmark()
                return START_NOT_STICKY
            }
            else -> {
                val fileUri = intent?.getStringExtra(EXTRA_FILE_URI)?.let { Uri.parse(it) }
                val useFileSource = intent?.getBooleanExtra(EXTRA_USE_FILE_SOURCE, true) ?: true
                startCapture(useFileSource = useFileSource, pickedFileUri = fileUri)
            }
        }
        return START_NOT_STICKY
    }

    private fun runBenchmark() {
        startForeground(NOTIFICATION_ID, buildNotification())
        lifecycleScope.launch {
            // Model load + decoding 4 full files back-to-back is minutes of uninterrupted CPU
            // work with no natural suspension point -- run it off the main thread, or the
            // service (and the whole app) ANRs, since nothing can service startForeground/
            // the notification/the binder while Main is pegged like that.
            val reportFile = withContext(Dispatchers.Default) {
                BenchmarkRunner.run(this@TranscriptionForegroundService)
            }
            android.util.Log.i("RttsBenchmark", "Reporte escrito en: ${reportFile.absolutePath}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startCapture(useFileSource: Boolean, pickedFileUri: Uri?) {
        startForeground(NOTIFICATION_ID, buildNotification())
        _isRunning.value = true

        // Loading the model (up to ~18s for `small`) and running VAD/ASR on every chunk is
        // real CPU work with no natural suspension point. Left on the default Main dispatcher,
        // this blocks the UI thread long enough to trip Android's ANR watchdog ("la app se ha
        // detenido") -- same root cause as the benchmark ANR fixed earlier, just never applied
        // to the production capture path until now.
        lifecycleScope.launch(Dispatchers.Default) {
            val container = (application as RttsApplication).container
            container.modelManager.ensureModelsExtracted()

            val engine = SherpaOnnxSttEngine(container.modelManager)
            sttEngine = engine

            val userId = container.database.userDao().getFirstUser()?.id ?: 0L
            val session = SessionEntity(userId = userId, startedAtEpochMs = System.currentTimeMillis())
            val sessionId = container.database.sessionDao().insert(session)
            _currentSessionId.value = sessionId

            val source: AudioSource = when {
                pickedFileUri != null -> UriAudioSource(this@TranscriptionForegroundService, pickedFileUri)
                useFileSource -> FileAudioSource(this@TranscriptionForegroundService)
                else -> AnalogLineInAudioSource(this@TranscriptionForegroundService)
            }
            audioSource = source

            source.start().collect { chunk ->
                val finishedSegments = engine.acceptAudioChunk(chunk.samples)
                for (seg in finishedSegments) {
                    persistSegment(engine, sessionId, seg.startSample, seg.samples)
                }
            }

            // Flow completed (file source reached the end, or stop() was called).
            for (seg in engine.flushPending()) {
                persistSegment(engine, sessionId, seg.startSample, seg.samples)
            }
            finishSession(sessionId)
        }
    }

    private suspend fun persistSegment(
        engine: SherpaOnnxSttEngine,
        sessionId: Long,
        startSample: Int,
        samples: FloatArray,
    ) {
        val result = engine.transcribe(samples, sampleRate = 16000)
        if (result.text.isBlank()) return
        val startMs = startSample * 1000L / 16000
        val durationMs = samples.size * 1000L / 16000
        val createdAt = System.currentTimeMillis()

        val audioFile = File(getExternalFilesDir(null) ?: filesDir, "segments/$sessionId/$createdAt.wav")
        val audioPath = try {
            WavFileWriter.write(audioFile, samples)
            audioFile.absolutePath
        } catch (e: Exception) {
            null // playback is a bonus; a failed write shouldn't drop the transcript itself
        }

        val container = (application as RttsApplication).container
        container.database.transcriptSegmentDao().insert(
            TranscriptSegmentEntity(
                sessionId = sessionId,
                startMs = startMs,
                endMs = startMs + durationMs,
                speakerLabel = "Desconocido",
                text = result.text,
                lang = result.lang,
                createdAtEpochMs = createdAt,
                audioFilePath = audioPath,
            )
        )
    }

    private suspend fun finishSession(sessionId: Long) {
        val container = (application as RttsApplication).container
        val session = container.database.sessionDao().getById(sessionId) ?: return
        container.database.sessionDao().update(session.copy(endedAtEpochMs = System.currentTimeMillis()))
    }

    private fun stopCapture() {
        audioSource?.stop()
        _isRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        sttEngine?.release()
        sttEngine = null
        _isRunning.value = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Transcripción RTTS",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RTTS grabando")
            .setContentText("Transcribiendo comunicación en vivo")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.rtts.app.action.STOP"
        const val ACTION_BENCHMARK = "com.rtts.app.action.BENCHMARK"
        const val EXTRA_USE_FILE_SOURCE = "use_file_source"
        const val EXTRA_FILE_URI = "file_uri"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _currentSessionId = MutableStateFlow<Long?>(null)
        val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()
    }
}
