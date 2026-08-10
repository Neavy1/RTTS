package com.rtts.app.di

import android.content.Context
import com.rtts.app.asr.ModelManager
import com.rtts.app.auth.AuthRepository
import com.rtts.app.data.AppDatabase

/** Minimal manual DI container -- avoids pulling in Hilt/kapt for a single-module MVP. */
class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.getInstance(context)
    val modelManager: ModelManager = ModelManager(context)
    val authRepository: AuthRepository = AuthRepository(database)
}
