package com.mica.music.media.usbhybrid

/** One-buffer readiness gate: preparation may stage, only STARTED may drain to USB. */
class DsdPrerollGate {
    private var started = false
    private var staged: ByteArray? = null

    fun isStarted(): Boolean = started
    fun hasStaged(): Boolean = staged != null

    fun stage(payload: ByteArray): Boolean {
        if (started || staged != null) return false
        staged = payload
        return true
    }

    fun arm(): ByteArray? {
        started = true
        return staged.also { staged = null }
    }

    fun stop() {
        started = false
    }

    fun clear() {
        started = false
        staged = null
    }

    fun reset(started: Boolean) {
        this.started = started
        staged = null
    }
}
