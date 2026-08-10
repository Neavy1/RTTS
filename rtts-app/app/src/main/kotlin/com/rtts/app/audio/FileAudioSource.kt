package com.rtts.app.audio

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.DataInputStream

private const val SAMPLE_RATE = 16000
private const val WAV_HEADER_BYTES = 44
private const val CHUNK_SAMPLES = 3200 // ~200ms at 16kHz

/**
 * Development/QA fixture: plays back the bundled sample recordings as if they were arriving
 * live from the radio. Lets the rest of the pipeline (VAD -> STT -> UI) be exercised and
 * demoed on a tablet/emulator before the physical data-radio hardware is available.
 *
 * The bundled assets are pre-converted to 16kHz mono PCM16 WAV, so this reads the raw PCM
 * frames directly rather than pulling in a general-purpose media decoder.
 */
class FileAudioSource(
    private val context: Context,
    private val assetNames: List<String> = listOf(
        "samples/videoejemplo.wav",
        "samples/ContactoAviones.wav",
        "samples/WhatsAppAudio.wav",
    ),
) : AudioSource {

    @Volatile
    private var playing = false

    override fun start(): Flow<AudioChunk> = flow {
        playing = true
        for (assetName in assetNames) {
            if (!playing) break
            context.assets.open(assetName).use { raw ->
                val input = DataInputStream(raw)
                input.skipBytes(WAV_HEADER_BYTES)
                val byteBuffer = ByteArray(CHUNK_SAMPLES * 2)
                while (playing) {
                    val read = input.read(byteBuffer)
                    if (read <= 0) break
                    val sampleCount = read / 2
                    val samples = FloatArray(sampleCount) { i ->
                        val lo = byteBuffer[i * 2].toInt() and 0xFF
                        val hi = byteBuffer[i * 2 + 1].toInt()
                        val sample = (hi shl 8) or lo
                        sample.toShort() / 32768.0f
                    }
                    emit(AudioChunk(samples, SAMPLE_RATE))
                    delay(sampleCount * 1000L / SAMPLE_RATE)
                }
            }
        }
        playing = false
    }

    override fun stop() {
        playing = false
    }
}
