package com.mica.music.media.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbReconnectProofPermissionTest {
    @Test
    fun oneUnpermittedMatchingVisibleVidPidRequestsProofPermissionForExactRuntime() {
        val candidate = potential(id = 91, permission = UsbPermissionState.UNKNOWN)
        val resolution = UsbStableReconnectResolution.PermissionUnavailable(listOf(candidate))

        val plan = UsbReconnectProofPermissionPlanner.plan(identity(), resolution)
            as UsbReconnectProofPermissionPlan.RequestProofPermission

        assertEquals(UsbAudioRuntimeHandle(91), plan.candidate.runtimeHandle)
    }

    @Test
    fun oneUnpermittedVisibleVidPidMismatchDoesNotRequest() {
        val candidate = potential(
            id = 91,
            vendorId = 0x1234,
            permission = UsbPermissionState.UNKNOWN,
        )

        val plan = UsbReconnectProofPermissionPlanner.plan(
            identity(),
            UsbStableReconnectResolution.PermissionUnavailable(listOf(candidate)),
        ) as UsbReconnectProofPermissionPlan.DoNotRequest

        assertEquals(
            UsbReconnectProofPermissionRejection.VISIBLE_VENDOR_PRODUCT_MISMATCH,
            plan.rejection,
        )
    }

    @Test
    fun twoUnpermittedSameVidPidPotentialsNeverRequestIndependentOfEnumerationOrder() {
        val first = potential(id = 20, permission = UsbPermissionState.UNKNOWN)
        val second = potential(id = 30, permission = UsbPermissionState.UNKNOWN)

        val forward = UsbReconnectProofPermissionPlanner.plan(
            identity(),
            UsbStableReconnectResolution.PermissionUnavailable(listOf(first, second)),
        ) as UsbReconnectProofPermissionPlan.DoNotRequest
        val reverse = UsbReconnectProofPermissionPlanner.plan(
            identity(),
            UsbStableReconnectResolution.PermissionUnavailable(listOf(second, first)),
        ) as UsbReconnectProofPermissionPlan.DoNotRequest

        assertEquals(UsbReconnectProofPermissionRejection.POTENTIAL_COUNT_NOT_ONE, forward.rejection)
        assertEquals(forward, reverse)
    }

    @Test
    fun matchingPotentialPlusUnrelatedAudioPotentialStillDoesNotRequest() {
        val matching = potential(id = 91, permission = UsbPermissionState.UNKNOWN)
        val unrelated = potential(
            id = 92,
            vendorId = 0x4321,
            productId = 0x0002,
            permission = UsbPermissionState.GRANTED,
        )

        val plan = UsbReconnectProofPermissionPlanner.plan(
            identity(),
            UsbStableReconnectResolution.PermissionUnavailable(listOf(matching, unrelated)),
        ) as UsbReconnectProofPermissionPlan.DoNotRequest

        assertEquals(UsbReconnectProofPermissionRejection.POTENTIAL_COUNT_NOT_ONE, plan.rejection)
    }

    @Test
    fun missingProvenIdentityCannotRequestPermission() {
        val candidate = potential(id = 91, permission = UsbPermissionState.UNKNOWN)

        val plan = UsbReconnectProofPermissionPlanner.plan(
            null,
            UsbStableReconnectResolution.PermissionUnavailable(listOf(candidate)),
        ) as UsbReconnectProofPermissionPlan.DoNotRequest

        assertEquals(UsbReconnectProofPermissionRejection.MISSING_PROVEN_IDENTITY, plan.rejection)
    }

    @Test
    fun permissionGrantAloneCannotCrossPostGrantProofGateAndFreshResolverMustRun() {
        var resolverCalls = 0
        val decision = UsbReconnectPostGrantProofGate.reproveAndDecide(
            grantedRuntimeHandle = UsbAudioRuntimeHandle(91),
        ) {
            resolverCalls += 1
            UsbStableReconnectResolution.PermissionUnavailable(
                listOf(potential(id = 91, permission = UsbPermissionState.UNKNOWN)),
            )
        } as UsbReconnectPostGrantDecision.DoNotRestore

        assertEquals(1, resolverCalls)
        assertEquals(UsbReconnectPostGrantRejection.NOT_EXACTLY_RESOLVED, decision.rejection)
    }

    @Test
    fun postGrantIdentityDriftDoesNotRestore() {
        val expected = identity(fingerprint = "sha256:old")
        val resolution = resolveAfterGrant(
            expected = expected,
            devices = listOf(attached(id = 91)),
            observed = mapOf(91 to identity(fingerprint = "sha256:new")),
        )

        val decision = UsbReconnectPostGrantProofGate.decide(UsbAudioRuntimeHandle(91), resolution)
            as UsbReconnectPostGrantDecision.DoNotRestore

        assertTrue(resolution is UsbStableReconnectResolution.Unavailable)
        assertEquals(UsbReconnectPostGrantRejection.NOT_EXACTLY_RESOLVED, decision.rejection)
    }

    @Test
    fun postGrantTwoExactMatchesDoNotRestore() {
        val expected = identity()
        val resolution = resolveAfterGrant(
            expected = expected,
            devices = listOf(attached(id = 91), attached(id = 92)),
            observed = mapOf(91 to expected, 92 to expected),
        )

        val decision = UsbReconnectPostGrantProofGate.decide(UsbAudioRuntimeHandle(91), resolution)
            as UsbReconnectPostGrantDecision.DoNotRestore

        assertTrue(resolution is UsbStableReconnectResolution.Ambiguous)
        assertEquals(UsbReconnectPostGrantRejection.NOT_EXACTLY_RESOLVED, decision.rejection)
    }

    @Test
    fun postGrantResolvedDifferentRuntimeDoesNotRestore() {
        val expected = identity()
        val resolution = resolveAfterGrant(
            expected = expected,
            devices = listOf(attached(id = 92)),
            observed = mapOf(92 to expected),
        )

        val decision = UsbReconnectPostGrantProofGate.decide(UsbAudioRuntimeHandle(91), resolution)
            as UsbReconnectPostGrantDecision.DoNotRestore

        assertEquals(UsbReconnectPostGrantRejection.RESOLVED_RUNTIME_MISMATCH, decision.rejection)
    }

    @Test
    fun postGrantUniqueExactResolutionForGrantedRuntimeReachesRestoreGate() {
        val expected = identity(serial = "SERIAL-A")
        val resolution = resolveAfterGrant(
            expected = expected,
            devices = listOf(attached(id = 91)),
            observed = mapOf(91 to expected),
        )

        val decision = UsbReconnectPostGrantProofGate.decide(UsbAudioRuntimeHandle(91), resolution)
            as UsbReconnectPostGrantDecision.Restore

        assertEquals(91, decision.resolved.candidate.runtimeHandle.runtimeDeviceId)
        assertEquals(expected, decision.resolved.identity)
    }

    @Test
    fun denialAndStalePermissionPublicationStayFailClosedWithInterruptedIntent() {
        val coordinator = UsbLifecycleRecoveryCoordinator()
        val detach = coordinator.beginDetach(UsbAudioRuntimeHandle(42))
        assertTrue(coordinator.rememberInterruptedPlayback(detach, true, "device_detached"))

        val deniedAttach = coordinator.beginAttach(UsbAudioRuntimeHandle(91))
        assertTrue(coordinator.bindPermissionRequest(deniedAttach, 17L))
        assertTrue(coordinator.rejectPermission(UsbAudioRuntimeHandle(91), 17L))
        assertFalse(
            coordinator.publishGrantedPermission(UsbAudioRuntimeHandle(91), 17L) { true },
        )
        assertTrue(coordinator.hasInterruptedUsbIntent)

        val newerAttach = coordinator.beginAttach(UsbAudioRuntimeHandle(92))
        assertTrue(coordinator.bindPermissionRequest(newerAttach, 18L))
        assertFalse(
            coordinator.publishGrantedPermission(UsbAudioRuntimeHandle(91), 17L) { true },
        )
        assertTrue(coordinator.hasInterruptedUsbIntent)
    }

    private fun resolveAfterGrant(
        expected: UsbAudioDeviceIdentity,
        devices: List<UsbAttachedDeviceDiscoveryFacts>,
        observed: Map<Int, UsbAudioDeviceIdentity>,
    ): UsbStableReconnectResolution = UsbStableIdentityReconnectResolver.resolve(
        expectedIdentity = expected,
        attached = devices,
    ) { candidate ->
        observed[candidate.runtimeHandle.runtimeDeviceId]
            ?.let(UsbStableReconnectCandidateProof::Proven)
            ?: UsbStableReconnectCandidateProof.Rejected("missing fake proof")
    }

    private fun attached(id: Int) = UsbAttachedDeviceDiscoveryFacts(
        runtimeHandle = UsbAudioRuntimeHandle(id),
        vendorId = 0x262a,
        productId = 0x0001,
        permission = UsbPermissionState.GRANTED,
        hasAudioInterface = true,
    )

    private fun potential(
        id: Int,
        vendorId: Int = 0x262a,
        productId: Int = 0x0001,
        permission: UsbPermissionState,
    ) = UsbPotentialAudioDevice(
        runtimeHandle = UsbAudioRuntimeHandle(id),
        vendorId = vendorId,
        productId = productId,
        permission = permission,
    )

    private fun identity(
        fingerprint: String = "sha256:descriptor",
        serial: String? = null,
    ) = UsbAudioDeviceIdentity(
        vendorId = 0x262a,
        productId = 0x0001,
        descriptorFingerprint = fingerprint,
        serialNumber = serial,
        bcdDevice = 0x0004,
    )
}
