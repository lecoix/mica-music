package com.mica.music.media

/** Stable identity facts observed at the MediaSession connection seam. */
internal data class ControllerIdentity(
    val packageName: String,
    val uid: Int,
    val isTrusted: Boolean,
    val controllerVersion: Int,
    val connectionHintKeys: Set<String>,
)

internal enum class ControllerClass {
    OWN_APP,
    TRUSTED_EXTERNAL,
    UNTRUSTED_EXTERNAL,
}

internal data class ControllerCapabilities(
    val controllerClass: ControllerClass,
    /** External controllers must resolve IDs through the service-owned catalog. */
    val resolveMediaItemsFromCatalog: Boolean,
    /** Empty until Mica explicitly implements an incoming custom command. */
    val allowedIncomingCustomActions: Set<String>,
)

/**
 * Policy for the MediaSession connection seam.
 *
 * Media3's default callback still decides the standard player command set. This module owns the
 * Mica-specific decisions: which controller is local, which media-item path it may use, and which
 * incoming custom actions are currently supported.
 */
internal object ControllerCapabilityPolicy {
    fun evaluate(
        identity: ControllerIdentity,
        ownPackageName: String,
    ): ControllerCapabilities {
        val controllerClass = when {
            identity.packageName == ownPackageName -> ControllerClass.OWN_APP
            identity.isTrusted -> ControllerClass.TRUSTED_EXTERNAL
            else -> ControllerClass.UNTRUSTED_EXTERNAL
        }
        return ControllerCapabilities(
            controllerClass = controllerClass,
            resolveMediaItemsFromCatalog = controllerClass != ControllerClass.OWN_APP,
            allowedIncomingCustomActions = emptySet(),
        )
    }

    fun allowsIncomingCustomAction(
        identity: ControllerIdentity,
        ownPackageName: String,
        action: String,
    ): Boolean = action in evaluate(identity, ownPackageName).allowedIncomingCustomActions
}
