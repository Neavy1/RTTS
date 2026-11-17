package com.rtts.app.i18n

import androidx.compose.runtime.staticCompositionLocalOf

// Independent of the transcription language and of platform resources.
enum class AppLanguage { EN, ES }

interface Strings {
    val onFrequency: String
    val lastForMe: String
    val trafficNearby: String
    val listen: String
    val stop: String
    val markMoment: String
    val forMeOnly: String
    val all: String
    val myCallsign: String
    val tapToSet: String
    val radioLanguage: String
    val runway: String
    val altitude: String
    val frequency: String
    val squawk: String
    val wind: String
    val sequence: String
    val callsign: String
    val paused: String
    val listening: String
    val finishing: String
    val forMe: String
    val radio: String
    val more: String
    val testSamples: String
    val chooseFile: String
    val listeningAid: String
    val nextCapture: String
    val english: String
    val spanish: String
    val automatic: String
    val close: String
    val callsignExample: String
    val save: String
    val cancel: String
    val noTransmissionsForMe: String
    val listeningEmpty: String
    val startCapture: String
    val smallerText: String
    val largerText: String
    val decreaseText: String
    val increaseText: String
    val defineCallsign: String
    val noInstructions: String
    val qnh: String
    val unavailable: String
    val trafficComingSoon: String
    val appName: String
    val radioToText: String
    val inputLevel: String
    val input: String
    val noCapture: String
    val radioAudio: String
    val fileSamples: String
    val language: String
    val utcTime: String
    val microphonePermission: String
    val finishingTranscript: String
    val pauseTransmission: String
    val replayTransmission: String
    val unmarkTransmission: String
    val markTransmission: String
    val captureFailed: String
    val notificationChannel: String
    val notificationTitle: String
    val notificationText: String
    val appLanguage: String
    val englishCode: String
    val spanishCode: String
    val automaticCode: String
    val clearedToLand: String
    val clearedForTakeoff: String

    fun correctedCallsign(value: String): String = "≈ $value"

    fun updatedAt(utc: String): String
    fun queueStatus(seconds: String, droppedChunks: Long): String
    fun sequenceNumber(number: String): String

    fun radioLanguageLabel(code: String): String = when (code) {
        "en" -> englishCode
        "es" -> spanishCode
        else -> automaticCode
    }

    // Translate extracted display values without changing the extractor's contract.
    fun sequenceLabel(value: String): String = when {
        value.startsWith("N.º ") -> sequenceNumber(value.removePrefix("N.º "))
        value == "Autorizado a aterrizar" -> clearedToLand
        value == "Autorizado a despegar" -> clearedForTakeoff
        else -> value
    }
}

object EnglishStrings : Strings {
    override val onFrequency = "On frequency"
    override val lastForMe = "Last for me"
    override val trafficNearby = "Traffic nearby"
    override val listen = "Listen"
    override val stop = "Stop"
    override val markMoment = "Mark this moment"
    override val forMeOnly = "For me only"
    override val all = "All"
    override val myCallsign = "My callsign"
    override val tapToSet = "Tap to set"
    override val radioLanguage = "Radio language"
    override val runway = "Runway"
    override val altitude = "Altitude"
    override val frequency = "Frequency"
    override val squawk = "Squawk"
    override val wind = "Wind"
    override val sequence = "Sequence"
    override val callsign = "Callsign"
    override val paused = "Paused"
    override val listening = "Listening"
    override val finishing = "Finishing…"
    override val forMe = "For me"
    override val radio = "Radio"
    override val more = "More"
    override val testSamples = "Test samples"
    override val chooseFile = "Choose file…"
    override val listeningAid = "Listening aid only. It does not replace the radio or ATC clearance."
    override val nextCapture = "The change applies to the next capture."
    override val english = "English"
    override val spanish = "Spanish"
    override val automatic = "Automatic"
    override val close = "Close"
    override val callsignExample = "Callsign (e.g. HK-2947)"
    override val save = "Save"
    override val cancel = "Cancel"
    override val noTransmissionsForMe = "No transmissions for my callsign yet."
    override val listeningEmpty = "Listening… transmissions will appear here."
    override val startCapture = "Start listening to see the transcript."
    override val smallerText = "Smaller text"
    override val largerText = "Larger text"
    override val decreaseText = "A−"
    override val increaseText = "A+"
    override val defineCallsign = "Set my callsign to see my instructions here"
    override val noInstructions = "No instructions for my callsign"
    override val qnh = "QNH"
    override val unavailable = "—"
    override val trafficComingSoon = "Coming in a future version with internet access"
    override val appName = "RTTS"
    override val radioToText = "Radio to text"
    override val inputLevel = "Input level"
    override val input = "Input"
    override val noCapture = "No capture"
    override val radioAudio = "Radio audio"
    override val fileSamples = "File / samples"
    override val language = "Language"
    override val utcTime = "UTC time"
    override val microphonePermission = "Microphone permission is required to listen to radio audio."
    override val finishingTranscript = "Finishing pending transcription…"
    override val pauseTransmission = "Pause transmission"
    override val replayTransmission = "Replay this transmission"
    override val unmarkTransmission = "Unmark this transmission"
    override val markTransmission = "Mark this transmission"
    override val captureFailed = "Capture could not be completed. Check microphone permission and the audio source."
    override val notificationChannel = "RTTS transcription"
    override val notificationTitle = "RTTS recording"
    override val notificationText = "Transcribing live communications"
    override val appLanguage = "App language"
    override val englishCode = "EN"
    override val spanishCode = "ES"
    override val automaticCode = "AUTO"
    override val clearedToLand = "Cleared to land"
    override val clearedForTakeoff = "Cleared for takeoff"
    override fun updatedAt(utc: String) = "Updated $utc UTC"
    override fun queueStatus(seconds: String, droppedChunks: Long) = "Queued audio: $seconds s · Dropped chunks: $droppedChunks"
    override fun sequenceNumber(number: String) = "No. $number"
}

object SpanishStrings : Strings {
    override val onFrequency = "Lo que se oye"
    override val lastForMe = "Lo último para mí"
    override val trafficNearby = "Tráfico cerca"
    override val listen = "Escuchar radio"
    override val stop = "Detener"
    override val markMoment = "Marcar este momento"
    override val forMeOnly = "Solo para mí"
    override val all = "Todo"
    override val myCallsign = "Mi indicativo"
    override val tapToSet = "Tocar para definir"
    override val radioLanguage = "Idioma de la radio"
    override val runway = "Pista"
    override val altitude = "Altitud"
    override val frequency = "Frecuencia"
    override val squawk = "Transp."
    override val wind = "Viento"
    override val sequence = "Turno"
    override val callsign = "Indicativo"
    override val paused = "En pausa"
    override val listening = "Escuchando"
    override val finishing = "Terminando…"
    override val forMe = "Para mí"
    override val radio = "Radio"
    override val more = "Más"
    override val testSamples = "Muestras de prueba"
    override val chooseFile = "Elegir archivo…"
    override val listeningAid = "Ayuda de escucha: no reemplaza la radio ni la autorización de la torre"
    override val nextCapture = "El cambio se aplicará a la siguiente captura."
    override val english = "Inglés"
    override val spanish = "Español"
    override val automatic = "Automático"
    override val close = "Cerrar"
    override val callsignExample = "Indicativo (por ejemplo HK-2947)"
    override val save = "Guardar"
    override val cancel = "Cancelar"
    override val noTransmissionsForMe = "Aún no hay transmisiones para mi indicativo."
    override val listeningEmpty = "Escuchando… el texto aparecerá aquí."
    override val startCapture = "Inicia una captura para ver la transcripción."
    override val smallerText = "Letra más pequeña"
    override val largerText = "Letra más grande"
    override val decreaseText = "A−"
    override val increaseText = "A+"
    override val defineCallsign = "Define mi indicativo para ver aquí mis instrucciones"
    override val noInstructions = "Sin instrucciones para mi indicativo"
    override val qnh = "QNH"
    override val unavailable = "—"
    override val trafficComingSoon = "Disponible con internet en una próxima versión"
    override val appName = "RTTS"
    override val radioToText = "Radio a texto"
    override val inputLevel = "Nivel de entrada"
    override val input = "Entrada"
    override val noCapture = "Sin captura"
    override val radioAudio = "Audio de radio"
    override val fileSamples = "Archivo / muestras"
    override val language = "Idioma"
    override val utcTime = "Hora UTC"
    override val microphonePermission = "Se necesita permiso de micrófono para escuchar el audio de la radio."
    override val finishingTranscript = "Finalizando transcripción pendiente…"
    override val pauseTransmission = "Pausar transmisión"
    override val replayTransmission = "Volver a oír esta transmisión"
    override val unmarkTransmission = "Quitar marca de esta transmisión"
    override val markTransmission = "Marcar esta transmisión"
    override val captureFailed = "No se pudo completar la captura. Revisa el permiso de micrófono y la fuente de audio."
    override val notificationChannel = "Transcripción RTTS"
    override val notificationTitle = "RTTS grabando"
    override val notificationText = "Transcribiendo comunicación en vivo"
    override val appLanguage = "Idioma de la app"
    override val englishCode = "EN"
    override val spanishCode = "ES"
    override val automaticCode = "AUTO"
    override val clearedToLand = "Autorizado a aterrizar"
    override val clearedForTakeoff = "Autorizado a despegar"
    override fun updatedAt(utc: String) = "Actualizado $utc UTC"
    override fun queueStatus(seconds: String, droppedChunks: Long) = "Audio en cola: $seconds s · Trozos descartados: $droppedChunks"
    override fun sequenceNumber(number: String) = "N.º $number"
}

fun stringsFor(language: AppLanguage): Strings = when (language) {
    AppLanguage.EN -> EnglishStrings
    AppLanguage.ES -> SpanishStrings
}

val LocalStrings = staticCompositionLocalOf<Strings> { EnglishStrings }
