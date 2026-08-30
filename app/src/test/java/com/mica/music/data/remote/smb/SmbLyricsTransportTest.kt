package com.mica.music.data.remote.smb

import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.TrackMetadata
import com.mica.music.data.remote.RemoteEmbeddedLyricsLoader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbLyricsTransportTest {
    @Test
    fun sameNameTtmlWinsAndAudioIsNeverOpened() = runTest {
        val files = linkedMapOf(
            "Library\\Album\\Song.lrc" to FakeFile("[00:01.00]LRC line".encodeToByteArray()),
            "Library\\Album\\Song.ttml" to FakeFile(
                """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="2s" end="3s">TTML line</p></div></body></tt>"""
                    .encodeToByteArray(),
            ),
        )
        val session = FakeSession(
            entries = listOf(
                SmbDirectoryEntry("Song.lrc", false, files.getValue("Library\\Album\\Song.lrc").length),
                SmbDirectoryEntry("Song.ttml", false, files.getValue("Library\\Album\\Song.ttml").length),
            ),
            files = files,
        )
        var embeddedCalls = 0
        val loader = SmbLyricsLoader(
            requestResolver = SmbPlaybackRequestResolver { request() },
            embeddedLoader = RemoteEmbeddedLyricsLoader { _, _, _ ->
                embeddedCalls++
                embeddedDocument()
            },
            sessionFactory = SmbSessionFactory { _, _ -> session },
        )

        val document = loader.load(song())

        assertEquals(LyricsFormat.TTML, document.format)
        assertEquals(LyricsOrigin.EXTERNAL, document.origin)
        assertEquals("TTML line", document.lines.single().parts.single().text)
        assertEquals(0, embeddedCalls)
        assertFalse(session.openedPaths.contains("Library\\Album\\Song.flac"))
        assertTrue(files.getValue("Library\\Album\\Song.ttml").closed)
        assertFalse(files.getValue("Library\\Album\\Song.lrc").closed)
        assertFalse(session.openedPaths.contains("Library\\Album\\Song.lrc"))
        assertTrue(session.closed)
    }

    @Test
    fun missingSidecarsFallsBackToEmbeddedRandomAccess() = runTest {
        val audio = FakeFile(byteArrayOf(10, 11, 12, 13, 14))
        val session = FakeSession(entries = emptyList(), files = mapOf("Library\\Album\\Song.flac" to audio))
        var embeddedCalls = 0
        val loader = SmbLyricsLoader(
            requestResolver = SmbPlaybackRequestResolver { request() },
            embeddedLoader = RemoteEmbeddedLyricsLoader { source, fileName, mimeType ->
                embeddedCalls++
                assertEquals("Song.flac", fileName)
                assertEquals("audio/flac", mimeType)
                val bytes = ByteArray(3)
                assertEquals(3, source.readAt(1, bytes, 0, bytes.size))
                assertEquals(listOf<Byte>(11, 12, 13), bytes.toList())
                embeddedDocument()
            },
            sessionFactory = SmbSessionFactory { _, _ -> session },
        )

        val document = loader.load(song())

        assertEquals(1, embeddedCalls)
        assertEquals(LyricsOrigin.EMBEDDED, document.origin)
        assertEquals("Embedded line", document.lines.single().parts.single().text)
        assertTrue(audio.closed)
        assertTrue(session.closed)
    }

    private fun request() = SmbPlaybackRequest(
        sourceInstanceId = "smb-lyrics",
        sourceConfigRevision = 1,
        credentialRevision = 1,
        endpoint = SmbPathCodec.parse("smb://nas.local/Music/Library"),
        relativePath = "Album/Song.flac",
        login = SmbLogin.parse("alice", "secret"),
    )

    private fun song() = Song(
        id = "remote-song",
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationSec = 10,
        metadata = TrackMetadata("FLAC", 44_100, 16, 0, 2, "audio/flac"),
        albumArtUri = null,
        coverColorArgb = 0,
        mediaUri = "mica-remote://song",
        fileName = "Song.flac",
        sizeBytes = 5,
        lyricsLoaded = false,
        source = SongSource.REMOTE,
    )

    private fun embeddedDocument() = LyricsDocument(
        format = LyricsFormat.PLAIN,
        origin = LyricsOrigin.EMBEDDED,
        lines = listOf(
            LyricLineNode(
                id = "embedded-1",
                startMs = 0,
                parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "Embedded line")),
            ),
        ),
    )

    private class FakeSession(
        private val entries: List<SmbDirectoryEntry>,
        private val files: Map<String, FakeFile>,
    ) : SmbSessionHandle {
        val openedPaths = mutableListOf<String>()
        var closed = false

        override fun list(serverPath: String): List<SmbDirectoryEntry> = entries

        override fun openFile(serverPath: String): SmbRandomAccessFile {
            openedPaths += serverPath
            return checkNotNull(files[serverPath]) { "Unexpected SMB open: $serverPath" }
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeFile(private val bytes: ByteArray) : SmbRandomAccessFile {
        override val length: Long = bytes.size.toLong()
        var closed = false

        override fun read(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (fileOffset >= bytes.size) return -1
            val count = minOf(length, bytes.size - fileOffset.toInt())
            bytes.copyInto(buffer, offset, fileOffset.toInt(), fileOffset.toInt() + count)
            return count
        }

        override fun close() {
            closed = true
        }
    }
}
