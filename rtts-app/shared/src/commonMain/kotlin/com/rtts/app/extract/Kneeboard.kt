package com.rtts.app.extract

/** Android-independent input. The caller supplies only the current session. */
data class KneeboardTransmission(val text: String, val timestampEpochMs: Long)

data class Kneeboard(
    val pista: String? = null,
    val altitud: String? = null,
    val qnh: String? = null,
    val turno: String? = null,
    val transpondedor: String? = null,
    val viento: String? = null,
    val updatedAtEpochMs: Long? = null,
)

/** Replay chronologically; unknown or ambiguous values never erase known fields. */
fun buildKneeboard(transmissions: List<KneeboardTransmission>, callsign: String): Kneeboard {
    val own = normalizeCallsign(callsign)
    if (own.isEmpty()) return Kneeboard()
    return transmissions.sortedBy { it.timestampEpochMs }.fold(Kneeboard()) { board, tx ->
        if (!isAddressedTo(tx.text, own)) return@fold board
        val data = extractInstruction(tx.text)
        if (listOf(data.pista, data.altitud, data.qnh, data.turno, data.transpondedor, data.viento)
                .all { it == null }) return@fold board
        board.copy(
            pista = data.pista ?: board.pista,
            altitud = data.altitud ?: board.altitud,
            qnh = data.qnh ?: board.qnh,
            turno = data.turno ?: board.turno,
            transpondedor = data.transpondedor ?: board.transpondedor,
            viento = data.viento ?: board.viento,
            updatedAtEpochMs = tx.timestampEpochMs,
        )
    }
}
