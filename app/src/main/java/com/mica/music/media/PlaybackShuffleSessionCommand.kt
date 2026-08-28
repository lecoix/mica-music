package com.mica.music.media

import android.os.Bundle
import androidx.media3.session.SessionCommand

internal data class PlaybackShuffleRequest(
    val enabled: Boolean,
    val seed: Long?,
)

internal object PlaybackShuffleSessionCommand {
    const val ACTION = "com.mica.music.action.SET_APP_SHUFFLE"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SEED = "seed"
    private const val KEY_HAS_SEED = "has_seed"

    val command: SessionCommand
        get() = SessionCommand(ACTION, Bundle.EMPTY)

    fun encode(enabled: Boolean, seed: Long?): Bundle = Bundle().apply {
        putBoolean(KEY_ENABLED, enabled)
        putBoolean(KEY_HAS_SEED, seed != null)
        if (seed != null) putLong(KEY_SEED, seed)
    }

    fun decode(command: SessionCommand, args: Bundle): PlaybackShuffleRequest? {
        if (command.customAction != ACTION) return null
        val enabled = args.getBoolean(KEY_ENABLED, false)
        val seed = args.getLong(KEY_SEED).takeIf { args.getBoolean(KEY_HAS_SEED, false) }
        if (enabled && seed == null) return null
        return PlaybackShuffleRequest(enabled = enabled, seed = seed)
    }
}
