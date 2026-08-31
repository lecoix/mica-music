package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavidromeStreamRequestResolverTest {
    @Test
    fun `stream request resolves credentials just in time from stable media id`() = runTest {
        val owner = RemoteSourceOwner(source())
        var credentialReads = 0
        val resolver = NavidromeStreamRequestResolver(
            sourceOwnerById = { id -> owner.takeIf { id == "nav-1" } },
            credentialStore = SecureRemoteCredentialStore { ref ->
                credentialReads++
                assertEquals("credential-nav-1", ref)
                credential()
            },
            requestFactory = NavidromeRequestFactory(saltProvider = { "fixedsalt" }),
        )
        val mediaId = RemoteMediaIdCodec.encode(RemoteTrackRef("nav-1", "track/9"))

        val request = resolver.resolve(mediaId)

        assertEquals(1, credentialReads)
        assertEquals("track/9", query(requireNotNull(request).url)["id"])
        assertEquals("fixedsalt", query(request.url)["s"])
    }

    @Test
    fun `source edit while credential is resolving rejects stale request`() = runTest {
        val owner = RemoteSourceOwner(source(endpoint = "https://old.example"))
        val resolver = NavidromeStreamRequestResolver(
            sourceOwnerById = { owner },
            credentialStore = SecureRemoteCredentialStore {
                owner.replace(source(endpoint = "https://new.example"))
                credential()
            },
            requestFactory = NavidromeRequestFactory(saltProvider = { "fixedsalt" }),
        )
        val mediaId = RemoteMediaIdCodec.encode(RemoteTrackRef("nav-1", "track-1"))

        assertNull(resolver.resolve(mediaId))
    }

    @Test
    fun `disabled source resolves neither credential nor playback request`() = runTest {
        val owner = RemoteSourceOwner(source(enabled = false))
        var credentialRead = false
        val resolver = NavidromeStreamRequestResolver(
            sourceOwnerById = { owner },
            credentialStore = SecureRemoteCredentialStore {
                credentialRead = true
                credential()
            },
        )
        val mediaId = RemoteMediaIdCodec.encode(RemoteTrackRef("nav-1", "track-1"))

        assertNull(resolver.resolve(mediaId))
        assertTrue(!credentialRead)
    }

    private fun source(
        endpoint: String = "https://music.example",
        enabled: Boolean = true,
    ) = RemoteSourceInstance(
        id = "nav-1",
        type = RemoteSourceType.NAVIDROME,
        displayName = "Home",
        endpoint = endpoint,
        credentialRef = "credential-nav-1",
        enabled = enabled,
    )

    private fun credential() = RemoteCredentialSnapshot(
        credentialRef = "credential-nav-1",
        revision = 3,
        material = RemoteCredentialMaterial.UsernamePassword("alice", "password"),
    )

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { pair ->
            val split = pair.split('=', limit = 2)
            URLDecoder.decode(split[0], Charsets.UTF_8.name()) to
                URLDecoder.decode(split.getOrElse(1) { "" }, Charsets.UTF_8.name())
        }
}
