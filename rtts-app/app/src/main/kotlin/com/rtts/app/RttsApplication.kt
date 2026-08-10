package com.rtts.app

import android.app.Application
import com.rtts.app.di.AppContainer

class RttsApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
