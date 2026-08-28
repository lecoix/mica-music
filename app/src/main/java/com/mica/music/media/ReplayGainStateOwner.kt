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
        if (started) {
            started = false
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            player.removeListener(playerListener)
        }
        MicaEqualizerManager.setReplayGain(enabled = false, factor = 1f)
        player.setReplayGainVolume(1f)
    }

    private fun apply(mediaItem: MediaItem?) {
        val song = mediaItem?.let(SongMediaItemCodec::decode)
        val mode = ReplayGainPreferences.mode(appContext)
        val loudness = song?.loudnessAnalysis?.takeIf { it.matches(song) }
        val next = ReplayGainPolicy.resolve(song?.replayGain ?: ReplayGainTags(), mode, loudness)
        applyResolved(next)
    }

    internal fun apply(tags: ReplayGainTags?, mode: ReplayGainMode): AppliedReplayGain {
        val next = ReplayGainPolicy.resolve(tags ?: ReplayGainTags(), mode)
        applyResolved(next)
        return next
    }

    private fun applyResolved(next: AppliedReplayGain) {
        val dspEnabled = next.mode != ReplayGainMode.OFF
        MicaEqualizerManager.setReplayGain(dspEnabled, next.linearFactor)
        player.setReplayGainVolume(1f)
        current = next
        DiagnosticLog.event(
            "ReplayGain",
            "mode=${next.mode} source=${next.source} factor=${next.linearFactor} " +
                "dsp=$dspEnabled playerFactor=1.0 modifiesSignal=${next.modifiesSignal}",
        )
    }
}
