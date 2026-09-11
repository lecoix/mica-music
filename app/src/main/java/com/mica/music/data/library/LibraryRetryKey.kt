package com.mica.music.data.library

/** Canonical retry-ledger keys shared by planners and persistence lookup. */
internal object LibraryRetryKey {
    private const val DEVICE_OBJECT_PREFIX = "device-object-probe:"
    private const val SAF_OBJECT_PREFIX = "saf-object-probe:"
    private const val SAF_UNKNOWN_FINGERPRINT_PREFIX = "saf-unknown-deep-verify:"

    fun deviceObject(stableObjectKey: String): String =
        "$DEVICE_OBJECT_PREFIX$stableObjectKey"

    fun safObject(stableObjectKey: String): String =
        "$SAF_OBJECT_PREFIX$stableObjectKey"

    fun safUnknownFingerprint(stableObjectKey: String): String =
        "$SAF_UNKNOWN_FINGERPRINT_PREFIX$stableObjectKey"

    fun allForStableObjectKey(stableObjectKey: String): List<String> = listOf(
        deviceObject(stableObjectKey),
        safObject(stableObjectKey),
        safUnknownFingerprint(stableObjectKey),
    )
}
