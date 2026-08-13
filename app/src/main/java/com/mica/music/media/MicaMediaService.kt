package com.mica.music.media

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
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
import com.mica.music.BuildConfig
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.preferences.AudioOffloadPreferences
import com.mica.music.data.preferences.EqualizerPreferences
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.media.usb.UsbOutputRuntime
import com.mica.music.media.usb.UsbOutputRequest
import com.mica.music.media.usb.UsbOutputPhase
import com.mica.music.media.usb.UsbOutputLifecycleEvent
import com.mica.music.media.usb.UsbOutputLifecycleRuntime
import com.mica.music.media.usb.UsbProvenReconnectTargetRuntime
import com.mica.music.media.usb.UsbLifecycleToken
import com.mica.music.media.usb.UsbHealthRecoveryController
import com.mica.music.media.usb.UsbHealthRecoveryDecision
import com.mica.music.media.usb.UsbRecoveryAckOutcome
import com.mica.music.media.usb.UsbRecoveryAction
import com.mica.music.media.usb.UsbRecoveryActivationExpectation
import com.mica.music.media.usb.UsbRecoveryActivationPolicy
import com.mica.music.media.usb.UsbRecoveryActivationState
import com.mica.music.media.usb.UsbRecoveryCoordinator
import com.mica.music.media.usb.UsbRecoveryEpoch
import com.mica.music.media.usb.UsbRecoveryRequestResult
import com.mica.music.media.usb.UsbRecoveryTrigger
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
    private var usbResumePlaybackRequested = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionScope: CoroutineScope? = null
    private var trustedMediaItemResolver: TrustedMediaItemResolver? = null
    private var unregisterLyricsPreferenceListener: (() -> Unit)? = null
    private var unregisterAudioOffloadPreferenceListener: (() -> Unit)? = null
    private var playbackRouteMonitor: PlaybackRouteMonitor? = null
    private var audioOffloadCircuitBreaker: AudioOffloadCircuitBreaker? = null
    private var audioPipelineCoordinator: AudioPipelineCoordinator? = null
    private lateinit var outputRebuildCoordinator: PlaybackOutputRebuildCoordinator<
        AudioOutputPathConfig,
        PlaybackStackSnapshot,
        ExoPlaybackStack,
    >
    private val usbRecoveryCoordinator = UsbRecoveryCoordinator()
    private val usbHealthRecoveryController = UsbHealthRecoveryController()
    private var usbRecoveryEpoch: UsbRecoveryEpoch? = null
    private var usbRecoveryRequest: UsbOutputRequest? = null
    private var usbRecoveryActivationExpectation: UsbRecoveryActivationExpectation? = null
    private var usbRecoveryRetry: Runnable? = null
    private var usbRecoveryFallbackAttempted = false
    private val usbHealthRecoveryPoll = object : Runnable {
        override fun run() {
            reconcilePendingUsbRecoveryActivation()
            usbHealthRecoveryController.poll(
                facts = { UsbOutputRuntime.owner.facts },
                recover = ::executeAutomaticUsbRecovery,
            )
            mainHandler.postDelayed(this, USB_HEALTH_POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeOutputPath = UsbHostOutputPreferences.selectedPath(this)
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
        usbResumePlaybackRequested = stack.compositePlayer.playWhenReady
        installUsbPlaybackIntentObserver(stack.compositePlayer)
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

        outputRebuildCoordinator = PlaybackOutputRebuildCoordinator(
            onGenerationPublished = { UsbOutputRuntime.owner.invalidate() },
            capture = {
                PlaybackStackSnapshot.capture(
                    checkNotNull(compositePlayer) { "Playback stack is not active" },
                )
            },
            buildCandidate = { target, _ -> ExoPlaybackStackFactory.build(this, target) },
            stageCandidate = { _, snapshot, candidate ->
                snapshot.stageInto(candidate.compositePlayer)
            },
            retirePublished = { _, _ -> retirePublishedPlaybackStack() },
            publishCandidate = ::publishRebuiltPlaybackStack,
            releaseCandidate = { candidate, _ -> candidate.exoPlayer.release() },
        )
        UsbOutputRebuildRuntime.install(::scheduleOutputPathRebuild)
        UsbOutputLifecycleRuntime.install(::handleUsbLifecycleEvent)
        UsbRecoveryDebugRuntime.install(::scheduleUsbRecoveryFromDebug)
        DebugPlaybackControlRuntime.install(::handleDebugPlaybackControl)
        mainHandler.postDelayed(usbHealthRecoveryPoll, USB_HEALTH_POLL_INTERVAL_MS)
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
        mainHandler.removeCallbacks(usbHealthRecoveryPoll)
        cancelUsbRecovery()
        UsbOutputRebuildRuntime.clear()
        UsbOutputLifecycleRuntime.clear()
        UsbOutputLifecycleRuntime.clearRecovery()
        UsbProvenReconnectTargetRuntime.clearForServiceDestruction()
        UsbRecoveryDebugRuntime.clear()
        DebugPlaybackControlRuntime.clear()
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
                    .apply {
                        if (BuildConfig.DEBUG && controller.packageName == packageName) {
                            add(UsbOutputRebuildSessionCommand.command)
                            add(DirectDsdPrototypeSessionCommand.command)
                        }
                    }
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
                if (customCommand.customAction == UsbOutputRebuildSessionCommand.ACTION &&
                    BuildConfig.DEBUG &&
                    controller.packageName == packageName
                ) {
                    scheduleOutputPathRebuild(args)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (customCommand.customAction == DirectDsdPrototypeSessionCommand.ACTION &&
                    BuildConfig.DEBUG &&
                    controller.packageName == packageName
                ) {
                    scheduleDirectDsdPrototypeRebuild(args)
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

    private fun scheduleOutputPathRebuild(args: Bundle) {
        scheduleOutputPathRebuild(
            requestedEnabled = args.getBoolean(
                UsbOutputRebuildSessionCommand.EXTRA_REQUESTED_ENABLED,
            ),
            previousEnabled = args.getBoolean(
                UsbOutputRebuildSessionCommand.EXTRA_PREVIOUS_ENABLED,
            ),
        )
    }

    private fun handleUsbLifecycleEvent(event: UsbOutputLifecycleEvent) {
        if (!BuildConfig.USB_EXCLUSIVE_SK02_AVAILABLE) return
        mainHandler.post {
            when (event) {
                is UsbOutputLifecycleEvent.Detached -> {
                    val token = UsbLifecycleToken(event.generation, event.runtimeHandle)
                    if (!UsbHostOutputPreferences.isEnabled(this)) {
                        UsbOutputLifecycleRuntime.clearIfCurrent(token)
                        return@post
                    }
                    if (activeOutputPath.outputMode != PlaybackOutputMode.UsbDirectPcm) {
                        if (!UsbOutputLifecycleRuntime.hasInterruptedPlayback(token)) {
                            UsbOutputLifecycleRuntime.clearIfCurrent(token)
                        }
                        return@post
                    }
                    val resumePlaybackRequested = usbResumePlaybackRequested
                    if (!UsbOutputLifecycleRuntime.rememberInterruptedPlayback(
                            token,
                            resumePlaybackRequested,
                            "usb_device_detached",
                        )
                    ) return@post
                    UsbOutputLifecycleRuntime.publishIfCurrent(token) {
                        val result = outputRebuildCoordinator.rebuild(AudioOutputPathConfig.PRODUCTION) {
                            it.copy(playWhenReady = resumePlaybackRequested)
                        }
                        DiagnosticLog.event(
                            "UsbOutputLifecycle",
                            "detach fallback=${result.javaClass.simpleName} generation=${event.generation} " +
                                "device=${event.runtimeHandle.runtimeDeviceId}",
                        )
                    }
                }
                is UsbOutputLifecycleEvent.Attached -> {
                    var lifecycleToken = UsbLifecycleToken(event.generation, event.runtimeHandle)
                    if (!UsbHostOutputPreferences.isEnabled(this) ||
                        !UsbOutputLifecycleRuntime.isCurrent(lifecycleToken)
                    ) return@post

                    var permissionRequest = com.mica.music.media.usb.UsbOutputRequest(
                        device = com.mica.music.media.usb.Sk02UsbContract.identity,
                    )
                    var permissionRuntimeHandle: com.mica.music.media.usb.UsbAudioRuntimeHandle? = null
                    var proofPermissionRequest = false
                    val interruptedUsbRecovery = UsbOutputLifecycleRuntime.hasInterruptedUsbIntent()
                    if (interruptedUsbRecovery) {
                        val expectedIdentity =
                            UsbProvenReconnectTargetRuntime.expectedIdentityForInterruptedRecovery(
                                interruptedUsbRecovery,
                            )
                        if (expectedIdentity == null) {
                            DiagnosticLog.event(
                                "UsbOutputLifecycle",
                                "reconnect resolution=unavailable reason=missing-proven-stable-identity",
                            )
                            return@post
                        }
                        DiagnosticLog.event(
                            "UsbOutputLifecycle",
                            "reconnect target=last-proven-production-open " +
                                "vendorId=${expectedIdentity.vendorId} productId=${expectedIdentity.productId} " +
                                "bcdDevice=${expectedIdentity.bcdDevice}",
                        )
                        val manager = getSystemService(android.hardware.usb.UsbManager::class.java)
                        when (
                            val resolution = com.mica.music.media.usb.AndroidUsbStableReconnectResolver.resolve(
                                manager,
                                expectedIdentity,
                            )
                        ) {
                            is com.mica.music.media.usb.UsbStableReconnectResolution.Resolved -> {
                                permissionRuntimeHandle = resolution.candidate.runtimeHandle
                                permissionRequest = com.mica.music.media.usb.UsbOutputRequest(
                                    device = expectedIdentity,
                                )
                                if (permissionRuntimeHandle != lifecycleToken.runtimeHandle) {
                                    lifecycleToken = UsbOutputLifecycleRuntime.beginAttach(
                                        permissionRuntimeHandle,
                                    )
                                }
                                if (!UsbOutputLifecycleRuntime.isCurrent(lifecycleToken)) return@post
                                DiagnosticLog.event(
                                    "UsbOutputLifecycle",
                                    "reconnect resolution=resolved lifecycleGeneration=${lifecycleToken.generation} " +
                                        "runtimeDeviceId=${permissionRuntimeHandle.runtimeDeviceId}",
                                )
                            }
                            com.mica.music.media.usb.UsbStableReconnectResolution.NoPotentialDevice -> {
                                DiagnosticLog.event(
                                    "UsbOutputLifecycle",
                                    "reconnect resolution=unavailable reason=no-potential-device",
                                )
                                return@post
                            }
                            is com.mica.music.media.usb.UsbStableReconnectResolution.PermissionUnavailable -> {
                                DiagnosticLog.event(
                                    "UsbOutputLifecycle",
                                    "reconnect resolution=permission-unavailable " +
                                        "candidates=${resolution.candidates.size}",
                                )
                                when (
                                    val plan = com.mica.music.media.usb.UsbReconnectProofPermissionPlanner.plan(
                                        expectedIdentity,
                                        resolution,
                                    )
                                ) {
                                    is com.mica.music.media.usb.UsbReconnectProofPermissionPlan.RequestProofPermission -> {
                                        val candidate = plan.candidate
                                        permissionRuntimeHandle = candidate.runtimeHandle
                                        permissionRequest = com.mica.music.media.usb.UsbOutputRequest(
                                            device = expectedIdentity,
                                        )
                                        if (permissionRuntimeHandle != lifecycleToken.runtimeHandle) {
                                            lifecycleToken = UsbOutputLifecycleRuntime.beginAttach(
                                                permissionRuntimeHandle,
                                            )
                                        }
                                        if (!UsbOutputLifecycleRuntime.isCurrent(lifecycleToken)) return@post
                                        proofPermissionRequest = true
                                        DiagnosticLog.event(
                                            "UsbOutputLifecycle",
                                            "reconnect proof-permission=planned lifecycleGeneration=${lifecycleToken.generation} " +
                                                "runtimeDeviceId=${candidate.runtimeHandle.runtimeDeviceId} " +
                                                "visibleVendorId=${candidate.vendorId} visibleProductId=${candidate.productId}",
                                        )
                                    }
                                    is com.mica.music.media.usb.UsbReconnectProofPermissionPlan.DoNotRequest -> {
                                        DiagnosticLog.event(
                                            "UsbOutputLifecycle",
                                            "reconnect proof-permission=blocked reason=${plan.rejection}",
                                        )
                                        return@post
                                    }
                                }
                            }
                            is com.mica.music.media.usb.UsbStableReconnectResolution.Unavailable -> {
                                DiagnosticLog.event(
                                    "UsbOutputLifecycle",
                                    "reconnect resolution=unavailable nonMatches=${resolution.nonMatches.size}",
                                )
                                return@post
                            }
                            is com.mica.music.media.usb.UsbStableReconnectResolution.Ambiguous -> {
                                DiagnosticLog.event(
                                    "UsbOutputLifecycle",
                                    "reconnect resolution=ambiguous matches=${resolution.matches.size}",
                                )
                                return@post
                            }
                        }
                    }

                    var permissionToken: com.mica.music.media.usb.UsbOutputRequestToken? = null
                    val requested = UsbOutputLifecycleRuntime.publishIfCurrent(lifecycleToken) {
                        if (activeOutputPath.outputMode == PlaybackOutputMode.UsbDirectPcm) {
                            val fallback = outputRebuildCoordinator.rebuild(AudioOutputPathConfig.PRODUCTION)
                            if (fallback !is PlaybackOutputRebuildResult.Published) return@publishIfCurrent
                        }
                        permissionToken = runCatching {
                            com.mica.music.media.usb.UsbOutputDeviceLifecycle.requestPermission(
                                this,
                                permissionRequest,
                                permissionRuntimeHandle,
                            )
                        }.getOrNull()
                    }
                    val token = permissionToken
                    if (!requested || token == null ||
                        !UsbOutputLifecycleRuntime.bindPermissionRequest(lifecycleToken, token.value)
                    ) return@post
                    val targetRuntimeHandle = permissionRuntimeHandle ?: lifecycleToken.runtimeHandle
                    DiagnosticLog.event(
                        "UsbOutputLifecycle",
                        "attach permission-requested lifecycleGeneration=${lifecycleToken.generation} " +
                            "permissionGeneration=${token.value} device=${targetRuntimeHandle.runtimeDeviceId} " +
                            "proofOnly=$proofPermissionRequest",
                    )
                    val facts = UsbOutputRuntime.owner.facts
                    if (facts.generation == token.value &&
                        facts.runtimeHandle == targetRuntimeHandle &&
                        facts.permission == com.mica.music.media.usb.UsbPermissionState.GRANTED
                    ) {
                        if (interruptedUsbRecovery && proofPermissionRequest) {
                            restoreInterruptedUsbAfterExactReproof(targetRuntimeHandle, token.value)
                        } else {
                            restoreUsbAfterGrantedPermission(targetRuntimeHandle, token.value)
                        }
                    }
                }
                is UsbOutputLifecycleEvent.Permission -> {
                    if (!UsbHostOutputPreferences.isEnabled(this)) return@post
                    if (!event.granted) {
                        val rejected = UsbOutputLifecycleRuntime.rejectPermission(
                            event.runtimeHandle,
                            event.generation,
                        )
                        DiagnosticLog.event(
                            "UsbOutputLifecycle",
                            "permission denied accepted=$rejected generation=${event.generation} " +
                                "device=${event.runtimeHandle.runtimeDeviceId}",
                        )
                        return@post
                    }
                    val facts = UsbOutputRuntime.owner.facts
                    if (facts.generation != event.generation || facts.runtimeHandle != event.runtimeHandle) return@post
                    if (UsbOutputLifecycleRuntime.hasInterruptedUsbIntent()) {
                        restoreInterruptedUsbAfterExactReproof(event.runtimeHandle, event.generation)
                    } else {
                        scheduleOutputPathRebuild(
                            requestedEnabled = true,
                            previousEnabled = false,
                        )
                    }
                }
            }
        }
    }

    private fun scheduleOutputPathRebuild(
        requestedEnabled: Boolean,
        previousEnabled: Boolean,
    ) {
        mainHandler.post {
            if (!requestedEnabled) {
                UsbOutputLifecycleRuntime.clearRecovery()
                UsbProvenReconnectTargetRuntime.clearForExplicitDisable()
            }
            val target = UsbHostOutputPreferences.pathForEnabled(requestedEnabled)
            val previousMode = activeOutputPath.outputMode
            val result = if (target == activeOutputPath) {
                PlaybackOutputRebuildResult.Published(0L)
            } else {
                outputRebuildCoordinator.rebuild(target)
            }
            DiagnosticLog.event(
                "UsbOutputRebuild",
                "result=${result.javaClass.simpleName} generation=${result.generation} " +
                    "from=$previousMode target=${target.outputMode}",
            )
            val resultCode = if (result is PlaybackOutputRebuildResult.Published) {
                SessionResult.RESULT_SUCCESS
            } else {
                SessionError.ERROR_UNKNOWN
            }
            if (resultCode != SessionResult.RESULT_SUCCESS) {
                UsbHostOutputPreferences.setEnabled(this, previousEnabled)
            } else {
                cancelUsbRecovery()
            }
            sendBroadcast(
                Intent(UsbOutputRebuildSessionCommand.resultAction(packageName))
                    .setPackage(packageName)
                    .putExtra(
                        UsbOutputRebuildSessionCommand.EXTRA_REQUESTED_ENABLED,
                        requestedEnabled,
                    )
                    .putExtra(UsbOutputRebuildSessionCommand.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(
                        UsbOutputRebuildSessionCommand.EXTRA_GENERATION,
                        result.generation,
                    ),
            )
        }
    }

    private fun scheduleDirectDsdPrototypeRebuild(args: Bundle) {
        val enabled = args.getBoolean(DirectDsdPrototypeSessionCommand.EXTRA_ENABLED, false)
        mainHandler.post {
            val previous = runCatching { DirectDsdPrototypeControl.isEnabled(this) }.getOrDefault(false)
            var resultCode = SessionError.ERROR_UNKNOWN
            var generation = -1L
            if (activeOutputPath.outputMode == PlaybackOutputMode.SharedPcm &&
                (!enabled || UsbOutputRuntime.owner.facts.phase == com.mica.music.media.usb.UsbOutputPhase.IDLE)
            ) {
                val rebuilt = runCatching {
                    DirectDsdPrototypeControl.setEnabled(this, enabled)
                    outputRebuildCoordinator.rebuild(activeOutputPath)
                }.getOrElse {
                    runCatching { DirectDsdPrototypeControl.setEnabled(this, previous) }
                    null
                }
                if (rebuilt != null) {
                    generation = rebuilt.generation
                    resultCode = if (rebuilt is PlaybackOutputRebuildResult.Published) {
                        SessionResult.RESULT_SUCCESS
                    } else {
                        runCatching { DirectDsdPrototypeControl.setEnabled(this, previous) }
                        SessionError.ERROR_UNKNOWN
                    }
                }
            }
            DiagnosticLog.event(
                "DirectDsdPrototype",
                "rebuild enabled=$enabled previous=$previous resultCode=$resultCode generation=$generation output=${activeOutputPath.outputMode}",
            )
            sendBroadcast(
                Intent(DirectDsdPrototypeSessionCommand.resultAction(packageName))
                    .setPackage(packageName)
                    .putExtra(DirectDsdPrototypeSessionCommand.EXTRA_ENABLED, enabled)
                    .putExtra(DirectDsdPrototypeSessionCommand.EXTRA_RESULT_CODE, resultCode),
            )
        }
    }

    private fun restoreInterruptedUsbAfterExactReproof(
        runtimeHandle: com.mica.music.media.usb.UsbAudioRuntimeHandle,
        permissionGeneration: Long,
    ) {
        val facts = UsbOutputRuntime.owner.facts
        if (facts.generation != permissionGeneration ||
            facts.runtimeHandle != runtimeHandle ||
            facts.permission != com.mica.music.media.usb.UsbPermissionState.GRANTED
        ) {
            DiagnosticLog.event(
                "UsbOutputLifecycle",
                "reconnect post-grant-reproof=blocked reason=stale-permission " +
                    "permissionGeneration=$permissionGeneration device=${runtimeHandle.runtimeDeviceId}",
            )
            return
        }
        val expectedIdentity = UsbProvenReconnectTargetRuntime.expectedIdentityForInterruptedRecovery(
            UsbOutputLifecycleRuntime.hasInterruptedUsbIntent(),
        )
        if (expectedIdentity == null) {
            DiagnosticLog.event(
                "UsbOutputLifecycle",
                "reconnect post-grant-reproof=blocked reason=missing-proven-stable-identity",
            )
            return
        }
        val manager = getSystemService(android.hardware.usb.UsbManager::class.java)
        when (
            val decision = com.mica.music.media.usb.UsbReconnectPostGrantProofGate.reproveAndDecide(
                runtimeHandle,
            ) {
                com.mica.music.media.usb.AndroidUsbStableReconnectResolver.resolve(
                    manager,
                    expectedIdentity,
                )
            }
        ) {
            is com.mica.music.media.usb.UsbReconnectPostGrantDecision.Restore -> {
                DiagnosticLog.event(
                    "UsbOutputLifecycle",
                    "reconnect post-grant-reproof=resolved permissionGeneration=$permissionGeneration " +
                        "runtimeDeviceId=${runtimeHandle.runtimeDeviceId} " +
                        "bcdDevice=${decision.resolved.identity.bcdDevice}",
                )
                restoreUsbAfterGrantedPermission(runtimeHandle, permissionGeneration)
            }
            is com.mica.music.media.usb.UsbReconnectPostGrantDecision.DoNotRestore -> {
                DiagnosticLog.event(
                    "UsbOutputLifecycle",
                    "reconnect post-grant-reproof=blocked reason=${decision.rejection} " +
                        "resolution=${decision.resolution.javaClass.simpleName} " +
                        "permissionGeneration=$permissionGeneration device=${runtimeHandle.runtimeDeviceId}",
                )
            }
        }
    }

    private fun restoreUsbAfterGrantedPermission(
        runtimeHandle: com.mica.music.media.usb.UsbAudioRuntimeHandle,
        permissionGeneration: Long,
    ) {
        UsbOutputLifecycleRuntime.publishGrantedPermission(runtimeHandle, permissionGeneration) { intent ->
            val result = outputRebuildCoordinator.rebuild(
                UsbHostOutputPreferences.pathForEnabled(true),
            ) { snapshot ->
                snapshot.copy(playWhenReady = intent.resumePlaybackRequested)
            }
            DiagnosticLog.event(
                "UsbOutputLifecycle",
                "attach restore=${result.javaClass.simpleName} permissionGeneration=$permissionGeneration " +
                    "device=${runtimeHandle.runtimeDeviceId}",
            )
            result is PlaybackOutputRebuildResult.Published
        }
    }

    private fun scheduleUsbRecoveryFromDebug(trigger: UsbRecoveryTrigger) {
        mainHandler.post { executeUsbRecovery(trigger) }
    }

    private fun executeAutomaticUsbRecovery(decision: UsbHealthRecoveryDecision) {
        executeUsbRecovery(decision.trigger, decision)
    }

    private fun executeUsbRecovery(
        trigger: UsbRecoveryTrigger,
        expectedHealth: UsbHealthRecoveryDecision? = null,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "USB recovery must execute on the main looper"
        }
        val facts = UsbOutputRuntime.owner.facts
        if ((expectedHealth != null && !expectedHealth.matches(facts)) ||
            facts.phase != UsbOutputPhase.ACTIVE ||
            activeOutputPath.outputMode != PlaybackOutputMode.UsbDirectPcm
        ) {
            publishUsbRecoveryResult(trigger, "not-active-or-stale")
            return
        }
        val epoch = usbRecoveryEpoch ?: usbRecoveryCoordinator
            .beginEpoch(facts.generation)
            .also {
                usbRecoveryEpoch = it
                usbRecoveryRequest = facts.request
                usbRecoveryFallbackAttempted = false
            }
        continueUsbRecovery(epoch, trigger)
    }

    private fun continueUsbRecovery(
        epoch: UsbRecoveryEpoch,
        trigger: UsbRecoveryTrigger,
    ) {
        if (usbRecoveryEpoch != epoch ||
            activeOutputPath.outputMode != PlaybackOutputMode.UsbDirectPcm
        ) {
            publishUsbRecoveryResult(trigger, "stale-epoch")
            return
        }
        when (val request = usbRecoveryCoordinator.requestFreshOpen(epoch, trigger)) {
            is UsbRecoveryRequestResult.Issued -> {
                val injectedFailure = if (BuildConfig.DEBUG) {
                    UsbRecoveryFailureInjectionRuntime.consume()
                } else {
                    null
                }
                if (injectedFailure != null) {
                    DiagnosticLog.event(
                        "UsbRecovery",
                        "injected-fresh-open-failure generation=${injectedFailure.generation} " +
                            "attempt=${injectedFailure.attempt} " +
                            "remaining=${injectedFailure.remainingFailures}",
                    )
                    acknowledgeUsbRecoveryFailure(epoch, request.action, "injected-failure")
                    return
                }

                val rebuild = outputRebuildCoordinator.rebuild(activeOutputPath)
                if (rebuild !is PlaybackOutputRebuildResult.Published) {
                    acknowledgeUsbRecoveryFailure(epoch, request.action, "rebuild-failed")
                    return
                }

                usbRecoveryActivationExpectation = UsbRecoveryActivationExpectation(
                    action = request.action,
                    expectedRequest = usbRecoveryRequest,
                    requireFrameProgress = usbResumePlaybackRequested,
                    deadlineElapsedRealtimeMs =
                        SystemClock.elapsedRealtime() + USB_RECOVERY_ACTIVATION_TIMEOUT_MS,
                )
                publishUsbRecoveryResult(
                    trigger = trigger,
                    result = "activation-waiting",
                    actionId = request.action.actionId,
                    attempt = request.action.attempt,
                )
                reconcilePendingUsbRecoveryActivation()
            }
            is UsbRecoveryRequestResult.AwaitingAck -> publishUsbRecoveryResult(
                trigger,
                "awaiting-ack",
                request.action.actionId,
                request.action.attempt,
            )
            is UsbRecoveryRequestResult.BackingOff -> scheduleUsbRecoveryRetry(
                epoch,
                trigger,
                request.retryAfterMs,
            )
            is UsbRecoveryRequestResult.BudgetExhausted -> {
                publishUsbRecoveryResult(
                    trigger,
                    "budget-exhausted",
                    attempt = request.attempts,
                )
                if (!usbRecoveryFallbackAttempted) {
                    usbRecoveryFallbackAttempted = true
                    fallbackToSharedPcm(epoch, trigger, request.attempts)
                }
            }
            UsbRecoveryRequestResult.Resolved -> publishUsbRecoveryResult(trigger, "resolved")
            UsbRecoveryRequestResult.StaleEpoch -> publishUsbRecoveryResult(
                trigger,
                "stale-epoch",
            )
        }
    }

    private fun reconcilePendingUsbRecoveryActivation() {
        val expectation = usbRecoveryActivationExpectation ?: return
        val epoch = usbRecoveryEpoch
        if (epoch == null || epoch != expectation.action.epoch) {
            usbRecoveryActivationExpectation = null
            return
        }
        if (activeOutputPath.outputMode != PlaybackOutputMode.UsbDirectPcm) {
            usbRecoveryActivationExpectation = null
            publishUsbRecoveryResult(
                expectation.action.trigger,
                "activation-stale-output-path",
                expectation.action.actionId,
                expectation.action.attempt,
            )
            cancelUsbRecovery()
            return
        }

        when (
            UsbRecoveryActivationPolicy.evaluate(
                expectation = expectation,
                facts = UsbOutputRuntime.owner.facts,
                elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            )
        ) {
            UsbRecoveryActivationState.WAITING -> Unit
            UsbRecoveryActivationState.STALE -> {
                usbRecoveryActivationExpectation = null
                publishUsbRecoveryResult(
                    expectation.action.trigger,
                    "activation-stale-session",
                    expectation.action.actionId,
                    expectation.action.attempt,
                )
                cancelUsbRecovery()
            }
            UsbRecoveryActivationState.FAILED -> {
                acknowledgeUsbRecoveryFailure(epoch, expectation.action, "activation-failed")
            }
            UsbRecoveryActivationState.SUCCEEDED -> {
                usbRecoveryActivationExpectation = null
                val acknowledged = usbRecoveryCoordinator.acknowledge(
                    expectation.action,
                    UsbRecoveryAckOutcome.SUCCEEDED,
                )
                publishUsbRecoveryResult(
                    trigger = expectation.action.trigger,
                    result = if (acknowledged) "succeeded" else "stale-ack",
                    actionId = expectation.action.actionId,
                    attempt = expectation.action.attempt,
                )
                if (acknowledged) cancelUsbRecovery()
            }
        }
    }

    private fun acknowledgeUsbRecoveryFailure(
        epoch: UsbRecoveryEpoch,
        action: UsbRecoveryAction,
        result: String,
    ) {
        usbRecoveryActivationExpectation = null
        val acknowledged = usbRecoveryCoordinator.acknowledge(
            action,
            UsbRecoveryAckOutcome.FAILED,
        )
        publishUsbRecoveryResult(
            trigger = action.trigger,
            result = if (acknowledged) result else "stale-ack",
            actionId = action.actionId,
            attempt = action.attempt,
        )
        if (acknowledged && usbRecoveryEpoch == epoch) {
            continueUsbRecovery(epoch, action.trigger)
        }
    }

    private fun scheduleUsbRecoveryRetry(
        epoch: UsbRecoveryEpoch,
        trigger: UsbRecoveryTrigger,
        delayMs: Long,
    ) {
        usbRecoveryRetry?.let(mainHandler::removeCallbacks)
        val retry = Runnable {
            usbRecoveryRetry = null
            continueUsbRecovery(epoch, trigger)
        }
        usbRecoveryRetry = retry
        mainHandler.postDelayed(retry, delayMs)
        publishUsbRecoveryResult(trigger, "backing-off-${delayMs}ms")
    }

    private fun fallbackToSharedPcm(
        epoch: UsbRecoveryEpoch,
        trigger: UsbRecoveryTrigger,
        attempts: Int,
    ) {
        if (usbRecoveryEpoch != epoch ||
            activeOutputPath.outputMode != PlaybackOutputMode.UsbDirectPcm
        ) {
            publishUsbRecoveryResult(trigger, "fallback-stale", attempt = attempts)
            return
        }
        val rebuild = outputRebuildCoordinator.rebuild(AudioOutputPathConfig.PRODUCTION)
        if (rebuild !is PlaybackOutputRebuildResult.Published ||
            usbRecoveryEpoch != epoch ||
            activeOutputPath.outputMode != PlaybackOutputMode.SharedPcm
        ) {
            publishUsbRecoveryResult(trigger, "fallback-failed", attempt = attempts)
            return
        }
        val gateCommitted = runCatching { UsbHostOutputPreferences.setEnabled(this, false) }.isSuccess
        val factsPublished = UsbOutputRuntime.owner.publishFallbackToSharedPcm(
            request = usbRecoveryRequest,
            stage = "recovery-exhausted",
            message = "USB recovery exhausted after $attempts attempts",
        )
        cancelUsbRecovery()
        publishUsbRecoveryResult(
            trigger,
            when {
                !gateCommitted -> "fallback-succeeded-gate-commit-failed"
                !factsPublished -> "fallback-succeeded-facts-stale"
                else -> "fallback-succeeded"
            },
            attempt = attempts,
        )
    }

    private fun cancelUsbRecovery() {
        usbRecoveryRetry?.let(mainHandler::removeCallbacks)
        usbRecoveryRetry = null
        usbRecoveryEpoch = null
        usbRecoveryRequest = null
        usbRecoveryActivationExpectation = null
        usbRecoveryFallbackAttempted = false
        UsbRecoveryFailureInjectionRuntime.clear()
    }

    private fun publishUsbRecoveryResult(
        trigger: UsbRecoveryTrigger,
        result: String,
        actionId: Long = -1L,
        attempt: Int = 0,
    ) {
        DiagnosticLog.event(
            "UsbRecovery",
            "trigger=$trigger result=$result actionId=$actionId attempt=$attempt",
        )
        sendBroadcast(
            Intent(UsbRecoveryDebugCommand.resultAction(packageName))
                .setPackage(packageName)
                .putExtra(UsbRecoveryDebugCommand.EXTRA_TRIGGER, trigger.name)
                .putExtra(UsbRecoveryDebugCommand.EXTRA_RESULT, result)
                .putExtra(UsbRecoveryDebugCommand.EXTRA_ACTION_ID, actionId)
                .putExtra(UsbRecoveryDebugCommand.EXTRA_ATTEMPT, attempt),
        )
    }

    private companion object {
        const val USB_HEALTH_POLL_INTERVAL_MS = 1_000L
        const val USB_RECOVERY_ACTIVATION_TIMEOUT_MS = 5_000L
    }

    private fun handleDebugPlaybackControl(
        control: DebugPlaybackControl,
        mediaIndex: Int,
    ): DebugPlaybackControlResult {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Debug playback control must run on the main looper"
        }
        val player = checkNotNull(compositePlayer) { "Playback stack is not active" }
        when (control) {
            DebugPlaybackControl.PLAY -> {
                player.prepare()
                player.play()
            }
            DebugPlaybackControl.PAUSE -> player.pause()
            DebugPlaybackControl.NEXT -> {
                player.seekToNextMediaItem()
                player.play()
            }
            DebugPlaybackControl.SELECT_INDEX -> {
                check(mediaIndex in 0 until player.mediaItemCount) {
                    "mediaIndex=$mediaIndex itemCount=${player.mediaItemCount}"
                }
                player.seekToDefaultPosition(mediaIndex)
                player.play()
            }
            DebugPlaybackControl.SEEK_NEAR_END -> {
                val duration = player.duration
                check(duration != C.TIME_UNSET && duration > 0L) {
                    "Current media duration is unavailable"
                }
                player.seekTo((duration - 5_000L).coerceAtLeast(0L))
                player.play()
            }
            DebugPlaybackControl.REPEAT_ONE -> player.repeatMode = Player.REPEAT_MODE_ONE
            DebugPlaybackControl.REPEAT_OFF -> player.repeatMode = Player.REPEAT_MODE_OFF
        }
        return DebugPlaybackControlResult(
            currentIndex = player.currentMediaItemIndex,
            currentPositionMs = player.currentPosition,
            durationMs = player.duration,
        )
    }

    /** Main-thread publication seam for every player-scoped service reference and observer. */
    private fun publishRebuiltPlaybackStack(
        target: AudioOutputPathConfig,
        snapshot: PlaybackStackSnapshot,
        candidate: ExoPlaybackStack,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Playback stack publication must run on the main looper"
        }
        DiagnosticLog.event(
            "UsbOutputRebuild",
            "barrier=activate target=${target.outputMode}",
        )
        snapshot.activate(candidate.compositePlayer, resumePlayback = false)
        checkNotNull(mediaSession) { "MediaSession is not active" }
            .setPlayer(candidate.compositePlayer)
        candidate.compositePlayer.playWhenReady = snapshot.playWhenReady
        exoPlayer = candidate.exoPlayer
        compositePlayer = candidate.compositePlayer
        activeOutputPath = target
        installPlayerScopedBindings(candidate)
        val expectedIndex = snapshot.currentIndex
            .takeIf { snapshot.mediaItems.indices.contains(it) }
        val expectedItem = expectedIndex?.let(snapshot.mediaItems::get)
        val adopted = if (expectedIndex != null && expectedItem != null) {
            playbackEngineCoordinator?.adoptPreparedRebuildRequest(expectedIndex, expectedItem)
        } else {
            null
        }
        DiagnosticLog.event(
            "UsbOutputRebuild",
            "prepared-request-adoption adopted=${adopted != null} " +
                "index=${expectedIndex ?: -1} mediaId=${expectedItem?.mediaId ?: "none"}",
        )
        installUsbPlaybackIntentObserver(candidate.compositePlayer)
    }

    /** Break-before-make barrier: no candidate renderer may activate before this returns. */
    private fun retirePublishedPlaybackStack() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Playback stack retirement must run on the main looper"
        }
        val previousExo = checkNotNull(exoPlayer)
        val previousComposite = checkNotNull(compositePlayer)
        val previousMode = activeOutputPath.outputMode
        DiagnosticLog.event(
            "UsbOutputRebuild",
            "barrier=retire-start from=$previousMode",
        )
        previousComposite.onPlaybackIntentChanged = null
        previousComposite.playWhenReady = false
        releasePlayerScopedBindings(previousExo)
        try {
            previousExo.release()
        } catch (error: Throwable) {
            DiagnosticLog.event(
                "UsbOutputRebuild",
                "old-player-release-failed error=${error.javaClass.simpleName}:${error.message}",
            )
            throw error
        }
        exoPlayer = null
        compositePlayer = null
        DiagnosticLog.event(
            "UsbOutputRebuild",
            "barrier=retire-complete from=$previousMode usbPhase=${UsbOutputRuntime.owner.facts.phase}",
        )
    }

    private fun installUsbPlaybackIntentObserver(player: MicaCompositePlayer) {
        player.onPlaybackIntentChanged = { resumePlaybackRequested ->
            usbResumePlaybackRequested = resumePlaybackRequested
            playbackStateCoordinator?.onExplicitPlaybackIntent(resumePlaybackRequested)
            if (!resumePlaybackRequested) UsbOutputLifecycleRuntime.clearRecovery()
        }
    }

    private fun releasePlayerScopedBindings(previousExo: ExoPlayer) {
        bindingStep("old-replay-gain-release") { replayGainStateOwner?.release() }
        replayGainStateOwner = null
        bindingStep("old-state-release") { playbackStateCoordinator?.release() }
        playbackStateCoordinator = null
        bindingStep("old-notification-release") { notificationLyricsCoordinator?.release() }
        notificationLyricsCoordinator = null
        bindingStep("old-car-bluetooth-release") { carBluetoothLyricsSession?.release() }
        carBluetoothLyricsSession = null
        bindingStep("old-engine-release") { playbackEngineCoordinator?.release() }
        playbackEngineCoordinator = null
        bindingStep("old-offload-release") {
            audioOffloadCircuitBreaker?.let { breaker ->
                previousExo.removeListener(breaker)
                previousExo.removeAudioOffloadListener(breaker)
                breaker.release()
            }
        }
        audioOffloadCircuitBreaker = null
        audioPipelineCoordinator = null
    }

    private fun installPlayerScopedBindings(stack: ExoPlaybackStack) {
        val micaApp = application as MicaApp
        bindingStep("new-replay-gain-install") {
            replayGainStateOwner = ReplayGainStateOwner(this, stack.compositePlayer)
                .also { it.start() }
        }
        bindingStep("new-offload-install") { installAudioOffloadCircuitBreaker(stack.exoPlayer) }
        bindingStep("new-pipeline-install") { installAudioPipelineCoordinator(stack.exoPlayer) }
        bindingStep("new-equalizer-session-install") {
            attachEqualizerSessionListener(stack.exoPlayer)
        }
        bindingStep("new-engine-install") {
            playbackEngineCoordinator = ServicePlaybackEngineCoordinator(
                player = stack.compositePlayer,
                context = this,
            ).also { coordinator ->
                coordinator.start()
                coordinator.onPlaybackBoundary = { boundary ->
                    mediaSession?.broadcastCustomCommand(
                        PlaybackBoundarySessionEvent.command,
                        PlaybackBoundarySessionEvent.encode(boundary),
                    )
                }
            }
        }
        bindingStep("new-state-install") {
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
                restorePersistedState = false,
            ).also { it.start() }
        }
        bindingStep("new-car-bluetooth-install") {
            carBluetoothLyricsSession = CarBluetoothLyricsSession(
                context = this,
                player = stack.compositePlayer,
                sessionActivity = createSessionActivityPendingIntent(),
            )
        }
        bindingStep("new-notification-install") {
            notificationLyricsCoordinator = NotificationLyricsCoordinator(
                context = this,
                player = stack.compositePlayer,
                handler = mainHandler,
                carBluetoothLyrics = carBluetoothLyricsSession,
                desktopLyrics = micaApp.desktopLyricsOverlayStateStore,
                transientSongResolver = micaApp.transientPlaybackCatalog::songById,
            ).also { it.start() }
        }
    }

    private inline fun bindingStep(stage: String, block: () -> Unit) {
        runCatching(block).onFailure { error ->
            DiagnosticLog.event(
                "UsbOutputRebuild",
                "$stage failed error=${error.javaClass.simpleName}:${error.message}",
            )
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
