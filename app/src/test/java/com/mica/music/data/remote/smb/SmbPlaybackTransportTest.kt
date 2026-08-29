package com.mica.music.data.remote.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmbPlaybackTransportTest {
    @Test
    fun resolverKeepsCredentialsEphemeralAndRejectsStaleGeneration() = runTest {
        val source = RemoteSourceInstance(
            id = "smb-1",
            type = RemoteSourceType.SMB,
            displayName = "NAS",
            endpoint = "smb://nas.local/Music/Library",
            credentialRef = "cred-1",
        )
        val owner = RemoteSourceOwner(source)
        val credential = RemoteCredentialSnapshot(
            credentialRef = "cred-1",
            revision = 7,
            material = RemoteCredentialMaterial.UsernamePassword("HOME\\alice", "super-secret"),
        )
        val mediaId = RemoteMediaIdCodec.encode(RemoteTrackRef(source.id, "Album/Track.flac"))
        val resolver = SmbStreamRequestResolver({ owner }, SecureRemoteCredentialStore { credential })

        val request = resolver.resolve(mediaId)!!

        assertEquals("Album/Track.flac", request.relativePath)
        assertEquals("HOME", request.login.domain)
        assertEquals("alice", request.login.username)
        assertEquals(7L, request.credentialRevision)
        assertFalse(request.toString().contains("super-secret"))
        assertFalse(request.toString().contains("nas.local"))
        assertFalse(request.toString().contains("Track.flac"))

        val staleResolver = SmbStreamRequestResolver(
            sourceOwnerById = { owner },
            credentialStore = SecureRemoteCredentialStore {
                owner.invalidateOperations()
                credential
            },
        )
        assertNull(staleResolver.resolve(mediaId))
    }

    @Test
    fun dataSourceReadsFromExactDataSpecOffsetWithoutSkip() {
        val payload = "0123456789".toByteArray()
        val file = FakeFile(payload)
        val session = FakeSession(file)
        val dataSource = SmbDataSource(request(), SmbSessionFactory { _, _ -> session })
        val stableUri = Uri.parse("mica-remote://track/stable")

        val opened = dataSource.open(
            DataSpec.Builder().setUri(stableUri).setPosition(4).setLength(4).build(),
        )
        val output = ByteArray(4)
        val first = dataSource.read(output, 0, 2)
        val second = dataSource.read(output, first, 2)
        val eof = dataSource.read(ByteArray(1), 0, 1)
        val openedUri = dataSource.uri
        dataSource.close()

        assertEquals(4L, opened)
        assertEquals(2, first)
        assertEquals(2, second)
        assertEquals(C.RESULT_END_OF_INPUT, eof)
        assertArrayEquals("4567".toByteArray(), output)
        assertEquals(listOf(4L, 6L), file.offsets)
        assertEquals(stableUri, openedUri)
        assertTrue(file.closed)
        assertTrue(session.closed)
    }

    @Test
    fun prematureRemoteEofIsFailureNotCleanSongEnd() {
        val file = FakeFile("abc".toByteArray(), declaredLength = 8)
        val dataSource = SmbDataSource(request(), SmbSessionFactory { _, _ -> FakeSession(file) })
        dataSource.open(DataSpec(Uri.parse("mica-remote://track/stable")))
        val buffer = ByteArray(8)
        assertEquals(3, dataSource.read(buffer, 0, buffer.size))

        val failure = runCatching { dataSource.read(buffer, 0, buffer.size) }.exceptionOrNull()
        runCatching { dataSource.close() }

        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("ended before"))
    }

    private fun request() = SmbPlaybackRequest(
        sourceInstanceId = "smb-1",
        sourceConfigRevision = 1,
        credentialRevision = 1,
        endpoint = SmbEndpoint("nas.local", 445, "Music", "Library"),
        relativePath = "Album/Track.flac",
        login = SmbLogin("alice", "secret", null),
    )

    private class FakeSession(private val file: SmbRandomAccessFile) : SmbSessionHandle {
        var closed = false
        override fun list(serverPath: String): List<SmbDirectoryEntry> = emptyList()
        override fun openFile(serverPath: String): SmbRandomAccessFile = file
        override fun close() { closed = true }
    }

    private class FakeFile(
        private val payload: ByteArray,
        private val declaredLength: Long = payload.size.toLong(),
    ) : SmbRandomAccessFile {
        override val length: Long get() = declaredLength
        val offsets = mutableListOf<Long>()
        var closed = false

        override fun read(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            offsets += fileOffset
            if (fileOffset >= payload.size) return -1
            val count = minOf(length, payload.size - fileOffset.toInt())
            payload.copyInto(buffer, offset, fileOffset.toInt(), fileOffset.toInt() + count)
            return count
        }

        override fun close() { closed = true }
    }
}
