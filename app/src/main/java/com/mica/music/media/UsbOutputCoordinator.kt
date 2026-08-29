package com.mica.music.media

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import com.mica.music.data.preferences.UsbHybridOutputMode
import com.mica.music.data.preferences.UsbSharedReturnCapability
import com.mica.music.data.preferences.UsbSharedReturnCapabilityStore
import com.mica.music.data.preferences.UsbSharedReturnPolicy
import com.mica.music.media.usbhybrid.AndroidUsbHybridControlEffects
import com.mica.music.media.usbhybrid.DesiredUsbOutput
import com.mica.music.media.usbhybrid.UsbActiveTransport
import com.mica.music.media.usbhybrid.UsbAudioSelection
import com.mica.music.media.usbhybrid.UsbDeviceCandidate
import com.mica.music.media.usbhybrid.UsbExclusiveMode
import com.mica.music.media.usbhybrid.UsbHybridPlaybackBinding
import com.mica.music.media.usbhybrid.UsbHybridRuntimeMonitor
import com.mica.music.media.usbhybrid.UsbHybridSessionOwner
import com.mica.music.media.usbhybrid.UsbOutputEffect
import com.mica.music.media.usbhybrid.UsbOutputEvent
import com.mica.music.media.usbhybrid.UsbOutputOperationId
import com.mica.music.media.usbhybrid.UsbOutputPermissionRequest
import com.mica.music.media.usbhybrid.UsbOutputPermissionResult
import com.mica.music.media.usbhybrid.UsbOutputPhase
import com.mica.music.media.usbhybrid.UsbOutputState
import com.mica.music.media.usbhybrid.UsbOutputStateMachine
import com.mica.music.media.usbhybrid.UsbPlaybackFacts
import com.mica.music.media.usbhybrid.UsbRequestEpoch
import com.mica.music.media.usbhybrid.UsbRuntimeHandle
import com.mica.music.media.usbhybrid.UsbSharedQuiescencePolicyResolver
import com.mica.music.usb.UsbStableIdentity
import com.mica.music.media.usbhybrid.UsbTopologyEvent
import com.mica.music.media.usbhybrid.toPlaybackOutputAvailability
import com.mica.music.util.DiagnosticLog
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal data class UsbPlaybackStackHandoff(
    val items: List<MediaItem>,
    val currentIndex: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val repeatMode: Int,
    val playbackParameters: PlaybackParameters,
    val volume: Float,
)

internal sealed interface UsbOutputCommand {
    data class SelectMode(
        val mode: UsbHybridOutputMode,
        val reason: String,
    ) : UsbOutputCommand

    data class PlaybackIntentChanged(
        val playWhenReady: Boolean,
    ) : UsbOutputCommand
}

internal interface UsbOutputCoordinator : AutoCloseable {
    fun start(initialMode: UsbHybridOutputMode)
    fun submit(command: UsbOutputCommand)
    override fun close()
}

/** Playback-only seam. USB coordination never reaches into MediaSessionService fields directly. */
internal interface UsbOutputPlaybackPort {
    fun captureHandoff(): UsbPlaybackStackHandoff?
    fun hasPlaybackStack(): Boolean
    fun isSharedOutputActive(): Boolean
    fun currentPlayWhenReady(): Boolean
    fun currentAudioSessionId(): Int?
    fun retireBeforeUsbRequest()
    fun rebuildShared(handoff: UsbPlaybackStackHandoff?, reason: String)
    fun rebuildExclusive(
        mode: DesiredUsbOutput,
        binding: UsbHybridPlaybackBinding,
        handoff: UsbPlaybackStackHandoff?,
        reason: String,
    )
    fun restorePlaybackIntent(playWhenReady: Boolean)
}

private data class UsbOutputOperation(
    val id: UsbOutputOperationId,
    val generation: Long,
    val phase: UsbOutputPhase,
)

/**
 * Owns the application-level USB output protocol above [UsbOutputStateMachine] and below the
 * playback service, including Android callback registration, owner facts and asynchronous
 * operation fencing. The service sees only commands plus the narrow playback port.
 */
internal class DefaultUsbOutputCoordinator(
    private val context: Context,
    private val mainHandler: Handler,
    private val playback: UsbOutputPlaybackPort,
) : UsbOutputCoordinator {
    private val outputStatusPublisher = PlaybackOutputStatusMonitor.openPublisher()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var effects: AndroidUsbHybridControlEffects
    private lateinit var owner: UsbHybridSessionOwner
    private var ownerFactsJob: Job? = null
    private var audioDeviceCallbackRegistered: Boolean = false
    private var state = UsbOutputState()
    private var outputHandoff: UsbPlaybackStackHandoff? = null
    private var candidate: UsbDeviceCandidate? = null
    private var ownerEpoch: UsbRequestEpoch? = null
    private var switchReason: String = "service-create"

    private var probePollAttempts: Int = 0
    private var probeStableObservations: Int = 0
    private var probeIdentity: UsbStableIdentity? = null
    private var probeRuntimeHandle: UsbRuntimeHandle? = null

    private var sharedQuiesceStartedAtMs: Long = 0L
    private var sharedQuiesceSettleMs: Long = 0L
    private var sharedRouteProbeStartedAtMs: Long = 0L
    private var sharedAudioAddSerial: Long = 0L
    private var sharedRouteWaitBaselineAddSerial: Long = 0L
    private var sharedReturnProbeIdentity: UsbStableIdentity? = null

    private var operation: UsbOutputOperation? = null
    @Volatile
    private var closed: Boolean = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            this@DefaultUsbOutputCoordinator.onAudioDevicesAdded(addedDevices)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            this@DefaultUsbOutputCoordinator.onAudioDevicesRemoved(removedDevices)
        }
    }

    private val telemetrySampler = object : Runnable {
        override fun run() {
            if (closed) return
            owner.refreshTelemetry(effects)
            mainHandler.postDelayed(this, USB_TELEMETRY_INTERVAL_MS)
        }
    }

    init {
        publishOutputStatus()
        installRuntime()
    }

    private fun installRuntime() {
        effects = AndroidUsbHybridControlEffects(
            context = context,
            permissionResultSink = { result ->
                mainHandler.post {
                    if (!closed) owner.onPermissionResult(result)
                }
            },
            topologyEventSink = { event ->
                mainHandler.post {
                    if (!closed) onTopologyEvent(event)
                }
            },
            outputPermissionResultSink = { result ->
                mainHandler.post {
                    if (!closed) onOutputPermissionResult(result)
                }
            },
        )
        owner = UsbHybridSessionOwner(
            effects = effects,
            factsPublisher = UsbHybridRuntimeMonitor::publishFromOwner,
        )
        ownerFactsJob = runtimeScope.launch {
            owner.facts.collectLatest { facts ->
                mainHandler.post {
                    if (!closed) onOwnerFacts(facts)
                }
            }
        }
        installAudioDeviceCallback()
        mainHandler.postDelayed(telemetrySampler, USB_TELEMETRY_INTERVAL_MS)
    }

    override fun start(initialMode: UsbHybridOutputMode) {
        submit(UsbOutputCommand.SelectMode(initialMode, "service-create"))
    }

    override fun submit(command: UsbOutputCommand) {
        if (closed) return
        when (command) {
            is UsbOutputCommand.SelectMode -> applyMode(command.mode, command.reason)
            is UsbOutputCommand.PlaybackIntentChanged -> {
                ownerEpoch?.let { owner.setSemanticPlayWhenReady(it, command.playWhenReady) }
                dispatch(UsbOutputEvent.UserPlayIntentChanged(command.playWhenReady))
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        outputStatusPublisher.close()
        invalidateOperation()
        uninstallAudioDeviceCallback()
        mainHandler.removeCallbacks(telemetrySampler)
        ownerFactsJob?.cancel()
        ownerFactsJob = null
        runtimeScope.cancel()
        runCatching {
            if (playback.hasPlaybackStack()) playback.retireBeforeUsbRequest()
        }.onFailure { error ->
            DiagnosticLog.event("UsbOutputState", "coordinator-close playback-retire failed error=${error.message}")
        }
        runCatching { owner.close() }
            .onFailure { error -> DiagnosticLog.event("UsbOutputState", "coordinator-close owner failed error=${error.message}") }
        runCatching { effects.close() }
            .onFailure { error -> DiagnosticLog.event("UsbOutputState", "coordinator-close android-effects failed error=${error.message}") }
    }

    internal fun onOutputPermissionResult(result: UsbOutputPermissionResult) {
        if (closed) return
        val current = state
        val activeOperation = operation ?: return
        if (result.operationId != activeOperation.id || !isCurrentOperation(activeOperation)) return
        val currentSelection = effects.discoverUsbAudioDevice()
        if (!isCurrentOperation(activeOperation)) return
        val currentCandidate = (currentSelection as? UsbAudioSelection.Selected)?.candidate
        if (currentCandidate == null || currentCandidate.identity != result.identity) {
            candidate = currentCandidate
            DiagnosticLog.event(
                "UsbOutputState",
                "permission-result target-lost generation=${current.generation} " +
                    "callbackRuntime=${result.runtimeHandle} current=${currentCandidate?.runtimeHandle}",
            )
            dispatch(UsbOutputEvent.TargetLost)
            return
        }
        candidate = currentCandidate
        if (!result.granted) {
            dispatch(UsbOutputEvent.PermissionResult(current.generation, false))
            return
        }
        val runtimeChanged = currentCandidate.runtimeHandle != result.runtimeHandle
        val permissionStillGranted = if (runtimeChanged) effects.hasPermission(currentCandidate) else true
        DiagnosticLog.event(
            "UsbOutputState",
            "permission-result generation=${current.generation} granted=true runtimeChanged=$runtimeChanged " +
                "callbackRuntime=${result.runtimeHandle} currentRuntime=${currentCandidate.runtimeHandle} " +
                "currentPermission=$permissionStillGranted",
        )
        if (permissionStillGranted) {
            dispatch(UsbOutputEvent.PermissionResult(current.generation, true))
        } else {
            dispatch(UsbOutputEvent.TargetLost)
        }
    }

    internal fun onTopologyEvent(event: UsbTopologyEvent) {
        if (closed) return
        when (event) {
            is UsbTopologyEvent.Attached -> {
                if (!event.hasAudioOutput) return
                val phase = state.phase
                if (
                    phase == UsbOutputPhase.PermissionWaiting ||
                    phase == UsbOutputPhase.ExclusiveOpening ||
                    phase is UsbOutputPhase.ExclusiveActive
                ) {
                    val currentRuntimeHandle = candidate?.runtimeHandle ?: owner.facts.value.runtimeHandle
                    if (currentRuntimeHandle != event.runtimeHandle) return
                }
                owner.onAttached()
                dispatch(UsbOutputEvent.UsbAttached)
            }
            is UsbTopologyEvent.Detached -> {
                val phase = state.phase
                val relevant = state.desiredMode != DesiredUsbOutput.Shared ||
                    phase == UsbOutputPhase.SharedReconnectRequired ||
                    phase == UsbOutputPhase.SharedRouteWaiting
                if (!relevant) return
                val targetRuntime = candidate?.runtimeHandle ?: owner.facts.value.runtimeHandle
                if (targetRuntime != null && targetRuntime != event.runtimeHandle) return
                playback.captureHandoff()?.let { outputHandoff = it }
                val playWhenReady = state.frozenIntent?.playWhenReady
                    ?: outputHandoff?.playWhenReady
                    ?: false
                candidate = null
                dispatch(UsbOutputEvent.UsbDetached(playWhenReady))
            }
        }
    }

    internal fun onOwnerFacts(facts: UsbPlaybackFacts) {
        if (closed || owner.facts.value != facts) return
        val activeOwnerEpoch = ownerEpoch ?: return
        if (facts.requestEpoch != activeOwnerEpoch.value) return
        val current = state
        if (current.phase != UsbOutputPhase.ExclusiveOpening && current.phase !is UsbOutputPhase.ExclusiveActive) return

        facts.failure?.let { failure ->
            if (current.phase != UsbOutputPhase.ExclusiveOpening) return
            val transientOwnershipRace = current.desiredMode == DesiredUsbOutput.NativeDsd &&
                isTransientExclusiveOpenFailure(failure.code, failure.message)
            dispatch(
                UsbOutputEvent.ExclusiveOpenFailed(
                    generation = current.generation,
                    code = failure.code,
                    message = failure.message,
                    transientOwnershipRace = transientOwnershipRace,
                ),
            )
            return
        }

        val activeTransport = facts.activeTransport ?: return
        if (facts.sessionId == null || !facts.exclusive || !facts.transportExact) return
        val shouldPublishTransport = when (current.phase) {
            UsbOutputPhase.ExclusiveOpening -> true
            is UsbOutputPhase.ExclusiveActive ->
                current.activeTransport != activeTransport || current.activeSessionId != facts.sessionId
            else -> false
        }
        if (shouldPublishTransport) {
            dispatch(
                UsbOutputEvent.ExclusiveOpenSucceeded(
                    generation = current.generation,
                    transport = activeTransport,
                    sessionId = facts.sessionId,
                ),
            )
        }
    }

    internal fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
        if (closed) return
        val usbDevice = addedDevices.firstOrNull(::isAndroidUsbAudioOutput) ?: return
        sharedAudioAddSerial += 1
        DiagnosticLog.event(
            "UsbOutputState",
            "shared-audio-device-added serial=$sharedAudioAddSerial deviceId=${usbDevice.id} phase=${state.phase}",
        )
        when (state.phase) {
            UsbOutputPhase.SharedRouteWaiting -> {
                if (sharedAudioAddSerial > sharedRouteWaitBaselineAddSerial) completeSharedRouteRecovery()
            }
            UsbOutputPhase.SharedReconnectRequired -> {
                dispatch(UsbOutputEvent.UsbAttached)
                if (state.phase == UsbOutputPhase.SharedRouteWaiting) completeSharedRouteRecovery()
            }
            else -> Unit
        }
    }

    internal fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
        if (closed || removedDevices.none(::isAndroidUsbAudioOutput)) return
        DiagnosticLog.event("UsbOutputState", "shared-audio-device-removed phase=${state.phase}")
    }

    private fun installAudioDeviceCallback() {
        if (audioDeviceCallbackRegistered) return
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
            audioDeviceCallbackRegistered = true
        }.onFailure { error ->
            DiagnosticLog.event("UsbOutputState", "shared-audio callback register failed error=${error.message}")
        }
    }

    private fun uninstallAudioDeviceCallback() {
        if (!audioDeviceCallbackRegistered) return
        val audioManager = context.getSystemService(AudioManager::class.java)
        runCatching { audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback) }
        audioDeviceCallbackRegistered = false
    }

    internal fun stateForTest(): UsbOutputState = state

    private fun applyMode(mode: UsbHybridOutputMode, reason: String) {
        if (closed) return
        val desired = mode.toDesiredUsbOutput()
        val phase = state.phase
        if (
            desired == state.desiredMode &&
            ((desired == DesiredUsbOutput.Shared && phase == UsbOutputPhase.SharedActive && playback.hasPlaybackStack()) ||
                (phase is UsbOutputPhase.ExclusiveActive && phase.mode == desired))
        ) {
            return
        }

        val handoff = playback.captureHandoff() ?: outputHandoff
        if (handoff != null) outputHandoff = handoff
        val playWhenReady = state.frozenIntent?.playWhenReady
            ?: handoff?.playWhenReady
            ?: playback.currentPlayWhenReady()
        val ownerFacts = owner.facts.value
        val returningFromExclusive = desired == DesiredUsbOutput.Shared &&
            (state.desiredMode != DesiredUsbOutput.Shared ||
                !playback.isSharedOutputActive() ||
                ownerFacts.activeMode != null || ownerFacts.exclusive)
        val identity = ownerFacts.identity ?: candidate?.identity
        val returnIdentity = identity ?: sharedReturnProbeIdentity
        val returnCapability = returnIdentity?.let { UsbSharedReturnCapabilityStore.capability(context, it) }
        if (closed) return
        val reconnectRequired = returningFromExclusive &&
            UsbSharedReturnPolicy.requiresPhysicalReconnect(returnCapability ?: UsbSharedReturnCapability.Unknown)
        sharedReturnProbeIdentity = when {
            returningFromExclusive -> returnIdentity
            desired != DesiredUsbOutput.Shared -> identity ?: sharedReturnProbeIdentity
            else -> sharedReturnProbeIdentity
        }
        if (returningFromExclusive) {
            sharedRouteWaitBaselineAddSerial = sharedAudioAddSerial
            DiagnosticLog.event(
                "UsbOutputState",
                "shared-return capability=${returnCapability ?: UsbSharedReturnCapability.Unknown} " +
                    "identity=${returnIdentity?.vendorId}:${returnIdentity?.productId} reconnect=$reconnectRequired " +
                    "audioAddBaseline=$sharedRouteWaitBaselineAddSerial",
            )
        }
        switchReason = reason
        dispatch(
            UsbOutputEvent.UserSelected(
                mode = desired,
                playWhenReady = playWhenReady,
                sharedReturnRequiresReconnect = reconnectRequired,
            ),
        )
    }

    private fun dispatch(event: UsbOutputEvent) {
        if (closed) return
        val before = state
        val reduction = UsbOutputStateMachine.reduce(before, event)
        state = reduction.state
        publishOutputStatus()
        if (before.generation != state.generation || before.phase != state.phase) invalidateOperation()
        DiagnosticLog.event(
            "UsbOutputState",
            "event=${event.javaClass.simpleName} generation=${state.generation} " +
                "desired=${state.desiredMode} phase=${before.phase}->${state.phase}",
        )
        reduction.effects.forEach(::executeEffect)
    }

    private fun publishOutputStatus() {
        val phase = state.phase
        outputStatusPublisher.publish(
            availability = phase.toPlaybackOutputAvailability(),
            pendingPlayIntent = state.frozenIntent?.playWhenReady ?: playback.currentPlayWhenReady(),
            failureMessage = (phase as? UsbOutputPhase.Failed)?.message,
        )
    }

    private fun beginOperation(generation: Long, phase: UsbOutputPhase): UsbOutputOperation? {
        if (closed || state.generation != generation || state.phase != phase) return null
        return UsbOutputOperation(
            id = UsbOutputOperationId(nextOperationId.incrementAndGet()),
            generation = generation,
            phase = phase,
        ).also { operation = it }
    }

    private fun isCurrentOperation(value: UsbOutputOperation): Boolean =
        !closed && operation == value && state.generation == value.generation && state.phase == value.phase

    private fun invalidateOperation() {
        operation = null
    }

    private fun postOperation(value: UsbOutputOperation, delayMs: Long = 0L, action: () -> Unit) {
        val guarded = Runnable { if (isCurrentOperation(value)) action() }
        if (delayMs > 0L) mainHandler.postDelayed(guarded, delayMs) else mainHandler.post(guarded)
    }

    private fun executeEffect(effect: UsbOutputEffect) {
        when (effect) {
            UsbOutputEffect.RetirePlayer -> {
                if (state.phase == UsbOutputPhase.SharedQuiescing) {
                    DiagnosticLog.event(
                        "UsbOutputState",
                        "shared-quiesce retire generation=${state.generation} " +
                            "audioSessionId=${playback.currentAudioSessionId() ?: 0}",
                    )
                }
                if (!playback.hasPlaybackStack()) {
                    if (state.phase == UsbOutputPhase.SharedQuiescing) {
                        dispatch(UsbOutputEvent.SharedQuiesced(state.generation))
                    }
                    return
                }
                runCatching { playback.retireBeforeUsbRequest() }
                    .onFailure { error ->
                        DiagnosticLog.event("UsbOutputState", "retire-player failed error=${error.message}")
                        if (state.phase == UsbOutputPhase.ExclusivePreparing || state.phase == UsbOutputPhase.SharedQuiescing) {
                            dispatch(
                                UsbOutputEvent.PreparationFailed(
                                    state.generation,
                                    "STACK_RELEASE_FAILED",
                                    error.message ?: "Old playback stack did not release cleanly.",
                                ),
                            )
                        }
                    }
            }
            UsbOutputEffect.WaitForSharedQuiescence -> scheduleSharedQuiescenceProbe(state.generation)
            UsbOutputEffect.WaitForTarget -> scheduleExclusiveTargetProbe(state.generation)
            UsbOutputEffect.RequestPermission -> requestPermissionForState()
            UsbOutputEffect.OpenExclusive -> openExclusiveForState()
            UsbOutputEffect.RetargetExclusive -> retargetExclusiveForState()
            UsbOutputEffect.CloseExclusive -> closeExclusiveForState()
            UsbOutputEffect.RestoreFrozenPlaybackIntent -> restoreFrozenPlaybackIntentForState()
            UsbOutputEffect.WaitForSharedRoute -> scheduleSharedRouteProbe(state.generation)
            UsbOutputEffect.BuildSharedPlayer -> buildSharedPlayerForState()
            UsbOutputEffect.ShowReconnectRequired -> Toast.makeText(
                context,
                "USB 独占已关闭，请重新插拔 DAC 以恢复 Android 共享输出",
                Toast.LENGTH_LONG,
            ).show()
            is UsbOutputEffect.ShowError -> {
                DiagnosticLog.event("UsbOutputState", "failed code=${effect.code} message=${effect.message}")
                Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun scheduleSharedQuiescenceProbe(generation: Long) {
        if (generation != state.generation || state.phase != UsbOutputPhase.SharedQuiescing) return
        val currentOperation = beginOperation(generation, UsbOutputPhase.SharedQuiescing) ?: return
        val identity = candidate?.identity ?: run {
            val selected = effects.discoverUsbAudioDevice() as? UsbAudioSelection.Selected
            selected?.candidate?.also { candidate = it }?.identity
        }
        val policy = UsbSharedQuiescencePolicyResolver.resolve(Build.MANUFACTURER, Build.MODEL, identity)
        sharedQuiesceStartedAtMs = SystemClock.elapsedRealtime()
        sharedQuiesceSettleMs = policy.settleMs
        DiagnosticLog.event(
            "UsbOutputState",
            "shared-quiesce start generation=$generation settleMs=${policy.settleMs} " +
                "host=${Build.MANUFACTURER}/${Build.MODEL} identity=${identity?.vendorId}:${identity?.productId}",
        )
        postOperation(currentOperation, SHARED_QUIESCE_POLL_MS) { pollSharedQuiescence(currentOperation) }
    }

    private fun pollSharedQuiescence(currentOperation: UsbOutputOperation) {
        if (!isCurrentOperation(currentOperation)) return
        val elapsedMs = SystemClock.elapsedRealtime() - sharedQuiesceStartedAtMs
        if (elapsedMs >= sharedQuiesceSettleMs) {
            DiagnosticLog.event(
                "UsbOutputState",
                "shared-quiesce complete generation=${currentOperation.generation} elapsedMs=$elapsedMs " +
                    "settleMs=$sharedQuiesceSettleMs",
            )
            dispatch(UsbOutputEvent.SharedQuiesced(currentOperation.generation))
            return
        }
        postOperation(currentOperation, SHARED_QUIESCE_POLL_MS) { pollSharedQuiescence(currentOperation) }
    }

    private fun scheduleExclusiveTargetProbe(generation: Long) {
        if (generation != state.generation || state.phase != UsbOutputPhase.ExclusivePreparing) return
        val currentOperation = beginOperation(generation, UsbOutputPhase.ExclusivePreparing) ?: return
        probePollAttempts = 0
        probeStableObservations = 0
        probeIdentity = null
        probeRuntimeHandle = null
        postOperation(currentOperation) { pollExclusiveTarget(currentOperation) }
    }

    private fun pollExclusiveTarget(currentOperation: UsbOutputOperation) {
        if (!isCurrentOperation(currentOperation)) return
        val selection = effects.discoverUsbAudioDevice()
        if (!isCurrentOperation(currentOperation)) return
        when (selection) {
            is UsbAudioSelection.Selected -> {
                val selected = selection.candidate
                val same = probeIdentity == selected.identity && probeRuntimeHandle == selected.runtimeHandle
                probeIdentity = selected.identity
                probeRuntimeHandle = selected.runtimeHandle
                probeStableObservations = if (same) probeStableObservations + 1 else 1
                candidate = selected
                sharedReturnProbeIdentity = selected.identity
                if (probeStableObservations >= EXCLUSIVE_TARGET_STABLE_OBSERVATIONS) {
                    dispatch(UsbOutputEvent.TargetStable)
                    return
                }
            }
            is UsbAudioSelection.Ambiguous -> {
                dispatch(
                    UsbOutputEvent.PreparationFailed(
                        currentOperation.generation,
                        "MULTIPLE_USB_AUDIO_DEVICES",
                        "Multiple USB audio output devices are attached; target selection is ambiguous.",
                    ),
                )
                return
            }
            UsbAudioSelection.NotFound -> {
                probeStableObservations = 0
                probeIdentity = null
                probeRuntimeHandle = null
                candidate = null
            }
        }
        probePollAttempts += 1
        if (probePollAttempts >= EXCLUSIVE_TARGET_READY_MAX_POLLS) {
            dispatch(
                UsbOutputEvent.PreparationFailed(
                    currentOperation.generation,
                    "USB_AUDIO_DEVICE_NOT_READY",
                    "USB audio device did not become stable and ready for exclusive playback.",
                ),
            )
            return
        }
        postOperation(currentOperation, EXCLUSIVE_TARGET_READY_POLL_MS) { pollExclusiveTarget(currentOperation) }
    }

    private fun requestPermissionForState() {
        val current = state
        if (current.phase != UsbOutputPhase.PermissionWaiting) return
        val currentOperation = beginOperation(current.generation, UsbOutputPhase.PermissionWaiting) ?: return
        val currentCandidate = candidate
        if (currentCandidate == null) {
            dispatch(UsbOutputEvent.TargetLost)
            return
        }
        effects.requestOutputPermission(
            UsbOutputPermissionRequest(
                operationId = currentOperation.id,
                mode = current.desiredMode.toExclusiveMode(),
                identity = currentCandidate.identity,
                runtimeHandle = currentCandidate.runtimeHandle,
            ),
        )
    }

    private fun openExclusiveForState() {
        val current = state
        if (current.phase != UsbOutputPhase.ExclusiveOpening) return
        val currentCandidate = candidate
        if (currentCandidate == null) {
            dispatch(UsbOutputEvent.TargetLost)
            return
        }
        val epoch = owner.armAuthorizedTarget(
            current.desiredMode.toExclusiveMode(),
            currentCandidate.identity,
            currentCandidate.runtimeHandle,
        )
        owner.awaitIdle()
        if (state.generation != current.generation || state.phase != UsbOutputPhase.ExclusiveOpening) return
        ownerEpoch = epoch
        playback.rebuildExclusive(
            current.desiredMode,
            UsbHybridPlaybackBinding(owner, effects, epoch),
            outputHandoff,
            "usb-state-open:$switchReason",
        )
    }

    private fun retargetExclusiveForState() {
        val current = state
        if (current.phase != UsbOutputPhase.ExclusiveOpening) return
        val activeOwnerEpoch = ownerEpoch ?: run {
            dispatch(
                UsbOutputEvent.ExclusiveOpenFailed(
                    current.generation,
                    "EXCLUSIVE_OWNER_MISSING",
                    "Exclusive USB ownership was lost before mode retarget.",
                ),
            )
            return
        }
        val currentCandidate = candidate ?: (effects.discoverUsbAudioDevice() as? UsbAudioSelection.Selected)?.candidate ?: run {
            dispatch(UsbOutputEvent.TargetLost)
            return
        }
        candidate = currentCandidate
        val retainedEpoch = owner.retargetAuthorizedTarget(
            current.desiredMode.toExclusiveMode(),
            currentCandidate.identity,
            currentCandidate.runtimeHandle,
        )
        if (retainedEpoch != activeOwnerEpoch) {
            dispatch(
                UsbOutputEvent.ExclusiveOpenFailed(
                    current.generation,
                    "EXCLUSIVE_EPOCH_CHANGED",
                    "Exclusive USB ownership changed during mode retarget.",
                ),
            )
            return
        }
        playback.rebuildExclusive(
            current.desiredMode,
            UsbHybridPlaybackBinding(owner, effects, activeOwnerEpoch),
            outputHandoff,
            "usb-state-retarget:$switchReason",
        )
    }

    private fun closeExclusiveForState() {
        runCatching {
            owner.retireExclusiveSession()
            owner.awaitIdle()
        }.onFailure { error ->
            DiagnosticLog.event("UsbOutputState", "close-exclusive failed error=${error.message}")
        }
        ownerEpoch = null
    }

    private fun restoreFrozenPlaybackIntentForState() {
        val current = state
        if (current.phase !is UsbOutputPhase.ExclusiveActive) return
        val semanticPlayWhenReady = current.frozenIntent
            ?.takeIf { it.generation == current.generation }
            ?.playWhenReady
            ?: false
        DiagnosticLog.event(
            "UsbOutputState",
            "restore-frozen-intent generation=${current.generation} desired=${current.desiredMode} " +
                "transport=${current.activeTransport} playWhenReady=$semanticPlayWhenReady",
        )
        ownerEpoch?.let { owner.setSemanticPlayWhenReady(it, semanticPlayWhenReady) }
        playback.restorePlaybackIntent(semanticPlayWhenReady)
    }

    private fun buildSharedPlayerForState() {
        if (state.desiredMode != DesiredUsbOutput.Shared || state.phase != UsbOutputPhase.SharedActive) return
        runCatching {
            owner.retireExclusiveSession()
            owner.awaitIdle()
        }
        playback.rebuildShared(outputHandoff, "usb-state-shared:$switchReason")
        ownerEpoch = null
    }

    private fun scheduleSharedRouteProbe(generation: Long) {
        if (generation != state.generation || state.phase != UsbOutputPhase.SharedRouteWaiting) return
        val currentOperation = beginOperation(generation, UsbOutputPhase.SharedRouteWaiting) ?: return
        sharedRouteProbeStartedAtMs = SystemClock.elapsedRealtime()
        if (sharedAudioAddSerial > sharedRouteWaitBaselineAddSerial) {
            DiagnosticLog.event(
                "UsbOutputState",
                "shared-route add already observed generation=$generation serial=$sharedAudioAddSerial " +
                    "baseline=$sharedRouteWaitBaselineAddSerial",
            )
            completeSharedRouteRecovery()
            return
        }
        DiagnosticLog.event(
            "UsbOutputState",
            "shared-route waiting generation=$generation baseline=$sharedRouteWaitBaselineAddSerial serial=$sharedAudioAddSerial",
        )
        postOperation(currentOperation, SHARED_RETURN_ROUTE_TIMEOUT_MS) { onSharedRouteTimeout(currentOperation) }
    }

    private fun onSharedRouteTimeout(currentOperation: UsbOutputOperation) {
        if (!isCurrentOperation(currentOperation)) return
        val elapsedMs = SystemClock.elapsedRealtime() - sharedRouteProbeStartedAtMs
        DiagnosticLog.event(
            "UsbOutputState",
            "shared-route timeout generation=${currentOperation.generation} elapsedMs=$elapsedMs " +
                "baseline=$sharedRouteWaitBaselineAddSerial serial=$sharedAudioAddSerial",
        )
        if (!state.sharedReturnRequiresReconnect) {
            sharedReturnProbeIdentity?.let { identity ->
                UsbSharedReturnCapabilityStore.setCapability(
                    context,
                    identity,
                    UsbSharedReturnCapability.ReconnectRequired,
                )
            }
        }
        dispatch(UsbOutputEvent.AndroidSharedRouteUnavailable)
    }

    private fun completeSharedRouteRecovery() {
        if (state.desiredMode != DesiredUsbOutput.Shared || state.phase != UsbOutputPhase.SharedRouteWaiting) return
        val recoveredAfterReconnect = state.sharedReturnRequiresReconnect
        if (!recoveredAfterReconnect) {
            sharedReturnProbeIdentity?.let { identity ->
                UsbSharedReturnCapabilityStore.setCapability(
                    context,
                    identity,
                    UsbSharedReturnCapability.HotSwitchVerified,
                )
            }
        }
        dispatch(UsbOutputEvent.AndroidSharedRouteReady)
        if (recoveredAfterReconnect) {
            Toast.makeText(context, "Android 共享输出已恢复", Toast.LENGTH_SHORT).show()
        }
        sharedReturnProbeIdentity = null
    }

    private fun isAndroidUsbAudioOutput(device: AudioDeviceInfo): Boolean =
        device.isSink &&
            (device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY)

    private fun isTransientExclusiveOpenFailure(code: String, message: String): Boolean =
        code.endsWith("OPEN_FAILED") &&
            message.contains("USBDEVFS_SUBMITURB", ignoreCase = true) &&
            (message.contains("No such file or directory", ignoreCase = true) ||
                message.contains("ENOENT", ignoreCase = true))

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

    private companion object {
        private val nextOperationId = AtomicLong()
        const val USB_TELEMETRY_INTERVAL_MS = 1_000L
        const val SHARED_RETURN_ROUTE_TIMEOUT_MS = 5_000L
        const val SHARED_QUIESCE_POLL_MS = 50L
        const val EXCLUSIVE_TARGET_READY_POLL_MS = 100L
        const val EXCLUSIVE_TARGET_READY_MAX_POLLS = 50
        const val EXCLUSIVE_TARGET_STABLE_OBSERVATIONS = 5
    }
}
