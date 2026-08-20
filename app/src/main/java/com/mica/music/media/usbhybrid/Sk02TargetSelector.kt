package com.mica.music.media.usbhybrid

data class UsbDeviceCandidate(
    val identity: UsbStableIdentity,
    val runtimeHandle: UsbRuntimeHandle,
)

sealed interface Sk02Selection {
    data object NotFound : Sk02Selection
    data class Ambiguous(val candidateCount: Int) : Sk02Selection
    data class Selected(val candidate: UsbDeviceCandidate) : Sk02Selection
}

object Sk02TargetSelector {
    const val VENDOR_ID = 0x262a
    const val PRODUCT_ID = 0x0001

    fun select(candidates: List<UsbDeviceCandidate>): Sk02Selection {
        val matching = candidates.filter {
            it.identity.vendorId == VENDOR_ID && it.identity.productId == PRODUCT_ID
        }
        return when (matching.size) {
            0 -> Sk02Selection.NotFound
            1 -> Sk02Selection.Selected(matching.single())
            else -> Sk02Selection.Ambiguous(matching.size)
        }
    }
}
