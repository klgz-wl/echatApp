package com.vexora.app

import android.app.Application
import com.vexora.core.config.CoreRuntimeConfig
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class VexoraApp : Application(), coil.ImageLoaderFactory {
    override fun newImageLoader() = coil.ImageLoader.Builder(this)
        .components {
            if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory())
            else { add(com.vexora.app.media.LegacyWebpDecoder.Factory()); add(coil.decode.GifDecoder.Factory()) }
            add(coil.decode.VideoFrameDecoder.Factory())
        }.build()
    @Inject lateinit var config: CoreRuntimeConfig
    override fun onCreate() {
        super.onCreate()
        if (config.diagnostics.enableDebugLogging) Timber.plant(Timber.DebugTree())
    }
}
