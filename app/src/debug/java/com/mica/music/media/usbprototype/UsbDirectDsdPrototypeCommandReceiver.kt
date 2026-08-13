package com.mica.music.media.usbprototype

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.mica.music.media.DirectDsdPrototypeSessionCommand
import com.mica.music.media.MicaMediaService
import java.util.concurrent.TimeUnit

/** Debug-only ADB control plane for rebuilding the existing Media3 stack with Direct DSD armed. */
class UsbDirectDsdPrototypeCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != action(context)) return
        val enabled = intent.getBooleanExtra(DirectDsdPrototypeSessionCommand.EXTRA_ENABLED, false)
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread(
            {
                var controller: MediaController? = null
                try {
                    val token = SessionToken(
                        appContext,
                        ComponentName(appContext, MicaMediaService::class.java),
                    )
                    controller = MediaController.Builder(appContext, token)
                        .buildAsync()
                        .get(10, TimeUnit.SECONDS)
                    val result = controller.sendCustomCommand(
                        DirectDsdPrototypeSessionCommand.command,
                        Bundle().apply {
                            putBoolean(DirectDsdPrototypeSessionCommand.EXTRA_ENABLED, enabled)
                        },
                    ).get(10, TimeUnit.SECONDS)
                    Log.i(TAG, "directDsd=control enabled=$enabled sessionResult=${result.resultCode}")
                } catch (error: Throwable) {
                    Log.e(TAG, "Direct DSD control failed enabled=$enabled", error)
                } finally {
                    controller?.release()
                    pending.finish()
                }
            },
            "MicaDirectDsdControl",
        ).start()
    }

    companion object {
        private const val TAG = "MicaDirectDsd"
        fun action(context: Context): String = "${context.packageName}.debug.DIRECT_DSD_PROTOTYPE"
    }
}
