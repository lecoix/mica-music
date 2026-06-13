package com.mica.music

import android.app.Application
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.media.AlacFfmpegHelper
import com.mica.music.util.DiagnosticLog

class MicaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.install(this)
        MicaImageLoaders.init(this)
        AlacFfmpegHelper.init(this)
    }
}
