package com.mica.music.media



import android.Manifest

import android.content.BroadcastReceiver

import android.content.Context

import android.content.Intent

import android.content.IntentFilter

import android.content.pm.PackageManager

import android.media.AudioManager

import android.os.Build

import android.os.Handler

import android.os.Looper

import androidx.media3.common.AudioAttributes

import androidx.media3.common.C

import androidx.media3.common.Player

import androidx.media3.common.TrackSelectionParameters

import androidx.media3.common.util.UnstableApi

import androidx.media3.datasource.DefaultDataSource

import androidx.media3.exoplayer.ExoPlayer

import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

import androidx.media3.session.MediaSession

import androidx.media3.session.MediaSessionService

import com.mica.music.util.DiagnosticLog

import com.mica.music.data.AppPreferences

import com.mica.music.data.MiniPlayerStyle



/**

 * 播放服务：拥有 ExoPlayer + MediaSession，独立于 Activity 生命周期。

 */

@UnstableApi

class MicaMediaService : MediaSessionService() {



    private var mediaSession: MediaSession? = null

    private var compositePlayer: MicaCompositePlayer? = null

    private var playbackStateCoordinator: ServicePlaybackStateCoordinator? = null

    private var playbackEngineCoordinator: ServicePlaybackEngineCoordinator? = null

    private var noisyReceiverRegistered = false

    private val mainHandler = Handler(Looper.getMainLooper())



    private val noisyReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {

                compositePlayer?.pause()

            }

        }

    }



    override fun onCreate() {

        super.onCreate()

        setListener(object : MediaSessionService.Listener {

            override fun onForegroundServiceStartNotAllowedException() {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&

                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=

                    PackageManager.PERMISSION_GRANTED

                ) {

                    return

                }

            }

        })

        val dataSourceFactory = DefaultDataSource.Factory(this)

        PlaybackCapabilityDiagnostics.logStartup(this)

        val renderersFactory = MicaRenderersFactory(this)

        val mediaSourceFactory = DefaultMediaSourceFactory(

            dataSourceFactory,

            MicaExtractorsFactory.create(),

        )

        val exoPlayer = ExoPlayer.Builder(this)

            .setRenderersFactory(renderersFactory)

            .setMediaSourceFactory(mediaSourceFactory)

            .setAudioAttributes(

                AudioAttributes.Builder()

                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)

                    .setUsage(C.USAGE_MEDIA)

                    .build(),

                /* handleAudioFocus = */ true,

            )

            .setHandleAudioBecomingNoisy(true)

            .build()

        MicaSpectrumAnalyzer.setEnabled(spectrumTapEnabled(), notifyPipeline = false)

        var equalizerPipelineEnabled = AppPreferences.equalizerEnabled(this)

        MicaEqualizerManager.onEnabledChanged = { enabled ->

            mainHandler.post {

                val pipelineChanged = equalizerPipelineEnabled != enabled
                equalizerPipelineEnabled = enabled

                if (compositePlayer != null) {

                    configureQualityMode(
                        exoPlayer,
                        dspEnabled = enabled,
                        spectrumTapEnabled = spectrumTapEnabled(),
                    )

                    playbackStateCoordinator?.setQualityMode(

                        if (enabled) AudioQualityMode.DSP else AudioQualityMode.HIFI,

                    )

                    if (pipelineChanged) {
                        flushAudioPipeline("equalizer-enabled=$enabled")
                    }

                }

            }

        }

        MicaSpectrumAnalyzer.onEnabledChanged = { enabled ->

            mainHandler.post {

                configureQualityMode(
                    exoPlayer,
                    dspEnabled = AppPreferences.equalizerEnabled(this@MicaMediaService),
                    spectrumTapEnabled = enabled,
                )

                flushAudioPipeline("spectrum-enabled=$enabled")

            }

        }

        configureQualityMode(

            exoPlayer,

            dspEnabled = equalizerPipelineEnabled,

            spectrumTapEnabled = spectrumTapEnabled(),

        )

        val player = MicaCompositePlayer(exoPlayer)

        compositePlayer = player

        playbackEngineCoordinator = ServicePlaybackEngineCoordinator(
            player = player,

        ).also { it.start() }

        playbackStateCoordinator = ServicePlaybackStateCoordinator(

            player = player,

            store = ServicePlaybackStateStore(this),

            handler = mainHandler,

            initialQualityMode = if (com.mica.music.data.AppPreferences.equalizerEnabled(this)) {

                AudioQualityMode.DSP

            } else {

                AudioQualityMode.HIFI

            },

        ).also { it.start() }

        exoPlayer.addListener(object : Player.Listener {

            override fun onAudioSessionIdChanged(audioSessionId: Int) {

                MicaEqualizerManager.attach(this@MicaMediaService, audioSessionId)

            }

        })

        if (exoPlayer.audioSessionId != 0) {

            MicaEqualizerManager.attach(this, exoPlayer.audioSessionId)

        }

        mediaSession = MediaSession.Builder(this, player).build()

        registerNoisyReceiver()

    }



    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =

        mediaSession



    override fun onTaskRemoved(rootIntent: Intent?) {

        val player = compositePlayer ?: mediaSession?.player ?: return

        if (MediaServiceLifecyclePolicy.shouldStopAfterTaskRemoved(

                playWhenReady = player.playWhenReady,

                mediaItemCount = player.mediaItemCount,

                playbackState = player.playbackState,

            )

        ) {

            stopSelf()

        }

    }



    override fun onDestroy() {

        if (noisyReceiverRegistered) {

            runCatching { unregisterReceiver(noisyReceiver) }

            noisyReceiverRegistered = false

        }

        MicaEqualizerManager.onEnabledChanged = null

        MicaSpectrumAnalyzer.onEnabledChanged = null

        MicaEqualizerManager.release()

        playbackStateCoordinator?.release()

        playbackStateCoordinator = null

        playbackEngineCoordinator?.release()

        playbackEngineCoordinator = null

        mediaSession?.run {

            player.release()

            release()

            mediaSession = null

        }

        compositePlayer = null

        clearListener()

        super.onDestroy()

    }



    private fun registerNoisyReceiver() {

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            registerReceiver(noisyReceiver, filter, RECEIVER_NOT_EXPORTED)

        } else {

            @Suppress("DEPRECATION")

            registerReceiver(noisyReceiver, filter)

        }

        noisyReceiverRegistered = true

    }



    private fun spectrumTapEnabled(): Boolean =
        AppPreferences.spectrumEnabled(this) ||
            AppPreferences.miniPlayerStyle(this) == MiniPlayerStyle.AUDIOPHILE ||
            AppPreferences.playerCoverFlowMode(this).usesPhotoStack

    private fun flushAudioPipeline(reason: String) {
        val player = compositePlayer ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        val positionMs = player.currentPosition
        val shouldResume = player.isPlaying
        player.rebuildAudioPipeline(positionMs, resumePlayback = shouldResume)
        DiagnosticLog.event(
            "AudioPipeline",
            "pipeline-flush reason=$reason pos=$positionMs resume=$shouldResume",
        )
    }

    private fun configureQualityMode(
        exoPlayer: ExoPlayer,
        dspEnabled: Boolean,
        spectrumTapEnabled: Boolean,
    ) {

        val offloadDisabled = dspEnabled || spectrumTapEnabled

        val offloadMode = if (offloadDisabled) {

            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED

        } else {

            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED

        }

        val preferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()

            .setAudioOffloadMode(offloadMode)

            .build()

        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters

            .buildUpon()

            .setAudioOffloadPreferences(preferences)

            .build()

        DiagnosticLog.event(

            "AudioQuality",

            "mode=${if (dspEnabled) "DSP" else "HIFI"} dsp=$dspEnabled spectrum=$spectrumTapEnabled offload=${!offloadDisabled}",

        )

    }

}


