package com.rtts.app.audio

private const val TARGET_SAMPLE_RATE = 16000

/**
 * Simple nearest-neighbour decimation resampler shared by the audio sources. It is not a
 * high-fidelity resampler, but is adequate for speech recognition and avoids pulling in a
 * DSP dependency for an MVP.
 */
object AudioResampler {

    /** Downmixes to mono (if needed) and resamples to 16kHz, returning normalized [-1, 1] floats. */
    fun toMono16kFloats(shorts: ShortArray, count: Int, channelCount: Int, sourceSampleRate: Int): FloatArray {
        val monoCount = count / channelCount
        val mono = ShortArray(monoCount)
        if (channelCount == 1) {
            System.arraycopy(shorts, 0, mono, 0, monoCount)
        } else {
            for (i in 0 until monoCount) {
                var sum = 0
                for (c in 0 until channelCount) sum += shorts[i * channelCount + c]
                mono[i] = (sum / channelCount).toShort()
            }
        }

        if (sourceSampleRate == TARGET_SAMPLE_RATE) {
            return FloatArray(monoCount) { mono[it] / 32768.0f }
        }
        val ratio = sourceSampleRate.toDouble() / TARGET_SAMPLE_RATE
        val outCount = (monoCount / ratio).toInt()
        return FloatArray(outCount) { i ->
            val srcIndex = (i * ratio).toInt().coerceAtMost(monoCount - 1)
            mono[srcIndex] / 32768.0f
        }
    }
}
