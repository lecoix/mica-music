package com.mica.music.media.usbhybrid

import com.mica.music.usb.UsbStableIdentity

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sole request-epoch, control-side-effect and facts-publication owner for Hybrid USB output.
 *
 * Epoch invalidation is synchronous so it can supersede an uninterruptible control operation.
 * Every result is checked again on the control executor immediately before cleanup or facts
 * publication. Realtime writes are deliberately outside this class and are fenced in Native by
 * the same epoch plus a native session id.
 */
private data class SemanticWriteGate(
    val epoch: UsbRequestEpoch,
    val playWhenReady: Boolean,
)

class UsbHybridSessionOwner(
    private val effects: UsbHybridControlEffects,
    private val factsPublisher: (UsbPlaybackFacts) -> Unit = {},
    private val control: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MicaUsbHybridControl")
    },
) : AutoCloseable {
    private val publicationLock = Any()
    private val mutableFacts = MutableStateFlow(UsbPlaybackFacts())
    val facts: StateFlow<UsbPlaybackFacts> = mutableFacts.asStateFlow()

    private var epoch = UsbRequestEpoch(0L)
    private var discoveryRevision = UsbDiscoveryRevision(0L)
    private var pending: UsbPermissionRequest? = null
    private var authorizedTarget: UsbPermissionRequest? = null
    private var activeSession: UsbTransportSessionId? = null
    @Volatile private var semanticWriteGate = SemanticWriteGate(epoch, playWhenReady = false)
    private var released = false

    init {
        factsPublisher(mutableFacts.value)
    }

    fun request(
        mode: UsbExclusiveMode,
        identity: UsbStableIdentity?,
        runtimeHandle: UsbRuntimeHandle?,
    ): UsbRequestEpoch {
        val next = mintEpoch(mode, identity, runtimeHandle)
        control.execute {
            if (!isCurrent(next)) return@execute
            closeActiveIfCurrent(next)
            if (mode == UsbExclusiveMode.SHARED_PCM) return@execute
            val targetIdentity = identity
            val targetRuntime = runtimeHandle
            if (targetIdentity == null || targetRuntime == null) {
                publishFailureIfCurrent(next, "TARGET_REQUIRED", "USB target identity is required.")
                return@execute
            }
            val request = UsbPermissionRequest(next, mode, targetIdentity, targetRuntime)
            synchronized(publicationLock) {
                if (epoch != next || released) return@synchronized
                pending = request
            }
            if (!isCurrent(next)) return@execute
            effects.requestPermission(request)
        }
        return next
    }

    /**
     * Arms an already-authorized target for the output state machine. Permission orchestration stays
     * above this owner; this method only mints/fences the exclusive session epoch.
     */
    fun armAuthorizedTarget(
        mode: UsbExclusiveMode,
        identity: UsbStableIdentity,
        runtimeHandle: UsbRuntimeHandle,
    ): UsbRequestEpoch {
        require(mode != UsbExclusiveMode.SHARED_PCM)
        val next = mintEpoch(
            mode = mode,
            identity = identity,
            runtimeHandle = runtimeHandle,
            permissionOverride = PermissionState.GRANTED,
        )
        synchronized(publicationLock) {
            if (epoch == next && !released) {
                authorizedTarget = UsbPermissionRequest(next, mode, identity, runtimeHandle)
            }
        }
        control.execute { closeActiveIfCurrent(next) }
        return next
    }

    /** Retargets an already-authorized exclusive ownership epoch without retiring the USB session. */
    fun retargetAuthorizedTarget(
        mode: UsbExclusiveMode,
        identity: UsbStableIdentity,
        runtimeHandle: UsbRuntimeHandle,
    ): UsbRequestEpoch {
        require(mode != UsbExclusiveMode.SHARED_PCM)
        return synchronized(publicationLock) {
            check(!released) { "USB Hybrid session owner is released." }
            val current = epoch
            authorizedTarget = UsbPermissionRequest(current, mode, identity, runtimeHandle)
            pending = null
            publishFactsLocked(mutableFacts.value.copy(
                requestedMode = mode,
                identity = identity,
                runtimeHandle = runtimeHandle,
                permission = PermissionState.GRANTED,
                failure = null,
            ))
            current
        }
    }

    /** Retires the active exclusive session without initiating permission or another open. */
    fun retireExclusiveSession(): UsbRequestEpoch {
        val next = mintEpoch(
            mode = UsbExclusiveMode.SHARED_PCM,
            identity = null,
            runtimeHandle = null,
            permissionOverride = PermissionState.NOT_REQUIRED,
        )
        control.execute { closeActiveIfCurrent(next) }
        return next
    }

    /**
     * Closes only the current physical USB session while retaining the exclusive request epoch,
     * authorized target and permission. This is the transport-level half of a technical reopen:
     * Media3 keeps its playback stack/binding and can request a fresh session on the same epoch.
     */
    fun retireActiveSessionRetainingEpoch(
        expectedEpoch: UsbRequestEpoch,
    ): CompletableFuture<UsbPlaybackFacts> {
        val completion = CompletableFuture<UsbPlaybackFacts>()
        control.execute {
            val session = synchronized(publicationLock) {
                if (released || epoch != expectedEpoch || authorizedTarget?.epoch != expectedEpoch) {
                    null
                } else {
                    activeSession
                }
            }
            if (session == null) {
                completion.complete(facts.value)
                return@execute
            }

            try {
                effects.close(session)
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
                return@execute
            }
            synchronized(publicationLock) {
                if (!released && epoch == expectedEpoch && activeSession == session) {
                    activeSession = null
                    publishFactsLocked(mutableFacts.value.copy(
                        activeMode = null,
                        activeTransport = null,
                        sessionId = null,
                        claimed = false,
                        exclusive = false,
                        transportExact = false,
                        signalExact = false,
                        sourceEncoding = null,
                        usbBitResolution = null,
                        sampleRate = null,
                        channels = null,
                        streamFormat = null,
                        telemetry = null,
                        failure = null,
                    ))
                }
            }
            completion.complete(facts.value)
        }
        return completion
    }
    /** Fences and cleans the old session, but deliberately does not request permission or open. */
    fun failRequest(
        mode: UsbExclusiveMode,
        identity: UsbStableIdentity?,
        runtimeHandle: UsbRuntimeHandle?,
        failure: UsbFailure,
    ): UsbRequestEpoch {
        val next = mintEpoch(
            mode = mode,
            identity = identity,
            runtimeHandle = runtimeHandle,
            failure = failure,
            permissionOverride = PermissionState.NOT_REQUIRED,
        )
        control.execute { closeActiveIfCurrent(next) }
        return next
    }

    fun onPermissionResult(result: UsbPermissionResult) {
        control.execute {
            val expected = synchronized(publicationLock) { pending }
            if (expected == null || expected.epoch != result.epoch || !isCurrent(result.epoch)) {
                return@execute
            }
            if (!matches(expected, result)) {
                synchronized(publicationLock) {
                    if (epoch != result.epoch || pending != expected || released) return@synchronized
                    pending = null
                    authorizedTarget = null
                    publishFactsLocked(mutableFacts.value.copy(
                        permission = PermissionState.DENIED,
                        failure = UsbFailure(
                            "TARGET_CHANGED",
                            "USB target identity or runtime handle changed during permission.",
                        ),
                    ))
                }
                return@execute
            }
            if (!result.granted) {
                synchronized(publicationLock) {
                    if (!matches(pending, result) || epoch != result.epoch || released) return@synchronized
                    pending = null
                    publishFactsLocked(mutableFacts.value.copy(
                        permission = PermissionState.DENIED,
                        failure = UsbFailure("PERMISSION_DENIED", "USB permission was denied."),
                    ))
                }
                return@execute
            }

            synchronized(publicationLock) {
                if (!matches(pending, result) || epoch != result.epoch || released) return@synchronized
                publishFactsLocked(mutableFacts.value.copy(permission = PermissionState.GRANTED))
            }
            if (!isCurrent(result.epoch)) return@execute
            synchronized(publicationLock) {
                if (epoch != result.epoch || released || !matches(pending, result)) return@synchronized
                pending = null
                authorizedTarget = expected
                publishFactsLocked(mutableFacts.value.copy(
                    permission = PermissionState.GRANTED,
                    failure = null,
                ))
            }
        }
    }

    fun requestOpen(
        expectedEpoch: UsbRequestEpoch,
        format: UsbStreamFormat,
    ): CompletableFuture<UsbPlaybackFacts> {
        val completion = CompletableFuture<UsbPlaybackFacts>()
        val enqueuedAtNs = System.nanoTime()
        control.execute {
            val startedAtNs = System.nanoTime()
            DiagnosticLog.event(
                "UsbHybridSessionOwner",
                "request-open start epoch=${expectedEpoch.value} format=$format queueMs=${(startedAtNs - enqueuedAtNs) / 1_000_000L}",
            )
            val (target, previousSession) = synchronized(publicationLock) {
                val currentTarget = authorizedTarget?.takeIf {
                    it.epoch == expectedEpoch && epoch == expectedEpoch && !released
                }
                currentTarget to activeSession
            }
            if (target == null || !isCurrent(expectedEpoch)) {
                completion.complete(facts.value)
                return@execute
            }
            val physicalOpenStartedAtNs = System.nanoTime()
            val opened = try {
                effects.open(
                    UsbOpenRequest(
                        target.epoch,
                        target.mode,
                        target.identity,
                        target.runtimeHandle,
                        format,
                    ),
                )
            } catch (error: Throwable) {
                retireFailedOpen(
                    expectedEpoch,
                    previousSession,
                    UsbFailure("OPEN_FAILED", error.message ?: "USB open failed."),
                )
                completion.complete(facts.value)
                return@execute
            }
            DiagnosticLog.event(
                "UsbHybridSessionOwner",
                "request-open physical-complete epoch=${expectedEpoch.value} format=$format openMs=${(System.nanoTime() - physicalOpenStartedAtNs) / 1_000_000L} session=${opened.sessionId?.nativeId} failure=${opened.failure?.code}",
            )
            val session = opened.sessionId
            if (!isCurrent(expectedEpoch)) {
                if (session != null) effects.close(session)
                completion.complete(facts.value)
                return@execute
            }
            if (session == null) {
                val failure = opened.failure ?: UsbFailure("OPEN_FAILED", "USB open returned no session.")
                retireFailedOpen(expectedEpoch, previousSession, failure)
                completion.complete(facts.value)
                return@execute
            }
            var stale = false
            synchronized(publicationLock) {
                if (epoch != expectedEpoch || released || authorizedTarget != target) {
                    stale = true
                } else {
                    activeSession = session
                    publishFactsLocked(mutableFacts.value.copy(
                        activeMode = target.mode,
                        activeTransport = when (format) {
                            is UsbStreamFormat.Pcm -> UsbActiveTransport.PCM
                            is UsbStreamFormat.Dsd -> if (format.native) {
                                UsbActiveTransport.NATIVE_DSD
                            } else {
                                UsbActiveTransport.DOP
                            }
                        },
                        sessionId = session.nativeId,
                        claimed = opened.claimed,
                        exclusive = opened.claimed,
                        transportExact = opened.transportExact,
                        signalExact = opened.signalExact,
                        sourceEncoding = opened.sourceEncoding,
                        usbBitResolution = opened.usbBitResolution,
                        sampleRate = opened.sampleRate,
                        channels = opened.channels,
                        streamFormat = opened.streamFormat,
                        failure = null,
                    ))
                }
            }
            if (stale) effects.close(session)
            completion.complete(facts.value)
        }
        return completion
    }

    fun onAttached() {
        synchronized(publicationLock) {
            discoveryRevision = UsbDiscoveryRevision(discoveryRevision.value + 1L)
            publishFactsLocked(mutableFacts.value.copy(discoveryRevision = discoveryRevision.value))
        }
    }

    fun onDetached(runtimeHandle: UsbRuntimeHandle) {
        val isTarget = synchronized(publicationLock) {
            discoveryRevision = UsbDiscoveryRevision(discoveryRevision.value + 1L)
            val current = mutableFacts.value
            val target = current.runtimeHandle == runtimeHandle || pending?.runtimeHandle == runtimeHandle
            if (!target) {
                publishFactsLocked(current.copy(discoveryRevision = discoveryRevision.value))
            }
            target
        }
        if (isTarget) {
            val next = mintEpoch(
                synchronized(publicationLock) { mutableFacts.value.requestedMode },
                synchronized(publicationLock) { mutableFacts.value.identity },
                runtimeHandle,
                failure = UsbFailure("TARGET_DETACHED", "The active USB target was detached."),
            )
            control.execute { closeActiveIfCurrent(next) }
        }
    }

    fun currentEpoch(): UsbRequestEpoch = synchronized(publicationLock) { epoch }

    /**
     * Semantic PLAY/PAUSE is the source-data write authority. Media3 may deliver AudioSink.pause()
     * late, so PCM source submission must not depend on that runtime callback.
     */
    fun setSemanticPlayWhenReady(expectedEpoch: UsbRequestEpoch, playWhenReady: Boolean): Boolean =
        synchronized(publicationLock) {
            if (released || epoch != expectedEpoch) return@synchronized false
            semanticWriteGate = SemanticWriteGate(expectedEpoch, playWhenReady)
            DiagnosticLog.event(
                "UsbHybridSessionOwner",
                "semantic-write-gate epoch=${expectedEpoch.value} playWhenReady=$playWhenReady",
            )
            true
        }

    /** Lock-free realtime read; epoch changes publish a closed gate before stale writers can proceed. */
    fun pcmSourceWriteAllowed(expectedEpoch: UsbRequestEpoch): Boolean {
        val gate = semanticWriteGate
        return gate.epoch == expectedEpoch && gate.playWhenReady
    }

    fun currentDiscoveryRevision(): UsbDiscoveryRevision =
        synchronized(publicationLock) { discoveryRevision }

    fun refreshTelemetry(realtime: UsbHybridRealtimePort) {
        control.execute {
            val session = synchronized(publicationLock) { activeSession } ?: return@execute
            val sampled = realtime.telemetry(session)
            val diagnostics = realtime.sessionDiagnostics(session).takeIf { it.isNotEmpty() }
            val hardwareVolume = diagnostics?.get("hardwareVolume") as? Map<*, *>
            val digitalVolumeActive = hardwareVolume?.get("digitalVolumeActive") == true
            synchronized(publicationLock) {
                if (released || activeSession != session || epoch != session.epoch) return@synchronized
                val current = mutableFacts.value
                val refreshedSignalExact = when (current.activeTransport) {
                    UsbActiveTransport.PCM, UsbActiveTransport.DOP -> current.transportExact && !digitalVolumeActive
                    UsbActiveTransport.NATIVE_DSD -> current.signalExact && !digitalVolumeActive
                    null -> current.signalExact
                }
                publishFactsLocked(
                    current.copy(
                        signalExact = refreshedSignalExact,
                        telemetry = sampled,
                        sessionDiagnostics = diagnostics,
                    ),
                )
            }
        }
    }
    fun awaitIdle(timeoutSeconds: Long = 5L) {
        control.submit {}.get(timeoutSeconds, TimeUnit.SECONDS)
    }

    override fun close() {
        val next = synchronized(publicationLock) {
            if (released) return
            released = true
            epoch = UsbRequestEpoch(epoch.value + 1L)
            semanticWriteGate = SemanticWriteGate(epoch, playWhenReady = false)
            effects.publishActiveEpoch(epoch)
            pending = null
            authorizedTarget = null
            publishFactsLocked(mutableFacts.value.copy(
                requestEpoch = epoch.value,
                activeMode = null,
                activeTransport = null,
                sessionId = null,
            claimed = false,
            exclusive = false,
            telemetry = null,
            streamFormat = null,
            ))
            epoch
        }
        control.submit { closeActiveEvenIfReleased(next) }.get(5L, TimeUnit.SECONDS)
        control.shutdown()
    }

    private fun mintEpoch(
        mode: UsbExclusiveMode,
        identity: UsbStableIdentity?,
        runtimeHandle: UsbRuntimeHandle?,
        failure: UsbFailure? = null,
        permissionOverride: PermissionState? = null,
    ): UsbRequestEpoch = synchronized(publicationLock) {
        check(!released) { "USB Hybrid session owner is released." }
        epoch = UsbRequestEpoch(epoch.value + 1L)
        semanticWriteGate = SemanticWriteGate(epoch, playWhenReady = false)
        effects.publishActiveEpoch(epoch)
        pending = null
        authorizedTarget = null
        publishFactsLocked(UsbPlaybackFacts(
            requestEpoch = epoch.value,
            discoveryRevision = discoveryRevision.value,
            requestedMode = mode,
            identity = identity,
            runtimeHandle = runtimeHandle,
            permission = permissionOverride ?: if (mode == UsbExclusiveMode.SHARED_PCM) {
                PermissionState.NOT_REQUIRED
            } else {
                PermissionState.REQUESTED
            },
            failure = failure,
            telemetry = null,
        ))
        epoch
    }

    private fun closeActiveIfCurrent(expectedEpoch: UsbRequestEpoch) {
        if (!isCurrent(expectedEpoch)) return
        val session = synchronized(publicationLock) {
            if (epoch != expectedEpoch || released) return@synchronized null
            activeSession.also { activeSession = null }
        } ?: return
        // Once detached from owner state this identity-scoped session must always be cleaned up.
        // Native close is fenced by (epoch, sessionId), so it cannot close a newer winner.
        effects.close(session)
    }

    private fun closeActiveEvenIfReleased(@Suppress("UNUSED_PARAMETER") expectedEpoch: UsbRequestEpoch) {
        val session = synchronized(publicationLock) {
            activeSession.also { activeSession = null }
        } ?: return
        effects.close(session)
    }

    private fun isCurrent(expected: UsbRequestEpoch): Boolean =
        synchronized(publicationLock) { !released && epoch == expected }

    private fun publishFailureIfCurrent(epoch: UsbRequestEpoch, code: String, message: String) {
        synchronized(publicationLock) {
            if (this.epoch != epoch || released) return
            publishFactsLocked(mutableFacts.value.copy(failure = UsbFailure(code, message)))
        }
    }

    private fun retireFailedOpen(
        expectedEpoch: UsbRequestEpoch,
        previousSession: UsbTransportSessionId?,
        failure: UsbFailure,
    ) {
        // The transport may already have closed the prior session while trying the new format.
        // Cleanup is nevertheless identity-scoped and unconditional; a stale close cannot affect a winner.
        if (previousSession != null) effects.close(previousSession)
        synchronized(publicationLock) {
            if (epoch != expectedEpoch || released) return
            if (activeSession == previousSession) activeSession = null
            publishFactsLocked(mutableFacts.value.copy(
                activeMode = null,
                activeTransport = null,
                sessionId = null,
                claimed = false,
                exclusive = false,
                transportExact = false,
                signalExact = false,
                sourceEncoding = null,
                usbBitResolution = null,
                sampleRate = null,
                channels = null,
                streamFormat = null,
                telemetry = null,
                failure = failure,
            ))
        }
    }

    /** Caller must hold [publicationLock]. */
    private fun publishFactsLocked(next: UsbPlaybackFacts) {
        mutableFacts.value = next
        factsPublisher(next)
    }

    private fun matches(expected: UsbPermissionRequest?, result: UsbPermissionResult): Boolean =
        expected != null &&
            expected.epoch == result.epoch &&
            expected.mode == result.mode &&
            expected.identity == result.identity &&
            expected.runtimeHandle == result.runtimeHandle
}
