package com.rtts.app.extract

/** Spoken radiotelephony designators, including historical names still useful in recordings. */
private val initialCallsigns = listOf(
    "American", "Delta", "United", "Southwest", "JetBlue", "Spirit Wings",
    "Frontier Flight", "Alaska", "Hawaiian", "FedEx", "UPS", "Air Canada",
    "Speedbird", "Lufthansa", "Air France", "KLM", "Iberia", "Europa",
    "Emirates", "Qatari", "Turkish", "Avianca", "LAN", "LATAM", "Copa",
    "Aeromexico", "Volaris", "Viva", "Viva Aerobus", "Wingo", "Satena", "Easy",
    "Sky", "JetSmart", "Aerolineas", "Gol", "Azul", "Tam", "Arajet",
    "Caribbean", "Cayman", "Bahamas", "Silver", "Envoy", "Endeavor",
    "Brickyard", "SkyWest", "Piedmont", "Blue Streak", "Air Shuttle", "Giant",
    "Connie", "Execjet", "Dragster", "Cactus", "Citrus", "Compass", "Jazz",
    "Rouge", "Westjet", "Sunwing", "Swiss", "Austrian", "Alitalia",
    "Air Portugal", "Aeroflot", "Singapore", "Cathay", "Korean Air",
    "Japan Air", "All Nippon", "Qantas",
)

/** Extensions are supplied by the caller; no global mutable catalog or platform storage. */
fun callsignCatalog(adicionales: List<String> = emptyList()): List<String> =
    (initialCallsigns + adicionales).map(String::trim).filter(String::isNotEmpty)
        .distinctBy { callsignKey(it) }

internal fun callsignKey(text: String): String = text.lowercase()
    .replace('á', 'a').replace('é', 'e').replace('í', 'i')
    .replace('ó', 'o').replace('ú', 'u').replace('ü', 'u').replace('ñ', 'n')
    .trim().replace(Regex("\\s+"), " ")
