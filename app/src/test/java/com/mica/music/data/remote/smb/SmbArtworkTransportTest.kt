package com.mica.music.data.remote.smb

import com.mica.music.data.remote.RemoteArtworkRef
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.RemoteFileArtworkIdCodec
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore
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
class SmbArtworkTransportTest {
    @Test
    fun resolverKeepsArtworkPathEphemeralAndSourceScoped() = runTest {
        val source = RemoteSourceInstance(
            id = "smb-1",
            type = RemoteSourceType.SMB,
            displayName = "NAS",
            endpoint = "smb://nas.local/Music/Library",
            credentialRef = "cred/smb-1",
        )
        val owner = RemoteSourceOwner(source)
        val credential = RemoteCredentialSnapshot(
            credentialRef = source.credentialRef,
            revision = 7L,
            material = RemoteCredentialMaterial.UsernamePassword("HOME\\alice", "secret"),
        )
        val resolver = SmbArtworkRequestResolver(
            sourceOwnerById = { id -> owner.takeIf { id == source.id } },
            credentialStore = SecureRemoteCredentialStore { ref -> credential.takeIf { ref == credential.credentialRef } },
        )
        val ref = RemoteArtworkRef(
            source.id,
            RemoteFileArtworkIdCodec.encode("Album/cover.jpg", "art:1"),
        )

        val request = resolver.resolve(ref)

        assertEquals("Album/cover.jpg", request?.relativePath)
        assertEquals("Library\\Album\\cover.jpg", request?.let { it.endpoint.serverPath(it.relativePath) })
        assertEquals(1L, request?.sourceConfigRevision)
        assertEquals(7L, request?.credentialRevision)
        assertEquals("HOME", request?.login?.domain)
        assertFalse(request.toString().contains("secret"))
        assertNull(resolver.resolve(RemoteArtworkRef(source.id, "navidrome-cover-id")))

        val embeddedResolver = SmbEmbeddedArtworkRequestResolver(
            sourceOwnerById = { id -> owner.takeIf { id == source.id } },
            credentialStore = SecureRemoteCredentialStore { ref ->
                credential.takeIf { ref == credential.credentialRef }
            },
        )
        val embedded = embeddedResolver.resolve(
            RemoteArtworkRef(
                source.id,
                RemoteEmbeddedArtworkIdCodec.encode("Album/Song.flac", "audio:1", 1234),
            ),
        )
        assertEquals("Album/Song.flac", embedded?.relativePath)
        assertEquals("Library\\Album\\Song.flac", embedded?.let { it.endpoint.serverPath(it.relativePath) })
        assertFalse(embedded.toString().contains("secret"))
    }

    @Test
    fun loaderReadsBoundedFileAndClosesBothHandles() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val file = FakeFile(bytes)
        val session = FakeSession(file)
        val loader = SmbArtworkByteLoader(
            sessionFactory = SmbSessionFactory { _, _ -> session },
            maxBytes = 10,
        )
        val request = SmbArtworkRequest(
            sourceInstanceId = "smb-1",
            sourceConfigRevision = 1L,
            credentialRevision = 1L,
            endpoint = SmbPathCodec.parse("smb://nas.local/Music/Library"),
            relativePath = "Album/cover.jpg",
            login = SmbLogin.parse("alice", "secret"),
        )

        assertArrayEquals(bytes, loader.load(request))
        assertEquals("Library\\Album\\cover.jpg", session.openedPath)
        assertTrue(file.closed)
        assertTrue(session.closed)
    }

    private class FakeSession(private val file: FakeFile) : SmbSessionHandle {
        var openedPath: String? = null
        var closed = false

        override fun list(serverPath: String): List<SmbDirectoryEntry> = emptyList()

        override fun openFile(serverPath: String): SmbRandomAccessFile {
            openedPath = serverPath
            return file
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
