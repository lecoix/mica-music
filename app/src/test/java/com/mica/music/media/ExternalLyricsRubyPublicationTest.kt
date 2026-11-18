package com.mica.music.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.FlagSet
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.*
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.testutil.SongFixtures
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExternalLyricsRubyPublicationTest {
    @Test fun oldNonCancellableLoadCannotOverwriteNewStatusBarRuby() = runTest { exercise(releaseBeforeCompletion = false) }
    @Test fun releasedCoordinatorCannotRepublishLateDesktopRuby() = runTest { exercise(releaseBeforeCompletion = true) }

    private suspend fun TestScope.exercise(releaseBeforeCompletion: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previousMode = LyricsPreferences.externalLyricsMode(context)
        val previousReading = LyricsPreferences.lyricReadingEnabled(context)
        SharedLyricsMemoryCache.clear()
        LyricsPreferences.setExternalLyricsMode(context, if (releaseBeforeCompletion) ExternalLyricsMode.DESKTOP else ExternalLyricsMode.STATUS_BAR)
        LyricsPreferences.setLyricReadingEnabled(context, true)
        fun song(id: String, reading: String) = SongFixtures.song(id).copy(lyricsDocument = LyricsDocument(lines = listOf(
            LyricLineNode("line", 1000, 4000, listOf(
                LyricTextPart(LyricTextRole.ORIGINAL, "風", listOf(LyricRuby(0, 1, reading))),
                LyricTextPart(LyricTextRole.READING, reading),
            )),
        )))
        val old = song("ruby-old", "old")
        val fresh = song("ruby-new", "new")
        var item = SongMediaItemCodec.encode(old)
        val listener = slot<Player.Listener>()
        val player = mockk<Player>(relaxed = true)
        every { player.applicationLooper } returns Looper.getMainLooper()
        every { player.currentMediaItem } answers { item }
        every { player.currentPosition } returns 1500L
        every { player.duration } returns 10000L
        every { player.isPlaying } returns true
        every { player.playbackParameters } returns PlaybackParameters.DEFAULT
        every { player.addListener(capture(listener)) } just Runs
        val store = DesktopLyricsOverlayStateStore()
        val began = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val coordinator = NotificationLyricsCoordinator(context, player, Handler(Looper.getMainLooper()),
            desktopLyrics = store, lyricsDispatcher = StandardTestDispatcher(testScheduler), songLoader = { spec ->
                if (spec.songId == old.id) withContext(NonCancellable) { began.complete(Unit); unblock.await(); old } else fresh
            })
        fun drain() { repeat(3) { testScheduler.runCurrent(); shadowOf(Looper.getMainLooper()).idle() } }
        try {
            coordinator.start()
            drain()
            assertTrue(began.isCompleted)
            if (releaseBeforeCompletion) {
                // Finish IO and queue its real Handler callback before release. Cancelling the
                // coroutine can no longer protect publication; the owner must reject that callback.
                unblock.complete(Unit)
                testScheduler.runCurrent()
                assertNull(store.state.value.desktop.line)
                coordinator.release()
            } else {
                item = SongMediaItemCodec.encode(fresh)
                listener.captured.onEvents(player, Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_ITEM_TRANSITION).build()))
                drain()
                assertEquals("new", store.state.value.statusBar.line!!.original!!.ruby.single().reading)
                assertEquals("new", store.state.value.statusBar.line!!.reading!!.text)
            }
            unblock.complete(Unit)
            drain()
            if (releaseBeforeCompletion) {
                assertNull(store.state.value.desktop.line)
                assertNull(store.state.value.statusBar.line)
            } else {
                assertEquals("new", store.state.value.statusBar.line!!.original!!.ruby.single().reading)
            }
        } finally {
            unblock.complete(Unit)
            coordinator.release()
            LyricsPreferences.setExternalLyricsMode(context, previousMode)
            LyricsPreferences.setLyricReadingEnabled(context, previousReading)
            SharedLyricsMemoryCache.clear()
        }
    }
}
