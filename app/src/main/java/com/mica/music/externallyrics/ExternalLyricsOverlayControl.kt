package com.mica.music.externallyrics

/**
 * Narrow application-facing control surface for the Android external-lyrics overlay feature.
 * Media code may request overlay lifecycle actions without depending on presentation classes.
 */
interface ExternalLyricsOverlayControl {
    fun canDrawOverlays(): Boolean
    fun start(): Boolean
    fun sync(): Boolean
    fun refreshPosition()
    fun refreshSettings()
    fun stop()
    fun openPermissionSettings()
}