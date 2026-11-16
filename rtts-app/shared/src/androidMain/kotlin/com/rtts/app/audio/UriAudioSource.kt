package com.rtts.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.ByteBuffer

private const val TARGET_SAMPLE_RATE = 16000
private const val TIMEOUT_US = 10_000L

/**
 * Decodes a user-picked audio/video file (any format the platform's MediaCodec supports:
 * mp3, mp4/aac, wav, etc.) to 16kHz mono PCM, so an operator can transcribe an existing
 * recording instead of only the bundled QA samples.
 */
class UriAudioSource(private val context: Context, private val uri: Uri) : AudioSource {

    @Volatile
    private var playing = false

    override fun start(): Flow<AudioChunk> = flow {
        playing = true
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (trackIndex == null) {
            extractor.release()
            throw IllegalArgumentException("No se encontró una pista de audio en el archivo seleccionado")
        }
        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        var sawInputEos = false
        var sawOutputEos = false
        var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sourceSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (playing && !sawOutputEos) {
                currentCoroutineContext().ensureActive()
                if (!sawInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer: ByteBuffer = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder.outputFormat
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sourceSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    outIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outputBuffer: ByteBuffer = decoder.getOutputBuffer(outIndex)!!
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val shorts = ShortArray(bufferInfo.size / 2)
                            outputBuffer.asShortBuffer().get(shorts)
                            val floatSamples = AudioResampler.toMono16kFloats(
                                shorts, shorts.size, channelCount.coerceAtLeast(1), sourceSampleRate
                            )
                            emit(AudioChunk(floatSamples, TARGET_SAMPLE_RATE))
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            }
        } finally {
            decoder.stop()
            decoder.release()
            extractor.release()
            playing = false
        }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        playing = false
    }
}
