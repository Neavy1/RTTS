package com.rtts.app.audio

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val SAMPLE_RATE = 16000
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
    private val assetNames: List<String> = DEFAULT_SAMPLES,
) : AudioSource {

    companion object {
        // Shared with the UI layer so background playback (MediaPlayer) plays the exact same
        // clips, in the exact same order, as what this source feeds into the STT pipeline.
        val DEFAULT_SAMPLES = listOf(
            "samples/videoejemplo.wav",
            "samples/ContactoAviones.wav",
            "samples/WhatsAppAudio.wav",
            "samples/WhatsAppAudio2.wav",
        )
    }

    @Volatile
    private var playing = false

    override fun start(): Flow<AudioChunk> = flow {
        playing = true
        for (assetName in assetNames) {
            if (!playing) break
            context.assets.open(assetName).use { raw ->
                val input = PcmWavReader(raw)
                while (playing) {
                    currentCoroutineContext().ensureActive()
                    val samples = input.readSamples(CHUNK_SAMPLES)
                    if (samples.isEmpty()) break
                    val sampleCount = samples.size
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
