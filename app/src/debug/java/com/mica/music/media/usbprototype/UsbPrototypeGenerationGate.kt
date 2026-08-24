package com.mica.music.media.usbprototype

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** THROWAWAY PROTOTYPE: generation owner plus the single serialized USB side-effect seam. */
internal class UsbPrototypeGenerationGate {
    private val generation = AtomicLong(0L)
    private val transportMutex = ReentrantLock()

    fun beginRequest(): Token = Token(generation.incrementAndGet())

    fun invalidate(): Long = generation.incrementAndGet()

    fun isCurrent(token: Token): Boolean = generation.get() == token.value

    fun <T> withTransport(token: Token, sideEffect: (Lease) -> T): T? {
        transportMutex.lock()
        return try {
            if (!isCurrent(token)) return null
            sideEffect(Lease(token, this))
        } finally {
            transportMutex.unlock()
        }
    }

    @JvmInline
    value class Token(val value: Long)

    class Lease internal constructor(
        private val token: Token,
        private val owner: UsbPrototypeGenerationGate,
    ) {
        fun isCurrent(): Boolean = owner.isCurrent(token)
    }
}
