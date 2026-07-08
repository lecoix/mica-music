package com.mica.music.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mica.music.data.preferences.PlaybackUiPreferences

@UnstableApi
internal data class ExoPlaybackStack(
    val exoPlayer: ExoPlayer,
    val compositePlayer: MicaCompositePlayer,
    val applyAudioFocusSetting: () -> Unit,
)

@UnstableApi
internal object ExoPlaybackStackFactory {

    fun build(
        context: Context,
        outputPath: AudioOutputPathConfig = AudioOutputPathConfig.PRODUCTION,
    ): ExoPlaybackStack {
        outputPath.requireSupportedForPlayback()
        outputPath.logForDiagnostics()
        val dataSourceFactory = DefaultDataSource.Factory(context)
        val renderersFactory = MicaRenderersFactory(context, outputPath)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            dataSourceFactory,
            MicaExtractorsFactory.create(),
        )
        val playbackAudioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val exoPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                playbackAudioAttributes,
                PlaybackUiPreferences.audioFocusEnabled(context),
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val applyAudioFocusSetting = {
            exoPlayer.setAudioAttributes(
                playbackAudioAttributes,
                PlaybackUiPreferences.audioFocusEnabled(context),
            )
        }
        val compositePlayer = MicaCompositePlayer(
            exoPlayer = exoPlayer,
            beforePlaybackStart = applyAudioFocusSetting,
        )
        return ExoPlaybackStack(
            exoPlayer = exoPlayer,
            compositePlayer = compositePlayer,
            applyAudioFocusSetting = applyAudioFocusSetting,
        )
    }
}
