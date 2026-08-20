package com.mica.music.media.usbhybrid

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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
        control.execute {
            val target = synchronized(publicationLock) {
                authorizedTarget?.takeIf { it.epoch == expectedEpoch && epoch == expectedEpoch && !released }
            }
            if (target == null || !isCurrent(expectedEpoch)) {
                completion.complete(facts.value)
                return@execute
            }
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
                publishFailureIfCurrent(
                    expectedEpoch,
                    "OPEN_FAILED",
                    error.message ?: "USB open failed.",
                )
                completion.complete(facts.value)
                return@execute
            }
            val session = opened.sessionId
            if (!isCurrent(expectedEpoch)) {
                if (session != null) effects.close(session)
                completion.complete(facts.value)
                return@execute
            }
            if (session == null) {
                val failure = opened.failure ?: UsbFailure("OPEN_FAILED", "USB open returned no session.")
                publishFailureIfCurrent(expectedEpoch, failure.code, failure.message)
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
                        sessionId = session.nativeId,
                        claimed = opened.claimed,
                        exclusive = opened.claimed,
                        transportExact = opened.transportExact,
                        signalExact = opened.signalExact,
                        sourceEncoding = opened.sourceEncoding,
                        usbBitResolution = opened.usbBitResolution,
                        sampleRate = opened.sampleRate,
                        channels = opened.channels,
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

    fun currentDiscoveryRevision(): UsbDiscoveryRevision =
        synchronized(publicationLock) { discoveryRevision }

    fun refreshTelemetry(realtime: UsbHybridRealtimePort) {
        control.execute {
            val session = synchronized(publicationLock) { activeSession } ?: return@execute
            val sampled = realtime.telemetry(session)
            synchronized(publicationLock) {
                if (released || activeSession != session || epoch != session.epoch) return@synchronized
                publishFactsLocked(mutableFacts.value.copy(telemetry = sampled))
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
            effects.publishActiveEpoch(epoch)
            pending = null
            authorizedTarget = null
            publishFactsLocked(mutableFacts.value.copy(
                requestEpoch = epoch.value,
                activeMode = null,
                sessionId = null,
            claimed = false,
            exclusive = false,
            telemetry = null,
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
    ): UsbRequestEpoch = synchronized(publicationLock) {
        check(!released) { "USB Hybrid session owner is released." }
        epoch = UsbRequestEpoch(epoch.value + 1L)
        effects.publishActiveEpoch(epoch)
        pending = null
        authorizedTarget = null
        publishFactsLocked(UsbPlaybackFacts(
            requestEpoch = epoch.value,
            discoveryRevision = discoveryRevision.value,
            requestedMode = mode,
            identity = identity,
            runtimeHandle = runtimeHandle,
            permission = if (mode == UsbExclusiveMode.SHARED_PCM) {
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
