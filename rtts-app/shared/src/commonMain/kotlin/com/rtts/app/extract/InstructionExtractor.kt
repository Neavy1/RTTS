package com.rtts.app.extract

data class Instruction(
    val pista: String?,
    val altitud: String?,
    val qnh: String?,
    val frecuencia: String?,
    val transpondedor: String?,
    val viento: String?,
    val turno: String?,
)

/** Context markers are required. Conflicting values for the same field remain unknown. */
fun extractInstruction(texto: String): Instruction {
    val t = words(texto)
    val fields = List(7) { mutableSetOf<String>() }
    fun put(field: Int, value: String?) { if (value != null) fields[field].add(value) }
    fun at(index: Int) = t.getOrNull(index)
    fun skip(index: Int, vararg fillers: String): Int {
        var p = index
        while (at(p) in fillers) p++
        return p
    }
    fun digits(index: Int) = digitRun(t, index)
    fun number(index: Int) = numberRun(t, index)
    fun altitudeNumber(index: Int): Pair<Int?, Int> {
        // Whisper's written thousands separator belongs to altitudes, not decimal frequencies.
        val whole = at(index)
        val thousands = at(index + 2)
        if (whole != null && Regex("[1-9][0-9]?").matches(whole) && at(index + 1) == "," &&
            thousands != null && Regex("[0-9]{3}").matches(thousands)) {
            return (whole + thousands).toIntOrNull() to (index + 3)
        }
        return number(index)
    }
    fun decimal(index: Int): Pair<String, String>? {
        val (whole, end) = number(index)
        if (whole == null || at(end) !in listOf("decimal", "point", "punto", ".", ",")) return null
        val (fraction, _) = digits(end + 1)
        return if (fraction.isNotEmpty()) whole.toString() to fraction else null
    }
    for (i in t.indices) {
        when (t[i]) {
            "pista", "runway" -> {
                val p = skip(i + 1, "la", "the")
                val compact = at(p)?.let { Regex("([0-9]{1,2})([lrc])").matchEntire(it) }
                if (compact != null) {
                    val runway = compact.groupValues[1].toInt()
                    if (runway in 1..36) put(0, runway.toString().padStart(2, '0') + compact.groupValues[2].uppercase())
                    continue
                }
                val (value, end) = number(p)
                if (value != null && value in 1..36) {
                    val side = when (at(end)) {
                        "l", "izquierda", "left" -> "L"
                        "r", "derecha", "right" -> "R"
                        "c", "central", "centro", "center", "centre" -> "C"
                        else -> ""
                    }
                    put(0, value.toString().padStart(2, '0') + side)
                }
            }
            "nivel", "flight", "fl" -> {
                val p = when (t[i]) {
                    "nivel" -> skip(i + 1, "de", "vuelo")
                    "flight" -> if (at(i + 1) == "level") i + 2 else continue
                    else -> i + 1
                }
                val (value, _) = number(p)
                if (value != null && value in 1..600) put(1, "FL" + value.toString().padStart(3, '0'))
            }
            "mantenga", "mantener", "ascienda", "descienda", "altitud", "maintain", "maintained", "climb", "descend", "altitude" -> {
                val p = skip(i + 1, "a", "hasta", "to", "and", "maintain", "la", "una", "altitud", "de")
                val (value, end) = altitudeNumber(p)
                if (value != null && value in 1..60000 && at(end) !in listOf("mil", "thousand", "hundred")) {
                    put(1, "$value ft")
                }
            }
            "qnh", "altimeter", "altimetro" -> {
                val p = skip(i + 1, ":", "es", "is", "setting", "ajuste", "de")
                val dec = decimal(p)
                val (raw, _) = digits(p)
                if (t[i] == "qnh") {
                    val (pressure, _) = number(p)
                    if (pressure != null && pressure in 850..1100 && dec == null) put(2, pressure.toString())
                } else {
                    val compact = if (dec != null && dec.first.length == 2 && dec.second.length == 2) dec.first + dec.second else raw
                    if (compact.length == 4 && compact.toIntOrNull() in 2800..3199) put(2, compact.take(2) + "." + compact.takeLast(2))
                }
            }
            "transpondedor", "transponder", "squawk" -> {
                val p = skip(i + 1, ":", "codigo", "code", "en", "a")
                val (code, _) = digits(p)
                if (code.length == 4 && code.all { it in '0'..'7' }) put(4, code)
            }
            "viento", "wind" -> {
                val p = skip(i + 1, "de", "del", "from", ":")
                val (direction, end) = number(p)
                val speedStart = skip(end, "grados", "degrees")
                if (at(speedStart) in listOf("con", "at", "a")) {
                    val (speed, _) = number(speedStart + 1)
                    if (direction != null && direction in 0..360 && speed != null && speed in 0..99) {
                        put(5, direction.toString().padStart(3, '0') + "/" + speed.toString().padStart(2, '0'))
                    }
                }
            }
            "numero", "number" -> {
                val (value, _) = number(i + 1)
                if (value != null && value in 1..99) put(6, "N.º $value")
            }
        }
        // Decimal frequencies are self-identifying within the aviation VHF band.
        if (i == 0 || digitToken(t[i - 1]) == null) {
            val dec = decimal(i)
            if (dec != null && dec.first.toIntOrNull() in 118..136 && dec.second.length in 1..3) {
                put(3, dec.first + "." + dec.second.padEnd(3, '0'))
            }
        }
        if (t[i] in listOf("autorizado", "autorizada", "cleared") &&
            at(i - 1) !in listOf("no", "not", "never", "nunca")) {
            val p = skip(i + 1, "a", "para", "to")
            when (at(p)) {
                "aterrizar", "land" -> put(6, "Autorizado a aterrizar")
                "despegar", "takeoff" -> put(6, "Autorizado a despegar")
                "take" -> if (at(p + 1) == "off") put(6, "Autorizado a despegar")
            }
        }
        // Whisper can retain the compact FL120 spelling.
        if (Regex("fl[0-9]{2,3}").matches(t[i])) {
            val level = t[i].drop(2).toInt()
            if (level in 1..600) put(1, "FL" + level.toString().padStart(3, '0'))
        }
    }
    fun result(index: Int) = fields[index].singleOrNull()
    return Instruction(result(0), result(1), result(2), result(3), result(4), result(5), result(6))
}
