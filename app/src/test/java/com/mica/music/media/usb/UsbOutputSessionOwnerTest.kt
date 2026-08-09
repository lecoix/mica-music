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

    private fun request(name: String) = UsbOutputRequest(
        device = UsbAudioDeviceIdentity(
            vendorId = 0x262a,
            productId = 0x0001,
            descriptorFingerprint = name,
        ),
    )

    private class FakeSession(
        private val name: String,
        private val effects: MutableList<String>,
        private val onRelease: (UsbOutputCleanupLease) -> Unit = {},
    ) : UsbOutputSession {
        override val activeFacts: PlaybackOutputFacts
            get() = PlaybackOutputFacts(
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
