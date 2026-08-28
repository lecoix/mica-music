package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavidromeProtocolCatalogApiTest {
    @Test
    fun `search request parses page and uses current source snapshot`() = runTest {
        val owner = RemoteSourceOwner(source())
        var requestedUrl: String? = null
        val api = NavidromeProtocolCatalogApi(
            sourceOwner = owner,
            credentialStore = credentials(),
            executor = NavidromeHttpExecutor { request ->
                requestedUrl = request.url
                """{"subsonic-response":{"status":"ok","searchResult3":{"song":[{"id":"1","title":"One"}]}}}"""
            },
            requestFactory = NavidromeRequestFactory(saltProvider = { "fixedsalt" }),
        )

        val page = api.searchAllSongsPage(offset = 0, count = 500)

        assertEquals(listOf("1"), page.songs.map { it.remoteId })
        assertEquals(1, page.rawCount)
        requireNotNull(requestedUrl)
        org.junit.Assert.assertTrue(requestedUrl!!.contains("/rest/search3?"))
        org.junit.Assert.assertTrue(requestedUrl!!.contains("songCount=500"))
    }

    @Test
    fun `result from invalidated generation is rejected before publication`() = runTest {
        val owner = RemoteSourceOwner(source())
        val api = NavidromeProtocolCatalogApi(
            sourceOwner = owner,
            credentialStore = credentials(),
            executor = NavidromeHttpExecutor {
                owner.invalidateOperations()
                """{"subsonic-response":{"status":"ok","searchResult3":{"song":[]}}}"""
            },
            requestFactory = NavidromeRequestFactory(saltProvider = { "fixedsalt" }),
        )

        val error = try {
            api.searchAllSongsPage(0, 500)
            null
        } catch (caught: NavidromeException) {
            caught
        }

        requireNotNull(error)
        assertEquals(NavidromeFailureKind.STALE_OPERATION, error.kind)
    }

    private fun source() = RemoteSourceInstance(
        id = "nav-1",
        type = RemoteSourceType.NAVIDROME,
        displayName = "Home",
        endpoint = "https://music.example",
        credentialRef = "credential-nav-1",
    )

    private fun credentials() = SecureRemoteCredentialStore {
        RemoteCredentialSnapshot(
            credentialRef = "credential-nav-1",
            revision = 1,
            material = RemoteCredentialMaterial.UsernamePassword("alice", "password"),
        )
    }
}
