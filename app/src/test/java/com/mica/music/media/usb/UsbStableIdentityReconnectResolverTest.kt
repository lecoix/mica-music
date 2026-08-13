package com.mica.music.media.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbStableIdentityReconnectResolverTest {
    @Test
    fun reenumeratedSameIdentityResolvesWithNewRuntimeDeviceId() {
        val expected = identity(serial = "SERIAL-A")
        val result = resolve(
            expected = expected,
            devices = listOf(device(id = 91)),
            identities = mapOf(91 to expected),
        ) as UsbStableReconnectResolution.Resolved

        assertEquals(91, result.candidate.runtimeHandle.runtimeDeviceId)
        assertEquals(expected, result.identity)
    }

    @Test
    fun oldRuntimeHandleMissingDoesNotMatterWhenStableIdentityMatches() {
        val expected = identity()
        val result = resolve(
            expected = expected,
            devices = listOf(device(id = 404)),
            identities = mapOf(404 to expected),
        )

        assertTrue(result is UsbStableReconnectResolution.Resolved)
    }

    @Test
    fun sameVidPidDifferentFingerprintDoesNotMatch() {
        val expected = identity(fingerprint = "sha256:old")
        val observed = identity(fingerprint = "sha256:new")
        val result = resolve(
            expected = expected,
            devices = listOf(device(id = 2)),
            identities = mapOf(2 to observed),
        ) as UsbStableReconnectResolution.Unavailable

        assertTrue(result.nonMatches.single().detail.contains("DESCRIPTOR_FINGERPRINT"))
    }

    @Test
    fun conflictingBcdDeviceDoesNotMatch() {
        val expected = identity(bcdDevice = 0x0004)
        val observed = identity(bcdDevice = 0x0005)
        val result = resolve(
            expected = expected,
            devices = listOf(device(id = 3)),
            identities = mapOf(3 to observed),
        ) as UsbStableReconnectResolution.Unavailable

        assertTrue(result.nonMatches.single().detail.contains("BCD_DEVICE"))
    }

    @Test
    fun previouslyUnknownRevisionDoesNotInventReconnectRequirement() {
        val expected = identity(bcdDevice = null)
        val observed = identity(bcdDevice = 0x0004)

        assertTrue(UsbStableIdentityPolicy.matches(expected, observed))
    }

    @Test
    fun serialPresentAndChangedFailsClosed() {
        val expected = identity(serial = "SERIAL-A")
        val observed = identity(serial = "SERIAL-B")
        val result = resolve(
            expected = expected,
            devices = listOf(device(id = 4)),
            identities = mapOf(4 to observed),
        ) as UsbStableReconnectResolution.Unavailable

        assertTrue(result.nonMatches.single().detail.contains("SERIAL_NUMBER"))
    }

    @Test
    fun serialPresentThenUnavailableFailsClosed() {
        val expected = identity(serial = "SERIAL-A")
        val observed = identity(serial = null)

        assertTrue(
            UsbStableIdentityConflict.SERIAL_NUMBER in
                UsbStableIdentityPolicy.conflicts(expected, observed),
        )
    }

    @Test
    fun previouslyUnknownSerialDoesNotInventReconnectRequirement() {
        val expected = identity(serial = null)
        val observed = identity(serial = "SERIAL-A")

        assertTrue(UsbStableIdentityPolicy.matches(expected, observed))
    }

    @Test
    fun twoIdenticalStableMatchesAreAmbiguous() {
        val expected = identity()
        val result = resolve(
            expected = expected,
            devices = listOf(device(id = 30), device(id = 20)),
            identities = mapOf(20 to expected, 30 to expected),
        ) as UsbStableReconnectResolution.Ambiguous

        assertEquals(listOf(20, 30), result.matches.map { it.candidate.runtimeHandle.runtimeDeviceId })
    }

    @Test
    fun enumerationOrderDoesNotAffectResolvedOutcome() {
        val expected = identity()
        val mismatch = identity(fingerprint = "sha256:mismatch")
        val devices = listOf(device(id = 70), device(id = 10))
        val identities = mapOf(70 to expected, 10 to mismatch)

        val forward = resolve(expected, devices, identities)
        val reverse = resolve(expected, devices.reversed(), identities)

        assertEquals(forward, reverse)
        assertEquals(
            70,
            (forward as UsbStableReconnectResolution.Resolved).candidate.runtimeHandle.runtimeDeviceId,
        )
    }

    @Test
    fun zeroPotentialCandidatesIsExplicitNonSuccess() {
        val result = resolve(
            expected = identity(),
            devices = listOf(device(id = 1, audio = false)),
            identities = emptyMap(),
        )

        assertEquals(UsbStableReconnectResolution.NoPotentialDevice, result)
    }

    @Test
    fun permissionUnavailableBlocksProofAndSuccess() {
        var proofCalls = 0
        val result = UsbStableIdentityReconnectResolver.resolve(
            expectedIdentity = identity(),
            attached = listOf(device(id = 5, permission = UsbPermissionState.UNKNOWN)),
        ) {
            proofCalls += 1
            UsbStableReconnectCandidateProof.Proven(identity())
        }

        assertTrue(result is UsbStableReconnectResolution.PermissionUnavailable)
        assertEquals(0, proofCalls)
    }

    @Test
    fun rejectedRuntimeFactsBecomeUnavailableRatherThanIdentityFallback() {
        val result = UsbStableIdentityReconnectResolver.resolve(
            expectedIdentity = identity(),
            attached = listOf(device(id = 6)),
        ) {
            UsbStableReconnectCandidateProof.Rejected("speed unavailable")
        } as UsbStableReconnectResolution.Unavailable

        assertEquals("speed unavailable", result.nonMatches.single().detail)
    }

    private fun resolve(
        expected: UsbAudioDeviceIdentity,
        devices: List<UsbAttachedDeviceDiscoveryFacts>,
        identities: Map<Int, UsbAudioDeviceIdentity>,
    ): UsbStableReconnectResolution = UsbStableIdentityReconnectResolver.resolve(
        expectedIdentity = expected,
        attached = devices,
    ) { candidate ->
        val identity = identities[candidate.runtimeHandle.runtimeDeviceId]
            ?: return@resolve UsbStableReconnectCandidateProof.Rejected("missing fake proof")
        UsbStableReconnectCandidateProof.Proven(identity)
    }

    private fun device(
        id: Int,
        permission: UsbPermissionState = UsbPermissionState.GRANTED,
        audio: Boolean = true,
    ) = UsbAttachedDeviceDiscoveryFacts(
        runtimeHandle = UsbAudioRuntimeHandle(id),
        vendorId = 0x262a,
        productId = 0x0001,
        permission = permission,
        hasAudioInterface = audio,
    )

    private fun identity(
        fingerprint: String = "sha256:descriptor",
        bcdDevice: Int? = 0x0004,
        serial: String? = null,
    ) = UsbAudioDeviceIdentity(
        vendorId = 0x262a,
        productId = 0x0001,
        descriptorFingerprint = fingerprint,
        serialNumber = serial,
        bcdDevice = bcdDevice,
    )
}
