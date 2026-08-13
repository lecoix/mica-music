package com.mica.music.media.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbProvenReconnectTargetTest {
    @Test
    fun releaseBeforeDetachKeepsProvenIdentityAndResolvesReenumeratedRuntime() {
        val owner = UsbOutputSessionOwner()
        val target = UsbProvenReconnectTarget()
        val identity = identity(serial = "SERIAL-A")
        val oldRuntime = UsbAudioRuntimeHandle(42)
        val session = target.publishAfterSuccessfulOpen(identity) {
            owner.replace(UsbOutputRequest(device = identity)) {
                FakeSession(oldRuntime)
            }
        }

        owner.release(session, "renderer-release")

        assertEquals(UsbOutputPhase.IDLE, owner.facts.phase)
        assertNull(owner.facts.request)
        assertEquals(
            UsbDeviceDetachDisposition.ORPHANED_CURRENT,
            owner.deviceDetached(oldRuntime),
        )
        assertEquals(identity, target.expectedIdentityForInterruptedRecovery(true))
        assertNull(target.expectedIdentityForInterruptedRecovery(false))

        val newRuntime = UsbAudioRuntimeHandle(91)
        val resolution = UsbStableIdentityReconnectResolver.resolve(
            expectedIdentity = target.expectedIdentityForInterruptedRecovery(true)!!,
            attached = listOf(device(newRuntime, UsbPermissionState.GRANTED)),
        ) {
            UsbStableReconnectCandidateProof.Proven(identity)
        } as UsbStableReconnectResolution.Resolved

        assertEquals(newRuntime, resolution.candidate.runtimeHandle)
        assertEquals(identity, resolution.identity)
    }

    @Test
    fun failedProductionOpenDoesNotReplaceLastProvenIdentity() {
        val owner = UsbOutputSessionOwner()
        val target = UsbProvenReconnectTarget()
        val proven = identity(fingerprint = "sha256:proven")
        val failed = identity(fingerprint = "sha256:failed")

        target.publishAfterSuccessfulOpen(proven) {
            owner.replace(UsbOutputRequest(device = proven)) {
                FakeSession(UsbAudioRuntimeHandle(11))
            }
        }

        val failure = runCatching {
            target.publishAfterSuccessfulOpen(failed) {
                owner.replace(UsbOutputRequest(device = failed)) {
                    error("synthetic production open failure")
                }
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(proven, target.identity)
    }

    @Test
    fun explicitClearRemovesReconnectAuthority() {
        val target = UsbProvenReconnectTarget()
        val proven = identity()
        target.publishAfterSuccessfulOpen(proven) { Unit }

        target.clear()

        assertNull(target.identity)
        assertNull(target.expectedIdentityForInterruptedRecovery(true))
    }

    @Test
    fun runtimeExplicitDisableAndServiceDestructionBothClearProcessLocalTarget() {
        val explicitDisableIdentity = identity(fingerprint = "sha256:disable")
        UsbProvenReconnectTargetRuntime.clearForServiceDestruction()
        UsbProvenReconnectTargetRuntime.publishAfterSuccessfulOpen(explicitDisableIdentity) { Unit }
        assertEquals(
            explicitDisableIdentity,
            UsbProvenReconnectTargetRuntime.expectedIdentityForInterruptedRecovery(true),
        )

        UsbProvenReconnectTargetRuntime.clearForExplicitDisable()
        assertNull(UsbProvenReconnectTargetRuntime.expectedIdentityForInterruptedRecovery(true))

        val serviceIdentity = identity(fingerprint = "sha256:service")
        UsbProvenReconnectTargetRuntime.publishAfterSuccessfulOpen(serviceIdentity) { Unit }
        UsbProvenReconnectTargetRuntime.clearForServiceDestruction()
        assertNull(UsbProvenReconnectTargetRuntime.expectedIdentityForInterruptedRecovery(true))
    }

    @Test
    fun laterSuccessfulProductionOpenOverwritesProvenIdentity() {
        val target = UsbProvenReconnectTarget()
        val first = identity(fingerprint = "sha256:first")
        val second = identity(fingerprint = "sha256:second")

        target.publishAfterSuccessfulOpen(first) { Unit }
        target.publishAfterSuccessfulOpen(second) { Unit }

        assertEquals(second, target.identity)
    }

    @Test
    fun retainedTargetStillUsesResolverFailClosedSemantics() {
        val target = UsbProvenReconnectTarget()
        val proven = identity(bcdDevice = 0x0004)
        target.publishAfterSuccessfulOpen(proven) { Unit }
        val expected = target.expectedIdentityForInterruptedRecovery(true)!!

        val drifted = UsbStableIdentityReconnectResolver.resolve(
            expectedIdentity = expected,
            attached = listOf(device(UsbAudioRuntimeHandle(21), UsbPermissionState.GRANTED)),
        ) {
            UsbStableReconnectCandidateProof.Proven(identity(bcdDevice = 0x0005))
        }
        assertTrue(drifted is UsbStableReconnectResolution.Unavailable)

        val ambiguous = UsbStableIdentityReconnectResolver.resolve(
            expectedIdentity = expected,
            attached = listOf(
                device(UsbAudioRuntimeHandle(31), UsbPermissionState.GRANTED),
                device(UsbAudioRuntimeHandle(32), UsbPermissionState.GRANTED),
            ),
        ) {
            UsbStableReconnectCandidateProof.Proven(proven)
        }
        assertTrue(ambiguous is UsbStableReconnectResolution.Ambiguous)

        var proofCalls = 0
        val permissionUnavailable = UsbStableIdentityReconnectResolver.resolve(
            expectedIdentity = expected,
            attached = listOf(device(UsbAudioRuntimeHandle(41), UsbPermissionState.UNKNOWN)),
        ) {
            proofCalls += 1
            UsbStableReconnectCandidateProof.Proven(proven)
        }
        assertTrue(permissionUnavailable is UsbStableReconnectResolution.PermissionUnavailable)
        assertEquals(0, proofCalls)
    }

    private fun device(
        runtimeHandle: UsbAudioRuntimeHandle,
        permission: UsbPermissionState,
    ) = UsbAttachedDeviceDiscoveryFacts(
        runtimeHandle = runtimeHandle,
        vendorId = 0x262a,
        productId = 0x0001,
        permission = permission,
        hasAudioInterface = true,
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

    private class FakeSession(
        private val runtimeHandle: UsbAudioRuntimeHandle,
    ) : UsbOutputSession {
        override val activeFacts: PlaybackOutputFacts
            get() = PlaybackOutputFacts(
                attached = true,
                permission = UsbPermissionState.GRANTED,
                runtimeHandle = runtimeHandle,
                claimed = true,
                exclusive = true,
                signalExact = true,
            )

        override fun restart(lease: UsbOutputRequestLease) = Unit

        override fun release(lease: UsbOutputCleanupLease, reason: String) {
            lease.ensureSerialized()
        }
    }
}
