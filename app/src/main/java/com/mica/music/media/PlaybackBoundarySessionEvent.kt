package com.mica.music.media

import android.os.Bundle
import androidx.media3.session.SessionCommand

internal data class ConfirmedPlaybackBoundary(
    val oldSongId: String?,
    val newSongId: String?,
    val oldPositionMs: Long,
    val newPositionMs: Long,
)

internal object PlaybackBoundarySessionEvent {
    private const val Action = "com.mica.music.PLAYBACK_BOUNDARY"
    private const val OldSongId = "old_song_id"
    private const val NewSongId = "new_song_id"
    private const val OldPositionMs = "old_position_ms"
    private const val NewPositionMs = "new_position_ms"

    val command: SessionCommand
        get() = SessionCommand(Action, Bundle.EMPTY)

    fun encode(boundary: ConfirmedPlaybackBoundary): Bundle = Bundle().apply {
        boundary.oldSongId?.let { putString(OldSongId, it) }
        boundary.newSongId?.let { putString(NewSongId, it) }
        putLong(OldPositionMs, boundary.oldPositionMs)
        putLong(NewPositionMs, boundary.newPositionMs)
    }

    fun decode(command: SessionCommand, arguments: Bundle): ConfirmedPlaybackBoundary? {
        if (command.customAction != Action) return null
        return ConfirmedPlaybackBoundary(
            oldSongId = arguments.getString(OldSongId),
            newSongId = arguments.getString(NewSongId),
            oldPositionMs = arguments.getLong(OldPositionMs),
            newPositionMs = arguments.getLong(NewPositionMs),
        )
    }
}
