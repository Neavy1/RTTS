package com.rtts.app.asr

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class TranscriptQualityTest {
    @Test fun punctuationDoesNotDisguiseRepetition() {
        assertTrue(looksLikeHallucination("todo el chero, todo el chero. todo el chero!"))
    }
    @Test fun shortRadioReadbacksAndNumbersRemainIntact() {
        assertFalse(looksLikeHallucination("Roger, roger."))
        assertFalse(looksLikeHallucination("Cleared runway two seven wind two four zero at ten"))
        assertFalse(looksLikeHallucination("Autorizado a aterrizar pista uno tres derecha"))
    }
    @Test fun failedFinalRetryIsMarkedInsteadOfPublished() {
        val result = rejectRepetition(TranscriptResult("shoot or shoot or shoot or shoot or", "en"))
        assertEquals("[INAUDIBLE]", result.text)
        assertEquals("en", result.lang)
    }
    @Test fun validResultIsUnchanged() {
        val result = TranscriptResult("Contact tower one one eight decimal five", "en")
        assertEquals(result, rejectRepetition(result))
    }
    @Test fun repeatedCallsignDigitsAreNotTreatedAsNoise() {
        assertFalse(looksLikeHallucination("November one one one one one"))
        assertFalse(looksLikeHallucination("uno uno uno uno dos dos"))
        assertFalse(looksLikeHallucination("118.1 118.1 118.1"))
    }
    @Test fun englishAtcRejectsLanguageDriftSeenInVideoBenchmark() {
        assertEquals("[INAUDIBLE]", validateEnglishAtcResult(TranscriptResult("ونحن نحن نحن", "ar")).text)
        assertEquals("[INAUDIBLE]", validateEnglishAtcResult(TranscriptResult("と wrong the way", "ja")).text)
    }
    @Test fun englishAtcDropsSilenceMarkersAndForcesLanguageTag() {
        assertEquals(TranscriptResult("", "en"), validateEnglishAtcResult(TranscriptResult("[no audio]", "en")))
        assertEquals(TranscriptResult("", "en"), validateEnglishAtcResult(TranscriptResult("[BLANK_", "en")))
        assertEquals("[INAUDIBLE]", validateEnglishAtcResult(TranscriptResult("(speaking in foreign language)", "en")).text)
        assertEquals(
            TranscriptResult("Tug 6123 give way to two Americans at Whiskey", "en"),
            validateEnglishAtcResult(TranscriptResult(" Tug 6123 give way to two Americans at Whiskey ", "es")),
        )
    }
}
