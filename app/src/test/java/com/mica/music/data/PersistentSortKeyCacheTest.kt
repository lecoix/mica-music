package com.mica.music.data

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentSortKeyCacheTest {

    @Test
    fun persistsAndReloadsCompleteSortKeys() {
        val file = testFile("reload")
        val writer = PersistentSortKeyCache(algorithmVersion = 3, maxEntries = 8)
        writer.configure(file)
        writer["月 feat. ヰ世界情緒"] = "YUE FEAT. ISEKAIJOUCHO"
        writer["病名は愛だった"] = "BING MING HA AI DATTA"
        writer.persist()

        val reader = PersistentSortKeyCache(algorithmVersion = 3, maxEntries = 8)
        reader.configure(file)

        assertEquals("YUE FEAT. ISEKAIJOUCHO", reader["月 feat. ヰ世界情緒"])
        assertEquals("BING MING HA AI DATTA", reader["病名は愛だった"])
        assertEquals(2, reader.inMemorySizeForTest())
    }

    @Test
    fun ignoresEntriesFromOlderAlgorithmVersion() {
        val file = testFile("version")
        PersistentSortKeyCache(algorithmVersion = 1, maxEntries = 8).also { cache ->
            cache.configure(file)
            cache["旧键"] = "OLD"
            cache.persist()
        }

        val reader = PersistentSortKeyCache(algorithmVersion = 2, maxEntries = 8)
        reader.configure(file)

        assertNull(reader["旧键"])
        assertEquals(0, reader.inMemorySizeForTest())
    }

    @Test
    fun persistsOnlyMostRecentEntriesWithinBound() {
        val file = testFile("bound")
        PersistentSortKeyCache(algorithmVersion = 1, maxEntries = 2).also { cache ->
            cache.configure(file)
            cache["一"] = "YI"
            cache["二"] = "ER"
            cache["三"] = "SAN"
            cache.persist()
        }

        val reader = PersistentSortKeyCache(algorithmVersion = 1, maxEntries = 2)
        reader.configure(file)

        assertNull(reader["一"])
        assertEquals("ER", reader["二"])
        assertEquals("SAN", reader["三"])
    }

    @Test
    fun enforcesTotalByteBudgetAndSkipsOversizedEntryWithoutEvictingExistingCache() {
        val file = testFile("byte-bound")
        // Each entry takes 8 header bytes + UTF-8 key + UTF-8 value.
        // "k1" (2) + "aaaa" (4) + 8 = 14 bytes; two entries = 28 bytes <= 32, third evicts oldest.
        val cache = PersistentSortKeyCache(algorithmVersion = 1, maxEntries = 10, maxTotalBytes = 32)
        cache.configure(file)
        cache["k1"] = "aaaa"
        cache["k2"] = "bbbb"
        assertEquals(2, cache.inMemorySizeForTest())
        assertTrue(cache.inMemoryBytesForTest() <= 32)

        // Single entry larger than total byte budget must be ignored without clearing existing entries.
        cache["huge"] = "x".repeat(64)
        assertNull(cache["huge"])
        assertEquals("aaaa", cache["k1"])
        assertEquals("bbbb", cache["k2"])

        // Adding a valid third entry evicts oldest entry ("k1") to stay within 32 bytes.
        cache["k3"] = "cccc"
        assertNull(cache["k1"])
        assertEquals("bbbb", cache["k2"])
        assertEquals("cccc", cache["k3"])
        assertTrue(cache.inMemoryBytesForTest() <= 32)

        cache.persist()
        val reader = PersistentSortKeyCache(algorithmVersion = 1, maxEntries = 10, maxTotalBytes = 32)
        reader.configure(file)
        assertNull(reader["k1"])
        assertEquals("bbbb", reader["k2"])
        assertEquals("cccc", reader["k3"])
    }

    @Test
    fun stalePersistPausedBeforeMoveCannotOverwriteNewerPersistedSnapshot() {
        val file = testFile("interleaving-stale-persist")
        val cache = PersistentSortKeyCache(algorithmVersion = 1, maxEntries = 8)
        cache.configure(file)
        cache["曲目"] = "OLD_VALUE"

        val staleAtBoundary = CountDownLatch(1)
        val releaseStale = CountDownLatch(1)
        cache.beforePersistMoveForTest = { version ->
            if (version == 1L) {
                staleAtBoundary.countDown()
                assertTrue(releaseStale.await(5, TimeUnit.SECONDS))
            }
        }

        val staleThread = thread { cache.persist() }
        assertTrue(staleAtBoundary.await(5, TimeUnit.SECONDS))

        // Newer mutation and persist complete while stale persist is parked before publish.
        cache["曲目"] = "NEW_VALUE"
        cache["新曲"] = "NEW_ENTRY"
        cache.persist()

        // Release stale persist and verify disk state still belongs to the newer snapshot.
        releaseStale.countDown()
        staleThread.join(5_000)
        assertFalse(staleThread.isAlive)

        val reader = PersistentSortKeyCache(algorithmVersion = 1, maxEntries = 8)
        reader.configure(file)
        assertEquals("NEW_VALUE", reader["曲目"])
        assertEquals("NEW_ENTRY", reader["新曲"])

        val leftoverTemps = file.parentFile!!.listFiles { _, name ->
            name.startsWith("${file.name}.") && name.endsWith(".tmp")
        }.orEmpty()
        assertEquals(0, leftoverTemps.size)
    }

    @Test
    fun stalePersistPausedBeforeMoveCannotWriteAfterReconfigureToDifferentFile() {
        val oldFile = testFile("interleaving-reconfigure-old")
        val newFile = testFile("interleaving-reconfigure-new")
        val cache = PersistentSortKeyCache(algorithmVersion = 1, maxEntries = 8)
        cache.configure(oldFile)
        cache["旧曲"] = "OLD"

        val staleAtBoundary = CountDownLatch(1)
        val releaseStale = CountDownLatch(1)
        cache.beforePersistMoveForTest = { version ->
            if (version == 1L) {
                staleAtBoundary.countDown()
                assertTrue(releaseStale.await(5, TimeUnit.SECONDS))
            }
        }

        val staleThread = thread { cache.persist() }
        assertTrue(staleAtBoundary.await(5, TimeUnit.SECONDS))

        // Reconfigure to a new file and persist newer data before releasing the old persist.
        cache.beforePersistMoveForTest = null
        cache.configure(newFile)
        cache["新曲"] = "NEW"
        cache.persist()

        releaseStale.countDown()
        staleThread.join(5_000)
        assertFalse(staleThread.isAlive)

        assertFalse("Reconfigured old target must not be written by stale persist", oldFile.exists())
        val reader = PersistentSortKeyCache(algorithmVersion = 1, maxEntries = 8)
        reader.configure(newFile)
        assertEquals("NEW", reader["新曲"])
        assertNull(reader["旧曲"])
    }

    private fun testFile(name: String): File =
        File("build/tmp/persistent-sort-key-cache/$name-${System.nanoTime()}.bin").also {
            it.parentFile?.mkdirs()
        }
}
