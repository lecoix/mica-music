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
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.media.dsd.DirectDsdTrackTransitionCoordinator
import com.mica.music.media.dsd.ManualNavigationTransitionBridge
import com.mica.music.media.dsd.ManualNavigationTimelinePeriodResolver
import com.mica.music.media.usb.protocol.OutputTarget
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackCoordinator
import com.mica.music.media.usb.shadow.UsbExclusiveShadowMedia3Facts
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackStack

@UnstableApi
internal data class ExoPlaybackStack(
    val exoPlayer: ExoPlayer,
    val compositePlayer: MicaCompositePlayer,
    val applyAudioFocusSetting: () -> Unit,
    val manualNavigationTransitionBridge: ManualNavigationTransitionBridge,
    val playbackStack: UsbExclusivePlaybackStack?,
) {
    /** Source-compatible alias; it references the same production stack instance. */
    val shadowStack: UsbExclusivePlaybackStack?
        get() = playbackStack
}

@UnstableApi
internal object ExoPlaybackStackFactory {

    fun build(
        context: Context,
        outputPath: AudioOutputPathConfig = AudioOutputPathConfig.PRODUCTION,
        playbackCoordinator: UsbExclusivePlaybackCoordinator? = null,
    ): ExoPlaybackStack {
        outputPath.requireSupportedForPlayback()
        outputPath.logForDiagnostics()
        val dataSourceFactory = DefaultDataSource.Factory(context)
        val trackTransitionCoordinator = DirectDsdTrackTransitionCoordinator()
        val manualNavigationTransitionBridge = ManualNavigationTransitionBridge()
        val initialOutputTarget = if (outputPath.usbOutputRequest == null) {
            OutputTarget.SharedPcm
        } else {
            OutputTarget.Unavailable
        }
        val playbackStack = playbackCoordinator?.createStack(initialOutputTarget)
        val renderersFactory = MicaRenderersFactory(
            context,
            outputPath,
            trackTransitionCoordinator,
            manualNavigationTransitionBridge,
            playbackStack,
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
        ).also { it.installUsbExclusivePlaybackStack(playbackStack) }
        fun publishApplicationCurrentness(
            timeline: Timeline,
            mediaItem: MediaItem?,
            invalidatePlayingOccurrence: Boolean = false,
        ) {
            val mediaId = mediaItem?.mediaId
            val targetPeriodUid = mediaId?.let {
                ManualNavigationTimelinePeriodResolver.resolveSinglePeriodUid(
                    timeline = timeline,
                    windowIndex = exoPlayer.currentMediaItemIndex,
                    expectedMediaId = it,
                )
            }
            if (mediaId != null) playbackStack?.observeTimelinePeriod(mediaId, targetPeriodUid)
            manualNavigationTransitionBridge.updateApplicationCurrentness(
                mediaId,
                targetPeriodUid,
                invalidatePlayingOccurrence,
            )
        }
        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                playbackStack?.observeApplicationMedia(mediaItem?.mediaId)
                publishApplicationCurrentness(
                    exoPlayer.currentTimeline,
                    mediaItem,
                    invalidatePlayingOccurrence = true,
                )
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val limit = minOf(exoPlayer.mediaItemCount, timeline.windowCount)
                for (index in 0 until limit) {
                    val item = runCatching { exoPlayer.getMediaItemAt(index) }.getOrNull() ?: continue
                    playbackStack?.observeTimelinePeriod(
                        item.mediaId,
                        ManualNavigationTimelinePeriodResolver.resolveSinglePeriodUid(
                            timeline,
                            index,
                            item.mediaId,
                        ),
                    )
                }
                publishApplicationCurrentness(timeline, exoPlayer.currentMediaItem)
            }
        })
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onEvents(player: Player, events: AnalyticsListener.Events) {
                var currentOccurrence: MediaSource.MediaPeriodId? = null
                var authoritative = events.size() > 0
                for (index in 0 until events.size()) {
                    val mediaPeriodId = events.getEventTime(events.get(index)).currentMediaPeriodId
                    if (mediaPeriodId == null) {
                        authoritative = false
                        break
                    }
                    if (currentOccurrence == null) {
                        currentOccurrence = mediaPeriodId
                    } else if (currentOccurrence != mediaPeriodId) {
                        authoritative = false
                        break
                    }
                }
                playbackStack?.observeCurrentPlayerOccurrence(
                    player.currentMediaItem?.mediaId,
                    currentOccurrence
                        ?.takeIf { authoritative }
                        ?.let(UsbExclusiveShadowMedia3Facts::occurrence),
                )
                manualNavigationTransitionBridge.updateApplicationPlayingOccurrence(
                    currentOccurrence.takeIf { authoritative },
                )
            }
        })
        return ExoPlaybackStack(
            exoPlayer = exoPlayer,
            compositePlayer = compositePlayer,
            applyAudioFocusSetting = applyAudioFocusSetting,
            manualNavigationTransitionBridge = manualNavigationTransitionBridge,
            playbackStack = playbackStack,
        )
    }
}
