package com.rtts.app.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Writes 16kHz mono float samples ([-1, 1]) as a plain PCM16 WAV file, so a transcript
 * segment's original audio can be played back later for verification.
 */
object WavFileWriter {

    private const val SAMPLE_RATE = 16000
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1

    fun write(file: File, samples: FloatArray) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val dataSize = samples.size * 2
            val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

            fun writeIntLE(v: Int) {
                raf.write(v and 0xFF)
                raf.write((v shr 8) and 0xFF)
                raf.write((v shr 16) and 0xFF)
                raf.write((v shr 24) and 0xFF)
            }
            fun writeShortLE(v: Int) {
                raf.write(v and 0xFF)
                raf.write((v shr 8) and 0xFF)
            }

            raf.writeBytes("RIFF")
            writeIntLE(36 + dataSize)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeIntLE(16)
            writeShortLE(1) // PCM
            writeShortLE(CHANNELS)
            writeIntLE(SAMPLE_RATE)
            writeIntLE(byteRate)
            writeShortLE(CHANNELS * BITS_PER_SAMPLE / 8)
            writeShortLE(BITS_PER_SAMPLE)
            raf.writeBytes("data")
            writeIntLE(dataSize)

            val bytes = ByteArray(dataSize)
            for (i in samples.indices) {
                val s = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                bytes[i * 2] = (s and 0xFF).toByte()
                bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            raf.write(bytes)
        }
    }
}
