package com.mica.music.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemotePlaybackUriCodec
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.smb.SmbDirectoryEntry
import com.mica.music.data.remote.smb.SmbEndpoint
import com.mica.music.data.remote.smb.SmbLogin
import com.mica.music.data.remote.smb.SmbPlaybackRequest
import com.mica.music.data.remote.smb.SmbPlaybackRequestResolver
import com.mica.music.data.remote.smb.SmbRandomAccessFile
import com.mica.music.data.remote.smb.SmbSessionFactory
import com.mica.music.data.remote.smb.SmbSessionHandle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class MicaSmbRoutingDataSourceTest {
    @Test
    fun remoteUriFallsThroughHttpResolverToSmbAndUsesExactOffset() {
        val mediaId = RemoteMediaIdCodec.encode(RemoteTrackRef("smb-1", "Album/Track.flac"))
        val stableUri = RemotePlaybackUriCodec.encode(mediaId)
        var httpReads = 0
        var smbReads = 0
        val file = FakeFile("0123456789".toByteArray())
        val factory = MicaRoutingDataSourceFactory(
            context = ApplicationProvider.getApplicationContext(),
            remoteResolver = RemoteHttpPlaybackRequestResolver { httpReads++; null },
            smbResolver = SmbPlaybackRequestResolver { requested ->
                smbReads++
                assertEquals(mediaId, requested)
                SmbPlaybackRequest(
                    sourceInstanceId = "smb-1",
                    sourceConfigRevision = 2,
                    credentialRevision = 3,
                    endpoint = SmbEndpoint("nas.local", 445, "Music", "Library"),
                    relativePath = "Album/Track.flac",
                    login = SmbLogin("alice", "secret", null),
                )
            },
            smbSessionFactory = SmbSessionFactory { _, _ -> FakeSession(file) },
        )
        val dataSource = factory.createDataSource()
        val spec = DataSpec.Builder().setUri(Uri.parse(stableUri)).setPosition(5).build()

        val opened = dataSource.open(spec)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(3)
        while (true) {
            val read = dataSource.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            output.write(buffer, 0, read)
        }
        dataSource.close()

        assertEquals(1, httpReads)
        assertEquals(1, smbReads)
        assertEquals(5L, opened)
        assertArrayEquals("56789".toByteArray(), output.toByteArray())
        assertEquals(listOf(5L, 8L), file.offsets)
        assertEquals(stableUri, spec.uri.toString())
        assertFalse(stableUri.contains("nas.local"))
        assertFalse(stableUri.contains("alice"))
    }

    private class FakeSession(private val file: SmbRandomAccessFile) : SmbSessionHandle {
        override fun list(serverPath: String): List<SmbDirectoryEntry> = emptyList()
        override fun openFile(serverPath: String): SmbRandomAccessFile = file
        override fun close() = Unit
    }

    private class FakeFile(private val bytes: ByteArray) : SmbRandomAccessFile {
        override val length: Long = bytes.size.toLong()
        val offsets = mutableListOf<Long>()
        override fun read(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            offsets += fileOffset
            if (fileOffset >= bytes.size) return -1
            val count = minOf(length, bytes.size - fileOffset.toInt())
            bytes.copyInto(buffer, offset, fileOffset.toInt(), fileOffset.toInt() + count)
            return count
        }
        override fun close() = Unit
    }
}