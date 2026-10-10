package com.rtts.app.asr

private val numberWords = setOf(
    "zero", "oh", "one", "two", "three", "tree", "four", "fower", "five", "fife", "six", "seven", "eight", "nine", "niner",
    "cero", "uno", "una", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "decimal", "punto",
)

/** Heuristic for repetition loops, not an estimate of recognition confidence. */
fun looksLikeHallucination(text: String): Boolean {
    val words = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.toList()
    if (words.size < 6) return false
    // Repeated digits are normal in callsigns and frequencies. Low diversity alone
    // is not sufficient evidence to discard a predominantly numeric readback.
    if (words.count { it in numberWords || it.all(Char::isDigit) } * 2 >= words.size) return false
    // Keep the existing threshold, but normalize punctuation before counting.
    return words.toSet().size.toDouble() / words.size < 0.45
}

fun rejectRepetition(result: TranscriptResult): TranscriptResult =
    if (looksLikeHallucination(result.text)) result.copy(text = "[INAUDIBLE]") else result

private val noSpeechMarkers = setOf("[no audio]", "[silence]", "(silence)", "[music]")

/** Rejects output that contradicts the English-only ATC session contract. */
fun validateEnglishAtcResult(result: TranscriptResult): TranscriptResult {
    val text = result.text.trim()
    val normalized = text.lowercase()
    if (text.isBlank() || normalized in noSpeechMarkers || normalized.startsWith("[blank")) {
        return result.copy(text = "", lang = "en")
    }
    val hasForeignScript = text.any { char ->
        val code = char.code
        code in 0x0600..0x06FF || // Arabic
            code in 0x3040..0x30FF || // Hiragana and Katakana
            code in 0x4E00..0x9FFF // CJK unified ideographs
    }
    val reportsForeignSpeech = "speaking in foreign language" in normalized
    if (hasForeignScript || reportsForeignSpeech || looksLikeHallucination(text)) {
        return result.copy(text = "[INAUDIBLE]", lang = "en")
    }
    return result.copy(text = text, lang = "en")
}
