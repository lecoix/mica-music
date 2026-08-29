package com.mica.music.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.smb.SmbPlaybackRequestResolver
import com.mica.music.media.usbhybrid.UsbHybridPlaybackBinding

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
        usbBinding: UsbHybridPlaybackBinding? = null,
        remoteResolver: RemoteHttpPlaybackRequestResolver? = null,
        smbResolver: SmbPlaybackRequestResolver? = null,
    ): ExoPlaybackStack {
        outputPath.requireSupportedForPlayback()
        outputPath.logForDiagnostics()
        val dataSourceFactory = if (remoteResolver != null || smbResolver != null) {
            MicaRoutingDataSourceFactory(
                context = context,
                remoteResolver = remoteResolver,
                smbResolver = smbResolver,
            )
        } else {
            DefaultDataSource.Factory(context)
        }
        val renderersFactory = MicaRenderersFactory(context, outputPath, usbBinding)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            dataSourceFactory,
            MicaExtractorsFactory.create(),
        )
        val playbackAudioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        // Media3's selector delegates API 32+ multichannel eligibility to the
        // platform Spatializer. Keep this enabled so the system decides whether
        // a spatializable track should be selected; no app-side DSP is applied.
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setConstrainAudioChannelCountToDeviceCapabilities(true)
                    .build(),
            )
        }
        val exoPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            // The service treats any release timeout as a failed switch and never opens the new
            // USB session. Allow ordinary USB renderer cleanup longer than Media3's short default.
            .setReleaseTimeoutMs(15_000L)
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
