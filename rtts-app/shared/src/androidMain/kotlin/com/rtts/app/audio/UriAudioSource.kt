package com.rtts.app.audio

import android.content.Context
import android.media.AudioFormat
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
import java.nio.ByteOrder

/** Decodes at the consumer's pace: imported audio must never be silently discarded. */
class UriAudioSource(private val context: Context, private val uri: Uri) : AudioSource {
    @Volatile private var playing = false

    override fun start(): Flow<AudioChunk> = flow {
        playing = true
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No se encontró una pista de audio")
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            while (playing && !outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = codec.outputFormat
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                } else if (outputIndex >= 0) {
                    var samples: FloatArray? = null
                    try {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outputIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val pcm = when (encoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> ShortArray(info.size / 2).also {
                                    buffer.asShortBuffer().get(it)
                                }
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val floats = FloatArray(info.size / 4)
                                    buffer.asFloatBuffer().get(floats)
                                    ShortArray(floats.size) { (floats[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
                                }
                                else -> error("Formato PCM no compatible: $encoding")
                            }
                            require(channels > 0 && rate > 0)
                            samples = AudioResampler.toMono16kFloats(pcm, pcm.size, channels, rate)
                        }
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                    // emit suspends instead of dropping data when Whisper is busy.
                    samples?.let { emit(AudioChunk(it, 16000)) }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
            }
        } finally {
            playing = false
            try { if (started) decoder?.stop() } finally {
                try { decoder?.release() } finally { extractor.release() }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun stop() { playing = false }
}
