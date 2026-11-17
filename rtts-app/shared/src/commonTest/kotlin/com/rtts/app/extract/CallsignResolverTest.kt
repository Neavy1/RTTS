package com.rtts.app.extract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallsignResolverTest {
    private val catalog = callsignCatalog()
    private fun spoken(text: String) = findCallsigns(text).single()
    private fun resolve(text: String, session: Map<String, String> = emptyMap()) =
        resolveCallsign(spoken(text), catalog, session)

    @Test fun catalogIsExtensibleAndContainsAtLeastSixtyDesignators() {
        assertTrue(catalog.size >= 60)
        assertTrue("Europa" in catalog && "Dragster" in catalog && "Brickyard" in catalog)
        assertEquals(1, callsignCatalog(listOf("Délta", "Custom Air")).count { callsignKey(it) == "delta" })
        assertTrue("Custom Air" in callsignCatalog(listOf("Custom Air")))
    }

    @Test fun tractorInRealApproachDoesNotCaptureAltitudesOrHeading() {
        val text = "Approach, Tractor 3916, 12,000 descending, 5,000, heading on 010"
        assertEquals(SpokenCallsign("Tractor", "3916", null), spoken(text))
        assertEquals(Confianza.PARECIDA, resolve(text).confianza)
        assertEquals("Dragster 3916", resolve(text).texto)
    }

    @Test fun dragsterIsExact() {
        assertEquals(Confianza.EXACTA, resolve("Dragster 3916, Miami Approach, welcome").confianza)
    }

    @Test fun deltaIsExactAndSpeedIsNotACallsign() {
        val result = resolve("Delta 1538, reduce speed to 210")
        assertEquals(Confianza.EXACTA, result.confianza)
        assertEquals("Delta 1538", result.texto)
    }

    @Test fun europaKeepsLeadingZeroAndHeavy() {
        val result = resolve("Europa zero niner seven heavy, descend via the bonefish arrival")
        assertEquals("Europa 097 heavy", result.texto)
        assertEquals(Confianza.EXACTA, result.confianza)
    }

    @Test fun marriageHyphenatedDigitsAndSeparatedHeavyRemainUnknown() {
        val text = "Marriage 85-10, heavy roger, turn left, heading 180"
        assertEquals(SpokenCallsign("Marriage", "8510", "heavy"), spoken(text))
        assertEquals(Confianza.DESCONOCIDA, resolve(text).confianza)
        assertEquals("Marriage 8510 heavy", resolve(text).texto)
    }

    @Test fun managerWithCommaIsExtractedWithoutSpeed() {
        val text = "Manager, 8510, heavy maintain 170 knots"
        assertEquals(SpokenCallsign("Manager", "8510", "heavy"), spoken(text))
        assertEquals(Confianza.DESCONOCIDA, resolve(text).confianza)
    }

    @Test fun directToUsesSessionWithoutChangingOriginal() {
        val text = "Direct to 3916, fly heading 040, descend and maintain 3000"
        val session = sessionCallsigns(listOf(text, "Dragster 3916, Miami Approach, welcome"), catalog)
        val result = resolve(text, session)
        assertEquals(Confianza.POR_SESION, result.confianza)
        assertEquals("Dragster 3916", result.texto)
        assertEquals("Direct to 3916", result.original)
    }

    @Test fun interactiveUsesSession() {
        val session = sessionCallsigns(listOf("Dragster 3916", "Interactive 3916, turn right heading 060"), catalog)
        val result = resolve("Interactive 3916, turn right heading 060", session)
        assertEquals(Confianza.POR_SESION, result.confianza)
        assertEquals("Dragster 3916", result.texto)
    }

    @Test fun weakSimilaritiesAreUnknownWithoutSession() {
        listOf("Direct to 3916", "Interactive 3916", "Marriage 8510", "Amarage 8510", "Merger 8510 heavy")
            .forEach { assertEquals(Confianza.DESCONOCIDA, resolve(it).confianza, it) }
    }

    @Test fun unknownWordsDoNotCreateSessionEvidence() {
        val session = sessionCallsigns(listOf("Marriage 8510", "Manager, 8510, heavy maintain 170 knots"), catalog)
        assertTrue(session.isEmpty())
        assertEquals(Confianza.DESCONOCIDA, resolve("Marriage 8510", session).confianza)
    }

    @Test fun tractorRequiresDragsterInCatalog() {
        assertEquals(Confianza.DESCONOCIDA,
            resolveCallsign(spoken("Tractor 3916"), catalog - "Dragster", emptyMap()).confianza)
    }

    @Test fun similarWordCanSeedSession() {
        assertEquals(mapOf("3916" to "Dragster"), sessionCallsigns(listOf("Tractor 3916"), catalog))
    }

    @Test fun oneDigitVariationWithSimilarWordKeepsBothNumbers() {
        val session = sessionCallsigns(listOf("Dragster 3916", "Tractor 3917"), catalog)
        assertEquals(mapOf("3916" to "Dragster", "3917" to "Dragster"), session)
        assertEquals("Dragster 3917", resolve("Interactive 3917", session).texto)
    }

    @Test fun twoDigitVariationIsNotJoined() {
        val session = sessionCallsigns(listOf("Dragster 3916", "drag the 3960"), catalog)
        assertEquals(mapOf("3916" to "Dragster"), session)
        assertEquals(Confianza.DESCONOCIDA, resolve("drag the 3960", session).confianza)
        assertEquals("drag the 3960", resolve("drag the 3960", session).texto)
    }

    @Test fun unknownOneDigitVariationIsNotEnoughEvidence() {
        val session = sessionCallsigns(listOf("Dragster 3916", "Interactive 3917"), catalog)
        assertEquals(Confianza.DESCONOCIDA, resolve("Interactive 3917", session).confianza)
    }

    @Test fun sessionCorrectionPreservesHeavyAndSuper() {
        val session = mapOf("3916" to "Dragster")
        assertEquals("Dragster 3916 heavy", resolve("Interactive 3916 heavy", session).texto)
        assertEquals("Dragster 3916 super", resolve("Interactive 3916 super", session).texto)
    }

    @Test fun noCallsignReturnsEmptyList() {
        assertTrue(findCallsigns("Approach, good morning, roger").isEmpty())
        assertTrue(findCallsigns("").isEmpty())
    }

    @Test fun operationalNumbersAreNeverCallsigns() {
        listOf("heading 180", "maintain 3000", "118.3", "170 knots",
            "fly heading 040, descend and maintain 3000", "contact 118.3",
            "heading on 010", "speed 170 knots", "flight level 180", "QNH 1013")
            .forEach { assertTrue(findCallsigns(it).isEmpty(), it) }
    }

    @Test fun writtenHyphensPreserveLeadingZero() {
        assertEquals("097", spoken("Europa 0-9-7").numero)
        assertEquals("3916", spoken("Dragster 39-16").numero)
    }

    @Test fun individualSpokenDigitsNormalize() {
        assertEquals("3916", spoken("Dragster three niner one six").numero)
    }

    @Test fun novemberRegistrationReusesNormalizer() {
        assertEquals(SpokenCallsign("November", "123AB", null),
            spoken("November one two three alfa bravo, roger"))
    }

    @Test fun hotelKiloRegistrationReusesNormalizer() {
        assertEquals(SpokenCallsign("Hotel Kilo", "2947", null),
            spoken("Hotel Kilo dos nueve cuatro siete"))
    }

    @Test fun exactIgnoresCaseAndAccents() {
        assertEquals(Confianza.EXACTA, resolve("DÉLTA 1538").confianza)
        assertEquals("Delta 1538", resolve("DÉLTA 1538").texto)
    }

    @Test fun multiwordDesignatorsRemainExact() {
        listOf("Air Canada", "Air France", "Blue Streak", "Air Shuttle", "Air Portugal", "All Nippon")
            .forEach { assertEquals(Confianza.EXACTA, resolve("$it 1234").confianza, it) }
    }

    @Test fun conflictsDoNotSupplySessionCorrections() {
        val session = sessionCallsigns(listOf("Delta 1234", "United 1234"), catalog)
        assertTrue(session.isEmpty())
    }

    @Test fun ambiguousSimilarityIsUnknown() {
        val result = resolveCallsign(SpokenCallsign("Tractor", "3916", null),
            listOf("Dragster", "Tracter"), emptyMap())
        assertEquals(Confianza.DESCONOCIDA, result.confianza)
    }

    @Test fun brokenWordsDoNotFuzzilyBecomeAnotherAirline() {
        assertEquals(Confianza.DESCONOCIDA, resolve("drag the 3960").confianza)
    }

    @Test fun multipleCallsignsCanBeFound() {
        assertEquals(listOf("1538", "097"), findCallsigns("Delta 1538, Europa 097 heavy").map { it.numero })
    }
}
