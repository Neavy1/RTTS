package com.rtts.app.extract

data class SpokenCallsign(val palabra: String, val numero: String, val sufijo: String?)
data class ResolvedCallsign(val texto: String, val confianza: Confianza, val original: String)
enum class Confianza { EXACTA, PARECIDA, POR_SESION, DESCONOCIDA }

private val instructionWords = setOf(
    "heading", "maintain", "maintained", "descending", "descend", "climb", "altitude",
    "speed", "knots", "frequency", "contact", "monitor", "squawk", "runway", "flight",
    "level", "fl", "qnh", "altimeter", "wind", "at", "to", "and", "on", "turn",
    "left", "right", "approach", "tower", "center", "centre", "departure", "roger",
    "rumbo", "mantenga", "descienda", "ascienda", "altitud", "velocidad", "frecuencia",
    "pista", "nivel", "viento", "a", "de", "en", "la", "el", "para", "heavy", "super",
)
private val registrationLetters = setOf(
    "alfa", "alpha", "bravo", "charlie", "delta", "echo", "eco", "foxtrot", "golf",
    "hotel", "india", "juliett", "juliet", "kilo", "lima", "mike", "november", "noviembre",
    "oscar", "papa", "quebec", "romeo", "sierra", "tango", "uniform", "uniforme",
    "victor", "whiskey", "whisky", "xray", "yankee", "yanqui", "zulu",
)
private val multiwordDesignators = callsignCatalog().map(::callsignKey).filter { ' ' in it }.toSet()

/** Finds digit runs only when preceded by a designator, never standalone ATC values. */
fun findCallsigns(texto: String): List<SpokenCallsign> {
    val matches = Regex("[a-z0-9]+|[.,;:!?]").findAll(callsignKey(texto)).toList()
    // Preserve the heard spelling with the same whitespace folding used for token offsets.
    val source = texto.trim().replace(Regex("\\s+"), " ")
    val tokens = matches.map { it.value }
    val result = mutableListOf<SpokenCallsign>()
    var i = 0
    while (i < tokens.size) {
        if (digitToken(tokens[i]) == null) { i++; continue }
        val (number, end) = digitRun(tokens, i)
        var before = i - 1
        if (tokens.getOrNull(before) == ",") before--
        val lastWord = tokens.getOrNull(before)
        // A US N-prefix or a pair of phonetic letters identifies a registration.
        val registration = lastWord in setOf("november", "noviembre") ||
            (lastWord in registrationLetters && tokens.getOrNull(before - 1) in registrationLetters)
        // Reject decimals and thousands groups, and values explicitly followed by units.
        val thousands = tokens.getOrNull(end) == "," && number.length <= 2 &&
            tokens.getOrNull(end + 1)?.let { it.length == 3 && it.all(Char::isDigit) } == true &&
            matches[end].range.first == matches[end - 1].range.last + 1 &&
            matches[end + 1].range.first == matches[end].range.last + 1
        val decimal = tokens.getOrNull(i - 1) == "." ||
            (tokens.getOrNull(end) == "." && digitToken(tokens.getOrElse(end + 1) { "" }) != null)
        if (number.length !in (if (registration) 1..5 else 2..4) || decimal || thousands || lastWord == null ||
            tokens.getOrNull(end) in listOf("knots", "feet", "ft", "pies", "hundred", "thousand", "mil")) {
            i = end; continue
        }
        var start = before
        var restEnd = end
        if (registration) {
            while (start > 0 && tokens[start - 1] in registrationLetters) start--
            while (tokens.getOrNull(restEnd) in registrationLetters) restEnd++
        } else {
            val pair = tokens.getOrNull(before - 1)?.let { "$it $lastWord" }
            if (pair == "direct to" || pair == "drag the" || pair in multiwordDesignators) start--
            if ((start == before && lastWord in instructionWords) ||
                tokens[start].none(Char::isLetter) || digitToken(tokens[start]) != null) {
                i = end; continue
            }
        }
        val word = source.substring(matches[start].range.first, matches[before].range.last + 1)
        val rest = if (registration) normalizeCallsign(tokens.subList(i, restEnd).joinToString(" ")) else number
        val suffixAt = if (tokens.getOrNull(restEnd) == ",") restEnd + 1 else restEnd
        val suffix = tokens.getOrNull(suffixAt)?.takeIf { it == "heavy" || it == "super" }
        result += SpokenCallsign(word, rest, suffix)
        i = restEnd
    }
    return result
}

private fun phoneticKey(text: String): String = callsignKey(text)
    .map { when (it) { 'c', 'k', 'q', 'g' -> 'k'; 't', 'd' -> 'd'; 'z', 's' -> 's'; else -> it } }
    .filter { it in 'a'..'z' && it !in "aeiou" }.joinToString("")

private fun editDistance(a: String, b: String): Int {
    var row = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val next = IntArray(b.length + 1)
        next[0] = i + 1
        for (j in b.indices) next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1,
            row[j] + if (a[i] == b[j]) 0 else 1)
        row = next
    }
    return row[b.length]
}

private fun recognized(word: String, catalog: List<String>): Pair<String, Confianza>? {
    catalog.firstOrNull { callsignKey(it) == callsignKey(word) }?.let { return it to Confianza.EXACTA }
    val key = phoneticKey(word)
    if (key.length < 5) return null
    // At most one consonant edit, <=20% normalized distance, and a unique candidate.
    // Preserve word boundaries: e.g. "drag the" must not become "Turkish".
    val wordCount = callsignKey(word).split(' ').size
    val candidates = catalog.distinctBy(::callsignKey).filter {
        val other = phoneticKey(it)
        callsignKey(it).split(' ').size == wordCount && other.length >= 5 && editDistance(key, other).let { distance ->
            distance <= 1 && distance.toDouble() / maxOf(key.length, other.length) <= 0.20
        }
    }
    return candidates.singleOrNull()?.let { it to Confianza.PARECIDA }
}

private fun SpokenCallsign.render(word: String = palabra): String =
    "$word $numero" + (sufijo?.let { " $it" } ?: "")

fun resolveCallsign(hablado: SpokenCallsign, catalogo: List<String>,
    vistosEnSesion: Map<String, String>): ResolvedCallsign {
    val match = recognized(hablado.palabra, catalogo)
        ?: vistosEnSesion[hablado.numero]?.let { it to Confianza.POR_SESION }
    return ResolvedCallsign(hablado.render(match?.first ?: hablado.palabra),
        match?.second ?: Confianza.DESCONOCIDA, hablado.render())
}

/** Only direct recognition supplies evidence. Conflicting designators leave a number unknown. */
fun sessionCallsigns(transmisiones: List<String>, catalogo: List<String>): Map<String, String> {
    val evidence = mutableMapOf<String, MutableSet<String>>()
    transmisiones.flatMap(::findCallsigns).forEach { spoken ->
        recognized(spoken.palabra, catalogo)?.let { (designator, _) ->
            evidence.getOrPut(spoken.numero) { mutableSetOf() }.add(designator)
        }
    }
    // A one-digit variation with a PARECIDA word already contributes its own number above.
    // Never rewrite numbers or spread an identity to a nearby, unrecognized number.
    return evidence.mapNotNull { (number, names) -> names.singleOrNull()?.let { number to it } }.toMap()
}
