package com.mica.music.media.usbhybrid

/**
 * Narrow scope of the rewrite-derived experimental u32le claim.
 * This is not Hybrid qualification and therefore can never imply signalExact.
 */
object Sk02ExperimentalNativeEvidence {
    const val PROFILE_SOURCE = "USB_EXCLUSIVE_REWRITE_REFERENCE_BASELINE.md:182-208"

    fun matches(
        identity: UsbStableIdentity,
        manufacturerName: String?,
        productName: String?,
    ): Boolean = identity.vendorId == Sk02TargetSelector.VENDOR_ID &&
        identity.productId == Sk02TargetSelector.PRODUCT_ID &&
        identity.bcdDevice == 0x0004 &&
        manufacturerName == "Speed Dragon" &&
        productName == "Fosi Audio SK02"
}
