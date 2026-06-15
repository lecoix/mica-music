package com.mica.music.data

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.mica.music.media.MicaMediaService

internal interface MediaControllerConnection {
    fun cancel()
}

internal interface MediaControllerConnector {
    fun connect(
        onConnected: (MediaController) -> Unit,
        onDisconnected: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ): MediaControllerConnection
}

internal interface PlaybackSessionStorage {
    fun save(session: PlaybackSession?, sync: Boolean = false)
    fun load(): PlaybackSession?
    fun clear()
}

internal class AndroidMediaControllerConnector(
    private val context: Context,
) : MediaControllerConnector {
    override fun connect(
        onConnected: (MediaController) -> Unit,
        onDisconnected: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ): MediaControllerConnection {
        val token = SessionToken(
            context,
            ComponentName(context, MicaMediaService::class.java),
        )
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    onDisconnected()
                }
            })
            .buildAsync()
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess(onConnected)
                    .onFailure(onFailure)
            },
            ContextCompat.getMainExecutor(context),
        )
        return object : MediaControllerConnection {
            override fun cancel() {
                MediaController.releaseFuture(future)
            }
        }
    }
}

internal class PreferencesPlaybackSessionStorage(
    private val context: Context,
) : PlaybackSessionStorage {
    override fun save(session: PlaybackSession?, sync: Boolean) =
        PlaybackSessionStore.save(context, session, sync)

    override fun load(): PlaybackSession? = PlaybackSessionStore.load(context)

    override fun clear() = PlaybackSessionStore.clear(context)
}
