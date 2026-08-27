package com.mica.music.usb

/** Stable DAC identity shared by USB persistence and transport runtime layers. */
data class UsbStableIdentity(
    val vendorId: Int,
    val productId: Int,
    val bcdDevice: Int?,
    val descriptorDigest: String,
)
