package com.mica.music.media

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.mica.music.MainActivity
import com.mica.music.data.preferences.EqualizerPreferences
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.util.DiagnosticLog

/**
 * 播放服务：拥有 ExoPlayer + MediaSession，独立于 Activity 生命周期。
 */
@UnstableApi
class MicaMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var compositePlayer: MicaCompositePlayer? = null
    private var replayGainStateOwner: ReplayGainStateOwner? = null
    private var spectrumAnalyzerStateOwner: SpectrumAnalyzerStateOwner? = null
    /** Fixed at Exo build; P6 USB attach/detach will change this via full-mode rebuild. */
    private var activeOutputPath: AudioOutputPathConfig = AudioOutputPathConfig.PRODUCTION
    private var playbackStateCoordinator: ServicePlaybackStateCoordinator? = null
    private var notificationLyricsCoordinator: NotificationLyricsCoordinator? = null
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

        PlaybackCapabilityDiagnostics.logStartup(this)
        PcmDeliveryExperiment.logActiveExperiments()
        spectrumAnalyzerStateOwner = SpectrumAnalyzerStateOwner(this).also { it.start() }

        val stack = ExoPlaybackStackFactory.build(this, activeOutputPath)
        exoPlayer = stack.exoPlayer
        compositePlayer = stack.compositePlayer
        replayGainStateOwner = ReplayGainStateOwner(this, stack.compositePlayer).also { it.start() }

        wireEqualizerAndSpectrumHandlers()
        configureQualityMode(
            stack.exoPlayer,
            dspEnabled = EqualizerPreferences.equalizerEnabled(this),
            spectrumTapEnabled = spectrumTapEnabled(),
        )

        playbackEngineCoordinator = ServicePlaybackEngineCoordinator(
            player = stack.compositePlayer,
            context = this,
        ).also { coordinator -> coordinator.start() }

        playbackStateCoordinator = ServicePlaybackStateCoordinator(
            player = stack.compositePlayer,
            store = ServicePlaybackStateStore(this),
            handler = mainHandler,
            initialQualityMode = if (EqualizerPreferences.equalizerEnabled(this)) {
                AudioQualityMode.DSP
            } else {
                AudioQualityMode.HIFI
            },
        ).also { coordinator ->
            coordinator.onRestoreCompleted = {
                mainHandler.post {
                    val song = compositePlayer?.currentMediaItem
                        ?.let(SongMediaItemCodec::decode)
                        ?: return@post
                    SharedPcmPipelineDiagnostics.logSongFormat(song)
                    PcmDeliveryProbeDiagnostics.logForSong(
                        context = this@MicaMediaService,
                        song = song,
                        playbackParameters = compositePlayer?.playbackParameters
                            ?: PlaybackParameters.DEFAULT,
                    )
                }
            }
            coordinator.start()
        }

        notificationLyricsCoordinator = NotificationLyricsCoordinator(
            context = this,
            player = stack.compositePlayer,
            handler = mainHandler,
        ).also { it.start() }

        attachEqualizerSessionListener(stack.exoPlayer)

        mediaSession = MediaSession.Builder(this, stack.compositePlayer)
            .setSessionActivity(createSessionActivityPendingIntent())
            .build()

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
        replayGainStateOwner?.release()
        replayGainStateOwner = null
        spectrumAnalyzerStateOwner?.release()
        spectrumAnalyzerStateOwner = null
        if (noisyReceiverRegistered) {
            runCatching { unregisterReceiver(noisyReceiver) }
            noisyReceiverRegistered = false
        }
        MicaEqualizerManager.onEnabledChanged = null
        MicaSpectrumAnalyzer.onEnabledChanged = null
        MicaEqualizerManager.release()
        playbackStateCoordinator?.release()
        playbackStateCoordinator = null
        notificationLyricsCoordinator?.release()
        notificationLyricsCoordinator = null
        playbackEngineCoordinator?.release()
        playbackEngineCoordinator = null
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        compositePlayer = null
        clearListener()
        super.onDestroy()
    }

    private fun wireEqualizerAndSpectrumHandlers() {
        MicaEqualizerManager.onEnabledChanged = { enabled ->
            mainHandler.post {
                playbackStateCoordinator?.setQualityMode(
                    if (enabled) AudioQualityMode.DSP else AudioQualityMode.HIFI,
                )
                configureQualityMode(
                    exoPlayer ?: return@post,
                    dspEnabled = enabled,
                    spectrumTapEnabled = spectrumTapEnabled(),
                )
                flushAudioPipeline("equalizer-enabled=$enabled")
            }
        }

        MicaSpectrumAnalyzer.onEnabledChanged = { enabled ->
            mainHandler.post {
                configureQualityMode(
                    exoPlayer ?: return@post,
                    dspEnabled = EqualizerPreferences.equalizerEnabled(this@MicaMediaService),
                    spectrumTapEnabled = enabled,
                )
                flushAudioPipeline("spectrum-enabled=$enabled")
            }
        }
    }

    private fun attachEqualizerSessionListener(exo: ExoPlayer) {
        exo.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                MicaEqualizerManager.attach(this@MicaMediaService, audioSessionId)
            }
        })
        if (exo.audioSessionId != 0) {
            MicaEqualizerManager.attach(this, exo.audioSessionId)
        }
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

    private fun createSessionActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun spectrumTapEnabled(): Boolean =
        spectrumAnalyzerStateOwner?.currentEnabled ?: PlaybackUiPreferences.spectrumTapEnabled(this)

    private fun flushAudioPipeline(reason: String) {
        val player = compositePlayer ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        val positionMs = player.currentPosition
        val shouldResume = player.playWhenReady
        player.flushPlaybackPipeline(positionMs, resumePlayback = shouldResume)
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
