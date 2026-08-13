package com.mica.music.media.usb

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local reconnect memory for the last identity proven by a completed production USB open.
 *
 * This is intentionally independent from [UsbOutputSessionOwner] facts: renderer/session release
 * may return the frozen P2 owner to IDLE before Android publishes DETACHED. The retained identity is
 * only reconnect authority while interrupted USB recovery is already pending; it is not a device
 * preference and is never persisted.
 */
internal class UsbProvenReconnectTarget {
    private val identityRef = AtomicReference<UsbAudioDeviceIdentity?>(null)

    val identity: UsbAudioDeviceIdentity?
        get() = identityRef.get()

    fun expectedIdentityForInterruptedRecovery(interruptedRecovery: Boolean): UsbAudioDeviceIdentity? =
        if (interruptedRecovery) identityRef.get() else null

    fun <T> publishAfterSuccessfulOpen(
        identity: UsbAudioDeviceIdentity,
        open: () -> T,
    ): T {
        val opened = open()
        identityRef.set(identity)
        return opened
    }

    fun clear() {
        identityRef.set(null)
    }
}

/** Production process-local holder. Tests use isolated [UsbProvenReconnectTarget] instances. */
internal object UsbProvenReconnectTargetRuntime {
    private val target = UsbProvenReconnectTarget()

    fun expectedIdentityForInterruptedRecovery(interruptedRecovery: Boolean): UsbAudioDeviceIdentity? =
        target.expectedIdentityForInterruptedRecovery(interruptedRecovery)

    fun <T> publishAfterSuccessfulOpen(
        identity: UsbAudioDeviceIdentity,
        open: () -> T,
    ): T = target.publishAfterSuccessfulOpen(identity, open)

    fun clearForExplicitDisable() = target.clear()

    fun clearForServiceDestruction() = target.clear()
}
