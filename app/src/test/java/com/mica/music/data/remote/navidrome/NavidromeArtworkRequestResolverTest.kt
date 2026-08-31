package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteArtworkRef
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NavidromeArtworkRequestResolverTest {
    @Test
    fun `artwork request resolves current credentials from stable artwork ref`() = runTest {
        val owner = RemoteSourceOwner(source())
        var credentialReads = 0
        val resolver = NavidromeArtworkRequestResolver(
            sourceOwnerById = { id -> owner.takeIf { id == "nav-1" } },
            credentialStore = SecureRemoteCredentialStore { ref ->
                credentialReads++
                assertEquals("credential-nav-1", ref)
                credential()
            },
            requestFactory = NavidromeRequestFactory(saltProvider = { "fixedsalt" }),
        )

        val request = resolver.resolve(RemoteArtworkRef("nav-1", "cover/9"))

        assertEquals(1, credentialReads)
        assertEquals("cover/9", query(requireNotNull(request).url)["id"])
        assertEquals("fixedsalt", query(request.url)["s"])
        assertFalse(request.toString().contains("fixedsalt"))
    }

    @Test
    fun `source edit during credential resolution rejects stale artwork request`() = runTest {
        val owner = RemoteSourceOwner(source(endpoint = "https://old.example"))
        val resolver = NavidromeArtworkRequestResolver(
            sourceOwnerById = { owner },
            credentialStore = SecureRemoteCredentialStore {
                owner.replace(source(endpoint = "https://new.example"))
                credential()
            },
            requestFactory = NavidromeRequestFactory(saltProvider = { "fixedsalt" }),
        )

        assertNull(resolver.resolve(RemoteArtworkRef("nav-1", "cover-1")))
    }

    @Test
    fun `disabled source does not resolve artwork credentials`() = runTest {
        val owner = RemoteSourceOwner(source(enabled = false))
        var credentialRead = false
        val resolver = NavidromeArtworkRequestResolver(
            sourceOwnerById = { owner },
            credentialStore = SecureRemoteCredentialStore {
                credentialRead = true
                credential()
            },
        )

        assertNull(resolver.resolve(RemoteArtworkRef("nav-1", "cover-1")))
        assertFalse(credentialRead)
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
