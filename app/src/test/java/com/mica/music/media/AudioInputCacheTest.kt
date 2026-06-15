package com.mica.music.media

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputCacheTest {

    @Test
    fun completedCopyIsReusedWithoutOpeningSourceAgain() {
        val root = Files.createTempDirectory("audio-input-cache").toFile()
        val cache = AudioInputCache(root)
        val bytes = ByteArray(64 * 1024) { (it % 251).toByte() }
        var opens = 0

        val first = cache.getOrCopy("song-v1", "flac", bytes.size.toLong(), { false }) {
            opens++
            ByteArrayInputStream(bytes)
        }
        val second = cache.getOrCopy("song-v1", "flac", bytes.size.toLong(), { false }) {
            opens++
            ByteArrayInputStream(bytes)
        }

        assertFalse(first!!.reused)
        assertTrue(second!!.reused)
        assertTrue(first.file == second.file)
        assertArrayEquals(bytes, second.file.readBytes())
        assertTrue(opens == 1)
        first.close()
        second.close()
    }

    @Test
    fun cancelledCopyLeavesNoReusablePartialFile() {
        val root = Files.createTempDirectory("audio-input-cache-cancel").toFile()
        val cache = AudioInputCache(root)
        val bytes = ByteArray(256 * 1024) { 7 }
        var checks = 0

        val result = cache.getOrCopy(
            identity = "cancelled-song",
            extension = "flac",
            expectedBytes = bytes.size.toLong(),
            isCancelled = { ++checks > 2 },
            openInput = { ByteArrayInputStream(bytes) },
        )

        assertNull(result)
        val cacheFiles = root.walkTopDown().filter { it.isFile }.toList()
        assertTrue(cacheFiles.isEmpty())
    }

    @Test
    fun activeLeaseIsNotRemovedByLruTrim() {
        val root = Files.createTempDirectory("audio-input-cache-lease").toFile()
        val cache = AudioInputCache(root, maxEntries = 1, maxBytes = 1024)
        val first = cache.getOrCopy("first", "flac", 128, { false }) {
            ByteArrayInputStream(ByteArray(128) { 1 })
        }!!
        val second = cache.getOrCopy("second", "flac", 128, { false }) {
            ByteArrayInputStream(ByteArray(128) { 2 })
        }!!

        assertTrue(first.file.exists())
        second.close()
        first.close()

        val third = cache.getOrCopy("third", "flac", 128, { false }) {
            ByteArrayInputStream(ByteArray(128) { 3 })
        }!!
        assertTrue(third.file.exists())
        assertFalse(first.file.exists())
        third.close()
    }
}
