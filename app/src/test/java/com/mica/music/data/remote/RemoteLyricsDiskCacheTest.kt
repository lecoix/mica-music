package com.mica.music.data.remote

import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteLyricsDiskCacheTest {
    @Test
    fun `lyrics survive process-style reopen only for the same revision`() {
        val directory = Files.createTempDirectory("mica-remote-lyrics").toFile()
        val document = LyricsDocument(
            format = LyricsFormat.LRC,
            origin = LyricsOrigin.EXTERNAL,
            lines = listOf(
                LyricLineNode(
                    id = "line-1",
                    startMs = 1200,
                    parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "hello")),
                ),
            ),
        )
        try {
            RemoteLyricsDiskCache(directory).put("song-1", "revision-a", 2, document)

            val reopened = RemoteLyricsDiskCache(directory)
            assertEquals(document, reopened.get("song-1", "revision-a", 2))
            assertNull(reopened.get("song-1", "revision-b", 2))
            assertNull(reopened.get("song-1", "revision-a", 3))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `empty lyrics are not persisted as a successful payload`() {
        val directory = Files.createTempDirectory("mica-remote-lyrics-empty").toFile()
        try {
            val cache = RemoteLyricsDiskCache(directory)
            cache.put("song-1", "revision-a", 2, LyricsDocument())
            assertNull(RemoteLyricsDiskCache(directory).get("song-1", "revision-a", 2))
        } finally {
            directory.deleteRecursively()
        }
    }
}
