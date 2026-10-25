package com.mica.music.ui.screens

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListItemInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LyricsOwnedMotionInterleavingTest {
    private fun layout(count: Int, y: Int): LazyListLayoutInfo {
        val row = mockk<LazyListItemInfo>()
        every { row.key } returns "row"
        every { row.offset } returns y
        every { row.size } returns 40
        val info = mockk<LazyListLayoutInfo>()
        every { info.totalItemsCount } returns count
        every { info.visibleItemsInfo } returns listOf(row)
        return info
    }

    @Test fun obsoleteFollowFrameAndCleanupCannotReplaceNewCollapseMotion() {
        val clock = HeldFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + clock)
        val owner = OwnedLyricsPositionMotion(scope)
        try {
            owner.acceptLayout(layout(2, 100), false, 170f, false)
            val old = owner.beginFollow()
            scope.launch {
                withFrameNanos { }
                owner.recordFollowFrame(old, 800_000_000L, emptyMap())
                owner.clearFollowFrame(old)
            }
            val current = owner.beginFollow()
            owner.recordFollowFrame(current, 200_000_000L, emptyMap())
            clock.deliver(0L) // Obsolete publication and finally both arrive after the new follow.
            owner.acceptLayout(layout(1, 184), true, 170f, true)
            owner.recordFollowFrame(current, 216_000_000L, emptyMap())
            val translation = owner.translationFor("row")
            assertTrue("New follow must drive actual collapse translation: $translation", translation in -80f..-65f)
            owner.pin("row", 7f)
            owner.recordFollowFrame(current, 232_000_000L, emptyMap())
            assertEquals("A new pin retires the follow compensation", 7f, owner.translationFor("row"), 0f)
        } finally {
            owner.dispose()
            scope.cancel()
        }
    }

    /** Deliberately delivers a frame even after cancellation, like a queued external callback. */
    private class HeldFrameClock : MonotonicFrameClock {
        private val pending = ArrayDeque<Continuation<Long>>()
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
            onFrame(suspendCoroutine { pending.addLast(it) })

        fun deliver(time: Long) = pending.removeFirst().resume(time)
    }

    @Test fun obsoleteSpringCannotOverwriteNewInterludePinAfterHeldFrameReturns() {
        val clock = HeldFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + clock)
        val owner = OwnedLyricsPositionMotion(scope)
        try {
            owner.accept(listOf(OwnedLyricGeometry("row", 100, 40)), false, 170f, false)
            owner.accept(listOf(OwnedLyricGeometry("row", 60, 40)), true, 170f, false)
            clock.deliver(0L) // Old spring is now stopped immediately before its first write.
            owner.pin("row", 7f)
            clock.deliver(16_000_000L)
            assertEquals("The new pin owns the displayed translation", 7f, owner.translationFor("row"), 0f)
        } finally {
            owner.dispose()
            scope.cancel()
        }
    }
}
