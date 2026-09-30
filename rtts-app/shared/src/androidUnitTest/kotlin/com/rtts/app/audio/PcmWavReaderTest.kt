package com.rtts.app.audio

import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals

class PcmWavReaderTest {
    private fun wav(metadata: Boolean = false, rate: Int = 16000): ByteArray {
        val body = ByteArrayOutputStream()
        fun uint(value: Int, bytes: Int) { repeat(bytes) { body.write(value ushr (it * 8) and 255) } }
        body.write("WAVEfmt ".toByteArray())
        uint(16, 4); uint(1, 2); uint(1, 2); uint(rate, 4); uint(rate * 2, 4); uint(2, 2); uint(16, 2)
        if (metadata) {
            body.write("JUNK".toByteArray()); uint(3, 4); body.write(byteArrayOf(5, 6, 7, 0))
            body.write("LIST".toByteArray()); uint(4, 4); body.write("INFO".toByteArray())
        }
        body.write("data".toByteArray()); uint(6, 4)
        uint(-32768, 2); uint(0, 2); uint(16384, 2)
        val result = ByteArrayOutputStream()
        result.write("RIFF".toByteArray())
        repeat(4) { result.write(body.size() ushr (it * 8) and 255) }
        result.write(body.toByteArray())
        return result.toByteArray()
    }
    @Test fun handlesStandardAndExtendedHeadersWithoutEmittingMetadata() {
        for (metadata in listOf(false, true)) {
            val reader = PcmWavReader(wav(metadata).inputStream())
            assertContentEquals(floatArrayOf(-1f, 0f), reader.readSamples(2))
            assertContentEquals(floatArrayOf(0.5f), reader.readSamples(2))
            assertEquals(0, reader.readSamples(2).size)
        }
    }
    @Test fun rejectsUnexpectedSampleRateInsteadOfMislabelingAudio() {
        assertFailsWith<IllegalArgumentException> { PcmWavReader(wav(rate = 48000).inputStream()) }
    }
    @Test fun rejectsTruncatedPcm() {
        val reader = PcmWavReader(wav().dropLast(1).toByteArray().inputStream())
        assertFailsWith<EOFException> { reader.readSamples(10) }
    }
}
