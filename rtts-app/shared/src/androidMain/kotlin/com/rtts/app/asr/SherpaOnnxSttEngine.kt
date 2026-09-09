package com.rtts.app.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Languages the ATC comms are expected to be in. Anything else is treated as a mis-detection. */
private val EXPECTED_LANGUAGES = setOf("es", "en")

/**
 * Wraps sherpa-onnx's VAD (transmission segmentation) and Whisper (speech-to-text).
 *
 * Whisper's automatic language detection is unreliable on short (<5s) push-to-talk
 * transmissions -- found during the Fase 0 spike, where clips were mis-detected as
 * Japanese/Arabic/Portuguese instead of es/en. As a cheap mitigation, when the
 * auto-detected language falls outside the expected es/en set, we redecode once
 * forcing English (statistically the more common mis-detection case for ATC audio).
 */
class SherpaOnnxSttEngine(modelManager: ModelManager, numThreads: Int = 4) : SttEngine {

    private val recognizer: OfflineRecognizer
    private val vad: Vad
    private val baseRecognizerConfig: OfflineRecognizerConfig
    private val baseWhisperConfig: OfflineWhisperModelConfig

    init {
        baseWhisperConfig = OfflineWhisperModelConfig(
            encoder = modelManager.whisperEncoderPath,
            decoder = modelManager.whisperDecoderPath,
            language = "",
            task = "transcribe",
        )
        val modelConfig = OfflineModelConfig(
            whisper = baseWhisperConfig,
            tokens = modelManager.whisperTokensPath,
            modelType = "whisper",
            numThreads = numThreads,
        )
        val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
        baseRecognizerConfig = OfflineRecognizerConfig(featConfig = featConfig, modelConfig = modelConfig)
        recognizer = OfflineRecognizer(config = baseRecognizerConfig)

        val vadModelConfig = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelManager.vadModelPath,
                // Raised from 0.5 / 0.25s: at the lower thresholds, ambiguous noise/background
                // segments were reaching Whisper and coming back as hallucinated nonsense text.
                threshold = 0.6f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.3f,
                windowSize = 512,
                maxSpeechDuration = 20.0f,
            ),
            sampleRate = 16000,
            numThreads = 1,
        )
        vad = Vad(config = vadModelConfig)
    }

    override fun acceptAudioChunk(samples: FloatArray): List<AudioSegment> {
        vad.acceptWaveform(samples)
        val finished = mutableListOf<AudioSegment>()
        while (!vad.empty()) {
            val seg = vad.front()
            finished.add(AudioSegment(startSample = seg.start, samples = seg.samples))
            vad.pop()
        }
        return finished
    }

    override fun flushPending(): List<AudioSegment> {
        vad.flush()
        val finished = mutableListOf<AudioSegment>()
        while (!vad.empty()) {
            val seg = vad.front()
            finished.add(AudioSegment(startSample = seg.start, samples = seg.samples))
            vad.pop()
        }
        return finished
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptResult {
        val first = decodeOnce(samples, sampleRate, language = "")
        if (first.text.isBlank()) return first
        if (first.lang in EXPECTED_LANGUAGES && !looksLikeHallucination(first.text)) return first

        // Either the auto-detected language was outside es/en, or the text itself looks like
        // a hallucination loop (e.g. "shoot or shoot or shoot..."). Retry forcing the other
        // expected language -- and if that still looks bad, try the remaining one as a
        // last resort. At most 3 decodes total, and only for audio that already failed once.
        val retryLang = if (first.lang == "es") "en" else "es"
        val second = decodeOnce(samples, sampleRate, language = retryLang)
        if (second.text.isBlank() || !looksLikeHallucination(second.text)) return second

        val lastLang = if (retryLang == "es") "en" else "es"
        return decodeOnce(samples, sampleRate, language = lastLang)
    }

    /**
     * Cheap detector for Whisper's repetition-loop failure mode ("shoot or shoot or shoot..."),
     * which tends to show up on ambiguous/noisy segments. Flags text with very few distinct
     * words relative to its length.
     */
    private fun looksLikeHallucination(text: String): Boolean {
        val words = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 6) return false
        val uniqueRatio = words.toSet().size.toDouble() / words.size
        return uniqueRatio < 0.45
    }

    private fun decodeOnce(samples: FloatArray, sampleRate: Int, language: String): TranscriptResult {
        if (language != baseWhisperConfig.language) {
            val forcedConfig = baseRecognizerConfig.copy(
                modelConfig = baseRecognizerConfig.modelConfig.copy(
                    whisper = baseWhisperConfig.copy(language = language)
                )
            )
            recognizer.setConfig(forcedConfig)
        }
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, sampleRate = sampleRate)
        recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        stream.release()
        if (language != baseWhisperConfig.language) {
            recognizer.setConfig(baseRecognizerConfig)
        }
        return TranscriptResult(text = result.text, lang = result.lang)
    }

    override fun release() {
        vad.release()
        recognizer.release()
    }
}
