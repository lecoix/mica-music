package com.mica.music.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.library.LibraryAccessState
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
    fun newerPlaylistMutationWinsBetweenFollowupCommitAndMemoryPublish() = runTest {
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

        val followupCommit = store.commitConfirmedMissingFollowup(event, songId)
        assertTrue(followupCommit.acknowledged)

        // This mutation commits a newer durable playlist revision and publishes the full current
        // Room snapshot (which already contains the follow-up deletion) before the old follow-up
        // gets a chance to publish its pre-mutation memory snapshot.
        val newer = store.createPlaylist("Newer")

        assertTrue(store.publishConfirmedMissingFollowup(followupCommit))

        assertTrue(store.playlistById(original.id)?.songIds?.isEmpty() == true)
        assertEquals(newer, store.playlistById(newer.id))

        val cold = PlaylistStore(context)
        cold.awaitReady()
        assertTrue(cold.playlistById(original.id)?.songIds?.isEmpty() == true)
        assertEquals(newer, cold.playlistById(newer.id))
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
