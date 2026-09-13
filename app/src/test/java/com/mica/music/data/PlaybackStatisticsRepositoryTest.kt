package com.mica.music.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackStatisticsRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    @Test
    fun persistsPlayWithoutPresentationSink() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = PlaybackStatisticsRepository(
            context = context,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )

        repo.recordPlay("song-a")
        advanceUntilIdle()

        assertEquals(1, PlayHistoryStore.getStats(context, "song-a").count)
    }

    @Test
    fun persistsListenSecondsAfterPresentationSinkDetached() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = PlaybackStatisticsRepository(
            context = context,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
        val token = Any()
        val presented = AtomicInteger(0)
        repo.attachPresentationSink(token) { _, _ -> presented.incrementAndGet() }
        repo.detachPresentationSink(token)

        repo.recordPlay("song-b")
        repo.recordListenSeconds("song-b", 40L)
        advanceUntilIdle()

        val stats = PlayHistoryStore.getStats(context, "song-b")
        assertEquals(1, stats.count)
        assertEquals(40L, stats.totalListenSeconds)
        assertEquals(0, presented.get())
    }

    @Test
    fun presentationSinkReceivesStatsOnMainDispatcher() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = PlaybackStatisticsRepository(
            context = context,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
        val token = Any()
        val presented = CopyOnWriteArrayList<Pair<String, PlayStats>>()
        repo.attachPresentationSink(token) { songId, stats ->
            presented += songId to stats
        }

        repo.recordPlay("song-c")
        advanceUntilIdle()

        assertEquals(1, presented.size)
        assertEquals("song-c", presented[0].first)
        assertEquals(1, presented[0].second.count)
    }

    @Test
    fun playbackEventSinkPersistsPlayCount() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = PlaybackStatisticsRepository(
            context = context,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )

        repo.playStartedSink("song-d")
        advanceUntilIdle()

        assertEquals(1, PlayHistoryStore.getStats(context, "song-d").count)
    }

    @Test
    fun concurrentPlaybackEventsAreSerializedWithoutLostUpdates() {
        val writerExecutor = Executors.newFixedThreadPool(4)
        val writerDispatcher = writerExecutor.asCoroutineDispatcher()
        val producers = Executors.newFixedThreadPool(8)
        val playEvents = 200
        val listenEvents = 200
        val presented = CountDownLatch(playEvents + listenEvents)
        val repo = PlaybackStatisticsRepository(
            context = context,
            ioDispatcher = writerDispatcher,
            mainDispatcher = writerDispatcher,
        )
        val token = Any()
        repo.attachPresentationSink(token) { songId, _ ->
            if (songId == "race-song") presented.countDown()
        }

        try {
            repeat(playEvents) { producers.execute { repo.recordPlay("race-song") } }
            repeat(listenEvents) { producers.execute { repo.recordListenSeconds("race-song", 1L) } }
            producers.shutdown()
            assertTrue(producers.awaitTermination(10, TimeUnit.SECONDS))
            assertTrue(presented.await(20, TimeUnit.SECONDS))

            val stats = PlayHistoryStore.getStats(context, "race-song")
            assertEquals(playEvents, stats.count)
            assertEquals(listenEvents.toLong(), stats.totalListenSeconds)
        } finally {
            producers.shutdownNow()
            writerDispatcher.close()
        }
    }

    @Test
    fun transientSongsDoNotPersistPlaybackStatistics() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = PlaybackStatisticsRepository(
            context = context,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            isPersistentSong = { false },
        )

        repo.recordPlay("external")
        repo.recordListenSeconds("external", 30L)
        advanceUntilIdle()

        assertEquals(0, PlayHistoryStore.getStats(context, "external").count)
        assertEquals(0L, PlayHistoryStore.getStats(context, "external").totalListenSeconds)
    }

    private fun clearPrefs() {
        context.getSharedPreferences("mica_play_counts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
