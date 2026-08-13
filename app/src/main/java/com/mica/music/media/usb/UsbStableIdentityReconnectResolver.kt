package com.mica.music.media.usb

internal enum class UsbStableIdentityConflict {
    VENDOR_ID,
    PRODUCT_ID,
    DESCRIPTOR_FINGERPRINT,
    BCD_DEVICE,
    SERIAL_NUMBER,
}

/**
 * Reconnect identity policy. Runtime Android device ids and enumeration order are deliberately
 * excluded. Previously unknown revision/serial evidence stays non-authoritative rather than being
 * invented retroactively.
 */
internal object UsbStableIdentityPolicy {
    fun conflicts(
        expected: UsbAudioDeviceIdentity,
        observed: UsbAudioDeviceIdentity,
    ): Set<UsbStableIdentityConflict> = buildSet {
        if (expected.vendorId != observed.vendorId) add(UsbStableIdentityConflict.VENDOR_ID)
        if (expected.productId != observed.productId) add(UsbStableIdentityConflict.PRODUCT_ID)
        if (expected.descriptorFingerprint != observed.descriptorFingerprint) {
            add(UsbStableIdentityConflict.DESCRIPTOR_FINGERPRINT)
        }
        if (expected.bcdDevice != null && expected.bcdDevice != observed.bcdDevice) {
            add(UsbStableIdentityConflict.BCD_DEVICE)
        }
        if (expected.serialNumber != null && expected.serialNumber != observed.serialNumber) {
            add(UsbStableIdentityConflict.SERIAL_NUMBER)
        }
    }

    fun matches(
        expected: UsbAudioDeviceIdentity,
        observed: UsbAudioDeviceIdentity,
    ): Boolean = conflicts(expected, observed).isEmpty()
}

internal sealed interface UsbStableReconnectCandidateProof {
    data class Proven(val identity: UsbAudioDeviceIdentity) : UsbStableReconnectCandidateProof
    data class Rejected(val detail: String) : UsbStableReconnectCandidateProof
}

internal data class UsbStableReconnectNonMatch(
    val candidate: UsbPotentialAudioDevice,
    val detail: String,
)

internal sealed interface UsbStableReconnectResolution {
    data object NoPotentialDevice : UsbStableReconnectResolution

    data class PermissionUnavailable(
        val candidates: List<UsbPotentialAudioDevice>,
    ) : UsbStableReconnectResolution

    data class Resolved(
        val candidate: UsbPotentialAudioDevice,
        val identity: UsbAudioDeviceIdentity,
    ) : UsbStableReconnectResolution

    data class Unavailable(
        val nonMatches: List<UsbStableReconnectNonMatch>,
    ) : UsbStableReconnectResolution

    data class Ambiguous(
        val matches: List<Resolved>,
    ) : UsbStableReconnectResolution
}

internal object UsbStableIdentityReconnectResolver {
    fun resolve(
        expectedIdentity: UsbAudioDeviceIdentity,
        attached: List<UsbAttachedDeviceDiscoveryFacts>,
        prove: (UsbPotentialAudioDevice) -> UsbStableReconnectCandidateProof,
    ): UsbStableReconnectResolution {
        val candidates = UsbPotentialAudioDeviceDiscovery.potentialCandidates(attached)
        if (candidates.isEmpty()) return UsbStableReconnectResolution.NoPotentialDevice

        // An unpermitted Audio-class device cannot be ruled in or out by authoritative runtime
        // facts. It could be the old target or an identical second target, so success must wait.
        if (candidates.any { it.permission != UsbPermissionState.GRANTED }) {
            return UsbStableReconnectResolution.PermissionUnavailable(candidates)
        }

        val matches = mutableListOf<UsbStableReconnectResolution.Resolved>()
        val nonMatches = mutableListOf<UsbStableReconnectNonMatch>()
        for (candidate in candidates) {
            when (val proof = prove(candidate)) {
                is UsbStableReconnectCandidateProof.Rejected -> nonMatches +=
                    UsbStableReconnectNonMatch(candidate, proof.detail)

                is UsbStableReconnectCandidateProof.Proven -> {
                    val conflicts = UsbStableIdentityPolicy.conflicts(expectedIdentity, proof.identity)
                    if (conflicts.isEmpty()) {
                        matches += UsbStableReconnectResolution.Resolved(candidate, proof.identity)
                    } else {
                        nonMatches += UsbStableReconnectNonMatch(
                            candidate,
                            "stable identity conflict=${conflicts.joinToString(separator = ",")}",
                        )
                    }
                }
            }
        }

        return when (matches.size) {
            0 -> UsbStableReconnectResolution.Unavailable(nonMatches)
            1 -> matches.single()
            else -> UsbStableReconnectResolution.Ambiguous(matches)
        }
    }
}
