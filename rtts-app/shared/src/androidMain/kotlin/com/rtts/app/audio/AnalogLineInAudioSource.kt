package com.rtts.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

private const val TARGET_SAMPLE_RATE = 16000
private const val FALLBACK_SAMPLE_RATE = 44100
private const val CHUNK_SAMPLES = 3200 // ~200ms at 16kHz

/**
 * Captures audio from an external line-in (jack / USB-audio adapter) connected to the
 * "data radio". Falls back to the device's default input if no external device is found,
 * which is fine for development on a tablet/emulator without hardware attached.
 */
class AnalogLineInAudioSource(private val context: Context) : AudioSource {

    @Volatile
    private var recording = false
    private var audioRecord: AudioRecord? = null

    override fun start(): Flow<AudioChunk> = callbackFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            close(SecurityException("RECORD_AUDIO permission not granted"))
            return@callbackFlow
        }

        val (record, sampleRate) = createAudioRecord()
        audioRecord = record
        preferExternalInputDevice(record)

        record.startRecording()
        recording = true

        val shortBuffer = ShortArray(CHUNK_SAMPLES * sampleRate / TARGET_SAMPLE_RATE)
        while (recording) {
            val read = record.read(shortBuffer, 0, shortBuffer.size)
            if (read > 0) {
                val floatSamples = AudioResampler.toMono16kFloats(shortBuffer, read, 1, sampleRate)
                trySend(AudioChunk(floatSamples, TARGET_SAMPLE_RATE))
            }
        }

        awaitClose { releaseRecord() }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        recording = false
    }

    private fun releaseRecord() {
        recording = false
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // already stopped
            }
            it.release()
        }
        audioRecord = null
    }

    private fun createAudioRecord(): Pair<AudioRecord, Int> {
        for (rate in intArrayOf(TARGET_SAMPLE_RATE, FALLBACK_SAMPLE_RATE)) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) continue
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4,
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                return record to rate
            }
            record.release()
        }
        throw IllegalStateException("Could not initialize AudioRecord at any supported sample rate")
    }

    /** Prefer a USB-audio or wired-headset input device (the data-radio adapter) over the built-in mic. */
    private fun preferExternalInputDevice(record: AudioRecord) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val external = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
        if (external != null) {
            record.preferredDevice = external
        }
    }
}
