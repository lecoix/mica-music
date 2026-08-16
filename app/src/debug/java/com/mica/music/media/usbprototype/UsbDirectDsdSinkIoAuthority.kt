package com.mica.music.media.usbprototype

import com.mica.music.media.usb.UsbOutputCleanupLease
import com.mica.music.media.usb.UsbOutputRequestLease

/**
 * Routes Direct-DSD Native sink I/O through exactly one serialized P2 authority.
 *
 * Normal playback retains the opening request lease as an additional stale-generation guard even
 * though every feeder entry point is already inside UsbOutputSessionOwner.withActiveSession().
 * Owner-driven teardown advances generation before session.release(), so release may temporarily
 * install the cleanup lease while draining only carrier bytes that P5/feeder already accepted.
 */
internal class UsbDirectDsdSinkIoAuthority(
    private val activeRequestLease: UsbOutputRequestLease,
    private val beforeContentIo: () -> Unit,
) {
    private val lock = Any()

    @Volatile
    private var cleanupLease: UsbOutputCleanupLease? = null

    @Volatile
    private var closed = false

    fun <T> io(block: () -> T): T {
        val cleanup = synchronized(lock) {
            check(!closed) { "Direct DSD Native sink I/O after authority close" }
            cleanupLease
        }
        if (cleanup == null) beforeContentIo()
        return if (cleanup != null) cleanup.io(block) else activeRequestLease.io(block)
    }

    fun <T> withCleanupLease(lease: UsbOutputCleanupLease, block: () -> T): T {
        lease.ensureSerialized()
        synchronized(lock) {
            check(!closed) { "Direct DSD cleanup authority after close" }
            check(cleanupLease == null) { "Direct DSD cleanup authority already installed" }
            cleanupLease = lease
        }
        return try {
            block()
        } finally {
            synchronized(lock) {
                check(cleanupLease === lease)
                cleanupLease = null
            }
        }
    }

    fun close() {
        synchronized(lock) {
            check(cleanupLease == null) { "Direct DSD sink authority closed during cleanup I/O" }
            closed = true
        }
    }
}
