package com.mica.music.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.ScanSource
import com.mica.music.data.UserPlaylist
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
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistRepositoryFollowupTest {
    private lateinit var database: MicaDatabase
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var playlistRepository: PlaylistRepository
    private val source = SourceIdentityKey.device()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        libraryRepository = LibraryRepository(database)
        playlistRepository = PlaylistRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun lateConfirmedMissingIsObsoleteWhenSameSourceObjectIsPresentAgain() = runTest {
        val song = SongFixtures.song("ms_42")
        seedActiveLibrary(listOf(song))
        playlistRepository.insertPlaylist(
            UserPlaylist(id = "playlist", name = "Recovered", songIds = listOf(song.id)),
            position = 0,
        )
        val event = confirmedMissing(song.id, evidenceRevision = "audio-generation-7")
        libraryRepository.enqueueFollowupOutbox(event)

        val outcome = playlistRepository.consumeConfirmedMissingFollowup(event, song.id)

        assertEquals(PlaylistFollowupDisposition.OBSOLETE, outcome.disposition)
        assertTrue(outcome.acknowledged)
        assertEquals(listOf(song.id), playlistRepository.load().single().songIds)
        assertTrue(libraryRepository.loadFollowupOutbox().isEmpty())
    }

    @Test
    fun confirmedMissingPlaylistDeleteAndAckRollbackTogether() = runTest {
        val songId = "ms_missing"
        seedActiveLibrary(emptyList())
        playlistRepository.insertPlaylist(
            UserPlaylist(id = "playlist", name = "Atomic", songIds = listOf(songId)),
            position = 0,
        )
        val event = confirmedMissing(songId, evidenceRevision = "audio-generation-8")
        libraryRepository.enqueueFollowupOutbox(event)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_followup_ack
            BEFORE DELETE ON library_followup_outbox
            WHEN OLD.eventId = '${event.eventId}'
            BEGIN
                SELECT RAISE(ABORT, 'forced followup ack failure');
            END
            """.trimIndent(),
        )

        val failure = runCatching {
            playlistRepository.consumeConfirmedMissingFollowup(event, songId)
        }

        assertTrue(failure.isFailure)
        assertEquals(listOf(songId), playlistRepository.load().single().songIds)
        assertEquals(listOf(event), libraryRepository.loadFollowupOutbox())
    }

    @Test
    fun newerDurableMissingEvidenceObsoletesOlderCleanup() = runTest {
        val songId = "ms_superseded"
        seedActiveLibrary(emptyList())
        playlistRepository.insertPlaylist(
            UserPlaylist(id = "playlist", name = "Superseded", songIds = listOf(songId)),
            position = 0,
        )
        val oldEvent = confirmedMissing(songId, evidenceRevision = "audio-generation-10")
        val newestEvent = confirmedMissing(songId, evidenceRevision = "audio-generation-11").copy(
            libraryRevision = 2L,
            createdAtMs = 2L,
        )
        libraryRepository.enqueueFollowupOutbox(oldEvent)
        libraryRepository.enqueueFollowupOutbox(newestEvent)

        val oldOutcome = playlistRepository.consumeConfirmedMissingFollowup(oldEvent, songId)

        assertEquals(PlaylistFollowupDisposition.OBSOLETE, oldOutcome.disposition)
        assertEquals(listOf(songId), playlistRepository.load().single().songIds)
        assertEquals(listOf(newestEvent), libraryRepository.loadFollowupOutbox())

        val newestOutcome = playlistRepository.consumeConfirmedMissingFollowup(newestEvent, songId)
        assertEquals(PlaylistFollowupDisposition.APPLIED, newestOutcome.disposition)
        assertTrue(playlistRepository.load().single().songIds.isEmpty())
        assertTrue(libraryRepository.loadFollowupOutbox().isEmpty())
    }

    @Test
    fun sameSourceAuthorityReappearanceObsoletesPendingConfirmedMissing() = runTest {
        val song = SongFixtures.song("ms_returned")
        seedActiveLibrary(emptyList())
        val event = confirmedMissing(song.id, evidenceRevision = "audio-generation-9")
        libraryRepository.enqueueFollowupOutbox(event)
        assertEquals(listOf(event), libraryRepository.loadFollowupOutbox())

        libraryRepository.commitScanAuthority(
            songs = listOf(song),
            lastScanAtMs = 200L,
            lastScanSource = ScanSource.DEVICE,
            totalSizeMb = 1,
            state = activeState(source),
        )

        assertTrue(libraryRepository.loadFollowupOutbox().isEmpty())
    }

    @Test
    fun sourceSwitchAloneDoesNotObsoleteOldConfirmedMissingFact() = runTest {
        seedActiveLibrary(emptyList())
        val event = confirmedMissing("ms_old_source", evidenceRevision = "audio-generation-10")
        libraryRepository.enqueueFollowupOutbox(event)
        val folderSource = SourceIdentityKey.folder("content://provider/tree/new")

        libraryRepository.commitScanAuthority(
            songs = listOf(SongFixtures.song("folder_song")),
            lastScanAtMs = 300L,
            lastScanSource = ScanSource.FOLDER,
            totalSizeMb = 1,
            state = activeState(folderSource),
        )

        assertEquals(listOf(event), libraryRepository.loadFollowupOutbox())
    }

    @Test
    fun appliedBacklogBatchCompactsTenThousandMemberPlaylistOncePerBatch() = runTest {
        seedActiveLibrary(emptyList())
        val songIds = List(10_000) { index -> "missing-${index.toString().padStart(5, '0')}" }
        playlistRepository.replaceAll(
            listOf(
                UserPlaylist(
                    id = "large-playlist",
                    name = "Large",
                    songIds = songIds,
                ),
            ),
        )
        val events = songIds.take(512).mapIndexed { index, songId ->
            confirmedMissing(songId, evidenceRevision = "batch-$index").copy(createdAtMs = index.toLong())
        }
        libraryRepository.commitScanAuthorityWithFollowups(
            songs = emptyList(),
            lastScanAtMs = 200L,
            lastScanSource = ScanSource.DEVICE,
            totalSizeMb = 0,
            state = activeState(source),
            followupOutboxItems = events,
        )
        val revisionBefore = requireNotNull(database.playlistDao().getRevision())

        val outcome = playlistRepository.consumeConfirmedMissingFollowups(
            events.map { event ->
                LibraryConfirmedMissingFollowup(event, event.stableObjectKey)
            },
        )

        assertEquals(512, outcome.acknowledgedCount)
        assertEquals(revisionBefore + 1L, outcome.playlistRevision)
        assertEquals(songIds.drop(512), outcome.playlists?.single()?.songIds)
        assertEquals(songIds.drop(512), playlistRepository.load().single().songIds)
        assertTrue(libraryRepository.loadFollowupOutbox().isEmpty())
    }

    private suspend fun LibraryRepository.loadFollowupOutbox(): List<LibraryFollowupOutboxItem> {
        val items = mutableListOf<LibraryFollowupOutboxItem>()
        var cursor = LibraryFollowupOutboxCursor.Start
        while (true) {
            val page = loadFollowupOutboxPage(cursor = cursor, limit = 64)
            items += page.items
            val next = page.nextCursor ?: break
            if (next == cursor || page.items.size < 64) break
            cursor = next
        }
        return items
    }

    private suspend fun seedActiveLibrary(songs: List<com.mica.music.data.Song>) {
        libraryRepository.commitScanAuthority(
            songs = songs,
            lastScanAtMs = 100L,
            lastScanSource = ScanSource.DEVICE,
            totalSizeMb = songs.size,
            state = activeState(source),
        )
    }

    private fun activeState(identity: SourceIdentityKey) = PersistedLibraryState(
        intent = LibraryIntentState.ACTIVE,
        access = LibraryAccessState.AVAILABLE,
        sourceState = LibrarySourceState(
            active = SourceActivation(identity, activationEpoch = 1L),
        ),
        configFingerprint = "cfg",
    )

    private fun confirmedMissing(
        songId: String,
        evidenceRevision: String,
    ) = LibraryFollowupOutboxItem(
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
