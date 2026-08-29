package com.mica.music.data.remote.smb

import com.hierynomus.mserref.NtStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbPathCodecTest {
    @Test
    fun normalizesShareRootUnicodeAndDefaultPort() {
        val normalized = SmbPathCodec.normalizeSourceEndpoint(
            " SMB://NAS.local:445/Music/My Albums/日本語/ ",
        )
        val endpoint = SmbPathCodec.parse(normalized)

        assertEquals("smb://nas.local/Music/My%20Albums/%E6%97%A5%E6%9C%AC%E8%AA%9E", normalized)
        assertEquals("nas.local", endpoint.host.lowercase())
        assertEquals(445, endpoint.port)
        assertEquals("Music", endpoint.share)
        assertEquals("My Albums\\日本語", endpoint.rootPath)
        assertEquals("My Albums\\日本語\\Album\\Track.flac", endpoint.serverPath("Album/Track.flac"))
    }

    @Test
    fun preservesExplicitPortAndRejectsCredentialTraversalOrMissingShare() {
        assertEquals(
            "smb://192.168.1.2:1445/Music",
            SmbPathCodec.normalizeSourceEndpoint("smb://192.168.1.2:1445/Music/"),
        )
        listOf(
            "smb://alice:secret@nas/Music",
            "smb://nas/Music/%2E%2E/Secret",
            "smb://nas",
            "https://nas/Music",
        ).forEach { value ->
            assertTrue("expected rejection for $value", runCatching { SmbPathCodec.parse(value) }.isFailure)
        }
    }

    @Test
    fun relativeTrackIdsCannotEscapeConfiguredRoot() {
        assertEquals("Disc 1/01.flac", SmbPathCodec.normalizeRelativePath("Disc 1\\01.flac"))
        assertEquals("Disc 1/01.flac", SmbPathCodec.appendChild("Disc 1", "01.flac"))
        assertTrue(runCatching { SmbPathCodec.normalizeRelativePath("../other.flac") }.isFailure)
        assertTrue(runCatching { SmbPathCodec.appendChild("", "bad/name.flac") }.isFailure)
    }

    @Test
    fun domainQualifiedUsernameIsSplitOnlyAtBackslash() {
        val domainLogin = SmbLogin.parse("HOME\\alice", "secret")
        assertEquals("HOME", domainLogin.domain)
        assertEquals("alice", domainLogin.username)

        val simpleLogin = SmbLogin.parse("alice@example.test", "secret")
        assertEquals(null, simpleLogin.domain)
        assertEquals("alice@example.test", simpleLogin.username)
    }
    @Test
    fun serverStatusClassificationKeepsAuthAndBadShareDistinct() {
        assertEquals(SmbFailureKind.AUTH, classifySmbStatus(NtStatus.STATUS_LOGON_FAILURE))
        assertEquals(SmbFailureKind.AUTH, classifySmbStatus(NtStatus.STATUS_ACCOUNT_DISABLED))
        assertEquals(SmbFailureKind.PROTOCOL, classifySmbStatus(NtStatus.STATUS_BAD_NETWORK_NAME))
        assertEquals(SmbFailureKind.CONNECT, classifySmbStatus(NtStatus.STATUS_IO_TIMEOUT))
    }

}
