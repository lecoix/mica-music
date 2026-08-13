package com.mica.music.media.usbprototype

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.mica.music.media.DirectDsdPrototypeSessionCommand
import com.mica.music.media.MicaMediaService
import java.util.concurrent.Executor

/** Debug-only ADB control plane for rebuilding the existing Media3 stack with Direct DSD armed. */
class UsbDirectDsdPrototypeCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != action(context)) return
        val enabled = intent.getBooleanExtra(DirectDsdPrototypeSessionCommand.EXTRA_ENABLED, false)
        val pending = goAsync()
        val appContext = context.applicationContext
        val controllerThread = MediaControllerApplicationThread(Looper.getMainLooper())
        controllerThread.execute {
            val token = SessionToken(
                appContext,
                ComponentName(appContext, MicaMediaService::class.java),
            )
            val controllerFuture = runCatching {
                MediaController.Builder(appContext, token)
                    .setApplicationLooper(controllerThread.looper)
                    .buildAsync()
            }.getOrElse { error ->
                Log.e(TAG, "Direct DSD controller build failed enabled=$enabled", error)
                pending.finish()
                return@execute
            }
            controllerFuture.addListener(
                {
                    val controller = runCatching { controllerFuture.get() }.getOrElse { error ->
                        Log.e(TAG, "Direct DSD controller connect failed enabled=$enabled", error)
                        pending.finish()
                        return@addListener
                    }
                    val commandFuture = runCatching {
                        controller.sendCustomCommand(
                            DirectDsdPrototypeSessionCommand.command,
                            Bundle().apply {
                                putBoolean(DirectDsdPrototypeSessionCommand.EXTRA_ENABLED, enabled)
                            },
                        )
                    }.getOrElse { error ->
                        Log.e(TAG, "Direct DSD command send failed enabled=$enabled", error)
                        controller.release()
                        pending.finish()
                        return@addListener
                    }
                    commandFuture.addListener(
                        {
                            try {
                                val result = commandFuture.get()
                                Log.i(
                                    TAG,
                                    "directDsd=control enabled=$enabled sessionResult=${result.resultCode}",
                                )
                            } catch (error: Throwable) {
                                Log.e(TAG, "Direct DSD command result failed enabled=$enabled", error)
                            } finally {
                                controller.release()
                                pending.finish()
                            }
                        },
                        controllerThread,
                    )
                },
                controllerThread,
            )
        }
    }

    companion object {
        private const val TAG = "MicaDirectDsd"
        fun action(context: Context): String = "${context.packageName}.debug.DIRECT_DSD_PROTOTYPE"
    }
}

/** Runs every MediaController interaction on the controller's declared application looper. */
internal class MediaControllerApplicationThread(
    val looper: Looper,
) : Executor {
    private val handler = Handler(looper)

    override fun execute(command: Runnable) {
        if (Looper.myLooper() == looper) {
            command.run()
        } else {
            check(handler.post(command)) { "MediaController application looper rejected work" }
        }
    }
}
