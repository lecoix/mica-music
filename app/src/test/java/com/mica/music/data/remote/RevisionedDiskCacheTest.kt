package com.mica.music.data.remote

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RevisionedDiskCacheTest {
    @Test
    fun `cache survives a new cache instance and revision key changes miss`() {
        val directory = Files.createTempDirectory("mica-remote-cache").toFile()
        try {
            RevisionedDiskCache(directory, maxBytes = 1024, maxEntries = 8)
                .put("song:revision-1", byteArrayOf(1, 2, 3))

            val reopened = RevisionedDiskCache(directory, maxBytes = 1024, maxEntries = 8)
            assertArrayEquals(byteArrayOf(1, 2, 3), reopened.get("song:revision-1"))
            assertNull(reopened.get("song:revision-2"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cache remains bounded by bytes and entry count`() {
        val directory = Files.createTempDirectory("mica-remote-cache-budget").toFile()
        var now = 1_000L
        try {
            val cache = RevisionedDiskCache(
                directory = directory,
                maxBytes = 6,
                maxEntries = 2,
                nowMs = { now++ },
            )
            cache.put("a", byteArrayOf(1, 1, 1))
            cache.put("b", byteArrayOf(2, 2, 2))
            assertArrayEquals(byteArrayOf(1, 1, 1), cache.get("a"))
            cache.put("c", byteArrayOf(3, 3, 3))

            assertArrayEquals(byteArrayOf(1, 1, 1), cache.get("a"))
            assertNull(cache.get("b"))
            assertArrayEquals(byteArrayOf(3, 3, 3), cache.get("c"))
            assertEquals(2, directory.listFiles().orEmpty().count { it.name.endsWith(".bin") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
