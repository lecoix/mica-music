package com.mica.music.data.remote

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteArtworkByteCacheTest {
    @Test
    fun `same revisioned artwork key loads once`() = runBlocking {
        val cache = RemoteArtworkByteCache(maxBytes = 1024)
        val key = key()
        var loads = 0

        val first = cache.getOrLoad(key) {
            loads += 1
            byteArrayOf(1, 2, 3)
        }
        val second = cache.getOrLoad(key) {
            loads += 1
            byteArrayOf(9)
        }

        assertArrayEquals(first, second)
        assertEquals(1, loads)
    }

    @Test
    fun `concurrent opens coalesce onto one loader`() = runBlocking {
        val cache = RemoteArtworkByteCache(maxBytes = 1024)
        val key = key()
        val loads = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            cache.getOrLoad(key) {
                loads.incrementAndGet()
                entered.complete(Unit)
                release.await()
                byteArrayOf(4, 5, 6)
            }
        }
        entered.await()
        val second = async {
            cache.getOrLoad(key) {
                loads.incrementAndGet()
                byteArrayOf(7)
            }
        }
        release.complete(Unit)

        assertArrayEquals(first.await(), second.await())
        assertEquals(1, loads.get())
    }

    @Test
    fun `credential config or catalog revision change cannot reuse stale bytes`() = runBlocking {
        val cache = RemoteArtworkByteCache(maxBytes = 1024)
        val loads = AtomicInteger(0)
        val original = key()
        val changedCredential = original.copy(credentialRevision = original.credentialRevision + 1)
        val changedConfig = original.copy(sourceConfigRevision = original.sourceConfigRevision + 1)
        val changedCatalog = original.copy(catalogRevision = original.catalogRevision + 1)

        cache.getOrLoad(original) { byteArrayOf(loads.incrementAndGet().toByte()) }
        cache.getOrLoad(changedCredential) { byteArrayOf(loads.incrementAndGet().toByte()) }
        cache.getOrLoad(changedConfig) { byteArrayOf(loads.incrementAndGet().toByte()) }
        cache.getOrLoad(changedCatalog) { byteArrayOf(loads.incrementAndGet().toByte()) }

        assertEquals(4, loads.get())
    }

    @Test
    fun `least recently used bytes are evicted within budget`() = runBlocking {
        val cache = RemoteArtworkByteCache(maxBytes = 4)
        val loads = AtomicInteger(0)
        val firstKey = key().copy(opaqueArtworkId = "cover-a")
        val secondKey = key().copy(opaqueArtworkId = "cover-b")

        cache.getOrLoad(firstKey) {
            loads.incrementAndGet()
            byteArrayOf(1, 1, 1)
        }
        cache.getOrLoad(secondKey) {
            loads.incrementAndGet()
            byteArrayOf(2, 2, 2)
        }
        cache.getOrLoad(secondKey) {
            loads.incrementAndGet()
            byteArrayOf(8)
        }
        cache.getOrLoad(firstKey) {
            loads.incrementAndGet()
            byteArrayOf(3, 3, 3)
        }

        assertEquals(3, loads.get())
    }

    @Test
    fun `failed load is not cached and can be retried`() = runBlocking {
        val cache = RemoteArtworkByteCache(maxBytes = 1024)
        val key = key()
        var loads = 0

        val firstFailure = runCatching {
            cache.getOrLoad(key) {
                loads += 1
                error("boom")
            }
        }
        val recovered = cache.getOrLoad(key) {
            loads += 1
            byteArrayOf(6)
        }

        assertTrue(firstFailure.isFailure)
        assertArrayEquals(byteArrayOf(6), recovered)
        assertEquals(2, loads)
    }

    @Test
    fun `disk cache survives new artwork cache instance and keeps revision fencing`() = runBlocking {
        val directory = Files.createTempDirectory("mica-remote-artwork").toFile()
        try {
            val loads = AtomicInteger(0)
            val first = RemoteArtworkByteCache(
                maxBytes = 1024,
                diskCache = RevisionedDiskCache(directory, maxBytes = 4096, maxEntries = 8),
            )
            val original = key()
            assertArrayEquals(
                byteArrayOf(1, 2, 3),
                first.getOrLoad(original) {
                    loads.incrementAndGet()
                    byteArrayOf(1, 2, 3)
                },
            )

            val reopened = RemoteArtworkByteCache(
                maxBytes = 1024,
                diskCache = RevisionedDiskCache(directory, maxBytes = 4096, maxEntries = 8),
            )
            assertArrayEquals(
                byteArrayOf(1, 2, 3),
                reopened.getOrLoad(original) {
                    loads.incrementAndGet()
                    byteArrayOf(9)
                },
            )
            assertEquals(1, loads.get())

            reopened.getOrLoad(original.copy(opaqueArtworkId = "cover-new-revision")) {
                loads.incrementAndGet()
                byteArrayOf(4)
            }
            assertEquals(2, loads.get())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun key() = RemoteArtworkCacheKey(
        sourceInstanceId = "nav-1",
        sourceConfigRevision = 4,
        catalogRevision = 9,
        credentialRevision = 7,
        opaqueArtworkId = "cover-42",
    )
}
