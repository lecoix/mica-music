package com.mica.music.media.usbhybrid

data class UsbHybridPlaybackBinding(
    val owner: UsbHybridSessionOwner,
    val realtime: UsbHybridRealtimePort,
    val epoch: UsbRequestEpoch,
)
