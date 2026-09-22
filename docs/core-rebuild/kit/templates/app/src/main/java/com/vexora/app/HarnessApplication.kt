package com.vexora.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class HarnessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG && BuildConfig.ENABLE_DEBUG_LOG) Timber.plant(Timber.DebugTree())
    }
}
