package com.mica.music.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayHistoryStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mica_play_counts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("mica_play_counts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun listenSecondsAccumulateWithoutChangingPlayCount() {
        PlayHistoryStore.recordPlay(context, "song-1")
        val first = PlayHistoryStore.recordListenSeconds(context, "song-1", 65)
        val second = PlayHistoryStore.recordListenSeconds(context, "song-1", 125)

        assertEquals(1, first.count)
        assertEquals(65L, first.totalListenSeconds)
        assertEquals(1, second.count)
        assertEquals(190L, second.totalListenSeconds)
        assertEquals(first.lastPlayedAtMs, second.lastPlayedAtMs)
    }
}
