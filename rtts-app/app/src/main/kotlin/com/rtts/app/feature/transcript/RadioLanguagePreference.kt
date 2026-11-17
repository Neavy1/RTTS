package com.rtts.app.feature.transcript

import android.content.SharedPreferences

internal const val CABIN_PREFERENCES = "rtts_cabin"
internal const val RADIO_LANGUAGE_KEY = "radio_language"

internal fun SharedPreferences.radioLanguage(): String =
    getString(RADIO_LANGUAGE_KEY, "en")?.takeIf { it in listOf("en", "es", "") } ?: "en"
