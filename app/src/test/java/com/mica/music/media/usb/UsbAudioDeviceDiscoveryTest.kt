package com.mica.music.media.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioDeviceDiscoveryTest {
    @Test
    fun zeroPotentialDevicesFailsDeterministically() {
        val result = UsbAudioDeviceSelection.select(
            attached = listOf(device(id = 9, audio = false, permission = UsbPermissionState.GRANTED)),
            prove = { error("non-audio device must not be proved") },
        )

        assertEquals(UsbAudioDeviceSelectionResult.NoPotentialDevice, result)
    }

    @Test
    fun permissionNeededWinsOverPermittedCandidateBecauseUnknownDeviceCouldAlsoBeCompatible() {
        var proofCalls = 0
        val result = UsbAudioDeviceSelection.select(
            attached = listOf(
                device(id = 10, vendor = 0x1111, product = 1, permission = UsbPermissionState.GRANTED),
                device(id = 11, vendor = 0x2222, product = 2, permission = UsbPermissionState.UNKNOWN),
            ),
            prove = {
                proofCalls += 1
                UsbSingleCandidateCompatibilityResult.Compatible(identityFor(it))
            },
        )

        assertTrue(result is UsbAudioDeviceSelectionResult.PermissionNeeded)
        assertEquals(0, proofCalls)
    }

    @Test
    fun onePermittedPotentialCanBeProvenCompatible() {
        val result = UsbAudioDeviceSelection.select(
            attached = listOf(device(id = 12, vendor = 0x262a, product = 1, permission = UsbPermissionState.GRANTED)),
            prove = { UsbSingleCandidateCompatibilityResult.Compatible(identityFor(it)) },
        ) as UsbAudioDeviceSelectionResult.OneCompatible

        assertEquals(12, result.candidate.runtimeHandle.runtimeDeviceId)
        assertEquals(0x262a, result.identity.vendorId)
        assertEquals(1, result.identity.productId)
    }

    @Test
    fun authoritativeProofCanRejectPotentialAsNotCompatible() {
        val rejection = UsbAudioRejection(
            UsbAudioRejectionCode.UNSUPPORTED_FORMAT,
            "no exact profile",
        )
        val result = UsbAudioDeviceSelection.select(
            attached = listOf(device(id = 13, permission = UsbPermissionState.GRANTED)),
            prove = { UsbSingleCandidateCompatibilityResult.NoCompatible(rejection) },
        ) as UsbAudioDeviceSelectionResult.NoCompatible

        assertEquals(rejection, result.rejection)
        assertEquals(13, result.candidate.runtimeHandle.runtimeDeviceId)
    }

    @Test
    fun runtimeFactRejectionRemainsDistinctFromNoCompatible() {
        val result = UsbAudioDeviceSelection.select(
            attached = listOf(device(id = 14, permission = UsbPermissionState.GRANTED)),
            prove = { UsbSingleCandidateCompatibilityResult.RuntimeFactRejected("speed unavailable") },
        ) as UsbAudioDeviceSelectionResult.RuntimeFactRejected

        assertEquals("speed unavailable", result.detail)
    }

    @Test
    fun twoIdenticalCompatibleDevicesRemainAmbiguousEvenWhenRuntimeIdsDiffer() {
        var proofCalls = 0
        val result = UsbAudioDeviceSelection.select(
            attached = listOf(
                device(id = 200, vendor = 0x262a, product = 1, permission = UsbPermissionState.GRANTED),
                device(id = 100, vendor = 0x262a, product = 1, permission = UsbPermissionState.GRANTED),
            ),
            prove = {
                proofCalls += 1
                UsbSingleCandidateCompatibilityResult.Compatible(identityFor(it))
            },
        ) as UsbAudioDeviceSelectionResult.Ambiguous

        assertEquals(0, proofCalls)
        assertEquals(listOf(100, 200), result.candidates.map { it.runtimeHandle.runtimeDeviceId })
    }

    @Test
    fun discoveryResultDoesNotDependOnEnumerationOrder() {
        val devices = listOf(
            device(id = 42, vendor = 0x3333, product = 9, permission = UsbPermissionState.GRANTED),
            device(id = 7, vendor = 0x1111, product = 3, permission = UsbPermissionState.GRANTED),
        )

        val forward = UsbPotentialAudioDeviceDiscovery.discover(devices)
        val reverse = UsbPotentialAudioDeviceDiscovery.discover(devices.reversed())

        assertEquals(forward, reverse)
        assertTrue(forward is UsbPotentialAudioDiscoveryResult.Ambiguous)
    }

    private fun device(
        id: Int,
        vendor: Int = 0x1234,
        product: Int = 0x5678,
        permission: UsbPermissionState,
        audio: Boolean = true,
    ) = UsbAttachedDeviceDiscoveryFacts(
        runtimeHandle = UsbAudioRuntimeHandle(id),
        vendorId = vendor,
        productId = product,
        permission = permission,
        hasAudioInterface = audio,
    )

    private fun identityFor(candidate: UsbPotentialAudioDevice) = UsbAudioDeviceIdentity(
        vendorId = candidate.vendorId,
        productId = candidate.productId,
        descriptorFingerprint = "test:${candidate.vendorId}:${candidate.productId}",
    )
}
