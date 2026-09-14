package com.mica.music.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.library.LibraryAccessState
import com.mica.music.data.library.LibraryConfirmedMissingFollowup
import com.mica.music.data.library.LibraryFollowupOutboxCursor
import com.mica.music.data.library.LibraryFollowupOutboxItem
import com.mica.music.data.library.LibraryFollowupProtocol
import com.mica.music.data.library.LibraryIntentState
import com.mica.music.data.library.LibrarySourceState
import com.mica.music.data.library.MembershipRemovalReason
import com.mica.music.data.library.PersistedLibraryState
import com.mica.music.data.library.SourceActivation
import com.mica.music.data.library.SourceIdentityKey
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.local.MicaDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistStoreFollowupPublicationTest {
    private lateinit var context: Context
    private val source = SourceIdentityKey.device()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mica_playlists", Context.MODE_PRIVATE).edit().clear().commit()
        MicaDatabase.resetForTests()
        context.deleteDatabase(MicaDatabase.DATABASE_NAME)
    }

    @Test
    fun cancellationAfterFollowupCommitCannotSkipMemoryAdoption() = runTest {
        val store = PlaylistStore(context)
        store.awaitReady()
        val songId = "missing-song"
        val original = store.createPlaylist("Original")
        assertTrue(store.addSongToPlaylist(original.id, songId))

        val repository = LibraryRepository(MicaDatabase.get(context))
        repository.commitScanAuthority(
            songs = emptyList(),
            lastScanAtMs = 100L,
            lastScanSource = ScanSource.DEVICE,
            totalSizeMb = 0,
            state = activeState(),
        )
        val event = confirmedMissing(songId)
        repository.enqueueFollowupOutbox(event)
        val library = MusicLibrary(context)

        val committedBeforeAdopt = CompletableDeferred<Unit>()
        val releaseAdopt = CompletableDeferred<Unit>()
        store.beforeMutationAdoptForTest = {
            committedBeforeAdopt.complete(Unit)
            releaseAdopt.await()
        }
        val consume = launch {
            library.consumeConfirmedMissingFollowups(
                store,
                listOf(LibraryConfirmedMissingFollowup(event, songId)),
            )
        }
        committedBeforeAdopt.await()
        consume.cancel()
        releaseAdopt.complete(Unit)
        consume.join()

        assertTrue(store.playlistById(original.id)?.songIds?.isEmpty() == true)
        assertTrue(
            repository.loadFollowupOutboxPage(
                cursor = LibraryFollowupOutboxCursor.Start,
                limit = 64,
            ).items.isEmpty(),
        )

        val cold = PlaylistStore(context)
        cold.awaitReady()
        assertTrue(cold.playlistById(original.id)?.songIds?.isEmpty() == true)
        library.release()
    }

    @Test
    fun userEditWaitsForFollowupAdoptionAndSurvivesDuplicateReplay() = runTest {
        val store = PlaylistStore(context)
        store.awaitReady()
        val original = store.createPlaylist("Original")
        store.addSongToPlaylist(original.id, "missing-song")
        val repository = LibraryRepository(MicaDatabase.get(context))
        repository.commitScanAuthority(
            songs = emptyList(), lastScanAtMs = 100L,
            lastScanSource = ScanSource.DEVICE, totalSizeMb = 0, state = activeState(),
        )
        val event = confirmedMissing("missing-song")
        repository.enqueueFollowupOutbox(event)
        val requests = listOf(LibraryConfirmedMissingFollowup(event, "missing-song"))
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeMutationAdoptForTest = { committed.complete(Unit); release.await() }
        val consume = launch { store.consumeConfirmedMissingFollowups(requests) }
        committed.await()
        val edit = launch(start = CoroutineStart.UNDISPATCHED) {
            assertTrue(store.addSongToPlaylist(original.id, "new-song"))
        }
        assertTrue("The user edit must wait for the admitted owner operation", !edit.isCompleted)
        release.complete(Unit)
        consume.join()
        edit.join()
        assertEquals(listOf("new-song"), store.playlistById(original.id)?.songIds)
        assertEquals(1, store.consumeConfirmedMissingFollowups(requests))
        val cold = PlaylistStore(context)
        cold.awaitReady()
        assertEquals(listOf("new-song"), cold.playlistById(original.id)?.songIds)
        assertEquals(cold.playlists, store.playlists)
    }

    @Test
    fun cancellationAfterOrdinaryMutationCommitCannotSkipMemoryAdoption() = runTest {
        val store = PlaylistStore(context)
        store.awaitReady()
        val committedBeforeAdopt = CompletableDeferred<Unit>()
        val releaseAdopt = CompletableDeferred<Unit>()
        store.beforeMutationAdoptForTest = {
            committedBeforeAdopt.complete(Unit)
            releaseAdopt.await()
        }

        val create = launch { store.createPlaylist("Committed") }
        committedBeforeAdopt.await()
        create.cancel()
        releaseAdopt.complete(Unit)
        create.join()

        assertEquals("Committed", store.playlists.single().name)
        val cold = PlaylistStore(context)
        cold.awaitReady()
        assertEquals("Committed", cold.playlists.single().name)
    }

    @Test
    fun cancellationWhileWaitingForPublicationGateLeavesSecondEventAndPlaylistIntact() = runTest {
        val store = PlaylistStore(context)
        store.awaitReady()
        val playlist = store.createPlaylist("Waiting")
        store.addSongsToPlaylist(playlist.id, listOf("first", "second"))
        val repository = LibraryRepository(MicaDatabase.get(context))
        repository.commitScanAuthority(
            songs = emptyList(), lastScanAtMs = 100L,
            lastScanSource = ScanSource.DEVICE, totalSizeMb = 0, state = activeState(),
        )
        val first = confirmedMissing("first")
        val second = confirmedMissing("second")
        repository.enqueueFollowupOutbox(first)
        repository.enqueueFollowupOutbox(second)
        val library = MusicLibrary(context)
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeMutationAdoptForTest = { admitted.complete(Unit); release.await() }
        val firstJob = launch {
            library.consumeConfirmedMissingFollowups(store, listOf(LibraryConfirmedMissingFollowup(first, "first")))
        }
        admitted.await()
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            library.consumeConfirmedMissingFollowups(store, listOf(LibraryConfirmedMissingFollowup(second, "second")))
        }
        assertTrue(!waiting.isCompleted)
        waiting.cancel()
        release.complete(Unit)
        firstJob.join()
        waiting.join()
        assertEquals(listOf("second"), store.playlistById(playlist.id)?.songIds)
        assertEquals(listOf(second), repository.loadFollowupOutboxPage(LibraryFollowupOutboxCursor.Start, 64).items)
        val cold = PlaylistStore(context)
        cold.awaitReady()
        assertEquals(store.playlists, cold.playlists)
        library.release()
    }

    private fun activeState() = PersistedLibraryState(
        intent = LibraryIntentState.ACTIVE,
        access = LibraryAccessState.AVAILABLE,
        sourceState = LibrarySourceState(
            active = SourceActivation(source, activationEpoch = 1L),
        ),
        configFingerprint = "cfg",
    )

    private fun confirmedMissing(songId: String): LibraryFollowupOutboxItem {
        val evidenceRevision = "audio-generation-42"
        return LibraryFollowupOutboxItem(
            eventId = LibraryFollowupProtocol.playlistRemovalEventId(
                sourceIdentity = source,
                stableObjectKey = songId,
                evidenceRevision = evidenceRevision,
            ),
            libraryRevision = 1L,
            action = LibraryFollowupProtocol.PLAYLIST_REMOVE_CONFIRMED_MISSING,
            sourceIdentity = source,
            activationEpoch = 1L,
            stableObjectKey = songId,
            evidenceRevision = evidenceRevision,
            removalReason = MembershipRemovalReason.CONFIRMED_MISSING,
            payload = LibraryFollowupProtocol.playlistRemovalPayload(songId),
            createdAtMs = 1L,
        )
    }
}
