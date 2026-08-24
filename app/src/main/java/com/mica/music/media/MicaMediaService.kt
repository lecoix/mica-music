package com.mica.music.media

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.Toast
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
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
import com.mica.music.isExternalAudioUriRestorableNow
import com.mica.music.data.TransientPlaybackCatalog
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.preferences.AudioOffloadPreferences
import com.mica.music.data.preferences.EqualizerPreferences
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.data.preferences.UsbHybridOutputMode
import com.mica.music.data.preferences.UsbHybridPreferences
import com.mica.music.data.preferences.UsbSharedReturnCapability
import com.mica.music.data.preferences.UsbSharedReturnCapabilityStore
import com.mica.music.data.preferences.UsbSharedReturnPolicy
import com.mica.music.media.usbhybrid.DesiredUsbOutput
import com.mica.music.media.usbhybrid.PermissionState
import com.mica.music.media.usbhybrid.UsbDeviceCandidate
import com.mica.music.media.usbhybrid.UsbOutputEffect
import com.mica.music.media.usbhybrid.UsbOutputEvent
import com.mica.music.media.usbhybrid.UsbOutputPhase
import com.mica.music.media.usbhybrid.UsbOutputState
import com.mica.music.media.usbhybrid.UsbOutputStateMachine
import com.mica.music.media.usbhybrid.UsbPermissionRequest
import com.mica.music.media.usbhybrid.UsbPermissionResult
import com.mica.music.media.usbhybrid.UsbRequestEpoch
import com.mica.music.media.usbhybrid.UsbRealtimeResult
import com.mica.music.media.usbhybrid.UsbRuntimeHandle
import com.mica.music.media.usbhybrid.UsbStreamFormat
import com.mica.music.media.usbhybrid.UsbTransportSessionId
import com.mica.music.media.usbhybrid.AndroidUsbHybridControlEffects
import com.mica.music.media.usbhybrid.UsbAudioSelection
import com.mica.music.media.usbhybrid.UsbActiveTransport
import com.mica.music.media.usbhybrid.UsbExclusiveMode
import com.mica.music.media.usbhybrid.UsbHybridPlaybackBinding
import com.mica.music.media.usbhybrid.UsbHybridSessionOwner
import com.mica.music.media.usbhybrid.UsbHybridRuntimeMonitor
import com.mica.music.media.usbhybrid.UsbPlaybackFacts
import com.mica.music.media.usbhybrid.UsbStableIdentity
import com.mica.music.media.usbhybrid.UsbSharedQuiescencePolicyResolver
import com.mica.music.media.usbhybrid.UsbTopologyEvent
import com.mica.music.util.DiagnosticLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest

/**
 * Playback service owns ExoPlayer + MediaSession independently from Activity lifecycle.
 */
private data class PlaybackStackHandoff(
    val items: List<MediaItem>,
    val currentIndex: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val repeatMode: Int,
    val playbackParameters: PlaybackParameters,
    val volume: Float,
)

@UnstableApi
class MicaMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var compositePlayer: MicaCompositePlayer? = null
    private var replayGainStateOwner: ReplayGainStateOwner? = null
    private var spectrumAnalyzerStateOwner: SpectrumAnalyzerStateOwner? = null
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
    private var unregisterUsbOutputPreferenceListener: (() -> Unit)? = null
    private var playbackRouteMonitor: PlaybackRouteMonitor? = null
    private var audioOffloadCircuitBreaker: AudioOffloadCircuitBreaker? = null
    private var audioPipelineCoordinator: AudioPipelineCoordinator? = null
    private var usbEffects: AndroidUsbHybridControlEffects? = null
    private var usbOwner: UsbHybridSessionOwner? = null
    private var usbFactsJob: Job? = null
    private var activeUsbEpoch: Long? = null
    private var usbOutputState = UsbOutputState()
    private var usbOutputHandoff: PlaybackStackHandoff? = null
    private var usbServiceCreateBootstrapMode: UsbHybridOutputMode? = null
    private var usbOutputCandidate: UsbDeviceCandidate? = null
    private var usbOutputOwnerEpoch: UsbRequestEpoch? = null
    private var usbOutputSwitchReason: String = "service-create"
    private var usbProbeGeneration: Long = -1L
    private var usbProbePollAttempts: Int = 0
    private var usbProbeStableObservations: Int = 0
    private var usbProbeIdentity: UsbStableIdentity? = null
    private var usbProbeRuntimeHandle: UsbRuntimeHandle? = null
    private var usbSharedQuiesceStartedAtMs: Long = 0L
    private var usbSharedQuiesceSettleMs: Long = 0L
    private var usbSharedRoutePollGeneration: Long = -1L
    private var usbSharedRouteProbeStartedAtMs: Long = 0L
    private var usbSharedAudioAddSerial: Long = 0L
    private var usbSharedRouteWaitBaselineAddSerial: Long = 0L
    private var usbSharedReturnProbeIdentity: UsbStableIdentity? = null
    private var usbSharedAudioDeviceCallbackRegistered: Boolean = false
    private val usbSharedAudioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val usbDevice = addedDevices.firstOrNull(::isAndroidUsbAudioOutput) ?: return
            usbSharedAudioAddSerial += 1
            DiagnosticLog.event(
                "UsbOutputState",
                "shared-audio-device-added serial=$usbSharedAudioAddSerial deviceId=${usbDevice.id} " +
                    "phase=${usbOutputState.phase}",
            )
            when (usbOutputState.phase) {
                UsbOutputPhase.SharedRouteWaiting -> {
                    if (usbSharedAudioAddSerial > usbSharedRouteWaitBaselineAddSerial) {
                        completeSharedRouteRecovery()
                    }
                }
                UsbOutputPhase.SharedReconnectRequired -> {
                    dispatchUsbOutput(UsbOutputEvent.UsbAttached)
                    if (usbOutputState.phase == UsbOutputPhase.SharedRouteWaiting) {
                        completeSharedRouteRecovery()
                    }
                }
                else -> Unit
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.none(::isAndroidUsbAudioOutput)) return
            DiagnosticLog.event(
                "UsbOutputState",
                "shared-audio-device-removed phase=${usbOutputState.phase}",
            )
        }
    }
    private val usbTelemetrySampler = object : Runnable {
        override fun run() {
            val owner = usbOwner ?: return
            val effects = usbEffects ?: return
            owner.refreshTelemetry(effects)
            mainHandler.postDelayed(this, USB_TELEMETRY_INTERVAL_MS)
        }
    }

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

        installUsbHybridOwner()
        activeOutputPath = AudioOutputPathConfig.PRODUCTION
        val stack = ExoPlaybackStackFactory.build(this, activeOutputPath)
        installPlaybackStackOwners(stack, micaApp)
        installPlaybackRouteMonitor()
        installUsbSharedAudioDeviceCallback()

        if (LyricsPreferences.externalLyricsMode(this) != com.mica.music.data.ExternalLyricsMode.OFF &&
            DesktopLyricsOverlayController.canDrawOverlays(this)
        ) {
            DesktopLyricsOverlayController.start(this)
        }

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
        unregisterUsbOutputPreferenceListener =
            UsbHybridPreferences.registerChangeListener(this) { mode ->
                mainHandler.post {
                    if (usbServiceCreateBootstrapMode != null) {
                        usbServiceCreateBootstrapMode = mode
                    } else {
                        applyUsbOutputMode(mode, "preference")
                    }
                }
            }
        applyUsbOutputModeOnServiceCreate(
            UsbHybridPreferences.outputMode(this),
            libraryRepository,
            micaApp,
        )
        mainHandler.postDelayed(usbTelemetrySampler, USB_TELEMETRY_INTERVAL_MS)

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

    private fun installUsbHybridOwner() {
        lateinit var owner: UsbHybridSessionOwner
        val effects = AndroidUsbHybridControlEffects(
            context = this,
            permissionResultSink = { result -> mainHandler.post { onUsbPermissionResult(result) } },
            topologyEventSink = { event -> mainHandler.post { onUsbTopologyEvent(event) } },
        )
        owner = UsbHybridSessionOwner(
            effects = effects,
            factsPublisher = UsbHybridRuntimeMonitor::publishFromOwner,
        )
        usbEffects = effects
        usbOwner = owner
        usbFactsJob = sessionScope?.launch {
            owner.facts.collectLatest { facts ->
                mainHandler.post { handleUsbFacts(facts) }
            }
        }
    }

    private fun applyUsbOutputModeOnServiceCreate(
        mode: UsbHybridOutputMode,
        libraryRepository: LibraryRepository,
        micaApp: MicaApp,
    ) {
        if (mode == UsbHybridOutputMode.SharedPcm) {
            applyUsbOutputMode(mode, "service-create")
            return
        }
        val snapshot = ServicePlaybackStateStore(this).load()
        if (snapshot == null || snapshot.queueSongIds.isEmpty()) {
            applyUsbOutputMode(mode, "service-create")
            return
        }

        usbServiceCreateBootstrapMode = mode
        sessionScope?.launch {
            val libraryIds = snapshot.queueSongIds
                .filterNot(TransientPlaybackCatalog::isTransientId)
                .distinct()
            val librarySongs = runCatching {
                libraryRepository.songSummariesByIds(libraryIds)
            }.getOrDefault(emptyMap())
            val persistedExternalSongs = snapshot.externalSongs
                .mapNotNull { external ->
                    val uri = runCatching { android.net.Uri.parse(external.mediaUri) }.getOrNull()
                    if (uri != null && isExternalAudioUriRestorableNow(this@MicaMediaService, uri)) {
                        external.id to external.toSong()
                    } else {
                        null
                    }
                }
                .toMap()
            val songsById = buildMap {
                putAll(librarySongs)
                putAll(persistedExternalSongs)
                snapshot.queueSongIds.forEach { id ->
                    micaApp.transientPlaybackCatalog.songById(id)?.let { put(id, it) }
                }
            }
            val bootstrap = ServicePlaybackBootstrapResolver.resolve(snapshot, songsById)
            mainHandler.post {
                val selectedMode = usbServiceCreateBootstrapMode
                    ?: UsbHybridPreferences.outputMode(this@MicaMediaService)
                usbServiceCreateBootstrapMode = null
                if (selectedMode != UsbHybridOutputMode.SharedPcm && bootstrap != null) {
                    usbOutputHandoff = PlaybackStackHandoff(
                        items = bootstrap.songs.map(SongMediaItemCodec::encode),
                        currentIndex = bootstrap.currentIndex,
                        positionMs = bootstrap.positionMs,
                        playWhenReady = false,
                        repeatMode = bootstrap.repeatMode,
                        playbackParameters = bootstrap.playbackTuning.toPlaybackParameters(),
                        volume = compositePlayer?.volume ?: 1f,
                    )
                    DiagnosticLog.event(
                        "PlaybackRestore",
                        "usb service-create bootstrap items=${bootstrap.songs.size} " +
                            "index=${bootstrap.currentIndex} positionMs=${bootstrap.positionMs} resumed=false",
                    )
                }
                applyUsbOutputMode(selectedMode, "service-create")
            }
        } ?: run {
            usbServiceCreateBootstrapMode = null
            applyUsbOutputMode(mode, "service-create")
        }
    }

    private fun applyUsbOutputMode(mode: UsbHybridOutputMode, reason: String) {
        val desired = mode.toDesiredUsbOutput()
        val phase = usbOutputState.phase
        if (
            desired == usbOutputState.desiredMode &&
            ((desired == DesiredUsbOutput.Shared && phase == UsbOutputPhase.SharedActive && exoPlayer != null) ||
                (phase is UsbOutputPhase.ExclusiveActive && phase.mode == desired))
        ) {
            return
        }

        val handoff = capturePlaybackStackHandoff() ?: usbOutputHandoff
        if (handoff != null) usbOutputHandoff = handoff
        val playWhenReady = usbOutputState.frozenIntent?.playWhenReady
            ?: handoff?.playWhenReady
            ?: compositePlayer?.playWhenReady
            ?: false
        val ownerFacts = usbOwner?.facts?.value
        val returningFromExclusive = desired == DesiredUsbOutput.Shared &&
            (usbOutputState.desiredMode != DesiredUsbOutput.Shared ||
                activeOutputPath.outputMode != PlaybackOutputMode.SharedPcm ||
                ownerFacts?.activeMode != null || ownerFacts?.exclusive == true)
        val identity = ownerFacts?.identity ?: usbOutputCandidate?.identity
        val returnIdentity = identity ?: usbSharedReturnProbeIdentity
        val returnCapability = returnIdentity?.let { UsbSharedReturnCapabilityStore.capability(this, it) }
        val reconnectRequired = returningFromExclusive &&
            UsbSharedReturnPolicy.requiresPhysicalReconnect(
                returnCapability ?: com.mica.music.data.preferences.UsbSharedReturnCapability.Unknown,
            )
        usbSharedReturnProbeIdentity = when {
            returningFromExclusive -> returnIdentity
            desired != DesiredUsbOutput.Shared -> identity ?: usbSharedReturnProbeIdentity
            else -> usbSharedReturnProbeIdentity
        }
        if (returningFromExclusive) {
            usbSharedRouteWaitBaselineAddSerial = usbSharedAudioAddSerial
            DiagnosticLog.event(
                "UsbOutputState",
                "shared-return capability=${returnCapability ?: UsbSharedReturnCapability.Unknown} " +
                    "identity=${returnIdentity?.vendorId}:${returnIdentity?.productId} reconnect=$reconnectRequired " +
                    "audioAddBaseline=$usbSharedRouteWaitBaselineAddSerial",
            )
        }
        usbOutputSwitchReason = reason
        dispatchUsbOutput(
            UsbOutputEvent.UserSelected(
                mode = desired,
                playWhenReady = playWhenReady,
                sharedReturnRequiresReconnect = reconnectRequired,
            ),
        )
    }

    private fun dispatchUsbOutput(event: UsbOutputEvent) {
        val before = usbOutputState
        val reduction = UsbOutputStateMachine.reduce(before, event)
        usbOutputState = reduction.state
        DiagnosticLog.event(
            "UsbOutputState",
            "event=${event.javaClass.simpleName} generation=${usbOutputState.generation} " +
                "desired=${usbOutputState.desiredMode} phase=${before.phase}->${usbOutputState.phase}",
        )
        reduction.effects.forEach(::executeUsbOutputEffect)
    }

    private fun executeUsbOutputEffect(effect: UsbOutputEffect) {
        when (effect) {
            UsbOutputEffect.RetirePlayer -> {
                if (usbOutputState.phase == UsbOutputPhase.SharedQuiescing) {
                    DiagnosticLog.event(
                        "UsbOutputState",
                        "shared-quiesce retire generation=${usbOutputState.generation} audioSessionId=${exoPlayer?.audioSessionId ?: 0}",
                    )
                }
                if (exoPlayer == null) {
                    if (usbOutputState.phase == UsbOutputPhase.SharedQuiescing) {
                        dispatchUsbOutput(UsbOutputEvent.SharedQuiesced(usbOutputState.generation))
                    }
                    return
                }
                runCatching { retirePlaybackStackBeforeUsbRequest() }
                    .onFailure { error ->
                        DiagnosticLog.event("UsbOutputState", "retire-player failed error=${error.message}")
                        if (
                            usbOutputState.phase == UsbOutputPhase.ExclusivePreparing ||
                            usbOutputState.phase == UsbOutputPhase.SharedQuiescing
                        ) {
                            dispatchUsbOutput(
                                UsbOutputEvent.PreparationFailed(
                                    usbOutputState.generation,
                                    "STACK_RELEASE_FAILED",
                                    error.message ?: "Old playback stack did not release cleanly.",
                                ),
                            )
                        }
                    }
            }
            UsbOutputEffect.WaitForSharedQuiescence -> scheduleSharedQuiescenceProbe(usbOutputState.generation)
            UsbOutputEffect.WaitForTarget -> scheduleExclusiveTargetProbe(usbOutputState.generation)
            UsbOutputEffect.RequestPermission -> requestUsbPermissionForState()
            UsbOutputEffect.OpenExclusive -> openExclusiveForState()
            UsbOutputEffect.RetargetExclusive -> retargetExclusiveForState()
            UsbOutputEffect.CloseExclusive -> closeExclusiveForState()
            UsbOutputEffect.RestoreFrozenPlaybackIntent -> restoreFrozenPlaybackIntentForState()
            UsbOutputEffect.WaitForSharedRoute -> scheduleSharedRouteProbe(usbOutputState.generation)
            UsbOutputEffect.BuildSharedPlayer -> buildSharedPlayerForState()
            UsbOutputEffect.ShowReconnectRequired -> Toast.makeText(
                this,
                "USB \u72ec\u5360\u5df2\u5173\u95ed\uff0c\u8bf7\u91cd\u65b0\u63d2\u62d4 DAC \u4ee5\u6062\u590d Android \u5171\u4eab\u8f93\u51fa",
                Toast.LENGTH_LONG,
            ).show()
            is UsbOutputEffect.ShowError -> {
                DiagnosticLog.event("UsbOutputState", "failed code=${effect.code} message=${effect.message}")
                Toast.makeText(this, effect.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onUsbTopologyEvent(event: UsbTopologyEvent) {
        when (event) {
            UsbTopologyEvent.Attached -> {
                usbOwner?.onAttached()
                dispatchUsbOutput(UsbOutputEvent.UsbAttached)
            }
            is UsbTopologyEvent.Detached -> {
                val phase = usbOutputState.phase
                val relevant = usbOutputState.desiredMode != DesiredUsbOutput.Shared ||
                    phase == UsbOutputPhase.SharedReconnectRequired ||
                    phase == UsbOutputPhase.SharedRouteWaiting
                if (!relevant) return
                capturePlaybackStackHandoff()?.let { usbOutputHandoff = it }
                val playWhenReady = usbOutputState.frozenIntent?.playWhenReady
                    ?: usbOutputHandoff?.playWhenReady
                    ?: false
                usbOutputCandidate = null
                dispatchUsbOutput(UsbOutputEvent.UsbDetached(playWhenReady))
            }
        }
    }

    private fun scheduleSharedQuiescenceProbe(generation: Long) {
        if (generation != usbOutputState.generation || usbOutputState.phase != UsbOutputPhase.SharedQuiescing) return
        val identity = usbOutputCandidate?.identity ?: run {
            val selected = usbEffects?.discoverUsbAudioDevice() as? UsbAudioSelection.Selected
            selected?.candidate?.also { usbOutputCandidate = it }?.identity
        }
        val policy = UsbSharedQuiescencePolicyResolver.resolve(Build.MANUFACTURER, Build.MODEL, identity)
        usbSharedQuiesceStartedAtMs = SystemClock.elapsedRealtime()
        usbSharedQuiesceSettleMs = policy.settleMs
        DiagnosticLog.event(
            "UsbOutputState",
            "shared-quiesce start generation=$generation settleMs=${policy.settleMs} " +
                "host=${Build.MANUFACTURER}/${Build.MODEL} identity=${identity?.vendorId}:${identity?.productId}",
        )
        mainHandler.postDelayed({ pollSharedQuiescence(generation) }, SHARED_QUIESCE_POLL_MS)
    }

    private fun pollSharedQuiescence(generation: Long) {
        if (generation != usbOutputState.generation || usbOutputState.phase != UsbOutputPhase.SharedQuiescing) return
        val elapsedMs = SystemClock.elapsedRealtime() - usbSharedQuiesceStartedAtMs
        if (elapsedMs >= usbSharedQuiesceSettleMs) {
            DiagnosticLog.event(
                "UsbOutputState",
                "shared-quiesce complete generation=$generation elapsedMs=$elapsedMs settleMs=$usbSharedQuiesceSettleMs",
            )
            dispatchUsbOutput(UsbOutputEvent.SharedQuiesced(generation))
            return
        }
        mainHandler.postDelayed({ pollSharedQuiescence(generation) }, SHARED_QUIESCE_POLL_MS)
    }

    private fun scheduleExclusiveTargetProbe(generation: Long) {
        if (generation != usbOutputState.generation || usbOutputState.phase != UsbOutputPhase.ExclusivePreparing) return
        usbProbeGeneration = generation
        usbProbePollAttempts = 0
        usbProbeStableObservations = 0
        usbProbeIdentity = null
        usbProbeRuntimeHandle = null
        mainHandler.post { pollExclusiveTarget(generation) }
    }

    private fun pollExclusiveTarget(generation: Long) {
        if (generation != usbOutputState.generation || usbOutputState.phase != UsbOutputPhase.ExclusivePreparing) return
        when (val selection = usbEffects?.discoverUsbAudioDevice()) {
            is UsbAudioSelection.Selected -> {
                val candidate = selection.candidate
                val same = usbProbeIdentity == candidate.identity && usbProbeRuntimeHandle == candidate.runtimeHandle
                usbProbeIdentity = candidate.identity
                usbProbeRuntimeHandle = candidate.runtimeHandle
                usbProbeStableObservations = if (same) usbProbeStableObservations + 1 else 1
                usbOutputCandidate = candidate
                usbSharedReturnProbeIdentity = candidate.identity
                if (usbProbeStableObservations >= EXCLUSIVE_TARGET_STABLE_OBSERVATIONS) {
                    dispatchUsbOutput(UsbOutputEvent.TargetStable)
                    return
                }
            }
            is UsbAudioSelection.Ambiguous -> {
                dispatchUsbOutput(
                    UsbOutputEvent.PreparationFailed(
                        generation,
                        "MULTIPLE_USB_AUDIO_DEVICES",
                        "Multiple USB audio output devices are attached; target selection is ambiguous.",
                    ),
                )
                return
            }
            UsbAudioSelection.NotFound, null -> {
                usbProbeStableObservations = 0
                usbProbeIdentity = null
                usbProbeRuntimeHandle = null
                usbOutputCandidate = null
            }
        }
        usbProbePollAttempts += 1
        if (usbProbePollAttempts >= EXCLUSIVE_TARGET_READY_MAX_POLLS) {
            dispatchUsbOutput(
                UsbOutputEvent.PreparationFailed(
                    generation,
                    "USB_AUDIO_DEVICE_NOT_READY",
                    "USB audio device did not become stable and ready for exclusive playback.",
                ),
            )
            return
        }
        mainHandler.postDelayed({ pollExclusiveTarget(generation) }, EXCLUSIVE_TARGET_READY_POLL_MS)
    }

    private fun requestUsbPermissionForState() {
        val state = usbOutputState
        if (state.phase != UsbOutputPhase.PermissionWaiting) return
        val candidate = usbOutputCandidate
        if (candidate == null) {
            dispatchUsbOutput(UsbOutputEvent.TargetLost)
            return
        }
        usbEffects?.requestPermission(
            UsbPermissionRequest(
                epoch = UsbRequestEpoch(state.generation),
                mode = state.desiredMode.toExclusiveMode(),
                identity = candidate.identity,
                runtimeHandle = candidate.runtimeHandle,
            ),
        )
    }

    private fun onUsbPermissionResult(result: UsbPermissionResult) {
        val state = usbOutputState
        if (result.epoch.value != state.generation || state.phase != UsbOutputPhase.PermissionWaiting) return
        val effects = usbEffects ?: return
        val currentSelection = effects.discoverUsbAudioDevice()
        val candidate = (currentSelection as? UsbAudioSelection.Selected)?.candidate
        if (candidate == null || candidate.identity != result.identity) {
            usbOutputCandidate = candidate
            DiagnosticLog.event(
                "UsbOutputState",
                "permission-result target-lost generation=${state.generation} callbackRuntime=${result.runtimeHandle} current=${candidate?.runtimeHandle}",
            )
            dispatchUsbOutput(UsbOutputEvent.TargetLost)
            return
        }
        usbOutputCandidate = candidate
        if (!result.granted) {
            dispatchUsbOutput(UsbOutputEvent.PermissionResult(state.generation, false))
            return
        }
        val runtimeChanged = candidate.runtimeHandle != result.runtimeHandle
        val permissionStillGranted = if (runtimeChanged) effects.hasPermission(candidate) else true
        DiagnosticLog.event(
            "UsbOutputState",
            "permission-result generation=${state.generation} granted=true runtimeChanged=$runtimeChanged " +
                "callbackRuntime=${result.runtimeHandle} currentRuntime=${candidate.runtimeHandle} currentPermission=$permissionStillGranted",
        )
        if (permissionStillGranted) {
            dispatchUsbOutput(UsbOutputEvent.PermissionResult(state.generation, true))
        } else {
            dispatchUsbOutput(UsbOutputEvent.TargetLost)
        }
    }

    private fun openExclusiveForState() {
        val state = usbOutputState
        if (state.phase != UsbOutputPhase.ExclusiveOpening) return
        val candidate = usbOutputCandidate
        if (candidate == null) {
            dispatchUsbOutput(UsbOutputEvent.TargetLost)
            return
        }
        val owner = usbOwner ?: return
        val effects = usbEffects ?: return
        val epoch = owner.armAuthorizedTarget(
            state.desiredMode.toExclusiveMode(),
            candidate.identity,
            candidate.runtimeHandle,
        )
        owner.awaitIdle()
        if (usbOutputState.generation != state.generation || usbOutputState.phase != UsbOutputPhase.ExclusiveOpening) return
        usbOutputOwnerEpoch = epoch
        val binding = UsbHybridPlaybackBinding(owner, effects, epoch)
        val handoff = usbOutputHandoff
        rebuildPlaybackStack(
            state.desiredMode.toOutputPath(),
            binding,
            "usb-state-open:${usbOutputSwitchReason}",
            handoff,
        )
    }

    private fun retargetExclusiveForState() {
        val state = usbOutputState
        if (state.phase != UsbOutputPhase.ExclusiveOpening) return
        val owner = usbOwner ?: return
        val effects = usbEffects ?: return
        val ownerEpoch = usbOutputOwnerEpoch ?: run {
            dispatchUsbOutput(UsbOutputEvent.ExclusiveOpenFailed(state.generation, "EXCLUSIVE_OWNER_MISSING", "Exclusive USB ownership was lost before mode retarget."))
            return
        }
        val candidate = usbOutputCandidate ?: (effects.discoverUsbAudioDevice() as? UsbAudioSelection.Selected)?.candidate ?: run {
            dispatchUsbOutput(UsbOutputEvent.TargetLost)
            return
        }
        usbOutputCandidate = candidate
        val retainedEpoch = owner.retargetAuthorizedTarget(state.desiredMode.toExclusiveMode(), candidate.identity, candidate.runtimeHandle)
        if (retainedEpoch != ownerEpoch) {
            dispatchUsbOutput(UsbOutputEvent.ExclusiveOpenFailed(state.generation, "EXCLUSIVE_EPOCH_CHANGED", "Exclusive USB ownership changed during mode retarget."))
            return
        }
        val binding = UsbHybridPlaybackBinding(owner, effects, ownerEpoch)
        val handoff = usbOutputHandoff
        rebuildPlaybackStack(state.desiredMode.toOutputPath(), binding, "usb-state-retarget:${usbOutputSwitchReason}", handoff)
    }

    private fun closeExclusiveForState() {
        val owner = usbOwner ?: return
        runCatching {
            owner.retireExclusiveSession()
            owner.awaitIdle()
        }.onFailure { error ->
            DiagnosticLog.event("UsbOutputState", "close-exclusive failed error=${error.message}")
        }
        usbOutputOwnerEpoch = null
    }

    private fun restoreFrozenPlaybackIntentForState() {
        val state = usbOutputState
        if (state.phase !is UsbOutputPhase.ExclusiveActive) return
        val semanticPlayWhenReady = state.frozenIntent
            ?.takeIf { it.generation == state.generation }
            ?.playWhenReady
            ?: false
        DiagnosticLog.event(
            "UsbOutputState",
            "restore-frozen-intent generation=${state.generation} desired=${state.desiredMode} " +
                "transport=${state.activeTransport} playWhenReady=$semanticPlayWhenReady",
        )
        usbOutputOwnerEpoch?.let { epoch ->
            usbOwner?.setSemanticPlayWhenReady(epoch, semanticPlayWhenReady)
        }
        if (semanticPlayWhenReady) {
            compositePlayer?.playExoDirect()
        } else {
            compositePlayer?.pauseExoDirect()
        }
    }

    private fun isTransientExclusiveOpenFailure(code: String, message: String): Boolean =
        code.endsWith("OPEN_FAILED") &&
            message.contains("USBDEVFS_SUBMITURB", ignoreCase = true) &&
            (message.contains("No such file or directory", ignoreCase = true) ||
                message.contains("ENOENT", ignoreCase = true))

    private fun buildSharedPlayerForState() {
        if (usbOutputState.desiredMode != DesiredUsbOutput.Shared || usbOutputState.phase != UsbOutputPhase.SharedActive) return
        runCatching {
            usbOwner?.retireExclusiveSession()
            usbOwner?.awaitIdle()
        }
        rebuildPlaybackStack(
            AudioOutputPathConfig.PRODUCTION,
            null,
            "usb-state-shared:${usbOutputSwitchReason}",
            usbOutputHandoff,
        )
        usbOutputOwnerEpoch = null
    }

    private fun scheduleSharedRouteProbe(generation: Long) {
        if (generation != usbOutputState.generation || usbOutputState.phase != UsbOutputPhase.SharedRouteWaiting) return
        usbSharedRoutePollGeneration = generation
        usbSharedRouteProbeStartedAtMs = SystemClock.elapsedRealtime()
        if (usbSharedAudioAddSerial > usbSharedRouteWaitBaselineAddSerial) {
            DiagnosticLog.event(
                "UsbOutputState",
                "shared-route add already observed generation=$generation serial=$usbSharedAudioAddSerial " +
                    "baseline=$usbSharedRouteWaitBaselineAddSerial",
            )
            completeSharedRouteRecovery()
            return
        }
        DiagnosticLog.event(
            "UsbOutputState",
            "shared-route waiting generation=$generation baseline=$usbSharedRouteWaitBaselineAddSerial " +
                "serial=$usbSharedAudioAddSerial",
        )
        mainHandler.postDelayed({ onSharedRouteTimeout(generation) }, SHARED_RETURN_ROUTE_TIMEOUT_MS)
    }

    private fun onSharedRouteTimeout(generation: Long) {
        if (generation != usbOutputState.generation || usbOutputState.phase != UsbOutputPhase.SharedRouteWaiting) return
        val elapsedMs = SystemClock.elapsedRealtime() - usbSharedRouteProbeStartedAtMs
        DiagnosticLog.event(
            "UsbOutputState",
            "shared-route timeout generation=$generation elapsedMs=$elapsedMs " +
                "baseline=$usbSharedRouteWaitBaselineAddSerial serial=$usbSharedAudioAddSerial",
        )
        if (!usbOutputState.sharedReturnRequiresReconnect) {
            usbSharedReturnProbeIdentity?.let { identity ->
                UsbSharedReturnCapabilityStore.setCapability(
                    this,
                    identity,
                    UsbSharedReturnCapability.ReconnectRequired,
                )
            }
        }
        dispatchUsbOutput(UsbOutputEvent.AndroidSharedRouteUnavailable)
    }

    private fun completeSharedRouteRecovery() {
        if (usbOutputState.desiredMode != DesiredUsbOutput.Shared || usbOutputState.phase != UsbOutputPhase.SharedRouteWaiting) return
        val recoveredAfterReconnect = usbOutputState.sharedReturnRequiresReconnect
        if (!recoveredAfterReconnect) {
            usbSharedReturnProbeIdentity?.let { identity ->
                UsbSharedReturnCapabilityStore.setCapability(
                    this,
                    identity,
                    UsbSharedReturnCapability.HotSwitchVerified,
                )
            }
        }
        dispatchUsbOutput(UsbOutputEvent.AndroidSharedRouteReady)
        if (recoveredAfterReconnect) {
            Toast.makeText(this, "Android \u5171\u4eab\u8f93\u51fa\u5df2\u6062\u590d", Toast.LENGTH_SHORT).show()
        }
        usbSharedReturnProbeIdentity = null
    }

    private fun installUsbSharedAudioDeviceCallback() {
        if (usbSharedAudioDeviceCallbackRegistered) return
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        runCatching {
            audioManager.registerAudioDeviceCallback(usbSharedAudioDeviceCallback, mainHandler)
            usbSharedAudioDeviceCallbackRegistered = true
        }.onFailure { error ->
            DiagnosticLog.event("UsbOutputState", "shared-audio callback register failed error=${error.message}")
        }
    }

    private fun uninstallUsbSharedAudioDeviceCallback() {
        if (!usbSharedAudioDeviceCallbackRegistered) return
        val audioManager = getSystemService(AudioManager::class.java)
        runCatching { audioManager?.unregisterAudioDeviceCallback(usbSharedAudioDeviceCallback) }
        usbSharedAudioDeviceCallbackRegistered = false
    }

    private fun isAndroidUsbAudioOutput(device: AudioDeviceInfo): Boolean =
        device.isSink &&
            (device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY)
    private fun UsbHybridOutputMode.toDesiredUsbOutput(): DesiredUsbOutput = when (this) {
        UsbHybridOutputMode.SharedPcm -> DesiredUsbOutput.Shared
        UsbHybridOutputMode.ExactPcm -> DesiredUsbOutput.ExactPcm
        UsbHybridOutputMode.Dop -> DesiredUsbOutput.Dop
        UsbHybridOutputMode.NativeDsdExperimental -> DesiredUsbOutput.NativeDsd
    }

    private fun DesiredUsbOutput.toExclusiveMode(): UsbExclusiveMode = when (this) {
        DesiredUsbOutput.Shared -> UsbExclusiveMode.SHARED_PCM
        DesiredUsbOutput.ExactPcm -> UsbExclusiveMode.USB_EXACT_PCM
        DesiredUsbOutput.Dop -> UsbExclusiveMode.USB_DOP
        DesiredUsbOutput.NativeDsd -> UsbExclusiveMode.USB_NATIVE_DSD_EXPERIMENTAL
    }

    private fun DesiredUsbOutput.toOutputPath(): AudioOutputPathConfig = when (this) {
        DesiredUsbOutput.Shared -> AudioOutputPathConfig.PRODUCTION
        DesiredUsbOutput.ExactPcm -> AudioOutputPathConfig(outputMode = PlaybackOutputMode.UsbDirectPcm)
        DesiredUsbOutput.Dop -> AudioOutputPathConfig(outputMode = PlaybackOutputMode.UsbDop)
        DesiredUsbOutput.NativeDsd -> AudioOutputPathConfig(outputMode = PlaybackOutputMode.UsbNativeDsdExperimental)
    }
    private fun retirePlaybackStackBeforeUsbRequest() {
        val oldExo = exoPlayer
        var releaseError: PlaybackException? = null
        val releaseObserver = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                releaseError = error
            }
        }
        oldExo?.addListener(releaseObserver)
        releasePlaybackStackOwners()
        oldExo?.release()
        exoPlayer = null
        compositePlayer = null
        releaseError?.let { error ->
            throw IllegalStateException(
                "Old ExoPlayer release failed: ${error.cause?.message ?: error.message}",
                error,
            )
        }
    }

    private fun handleUsbFacts(facts: UsbPlaybackFacts) {
        val owner = usbOwner ?: return
        if (owner.facts.value != facts) return
        val ownerEpoch = usbOutputOwnerEpoch ?: return
        if (facts.requestEpoch != ownerEpoch.value) return
        val state = usbOutputState
        if (state.phase != UsbOutputPhase.ExclusiveOpening && state.phase !is UsbOutputPhase.ExclusiveActive) return

        facts.failure?.let { failure ->
            if (state.phase != UsbOutputPhase.ExclusiveOpening) return
            val transientOwnershipRace = state.desiredMode == DesiredUsbOutput.NativeDsd &&
                isTransientExclusiveOpenFailure(failure.code, failure.message)
            dispatchUsbOutput(
                UsbOutputEvent.ExclusiveOpenFailed(
                    generation = state.generation,
                    code = failure.code,
                    message = failure.message,
                    transientOwnershipRace = transientOwnershipRace,
                ),
            )
            return
        }

        val activeTransport = facts.activeTransport ?: return
        if (facts.sessionId == null || !facts.exclusive || !facts.transportExact) return
        val shouldPublishTransport = when (state.phase) {
            UsbOutputPhase.ExclusiveOpening -> true
            is UsbOutputPhase.ExclusiveActive ->
                state.activeTransport != activeTransport || state.activeSessionId != facts.sessionId
            else -> false
        }
        if (shouldPublishTransport) {
            dispatchUsbOutput(
                UsbOutputEvent.ExclusiveOpenSucceeded(
                    generation = state.generation,
                    transport = activeTransport,
                    sessionId = facts.sessionId,
                ),
            )
        }
    }
    private fun capturePlaybackStackHandoff(): PlaybackStackHandoff? {
        val player = compositePlayer ?: return null
        val queue = player.playbackQueueSnapshot()
        if (queue.items.isEmpty()) return null
        return PlaybackStackHandoff(
            items = queue.items,
            currentIndex = queue.currentIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = player.playWhenReady,
            repeatMode = player.repeatMode,
            playbackParameters = player.playbackParameters,
            volume = player.volume,
        )
    }

    private fun rebuildPlaybackStack(
        target: AudioOutputPathConfig,
        usbBinding: UsbHybridPlaybackBinding?,
        reason: String,
        handoffOverride: PlaybackStackHandoff? = null,
    ) {
        val requestedUsbEpoch = usbBinding?.epoch?.value
        if (exoPlayer != null && target == activeOutputPath && requestedUsbEpoch == activeUsbEpoch) return
        val micaApp = application as MicaApp
        val handoff = handoffOverride ?: capturePlaybackStackHandoff() ?: usbOutputHandoff
        usbOwner?.awaitIdle()
        if (usbBinding != null && usbOwner?.currentEpoch() != usbBinding.epoch) return
        val oldExo = exoPlayer
        releasePlaybackStackOwners()
        oldExo?.release()
        exoPlayer = null
        compositePlayer = null
        val newStack = runCatching { ExoPlaybackStackFactory.build(this, target, usbBinding) }
            .getOrElse { error ->
                DiagnosticLog.event(
                    "AudioOutputPath",
                    "rebuild-failed reason=$reason target=${target.outputMode} error=${error.message}",
                )
                return
            }
        activeOutputPath = target
        activeUsbEpoch = requestedUsbEpoch
        installPlaybackStackOwners(newStack, micaApp, handoff)
        DiagnosticLog.event(
            "AudioOutputPath",
            "rebuild-complete reason=$reason mode=${target.outputMode} " +
                "items=${handoff?.items?.size ?: 0} index=${handoff?.currentIndex ?: 0} " +
                "positionMs=${handoff?.positionMs ?: 0} resume=${handoff?.playWhenReady == true}",
        )
    }

    private fun installPlaybackStackOwners(
        stack: ExoPlaybackStack,
        micaApp: MicaApp,
        handoff: PlaybackStackHandoff? = null,
    ) {
        exoPlayer = stack.exoPlayer
        compositePlayer = stack.compositePlayer

        if (handoff != null) {
            stack.compositePlayer.selectWithoutPlayback(
                mediaItems = handoff.items,
                startIndex = handoff.currentIndex,
                startPositionMs = handoff.positionMs,
            )
            stack.compositePlayer.repeatMode = handoff.repeatMode
            stack.compositePlayer.playbackParameters = handoff.playbackParameters
            stack.compositePlayer.volume = if (activeOutputPath.outputMode.allowsSharedPcmDsp) {
                handoff.volume
            } else {
                1f
            }
        }
        mediaSession?.setPlayer(stack.compositePlayer)

        if (activeOutputPath.outputMode.allowsSharedPcmDsp) {
            replayGainStateOwner = ReplayGainStateOwner(this, stack.compositePlayer).also { it.start() }
        }
        if (activeOutputPath.outputMode.allowsSharedPcmDsp) {
            installAudioOffloadCircuitBreaker(stack.exoPlayer)
            installAudioPipelineCoordinator(stack.exoPlayer)
            wireEqualizerAndSpectrumHandlers()
        }

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

        var restoredFromStore = false
        playbackStateCoordinator = ServicePlaybackStateCoordinator(
            player = stack.compositePlayer,
            store = ServicePlaybackStateStore(this),
            handler = mainHandler,
            initialQualityMode = if (
                activeOutputPath.outputMode.allowsSharedPcmDsp &&
                EqualizerPreferences.equalizerEnabled(this)
            ) {
                AudioQualityMode.DSP
            } else {
                AudioQualityMode.HIFI
            },
            externalSongResolver = micaApp.transientPlaybackCatalog::songForPersistence,
        ).also { coordinator ->
            coordinator.onRestoreCompleted = {
                restoredFromStore = true
                mainHandler.post {
                    logRestoredSongDiagnostics()
                    if (handoff?.playWhenReady == true) {
                        compositePlayer?.playWhenReady = true
                    }
                }
            }
            coordinator.start()
        }

        if (handoff != null && !restoredFromStore) {
            stack.compositePlayer.seekTo(handoff.currentIndex, handoff.positionMs)
            stack.compositePlayer.prepare()
            stack.compositePlayer.playWhenReady = handoff.playWhenReady
        }

        stack.compositePlayer.onUserPlayIntentChanged = { playWhenReady ->
            usbOutputOwnerEpoch?.let { epoch ->
                usbOwner?.setSemanticPlayWhenReady(epoch, playWhenReady)
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                dispatchUsbOutput(UsbOutputEvent.UserPlayIntentChanged(playWhenReady))
            } else {
                mainHandler.post {
                    dispatchUsbOutput(UsbOutputEvent.UserPlayIntentChanged(playWhenReady))
                }
            }
        }
        stack.compositePlayer.shouldDeferUserPlayIntent = { false }

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
            transientSongResolver = micaApp.transientPlaybackCatalog::songById,
        ).also { it.start() }
        if (activeOutputPath.outputMode.allowsSharedPcmDsp) {
            attachEqualizerSessionListener(stack.exoPlayer)
        }
    }

    private fun logRestoredSongDiagnostics() {
        val song = compositePlayer?.currentMediaItem
            ?.let(SongMediaItemCodec::decode)
            ?: return
        SharedPcmPipelineDiagnostics.logSongFormat(song)
        PcmDeliveryProbeDiagnostics.logForSong(
            context = this,
            song = song,
            playbackParameters = compositePlayer?.playbackParameters ?: PlaybackParameters.DEFAULT,
        )
    }

    private fun releasePlaybackStackOwners() {
        compositePlayer?.retireForReplacement()
        compositePlayer?.onUserPlayIntentChanged = null
        compositePlayer?.shouldDeferUserPlayIntent = null
        replayGainStateOwner?.release()
        replayGainStateOwner = null
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
        audioPipelineCoordinator = null
        MicaEqualizerManager.onEnabledChanged = null
        MicaSpectrumAnalyzer.onEnabledChanged = null
        MicaEqualizerManager.release()
    }

    override fun onDestroy() {
        uninstallUsbSharedAudioDeviceCallback()
        playbackRouteMonitor?.release()
        playbackRouteMonitor = null
        unregisterLyricsPreferenceListener?.invoke()
        unregisterLyricsPreferenceListener = null
        unregisterAudioOffloadPreferenceListener?.invoke()
        unregisterAudioOffloadPreferenceListener = null
        unregisterUsbOutputPreferenceListener?.invoke()
        unregisterUsbOutputPreferenceListener = null
        mainHandler.removeCallbacks(usbTelemetrySampler)
        usbFactsJob?.cancel()
        usbFactsJob = null
        sessionScope?.cancel()
        sessionScope = null
        trustedMediaItemResolver = null
        releasePlaybackStackOwners()
        spectrumAnalyzerStateOwner?.release()
        spectrumAnalyzerStateOwner = null
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        compositePlayer = null
        activeUsbEpoch = null
        usbOwner?.close()
        usbOwner = null
        usbEffects?.close()
        usbEffects = null
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
                    .add(ExternalLyricsSessionCommands.toggleDesktopLyrics)
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
                    ExternalLyricsSessionCommands.TOGGLE_DESKTOP_LYRICS_ACTION &&
                    isMediaNotificationController(session, controller)
                ) {
                    mainHandler.post {
                        val currentMode = LyricsPreferences.externalLyricsMode(this@MicaMediaService)
                        LyricsPreferences.setExternalLyricsMode(
                            this@MicaMediaService,
                            ExternalLyricsSessionCommands.nextModeAfterDesktopToggle(currentMode),
                        )
                        DesktopLyricsOverlayController.sync(this@MicaMediaService)
                        updateMediaButtonPreferences()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
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

    private companion object {
        const val USB_TELEMETRY_INTERVAL_MS = 1_000L
        const val NATIVE_DSD_HOTPLUG_PRIME_SETTLE_MS = 300L
        const val SHARED_RETURN_ROUTE_TIMEOUT_MS = 5_000L
        const val SHARED_QUIESCE_POLL_MS = 50L
        const val EXCLUSIVE_TARGET_READY_POLL_MS = 100L
        const val EXCLUSIVE_TARGET_READY_MAX_POLLS = 50
        const val EXCLUSIVE_TRANSIENT_OPEN_MAX_RETRIES = 2
        const val EXCLUSIVE_TARGET_STABLE_OBSERVATIONS = 5
    }
}
