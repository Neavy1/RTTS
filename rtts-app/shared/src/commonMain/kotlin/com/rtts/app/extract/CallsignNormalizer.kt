package com.rtts.app.extract

private val phonetic = listOf(
    "alfa alpha", "bravo", "charlie", "delta", "echo eco", "foxtrot", "golf",
    "hotel", "india", "juliett juliet", "kilo", "lima", "mike", "november noviembre",
    "oscar", "papa", "quebec", "romeo", "sierra", "tango", "uniform uniforme",
    "victor", "whiskey whisky", "xray", "yankee yanqui", "zulu",
).flatMapIndexed { index, names -> names.split(' ').map { it to ('A' + index).toString() } }.toMap()

fun normalizeCallsign(texto: String): String = words(texto)
    .filter { it.any(Char::isLetterOrDigit) }
    .joinToString("") { phonetic[it] ?: digitToken(it) ?: it.uppercase() }
    .replace("XRAY", "X")

fun isAddressedTo(transmision: String, miIndicativo: String): Boolean {
    val own = normalizeCallsign(miIndicativo)
    if (own.length < 3) return false
    val tokens = words(transmision)
    val safeBefore = setOf(
        ",", ".", ":", ";", "!", "?", "para", "to", "indicativo", "callsign",
        "torre", "tower", "roger", "recibido", "adelante", "go", "ahead",
    )
    for (start in tokens.indices) {
        if (tokens[start].none(Char::isLetterOrDigit)) continue
        var candidate = ""
        for (end in start until tokens.size) {
            val token = tokens[end]
            if (token.none(Char::isLetterOrDigit)) break
            candidate += normalizeCallsign(token)
            if (candidate.length > own.length) break
            val full = candidate == own
            val short = candidate.length in 3..4 && own.length > candidate.length && own.endsWith(candidate)
            if (!full && !short) continue
            val previous = tokens.getOrNull(start - 1)
            val next = tokens.getOrNull(end + 1)
            // Never find a suffix embedded in a different or longer callsign.
            val previousIsPart = previous != null && (digitToken(previous) != null || phonetic[previous] != null)
            val nextIsPart = next != null && (digitToken(next) != null || phonetic[next] != null)
            if (!previousIsPart && !nextIsPart && (full || previous == null || previous in safeBefore)) return true
        }
    }
    return false
}
