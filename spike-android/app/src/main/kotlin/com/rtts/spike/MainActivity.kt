package com.rtts.spike

import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

private const val TAG = "RttsSpike"

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply {
            textSize = 11f
            setPadding(24, 24, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(logView) })

        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.Default) {
                runSpike()
            }
        }
    }

    private fun appendLog(line: String) {
        Log.i(TAG, line)
        log.append(line).append('\n')
        runOnUiThread { logView.text = log.toString() }
    }

    private fun runSpike() {
        val base = getExternalFilesDir(null)!!.absolutePath
        val modelsDir = "$base/models"
        val samplesDir = "$base/samples"
        val resultFile = File("$base/spike_results.txt")
        val resultWriter = FileWriter(resultFile)

        fun out(s: String) {
            appendLog(s)
            resultWriter.write(s)
            resultWriter.write("\n")
            resultWriter.flush()
        }

        out("RTTS Android spike starting. base=$base")

        if (!File(modelsDir).exists()) {
            out("ERROR: models dir not found at $modelsDir. Push models first via adb push.")
            resultWriter.close()
            return
        }

        val whisperConfig = OfflineWhisperModelConfig(
            encoder = "$modelsDir/sherpa-onnx-whisper-small/small-encoder.int8.onnx",
            decoder = "$modelsDir/sherpa-onnx-whisper-small/small-decoder.int8.onnx",
            language = "",
            task = "transcribe",
        )
        val modelConfig = OfflineModelConfig(
            whisper = whisperConfig,
            tokens = "$modelsDir/sherpa-onnx-whisper-small/small-tokens.txt",
            modelType = "whisper",
            numThreads = 4,
        )
        val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
        val recConfig = OfflineRecognizerConfig(featConfig = featConfig, modelConfig = modelConfig)

        out("Loading Whisper small (int8)...")
        var t0 = System.nanoTime()
        val recognizer = OfflineRecognizer(config = recConfig)
        out("  loaded in %.1fs".format((System.nanoTime() - t0) / 1e9))

        val sileroConfig = SileroVadModelConfig(
            model = "$modelsDir/silero_vad.onnx",
            threshold = 0.5f,
            minSilenceDuration = 0.5f,
            minSpeechDuration = 0.25f,
            windowSize = 512,
            maxSpeechDuration = 20.0f,
        )
        val vadModelConfig = VadModelConfig(
            sileroVadModelConfig = sileroConfig,
            sampleRate = 16000,
            numThreads = 1,
        )

        val pyannoteConfig = OfflineSpeakerSegmentationPyannoteModelConfig(
            model = "$modelsDir/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx",
        )
        val segConfig = OfflineSpeakerSegmentationModelConfig(pyannote = pyannoteConfig)
        val embConfig = SpeakerEmbeddingExtractorConfig(
            model = "$modelsDir/campplus_sv_en_voxceleb_16k.onnx",
        )
        val clusterConfig = FastClusteringConfig(threshold = 0.7f)
        val sdConfig = OfflineSpeakerDiarizationConfig(
            segmentation = segConfig,
            embedding = embConfig,
            clustering = clusterConfig,
        )

        out("Loading speaker diarization pipeline...")
        t0 = System.nanoTime()
        val sd = OfflineSpeakerDiarization(config = sdConfig)
        out("  loaded in %.1fs".format((System.nanoTime() - t0) / 1e9))

        val sampleNames = listOf("videoejemplo.wav", "ContactoAviones.wav", "WhatsAppAudio.wav")
        val sampleFiles = sampleNames.map { File("$samplesDir/$it") }

        for (f in sampleFiles) {
            out("  candidate=${f.absolutePath} exists=${f.exists()} canRead=${f.canRead()} len=${f.length()}")
        }

        for (sampleFile in sampleFiles) {
            out("\n=========================================")
            out("FILE: ${sampleFile.absolutePath}")
            val wave = WaveReader.readWave(filename = sampleFile.absolutePath)
            val sr = wave.sampleRate
            val durationSec = wave.samples.size / sr.toDouble()
            out("  duration=%.1fs sampleRate=%d".format(durationSec, sr))

            val dT0 = System.nanoTime()
            val diarSegments = sd.process(wave.samples)
            val diarSeconds = (System.nanoTime() - dT0) / 1e9
            out("  [diarization] ${diarSegments.size} segments in %.1fs (RTF=%.2f)".format(
                diarSeconds, diarSeconds / durationSec))
            val distinctSpeakers = diarSegments.map { it.speaker }.distinct().size
            out("  [diarization] distinct speakers: $distinctSpeakers")

            val vad = Vad(config = vadModelConfig)
            val windowSize = 512
            var totalAsrAudioSec = 0.0
            var totalAsrNanos = 0L
            var segIndex = 0

            out("  [transcript]")
            var offset = 0
            while (offset + windowSize <= wave.samples.size) {
                val chunk = wave.samples.copyOfRange(offset, offset + windowSize)
                vad.acceptWaveform(chunk)
                while (!vad.empty()) {
                    val seg = vad.front()
                    vad.pop()
                    segIndex++
                    val segStartSec = seg.start / sr.toDouble()
                    val segDurSec = seg.samples.size / sr.toDouble()

                    val aT0 = System.nanoTime()
                    val stream = recognizer.createStream()
                    stream.acceptWaveform(seg.samples, sampleRate = sr)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    stream.release()
                    val aNanos = System.nanoTime() - aT0

                    totalAsrAudioSec += segDurSec
                    totalAsrNanos += aNanos

                    out("    [#%d %.1fs -> %.1fs | %.1fs, decode=%.2fs] %s".format(
                        segIndex, segStartSec, segStartSec + segDurSec, segDurSec,
                        aNanos / 1e9, result.text))
                }
                offset += windowSize
            }
            vad.flush()
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                segIndex++
                val segStartSec = seg.start / sr.toDouble()
                val segDurSec = seg.samples.size / sr.toDouble()

                val aT0 = System.nanoTime()
                val stream = recognizer.createStream()
                stream.acceptWaveform(seg.samples, sampleRate = sr)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                stream.release()
                val aNanos = System.nanoTime() - aT0

                totalAsrAudioSec += segDurSec
                totalAsrNanos += aNanos

                out("    [#%d %.1fs -> %.1fs | %.1fs, decode=%.2fs] %s".format(
                    segIndex, segStartSec, segStartSec + segDurSec, segDurSec,
                    aNanos / 1e9, result.text))
            }
            vad.release()

            val totalAsrSec = totalAsrNanos / 1e9
            out("  [asr summary] $segIndex segments, %.1fs speech audio, %.1fs decode time, RTF=%.2f".format(
                totalAsrAudioSec, totalAsrSec, totalAsrSec / maxOf(totalAsrAudioSec, 0.001)))
        }

        recognizer.release()
        sd.release()
        out("\nDone. Results written to ${resultFile.absolutePath}")
        resultWriter.close()
    }
}
