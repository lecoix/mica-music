package com.mica.music.media

import android.os.Bundle
import androidx.media3.session.SessionCommand
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

/** Process-local debug harness seam; production UI/service policy must not use this bridge. */
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
