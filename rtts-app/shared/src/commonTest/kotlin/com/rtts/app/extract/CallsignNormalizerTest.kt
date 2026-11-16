package com.rtts.app.extract

import kotlin.test.Test
import kotlin.test.assertEquals

class CallsignNormalizerTest {
    @Test
    fun normalizesPhoneticsDigitsAndCompactCallsigns() {
        val cases = listOf(
            "Hotel Kilo dos nueve cuatro siete" to "HK2947",
            "Hotel Kilo two niner four seven" to "HK2947",
            "HK-2947" to "HK2947", "hk 2947" to "HK2947",
            "Andes ocho cuatro uno dos" to "ANDES8412",
            "Cóndor tres dos siete" to "CONDOR327",
            "Alpha Bravo Charlie Delta Echo Foxtrot Golf" to "ABCDEFG",
            "Hotel India Juliett Kilo Lima Mike November" to "HIJKLMN",
            "Oscar Papa Quebec Romeo Sierra Tango Uniform" to "OPQRSTU",
            "Victor Whiskey X-ray Yankee Zulu" to "VWXYZ",
            "Alfa eco juliet noviembre uniforme whisky yanqui" to "AEJNUWY",
            "zero one two three four fife six seven eight nine" to "0123456789",
            "Hotel Kilo 2 nueve 47" to "HK2947",
            "" to "",
        )
        cases.forEach { (text, result) -> assertEquals(result, normalizeCallsign(text), text) }
    }

    @Test
    fun recognizesCompleteAndAbbreviatedCallsignsAtTokenBoundaries() {
        val cases = listOf(
            "Hotel Kilo dos nueve cuatro siete, torre, mantenga cuatro mil quinientos pies, QNH uno cero uno ocho" to true,
            "HK-2947, recibido" to true,
            "dos nueve cuatro siete, mantenga" to true,
            "nueve cuatro siete, adelante" to true,
            "2947, torre" to true,
            "947, torre" to true,
            "Torre para 9 cuatro siete, adelante" to true,
            "Hotel Kilo 2 nueve 4 siete, torre" to true,
            "Andes ocho cuatro uno dos, torre, viento dos siete cero con ocho, pista cero dos autorizado aterrizar" to false,
            "Hotel Kilo tres nueve cuatro siete, torre" to false,
            "Andes nueve cuatro siete, torre" to false,
            "HK3947, torre" to false,
            "12947, torre" to false,
            "9470, torre" to false,
            "mantenga 2947 pies" to false,
            "QNH 0947" to false,
            "cuatro siete, torre" to false,
            "Hotel Kilo dos nueve cuatro siete uno, torre" to false,
            "Torre, buenos días" to false,
            "" to false,
        )
        cases.forEach { (text, result) -> assertEquals(result, isAddressedTo(text, "HK-2947"), text) }
        assertEquals(false, isAddressedTo("torre", ""))
    }
}
