package com.mica.music.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.media.dsd.DirectDsdTrackTransitionCoordinator
import com.mica.music.media.dsd.ManualNavigationTransitionBridge
import com.mica.music.media.dsd.ManualNavigationTimelinePeriodResolver

@UnstableApi
internal data class ExoPlaybackStack(
    val exoPlayer: ExoPlayer,
    val compositePlayer: MicaCompositePlayer,
    val applyAudioFocusSetting: () -> Unit,
    val manualNavigationTransitionBridge: ManualNavigationTransitionBridge,
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
        val trackTransitionCoordinator = DirectDsdTrackTransitionCoordinator()
        val manualNavigationTransitionBridge = ManualNavigationTransitionBridge()
        val renderersFactory = MicaRenderersFactory(
            context,
            outputPath,
            trackTransitionCoordinator,
            manualNavigationTransitionBridge,
        )
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
            .setLoadControl(buildAudioLoadControl())
            .setAudioAttributes(
                playbackAudioAttributes,
                PlaybackUiPreferences.audioFocusEnabled(context),
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
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
            trackTransitionCoordinator = trackTransitionCoordinator,
            manualNavigationTransitionBridge = manualNavigationTransitionBridge,
        )
        fun publishApplicationCurrentness(timeline: Timeline, mediaItem: MediaItem?) {
            val mediaId = mediaItem?.mediaId
            val targetPeriodUid = mediaId?.let {
                ManualNavigationTimelinePeriodResolver.resolveSinglePeriodUid(
                    timeline = timeline,
                    windowIndex = exoPlayer.currentMediaItemIndex,
                    expectedMediaId = it,
                )
            }
            manualNavigationTransitionBridge.updateApplicationCurrentness(mediaId, targetPeriodUid)
        }
        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                publishApplicationCurrentness(exoPlayer.currentTimeline, mediaItem)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                publishApplicationCurrentness(timeline, exoPlayer.currentMediaItem)
            }
        })
        return ExoPlaybackStack(
            exoPlayer = exoPlayer,
            compositePlayer = compositePlayer,
            applyAudioFocusSetting = applyAudioFocusSetting,
            manualNavigationTransitionBridge = manualNavigationTransitionBridge,
        )
    }
}
