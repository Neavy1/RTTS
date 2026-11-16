package com.rtts.app.extract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KneeboardTest {
    private fun tx(text: String, time: Long = 1) = KneeboardTransmission(text, time)
    @Test fun emptySession() { assertEquals(Kneeboard(), buildKneeboard(emptyList(), "HK2947")) }
    @Test fun undefinedCallsign() {
        assertEquals(Kneeboard(), buildKneeboard(listOf(tx("HK2947, pista 02")), " "))
    }
    @Test fun ignoresOtherTraffic() {
        assertEquals(Kneeboard(), buildKneeboard(listOf(tx("HK5106, pista 02")), "HK2947"))
    }
    @Test fun mergesAndPreservesFields() {
        val b = buildKneeboard(listOf(tx("HK2947, pista 02, QNH 1018"),
            tx("HK2947, mantenga 4500 pies", 2)), "HK2947")
        assertEquals("02", b.pista); assertEquals("1018", b.qnh)
        assertEquals("4500 ft", b.altitud); assertEquals(2L, b.updatedAtEpochMs)
    }
    @Test fun replacesInChronologicalOrder() {
        val b = buildKneeboard(listOf(tx("HK2947, pista 18", 3), tx("HK2947, pista 02", 1)), "HK2947")
        assertEquals("18", b.pista); assertEquals(3L, b.updatedAtEpochMs)
    }
    @Test fun ambiguousDataDoesNotEraseOrAdvanceTime() {
        val b = buildKneeboard(listOf(tx("HK2947, pista 02"), tx("HK2947, pista 18, pista 27", 2)), "HK2947")
        assertEquals("02", b.pista); assertEquals(1L, b.updatedAtEpochMs)
    }
    @Test fun phoneticAndRemainingCells() {
        val b = buildKneeboard(listOf(tx("Hotel Kilo dos nueve cuatro siete, número tres, transpondedor 4215, viento 270 con 8")), "hk-2947")
        assertEquals("N.º 3", b.turno); assertEquals("4215", b.transpondedor); assertEquals("270/08", b.viento)
    }
    @Test fun frequencyAloneDoesNotUpdateSixCellBoard() {
        val b = buildKneeboard(listOf(tx("HK2947, frecuencia 119.1")), "HK2947")
        assertNull(b.updatedAtEpochMs)
    }
    @Test fun changingCallsignRebuildsFromOwnTraffic() {
        val input = listOf(tx("HK2947, pista 02"), tx("HK5106, QNH 1018", 2))
        assertNull(buildKneeboard(input, "HK5106").pista)
        assertNull(buildKneeboard(input, "HK2947").qnh)
    }
}
