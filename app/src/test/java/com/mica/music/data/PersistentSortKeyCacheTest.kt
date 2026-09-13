package com.mica.music.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun testFile(name: String): File =
        File("build/tmp/persistent-sort-key-cache/$name-${System.nanoTime()}.bin").also {
            it.parentFile?.mkdirs()
        }
}
