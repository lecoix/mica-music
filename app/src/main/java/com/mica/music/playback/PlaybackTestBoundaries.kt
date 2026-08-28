package com.mica.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import androidx.media3.session.SessionToken
import com.mica.music.data.PlaybackSession
import com.mica.music.data.PlaybackSessionStore
import com.mica.music.media.ConfirmedPlaybackBoundary
import com.mica.music.media.MicaMediaService
import com.mica.music.media.PlaybackBoundarySessionEvent
import com.mica.music.media.PlaybackStackSessionEvent

internal interface MediaControllerConnection {
    fun cancel()
}

internal interface MediaControllerConnector {
    fun connect(
        onConnected: (MediaController) -> Unit,
        onDisconnected: () -> Unit,
        onFailure: (Throwable) -> Unit,
        onPlaybackBoundary: (ConfirmedPlaybackBoundary) -> Unit,
        onPlaybackStackRebuilt: () -> Unit,
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
        onPlaybackBoundary: (ConfirmedPlaybackBoundary) -> Unit,
        onPlaybackStackRebuilt: () -> Unit,
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

                override fun onCustomCommand(
                    controller: MediaController,
                    command: SessionCommand,
                    args: android.os.Bundle,
                ) = Futures.immediateFuture(
                    if (PlaybackBoundarySessionEvent.decode(command, args)?.also(onPlaybackBoundary) != null) {
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    } else if (PlaybackStackSessionEvent.matches(command)) {
                        onPlaybackStackRebuilt()
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    } else {
                        SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                    },
                )
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
