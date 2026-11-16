package com.rtts.app.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.rtts.app.audio.AudioChunk
import com.rtts.app.audio.FileAudioSource
import com.rtts.app.audio.UriAudioSource
import com.rtts.app.audio.WavFileWriter
import com.rtts.app.data.SessionEntity
import com.rtts.app.data.TranscriptSegmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val NOTIFICATION_CHANNEL_ID = "rtts_transcription"
private const val NOTIFICATION_ID = 1
private const val AUDIO_QUEUE_CAPACITY = 1500 // About five minutes of 200 ms live chunks (~19 MB).

data class CaptureStats(val inputLevel: Float = 0f, val droppedChunks: Long = 0, val queuedSeconds: Double = 0.0)

class TranscriptionForegroundService : LifecycleService() {

    @Volatile
    private var audioSource: AudioSource? = null
    @Volatile
    private var stopRequested = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_BENCHMARK -> {
                if (_isRunning.value) return START_NOT_STICKY
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
        startTranscriptionForeground(useMicrophone = false)
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
        if (_isRunning.value) return
        startTranscriptionForeground(useMicrophone = !useFileSource && pickedFileUri == null)
        stopRequested = false
        _isStopping.value = false
        _isLive.value = !useFileSource && pickedFileUri == null
        _captureStats.value = CaptureStats()
        _captureError.value = null
        _isRunning.value = true

        // Loading the model (up to ~18s for `small`) and running VAD/ASR on every chunk is
        // real CPU work with no natural suspension point. Left on the default Main dispatcher,
        // this blocks the UI thread long enough to trip Android's ANR watchdog ("la app se ha
        // detenido") -- same root cause as the benchmark ANR fixed earlier, just never applied
        // to the production capture path until now.
        lifecycleScope.launch(Dispatchers.Default) {
            var engine: SherpaOnnxSttEngine? = null
            var sessionId: Long? = null
            try {
                val container = (application as RttsApplication).container
                container.modelManager.ensureModelsExtracted()

                val activeEngine = SherpaOnnxSttEngine(container.modelManager)
                engine = activeEngine

                val userId = container.database.userDao().getFirstUser()?.id ?: 0L
                val session = SessionEntity(userId = userId, startedAtEpochMs = System.currentTimeMillis())
                val activeSessionId = container.database.sessionDao().insert(session)
                sessionId = activeSessionId
                _currentSessionId.value = activeSessionId

                val source: AudioSource = when {
                    pickedFileUri != null -> UriAudioSource(this@TranscriptionForegroundService, pickedFileUri)
                    useFileSource -> FileAudioSource(this@TranscriptionForegroundService)
                    else -> AnalogLineInAudioSource(this@TranscriptionForegroundService)
                }
                audioSource = source

                // Live capture never waits for Whisper. This queue is the only dropping point;
                // source flows use suspending emit, so no upstream trySend losses go unreported.
                coroutineScope {
                    val queue = Channel<AudioChunk>(AUDIO_QUEUE_CAPACITY)
                    val statsLock = Any()
                    var queuedSeconds = 0.0
                    val worker = launch {
                        for (chunk in queue) {
                            synchronized(statsLock) {
                                queuedSeconds = (queuedSeconds - chunk.samples.size.toDouble() / chunk.sampleRate).coerceAtLeast(0.0)
                                _captureStats.value = _captureStats.value.copy(queuedSeconds = queuedSeconds)
                            }
                            for (seg in activeEngine.acceptAudioChunk(chunk.samples)) {
                                persistSegment(activeEngine, activeSessionId, seg.startSample, seg.samples)
                            }
                        }
                    }
                    try {
                        if (!stopRequested) source.start().buffer(0).collect { chunk ->
                            if (stopRequested) source.stop()
                            var sumSquares = 0.0
                            for (sample in chunk.samples) sumSquares += sample * sample
                            val level = if (chunk.samples.isEmpty()) 0f else
                                kotlin.math.sqrt(sumSquares / chunk.samples.size).toFloat().coerceIn(0f, 1f)
                            if (useFileSource || pickedFileUri != null) {
                                // Files can wait instead of dropping audio when decoded faster than ASR.
                                synchronized(statsLock) {
                                    queuedSeconds += chunk.samples.size.toDouble() / chunk.sampleRate
                                    _captureStats.value = _captureStats.value.copy(inputLevel = level, queuedSeconds = queuedSeconds)
                                }
                                queue.send(chunk)
                            } else {
                                synchronized(statsLock) {
                                    val accepted = queue.trySend(chunk).isSuccess
                                    if (accepted) queuedSeconds += chunk.samples.size.toDouble() / chunk.sampleRate
                                    _captureStats.value = CaptureStats(
                                        inputLevel = level,
                                        droppedChunks = _captureStats.value.droppedChunks + if (accepted) 0L else 1L,
                                        queuedSeconds = queuedSeconds,
                                    )
                                }
                            }
                        }
                    } finally {
                        queue.close()
                        _isStopping.value = true
                        synchronized(statsLock) {
                            _captureStats.value = _captureStats.value.copy(inputLevel = 0f)
                        }
                    }
                    worker.join()
                }

                // Drain the queue and the final VAD segment before ending the session/service.
                for (seg in activeEngine.flushPending()) {
                    persistSegment(activeEngine, activeSessionId, seg.startSample, seg.samples)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("RttsCapture", "Error de transcripción", e)
                _captureError.value = "No se pudo completar la captura. Revisa el permiso de micrófono y la fuente de audio."
            } finally {
                audioSource?.stop()
                audioSource = null
                try {
                    withContext(NonCancellable) { sessionId?.let { finishSession(it) } }
                } finally {
                    engine?.release()
                    _captureStats.value = _captureStats.value.copy(inputLevel = 0f, queuedSeconds = 0.0)
                    withContext(NonCancellable + Dispatchers.Main) {
                        _isRunning.value = false
                        _isStopping.value = false
                        _isLive.value = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
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
        stopRequested = true
        if (_isRunning.value) _isStopping.value = true
        audioSource?.stop()
        // Keep lifecycleScope and foreground alive while the worker drains and flushes.
        if (!_isRunning.value) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopRequested = true
        audioSource?.stop()
        super.onDestroy()
    }

    private fun startTranscriptionForeground(useMicrophone: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (useMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
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

        private val _isStopping = MutableStateFlow(false)
        val isStopping: StateFlow<Boolean> = _isStopping.asStateFlow()
        private val _isLive = MutableStateFlow(false)
        val isLive: StateFlow<Boolean> = _isLive.asStateFlow()
        private val _captureStats = MutableStateFlow(CaptureStats())
        val captureStats: StateFlow<CaptureStats> = _captureStats.asStateFlow()
        private val _captureError = MutableStateFlow<String?>(null)
        val captureError: StateFlow<String?> = _captureError.asStateFlow()

        private val _currentSessionId = MutableStateFlow<Long?>(null)
        val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()
    }
}
