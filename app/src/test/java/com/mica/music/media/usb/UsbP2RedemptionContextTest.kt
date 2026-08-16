package com.mica.music.media.usb

import com.mica.music.media.usb.protocol.ActiveWriteLease
import com.mica.music.media.usb.protocol.AdapterInstanceId
import com.mica.music.media.usb.protocol.ActivationId
import com.mica.music.media.usb.protocol.FamilyOwnershipId
import com.mica.music.media.usb.protocol.MutationId
import com.mica.music.media.usb.protocol.OutputTarget
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.PlaybackStackId
import com.mica.music.media.usb.protocol.UsbOutputGeneration
import com.mica.music.media.usb.protocol.WriteKind
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbP2RedemptionContextTest {
    @Test
    fun reservationBindsProtocolTargetToOneOwnerSessionAndRotatesOnRestart() {
        val owner = UsbOutputSessionOwner()
        val context = UsbP2RedemptionContext(owner, request())

        val firstTarget = assertTrueUsb(context.prepareProtocolBinding())
        val session = context.consumeCurrent { binding, lease ->
            binding.ensureRequestLease(lease)
            FakeSession()
        }
        val firstBinding = context.currentBinding()
        assertEquals(firstTarget, firstBinding.target)
        assertTrue(owner.facts.phase == UsbOutputPhase.ACTIVE)

        owner.restart(session)

        val rotatedBinding = context.currentBinding()
        assertTrue(rotatedBinding.target.generation.value > firstTarget.generation.value)
        assertFalse(rotatedBinding.target == firstTarget)
        assertTrue(owner.facts.generation == rotatedBinding.target.generation.value)
    }

    @Test
    fun staleReservationCannotRedeemAfterOwnerGenerationChanges() {
        val owner = UsbOutputSessionOwner()
        val context = UsbP2RedemptionContext(owner, request())
        val target = assertTrueUsb(context.prepareProtocolBinding())
        val binding = context.currentBinding()
        val staleLease = UsbOutputRequestLease(
            UsbOutputRequestToken(target.generation.value),
            owner,
        )

        owner.invalidate()

        assertThrows(StaleUsbOutputRequestException::class.java) {
            binding.ensureRequestLease(staleLease)
        }
        assertThrows(IllegalStateException::class.java) {
            context.prepareProtocolBinding()
        }

        // A fresh renderer stack may establish a new binding; the stale stack may not re-reserve
        // the owner generation after it has been invalidated.
        val successorContext = UsbP2RedemptionContext(owner, request())
        val successorTarget = assertTrueUsb(successorContext.prepareProtocolBinding())
        assertTrue(successorTarget.generation.value > target.generation.value)
    }

    @Test
    fun successorReservationCleansOldActiveSessionInsideOwnerSeam() {
        val owner = UsbOutputSessionOwner()
        val releases = AtomicInteger(0)
        owner.replace(request()) { FakeSession { releases.incrementAndGet() } }

        val successorContext = UsbP2RedemptionContext(owner, request())
        successorContext.prepareProtocolBinding()

        assertEquals(1, releases.get())
        assertEquals(UsbOutputPhase.RELEASING, owner.facts.phase)
    }

    @Test
    fun protocolWriteScopeIsExactKindNestedSafeAndDoesNotLeakAcrossThreads() {
        val owner = UsbOutputSessionOwner()
        val context = UsbP2RedemptionContext(owner, request())
        val target = assertTrueUsb(context.prepareProtocolBinding())
        context.consumeCurrent { _, _ -> FakeSession() }
        val occurrence = PlaybackOccurrence("period", 7L)
        val writeLease = ActiveWriteLease(
            com.mica.music.media.usb.protocol.WriteLeaseIdentity(
                stackId = PlaybackStackId(1L),
                outputTarget = target,
                mutationId = MutationId(2L),
                occurrence = occurrence,
                adapterInstanceId = AdapterInstanceId(3L),
                familyOwnershipId = FamilyOwnershipId(4L),
                activationId = ActivationId(5L),
                family = com.mica.music.media.usb.protocol.PlaybackFamily.DOP,
            ),
        )
        assertTrue(writeLease.tryEnter(occurrence, MutationId(2L), AdapterInstanceId(3L), WriteKind.DOP_CONTENT))

        val effects = AtomicInteger(0)
        val workerFailure = AtomicReference<Throwable?>(null)
        context.withProtocolWrite(target, writeLease, WriteKind.DOP_CONTENT) {
            context.requireProtocolWrite(target, WriteKind.DOP_CONTENT)
            context.withProtocolWrite(target, writeLease, WriteKind.DOP_CONTENT) {
                effects.incrementAndGet()
            }
            thread(start = true) {
                try {
                    context.requireProtocolWrite(target, WriteKind.DOP_CONTENT)
                } catch (error: Throwable) {
                    workerFailure.set(error)
                }
            }.join()
            assertThrows(IllegalStateException::class.java) {
                context.requireProtocolWrite(target, WriteKind.DOP_GAP)
            }
        }
        writeLease.exit()

        assertEquals(1, effects.get())
        assertNotNull(workerFailure.get())
        assertThrows(IllegalStateException::class.java) {
            context.requireProtocolWrite(target, WriteKind.DOP_CONTENT)
        }
    }

    @Test
    fun unavailableSharedPcmContextCannotRedeemUsb() {
        val context = UsbP2RedemptionContext(UsbOutputSessionOwner(), null)

        assertEquals(null, context.prepareProtocolBinding())
        assertThrows(IllegalStateException::class.java) {
            context.requireCurrentBinding()
        }
    }

    @Test
    fun pcmWriteScopeRejectsDopAndWrongTarget() {
        val owner = UsbOutputSessionOwner()
        val context = UsbP2RedemptionContext(owner, request())
        val target = assertTrueUsb(context.prepareProtocolBinding())
        context.consumeCurrent { _, _ -> FakeSession() }
        val lease = ActiveWriteLease(
            com.mica.music.media.usb.protocol.WriteLeaseIdentity(
                stackId = PlaybackStackId(11L),
                outputTarget = target,
                mutationId = MutationId(12L),
                occurrence = PlaybackOccurrence("period", 13L),
                adapterInstanceId = AdapterInstanceId(14L),
                familyOwnershipId = FamilyOwnershipId(15L),
                activationId = ActivationId(16L),
                family = com.mica.music.media.usb.protocol.PlaybackFamily.PCM,
            ),
        )
        val occurrence = lease.identity.occurrence
        assertTrue(lease.tryEnter(occurrence, MutationId(12L), AdapterInstanceId(14L), WriteKind.PCM_DATA))
        context.withProtocolWrite(target, lease, WriteKind.PCM_DATA) {
            context.requireProtocolWrite(target, WriteKind.PCM_DATA)
            assertThrows(IllegalStateException::class.java) {
                context.requireProtocolWrite(target, WriteKind.DOP_CONTENT)
            }
            assertThrows(IllegalStateException::class.java) {
                context.requireProtocolWrite(
                    OutputTarget.UsbBound(UsbOutputGeneration(target.generation.value + 1L)),
                    WriteKind.PCM_DATA,
                )
            }
        }
        lease.exit()
    }

    private fun request(): UsbOutputRequest = UsbOutputRequest(
        device = UsbAudioDeviceIdentity(
            vendorId = 0x1234,
            productId = 0x5678,
            descriptorFingerprint = "test-device",
        ),
    )

    private fun assertTrueUsb(target: OutputTarget?): OutputTarget.UsbBound {
        assertTrue(target is OutputTarget.UsbBound)
        return target as OutputTarget.UsbBound
    }

    private class FakeSession(
        private val onRelease: () -> Unit = {},
    ) : UsbOutputSession {
        override val activeFacts: PlaybackOutputFacts = PlaybackOutputFacts()
        override fun restart(lease: UsbOutputRequestLease) = lease.ensureCurrent()
        override fun release(lease: UsbOutputCleanupLease, reason: String) {
            lease.ensureSerialized()
            onRelease()
        }
    }
}
