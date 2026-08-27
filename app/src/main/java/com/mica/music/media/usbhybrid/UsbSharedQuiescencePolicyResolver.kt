package com.mica.music.media.usbhybrid

import com.mica.music.usb.UsbStableIdentity

internal data class UsbSharedQuiescencePolicy(val settleMs: Long)

/**
 * Android shared-audio release timing is a host + DAC property, not a DAC-only quirk.
 * Keep this policy above the transport so USB open/close semantics remain reference-aligned.
 */
internal object UsbSharedQuiescencePolicyResolver {
    private const val DEFAULT_SETTLE_MS = 600L
    private const val REDMI_22081212C_SK02_SETTLE_MS = 800L

    fun resolve(
        manufacturer: String?,
        model: String?,
        identity: UsbStableIdentity?,
    ): UsbSharedQuiescencePolicy {
        val isCurrentQualifiedHost = manufacturer.equals("Xiaomi", ignoreCase = true) && model == "22081212C"
        val isSk02 = identity?.vendorId == 0x262a && identity.productId == 0x0001
        return UsbSharedQuiescencePolicy(
            settleMs = if (isCurrentQualifiedHost && isSk02) REDMI_22081212C_SK02_SETTLE_MS else DEFAULT_SETTLE_MS,
        )
    }
}
