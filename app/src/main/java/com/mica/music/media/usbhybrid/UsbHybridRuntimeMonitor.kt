package com.mica.music.media.usbhybrid

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read-only process view. Only [UsbHybridSessionOwner]'s publication seam writes this state. */
object UsbHybridRuntimeMonitor {
    private val mutableFacts = MutableStateFlow(UsbPlaybackFacts())
    val facts: StateFlow<UsbPlaybackFacts> = mutableFacts.asStateFlow()

    internal fun publishFromOwner(facts: UsbPlaybackFacts) {
        mutableFacts.value = facts
    }
}
