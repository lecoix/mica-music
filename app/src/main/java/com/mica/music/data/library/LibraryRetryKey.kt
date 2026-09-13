package com.mica.music.data.library

/** Canonical retry-ledger keys shared by planners and persistence lookup. */
internal object LibraryRetryKey {
    private const val DEVICE_OBJECT_PREFIX = "device-object-probe:"
    private const val SAF_OBJECT_PREFIX = "saf-object-probe:"
    private const val SAF_UNKNOWN_FINGERPRINT_PREFIX = "saf-unknown-deep-verify:"
    private const val SAF_MASS_DELETION_VERIFY_PREFIX = "saf-mass-deletion-verify:"
    const val SAF_MASS_DELETION_STABLE_OBJECT_KEY = "__saf_mass_deletion_confirmation__"

    fun deviceObject(stableObjectKey: String): String =
        "$DEVICE_OBJECT_PREFIX$stableObjectKey"

    fun safObject(stableObjectKey: String): String =
        "$SAF_OBJECT_PREFIX$stableObjectKey"

    fun safUnknownFingerprint(stableObjectKey: String): String =
        "$SAF_UNKNOWN_FINGERPRINT_PREFIX$stableObjectKey"

    fun safMassDeletionVerify(): String =
        "$SAF_MASS_DELETION_VERIFY_PREFIX$SAF_MASS_DELETION_STABLE_OBJECT_KEY"

    fun allForStableObjectKey(stableObjectKey: String): List<String> = buildList {
        add(deviceObject(stableObjectKey))
        add(safObject(stableObjectKey))
        add(safUnknownFingerprint(stableObjectKey))
        if (stableObjectKey == SAF_MASS_DELETION_STABLE_OBJECT_KEY) {
            add(safMassDeletionVerify())
        }
    }
}
