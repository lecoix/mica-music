package com.mica.music.data.preferences

import android.content.Context
import android.os.Build
import com.mica.music.media.usbhybrid.UsbStableIdentity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class UsbSharedReturnCapability {
    HotSwitchVerified,
    ReconnectRequired,
    Unknown,
}

object UsbSharedReturnPolicy {
    fun requiresPhysicalReconnect(capability: UsbSharedReturnCapability): Boolean =
        capability == UsbSharedReturnCapability.ReconnectRequired
}

object UsbSharedReturnCapabilityStore {
    private const val KEY_PREFIX = "usb_shared_return_capability_"

    fun capability(context: Context, identity: UsbStableIdentity): UsbSharedReturnCapability {
        val stored = MicaSettingsStore.prefs(context).getString(storageKey(currentEnvironmentKey(), identity), null)
        return UsbSharedReturnCapability.entries.firstOrNull { it.name == stored }
            ?: UsbSharedReturnCapability.Unknown
    }

    fun setCapability(
        context: Context,
        identity: UsbStableIdentity,
        capability: UsbSharedReturnCapability,
    ) {
        MicaSettingsStore.prefs(context).edit()
            .putString(storageKey(currentEnvironmentKey(), identity), capability.name)
            .apply()
    }

    fun currentEnvironmentKey(): String = environmentKey(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
        fingerprint = Build.FINGERPRINT,
    )

    internal fun environmentKey(
        manufacturer: String,
        model: String,
        sdkInt: Int,
        fingerprint: String,
    ): String = listOf(manufacturer, model, sdkInt.toString(), fingerprint).joinToString("|")

    internal fun storageKey(environmentKey: String, identity: UsbStableIdentity): String {
        val canonical = buildString {
            append(environmentKey).append('|')
            append(identity.vendorId).append(':').append(identity.productId).append(':')
            append(identity.bcdDevice ?: -1).append(':').append(identity.descriptorDigest)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return KEY_PREFIX + digest
    }
}
