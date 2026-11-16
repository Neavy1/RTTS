package com.rtts.app.extract

import kotlin.test.Test
import kotlin.test.assertEquals

class InstructionExtractorTest {
    private fun expected(
        pista: String? = null, altitud: String? = null, qnh: String? = null,
        frecuencia: String? = null, transpondedor: String? = null,
        viento: String? = null, turno: String? = null,
    ) = Instruction(pista, altitud, qnh, frecuencia, transpondedor, viento, turno)

    @Test
    fun spanishTransmissions() {
        val cases = listOf(
            "Hotel Kilo dos nueve cuatro siete, torre, mantenga cuatro mil quinientos pies, QNH uno cero uno ocho" to expected(altitud = "4500 ft", qnh = "1018"),
            "Andes ocho cuatro uno dos, torre, viento dos siete cero con ocho, pista cero dos autorizado aterrizar" to expected(pista = "02", viento = "270/08", turno = "Autorizado a aterrizar"),
            "Cóndor tres dos siete, descienda a siete mil pies, comunique aproximación uno uno nueve decimal uno" to expected(altitud = "7000 ft", frecuencia = "119.100"),
            "Hotel Kilo dos nueve cuatro siete, entre a básica derecha pista cero dos, número tres, transpondedor cuatro dos uno cinco" to expected(pista = "02", turno = "N.º 3", transpondedor = "4215"),
            "Pista dos siete izquierda" to expected(pista = "27L"),
            "Pista 02 derecha, autorizado a despegar" to expected(pista = "02R", turno = "Autorizado a despegar"),
            "Pista treinta y seis central" to expected(pista = "36C"),
            "Pista 02L" to expected(pista = "02L"),
            "Ascienda a cinco mil pies" to expected(altitud = "5000 ft"),
            "Mantenga 4500 pies, QNH 1018" to expected(altitud = "4500 ft", qnh = "1018"),
            "Mantenga cuatro mil 500 pies" to expected(altitud = "4500 ft"),
            "Nivel de vuelo uno dos cero" to expected(altitud = "FL120"),
            "Descienda a nivel de vuelo 080" to expected(altitud = "FL080"),
            "QNH uno 0 uno ocho" to expected(qnh = "1018"),
            "QNH mil dieciocho" to expected(qnh = "1018"),
            "Altímetro dos nueve nueve dos" to expected(qnh = "29.92"),
            "Comunique frecuencia 119.125" to expected(frecuencia = "119.125"),
            "Aproximación uno dos cero punto cinco" to expected(frecuencia = "120.500"),
            "Transpondedor 0421" to expected(transpondedor = "0421"),
            "Viento 270 con 8 nudos" to expected(viento = "270/08"),
            "Viento tres seis cero grados con quince" to expected(viento = "360/15"),
            "Número 2, entre a base" to expected(turno = "N.º 2"),
            "Autorizada para aterrizar pista uno ocho" to expected(pista = "18", turno = "Autorizado a aterrizar"),
            "Torre, buenos días, recibido" to expected(),
            "HK2947, espere instrucciones" to expected(),
        )
        cases.forEach { (text, result) -> assertEquals(result, extractInstruction(text), text) }
    }

    @Test
    fun englishTransmissions() {
        val cases = listOf(
            "Hotel Kilo two niner four seven, climb and maintain four thousand five hundred, QNH one zero one eight" to expected(altitud = "4500 ft", qnh = "1018"),
            "Runway zero two, cleared to land" to expected(pista = "02", turno = "Autorizado a aterrizar"),
            "Flight level one two zero" to expected(altitud = "FL120"),
            "Altimeter two niner niner two" to expected(qnh = "29.92"),
            "Contact approach one one niner decimal one" to expected(frecuencia = "119.100"),
            "Contact tower one hundred nineteen point one" to expected(frecuencia = "119.100"),
            "Squawk four two one five" to expected(transpondedor = "4215"),
            "Wind two seven zero at eight" to expected(viento = "270/08"),
            "Number three" to expected(turno = "N.º 3"),
            "Runway two seven right, cleared for approach" to expected(pista = "27R"),
            "Runway 27R" to expected(pista = "27R"),
            "Runway 09 left, cleared to take off" to expected(pista = "09L", turno = "Autorizado a despegar"),
            "Descend to seven thousand feet" to expected(altitud = "7000 ft"),
            "Maintain 4 thousand five hundred feet" to expected(altitud = "4500 ft"),
            "Altimeter 29.92, contact tower 118.05" to expected(qnh = "29.92", frecuencia = "118.050"),
            "Wind 180 degrees at 12, runway one eight center" to expected(pista = "18C", viento = "180/12"),
            "Maintain FL120" to expected(altitud = "FL120"),
            "Cleared to takeoff" to expected(turno = "Autorizado a despegar"),
        )
        cases.forEach { (text, result) -> assertEquals(result, extractInstruction(text), text) }
    }

    @Test
    fun maintainWithThousandsComma() {
        assertEquals(expected(altitud = "3000 ft"), extractInstruction("maintain 3,000"))
    }

    @Test
    fun descendWithThousandsComma() {
        assertEquals(expected(altitud = "7000 ft"), extractInstruction("descend 7,000 feet"))
    }

    @Test
    fun maintainedWithThousandsComma() {
        assertEquals(expected(altitud = "3000 ft"), extractInstruction("The center maintained 3,000."))
    }

    @Test
    fun missingInvalidAndConflictingDataRemainUnknown() {
        val cases = listOf(
            "Good morning, standby", "pista", "pista cuarenta", "QNH uno cero",
            "squawk 8912", "transpondedor cuatro dos uno", "wind 400 at 8",
            "viento variable con ocho", "contact approach one one niner",
            "mantenga posición", "no autorizado a aterrizar", "not cleared to land",
            "pista cero dos, pista uno ocho", "QNH 1018, QNH 1020",
            "frecuencia 119.1234", "altimeter 29.9", "flight level", "number",
            "HK2947 Andes8412 4500 1018 4215", "altimeter 29.923",
        )
        cases.forEach { assertEquals(expected(), extractInstruction(it), it) }
    }
}
