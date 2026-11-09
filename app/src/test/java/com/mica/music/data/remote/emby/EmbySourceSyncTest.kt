package com.mica.music.data.remote.emby

import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteOperationToken
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.SecureRemoteCredentialStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmbySourceSyncTest {
    private val source = RemoteSourceInstance("emby-test", RemoteSourceType.EMBY, "Emby", "https://music.example", "ref")
    private val credentials = SecureRemoteCredentialStore { key ->
        RemoteCredentialSnapshot(key, 1, RemoteCredentialMaterial.BearerToken("", "secret-key"))
    }

    /** Fake only the database boundary; real owner tokens and real Emby parsing remain under test. */
    private class MemoryCatalogStore(source: RemoteSourceInstance) : EmbyCatalogStore {
        val owner = RemoteSourceOwner(source)
        var published: List<RemoteTrackSummary> = emptyList()
            private set
        var catalogRevision = 0L
            private set

        override suspend fun sourceOwner(sourceInstanceId: String): RemoteSourceOwner? =
            owner.takeIf { it.snapshot().instance.id == sourceInstanceId }

        override suspend fun publishCatalogIfCurrent(
            token: RemoteOperationToken,
            tracks: List<RemoteTrackSummary>,
        ): Boolean {
            if (!owner.isCurrent(token) || !owner.snapshot().instance.enabled) return false
            require(tracks.all { it.ref.sourceInstanceId == token.sourceInstanceId })
            require(tracks.map { it.ref.opaqueTrackId }.distinct().size == tracks.size)
            published = tracks.toList()
            catalogRevision++
            return true
        }
    }

    @Test fun syncPagesWithNoMoreThanRequestedAndCapsAt10000() = runTest {
        val catalog = MemoryCatalogStore(source)
        var requests = 0
        val transport = EmbyJsonTransport { url, token ->
            assertEquals("secret-key", token)
            assertFalse(url.contains(token))
            val start = Regex("StartIndex=(\\d+)").find(url)!!.groupValues[1].toInt()
            val count = Regex("Limit=(\\d+)").find(url)!!.groupValues[1].toInt()
            assertTrue(count <= 500)
            requests++
            val rows = (start until start + count).joinToString(",") {
                """{"Id":"item-$it","Type":"Audio","Name":"Song $it"}"""
            }
            """{"Items":[$rows],"TotalRecordCount":30000}"""
        }
        val result = EmbySourceSync(catalog, credentials, transport).sync(source.id, 10_001)
        assertEquals(10_000, result.trackCount)
        assertEquals(20, requests)
        assertEquals(10_000, catalog.published.size)
        assertEquals(1L, catalog.catalogRevision)
    }

    @Test fun staleSyncDoesNotReplaceCatalog() = runTest {
        val catalog = MemoryCatalogStore(source)
        val transport = EmbyJsonTransport { _, _ ->
            catalog.owner.replace(source.copy(displayName = "Edited"))
            """{"Items":[{"Id":"1","Type":"Audio"}],"TotalRecordCount":1}"""
        }
        try {
            EmbySourceSync(catalog, credentials, transport).sync(source.id)
            error("Expected stale sync failure")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("changed"))
        }
        assertEquals(0, catalog.published.size)
        assertEquals(0L, catalog.catalogRevision)
    }
}
