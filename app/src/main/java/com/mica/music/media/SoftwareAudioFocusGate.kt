package com.mica.music.media

internal interface SoftwareAudioFocusGate {
    fun request(generation: Long): Boolean
    fun abandon(generation: Long)
}

internal object AlwaysGrantedSoftwareAudioFocusGate : SoftwareAudioFocusGate {
    override fun request(generation: Long): Boolean = true
    override fun abandon(generation: Long) = Unit
}
