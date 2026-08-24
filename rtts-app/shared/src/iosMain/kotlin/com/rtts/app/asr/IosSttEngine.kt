package com.rtts.app.asr

/**
 * TODO(iOS): not implemented yet. Needs, in order:
 *  1. Build sherpa-onnx for iOS (produces a .xcframework) -- see
 *     https://k2-fsa.github.io/sherpa/onnx/ios/build-sherpa-onnx-swift.html
 *  2. Write a Kotlin/Native cinterop .def binding against sherpa-onnx's C API
 *     (sherpa-onnx-c-api.h) so this class can call it directly, the same way the Android
 *     implementation calls the Kotlin JNI bindings in the AAR.
 * Both steps require a macOS host to build/verify.
 */
class IosSttEngine : SttEngine {
    override fun acceptAudioChunk(samples: FloatArray): List<AudioSegment> =
        throw NotImplementedError("IosSttEngine: sherpa-onnx iOS binding not written yet, see class doc")

    override fun flushPending(): List<AudioSegment> =
        throw NotImplementedError("IosSttEngine: sherpa-onnx iOS binding not written yet, see class doc")

    override fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptResult =
        throw NotImplementedError("IosSttEngine: sherpa-onnx iOS binding not written yet, see class doc")

    override fun release() = Unit
}
