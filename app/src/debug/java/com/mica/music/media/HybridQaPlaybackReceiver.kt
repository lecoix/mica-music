package com.mica.music.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.preferences.UsbHybridOutputMode
import com.mica.music.data.preferences.UsbHybridPreferences
import kotlinx.coroutines.runBlocking

/** Debug-only physical-validation control seam. Never packaged in Perf/Release. */
class HybridQaPlaybackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val action = intent.action.orEmpty()
        if (action == "${appContext.packageName}.debug.HYBRID_QA_SET_MODE_PREFERENCE") {
            runCatching {
                val mode = UsbHybridOutputMode.valueOf(checkNotNull(intent.getStringExtra("mode")))
                UsbHybridPreferences.setOutputMode(appContext, mode)
                Log.i(TAG, "complete action=$action mode=$mode")
            }.onFailure { error ->
                Log.e(TAG, "control failed action=$action", error)
            }
            pending.finish()
            return
        }
        val future = MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, MicaMediaService::class.java)),
        ).buildAsync()
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrElse { error ->
                    Log.e(TAG, "controller-connect failed action=$action", error)
                    pending.finish()
                    return@addListener
                }
                if (action == "${appContext.packageName}.debug.HYBRID_QA_LOAD_LIBRARY_INDEX") {
                    val index = intent.getIntExtra("mediaIndex", 0)
                    Thread {
                        val songs = runCatching {
                            runBlocking { LibraryRepository(appContext).loadCached()?.songs.orEmpty() }
                        }.getOrElse { error ->
                            Log.e(TAG, "library-load failed action=$action", error)
                            controller.release()
                            pending.finish()
                            return@Thread
                        }
                        ContextCompat.getMainExecutor(appContext).execute {
                            try {
                                require(index in songs.indices) { "mediaIndex=$index libraryCount=${songs.size}" }
                                val items = songs.map(SongMediaItemCodec::encode)
                                controller.setMediaItems(items, index, 0L)
                                controller.prepare()
                                val handler = Handler(Looper.getMainLooper())
                                handler.postDelayed({
                                    controller.play()
                                    when (intent.getStringExtra("warmupIntentSequence")) {
                                        "PLAY_PAUSE" -> handler.postDelayed({ controller.pause() }, 250L)
                                        "PAUSE_PLAY" -> {
                                            handler.postDelayed({ controller.pause() }, 150L)
                                            handler.postDelayed({ controller.play() }, 300L)
                                        }
                                    }
                                    intent.getLongExtra("warmupSeekMs", -1L)
                                        .takeIf { it >= 0L }
                                        ?.let { seekMs -> handler.postDelayed({ controller.seekTo(seekMs) }, 200L) }
                                    Log.i(
                                        TAG,
                                        "complete action=$action index=$index libraryCount=${songs.size} " +
                                            "mediaItemCount=${controller.mediaItemCount} target=${songs[index].id}",
                                    )
                                    handler.postDelayed({
                                        controller.release()
                                        pending.finish()
                                    }, 1_500L)
                                }, 2_000L)
                            } catch (error: Throwable) {
                                Log.e(TAG, "control failed action=$action", error)
                                controller.release()
                                pending.finish()
                            }
                        }
                    }.start()
                    return@addListener
                }
                try {
                    when (action) {
                        "${appContext.packageName}.debug.HYBRID_QA_REPEAT_ONE" ->
                            controller.repeatMode = Player.REPEAT_MODE_ONE
                        "${appContext.packageName}.debug.HYBRID_QA_REPEAT_OFF" ->
                            controller.repeatMode = Player.REPEAT_MODE_OFF
                        "${appContext.packageName}.debug.HYBRID_QA_PLAY" -> controller.play()
                        "${appContext.packageName}.debug.HYBRID_QA_PAUSE" -> controller.pause()
                        "${appContext.packageName}.debug.HYBRID_QA_SEEK_MS" ->
                            controller.seekTo(intent.getLongExtra("positionMs", 0L).coerceAtLeast(0L))
                        "${appContext.packageName}.debug.HYBRID_QA_NEXT" -> controller.seekToNextMediaItem()
                        "${appContext.packageName}.debug.HYBRID_QA_SET_MODE" -> {
                            val mode = UsbHybridOutputMode.valueOf(checkNotNull(intent.getStringExtra("mode")))
                            UsbHybridPreferences.setOutputMode(appContext, mode)
                        }
                        "${appContext.packageName}.debug.HYBRID_QA_SELECT_INDEX" -> {
                            val index = intent.getIntExtra("mediaIndex", -1)
                            require(index in 0 until controller.mediaItemCount) {
                                "mediaIndex=$index count=${controller.mediaItemCount}"
                            }
                            controller.seekToDefaultPosition(index)
                            controller.play()
                        }
                        else -> error("unknown action=$action")
                    }
                    Log.i(
                        TAG,
                        "complete action=$action index=${controller.currentMediaItemIndex} " +
                            "positionMs=${controller.currentPosition} repeat=${controller.repeatMode} " +
                            "playWhenReady=${controller.playWhenReady}",
                    )
                } catch (error: Throwable) {
                    Log.e(TAG, "control failed action=$action", error)
                } finally {
                    controller.release()
                    pending.finish()
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private companion object {
        const val TAG = "MicaHybridQa"
    }
}
