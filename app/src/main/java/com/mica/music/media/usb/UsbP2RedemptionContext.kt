package com.mica.music.media.usb

import com.mica.music.media.usb.protocol.ActiveWriteLease
import com.mica.music.media.usb.protocol.OutputTarget
import com.mica.music.media.usb.protocol.UsbOutputGeneration
import com.mica.music.media.usb.protocol.WriteKind
import java.util.concurrent.locks.ReentrantLock

/**
 * One playback-stack bridge between the protocol's USB target and the frozen P2 owner.
 *
 * The bridge deliberately carries the owner-created binding object rather than treating a
 * generation number as authority.  It is scoped to one renderer stack, and its write scope is
 * synchronous and re-entrant only for the same thread, target, lease and write kind.  A callback
 * that escapes that scope fails closed.
 */
internal class UsbP2RedemptionContext(
    private val owner: UsbOutputSessionOwner,
    private val request: UsbOutputRequest?,
) {
    private val lock = ReentrantLock()
    private val reservationLock = ReentrantLock()
    private var binding: UsbOutputRedemptionBinding? = null
    private var activeWrite: ActiveWriteScope? = null

    /** Returns the target that must be used for the next protocol permit, or null for SharedPcm. */
    fun prepareProtocolBinding(): OutputTarget? {
        val request = request ?: return null
        reservationLock.lock()
        return try {
            val current = readBinding()
            if (current != null) {
                check(current.isProtocolCurrent()) {
                    "USB protocol binding was invalidated; a stale stack cannot re-reserve it"
                }
                current.target
            } else {
                val reserved = owner.reserveRedemption(request)
                lock.lock()
                try {
                    check(binding == null) { "USB protocol binding was concurrently established" }
                    binding = reserved
                } finally {
                    lock.unlock()
                }
                check(reserved.isProtocolCurrent()) {
                    "USB redemption reservation was superseded before publication"
                }
                reserved.target
            }
        } finally {
            reservationLock.unlock()
        }
    }

    internal fun requireCurrentBinding(): UsbOutputRedemptionBinding {
        check(request != null) { "USB redemption is unavailable for SharedPcm" }
        val current = readBinding()
        check(current != null && current.isProtocolCurrent()) {
            "USB protocol binding is absent or stale"
        }
        return current
    }

    private fun readBinding(): UsbOutputRedemptionBinding? {
        lock.lock()
        return try {
            binding
        } finally {
            lock.unlock()
        }
    }

    internal fun currentBinding(): UsbOutputRedemptionBinding = requireCurrentBinding()

    /** Validates that a protocol permit carries this instance's exact P2-bound target. */
    internal fun ensurePermitTarget(target: OutputTarget) {
        val request = request
        if (request == null) {
            check(target !is OutputTarget.UsbBound) {
                "SharedPcm cannot carry a USB redemption target"
            }
        } else {
            check(requireCurrentBinding().target == target) {
                "Protocol permit target does not match the P2 redemption binding"
            }
        }
    }

    internal fun <T : UsbOutputSession> consumeCurrent(
        open: (UsbOutputRedemptionBinding, UsbOutputRequestLease) -> T,
    ): T {
        val current = requireCurrentBinding()
        return owner.consumeRedemption(current) { lease -> open(current, lease) }
    }

    /**
     * Establishes the only valid synchronous bridge from a protocol write lease to provider or
     * feeder callbacks.  The callback must not retain the scope after this returns.
     */
    internal fun <T> withProtocolWrite(
        target: OutputTarget,
        lease: ActiveWriteLease,
        kind: WriteKind,
        block: () -> T,
    ): T {
        check(request != null) { "USB write redemption is unavailable for SharedPcm" }
        val usbTarget = target as? OutputTarget.UsbBound
            ?: error("USB write requires UsbBound output target")
        requireCurrentBinding().let { binding ->
            check(binding.target == usbTarget) {
                "USB write target does not match the owner binding"
            }
            binding.ensureProtocolLease(lease)
        }

        lock.lock()
        var entered = false
        try {
            val threadId = Thread.currentThread().id
            val previous = activeWrite
            if (previous == null) {
                activeWrite = ActiveWriteScope(threadId, usbTarget, lease, kind, depth = 1)
            } else {
                check(previous.threadId == threadId && previous.target == usbTarget &&
                    previous.lease === lease && previous.kind == kind) {
                    "USB write scope was nested with a different binding"
                }
                previous.depth++
            }
            entered = true
            return block()
        } finally {
            if (entered) {
                val current = checkNotNull(activeWrite)
                check(current.lease === lease && current.kind == kind)
                current.depth--
                if (current.depth == 0) activeWrite = null
            }
            lock.unlock()
        }
    }

    /** Called immediately before a provider/native/feeder content write. */
    internal fun requireProtocolWrite(target: OutputTarget, kind: WriteKind? = null) {
        val usbTarget = target as? OutputTarget.UsbBound
            ?: error("USB write requires UsbBound output target")
        check(lock.tryLock()) {
            "USB write scope was accessed from another thread"
        }
        val leaseForValidation = try {
            val current = activeWrite
            check(current != null && current.threadId == Thread.currentThread().id &&
                current.target == usbTarget && (kind == null || current.kind == kind)) {
                "USB write escaped its exact protocol scope"
            }
            current.lease
        } finally {
            lock.unlock()
        }
        requireCurrentBinding().ensureProtocolLease(leaseForValidation)
    }

    private data class ActiveWriteScope(
        val threadId: Long,
        val target: OutputTarget.UsbBound,
        val lease: ActiveWriteLease,
        val kind: WriteKind,
        var depth: Int,
    )
}

/** Explicit SharedPcm/unavailable context; it has no USB redemption capability. */
internal fun sharedPcmUsbP2RedemptionContext(owner: UsbOutputSessionOwner): UsbP2RedemptionContext =
    UsbP2RedemptionContext(owner, null)

internal class UsbOutputRedemptionBinding internal constructor(
    val request: UsbOutputRequest,
    initialToken: UsbOutputRequestToken,
    private val owner: UsbOutputSessionOwner,
) {
    @Volatile
    private var tokenState: UsbOutputRequestToken = initialToken
    @Volatile
    private var activeSession: UsbOutputSession? = null
    @Volatile
    private var invalidated = false

    val token: UsbOutputRequestToken
        get() = tokenState

    val target: OutputTarget.UsbBound
        get() = OutputTarget.UsbBound(UsbOutputGeneration(tokenState.value))

    internal fun isProtocolCurrent(): Boolean = !invalidated && owner.isBindingCurrent(this)

    internal fun ensureRequestLease(lease: UsbOutputRequestLease) {
        check(!invalidated) { "USB request binding was invalidated" }
        owner.ensureRequestBinding(this, lease)
    }

    internal fun ensureProtocolLease(lease: ActiveWriteLease) {
        check(!invalidated) { "USB protocol binding was invalidated" }
        check(owner.isBindingLeaseCurrent(this, lease)) {
            "USB protocol lease is not bound to the current P2 generation"
        }
    }

    internal fun ensureActiveSession(session: UsbOutputSession, lease: UsbOutputRequestLease) {
        check(!invalidated) { "USB active-session binding was invalidated" }
        owner.ensureActiveBinding(this, session, lease)
    }

    internal fun attachActiveSession(session: UsbOutputSession) {
        check(activeSession == null || activeSession === session)
        activeSession = session
    }

    internal fun rotateTo(token: UsbOutputRequestToken) {
        check(!invalidated) { "Cannot rotate an invalid USB binding" }
        tokenState = token
    }

    internal fun invalidateFromOwner() {
        invalidated = true
        activeSession = null
    }
}
