package com.mica.music.media.usbhybrid

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class UsbDescriptorModel(
    val vendorId: Int,
    val productId: Int,
    val version: String?,
    val configurations: List<String>,
)

object UsbStableIdentityDigest {
    fun sha256(model: UsbDescriptorModel): String {
        val canonical = buildString {
            append(model.vendorId).append(':').append(model.productId).append(':')
            append(model.version.orEmpty()).append('\n')
            model.configurations.sorted().forEach { append(it).append('\n') }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
