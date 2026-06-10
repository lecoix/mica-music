package com.mica.music

import android.app.Application
import android.util.Log
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.media.AlacFfmpegHelper

/**
 * 方便在 Logcat 里搜索固定标签定位崩溃（与系统 `AndroidRuntime` 可同时打出来）。
 *
 * 若仍只有 SurfaceFlinger / InputDispatcher 而没有本标签，多半是 native 崩溃或进程被系统直接杀死。
 */
class MicaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MicaImageLoaders.init(this)
        AlacFfmpegHelper.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG_CRASH, "Uncaught in thread ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG_CRASH = "MICA_JAVA_CRASH"
    }
}
