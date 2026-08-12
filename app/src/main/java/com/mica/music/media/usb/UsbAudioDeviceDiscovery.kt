package com.mica.music.media.usb

internal data class UsbAttachedDeviceDiscoveryFacts(
    val runtimeHandle: UsbAudioRuntimeHandle,
    val vendorId: Int,
    val productId: Int,
    val permission: UsbPermissionState,
    val hasAudioInterface: Boolean,
)

internal data class UsbPotentialAudioDevice(
    val runtimeHandle: UsbAudioRuntimeHandle,
    val vendorId: Int,
    val productId: Int,
    val permission: UsbPermissionState,
)

internal sealed interface UsbPotentialAudioDiscoveryResult {
    data object NoPotentialDevice : UsbPotentialAudioDiscoveryResult

    data class PermissionNeeded(
        val candidates: List<UsbPotentialAudioDevice>,
    ) : UsbPotentialAudioDiscoveryResult

    data class OnePermittedCandidate(
        val candidate: UsbPotentialAudioDevice,
    ) : UsbPotentialAudioDiscoveryResult

    data class Ambiguous(
        val candidates: List<UsbPotentialAudioDevice>,
    ) : UsbPotentialAudioDiscoveryResult
}

/**
 * Discovery is intentionally coarse. USB Audio interface evidence can make a device a potential
 * candidate, but never proves exact format/capability compatibility.
 */
internal object UsbPotentialAudioDeviceDiscovery {
    fun discover(
        attached: List<UsbAttachedDeviceDiscoveryFacts>,
    ): UsbPotentialAudioDiscoveryResult {
        val candidates = attached
            .asSequence()
            .filter(UsbAttachedDeviceDiscoveryFacts::hasAudioInterface)
            .map { facts ->
                UsbPotentialAudioDevice(
                    runtimeHandle = facts.runtimeHandle,
                    vendorId = facts.vendorId,
                    productId = facts.productId,
                    permission = facts.permission,
                )
            }
            .sortedWith(
                compareBy<UsbPotentialAudioDevice>(
                    UsbPotentialAudioDevice::vendorId,
                    UsbPotentialAudioDevice::productId,
                ).thenBy { it.runtimeHandle.runtimeDeviceId },
            )
            .toList()

        if (candidates.isEmpty()) return UsbPotentialAudioDiscoveryResult.NoPotentialDevice

        // An unproved, unpermitted Audio-class device could also be compatible. Do not silently
        // prefer a permitted device while another potential candidate cannot yet be proved.
        if (candidates.any { it.permission != UsbPermissionState.GRANTED }) {
            return UsbPotentialAudioDiscoveryResult.PermissionNeeded(candidates)
        }

        return if (candidates.size == 1) {
            UsbPotentialAudioDiscoveryResult.OnePermittedCandidate(candidates.single())
        } else {
            UsbPotentialAudioDiscoveryResult.Ambiguous(candidates)
        }
    }
}

internal sealed interface UsbSingleCandidateCompatibilityResult {
    data class Compatible(
        val identity: UsbAudioDeviceIdentity,
    ) : UsbSingleCandidateCompatibilityResult

    data class NoCompatible(
        val rejection: UsbAudioRejection,
    ) : UsbSingleCandidateCompatibilityResult

    data class RuntimeFactRejected(
        val detail: String,
    ) : UsbSingleCandidateCompatibilityResult
}

internal sealed interface UsbAudioDeviceSelectionResult {
    data object NoPotentialDevice : UsbAudioDeviceSelectionResult

    data class PermissionNeeded(
        val candidates: List<UsbPotentialAudioDevice>,
    ) : UsbAudioDeviceSelectionResult

    data class OneCompatible(
        val candidate: UsbPotentialAudioDevice,
        val identity: UsbAudioDeviceIdentity,
    ) : UsbAudioDeviceSelectionResult

    data class NoCompatible(
        val candidate: UsbPotentialAudioDevice,
        val rejection: UsbAudioRejection,
    ) : UsbAudioDeviceSelectionResult

    data class Ambiguous(
        val candidates: List<UsbPotentialAudioDevice>,
    ) : UsbAudioDeviceSelectionResult

    data class RuntimeFactRejected(
        val candidate: UsbPotentialAudioDevice,
        val detail: String,
    ) : UsbAudioDeviceSelectionResult
}

internal object UsbAudioDeviceSelection {
    fun select(
        attached: List<UsbAttachedDeviceDiscoveryFacts>,
        prove: (UsbPotentialAudioDevice) -> UsbSingleCandidateCompatibilityResult,
    ): UsbAudioDeviceSelectionResult = when (val discovery = UsbPotentialAudioDeviceDiscovery.discover(attached)) {
        UsbPotentialAudioDiscoveryResult.NoPotentialDevice -> UsbAudioDeviceSelectionResult.NoPotentialDevice
        is UsbPotentialAudioDiscoveryResult.PermissionNeeded ->
            UsbAudioDeviceSelectionResult.PermissionNeeded(discovery.candidates)
        is UsbPotentialAudioDiscoveryResult.Ambiguous ->
            UsbAudioDeviceSelectionResult.Ambiguous(discovery.candidates)
        is UsbPotentialAudioDiscoveryResult.OnePermittedCandidate -> {
            val candidate = discovery.candidate
            when (val proof = prove(candidate)) {
                is UsbSingleCandidateCompatibilityResult.Compatible ->
                    UsbAudioDeviceSelectionResult.OneCompatible(candidate, proof.identity)
                is UsbSingleCandidateCompatibilityResult.NoCompatible ->
                    UsbAudioDeviceSelectionResult.NoCompatible(candidate, proof.rejection)
                is UsbSingleCandidateCompatibilityResult.RuntimeFactRejected ->
                    UsbAudioDeviceSelectionResult.RuntimeFactRejected(candidate, proof.detail)
            }
        }
    }
}
