package com.mica.music.media

import android.os.Bundle
import androidx.media3.session.SessionCommand

internal object PlaybackStackSessionEvent {
    private const val Action = "com.mica.music.PLAYBACK_STACK_REBUILT"

    val command: SessionCommand
        get() = SessionCommand(Action, Bundle.EMPTY)

    fun matches(command: SessionCommand): Boolean = command.customAction == Action
}
