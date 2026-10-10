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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val NOTIFICATION_CHANNEL_ID = "rtts_transcription"
private const val NOTIFICATION_ID = 1

class TranscriptionForegroundService : LifecycleService() {

    private var captureJob: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var audioSource: AudioSource? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_BENCHMARK -> {
                if (!_isRunning.value) runBenchmark()
                return START_NOT_STICKY
            }
            else -> {
                val fileUri = intent?.getStringExtra(EXTRA_FILE_URI)?.let { Uri.parse(it) }
                val useFileSource = intent?.getBooleanExtra(EXTRA_USE_FILE_SOURCE, true) ?: true
                if (!_isRunning.value) startCapture(useFileSource, fileUri)
            }
        }
        return START_NOT_STICKY
    }

    private fun runBenchmark() {
        startForeground(NOTIFICATION_ID, buildNotification())
        _isRunning.value = true
        _stage.value = CaptureStage.TRANSCRIBING
        captureJob = lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.Default) { BenchmarkRunner.run(this@TranscriptionForegroundService) }
                android.util.Log.i("RttsBenchmark", "Reporte: ${report.absolutePath}")
                _stage.value = CaptureStage.COMPLETED
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _stage.value = CaptureStage.ERROR
                _error.value = "No se pudo completar la prueba de rendimiento."
                android.util.Log.e("RTTS", "Benchmark failed", e)
            } finally {
                _isRunning.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startCapture(useFileSource: Boolean, pickedFileUri: Uri?) {
        startForeground(NOTIFICATION_ID, buildNotification())
        stopRequested = false
        _isRunning.value = true
        _stage.value = CaptureStage.PREPARING
        _error.value = null
        _audioSeconds.value = 0L
        _currentSessionId.value = null
        _sourceLabel.value = when {
            pickedFileUri != null -> "Archivo importado"
            useFileSource -> "Muestras de radio"
            else -> "Micrófono / entrada externa"
        }
        captureJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val container = (application as RttsApplication).container
                    var engine: SherpaOnnxSttEngine? = null
                    var sessionId: Long? = null
                    try {
                        container.modelManager.ensureModelsExtracted()
                        currentCoroutineContext().ensureActive()
                        if (stopRequested) return@withContext
                        val activeEngine = SherpaOnnxSttEngine(container.modelManager)
                        engine = activeEngine
                        currentCoroutineContext().ensureActive()
                        if (stopRequested) return@withContext
                        val userId = container.database.userDao().getFirstUser()?.id ?: 0L
                        val id = container.database.sessionDao().insert(
                            SessionEntity(userId = userId, startedAtEpochMs = System.currentTimeMillis())
                        )
                        sessionId = id
                        com.rtts.app.feature.review.ReviewStore(this@TranscriptionForegroundService).markSession(id,
                            if (pickedFileUri != null) "imported_audio" else if (useFileSource) "bundled_sample" else "live_input")
                        _currentSessionId.value = id
                        val source: AudioSource = when {
                            pickedFileUri != null -> UriAudioSource(this@TranscriptionForegroundService, pickedFileUri)
                            useFileSource -> FileAudioSource(this@TranscriptionForegroundService)
                            else -> AnalogLineInAudioSource(this@TranscriptionForegroundService)
                        }
                        audioSource = source
                        if (stopRequested) return@withContext
                        _stage.value = CaptureStage.TRANSCRIBING
                        var acceptedSamples = 0L
                        source.start().takeWhile { !stopRequested }.collect { chunk ->
                            currentCoroutineContext().ensureActive()
                            val segments = activeEngine.acceptAudioChunk(chunk.samples)
                            acceptedSamples += chunk.samples.size
                            _audioSeconds.value = acceptedSamples / 16000
                            for (seg in segments) {
                                currentCoroutineContext().ensureActive()
                                persistSegment(activeEngine, id, seg.startSample, seg.samples)
                            }
                        }
                        // Finish the speech already accepted before releasing native resources.
                        for (seg in activeEngine.flushPending()) {
                            currentCoroutineContext().ensureActive()
                            persistSegment(activeEngine, id, seg.startSample, seg.samples)
                        }
                    } finally {
                        audioSource?.stop()
                        audioSource = null
                        try { engine?.release() } finally {
                            withContext(NonCancellable + Dispatchers.IO) {
                                sessionId?.let { finishSession(it) }
                            }
                        }
                    }
                }
                _stage.value = CaptureStage.COMPLETED
            } catch (e: CancellationException) {
                _stage.value = CaptureStage.COMPLETED
                throw e
            } catch (e: Exception) {
                android.util.Log.e("RTTS", "Transcription failed", e)
                _error.value = when (e) {
                    is SecurityException -> "Permite el acceso al micrófono o vuelve a elegir el archivo."
                    else -> "No se pudo completar la transcripción. Revisa el audio y vuelve a intentarlo."
                }
                _stage.value = CaptureStage.ERROR
            } finally {
                _isRunning.value = false
                captureJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
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
        if (!_isRunning.value) { stopSelf(); return }
        stopRequested = true
        _stage.value = CaptureStage.STOPPING
        audioSource?.stop()
        // Do not destroy/release an engine while a native decode is still using it.
    }

    override fun onDestroy() {
        stopRequested = true
        audioSource?.stop()
        captureJob?.cancel()
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
        private val _stage = MutableStateFlow(CaptureStage.IDLE)
        val stage = _stage.asStateFlow()
        private val _error = MutableStateFlow<String?>(null)
        val error = _error.asStateFlow()
        private val _audioSeconds = MutableStateFlow(0L)
        val audioSeconds = _audioSeconds.asStateFlow()
        private val _sourceLabel = MutableStateFlow("Sin sesión activa")
        val sourceLabel = _sourceLabel.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _currentSessionId = MutableStateFlow<Long?>(null)
        val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()
    }
}

enum class CaptureStage { IDLE, PREPARING, TRANSCRIBING, STOPPING, COMPLETED, ERROR }
