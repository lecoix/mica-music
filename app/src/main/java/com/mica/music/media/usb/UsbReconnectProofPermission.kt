package com.mica.music.media.usb

internal enum class UsbReconnectProofPermissionRejection {
    MISSING_PROVEN_IDENTITY,
    NOT_PERMISSION_UNAVAILABLE,
    POTENTIAL_COUNT_NOT_ONE,
    CANDIDATE_ALREADY_PERMITTED,
    VISIBLE_VENDOR_PRODUCT_MISMATCH,
}

internal sealed interface UsbReconnectProofPermissionPlan {
    /**
     * Permission is requested only so the current runtime can later be proved authoritatively.
     * The visible VID/PID prefilter below is not stable identity or compatibility proof.
     */
    data class RequestProofPermission(
        val candidate: UsbPotentialAudioDevice,
    ) : UsbReconnectProofPermissionPlan

    data class DoNotRequest(
        val rejection: UsbReconnectProofPermissionRejection,
    ) : UsbReconnectProofPermissionPlan
}

/** Pure policy for the one bounded reconnect proof-permission request authorized by directive 13. */
internal object UsbReconnectProofPermissionPlanner {
    fun plan(
        expectedIdentity: UsbAudioDeviceIdentity?,
        resolution: UsbStableReconnectResolution,
    ): UsbReconnectProofPermissionPlan {
        if (expectedIdentity == null) {
            return UsbReconnectProofPermissionPlan.DoNotRequest(
                UsbReconnectProofPermissionRejection.MISSING_PROVEN_IDENTITY,
            )
        }
        val permissionUnavailable = resolution as? UsbStableReconnectResolution.PermissionUnavailable
            ?: return UsbReconnectProofPermissionPlan.DoNotRequest(
                UsbReconnectProofPermissionRejection.NOT_PERMISSION_UNAVAILABLE,
            )
        if (permissionUnavailable.candidates.size != 1) {
            return UsbReconnectProofPermissionPlan.DoNotRequest(
                UsbReconnectProofPermissionRejection.POTENTIAL_COUNT_NOT_ONE,
            )
        }
        val candidate = permissionUnavailable.candidates.single()
        if (candidate.permission == UsbPermissionState.GRANTED) {
            return UsbReconnectProofPermissionPlan.DoNotRequest(
                UsbReconnectProofPermissionRejection.CANDIDATE_ALREADY_PERMITTED,
            )
        }
        if (candidate.vendorId != expectedIdentity.vendorId ||
            candidate.productId != expectedIdentity.productId
        ) {
            return UsbReconnectProofPermissionPlan.DoNotRequest(
                UsbReconnectProofPermissionRejection.VISIBLE_VENDOR_PRODUCT_MISMATCH,
            )
        }
        return UsbReconnectProofPermissionPlan.RequestProofPermission(candidate)
    }
}

internal enum class UsbReconnectPostGrantRejection {
    NOT_EXACTLY_RESOLVED,
    RESOLVED_RUNTIME_MISMATCH,
}

internal sealed interface UsbReconnectPostGrantDecision {
    data class Restore(
        val resolved: UsbStableReconnectResolution.Resolved,
    ) : UsbReconnectPostGrantDecision

    data class DoNotRestore(
        val rejection: UsbReconnectPostGrantRejection,
        val resolution: UsbStableReconnectResolution,
    ) : UsbReconnectPostGrantDecision
}

/**
 * Permission grant is deliberately insufficient. This seam always obtains a fresh exact resolver
 * result, and only that result for the same runtime that received permission may cross into the
 * existing recovery restore path.
 */
internal object UsbReconnectPostGrantProofGate {
    fun reproveAndDecide(
        grantedRuntimeHandle: UsbAudioRuntimeHandle,
        resolve: () -> UsbStableReconnectResolution,
    ): UsbReconnectPostGrantDecision = decide(grantedRuntimeHandle, resolve())

    fun decide(
        grantedRuntimeHandle: UsbAudioRuntimeHandle,
        resolution: UsbStableReconnectResolution,
    ): UsbReconnectPostGrantDecision {
        val resolved = resolution as? UsbStableReconnectResolution.Resolved
            ?: return UsbReconnectPostGrantDecision.DoNotRestore(
                UsbReconnectPostGrantRejection.NOT_EXACTLY_RESOLVED,
                resolution,
            )
        if (resolved.candidate.runtimeHandle != grantedRuntimeHandle) {
            return UsbReconnectPostGrantDecision.DoNotRestore(
                UsbReconnectPostGrantRejection.RESOLVED_RUNTIME_MISMATCH,
                resolution,
            )
        }
        return UsbReconnectPostGrantDecision.Restore(resolved)
    }
}
