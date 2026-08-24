package com.rtts.app.asr

import android.content.Context
import java.io.File

/**
 * Extracts the ASR models bundled as APK assets into internal storage on first run.
 * sherpa-onnx's ONNX Runtime session cannot read directly from a compressed asset stream,
 * so the files must live as plain files on disk.
 */
class ModelManager(private val context: Context) {

    private val modelsDir = File(context.filesDir, "models")

    val whisperEncoderPath: String
        get() = File(modelsDir, "sherpa-onnx-whisper-base/base-encoder.int8.onnx").absolutePath
    val whisperDecoderPath: String
        get() = File(modelsDir, "sherpa-onnx-whisper-base/base-decoder.int8.onnx").absolutePath
    val whisperTokensPath: String
        get() = File(modelsDir, "sherpa-onnx-whisper-base/base-tokens.txt").absolutePath
    val vadModelPath: String
        get() = File(modelsDir, "silero_vad.onnx").absolutePath

    fun ensureModelsExtracted() {
        val marker = File(modelsDir, ".extracted")
        if (marker.exists()) return

        modelsDir.deleteRecursively()
        modelsDir.mkdirs()
        copyAssetTree("models", modelsDir)
        marker.createNewFile()
    }

    private fun copyAssetTree(assetPath: String, destDir: File) {
        val entries = context.assets.list(assetPath) ?: return
        destDir.mkdirs()
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childDest = File(destDir, entry)
            val childEntries = context.assets.list(childAssetPath)
            if (childEntries.isNullOrEmpty()) {
                context.assets.open(childAssetPath).use { input ->
                    childDest.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                copyAssetTree(childAssetPath, childDest)
            }
        }
    }
}
