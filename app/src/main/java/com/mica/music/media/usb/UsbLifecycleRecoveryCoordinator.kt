package com.mica.music.media.usb

internal data class UsbLifecycleToken(val generation: Long, val runtimeHandle: UsbAudioRuntimeHandle)

internal data class UsbInterruptedPlaybackIntent(
    val resumePlaybackRequested: Boolean,
    val reason: String,
)

/** Serialises physical USB recovery intent independently from native session lifetime. */
internal class UsbLifecycleRecoveryCoordinator(
    private val beforePublication: (UsbLifecycleToken) -> Unit = {},
) {
    private val lock = Any()
    private var nextGeneration = 0L
    private var current: State? = null

    val hasInterruptedUsbIntent: Boolean
        get() = synchronized(lock) { current?.interruptedUsbIntent == true }

    fun beginDetach(runtimeHandle: UsbAudioRuntimeHandle): UsbLifecycleToken = synchronized(lock) {
        UsbLifecycleToken(++nextGeneration, runtimeHandle).also {
            current = State(
                token = it,
                interruptedUsbIntent = true,
                interruptedPlayback = current?.interruptedPlayback,
            )
        }
    }

    fun beginAttach(runtimeHandle: UsbAudioRuntimeHandle): UsbLifecycleToken = synchronized(lock) {
        UsbLifecycleToken(++nextGeneration, runtimeHandle).also {
            val previous = current
            current = State(
                token = it,
                interruptedUsbIntent = previous?.interruptedUsbIntent == true,
                interruptedPlayback = previous?.interruptedPlayback,
            )
        }
    }

    fun rememberInterruptedPlayback(
        token: UsbLifecycleToken,
        resumePlaybackRequested: Boolean,
        reason: String,
    ): Boolean = synchronized(lock) {
        val state = current ?: return@synchronized false
        if (state.token != token) return@synchronized false
        state.interruptedPlayback = UsbInterruptedPlaybackIntent(resumePlaybackRequested, reason)
        true
    }

    fun hasInterruptedPlayback(token: UsbLifecycleToken): Boolean = synchronized(lock) {
        current?.let { state ->
            state.token == token && state.interruptedUsbIntent && state.interruptedPlayback != null
        } == true
    }

    fun bindPermissionRequest(token: UsbLifecycleToken, permissionGeneration: Long): Boolean = synchronized(lock) {
        val state = current ?: return@synchronized false
        if (state.token != token) return@synchronized false
        state.permissionGeneration = permissionGeneration
        true
    }

    fun rejectPermission(
        runtimeHandle: UsbAudioRuntimeHandle,
        permissionGeneration: Long,
    ): Boolean {
        val token = synchronized(lock) {
            val state = current ?: return@synchronized null
            if (state.token.runtimeHandle != runtimeHandle ||
                state.permissionGeneration != permissionGeneration ||
                !state.interruptedUsbIntent
            ) return@synchronized null
            state.token
        } ?: return false
        beforePublication(token)
        synchronized(lock) {
            val state = current ?: return false
            if (state.token != token || state.permissionGeneration != permissionGeneration ||
                !state.interruptedUsbIntent
            ) return false
            state.permissionGeneration = null
            return true
        }
    }

    fun publishIfCurrent(token: UsbLifecycleToken, effect: () -> Unit): Boolean {
        beforePublication(token)
        synchronized(lock) {
            val state = current ?: return false
            if (state.token != token || !state.interruptedUsbIntent) return false
            effect()
            return true
        }
    }

    fun isCurrent(token: UsbLifecycleToken): Boolean = synchronized(lock) {
        current?.let { it.token == token && it.interruptedUsbIntent } == true
    }

    fun clearIfCurrent(token: UsbLifecycleToken): Boolean = synchronized(lock) {
        if (current?.token != token) return@synchronized false
        current = null
        true
    }

    fun publishGrantedPermission(
        runtimeHandle: UsbAudioRuntimeHandle,
        permissionGeneration: Long,
        effect: (UsbInterruptedPlaybackIntent) -> Boolean,
    ): Boolean {
        val token = synchronized(lock) {
            val state = current ?: return@synchronized null
            if (state.token.runtimeHandle != runtimeHandle ||
                state.permissionGeneration != permissionGeneration ||
                !state.interruptedUsbIntent
            ) return@synchronized null
            state.token
        } ?: return false
        beforePublication(token)
        synchronized(lock) {
            val state = current ?: return false
            if (state.token != token || state.permissionGeneration != permissionGeneration ||
                !state.interruptedUsbIntent
            ) return false
            if (!effect(state.interruptedPlayback ?: return false)) return false
            state.interruptedUsbIntent = false
            return true
        }
    }

    fun clear() = synchronized(lock) { current = null }

    private data class State(
        val token: UsbLifecycleToken,
        var interruptedUsbIntent: Boolean,
        var interruptedPlayback: UsbInterruptedPlaybackIntent? = null,
        var permissionGeneration: Long? = null,
    )
}
