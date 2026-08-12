package com.mica.music.media.usb

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbOutputSessionOwnerTest {
    @Test
    fun oldOpenPausedAtSideEffectBoundaryCannotWriteAfterNewRequestWins() {
        val publishedGenerations = Collections.synchronizedList(mutableListOf<Long>())
        val owner = UsbOutputSessionOwner(publishedGenerations::add)
        val effects = Collections.synchronizedList(mutableListOf<String>())
        val oldAtBoundary = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldFinished = CountDownLatch(1)

        val oldThread = thread(name = "old-usb-open") {
            runCatching {
                owner.replace(request("old")) { lease ->
                    oldAtBoundary.countDown()
                    assertTrue(releaseOld.await(5, TimeUnit.SECONDS))
                    lease.io { effects += "old-claim" }
                    FakeSession("old", effects)
                }
            }
            oldFinished.countDown()
        }
        assertTrue(oldAtBoundary.await(5, TimeUnit.SECONDS))

        val newStarted = CountDownLatch(1)
        val newThread = thread(name = "new-usb-open") {
            newStarted.countDown()
            owner.replace(request("new")) { lease ->
                lease.io { effects += "new-claim" }
                FakeSession("new", effects)
            }
        }
        assertTrue(newStarted.await(5, TimeUnit.SECONDS))
        while (publishedGenerations.size < 2) Thread.yield()
        releaseOld.countDown()

        assertTrue(oldFinished.await(5, TimeUnit.SECONDS))
        oldThread.join(5_000)
        newThread.join(5_000)
        assertEquals(listOf("new-claim"), effects)
        assertEquals(UsbOutputPhase.ACTIVE, owner.facts.phase)
        assertEquals("new", owner.facts.request?.device?.descriptorFingerprint)
    }

    @Test
    fun cleanupCompletesInsideSeamEvenWhenOpeningRequestIsSuperseded() {
        val thirdGenerationPublished = CountDownLatch(1)
        val owner = UsbOutputSessionOwner(
            onGenerationPublished = { generation ->
                if (generation == 3L) thirdGenerationPublished.countDown()
            },
        )
        val effects = Collections.synchronizedList(mutableListOf<String>())
        val cleanupAtBoundary = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val initial = owner.replace(request("initial")) {
            FakeSession(
                name = "initial",
                effects = effects,
                onRelease = { lease ->
                    cleanupAtBoundary.countDown()
                    assertTrue(releaseCleanup.await(5, TimeUnit.SECONDS))
                    lease.io { effects += "initial-release-interface" }
                },
            )
        }
        assertEquals(UsbOutputPhase.ACTIVE, owner.facts.phase)

        val secondFinished = CountDownLatch(1)
        val second = thread(name = "second-usb-open") {
            runCatching {
                owner.replace(request("second")) { lease ->
                    lease.io { effects += "second-claim" }
                    FakeSession("second", effects)
                }
            }
            secondFinished.countDown()
        }
        assertTrue(cleanupAtBoundary.await(5, TimeUnit.SECONDS))

        val third = thread(name = "third-usb-open") {
            owner.replace(request("third")) { lease ->
                lease.io { effects += "third-claim" }
                FakeSession("third", effects)
            }
        }
        assertTrue(thirdGenerationPublished.await(5, TimeUnit.SECONDS))
        releaseCleanup.countDown()

        assertTrue(secondFinished.await(5, TimeUnit.SECONDS))
        second.join(5_000)
        third.join(5_000)
        assertTrue(effects.contains("initial-release-interface"))
        assertFalse(effects.contains("second-claim"))
        assertTrue(effects.contains("third-claim"))
        assertEquals("third", owner.facts.request?.device?.descriptorFingerprint)

        owner.release(initial) // A stale release is a no-op and must not invalidate the winner.
        assertEquals(UsbOutputPhase.ACTIVE, owner.facts.phase)
    }

    @Test
    fun activeWritePausedAtNativeBoundaryCannotSubmitAfterReplacementIsRequested() {
        val replacementPublished = CountDownLatch(1)
        val owner = UsbOutputSessionOwner(
            onGenerationPublished = { generation ->
                if (generation == 2L) replacementPublished.countDown()
            },
        )
        val effects = Collections.synchronizedList(mutableListOf<String>())
        val initial = owner.replace(request("initial")) { FakeSession("initial", effects) }
        val writeAtBoundary = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)

        val writer = thread(name = "old-active-write") {
            owner.withActiveSession(initial) { lease ->
                writeAtBoundary.countDown()
                assertTrue(releaseWrite.await(5, TimeUnit.SECONDS))
                runCatching { lease.io { effects += "old-native-submit" } }
            }
        }
        assertTrue(writeAtBoundary.await(5, TimeUnit.SECONDS))

        val replacement = thread(name = "replacement-open") {
            owner.replace(request("replacement")) { lease ->
                lease.io { effects += "replacement-claim" }
                FakeSession("replacement", effects)
            }
        }
        assertTrue(replacementPublished.await(5, TimeUnit.SECONDS))
        releaseWrite.countDown()

        writer.join(5_000)
        replacement.join(5_000)
        assertFalse(effects.contains("old-native-submit"))
        assertTrue(effects.contains("initial-close"))
        assertTrue(effects.contains("replacement-claim"))
    }

    @Test
    fun debugHarnessCannotEnterTransportWhileProductionSessionIsActive() {
        val owner = UsbOutputSessionOwner()
        val effects = mutableListOf<String>()
        owner.replace(request("active")) { FakeSession("active", effects) }
        val generationBeforeProbe = owner.facts.generation
        val probe = owner.beginHarnessRequest()

        assertEquals(null, probe)
        assertEquals(generationBeforeProbe, owner.facts.generation)
        assertFalse(effects.contains("raw-probe-claim"))
    }

    @Test
    fun staleActiveFactsPausedAtPublicationBoundaryCannotOverwriteWinner() {
        val oldAtFactsBoundary = CountDownLatch(1)
        val releaseOldFacts = CountDownLatch(1)
        val newerGenerationPublished = CountDownLatch(1)
        val owner = UsbOutputSessionOwner(
            onGenerationPublished = { generation ->
                if (generation == 2L) newerGenerationPublished.countDown()
            },
            beforeFactsPublication = { facts ->
                if (facts.generation == 1L && facts.phase == UsbOutputPhase.ACTIVE) {
                    oldAtFactsBoundary.countDown()
                    assertTrue(releaseOldFacts.await(5, TimeUnit.SECONDS))
                }
            },
        )

        val old = thread(name = "old-facts-publication") {
            runCatching { owner.replace(request("old-facts")) { FakeSession("old", mutableListOf()) } }
        }
        assertTrue(oldAtFactsBoundary.await(5, TimeUnit.SECONDS))
        val newer = thread(name = "new-facts-publication") {
            owner.replace(request("new-facts")) { FakeSession("new", mutableListOf()) }
        }
        assertTrue(newerGenerationPublished.await(5, TimeUnit.SECONDS))
        releaseOldFacts.countDown()

        old.join(5_000)
        newer.join(5_000)
        assertEquals(UsbOutputPhase.ACTIVE, owner.facts.phase)
        assertEquals("new-facts", owner.facts.request?.device?.descriptorFingerprint)
    }

    @Test
    fun staleHealthPausedAtPublicationBoundaryCannotOverwriteReplacement() {
        val oldHealthAtBoundary = CountDownLatch(1)
        val releaseOldHealth = CountDownLatch(1)
        val replacementGenerationPublished = CountDownLatch(1)
        val oldHealth = health(sampledAtMs = 100L, completedFrames = 1_000L)
        val owner = UsbOutputSessionOwner(
            onGenerationPublished = { generation ->
                if (generation == 2L) replacementGenerationPublished.countDown()
            },
            beforeFactsPublication = { facts ->
                if (facts.runtimeHealth == oldHealth) {
                    oldHealthAtBoundary.countDown()
                    assertTrue(releaseOldHealth.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val oldSession = owner.replace(request("old-health")) {
            FakeSession("old", mutableListOf())
        }

        val sampler = thread(name = "old-health-publication") {
            owner.withActiveSession(oldSession) { lease ->
                owner.publishRuntimeHealth(oldSession, lease, oldHealth)
            }
        }
        assertTrue(oldHealthAtBoundary.await(5, TimeUnit.SECONDS))

        val replacement = thread(name = "replacement-after-health") {
            owner.replace(request("replacement")) { FakeSession("new", mutableListOf()) }
        }
        assertTrue(replacementGenerationPublished.await(5, TimeUnit.SECONDS))
        releaseOldHealth.countDown()

        sampler.join(5_000)
        replacement.join(5_000)
        assertEquals(UsbOutputPhase.ACTIVE, owner.facts.phase)
        assertEquals("replacement", owner.facts.request?.device?.descriptorFingerprint)
        assertEquals(null, owner.facts.runtimeHealth)
    }

    @Test
    fun currentHealthPublishesInsideActiveSessionSeam() {
        val owner = UsbOutputSessionOwner()
        val session = owner.replace(request("health")) { FakeSession("health", mutableListOf()) }
        val snapshot = health(sampledAtMs = 200L, completedFrames = 2_000L)

        val published = owner.withActiveSession(session) { lease ->
            owner.publishRuntimeHealth(session, lease, snapshot)
        }

        assertEquals(true, published)
        assertEquals(snapshot, owner.facts.runtimeHealth)
    }

    @Test
    fun failedReplacementInvalidatesAndReleasesOldSessionWithoutNewSideEffects() {
        val effects = mutableListOf<String>()
        val owner = UsbOutputSessionOwner()
        owner.replace(request("old")) { FakeSession("old", effects) }
        val oldGeneration = owner.facts.generation

        val failure = runCatching {
            owner.replace(request("unsupported")) {
                error("format rejected before USB open")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(owner.facts.generation > oldGeneration)
        assertEquals(UsbOutputPhase.FAILED, owner.facts.phase)
        assertEquals("unsupported", owner.facts.request?.device?.descriptorFingerprint)
        assertEquals("open", owner.facts.failure?.stage)
        assertTrue(effects.contains("old-close"))
        assertFalse(effects.contains("unsupported-claim"))
    }

    @Test
    fun stalePermissionCallbackCannotOverwriteNewerGrantedRequest() {
        val owner = UsbOutputSessionOwner()
        val effects = mutableListOf<String>()
        val oldToken = owner.beginPermissionRequest(
            request("old-permission"),
            UsbAudioRuntimeHandle(10),
        )
        val newToken = owner.beginPermissionRequest(
            request("new-permission"),
            UsbAudioRuntimeHandle(11),
        )

        val oldSideEffect = owner.withTransport(oldToken) { lease ->
            lease.io { effects += "old-permission-dialog" }
        }
        val newSideEffect = owner.withTransport(newToken) { lease ->
            lease.io { effects += "new-permission-dialog" }
        }

        assertEquals(null, oldSideEffect)
        assertEquals(Unit, newSideEffect)
        assertFalse(
            owner.completePermissionRequest(
                newToken,
                UsbAudioRuntimeHandle(999),
                granted = false,
            ),
        )
        assertTrue(
            owner.completePermissionRequest(
                newToken,
                UsbAudioRuntimeHandle(11),
                granted = true,
            ),
        )
        assertFalse(
            owner.completePermissionRequest(
                oldToken,
                UsbAudioRuntimeHandle(10),
                granted = false,
            ),
        )
        assertEquals(listOf("new-permission-dialog"), effects)
        assertEquals(UsbPermissionState.GRANTED, owner.facts.permission)
        assertEquals(11, owner.facts.runtimeHandle?.runtimeDeviceId)
        assertEquals("new-permission", owner.facts.request?.device?.descriptorFingerprint)
    }

    @Test
    fun permissionRequestReleasesActiveSessionBeforePublishingRequestedFacts() {
        val effects = mutableListOf<String>()
        val owner = UsbOutputSessionOwner()
        owner.replace(request("active")) {
            FakeSession("active", effects, runtimeHandle = UsbAudioRuntimeHandle(20))
        }

        val token = owner.beginPermissionRequest(
            request("replacement"),
            UsbAudioRuntimeHandle(21),
        )

        assertTrue(effects.contains("active-close"))
        assertEquals(UsbOutputPhase.REQUESTED, owner.facts.phase)
        assertEquals(UsbPermissionState.REQUESTED, owner.facts.permission)
        assertEquals(21, owner.facts.runtimeHandle?.runtimeDeviceId)
        assertTrue(
            owner.completePermissionRequest(
                token,
                UsbAudioRuntimeHandle(21),
                granted = false,
            ),
        )
        assertEquals(UsbOutputPhase.FAILED, owner.facts.phase)
        assertEquals(UsbPermissionState.DENIED, owner.facts.permission)
        assertEquals("permission", owner.facts.failure?.stage)
    }

    @Test
    fun detachPublishedDuringActiveWritePreventsNativeSubmitAndReleasesSession() {
        val detachGenerationPublished = CountDownLatch(1)
        val owner = UsbOutputSessionOwner(
            onGenerationPublished = { generation ->
                if (generation == 2L) detachGenerationPublished.countDown()
            },
        )
        val effects = Collections.synchronizedList(mutableListOf<String>())
        val runtimeHandle = UsbAudioRuntimeHandle(30)
        val session = owner.replace(request("active")) {
            FakeSession("active", effects, runtimeHandle = runtimeHandle)
        }
        val writeAtBoundary = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)

        val writer = thread(name = "active-write-before-detach") {
            owner.withActiveSession(session) { lease ->
                writeAtBoundary.countDown()
                assertTrue(releaseWrite.await(5, TimeUnit.SECONDS))
                runCatching { lease.io { effects += "detached-native-submit" } }
            }
        }
        assertTrue(writeAtBoundary.await(5, TimeUnit.SECONDS))
        val detached = thread(name = "usb-device-detached") {
            assertEquals(
                UsbDeviceDetachDisposition.RELEASED_CURRENT,
                owner.deviceDetached(runtimeHandle),
            )
        }
        assertTrue(detachGenerationPublished.await(5, TimeUnit.SECONDS))
        releaseWrite.countDown()

        writer.join(5_000)
        detached.join(5_000)
        assertFalse(effects.contains("detached-native-submit"))
        assertTrue(effects.contains("active-close"))
        assertEquals(UsbOutputPhase.FAILED, owner.facts.phase)
        assertFalse(owner.facts.attached)
        assertEquals(null, owner.facts.runtimeHandle)
        assertEquals("detach", owner.facts.failure?.stage)
    }

    @Test
    fun detachForOldRuntimeHandleDoesNotInvalidateCurrentSession() {
        val effects = mutableListOf<String>()
        val owner = UsbOutputSessionOwner()
        owner.replace(request("current")) {
            FakeSession("current", effects, runtimeHandle = UsbAudioRuntimeHandle(41))
        }
        val generation = owner.facts.generation

        assertEquals(
            UsbDeviceDetachDisposition.STALE_RUNTIME,
            owner.deviceDetached(UsbAudioRuntimeHandle(40)),
        )
        assertEquals(generation, owner.facts.generation)
        assertEquals(UsbOutputPhase.ACTIVE, owner.facts.phase)
        assertFalse(effects.contains("current-close"))
    }

    @Test
    fun detachAfterCurrentTransportAlreadyClosedRemainsLifecycleRelevant() {
        val effects = mutableListOf<String>()
        val owner = UsbOutputSessionOwner()
        val runtimeHandle = UsbAudioRuntimeHandle(42)
        val session = owner.replace(request("current")) {
            FakeSession("current", effects, runtimeHandle = runtimeHandle)
        }
        owner.release(session)
        val generation = owner.facts.generation

        assertEquals(
            UsbDeviceDetachDisposition.ORPHANED_CURRENT,
            owner.deviceDetached(runtimeHandle),
        )
        assertEquals(generation, owner.facts.generation)
        assertEquals(UsbOutputPhase.IDLE, owner.facts.phase)
    }

    @Test
    fun detachWaitingBehindTransportCannotSupersedeNewerReplacementGeneration() {
        val effects = Collections.synchronizedList(mutableListOf<String>())
        val replacementGenerationPublished = CountDownLatch(1)
        val owner = UsbOutputSessionOwner(
            onGenerationPublished = { generation ->
                if (generation == 2L) replacementGenerationPublished.countDown()
            },
        )
        val oldRuntime = UsbAudioRuntimeHandle(50)
        val oldSession = owner.replace(request("old")) {
            FakeSession("old", effects, runtimeHandle = oldRuntime)
        }
        val transportHeld = CountDownLatch(1)
        val releaseTransport = CountDownLatch(1)
        val holder = thread(name = "hold-old-usb-transport") {
            owner.withActiveSession(oldSession) {
                transportHeld.countDown()
                assertTrue(releaseTransport.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(transportHeld.await(5, TimeUnit.SECONDS))

        val replacement = thread(name = "replacement-wins-generation") {
            owner.replace(request("replacement")) {
                FakeSession(
                    "replacement",
                    effects,
                    runtimeHandle = UsbAudioRuntimeHandle(51),
                )
            }
        }
        assertTrue(replacementGenerationPublished.await(5, TimeUnit.SECONDS))

        var detachDisposition: UsbDeviceDetachDisposition? = null
        val detach = thread(name = "late-old-runtime-detach") {
            detachDisposition = owner.deviceDetached(oldRuntime)
        }
        detach.join(5_000L)
        assertEquals(UsbDeviceDetachDisposition.STALE_RUNTIME, detachDisposition)
        releaseTransport.countDown()

        holder.join(5_000L)
        replacement.join(5_000L)

        assertEquals(UsbDeviceDetachDisposition.STALE_RUNTIME, detachDisposition)
        assertEquals(UsbOutputPhase.ACTIVE, owner.facts.phase)
        assertEquals(UsbAudioRuntimeHandle(51), owner.facts.runtimeHandle)
        assertFalse(effects.contains("replacement-close"))
    }

    @Test
    fun staleFallbackPausedAtFactsBoundaryCannotOverwriteReplacement() {
        val fallbackAtBoundary = CountDownLatch(1)
        val releaseFallback = CountDownLatch(1)
        val replacementGenerationPublished = CountDownLatch(1)
        val owner = UsbOutputSessionOwner(
            onGenerationPublished = { generation ->
                if (generation == 3L) replacementGenerationPublished.countDown()
            },
            beforeFactsPublication = { facts ->
                if (facts.failure?.fallbackToSharedPcm == true) {
                    fallbackAtBoundary.countDown()
                    assertTrue(releaseFallback.await(5, TimeUnit.SECONDS))
                }
            },
        )
        owner.replace(request("failed-usb")) { FakeSession("failed", mutableListOf()) }

        var fallbackPublished = true
        val fallback = thread(name = "stale-shared-pcm-fallback") {
            fallbackPublished = owner.publishFallbackToSharedPcm(
                request = request("failed-usb"),
                stage = "recovery-exhausted",
                message = "USB recovery exhausted",
            )
        }
        assertTrue(fallbackAtBoundary.await(5, TimeUnit.SECONDS))
        val replacement = thread(name = "replacement-after-fallback") {
            owner.replace(request("replacement")) { FakeSession("replacement", mutableListOf()) }
        }
        assertTrue(replacementGenerationPublished.await(5, TimeUnit.SECONDS))
        releaseFallback.countDown()

        fallback.join(5_000L)
        replacement.join(5_000L)
        assertFalse(fallbackPublished)
        assertEquals(UsbOutputPhase.ACTIVE, owner.facts.phase)
        assertEquals("replacement", owner.facts.request?.device?.descriptorFingerprint)
        assertEquals(null, owner.facts.failure)
    }

    @Test
    fun sharedPcmFallbackPublishesExplicitNonExactFailureFacts() {
        val effects = mutableListOf<String>()
        val owner = UsbOutputSessionOwner()
        owner.replace(request("failed-usb")) { FakeSession("failed", effects) }

        assertTrue(
            owner.publishFallbackToSharedPcm(
                request = request("failed-usb"),
                stage = "recovery-exhausted",
                message = "USB recovery exhausted after 3 attempts",
            ),
        )

        assertTrue(effects.contains("failed-close"))
        assertEquals(UsbOutputPhase.FAILED, owner.facts.phase)
        assertEquals("failed-usb", owner.facts.request?.device?.descriptorFingerprint)
        assertFalse(owner.facts.exclusive)
        assertFalse(owner.facts.signalExact)
        assertEquals(true, owner.facts.failure?.fallbackToSharedPcm)
        assertEquals("recovery-exhausted", owner.facts.failure?.stage)
    }

    private fun request(name: String) = UsbOutputRequest(
        device = UsbAudioDeviceIdentity(
            vendorId = 0x262a,
            productId = 0x0001,
            descriptorFingerprint = name,
        ),
    )

    private fun health(sampledAtMs: Long, completedFrames: Long) = UsbRuntimeHealth(
        sampledAtElapsedRealtimeMs = sampledAtMs,
        completedFrames = completedFrames,
        bufferedFrames = 480L,
        underrunBytes = 0L,
        transportErrorCode = 0,
        playbackRequested = true,
        sourceConsumptionActive = true,
        bufferCapacityFrames = 96_000L,
        minimumBufferedFrames = 9_600L,
        acceptedPcmBytes = 16_384L,
        previousSuccessfulWriteGapUs = 20_000L,
        maximumSuccessfulWriteGapUs = 150_729L,
        previousDataCompletionGapUs = 1_010L,
        maximumDataCompletionGapUs = 42_000L,
        previousFeedbackCompletionGapUs = 4_020L,
        maximumFeedbackCompletionGapUs = 44_000L,
        totalPollTimeouts = 2L,
        maximumConsecutivePollTimeouts = 1L,
        invalidFeedbackPacketCount = 3L,
        dataPacketErrorCount = 4L,
        currentFeedbackQ16 = 393_216L,
        minimumFeedbackQ16 = 393_200L,
        maximumFeedbackQ16 = 393_232L,
        maximumFeedbackStepQ16 = 16L,
        trustedFeedbackQ16 = 393_216L,
        feedbackFilterInterventionCount = 2L,
    )

    private class FakeSession(
        private val name: String,
        private val effects: MutableList<String>,
        private val onRelease: (UsbOutputCleanupLease) -> Unit = {},
        private val runtimeHandle: UsbAudioRuntimeHandle? = null,
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

        override fun restart(lease: UsbOutputRequestLease) {
            lease.io { effects += "$name-restart" }
        }

        override fun release(lease: UsbOutputCleanupLease, reason: String) {
            onRelease(lease)
            lease.io { effects += "$name-close" }
        }
    }
}
