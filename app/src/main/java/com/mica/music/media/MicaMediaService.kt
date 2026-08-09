package com.mica.music.media

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.mica.music.MainActivity
import com.mica.music.MicaApp
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.preferences.AudioOffloadPreferences
import com.mica.music.data.preferences.EqualizerPreferences
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private var carBluetoothLyricsSession: CarBluetoothLyricsSession? = null
    private var playbackEngineCoordinator: ServicePlaybackEngineCoordinator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionScope: CoroutineScope? = null
    private var trustedMediaItemResolver: TrustedMediaItemResolver? = null
    private var unregisterLyricsPreferenceListener: (() -> Unit)? = null
    private var unregisterAudioOffloadPreferenceListener: (() -> Unit)? = null
    private var playbackRouteMonitor: PlaybackRouteMonitor? = null
    private var audioOffloadCircuitBreaker: AudioOffloadCircuitBreaker? = null
    private var audioPipelineCoordinator: AudioPipelineCoordinator? = null

    override fun onCreate() {
        super.onCreate()
        activeOutputPath = UsbHostPrototypeOutput.selectedPath(this)
        val micaApp = application as MicaApp
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val libraryRepository = LibraryRepository(this)
        trustedMediaItemResolver = TrustedMediaItemResolver(
            transientSongById = micaApp.transientPlaybackCatalog::songById,
            librarySongsById = libraryRepository::songSummariesByIds,
            mediaItemFactory = { song -> ExternalMediaItemCodec.encode(this, song) },
        )
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

        installAudioOffloadCircuitBreaker(stack.exoPlayer)
        installAudioPipelineCoordinator(stack.exoPlayer)
        wireEqualizerAndSpectrumHandlers()
        installPlaybackRouteMonitor()

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
            externalSongResolver = micaApp.transientPlaybackCatalog::songForPersistence,
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

        carBluetoothLyricsSession = CarBluetoothLyricsSession(
            context = this,
            player = stack.compositePlayer,
            sessionActivity = createSessionActivityPendingIntent(),
        )

        notificationLyricsCoordinator = NotificationLyricsCoordinator(
            context = this,
            player = stack.compositePlayer,
            handler = mainHandler,
            carBluetoothLyrics = carBluetoothLyricsSession,
            desktopLyrics = micaApp.desktopLyricsOverlayStateStore,
            transientSongResolver = (application as MicaApp).transientPlaybackCatalog::songById,
        ).also { it.start() }

        if (LyricsPreferences.externalLyricsMode(this) != com.mica.music.data.ExternalLyricsMode.OFF &&
            DesktopLyricsOverlayController.canDrawOverlays(this)
        ) {
            DesktopLyricsOverlayController.start(this)
        }

        attachEqualizerSessionListener(stack.exoPlayer)

        mediaSession = MediaSession.Builder(this, stack.compositePlayer)
            .setCallback(createMediaSessionCallback())
            .setSessionActivity(createSessionActivityPendingIntent())
            .setMediaButtonPreferences(
                ExternalLyricsSessionCommands.mediaButtonPreferences(this),
            )
            .build()
        unregisterLyricsPreferenceListener =
            LyricsPreferences.registerNotificationLyricsChangeListener(this) {
                mainHandler.post(::updateMediaButtonPreferences)
            }
        unregisterAudioOffloadPreferenceListener =
            AudioOffloadPreferences.registerChangeListener(this) { state ->
                mainHandler.post {
                    audioPipelineCoordinator?.onOffloadPreferenceChanged(state.enabled)
                }
            }
        playbackEngineCoordinator?.onPlaybackBoundary = { boundary ->
            mediaSession?.broadcastCustomCommand(
                PlaybackBoundarySessionEvent.command,
                PlaybackBoundarySessionEvent.encode(boundary),
            )
        }

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
        playbackRouteMonitor?.release()
        playbackRouteMonitor = null
        unregisterLyricsPreferenceListener?.invoke()
        unregisterLyricsPreferenceListener = null
        unregisterAudioOffloadPreferenceListener?.invoke()
        unregisterAudioOffloadPreferenceListener = null
        audioPipelineCoordinator = null
        sessionScope?.cancel()
        sessionScope = null
        trustedMediaItemResolver = null
        replayGainStateOwner?.release()
        replayGainStateOwner = null
        spectrumAnalyzerStateOwner?.release()
        spectrumAnalyzerStateOwner = null
        MicaEqualizerManager.onEnabledChanged = null
        MicaSpectrumAnalyzer.onEnabledChanged = null
        MicaEqualizerManager.release()
        playbackStateCoordinator?.release()
        playbackStateCoordinator = null
        notificationLyricsCoordinator?.release()
        notificationLyricsCoordinator = null
        carBluetoothLyricsSession?.release()
        carBluetoothLyricsSession = null
        playbackEngineCoordinator?.release()
        playbackEngineCoordinator = null
        audioOffloadCircuitBreaker?.let { breaker ->
            exoPlayer?.removeListener(breaker)
            exoPlayer?.removeAudioOffloadListener(breaker)
            breaker.release()
        }
        audioOffloadCircuitBreaker = null
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        compositePlayer = null
        clearListener()
        super.onDestroy()
    }

    private fun createMediaSessionCallback(): MediaSession.Callback =
        object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                val identity = controllerIdentity(controller)
                val capabilities = ControllerCapabilityPolicy.evaluate(identity, packageName)
                DiagnosticLog.event(
                    "MediaSession",
                    "controller-connect package=${identity.packageName} uid=${identity.uid} " +
                        "trusted=${identity.isTrusted} version=${identity.controllerVersion} " +
                        "class=${capabilities.controllerClass} " +
                        "hints=${identity.connectionHintKeys.sorted().joinToString(",")}",
                )

                // Media3's default callback preserves its standard trusted/untrusted player
                // command rules. Mica-specific capabilities are handled below and are explicit.
                val defaultResult = super.onConnect(session, controller)
                if (!controller.isTrusted && controller.packageName != packageName) {
                    return defaultResult.also {
                        grantArtworkUriPermissions(
                            targetPackage = controller.packageName,
                            mediaItems = session.player.timelineMediaItems(),
                        )
                    }
                }
                val availableSessionCommands = defaultResult.availableSessionCommands
                    .buildUpon()
                    .add(ExternalLyricsSessionCommands.toggleDesktopLock)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(defaultResult.availablePlayerCommands)
                    .setAvailableSessionCommands(availableSessionCommands)
                    .setMediaButtonPreferences(
                        ExternalLyricsSessionCommands.mediaButtonPreferences(this@MicaMediaService),
                    )
                    .build().also {
                    grantArtworkUriPermissions(
                        targetPackage = controller.packageName,
                        mediaItems = session.player.timelineMediaItems(),
                    )
                }
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: androidx.media3.session.SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                val identity = controllerIdentity(controller)
                if (customCommand.customAction ==
                    ExternalLyricsSessionCommands.TOGGLE_DESKTOP_LOCK_ACTION &&
                    isMediaNotificationController(session, controller)
                ) {
                    mainHandler.post {
                        if (LyricsPreferences.externalLyricsMode(this@MicaMediaService) ==
                            com.mica.music.data.ExternalLyricsMode.DESKTOP
                        ) {
                            LyricsPreferences.setDesktopLyricsLocked(
                                this@MicaMediaService,
                                !LyricsPreferences.desktopLyricsLocked(this@MicaMediaService),
                            )
                            DesktopLyricsOverlayController.refreshSettings(this@MicaMediaService)
                            updateMediaButtonPreferences()
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (!ControllerCapabilityPolicy.allowsIncomingCustomAction(
                        identity = identity,
                        ownPackageName = packageName,
                        action = customCommand.customAction,
                    )
                ) {
                    DiagnosticLog.event(
                        "MediaSession",
                        "custom-command-rejected package=${identity.packageName} " +
                            "action=${customCommand.customAction}",
                    )
                    return Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                    )
                }
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
            ): ListenableFuture<MutableList<MediaItem>> {
                val identity = controllerIdentity(controller)
                val capabilities = ControllerCapabilityPolicy.evaluate(identity, packageName)
                if (!capabilities.resolveMediaItemsFromCatalog) {
                    return Futures.immediateFuture(mediaItems)
                }
                return launchSessionFuture {
                    val resolved = trustedMediaItemResolver
                        ?.resolve(mediaItems)
                        ?.mediaItems.orEmpty()
                    grantArtworkUriPermissions(controller.packageName, resolved)
                    resolved.toMutableList()
                }
            }

            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val identity = controllerIdentity(controller)
                val capabilities = ControllerCapabilityPolicy.evaluate(identity, packageName)
                if (!capabilities.resolveMediaItemsFromCatalog) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            mediaItems,
                            startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0)),
                            startPositionMs,
                        ),
                    )
                }
                if (mediaItems.size == 1 &&
                    MediaItemRequestPolicy.isEmptyRequest(mediaItems.single())
                ) {
                    val current = mediaSession.player.currentMediaItem
                    if (current != null) {
                        grantArtworkUriPermissions(controller.packageName, listOf(current))
                        DiagnosticLog.event(
                            "MediaSession",
                            "empty-set-request-preserved-current package=${controller.packageName}",
                        )
                        return Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(
                                listOf(current),
                                0,
                                startPositionMs,
                            ),
                        )
                    }
                }
                return launchSessionFuture {
                    val resolution = trustedMediaItemResolver?.resolve(mediaItems, startIndex)
                        ?: TrustedMediaItemsResolution(emptyList(), null)
                    grantArtworkUriPermissions(controller.packageName, resolution.mediaItems)
                    MediaSession.MediaItemsWithStartPosition(
                        resolution.mediaItems.toMutableList(),
                        resolution.resolvedStartIndex ?: 0,
                        startPositionMs,
                    )
                }
            }
        }

    private fun grantArtworkUriPermissions(targetPackage: String, mediaItems: List<MediaItem>) {
        if (targetPackage.isBlank()) return
        val artworkAuthority = "$packageName.artwork"
        mediaItems.forEach { mediaItem ->
            val artworkUri = mediaItem.mediaMetadata.artworkUri ?: return@forEach
            if (artworkUri.scheme?.equals("content", ignoreCase = true) != true ||
                artworkUri.authority != artworkAuthority
            ) {
                return@forEach
            }
            runCatching {
                grantUriPermission(targetPackage, artworkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure { error ->
                DiagnosticLog.event(
                    "MediaSession",
                    "artwork-grant-failed package=$targetPackage uri=$artworkUri " +
                        "error=${error.javaClass.simpleName}",
                )
            }
        }
    }

    private fun isMediaNotificationController(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): Boolean {
        val notificationController = session.getMediaNotificationControllerInfo()
            ?: return false
        return notificationController.packageName == controller.packageName &&
            notificationController.uid == controller.uid
    }

    private fun updateMediaButtonPreferences() {
        mediaSession?.setMediaButtonPreferences(
            ExternalLyricsSessionCommands.mediaButtonPreferences(this),
        )
    }

    private fun Player.timelineMediaItems(): List<MediaItem> =
        List(mediaItemCount) { index -> getMediaItemAt(index) }

    private fun controllerIdentity(controller: MediaSession.ControllerInfo): ControllerIdentity =
        ControllerIdentity(
            packageName = controller.packageName,
            uid = controller.uid,
            isTrusted = controller.isTrusted,
            controllerVersion = controller.controllerVersion,
            connectionHintKeys = controller.connectionHints.keySet(),
        )

    private fun <T> launchSessionFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val scope = sessionScope
        if (scope == null) {
            future.setException(IllegalStateException("MediaSession scope is not active"))
            return future
        }
        scope.launch {
            runCatching { block() }
                .onSuccess(future::set)
                .onFailure(future::setException)
        }
        return future
    }

    private fun wireEqualizerAndSpectrumHandlers() {
        MicaEqualizerManager.onEnabledChanged = { enabled ->
            mainHandler.post {
                audioPipelineCoordinator?.onEqualizerEnabledChanged(enabled)
            }
        }

        MicaSpectrumAnalyzer.onEnabledChanged = { enabled ->
            mainHandler.post {
                audioPipelineCoordinator?.onSpectrumTapEnabledChanged(enabled)
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

    private fun installPlaybackRouteMonitor() {
        playbackRouteMonitor?.release()
        playbackRouteMonitor = PlaybackRouteMonitor(
            context = this,
            mainHandler = mainHandler,
        ) { previous, current, event ->
            audioPipelineCoordinator?.onRouteChanged(
                "route-change event=$event ${previous.deviceName}->${current.deviceName}",
            )
        }.also { it.install() }
    }

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

    private fun installAudioPipelineCoordinator(exoPlayer: ExoPlayer) {
        val preferenceState = AudioOffloadPreferences.state(this)
        audioPipelineCoordinator = AudioPipelineCoordinator(
            initialState = AudioPipelineState(
                equalizerEnabled = EqualizerPreferences.equalizerEnabled(this),
                spectrumTapEnabled = spectrumTapEnabled(),
                offloadPreferenceEnabled = preferenceState.enabled,
            ),
            invalidateCircuitBreaker = {
                audioOffloadCircuitBreaker?.invalidateExternalBoundary()
            },
            resetCircuitBreaker = {
                audioOffloadCircuitBreaker?.resetForManualRetry()
            },
            applyConfiguration = { state ->
                applyAudioPipelineConfiguration(exoPlayer, state)
            },
            persistQualityMode = { mode ->
                playbackStateCoordinator?.setQualityMode(mode)
            },
            flushPipeline = ::flushAudioPipeline,
        ).also { it.applyInitialConfiguration() }
    }

    private fun applyAudioPipelineConfiguration(
        exoPlayer: ExoPlayer,
        state: AudioPipelineState,
    ) {
        val offloadMode = if (state.offloadEnabled) {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        } else {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
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
            "mode=${if (state.equalizerEnabled) "DSP" else "HIFI"} " +
                "dsp=${state.equalizerEnabled} spectrum=${state.spectrumTapEnabled} " +
                "offload=${state.offloadEnabled} preference=${state.offloadPreferenceEnabled} " +
                "circuitOpen=${state.circuitOpen}",
        )
    }

    private fun installAudioOffloadCircuitBreaker(exo: ExoPlayer) {
        val breaker = AudioOffloadCircuitBreaker(
            snapshot = {
                AudioOffloadPlaybackSnapshot(
                    mediaId = exo.currentMediaItem?.mediaId,
                    uriScheme = exo.currentMediaItem?.localConfiguration?.uri?.scheme?.lowercase(),
                    playbackState = exo.playbackState,
                    playWhenReady = exo.playWhenReady,
                    isPlaying = exo.isPlaying,
                    playbackSuppressionReason = exo.playbackSuppressionReason,
                    totalBufferedDurationMs = exo.totalBufferedDuration,
                    currentPositionMs = exo.currentPosition,
                )
            },
            scheduler = HandlerAudioOffloadWatchdogScheduler(mainHandler),
            onFallbackToPcm = {
                DiagnosticLog.event(
                    "AudioOffload",
                    "stall-detected fallback=pcm mediaId=${exo.currentMediaItem?.mediaId}",
                )
                audioPipelineCoordinator?.onOffloadCircuitOpened()
            },
            onVerifiedFailure = {
                DiagnosticLog.event(
                    "AudioOffload",
                    "pcm-recovery-verified disable-current-build=true",
                )
                AudioOffloadPreferences.recordVerifiedFailure(this)
            },
        )
        audioOffloadCircuitBreaker = breaker
        exo.addListener(breaker)
        exo.addAudioOffloadListener(breaker)
    }
}
