package com.mica.music.media

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
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

/**
 * 播放服务：拥有 ExoPlayer + MediaSession，独立于 Activity 生命周期。
 *
 * 职责：
 * - 唯一持有 ExoPlayer 实例（避免多个 Activity 时音频抢占）
 * - 通过 MediaSession 对接系统：通知栏 / 锁屏 / 蓝牙耳机 / Auto / Wear
 * - 由 Media3 自动生成媒体通知（无需手写 NotificationChannel）
 *
 * 生命周期：
 * - 由 MediaController.buildAsync() 自动启动（无需 Activity 显式 startService）
 * - onTaskRemoved：用户滑掉任务栈时，仅当未在播放时停止服务（在播放则继续）
 */
@UnstableApi
class MicaMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var compositePlayer: MicaCompositePlayer? = null
    private var playbackStateCoordinator: ServicePlaybackStateCoordinator? = null
    private var playbackEngineCoordinator: ServicePlaybackEngineCoordinator? = null
    private var softwareEngine: AlacAudioTrackEngine? = null
    private lateinit var audioManager: AudioManager
    private var softwareFocusRequest: AudioFocusRequest? = null
    private var softwareFocusGeneration: Long? = null
    private var noisyReceiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                compositePlayer?.pause()
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val player = compositePlayer ?: return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                softwareEngine?.setVolume(1f)
                softwareFocusGeneration?.let {
                    playbackEngineCoordinator?.onSoftwareAudioFocusGain(it)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (player.isAlacActive) {
                    softwareEngine?.setVolume(0.25f)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                softwareFocusGeneration?.let {
                    playbackEngineCoordinator?.onSoftwareAudioFocusLoss(it, transient = true)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                softwareFocusGeneration?.let {
                    playbackEngineCoordinator?.onSoftwareAudioFocusLoss(it, transient = false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                // 与 Media3 官方 Session Demo 对齐：Android 13+ 未授予 POST_NOTIFICATIONS 时，
                // 强行 pauseAllPlayersAndStopSelf() 容易与 Activity 侧 MediaController 断开时机冲突，导致进程退出。
                // 此时不做激进收尾；用户授权通知后前台服务即可正常建立。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
            }
        })
        val dataSourceFactory = DefaultDataSource.Factory(this)
        val renderersFactory = MicaRenderersFactory(this).apply {
            setEnableDecoderFallback(true)
        }
        val exoPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        MicaEqualizerManager.onEnabledChanged = { enabled ->
            mainHandler.post {
                if (compositePlayer != null) {
                    configureQualityMode(exoPlayer, dspEnabled = enabled)
                    playbackStateCoordinator?.setQualityMode(
                        if (enabled) AudioQualityMode.DSP else AudioQualityMode.HIFI,
                    )
                }
            }
        }
        configureQualityMode(
            exoPlayer,
            dspEnabled = com.mica.music.data.AppPreferences.equalizerEnabled(this),
        )
        val player = MicaCompositePlayer(exoPlayer)
        compositePlayer = player
        val softwareEngine = AlacAudioTrackEngine(this).also { this.softwareEngine = it }
        val focusGate = object : SoftwareAudioFocusGate {
            override fun request(generation: Long): Boolean {
                val granted = requestSoftwareAudioFocus()
                if (granted) softwareFocusGeneration = generation
                return granted
            }

            override fun abandon(generation: Long) {
                if (softwareFocusGeneration != generation) return
                abandonSoftwareAudioFocus()
            }
        }
        playbackEngineCoordinator = ServicePlaybackEngineCoordinator(
            context = this,
            player = player,
            engine = softwareEngine,
            audioFocusGate = focusGate,
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
                if (player.alacState == null) {
                    MicaEqualizerManager.attach(this@MicaMediaService, audioSessionId)
                }
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
        val alacSession = (player as? MicaCompositePlayer)?.alacState
        val alacActive = alacSession?.playWhenReady == true
        // 用户滑掉任务栈：仅在以下情况停止服务
        // - 未在播放（playWhenReady=false，即暂停状态）
        // - 队列为空
        // - 已播完（STATE_ENDED）
        // 正在播放则保留服务，让用户通过通知栏继续控制
        if (MediaServiceLifecyclePolicy.shouldStopAfterTaskRemoved(
                playWhenReady = player.playWhenReady,
                alacPlayWhenReady = alacActive,
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
        abandonSoftwareAudioFocus()
        MicaEqualizerManager.onEnabledChanged = null
        MicaEqualizerManager.release()
        playbackStateCoordinator?.release()
        playbackStateCoordinator = null
        playbackEngineCoordinator?.release()
        playbackEngineCoordinator = null
        softwareEngine = null
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

    private fun requestSoftwareAudioFocus(): Boolean {
        val request = softwareFocusRequest ?: AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN,
        )
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build()
            .also { softwareFocusRequest = it }
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonSoftwareAudioFocus() {
        softwareFocusGeneration = null
        softwareFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        softwareEngine?.setVolume(1f)
    }

    private fun configureQualityMode(exoPlayer: ExoPlayer, dspEnabled: Boolean) {
        val offloadMode = if (dspEnabled) {
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
            "mode=${if (dspEnabled) "DSP" else "HIFI"} dsp=$dspEnabled offload=${!dspEnabled}",
        )
    }
}
