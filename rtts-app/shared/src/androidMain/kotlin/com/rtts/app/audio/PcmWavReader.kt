package com.rtts.app.audio

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** Reads RIFF chunks instead of treating metadata after byte 44 as audio. */
class PcmWavReader(input: InputStream) {
    private val stream = DataInputStream(input)
    private var remaining = 0L

    init {
        require(tag() == "RIFF") { "El archivo no es WAV RIFF" }
        uint32()
        require(tag() == "WAVE") { "El archivo no es WAV" }
        var validFormat = false
        while (true) {
            val id = tag()
            val size = uint32()
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Cabecera WAV incompleta" }
                    val encoding = uint16()
                    val channels = uint16()
                    val rate = uint32()
                    uint32()
                    val alignment = uint16()
                    val bits = uint16()
                    require(encoding == 1 && channels == 1 && rate == 16000L && bits == 16 && alignment == 2) {
                        "Las muestras deben ser PCM16 mono a 16 kHz"
                    }
                    skip(size - 16)
                    validFormat = true
                }
                "data" -> {
                    require(validFormat && size % 2L == 0L) { "Datos WAV inválidos" }
                    remaining = size
                    break
                }
                else -> skip(size)
            }
            if (size % 2L != 0L) skip(1)
        }
    }

    fun readSamples(maxSamples: Int): FloatArray {
        require(maxSamples > 0)
        val count = minOf(maxSamples.toLong(), remaining / 2).toInt()
        val result = FloatArray(count) { uint16().toShort() / 32768.0f }
        remaining -= count * 2L
        return result
    }

    private fun tag() = ByteArray(4).also { stream.readFully(it) }.toString(Charsets.US_ASCII)
    private fun uint16() = stream.readUnsignedByte() or (stream.readUnsignedByte() shl 8)
    private fun uint32() = (uint16().toLong() or (uint16().toLong() shl 16))
    private fun skip(size: Long) {
        var left = size
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped > 0) left -= skipped
            else { if (stream.read() == -1) throw EOFException("WAV truncado"); left-- }
        }
    }
}
