package com.rtts.app.asr

data class TranscriptResult(val text: String, val lang: String)

/** A VAD-segmented chunk of audio ready to be transcribed: samples plus its start offset. */
data class AudioSegment(val startSample: Int, val samples: FloatArray)

/**
 * Speech-to-text engine contract: VAD segmentation + transcription of one segment.
 * Android's implementation ([com.rtts.app.asr.SherpaOnnxSttEngine], androidMain) wraps
 * sherpa-onnx. The iOS implementation is not written yet -- see README.md "iOS" section.
 */
interface SttEngine {
    /** Feed a chunk of 16kHz mono audio; returns any transmissions VAD finished segmenting. */
    fun acceptAudioChunk(samples: FloatArray): List<AudioSegment>

    /** Call when the audio source stops, to flush any trailing in-progress segment. */
    fun flushPending(): List<AudioSegment>

    fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptResult

    fun release()
}
