package com.rtts.app.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringsTest {
    @Test fun bothCatalogsHaveNoEmptyTextProperties() {
        listOf(EnglishStrings, SpanishStrings).forEach { strings ->
            val properties = listOf(
                strings.onFrequency,
                strings.lastForMe,
                strings.trafficNearby,
                strings.listen,
                strings.stop,
                strings.markMoment,
                strings.forMeOnly,
                strings.all,
                strings.myCallsign,
                strings.tapToSet,
                strings.radioLanguage,
                strings.runway,
                strings.altitude,
                strings.frequency,
                strings.squawk,
                strings.wind,
                strings.sequence,
                strings.callsign,
                strings.paused,
                strings.listening,
                strings.finishing,
                strings.forMe,
                strings.radio,
                strings.more,
                strings.testSamples,
                strings.chooseFile,
                strings.listeningAid,
                strings.nextCapture,
                strings.english,
                strings.spanish,
                strings.automatic,
                strings.close,
                strings.callsignExample,
                strings.save,
                strings.cancel,
                strings.noTransmissionsForMe,
                strings.listeningEmpty,
                strings.startCapture,
                strings.smallerText,
                strings.largerText,
                strings.decreaseText,
                strings.increaseText,
                strings.defineCallsign,
                strings.noInstructions,
                strings.qnh,
                strings.unavailable,
                strings.trafficComingSoon,
                strings.appName,
                strings.radioToText,
                strings.inputLevel,
                strings.input,
                strings.noCapture,
                strings.radioAudio,
                strings.fileSamples,
                strings.language,
                strings.utcTime,
                strings.microphonePermission,
                strings.finishingTranscript,
                strings.pauseTransmission,
                strings.replayTransmission,
                strings.unmarkTransmission,
                strings.markTransmission,
                strings.captureFailed,
                strings.notificationChannel,
                strings.notificationTitle,
                strings.notificationText,
                strings.appLanguage,
                strings.englishCode,
                strings.spanishCode,
                strings.automaticCode,
                strings.clearedToLand,
                strings.clearedForTakeoff,
            )
            properties.forEachIndexed { index, text ->
                assertTrue(text.isNotBlank(), "${strings.language} property $index is blank")
            }
            assertEquals("≈ Europa 097", strings.correctedCallsign("Europa 097"))
            assertTrue(strings.updatedAt("12:34:56").isNotBlank())
            assertTrue(strings.queueStatus("1.5", 2L).isNotBlank())
            assertTrue(strings.sequenceNumber("3").isNotBlank())
        }
    }

    @Test fun sequenceLabelsTranslateWithoutChangingExtractorValues() {
        val values = listOf("N.º 3", "Autorizado a aterrizar", "Autorizado a despegar")
        assertEquals(listOf("No. 3", "Cleared to land", "Cleared for takeoff"),
            values.map(EnglishStrings::sequenceLabel))
        assertEquals(values, values.map(SpanishStrings::sequenceLabel))
        assertEquals("Unknown value", EnglishStrings.sequenceLabel("Unknown value"))
        assertEquals("Unknown value", SpanishStrings.sequenceLabel("Unknown value"))
    }
}
