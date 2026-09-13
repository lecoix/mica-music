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

        val committedBeforeAdopt = CompletableDeferred<Unit>()
        val releaseAdopt = CompletableDeferred<Unit>()
        store.beforeMutationAdoptForTest = {
            committedBeforeAdopt.complete(Unit)
            releaseAdopt.await()
        }
        val consume = launch {
            store.consumeConfirmedMissingFollowups(
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
