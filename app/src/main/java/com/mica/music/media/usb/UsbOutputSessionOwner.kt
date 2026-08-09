package com.mica.music.media.usb

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

internal class StaleUsbOutputRequestException(generation: Long) :
    IllegalStateException("USB output request $generation is stale")

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
) {
    private val generation = AtomicLong(0L)
    private val generationPublicationLock = Any()
    private val transportLock = ReentrantLock()
    private val factsRef = AtomicReference(PlaybackOutputFacts())
    @Volatile
    private var activeSession: UsbOutputSession? = null
    private var activeRequest: UsbOutputRequest? = null

    val facts: PlaybackOutputFacts
        get() = factsRef.get()

    /** Debug harness requests fail without invalidating a live production session. */
    fun beginHarnessRequest(): UsbOutputRequestToken? {
        if (activeSession != null) return null
        return publishNextGeneration()
    }

    fun invalidate(): Long = publishNextGeneration().value

    fun isCurrent(token: UsbOutputRequestToken): Boolean = generation.get() == token.value

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

    fun <T : UsbOutputSession> replace(
        request: UsbOutputRequest,
        open: (UsbOutputRequestLease) -> T,
    ): T {
        val token = publishNextGeneration()
        transportLock.lock()
        try {
            val requestLease = UsbOutputRequestLease(token, this)
            requestLease.ensureCurrent()
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
            }
            requestLease.ensureCurrent()
            publishFor(token, facts.copy(phase = UsbOutputPhase.OPENING))

            val opened = try {
                open(requestLease)
            } catch (error: Throwable) {
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
                opened.release(cleanupLease(), "superseded-after-open")
                throw StaleUsbOutputRequestException(token.value)
            }
            activeSession = opened
            activeRequest = request
            publishFor(
                token,
                opened.activeFacts.copy(
                    generation = token.value,
                    phase = UsbOutputPhase.ACTIVE,
                    request = request,
                ),
            )
            return opened
        } finally {
            transportLock.unlock()
        }
    }

    fun restart(session: UsbOutputSession) {
        transportLock.lock()
        try {
            check(activeSession === session) { "Cannot restart a stale USB output session" }
            val token = publishNextGeneration()
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
    }

    private fun cleanupLease(): UsbOutputCleanupLease {
        check(transportLock.isHeldByCurrentThread)
        return UsbOutputCleanupLease(transportLock)
    }

    internal fun cleanupLeaseForCurrentThread(): UsbOutputCleanupLease = cleanupLease()
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
    private val generationPublisher = AtomicReference<(Long) -> Unit>({})
    val owner = UsbOutputSessionOwner(
        onGenerationPublished = { generation -> generationPublisher.get()(generation) },
    )

    /** Debug SK02 adapter installs the Native generation bridge; release keeps the no-op bridge. */
    fun installGenerationPublisher(publisher: (Long) -> Unit) {
        generationPublisher.set(publisher)
    }
}
