package com.mica.music.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricsDocumentMemoryCacheTest {

    @Test
    fun rubyPayloadCountsTowardTheCacheBudgetAcrossTenThousandSongs() {
        val document = document("字".repeat(64)).let { doc ->
            doc.copy(lines = doc.lines.map { line -> line.copy(parts = line.parts.map { part ->
                part.copy(ruby = (0 until 64).map { LyricRuby(it, it + 1, "reading") })
            }) })
        }
        val cache = LyricsDocumentMemoryCache(maxBytes = 4_000)
        repeat(10_000) { index -> cache.put(LyricsCacheKey("song-$index", "1", 14), document) }
        assertEquals(0, cache.entryCount())
        assertTrue(cache.sizeBytes() <= 4_000)
    }

    @Test
    fun oldRubyLoadCannotReplaceANewerPublishedReading() = runTest {
        fun withRuby(reading: String) = document("字").let { doc ->
            doc.copy(lines = doc.lines.map { line -> line.copy(parts = listOf(
                LyricTextPart(LyricTextRole.READING, reading),
                LyricTextPart(LyricTextRole.ORIGINAL, "字", listOf(LyricRuby(0, 1, reading))),
            )) })
        }
        val coordinator = LyricsCacheCoordinator()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = withRuby("old")
        val fresh = withRuby("new")
        val loading = async {
            coordinator.load("song", "revision", 14) { started.complete(Unit); release.await(); old }
        }
        started.await()
        coordinator.invalidateSongs(listOf("song"))
        assertEquals(fresh, coordinator.load("song", "revision", 14) { fresh })
        release.complete(Unit)
        assertEquals(fresh, loading.await())
        assertEquals(fresh, coordinator.get("song", "revision", 14))
    }

    @Test
    fun evictsLeastRecentlyUsedDocumentWhenByteBudgetIsExceeded() {
        val document = LyricsDocument(
            lines = listOf(
                LyricLineNode(
                    id = "line",
                    startMs = 0,
                    parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "x".repeat(200))),
                ),
            ),
        )
        val cache = LyricsDocumentMemoryCache(maxBytes = 1_000)
        val first = LyricsCacheKey("first", "1", 1)
        val second = LyricsCacheKey("second", "1", 1)

        cache.put(first, document)
        cache.put(second, document)

        assertNull(cache.get(first))
        assertEquals(document, cache.get(second))
        assertEquals(1, cache.entryCount())
    }

    @Test
    fun invalidationDuringLoadCannotRestoreTheStaleDocument() = runTest {
        val coordinator = LyricsCacheCoordinator(maxBytes = 1_000)
        val stale = document("stale")
        val fresh = document("fresh")
        val firstLoadStarted = CompletableDeferred<Unit>()
        val releaseFirstLoad = CompletableDeferred<Unit>()
        var loadCount = 0

        val result = async {
            coordinator.load("song", "revision", 1) {
                loadCount++
                if (loadCount == 1) {
                    firstLoadStarted.complete(Unit)
                    releaseFirstLoad.await()
                    stale
                } else {
                    fresh
                }
            }
        }
        firstLoadStarted.await()

        coordinator.invalidateSongs(listOf("song"))
        releaseFirstLoad.complete(Unit)

        assertEquals(fresh, result.await())
        assertEquals(2, loadCount)
        assertEquals(fresh, coordinator.get("song", "revision", 1))
    }

    @Test
    fun concurrentLoadsForTheSameKeyShareOneLoader() = runTest {
        val coordinator = LyricsCacheCoordinator(maxBytes = 1_000)
        val document = document("shared")
        val releaseLoad = CompletableDeferred<Unit>()
        var loadCount = 0
        val loader: suspend () -> LyricsDocument = {
            loadCount++
            releaseLoad.await()
            document
        }

        val first = async { coordinator.load("song", "revision", 1, loader = loader) }
        val second = async { coordinator.load("song", "revision", 1, loader = loader) }
        testScheduler.runCurrent()
        releaseLoad.complete(Unit)

        assertEquals(document, first.await())
        assertEquals(document, second.await())
        assertEquals(1, loadCount)
    }

    @Test
    fun cancelledLeaderLetsAnActiveFollowerRetryWithItsOwnLoader() = runTest {
        val coordinator = LyricsCacheCoordinator(maxBytes = 1_000)
        val leaderStarted = CompletableDeferred<Unit>()
        val keepLeaderLoading = CompletableDeferred<Unit>()
        val expected = document("follower")
        var followerLoadCount = 0

        val leader = async {
            coordinator.load("song", "revision", 1) {
                leaderStarted.complete(Unit)
                keepLeaderLoading.await()
                document("leader")
            }
        }
        leaderStarted.await()
        val follower = async {
            coordinator.load("song", "revision", 1) {
                followerLoadCount++
                expected
            }
        }
        testScheduler.runCurrent()
        assertEquals(0, followerLoadCount)

        leader.cancel()
        testScheduler.runCurrent()

        assertEquals(expected, follower.await())
        assertEquals(1, followerLoadCount)
    }

    @Test
    fun differentKeysRunAtMostTwoLoadersAtOnce() = runTest {
        val coordinator = LyricsCacheCoordinator(maxBytes = 1_000)
        val releaseLoads = CompletableDeferred<Unit>()
        var activeLoads = 0
        var maxActiveLoads = 0
        val jobs = List(3) { index ->
            async {
                coordinator.load("song-$index", "revision", 1) {
                    activeLoads++
                    maxActiveLoads = maxOf(maxActiveLoads, activeLoads)
                    try {
                        releaseLoads.await()
                        document("song-$index")
                    } finally {
                        activeLoads--
                    }
                }
            }
        }

        testScheduler.runCurrent()
        val observedMax = maxActiveLoads
        releaseLoads.complete(Unit)
        jobs.forEach { it.await() }

        assertEquals(2, observedMax)
    }

    @Test
    fun prefetchLeavesOneLoadSlotAvailableForForegroundLyrics() = runTest {
        val coordinator = LyricsCacheCoordinator(maxBytes = 1_000)
        val releasePrefetch = CompletableDeferred<Unit>()
        val foregroundStarted = CompletableDeferred<Unit>()
        var startedPrefetches = 0
        val prefetches = List(2) { index ->
            async {
                coordinator.load("prefetch-$index", "revision", 1, isPrefetch = true) {
                    startedPrefetches++
                    releasePrefetch.await()
                    document("prefetch-$index")
                }
            }
        }
        testScheduler.runCurrent()

        val foreground = async {
            coordinator.load("foreground", "revision", 1) {
                foregroundStarted.complete(Unit)
                document("foreground")
            }
        }
        testScheduler.runCurrent()

        assertEquals(1, startedPrefetches)
        assertTrue(foregroundStarted.isCompleted)
        releasePrefetch.complete(Unit)
        prefetches.forEach { it.await() }
        foreground.await()
    }

    private fun document(text: String) = LyricsDocument(
        lines = listOf(
            LyricLineNode(
                id = text,
                startMs = 0,
                parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, text)),
            ),
        ),
    )
}
