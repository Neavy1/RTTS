package com.rtts.app.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import com.rtts.app.asr.ModelManager
import com.rtts.app.asr.SherpaOnnxSttEngine
import com.rtts.app.asr.looksLikeHallucination
import java.io.DataInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SAMPLE_RATE = 16000
private const val WAV_HEADER_BYTES = 44
private const val CHUNK_SAMPLES = 3200 // ~200ms, matches FileAudioSource's live-feed chunk size

/**
 * Headless benchmark: feeds the bundled real-audio samples through the current
 * SherpaOnnxSttEngine configuration (whatever model is in the APK's assets right now) and
 * writes a structured, human-readable report -- no UI interaction needed, so switching the
 * bundled model + rebuilding + running this is the whole loop for an apples-to-apples
 * tiny/base/small comparison.
 *
 * Not part of the SttEngine abstraction: this is diagnostic tooling around the concrete
 * Android engine, not something the rest of the app (or iOS) needs to know about.
 */
object BenchmarkRunner {

    private val SAMPLE_ASSETS = listOf(
        "samples/videoejemplo.wav",
        "samples/ContactoAviones.wav",
        "samples/WhatsAppAudio.wav",
        "samples/WhatsAppAudio2.wav",
    )

    fun run(context: Context): File {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val pssBeforeLoadKb = processPssKb()

        val modelManager = ModelManager(context)
        modelManager.ensureModelsExtracted()
        val modelLabel = File(modelManager.whisperEncoderPath).parentFile?.name ?: "?"

        val loadStart = System.nanoTime()
        val engine = SherpaOnnxSttEngine(modelManager)
        val loadNanos = System.nanoTime() - loadStart
        val pssAfterLoadKb = processPssKb()

        val sb = StringBuilder()
        sb.appendLine("RTTS -- Benchmark de reconocimiento de voz (sherpa-onnx)")
        sb.appendLine("=".repeat(64))
        sb.appendLine()
        sb.appendLine("-- Dispositivo --")
        sb.appendLine("Modelo de equipo   : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Hardware/SoC       : ${Build.HARDWARE} (board=${Build.BOARD})")
        sb.appendLine("ABIs soportadas    : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("Android            : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("RAM total del equipo: ${memInfo.totalMem / (1024 * 1024)} MB")
        sb.appendLine()
        sb.appendLine("-- Modelo --")
        sb.appendLine("Modelo Whisper     : $modelLabel")
        sb.appendLine("Threads            : ${engine.numThreads}")
        sb.appendLine("Tiempo de carga     : %.2f s".format(loadNanos / 1e9))
        sb.appendLine("PSS antes de cargar : ${pssBeforeLoadKb / 1024} MB")
        sb.appendLine("PSS tras cargar     : ${pssAfterLoadKb / 1024} MB")
        sb.appendLine("PSS atribuible al modelo (aprox.): ${(pssAfterLoadKb - pssBeforeLoadKb) / 1024} MB")
        sb.appendLine()

        var grandAudioSec = 0.0
        var grandDecodeSec = 0.0
        var grandSegments = 0
        var grandHallucinations = 0
        var grandRetries = 0
        var peakPssKb = pssAfterLoadKb

        for (assetPath in SAMPLE_ASSETS) {
            sb.appendLine("-- Archivo: $assetPath --")
            val readStart = System.nanoTime()
            val samples = readWavAsset(context, assetPath)
            val readNanos = System.nanoTime() - readStart
            val durationSec = samples.size / SAMPLE_RATE.toDouble()

            val vadNanosBefore = engine.totalVadNanos
            val decodeNanosBefore = engine.totalDecodeNanos
            val retriesBefore = engine.retriedSegmentCount

            var offset = 0
            var segmentIndex = 0
            var fileHallucinations = 0
            while (offset < samples.size) {
                val end = minOf(offset + CHUNK_SAMPLES, samples.size)
                val chunk = samples.copyOfRange(offset, end)
                for (seg in engine.acceptAudioChunk(chunk)) {
                    segmentIndex++
                    val result = engine.transcribe(seg.samples, SAMPLE_RATE)
                    val flagged = result.text.isNotBlank() && looksLikeHallucination(result.text)
                    if (flagged) fileHallucinations++
                    val startS = seg.startSample / SAMPLE_RATE.toDouble()
                    val durS = seg.samples.size / SAMPLE_RATE.toDouble()
                    sb.appendLine(
                        "  [%2d] %6.1fs -> %6.1fs (%4.1fs) [%s]%s  %s".format(
                            segmentIndex, startS, startS + durS, durS, result.lang,
                            if (flagged) " (?)" else "", result.text.ifBlank { "(vacío)" },
                        )
                    )
                }
                offset = end
            }
            for (seg in engine.flushPending()) {
                segmentIndex++
                val result = engine.transcribe(seg.samples, SAMPLE_RATE)
                val flagged = result.text.isNotBlank() && looksLikeHallucination(result.text)
                if (flagged) fileHallucinations++
                val startS = seg.startSample / SAMPLE_RATE.toDouble()
                val durS = seg.samples.size / SAMPLE_RATE.toDouble()
                sb.appendLine(
                    "  [%2d] %6.1fs -> %6.1fs (%4.1fs) [%s]%s  %s".format(
                        segmentIndex, startS, startS + durS, durS, result.lang,
                        if (flagged) " (?)" else "", result.text.ifBlank { "(vacío)" },
                    )
                )
            }

            val fileVadSec = (engine.totalVadNanos - vadNanosBefore) / 1e9
            val fileDecodeSec = (engine.totalDecodeNanos - decodeNanosBefore) / 1e9
            val fileRetries = engine.retriedSegmentCount - retriesBefore
            val fileRtf = if (durationSec > 0) fileDecodeSec / durationSec else 0.0
            peakPssKb = maxOf(peakPssKb, processPssKb())

            sb.appendLine("  Duración audio     : %.1f s".format(durationSec))
            sb.appendLine("  Decodificar+resample: %.2f s".format(readNanos / 1e9))
            sb.appendLine("  Tiempo VAD          : %.2f s".format(fileVadSec))
            sb.appendLine("  Tiempo ASR (decode) : %.2f s".format(fileDecodeSec))
            sb.appendLine("  RTF (ASR/duración)  : %.2fx".format(fileRtf))
            sb.appendLine("  Segmentos           : $segmentIndex")
            sb.appendLine("  Reintentos idioma   : $fileRetries")
            sb.appendLine("  Posibles alucinaciones: $fileHallucinations")
            sb.appendLine()

            grandAudioSec += durationSec
            grandDecodeSec += fileDecodeSec
            grandSegments += segmentIndex
            grandHallucinations += fileHallucinations
            grandRetries += fileRetries
        }

        engine.release()
        peakPssKb = maxOf(peakPssKb, processPssKb())

        sb.appendLine("-- Resumen ($modelLabel) --")
        sb.appendLine("Audio total          : %.1f s".format(grandAudioSec))
        sb.appendLine("ASR total            : %.1f s".format(grandDecodeSec))
        sb.appendLine("RTF global           : %.2fx".format(if (grandAudioSec > 0) grandDecodeSec / grandAudioSec else 0.0))
        sb.appendLine("Segmentos totales    : $grandSegments")
        sb.appendLine("Reintentos de idioma : $grandRetries")
        sb.appendLine("Posibles alucinaciones: $grandHallucinations")
        sb.appendLine("PSS máxima observada : ${peakPssKb / 1024} MB")

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outDir = File(context.getExternalFilesDir(null), "benchmark")
        outDir.mkdirs()
        val outFile = File(outDir, "benchmark_${modelLabel}_$stamp.txt")
        outFile.writeText(sb.toString())
        return outFile
    }

    private fun processPssKb(): Int {
        val am = Debug.MemoryInfo()
        Debug.getMemoryInfo(am)
        return am.totalPss
    }

    private fun readWavAsset(context: Context, assetPath: String): FloatArray {
        context.assets.open(assetPath).use { raw ->
            val input = DataInputStream(raw)
            input.skipBytes(WAV_HEADER_BYTES)
            val bytes = input.readBytes()
            val sampleCount = bytes.size / 2
            return FloatArray(sampleCount) { i ->
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                sample.toShort() / 32768.0f
            }
        }
    }
}
