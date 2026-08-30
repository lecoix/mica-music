package com.mica.music.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteCatalogRepositoryTest {
    private lateinit var database: MicaDatabase
    private lateinit var repository: RemoteCatalogRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RemoteCatalogRepository(database) { 1234L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sourceAndCatalogRoundTripUsesStableRemoteIdentity() = runTest {
        val source = source("nav-1")
        val sourceSnapshot = repository.upsertSource(source)
        val operation = repository.beginOperation(source.id)
        assertNotNull(operation)

        val track = track(source.id, "song-9", title = "Nine")
        assertTrue(repository.publishCatalogIfCurrent(operation!!.token, listOf(track)))

        assertEquals(1L, sourceSnapshot.configRevision)
        assertEquals(listOf(source), repository.sources())
        assertEquals(listOf(track), repository.tracksForSource(source.id))
        assertEquals(track, repository.find(listOf(track.ref))[track.ref])

        val stored = database.remoteSourceDao().getById(source.id)!!
        assertEquals("credential/nav-1", stored.credentialRef)
        assertEquals(1L, stored.catalogRevision)
        assertEquals(1234L, stored.lastSyncAtMs)
    }

    @Test
    fun staleGenerationCannotReplaceNewerCatalog() = runTest {
        val source = source("nav-1")
        repository.upsertSource(source)
        val first = repository.beginOperation(source.id)!!.token
        assertTrue(repository.publishCatalogIfCurrent(first, listOf(track(source.id, "old"))))

        val stale = repository.beginOperation(source.id)!!.token
        repository.invalidateOperations(source.id)
        val current = repository.beginOperation(source.id)!!.token
        assertTrue(repository.publishCatalogIfCurrent(current, listOf(track(source.id, "new"))))

        assertFalse(repository.publishCatalogIfCurrent(stale, listOf(track(source.id, "stale"))))
        assertEquals(listOf("new"), repository.tracksForSource(source.id).map { it.ref.opaqueTrackId })
    }

    @Test
    fun sourceEditInvalidatesOldOperationWithoutClearingPublishedCatalog() = runTest {
        val source = source("nav-1")
        repository.upsertSource(source)
        val original = repository.beginOperation(source.id)!!.token
        assertTrue(repository.publishCatalogIfCurrent(original, listOf(track(source.id, "kept"))))

        val stale = repository.beginOperation(source.id)!!.token
        val edited = source.copy(endpoint = "https://new.example")
        val editedSnapshot = repository.upsertSource(edited)

        assertEquals(2L, editedSnapshot.configRevision)
        assertFalse(repository.publishCatalogIfCurrent(stale, listOf(track(source.id, "wrong"))))
        assertEquals(listOf("kept"), repository.tracksForSource(source.id).map { it.ref.opaqueTrackId })
        assertEquals("https://new.example", repository.source(source.id)?.endpoint)
    }

    @Test
    fun publishingOneSourceDoesNotMutateAnotherSourceSnapshot() = runTest {
        val a = source("a")
        val b = source("b")
        repository.upsertSource(a)
        repository.upsertSource(b)
        val a1 = repository.beginOperation(a.id)!!.token
        val b1 = repository.beginOperation(b.id)!!.token
        assertTrue(repository.publishCatalogIfCurrent(a1, listOf(track(a.id, "a-1"))))
        assertTrue(repository.publishCatalogIfCurrent(b1, listOf(track(b.id, "b-1"))))

        val a2 = repository.beginOperation(a.id)!!.token
        assertTrue(repository.publishCatalogIfCurrent(a2, listOf(track(a.id, "a-2"))))

        assertEquals(listOf("a-2"), repository.tracksForSource(a.id).map { it.ref.opaqueTrackId })
        assertEquals(listOf("b-1"), repository.tracksForSource(b.id).map { it.ref.opaqueTrackId })
        assertEquals(1L, database.remoteSourceDao().getById(b.id)!!.catalogRevision)
    }

    @Test
    fun enabledSourceAggregateHidesDisabledCatalogAndPreservesSourceOrder() = runTest {
        val zulu = source("zulu").copy(displayName = "Zulu")
        val alpha = source("alpha").copy(displayName = "Alpha")
        val hidden = source("hidden").copy(displayName = "Hidden", enabled = false)
        repository.upsertSource(zulu)
        repository.upsertSource(alpha)
        repository.upsertSource(hidden)
        assertTrue(
            repository.publishCatalogIfCurrent(
                repository.beginOperation(zulu.id)!!.token,
                listOf(track(zulu.id, "z-1"), track(zulu.id, "z-2")),
            ),
        )
        assertTrue(
            repository.publishCatalogIfCurrent(
                repository.beginOperation(alpha.id)!!.token,
                listOf(track(alpha.id, "a-1")),
            ),
        )
        assertTrue(
            repository.publishCatalogIfCurrent(
                repository.beginOperation(hidden.id)!!.token,
                listOf(track(hidden.id, "hidden-1")),
            ),
        )

        assertEquals(
            listOf("a-1", "z-1", "z-2"),
            repository.tracksForEnabledSources().map { it.ref.opaqueTrackId },
        )
    }

    @Test
    fun enabledSourceFlowFollowsCatalogPublicationAndSourceDisable() = runTest {
        val source = source("live")
        repository.upsertSource(source)
        val published = async(start = CoroutineStart.UNDISPATCHED) {
            repository.observeTracksForEnabledSources()
                .map { tracks -> tracks.map { it.ref.opaqueTrackId } }
                .first { it == listOf("song-1") }
        }

        assertTrue(
            repository.publishCatalogIfCurrent(
                repository.beginOperation(source.id)!!.token,
                listOf(track(source.id, "song-1")),
            ),
        )
        assertEquals(listOf("song-1"), published.await())

        val hidden = async(start = CoroutineStart.UNDISPATCHED) {
            repository.observeTracksForEnabledSources()
                .map { tracks -> tracks.map { it.ref.opaqueTrackId } }
                .first { it.isEmpty() }
        }
        repository.upsertSource(source.copy(enabled = false))
        assertEquals(emptyList<String>(), hidden.await())
    }

    @Test
    fun deleteSourceCascadesCatalogAndRejectsStaleOperation() = runTest {
        val source = source("delete")
        repository.upsertSource(source)
        val token = repository.beginOperation(source.id)!!.token
        assertTrue(repository.publishCatalogIfCurrent(token, listOf(track(source.id, "song"))))
        val stale = repository.beginOperation(source.id)!!.token
        assertTrue(repository.deleteSource(source.id))
        assertEquals(null, repository.source(source.id))
        assertTrue(repository.tracksForSource(source.id).isEmpty())
        assertFalse(repository.publishCatalogIfCurrent(stale, emptyList()))
        assertFalse(repository.deleteSource(source.id))
    }
    @Test
    fun configRevisionSurvivesRepositoryRecreation() = runTest {
        val source = source("nav-1")
        repository.upsertSource(source)
        repository.upsertSource(source.copy(displayName = "Edited"))

        val recreated = RemoteCatalogRepository(database)
        val snapshot = recreated.sourceSnapshot(source.id)

        assertEquals(2L, snapshot?.configRevision)
        assertEquals("Edited", snapshot?.instance?.displayName)
    }

    @Test
    fun artworkAuthorizationRequiresCurrentPublishedCatalogReference() = runTest {
        val source = source("nav-1")
        repository.upsertSource(source)
        val first = repository.beginOperation(source.id)!!.token
        assertTrue(repository.publishCatalogIfCurrent(first, listOf(track(source.id, "song-1"))))

        assertEquals(
            1L,
            repository.artworkCatalogRevisionIfPublishedForConfig(
                RemoteArtworkRef(source.id, "cover-1"),
                sourceConfigRevision = 1L,
            ),
        )
        assertEquals(
            null,
            repository.artworkCatalogRevisionIfPublishedForConfig(
                RemoteArtworkRef(source.id, "not-published"),
                sourceConfigRevision = 1L,
            ),
        )

        repository.upsertSource(source.copy(endpoint = "https://edited.example"))

        assertEquals(
            null,
            repository.artworkCatalogRevisionIfPublishedForConfig(
                RemoteArtworkRef(source.id, "cover-1"),
                sourceConfigRevision = 2L,
            ),
        )
        val second = repository.beginOperation(source.id)!!.token
        assertTrue(repository.publishCatalogIfCurrent(second, listOf(track(source.id, "song-2"))))
        assertEquals(
            2L,
            repository.artworkCatalogRevisionIfPublishedForConfig(
                RemoteArtworkRef(source.id, "cover-1"),
                sourceConfigRevision = 2L,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun catalogPublicationRejectsMixedSourceTracks() = runTest {
        val source = source("nav-1")
        repository.upsertSource(source)
        val token = repository.beginOperation(source.id)!!.token
        repository.publishCatalogIfCurrent(token, listOf(track("other", "bad")))
    }

    private fun source(id: String) = RemoteSourceInstance(
        id = id,
        type = RemoteSourceType.NAVIDROME,
        displayName = "Source $id",
        endpoint = "https://$id.example",
        credentialRef = "credential/$id",
    )

    private fun track(sourceId: String, remoteId: String, title: String = remoteId) = RemoteTrackSummary(
        ref = RemoteTrackRef(sourceId, remoteId),
        title = title,
        artist = "Artist",
        album = "Album",
        albumArtist = "Album Artist",
        durationSec = 123,
        mimeTypeHint = "audio/flac",
        fileName = "$remoteId.flac",
        suffix = "flac",
        sizeBytes = 456L,
        year = 2026,
        trackNumber = 2,
        discNumber = 1,
        albumOpaqueId = "album-1",
        artistOpaqueId = "artist-1",
        artworkOpaqueId = "cover-1",
    )
}
