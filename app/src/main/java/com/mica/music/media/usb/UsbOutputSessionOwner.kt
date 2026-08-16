package com.mica.music.media.usb

import com.mica.music.util.DiagnosticLog
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

internal class StaleUsbOutputRequestException(generation: Long) :
    IllegalStateException("USB output request $generation is stale")

internal enum class UsbDeviceDetachDisposition {
    RELEASED_CURRENT,
    ORPHANED_CURRENT,
    STALE_RUNTIME,
}

private sealed interface UsbDeviceDetachClaim {
    data class Current(val token: UsbOutputRequestToken) : UsbDeviceDetachClaim
    data object Orphaned : UsbDeviceDetachClaim
    data object Stale : UsbDeviceDetachClaim
}

/**
 * Sole generation owner and sole serialized seam for USB output side effects.
 *
 * A new request publishes its generation before waiting for the transport lock so an old worker
 * can stop promptly. Cleanup uses a separate lease: once cleanup owns the lock it remains valid
 * even if another request supersedes the request that initiated cleanup. The newer request still
 * cannot open until cleanup has restored and released the old device.
 */
internal class UsbOutputSessionOwner(
    private val onGenerationPublished: (Long) -> Unit = {},
    private val beforeFactsPublication: (PlaybackOutputFacts) -> Unit = {},
    private val afterFactsPublication: (PlaybackOutputFacts) -> Unit = {},
) {
    private val generation = AtomicLong(0L)
    private val generationPublicationLock = Any()
    private val transportLock = ReentrantLock()
    private val factsRef = AtomicReference(PlaybackOutputFacts())
    @Volatile
    private var activeSession: UsbOutputSession? = null
    private var activeRequest: UsbOutputRequest? = null
    private val pendingRedemptions = linkedMapOf<UsbOutputRequestToken, UsbOutputRedemptionBinding>()
    private var openingRedemption: UsbOutputRedemptionBinding? = null
    private var activeRedemption: UsbOutputRedemptionBinding? = null

    val facts: PlaybackOutputFacts
        get() = factsRef.get()

    /** Debug harness requests fail without invalidating a live production session. */
    fun beginHarnessRequest(): UsbOutputRequestToken? {
        if (activeSession != null) return null
        return publishNextGeneration()
    }

    fun invalidate(): Long = publishNextGeneration().value

    fun isCurrent(token: UsbOutputRequestToken): Boolean = generation.get() == token.value

    /** Reserves one exact owner generation for a protocol stack before its first USB side effect. */
    internal fun reserveRedemption(request: UsbOutputRequest): UsbOutputRedemptionBinding {
        val token = publishNextGeneration()
        transportLock.lock()
        return try {
            val binding = synchronized(generationPublicationLock) {
                check(generation.get() == token.value) {
                    "USB redemption reservation was superseded before the owner seam"
                }
                invalidateRedemptionsLocked()
                activeSession?.let { session ->
                    publishFor(
                        token,
                        facts.copy(generation = token.value, phase = UsbOutputPhase.RELEASING),
                    )
                    session.release(cleanupLease(), "redemption-replaced")
                    activeSession = null
                    activeRequest = null
                }
                val binding = UsbOutputRedemptionBinding(request, token, this)
                pendingRedemptions[token] = binding
                binding
            }
            binding
        } finally {
            transportLock.unlock()
        }
    }

    internal fun <T : UsbOutputSession> consumeRedemption(
        binding: UsbOutputRedemptionBinding,
        open: (UsbOutputRequestLease) -> T,
    ): T {
        transportLock.lock()
        return try {
            check(pendingRedemptions[binding.token] === binding) {
                "USB redemption is not the current pending reservation"
            }
            replaceLocked(binding.token, binding.request, binding, open)
        } finally {
            transportLock.unlock()
        }
    }

    internal fun isBindingCurrent(binding: UsbOutputRedemptionBinding): Boolean {
        transportLock.lock()
        return try {
            isCurrent(binding.token) &&
                ((pendingRedemptions[binding.token] === binding) ||
                    (openingRedemption === binding) ||
                    (activeRedemption === binding))
        } finally {
            transportLock.unlock()
        }
    }

    internal fun ensureRequestBinding(
        binding: UsbOutputRedemptionBinding,
        lease: UsbOutputRequestLease,
    ) {
        transportLock.lock()
        try {
            if (!isCurrent(binding.token)) {
                throw StaleUsbOutputRequestException(binding.token.value)
            }
            check(
                pendingRedemptions[binding.token] === binding || openingRedemption === binding,
            )
            check(lease.token == binding.token) { "USB request lease does not match binding" }
            lease.ensureCurrent()
        } finally {
            transportLock.unlock()
        }
    }

    internal fun isBindingLeaseCurrent(
        binding: UsbOutputRedemptionBinding,
        lease: com.mica.music.media.usb.protocol.ActiveWriteLease,
    ): Boolean {
        transportLock.lock()
        return try {
            isCurrent(binding.token) && activeRedemption === binding &&
                activeSession != null && lease.identity.outputTarget == binding.target
        } finally {
            transportLock.unlock()
        }
    }

    internal fun ensureActiveBinding(
        binding: UsbOutputRedemptionBinding,
        session: UsbOutputSession,
        lease: UsbOutputRequestLease,
    ) {
        transportLock.lock()
        try {
            check(activeRedemption === binding && activeSession === session)
            if (!isCurrent(binding.token)) {
                throw StaleUsbOutputRequestException(binding.token.value)
            }
            check(lease.token == binding.token)
            lease.ensureCurrent()
        } finally {
            transportLock.unlock()
        }
    }

    fun <T> withTransport(
        token: UsbOutputRequestToken,
        block: (UsbOutputRequestLease) -> T,
    ): T? {
        transportLock.lock()
        return try {
            if (!isCurrent(token)) return null
            if (activeSession != null) return null
            block(UsbOutputRequestLease(token, this))
        } finally {
            transportLock.unlock()
        }
    }

    /** Serializes active-session callbacks against replace/restart/release. */
    fun <T> withActiveSession(
        session: UsbOutputSession,
        block: (UsbOutputRequestLease) -> T,
    ): T? {
        transportLock.lock()
        return try {
            if (activeSession !== session) return null
            val activeGeneration = facts.generation
            val token = UsbOutputRequestToken(activeGeneration)
            if (!isCurrent(token)) return null
            block(UsbOutputRequestLease(token, this))
        } finally {
            transportLock.unlock()
        }
    }

    /**
     * Runs identity-scoped teardown for an active session under the frozen cleanup lease. The
     * generation may already be stale: cleanup is allowed to drain the exact old session, while
     * content callbacks continue to require [withActiveSession] and the current generation.
     */
    internal fun <T> withActiveSessionCleanup(
        session: UsbOutputSession,
        block: (UsbOutputCleanupLease) -> T,
    ): T? {
        transportLock.lock()
        return try {
            if (activeSession !== session) return null
            block(cleanupLease())
        } finally {
            transportLock.unlock()
        }
    }

    /**
     * Publishes observations collected through the current active-session lease. The caller must
     * already own the transport seam so sampling cannot race native restart or destruction.
     */
    fun publishRuntimeHealth(
        session: UsbOutputSession,
        lease: UsbOutputRequestLease,
        health: UsbRuntimeHealth,
    ): Boolean {
        check(transportLock.isHeldByCurrentThread) {
            "USB runtime health must publish inside the active-session seam"
        }
        if (activeSession !== session || facts.phase != UsbOutputPhase.ACTIVE) return false
        if (!lease.isCurrent() || facts.generation != lease.token.value) return false
        return try {
            publishFor(lease.token, facts.copy(runtimeHealth = health))
            true
        } catch (_: StaleUsbOutputRequestException) {
            false
        }
    }

    /**
     * Starts one permission request in the same generation and seam as transport ownership.
     * The caller must perform Android's requestPermission side effect through [withTransport].
     */
    fun beginPermissionRequest(
        request: UsbOutputRequest,
        runtimeHandle: UsbAudioRuntimeHandle,
    ): UsbOutputRequestToken {
        val token = publishNextGeneration()
        transportLock.lock()
        try {
            check(invalidateRedemptionsLocked(token)) {
                "USB permission request was superseded before the owner seam"
            }
            val lease = UsbOutputRequestLease(token, this)
            lease.ensureCurrent()
            activeSession?.let { session ->
                publishFor(
                    token,
                    facts.copy(
                        generation = token.value,
                        phase = UsbOutputPhase.RELEASING,
                        request = request,
                    ),
                )
                session.release(cleanupLease(), "permission-request")
                activeSession = null
                activeRequest = null
            }
            lease.ensureCurrent()
            publishFor(
                token,
                PlaybackOutputFacts(
                    generation = token.value,
                    phase = UsbOutputPhase.REQUESTED,
                    request = request,
                    runtimeHandle = runtimeHandle,
                    attached = true,
                    permission = UsbPermissionState.REQUESTED,
                ),
            )
            return token
        } finally {
            transportLock.unlock()
        }
    }

    /** Returns false when a callback belongs to an older permission request. */
    fun completePermissionRequest(
        token: UsbOutputRequestToken,
        runtimeHandle: UsbAudioRuntimeHandle,
        granted: Boolean,
    ): Boolean {
        transportLock.lock()
        return try {
            if (!isCurrent(token) ||
                facts.runtimeHandle != runtimeHandle ||
                facts.permission != UsbPermissionState.REQUESTED
            ) {
                return false
            }
            if (granted) {
                publishFor(
                    token,
                    facts.copy(
                        permission = UsbPermissionState.GRANTED,
                        failure = null,
                    ),
                )
            } else {
                publishFor(
                    token,
                    facts.copy(
                        phase = UsbOutputPhase.FAILED,
                        permission = UsbPermissionState.DENIED,
                        failure = UsbOutputFailure(
                            stage = "permission",
                            message = "USB permission denied",
                        ),
                    ),
                )
            }
            true
        } finally {
            transportLock.unlock()
        }
    }

    /**
     * Atomically classifies the enumerated runtime and claims a newer generation before waiting for
     * the transport seam. This preserves the detach guarantee that in-flight leases become stale
     * immediately, while preventing an old runtime callback from invalidating a newer replacement.
     * An already-closed current transport stays lifecycle-relevant as ORPHANED_CURRENT.
     */
    fun deviceDetached(runtimeHandle: UsbAudioRuntimeHandle): UsbDeviceDetachDisposition {
        val claim = synchronized(generationPublicationLock) {
            val observed = factsRef.get()
            when {
                generation.get() != observed.generation -> UsbDeviceDetachClaim.Stale
                observed.attached && observed.runtimeHandle != null &&
                    observed.runtimeHandle != runtimeHandle -> UsbDeviceDetachClaim.Stale
                !observed.attached || observed.runtimeHandle != runtimeHandle ->
                    UsbDeviceDetachClaim.Orphaned
                else -> UsbDeviceDetachClaim.Current(
                    UsbOutputRequestToken(generation.incrementAndGet()),
                )
            }
        }
        when (claim) {
            UsbDeviceDetachClaim.Stale -> return UsbDeviceDetachDisposition.STALE_RUNTIME
            UsbDeviceDetachClaim.Orphaned -> return UsbDeviceDetachDisposition.ORPHANED_CURRENT
            is UsbDeviceDetachClaim.Current -> onGenerationPublished(claim.token.value)
        }

        val token = (claim as UsbDeviceDetachClaim.Current).token
        transportLock.lock()
        return try {
            if (!invalidateRedemptionsLocked(token)) return UsbDeviceDetachDisposition.STALE_RUNTIME
            val previous = facts
            if (previous.runtimeHandle != runtimeHandle || !previous.attached) {
                return UsbDeviceDetachDisposition.STALE_RUNTIME
            }
            activeSession?.let { session ->
                publishFor(
                    token,
                    previous.copy(
                        generation = token.value,
                        phase = UsbOutputPhase.RELEASING,
                    ),
                )
                session.release(cleanupLease(), "device-detached")
            }
            activeSession = null
            activeRequest = null
            publishFor(
                token,
                PlaybackOutputFacts(
                    generation = token.value,
                    phase = UsbOutputPhase.FAILED,
                    request = previous.request,
                    attached = false,
                    permission = UsbPermissionState.UNKNOWN,
                    failure = UsbOutputFailure(
                        stage = "detach",
                        message = "USB device detached",
                    ),
                ),
            )
            UsbDeviceDetachDisposition.RELEASED_CURRENT
        } finally {
            transportLock.unlock()
        }
    }

    /** Publishes that playback continued through SharedPcm after USB recovery was exhausted. */
    fun publishFallbackToSharedPcm(
        request: UsbOutputRequest?,
        stage: String,
        message: String,
    ): Boolean {
        val previous = facts
        val token = publishNextGeneration()
        transportLock.lock()
        return try {
            if (!invalidateRedemptionsLocked(token)) return false
            activeSession?.let { session ->
                publishFor(
                    token,
                    previous.copy(generation = token.value, phase = UsbOutputPhase.RELEASING),
                )
                session.release(cleanupLease(), "shared-pcm-fallback")
                activeSession = null
                activeRequest = null
            }
            publishFor(
                token,
                PlaybackOutputFacts(
                    generation = token.value,
                    phase = UsbOutputPhase.FAILED,
                    request = request ?: previous.request,
                    failure = UsbOutputFailure(
                        stage = stage,
                        message = message,
                        fallbackToSharedPcm = true,
                    ),
                ),
            )
            true
        } catch (_: StaleUsbOutputRequestException) {
            false
        } finally {
            transportLock.unlock()
        }
    }

    fun <T : UsbOutputSession> replace(
        request: UsbOutputRequest,
        open: (UsbOutputRequestLease) -> T,
    ): T {
        val token = publishNextGeneration()
        transportLock.lock()
        try {
            check(invalidateRedemptionsLocked(token)) {
                "USB replacement was superseded before the owner seam"
            }
            return replaceLocked(token, request, null, open)
        } finally {
            transportLock.unlock()
        }
    }

    private fun <T : UsbOutputSession> replaceLocked(
        token: UsbOutputRequestToken,
        request: UsbOutputRequest,
        binding: UsbOutputRedemptionBinding?,
        open: (UsbOutputRequestLease) -> T,
    ): T {
        check(transportLock.isHeldByCurrentThread)
        val requestLease = UsbOutputRequestLease(token, this)
        requestLease.ensureCurrent()
        if (binding != null) {
            check(pendingRedemptions[token] === binding)
            pendingRedemptions.remove(token)
            openingRedemption = binding
        }
        publishFor(
            token,
            PlaybackOutputFacts(
                generation = token.value,
                phase = UsbOutputPhase.REQUESTED,
                request = request,
            ),
        )

        activeSession?.let { session ->
            publishFor(
                token,
                facts.copy(generation = token.value, phase = UsbOutputPhase.RELEASING),
            )
            session.release(cleanupLease(), "replaced")
            activeSession = null
            activeRequest = null
            activeRedemption = null
        }
        requestLease.ensureCurrent()
        publishFor(token, facts.copy(phase = UsbOutputPhase.OPENING))

        val opened = try {
            open(requestLease)
        } catch (error: Throwable) {
            openingRedemption = null
            binding?.invalidateFromOwner()
            if (isCurrent(token)) {
                runCatching {
                    publishFor(
                        token,
                        facts.copy(
                            phase = UsbOutputPhase.FAILED,
                            failure = UsbOutputFailure(
                                stage = "open",
                                message = error.message ?: error::class.java.simpleName,
                            ),
                        ),
                    )
                }
            }
            throw error
        }

        if (!isCurrent(token)) {
            openingRedemption = null
            opened.release(cleanupLease(), "superseded-after-open")
            binding?.invalidateFromOwner()
            throw StaleUsbOutputRequestException(token.value)
        }
        activeSession = opened
        activeRequest = request
        activeRedemption = binding
        openingRedemption = null
        binding?.attachActiveSession(opened)
        publishFor(
            token,
            opened.activeFacts.copy(
                generation = token.value,
                phase = UsbOutputPhase.ACTIVE,
                request = request,
            ),
        )
        return opened
    }

    fun restart(session: UsbOutputSession) {
        transportLock.lock()
        try {
            check(activeSession === session) { "Cannot restart a stale USB output session" }
            val token = publishNextGeneration()
            activeRedemption?.rotateTo(token)
            val lease = UsbOutputRequestLease(token, this)
            lease.ensureCurrent()
            session.restart(lease)
            lease.ensureCurrent()
            publishFor(
                token,
                session.activeFacts.copy(
                    generation = token.value,
                    phase = UsbOutputPhase.ACTIVE,
                    request = activeRequest,
                ),
            )
        } finally {
            transportLock.unlock()
        }
    }

    fun release(session: UsbOutputSession, reason: String = "release") {
        transportLock.lock()
        try {
            if (activeSession !== session) return
            val token = publishNextGeneration()
            publishFor(
                token,
                facts.copy(generation = token.value, phase = UsbOutputPhase.RELEASING),
            )
            session.release(cleanupLease(), reason)
            activeSession = null
            activeRequest = null
            activeRedemption?.invalidateFromOwner()
            activeRedemption = null
            if (isCurrent(token)) {
                publishFor(token, PlaybackOutputFacts(generation = token.value))
            }
        } finally {
            transportLock.unlock()
        }
    }

    private fun publishNextGeneration(): UsbOutputRequestToken {
        val token = synchronized(generationPublicationLock) {
            UsbOutputRequestToken(generation.incrementAndGet())
        }
        onGenerationPublished(token.value)
        return token
    }

    private fun publishFor(token: UsbOutputRequestToken, next: PlaybackOutputFacts) {
        check(transportLock.isHeldByCurrentThread) { "Facts must publish inside USB transport seam" }
        beforeFactsPublication(next)
        synchronized(generationPublicationLock) {
            if (!isCurrent(token)) throw StaleUsbOutputRequestException(token.value)
            factsRef.set(next)
        }
        afterFactsPublication(next)
    }

    private fun cleanupLease(): UsbOutputCleanupLease {
        check(transportLock.isHeldByCurrentThread)
        return UsbOutputCleanupLease(transportLock)
    }

    internal fun cleanupLeaseForCurrentThread(): UsbOutputCleanupLease = cleanupLease()

    private fun invalidateRedemptionsLocked(expectedToken: UsbOutputRequestToken? = null): Boolean {
        check(transportLock.isHeldByCurrentThread)
        return synchronized(generationPublicationLock) {
            if (expectedToken != null && generation.get() != expectedToken.value) return@synchronized false
            pendingRedemptions.values.forEach(UsbOutputRedemptionBinding::invalidateFromOwner)
            pendingRedemptions.clear()
            openingRedemption?.invalidateFromOwner()
            openingRedemption = null
            activeRedemption?.invalidateFromOwner()
            activeRedemption = null
            true
        }
    }
}

@JvmInline
internal value class UsbOutputRequestToken(val value: Long)

internal class UsbOutputRequestLease internal constructor(
    val token: UsbOutputRequestToken,
    private val owner: UsbOutputSessionOwner,
) {
    fun isCurrent(): Boolean = owner.isCurrent(token)

    fun ensureCurrent() {
        if (!isCurrent()) throw StaleUsbOutputRequestException(token.value)
    }

    /**
     * Check immediately before IO. The result then reaches local cleanup bookkeeping; the next
     * [io], [ensureCurrent], or owner publication performs the required post-IO validity check.
     */
    fun <T> io(block: () -> T): T {
        ensureCurrent()
        return block()
    }

    fun cleanupLease(): UsbOutputCleanupLease {
        return owner.cleanupLeaseForCurrentThread()
    }
}

internal class UsbOutputCleanupLease internal constructor(
    private val transportLock: ReentrantLock,
) {
    fun ensureSerialized() {
        check(transportLock.isHeldByCurrentThread) {
            "USB cleanup escaped the serialized transport seam"
        }
    }

    /** Cleanup remains authoritative while holding the seam, even if another request arrives. */
    fun <T> io(block: () -> T): T {
        ensureSerialized()
        val result = block()
        ensureSerialized()
        return result
    }
}

internal object UsbOutputRuntime {
    private val generationFanout = UsbOutputGenerationObserverFanout { generation, error ->
        DiagnosticLog.event(
            "UsbExclusiveShadow",
            "event=USB_GENERATION_OBSERVER_FAILURE generation=$generation " +
                "error=${error.javaClass.simpleName}",
        )
    }
    private val factsFanout = UsbOutputFactsObserverFanout { facts, error ->
        DiagnosticLog.event(
            "UsbExclusiveShadow",
            "event=USB_FACTS_OBSERVER_FAILURE generation=${facts.generation} phase=${facts.phase} " +
                "error=${error.javaClass.simpleName}",
        )
    }
    val owner = UsbOutputSessionOwner(
        onGenerationPublished = generationFanout::publish,
        afterFactsPublication = factsFanout::publish,
    )

    /** Debug SK02 adapter installs the Native generation bridge; release keeps the no-op bridge. */
    fun installGenerationPublisher(publisher: (Long) -> Unit) {
        generationFanout.installPublisher(publisher)
    }

    /** Read-only observer fan-out. Removing/throwing observers cannot change owner publication. */
    fun installGenerationObserver(observer: (Long) -> Unit): () -> Unit =
        generationFanout.installObserver(observer)

    /** Current owner facts publish only after the P2 state is committed. */
    fun installFactsObserver(observer: (PlaybackOutputFacts) -> Unit): () -> Unit =
        factsFanout.installObserver(observer)
}
