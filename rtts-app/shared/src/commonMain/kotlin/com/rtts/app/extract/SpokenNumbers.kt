package com.rtts.app.extract

internal fun words(text: String): List<String> = text.lowercase()
    .replace('á', 'a').replace('é', 'e').replace('í', 'i')
    .replace('ó', 'o').replace('ú', 'u').replace('ü', 'u')
    .let { Regex("[a-z0-9]+|[.,;:!?]").findAll(it).map { match -> match.value }.toList() }

internal val spokenDigits = mapOf(
    "cero" to "0", "zero" to "0", "oh" to "0",
    "uno" to "1", "una" to "1", "un" to "1", "one" to "1",
    "dos" to "2", "two" to "2", "tres" to "3", "three" to "3",
    "cuatro" to "4", "four" to "4", "cinco" to "5", "five" to "5", "fife" to "5",
    "seis" to "6", "six" to "6", "siete" to "7", "seven" to "7",
    "ocho" to "8", "eight" to "8", "nueve" to "9", "nine" to "9", "niner" to "9",
)

internal fun digitToken(token: String): String? = spokenDigits[token]
    ?: token.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

/** Reads only adjacent digit tokens, never skips unknown words. */
internal fun digitRun(tokens: List<String>, start: Int): Pair<String, Int> {
    var end = start
    val result = StringBuilder()
    while (end < tokens.size) {
        result.append(digitToken(tokens[end]) ?: break)
        end++
    }
    return result.toString() to end
}

private val cardinal = mapOf(
    "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14,
    "quince" to 15, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18,
    "diecinueve" to 19, "veinte" to 20, "veintiuno" to 21, "veintidos" to 22,
    "veintitres" to 23, "veinticuatro" to 24, "veinticinco" to 25,
    "veintiseis" to 26, "veintisiete" to 27, "veintiocho" to 28, "veintinueve" to 29,
    "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60,
    "setenta" to 70, "ochenta" to 80, "noventa" to 90,
    "cien" to 100, "ciento" to 100, "doscientos" to 200, "trescientos" to 300,
    "cuatrocientos" to 400, "quinientos" to 500, "seiscientos" to 600,
    "setecientos" to 700, "ochocientos" to 800, "novecientos" to 900,
    "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
    "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
    "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
    "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
    "eighty" to 80, "ninety" to 90,
)

internal fun numberRun(tokens: List<String>, start: Int): Pair<Int?, Int> {
    var end = start
    var group = 0
    var total = 0
    var seen = false
    val collected = mutableListOf<String>()
    while (end < tokens.size) {
        val token = tokens[end]
        val value = digitToken(token)?.toIntOrNull() ?: cardinal[token]
        when {
            value != null -> { group += value; seen = true }
            token == "hundred" && group in 1..9 -> group *= 100
            token in listOf("mil", "thousand") -> { total += (if (group == 0) 1 else group) * 1000; group = 0; seen = true }
            token in listOf("y", "and") && seen && end + 1 < tokens.size &&
                (digitToken(tokens[end + 1]) != null || cardinal[tokens[end + 1]] != null) -> Unit
            else -> break
        }
        collected += token
        end++
    }
    if (!seen) return null to start
    // A sequence of individual digits represents a decimal integer, not a sum.
    if (collected.all { digitToken(it) != null }) {
        return collected.joinToString("") { digitToken(it)!! }.toIntOrNull() to end
    }
    return (total + group) to end
}
