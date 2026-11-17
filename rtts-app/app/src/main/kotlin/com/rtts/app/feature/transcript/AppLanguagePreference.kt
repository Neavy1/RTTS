package com.rtts.app.feature.transcript

import android.content.SharedPreferences
import com.rtts.app.i18n.AppLanguage
import java.util.Locale

internal const val APP_LANGUAGE_KEY = "app_language"

internal fun SharedPreferences.appLanguage(): AppLanguage = when (getString(APP_LANGUAGE_KEY, null)) {
    "EN" -> AppLanguage.EN
    "ES" -> AppLanguage.ES
    else -> if (Locale.getDefault().language == "es") AppLanguage.ES else AppLanguage.EN
}
