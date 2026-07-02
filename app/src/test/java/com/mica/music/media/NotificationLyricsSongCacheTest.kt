package com.mica.music.media

import android.os.Handler
import android.os.Looper
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationLyricsSongCacheTest {
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
        val decoded = song.copy(lyrics = emptyList())

        val first = cache.songWithLyrics(decoded) { callbacks += 1 }
        scope.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        val second = cache.songWithLyrics(decoded) { callbacks += 1 }

        assertEquals(emptyList<com.mica.music.data.LyricLine>(), first.lyrics)
        assertEquals(song.lyrics, second.lyrics)
        assertEquals(1, loadCount)
        assertEquals(1, callbacks)
    }
}
