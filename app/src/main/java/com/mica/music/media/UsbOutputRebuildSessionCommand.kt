package com.mica.music.media

import android.os.Bundle
import androidx.media3.session.SessionCommand
import com.mica.music.media.usb.UsbRecoveryTrigger
import java.util.concurrent.atomic.AtomicReference

internal object UsbOutputRebuildSessionCommand {
    const val ACTION = "com.mica.music.action.REBUILD_USB_OUTPUT"
    const val EXTRA_REQUESTED_ENABLED = "requestedEnabled"
    const val EXTRA_PREVIOUS_ENABLED = "previousEnabled"
    const val EXTRA_RESULT_CODE = "resultCode"
    const val EXTRA_GENERATION = "generation"
    const val RESULT_ACTION_SUFFIX = ".debug.USB_OUTPUT_REBUILD_RESULT"
    val command = SessionCommand(ACTION, Bundle.EMPTY)

    fun resultAction(packageName: String): String = packageName + RESULT_ACTION_SUFFIX
}

/** Process-local UI/service seam; the exported Debug receiver is only one optional caller. */
internal object UsbOutputRebuildRuntime {
    private val requester = AtomicReference<((Boolean, Boolean) -> Unit)?>(null)

    fun install(request: (requestedEnabled: Boolean, previousEnabled: Boolean) -> Unit) {
        requester.set(request)
    }

    fun clear() {
        requester.set(null)
    }

    fun request(requestedEnabled: Boolean, previousEnabled: Boolean): Boolean {
        val active = requester.get() ?: return false
        active(requestedEnabled, previousEnabled)
        return true
    }
}

internal object UsbRecoveryDebugCommand {
    const val RESULT_ACTION_SUFFIX = ".debug.USB_RECOVERY_RESULT"
    const val EXTRA_TRIGGER = "trigger"
    const val EXTRA_RESULT = "result"
    const val EXTRA_ACTION_ID = "actionId"
    const val EXTRA_ATTEMPT = "attempt"

    fun resultAction(packageName: String): String = packageName + RESULT_ACTION_SUFFIX
}

/** Process-local debug bridge. Release exposes no receiver that can call it. */
internal object UsbRecoveryDebugRuntime {
    private val requester = AtomicReference<((UsbRecoveryTrigger) -> Unit)?>(null)

    fun install(request: (UsbRecoveryTrigger) -> Unit) {
        requester.set(request)
    }

    fun clear() {
        requester.set(null)
    }

    fun request(trigger: UsbRecoveryTrigger): Boolean {
        val active = requester.get() ?: return false
        active(trigger)
        return true
    }
}

internal data class UsbRecoveryInjectedFailure(
    val generation: Long,
    val attempt: Int,
    val remainingFailures: Int,
)

/** Debug harness budget consumed only at the fresh-open rebuild boundary. */
internal object UsbRecoveryFailureInjectionRuntime {
    private val lock = Any()
    private var generation = 0L
    private var configuredFailures = 0
    private var remainingFailures = 0

    fun arm(failures: Int): Long = synchronized(lock) {
        require(failures > 0)
        configuredFailures = failures
        remainingFailures = failures
        ++generation
    }

    fun consume(): UsbRecoveryInjectedFailure? = synchronized(lock) {
        if (remainingFailures <= 0) return@synchronized null
        remainingFailures--
        UsbRecoveryInjectedFailure(
            generation = generation,
            attempt = configuredFailures - remainingFailures,
            remainingFailures = remainingFailures,
        )
    }

    fun clear() = synchronized(lock) {
        configuredFailures = 0
        remainingFailures = 0
        generation++
    }
}
