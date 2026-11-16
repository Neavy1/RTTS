package com.rtts.app.feature.transcript

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.rtts.app.RttsApplication
import com.rtts.app.audio.FileAudioSource
import com.rtts.app.data.TranscriptSegmentEntity
import com.rtts.app.pipeline.TranscriptionForegroundService
import kotlinx.coroutines.launch

@Composable
fun LiveTranscriptScreen() {
    val context = LocalContext.current
    val isRunning by TranscriptionForegroundService.isRunning.collectAsState()
    val isStopping by TranscriptionForegroundService.isStopping.collectAsState()
    val isLive by TranscriptionForegroundService.isLive.collectAsState()
    val captureStats by TranscriptionForegroundService.captureStats.collectAsState()
    val captureError by TranscriptionForegroundService.captureError.collectAsState()
    val sessionId by TranscriptionForegroundService.currentSessionId.collectAsState()
    val container = (context.applicationContext as RttsApplication).container

    val segments by produceState(initialValue = emptyList<TranscriptSegmentEntity>(), sessionId) {
        value = emptyList()
        val id = sessionId
        if (id == null) {
            value = emptyList()
        } else {
            container.database.transcriptSegmentDao().observeForSession(id).collect { value = it }
        }
    }

    val backgroundPlayer = remember { BackgroundAudioPlayer(context) }
    DisposableEffect(Unit) { onDispose { backgroundPlayer.release() } }
    var pendingPlayback by remember { mutableStateOf<PlaybackSource?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val startLive = {
        pendingPlayback = null
        backgroundPlayer.stop()
        permissionDenied = false
        startTranscriptionOfRadio(context)
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startLive() else permissionDenied = true
    }
    LaunchedEffect(isRunning) {
        if (isRunning) pendingPlayback?.let { backgroundPlayer.play(it) } else backgroundPlayer.stop()
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !TranscriptionForegroundService.isRunning.value) {
            pendingPlayback = PlaybackSource.PickedFile(uri)
            startTranscriptionOfFile(context, uri)
        }
    }

    val player = remember { SegmentAudioPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val playingSegmentId by player.playingSegmentId
    val scope = rememberCoroutineScope()
    val sessionStart by produceState<Pair<Long, Long>?>(null, sessionId) {
        value = null
        value = sessionId?.let { id ->
            container.database.sessionDao().getById(id)?.let { id to it.startedAtEpochMs }
        }
    }
    CabinScreen(
        segments = segments.filter { it.sessionId == sessionId },
        sessionStartEpochMs = sessionStart?.takeIf { it.first == sessionId }?.second,
        isRunning = isRunning, isStopping = isStopping, isLive = isLive,
        captureStats = captureStats, captureError = captureError,
        permissionDenied = permissionDenied, playingSegmentId = playingSegmentId,
        onListen = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) startLive() else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
        onStop = { backgroundPlayer.stop(); stopTranscription(context) },
        onSamples = {
            pendingPlayback = PlaybackSource.Samples
            startTranscriptionOfSamples(context)
        },
        onPickFile = { filePicker.launch(arrayOf("audio/*", "video/mp4", "video/*")) },
        onPlay = { backgroundPlayer.stop(); player.toggle(it) },
        onBookmark = { segment, marked ->
            scope.launch { container.database.transcriptSegmentDao().setBookmarked(segment.id, marked) }
        },
    )
}

/** What to play in the background for the capture that was just started. */
private sealed interface PlaybackSource {
    data object Samples : PlaybackSource
    data class PickedFile(val uri: Uri) : PlaybackSource
}

/**
 * Plays the original source audio in the background while the pipeline transcribes it, so an
 * operator hears the transmission as captions settle in. Independent from [SegmentAudioPlayer],
 * which replays one already-finished segment on demand -- starting one stops the other so they
 * never talk over each other.
 */
private class BackgroundAudioPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

    fun play(source: PlaybackSource) {
        stop()
        when (source) {
            is PlaybackSource.Samples -> {
                val player = MediaPlayer().apply { setOnErrorListener { _, _, _ -> true } }
                mediaPlayer = player
                playSampleSequence(player, FileAudioSource.DEFAULT_SAMPLES, 0)
            }
            is PlaybackSource.PickedFile -> {
                mediaPlayer = MediaPlayer().apply {
                    setOnErrorListener { _, _, _ -> true }
                    setOnCompletionListener { it.release() }
                    setDataSource(context, source.uri)
                    prepare()
                    start()
                }
            }
        }
    }

    private fun playSampleSequence(player: MediaPlayer, assets: List<String>, index: Int) {
        if (index >= assets.size) return
        context.assets.openFd(assets[index]).use { afd ->
            player.reset()
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
        player.setOnCompletionListener { playSampleSequence(player, assets, index + 1) }
        player.prepare()
        player.start()
    }

    fun stop() {
        mediaPlayer?.let {
            it.setOnCompletionListener(null)
            it.release()
        }
        mediaPlayer = null
    }

    fun release() = stop()
}

/**
 * Plays back one transcript segment's saved audio clip at a time, so an operator can listen
 * and verify a transmission against its transcription. Segment-level only (no word-level
 * highlighting) -- our STT engine doesn't return word timestamps.
 */
private class SegmentAudioPlayer {
    val playingSegmentId = mutableStateOf<Long?>(null)
    private var mediaPlayer: MediaPlayer? = null

    fun toggle(segment: TranscriptSegmentEntity) {
        val path = segment.audioFilePath ?: return
        if (playingSegmentId.value == segment.id) {
            stop()
            return
        }
        stop()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(path)
            setOnCompletionListener { stop() }
            setOnErrorListener { _, _, _ -> stop(); true }
            prepare()
            start()
        }
        playingSegmentId.value = segment.id
    }

    private fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
        playingSegmentId.value = null
    }

    fun release() = stop()
}

private fun startTranscriptionOfSamples(context: Context) {
    val intent = Intent(context, TranscriptionForegroundService::class.java).apply {
        putExtra(TranscriptionForegroundService.EXTRA_USE_FILE_SOURCE, true)
    }
    context.startForegroundService(intent)
}

private fun startTranscriptionOfRadio(context: Context) {
    val intent = Intent(context, TranscriptionForegroundService::class.java).apply {
        putExtra(TranscriptionForegroundService.EXTRA_USE_FILE_SOURCE, false)
    }
    context.startForegroundService(intent)
}

private fun startTranscriptionOfFile(context: Context, uri: Uri) {
    context.contentResolver.takePersistableUriPermission(
        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
    )
    val intent = Intent(context, TranscriptionForegroundService::class.java).apply {
        putExtra(TranscriptionForegroundService.EXTRA_FILE_URI, uri.toString())
    }
    context.startForegroundService(intent)
}

private fun stopTranscription(context: Context) {
    val intent = Intent(context, TranscriptionForegroundService::class.java).apply {
        action = TranscriptionForegroundService.ACTION_STOP
    }
    context.startService(intent)
}
