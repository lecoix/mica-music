package com.mica.music.media

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mica.music.data.AppliedReplayGain
import com.mica.music.data.ReplayGainMode
import com.mica.music.data.ReplayGainPolicy
import com.mica.music.data.ReplayGainSource
import com.mica.music.data.ReplayGainTags
import com.mica.music.data.preferences.MicaSettingsStore
import com.mica.music.data.preferences.ReplayGainPreferences
import com.mica.music.util.DiagnosticLog

internal class ReplayGainStateOwner(
    context: Context,
    private val player: MicaCompositePlayer,
) {
    private val appContext = context.applicationContext
    private val preferences = MicaSettingsStore.prefs(appContext)
    private var started = false

    var current: AppliedReplayGain = AppliedReplayGain(
        mode = ReplayGainMode.OFF,
        source = ReplayGainSource.OFF,
        linearFactor = 1f,
    )
        private set

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            apply(mediaItem)
        }
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ReplayGainPreferences.KEY_MODE) {
            apply(player.currentMediaItem)
        }
    }

    fun start() {
        if (started) return
        started = true
        player.addListener(playerListener)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        apply(player.currentMediaItem)
    }

    fun release() {
        if (!started) return
        started = false
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        player.removeListener(playerListener)
    }

    private fun apply(mediaItem: MediaItem?) {
        val tags = mediaItem?.let(SongMediaItemCodec::decode)?.replayGain
        apply(tags, ReplayGainPreferences.mode(appContext))
    }

    internal fun apply(tags: ReplayGainTags?, mode: ReplayGainMode): AppliedReplayGain {
        val next = ReplayGainPolicy.resolve(tags ?: ReplayGainTags(), mode)
        player.setReplayGainVolume(next.linearFactor)
        current = next
        DiagnosticLog.event(
            "ReplayGain",
            "mode=${next.mode} source=${next.source} factor=${next.linearFactor} " +
                "modifiesSignal=${next.modifiesSignal}",
        )
        return next
    }
}
