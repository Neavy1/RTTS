package com.rtts.app.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Wraps sherpa-onnx's VAD (transmission segmentation) and Whisper (speech-to-text).
 *
 * RTTS currently targets English ATC. The language is deliberately forced because the
 * video benchmark showed that automatic detection changed short transmissions to Arabic,
 * Japanese and Spanish. Callers cannot silently re-enable automatic language detection.
 */
class SherpaOnnxSttEngine(modelManager: ModelManager, val numThreads: Int = 4) : SttEngine {

    private val recognizer: OfflineRecognizer
    private val vad: Vad
    private val baseRecognizerConfig: OfflineRecognizerConfig
    private val baseWhisperConfig: OfflineWhisperModelConfig

    /** Cumulative time spent inside the VAD across all [acceptAudioChunk]/[flushPending] calls. */
    var totalVadNanos: Long = 0
        private set

    /** Cumulative time spent inside Whisper decode across all [transcribe] calls (all retries included). */
    var totalDecodeNanos: Long = 0
        private set

    /** Kept for benchmark compatibility. English-only decoding never retries another language. */
    var retriedSegmentCount: Int = 0
        private set

    init {
        baseWhisperConfig = OfflineWhisperModelConfig(
            encoder = modelManager.whisperEncoderPath,
            decoder = modelManager.whisperDecoderPath,
            language = ATC_LANGUAGE,
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
        vad = try { Vad(config = vadModelConfig) } catch (e: Exception) {
            recognizer.release()
            throw e
        }
    }

    override fun acceptAudioChunk(samples: FloatArray): List<AudioSegment> {
        val t0 = System.nanoTime()
        vad.acceptWaveform(samples)
        val finished = mutableListOf<AudioSegment>()
        while (!vad.empty()) {
            val seg = vad.front()
            finished.add(AudioSegment(startSample = seg.start, samples = seg.samples))
            vad.pop()
        }
        totalVadNanos += System.nanoTime() - t0
        return finished
    }

    override fun flushPending(): List<AudioSegment> {
        val t0 = System.nanoTime()
        vad.flush()
        val finished = mutableListOf<AudioSegment>()
        while (!vad.empty()) {
            val seg = vad.front()
            finished.add(AudioSegment(startSample = seg.start, samples = seg.samples))
            vad.pop()
        }
        totalVadNanos += System.nanoTime() - t0
        return finished
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptResult {
        val t0 = System.nanoTime()
        val result = transcribeInternal(samples, sampleRate)
        totalDecodeNanos += System.nanoTime() - t0
        return result
    }

    private fun transcribeInternal(samples: FloatArray, sampleRate: Int): TranscriptResult {
        return validateEnglishAtcResult(decodeOnce(samples, sampleRate, language = ATC_LANGUAGE))
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
        try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate = sampleRate)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                return TranscriptResult(text = result.text, lang = result.lang)
            } finally {
                stream.release()
            }
        } finally {
            if (language != baseWhisperConfig.language) recognizer.setConfig(baseRecognizerConfig)
        }
    }

    override fun release() {
        vad.release()
        recognizer.release()
    }

    private companion object {
        const val ATC_LANGUAGE = "en"
    }
}
