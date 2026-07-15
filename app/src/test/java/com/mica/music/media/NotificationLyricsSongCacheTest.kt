package com.mica.music.media

import android.os.Handler
import android.os.Looper
import com.mica.music.data.LyricsDocument
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationLyricsSongCacheTest {
    @Before
    fun clearSharedCache() {
        SharedLyricsMemoryCache.clear()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loadsCurrentSongLyricsOnDemandAndCachesThem() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val song = SongFixtures.song("lyric-song")
        var loadCount = 0
        var callbacks = 0
        val cache = NotificationLyricsSongCache(
            scope = scope,
            handler = Handler(Looper.getMainLooper()),
            loadSong = { id ->
                loadCount += 1
                song.takeIf { it.id == id }
            },
        )
        val decoded = song.copy(lyricsDocument = LyricsDocument())

        val first = cache.songWithLyrics(decoded, "revision-1") { callbacks += 1 }
        scope.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        val second = cache.songWithLyrics(decoded, "revision-1") { callbacks += 1 }

        assertEquals(LyricsDocument(), first.lyricsDocument)
        assertEquals(song.lyricsDocument, second.lyricsDocument)
        assertEquals(1, loadCount)
        assertEquals(1, callbacks)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun reloadsLyricsWhenRevisionChangesForSameSong() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        var stored = SongFixtures.song("lyric-song")
        var loadCount = 0
        val cache = NotificationLyricsSongCache(
            scope = scope,
            handler = Handler(Looper.getMainLooper()),
            loadSong = {
                loadCount += 1
                stored
            },
        )
        val decoded = stored.copy(lyricsDocument = LyricsDocument())

        cache.songWithLyrics(decoded, "revision-1") {}
        scope.advanceUntilIdle()
        stored = stored.copy(lyricsDocument = LyricsDocument())
        cache.songWithLyrics(decoded, "revision-2") {}
        scope.advanceUntilIdle()

        assertEquals(2, loadCount)
    }
}
