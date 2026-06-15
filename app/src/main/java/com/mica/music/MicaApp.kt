package com.mica.music

import android.app.Application
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.data.PlayerController
import com.mica.music.data.scanner.ScanCacheManager
import com.mica.music.media.AlacFfmpegHelper
import com.mica.music.util.BluetoothAudioDiagnostics
import com.mica.music.util.DiagnosticLog
import com.mica.music.util.AudioEnvironmentDiagnostics

class MicaApp : Application() {
    /**
     * Process-lifetime playback facade. Activity/ViewModel destruction must never tear down
     * the service-backed playback session.
     */
    val playerController: PlayerController by lazy(LazyThreadSafetyMode.NONE) {
        PlayerController(this)
    }

    override fun onCreate() {
        super.onCreate()
        ScanCacheManager.runStartupCacheCleanup(this)
        DiagnosticLog.install(this)
        BluetoothAudioDiagnostics.install(this)
        AudioEnvironmentDiagnostics.install(this)
        MicaImageLoaders.init(this)
        AlacFfmpegHelper.init(this)
    }
}
