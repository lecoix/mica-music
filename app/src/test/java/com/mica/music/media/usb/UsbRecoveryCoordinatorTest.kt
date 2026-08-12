package com.mica.music.media.usb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbRecoveryCoordinatorTest {
    @Test
    fun freshOpenRequiresAckAndIsBoundedToThreeAttemptsPerEpoch() {
        var nowMs = 10_000L
        val coordinator = UsbRecoveryCoordinator(
            maxFreshOpenAttemptsPerEpoch = 3,
            elapsedRealtimeMs = { nowMs },
        )
        val epoch = coordinator.beginEpoch(sessionGeneration = 7L)

        val first = coordinator.requestFreshOpen(epoch, UsbRecoveryTrigger.STALLED_PROGRESS) as
            UsbRecoveryRequestResult.Issued
        assertEquals(1, first.action.attempt)
        assertTrue(
            coordinator.requestFreshOpen(epoch, UsbRecoveryTrigger.STALLED_PROGRESS) is
                UsbRecoveryRequestResult.AwaitingAck,
        )
        assertTrue(coordinator.acknowledge(first.action, UsbRecoveryAckOutcome.FAILED))
        assertEquals(
            UsbRecoveryRequestResult.BackingOff(1_000L),
            coordinator.requestFreshOpen(epoch, UsbRecoveryTrigger.STALLED_PROGRESS),
        )
        nowMs += 1_000L

        val second = coordinator.requestFreshOpen(epoch, UsbRecoveryTrigger.STALLED_PROGRESS) as
            UsbRecoveryRequestResult.Issued
        assertEquals(2, second.action.attempt)
        assertTrue(coordinator.acknowledge(second.action, UsbRecoveryAckOutcome.FAILED))
        assertEquals(
            UsbRecoveryRequestResult.BackingOff(2_000L),
            coordinator.requestFreshOpen(epoch, UsbRecoveryTrigger.STALLED_PROGRESS),
        )
        nowMs += 2_000L
        val third = coordinator.requestFreshOpen(epoch, UsbRecoveryTrigger.STALLED_PROGRESS) as
            UsbRecoveryRequestResult.Issued
        assertEquals(3, third.action.attempt)
        assertTrue(coordinator.acknowledge(third.action, UsbRecoveryAckOutcome.FAILED))

        assertTrue(
            coordinator.requestFreshOpen(epoch, UsbRecoveryTrigger.STALLED_PROGRESS) is
                UsbRecoveryRequestResult.BudgetExhausted,
        )
        assertEquals(3, coordinator.snapshot().issuedFreshOpenAttempts)
    }

    @Test
    fun successfulAckResolvesEpochAndPreventsFurtherRecovery() {
        val coordinator = UsbRecoveryCoordinator()
        val epoch = coordinator.beginEpoch(sessionGeneration = 8L)
        val issued = coordinator.requestFreshOpen(epoch, UsbRecoveryTrigger.TRANSPORT_ERROR) as
            UsbRecoveryRequestResult.Issued

        assertTrue(coordinator.acknowledge(issued.action, UsbRecoveryAckOutcome.SUCCEEDED))

        assertTrue(
            coordinator.requestFreshOpen(epoch, UsbRecoveryTrigger.STALLED_PROGRESS) is
                UsbRecoveryRequestResult.Resolved,
        )
        assertTrue(coordinator.snapshot().resolved)
    }

    @Test
    fun staleAckFromPreviousEpochCannotMutateCurrentEpoch() {
        val oldAckAtBoundary = CountDownLatch(1)
        val releaseOldAck = CountDownLatch(1)
        val coordinator = UsbRecoveryCoordinator(
            beforeAckPublication = { action ->
                if (action.actionId == 1L) {
                    oldAckAtBoundary.countDown()
                    assertTrue(releaseOldAck.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val oldEpoch = coordinator.beginEpoch(sessionGeneration = 10L)
        val oldAction = (coordinator.requestFreshOpen(oldEpoch, UsbRecoveryTrigger.TRANSPORT_ERROR) as
            UsbRecoveryRequestResult.Issued).action

        var oldAckAccepted = true
        val oldAck = thread(name = "stale-recovery-ack") {
            oldAckAccepted = coordinator.acknowledge(
                oldAction,
                UsbRecoveryAckOutcome.SUCCEEDED,
            )
        }
        assertTrue(oldAckAtBoundary.await(5, TimeUnit.SECONDS))
        val currentEpoch = coordinator.beginEpoch(sessionGeneration = 11L)
        val currentAction = (coordinator.requestFreshOpen(
            currentEpoch,
            UsbRecoveryTrigger.TRANSPORT_ERROR,
        ) as
            UsbRecoveryRequestResult.Issued).action
        val before = coordinator.snapshot()
        releaseOldAck.countDown()
        oldAck.join(5_000L)

        assertFalse(oldAckAccepted)
        assertEquals(before, coordinator.snapshot())
        assertEquals(currentAction, coordinator.snapshot().pendingAction)
        assertTrue(coordinator.acknowledge(currentAction, UsbRecoveryAckOutcome.FAILED))
    }

    @Test
    fun staleEpochCannotIssueActionAfterNewSessionWins() {
        val coordinator = UsbRecoveryCoordinator()
        val oldEpoch = coordinator.beginEpoch(sessionGeneration = 20L)
        val currentEpoch = coordinator.beginEpoch(sessionGeneration = 21L)

        assertTrue(
            coordinator.requestFreshOpen(oldEpoch, UsbRecoveryTrigger.STALLED_PROGRESS) is
                UsbRecoveryRequestResult.StaleEpoch,
        )
        val current = coordinator.requestFreshOpen(
            currentEpoch,
            UsbRecoveryTrigger.STALLED_PROGRESS,
        ) as
            UsbRecoveryRequestResult.Issued
        assertEquals(21L, current.action.epoch.sessionGeneration)
        assertEquals(1, current.action.attempt)
    }
}
