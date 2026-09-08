package com.mica.music.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.ScanSource
import com.mica.music.data.ArtistNames
import com.mica.music.data.ArtistSplitConfig
import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.BrowseGroup
import com.mica.music.data.cacheKey
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.LyricsSlots
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.Song
import com.mica.music.data.LyricsSlot
import com.mica.music.data.library.LibraryAccessState
import com.mica.music.data.library.LibraryAutoSyncStateMutation
import com.mica.music.data.library.LibraryFollowupOutboxItem
import com.mica.music.data.library.LibraryIntentState
import com.mica.music.data.library.LibraryRetryItem
import com.mica.music.data.library.LibraryRetryKind
import com.mica.music.data.library.LibrarySyncCheckpoint
import com.mica.music.data.library.LibraryUserExclusion
import com.mica.music.data.library.PersistedLibraryState
import com.mica.music.data.library.SourceActivation
import com.mica.music.data.library.SourceIdentityKey
import com.mica.music.data.library.LibrarySourceState
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryTest {

    private lateinit var database: MicaDatabase
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LibraryRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun incrementalSyncReportsAddsUpdatesRemovalsAndPreservesOrder() = runTest {
        val initial = SongFixtures.queue(3)
        assertEquals(
            LibrarySyncResult(added = 3, updated = 0, removed = 0, unchanged = 0),
            repository.syncIncremental(initial, 100, ScanSource.DEVICE, 3),
        )

        val changed = listOf(
            initial[2],
            initial[0].copy(title = "Updated"),
            SongFixtures.song(id = "song-new", queueOrder = 9),
        )
        assertEquals(
            LibrarySyncResult(added = 1, updated = 2, removed = 1, unchanged = 0),
            repository.syncIncremental(changed, 200, ScanSource.FOLDER, 4),
        )

        val cached = repository.loadCached()!!
        assertEquals(changed.map { it.id }, cached.songs.map { it.id })
        assertEquals(ScanSource.FOLDER, cached.lastScanSource)
        assertEquals(4, cached.totalSizeMb)
    }

    @Test
    fun clearMakesCacheUnavailable() = runTest {
        repository.save(SongFixtures.queue(2), 100, ScanSource.DEVICE, 2)
        repository.clear()
        assertNull(repository.loadCached())
    }

    @Test
    fun checkpointAndRetryCommitAtomically() = runTest {
        val source = SourceIdentityKey.device()
        val checkpoint = LibrarySyncCheckpoint(
            sourceIdentity = source,
            partitionKey = "external_primary",
            providerVersion = "v1",
            generation = 42L,
            configFingerprint = "cfg",
            lastSuccessfulAutoSyncAtMs = 1234L,
        )
        val retry = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "song-1@rev-1",
            activationEpoch = 7L,
            stableObjectKey = "song-1",
            observedFingerprint = "rev-1",
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = "metadata",
            attemptCount = 1,
            nextRetryAtMs = 9999L,
        )

        repository.applyAutoSyncState(
            LibraryAutoSyncStateMutation(
                sourceIdentity = source,
                checkpoints = listOf(checkpoint),
                retryUpserts = listOf(retry),
            ),
        )

        assertEquals(listOf(checkpoint), repository.loadSyncCheckpoints(source))
        assertEquals(listOf(retry), repository.loadRetryItems(source))
    }

    @Test
    fun retryInsertFailureRollsBackCheckpointAdvance() = runTest {
        val source = SourceIdentityKey.device()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_library_retry_insert
            BEFORE INSERT ON library_retry_items
            BEGIN
                SELECT RAISE(ABORT, 'forced retry failure');
            END
            """.trimIndent(),
        )
        val checkpoint = LibrarySyncCheckpoint(
            sourceIdentity = source,
            partitionKey = "external_primary",
            providerVersion = "v2",
            generation = 99L,
            configFingerprint = "cfg",
            lastSuccessfulAutoSyncAtMs = 2222L,
        )
        val retry = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "song-fail@rev",
            activationEpoch = 1L,
            stableObjectKey = "song-fail",
            observedFingerprint = "rev",
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = "forced",
            attemptCount = 1,
            nextRetryAtMs = 3333L,
        )

        try {
            repository.applyAutoSyncState(
                LibraryAutoSyncStateMutation(
                    sourceIdentity = source,
                    checkpoints = listOf(checkpoint),
                    retryUpserts = listOf(retry),
                ),
            )
            fail("expected retry insert to abort transaction")
        } catch (_: Exception) {
            // Expected: the trigger aborts the single Room transaction.
        }

        assertTrue(repository.loadSyncCheckpoints(source).isEmpty())
        assertTrue(repository.loadRetryItems(source).isEmpty())
    }

    @Test
    fun autoSnapshotCheckpointAndFollowupRollbackTogetherWhenFollowupInsertFails() = runTest {
        val source = SourceIdentityKey.device()
        val activeState = PersistedLibraryState(
            intent = LibraryIntentState.ACTIVE,
            access = LibraryAccessState.AVAILABLE,
            sourceState = LibrarySourceState(
                active = SourceActivation(source, activationEpoch = 1L),
            ),
            configFingerprint = "cfg",
        )
        val initial = SongFixtures.queue(2)
        repository.commitScanAuthority(
            songs = initial,
            lastScanAtMs = 100L,
            lastScanSource = ScanSource.DEVICE,
            totalSizeMb = 2,
            state = activeState,
        )
        val checkpoint = LibrarySyncCheckpoint(
            sourceIdentity = source,
            partitionKey = "mediastore:audio",
            providerVersion = "v3",
            generation = 101L,
            configFingerprint = "cfg",
            lastSuccessfulAutoSyncAtMs = 2_000L,
        )
        val followup = LibraryFollowupOutboxItem(
            eventId = "fail-auto-followup",
            libraryRevision = 1L,
            action = "PLAYLIST_REMOVE_LIBRARY_MEMBERSHIP",
            sourceIdentity = source,
            activationEpoch = 1L,
            stableObjectKey = initial.first().id,
            payload = "songId=${initial.first().id}",
            createdAtMs = 2_000L,
        )
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_auto_followup
            BEFORE INSERT ON library_followup_outbox
            BEGIN
                SELECT RAISE(ABORT, 'forced auto followup failure');
            END
            """.trimIndent(),
        )

        try {
            repository.commitAutoSyncSnapshotAuthority(
                songs = listOf(initial.last()),
                lastScanAtMs = 100L,
                lastScanSource = ScanSource.DEVICE,
                totalSizeMb = 1,
                state = activeState,
                autoSyncStateMutation = LibraryAutoSyncStateMutation(
                    sourceIdentity = source,
                    checkpoints = listOf(checkpoint),
                ),
                followupOutboxItems = listOf(followup),
            )
            fail("expected followup insert to abort auto snapshot transaction")
        } catch (_: Exception) {
            // Expected: snapshot, checkpoint and outbox share one Room transaction.
        }

        val cached = repository.loadCached()!!
        assertEquals(initial.map(Song::id), cached.songs.map(Song::id))
        assertTrue(repository.loadSyncCheckpoints(source).isEmpty())
        assertTrue(repository.loadFollowupOutbox().isEmpty())
        assertEquals(activeState, repository.loadLibraryState())
    }
    @Test
    fun autoSnapshotPromotesStagedLyricsWithCheckpointAndRetryInSameCommit() = runTest {
        val source = SourceIdentityKey.folder("content://provider/tree/music")
        val state = PersistedLibraryState(
            intent = LibraryIntentState.ACTIVE,
            access = LibraryAccessState.AVAILABLE,
            sourceState = LibrarySourceState(
                active = SourceActivation(source, activationEpoch = 7L),
            ),
            configFingerprint = "cfg",
        )
        val oldLyrics = SongFixtures.song("old-lyrics").lyricsDocument.copy(
            format = LyricsFormat.LRC,
            origin = LyricsOrigin.EXTERNAL,
        )
        val oldSong = SongFixtures.song("saf-stage-success").copy(
            title = "Before",
            dateModifiedMs = 1L,
            lyricsDocument = oldLyrics,
            lyricsLoaded = true,
        )
        repository.commitScanAuthority(
            songs = listOf(oldSong),
            lastScanAtMs = 100L,
            lastScanSource = ScanSource.FOLDER,
            totalSizeMb = 1,
            state = state,
        )
        val newSong = oldSong.copy(
            title = "After",
            dateModifiedMs = 2L,
            lyricsDocument = LyricsDocument(),
            lyricsLoaded = false,
        )
        val newLyrics = oldLyrics.copy(format = LyricsFormat.TTML)
        val scanId = "saf-auto-stage-success"
        repository.stageLyrics(
            scanId,
            listOf(
                ScannedSongLyrics(
                    newSong.id,
                    newSong.lyricsCacheRevision,
                    LyricsSlots(externalTtml = newLyrics),
                ),
            ),
        )
        assertEquals(1, pendingLyricsCount(scanId))

        val checkpoint = LibrarySyncCheckpoint(
            sourceIdentity = source,
            partitionKey = "saf:tree",
            providerVersion = "saf-full-walk-v1",
            generation = 0L,
            configFingerprint = "cfg",
            lastSuccessfulAutoSyncAtMs = 1_000L,
        )
        val retry = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "saf-object-probe:" + newSong.id,
            activationEpoch = 7L,
            stableObjectKey = newSong.id,
            observedFingerprint = "fp",
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = "probe",
            attemptCount = 1,
            nextRetryAtMs = 2_000L,
        )

        repository.commitAutoSyncSnapshotAuthority(
            songs = listOf(newSong),
            lastScanAtMs = 100L,
            lastScanSource = ScanSource.FOLDER,
            totalSizeMb = 1,
            state = state,
            autoSyncStateMutation = LibraryAutoSyncStateMutation(
                sourceIdentity = source,
                checkpoints = listOf(checkpoint),
                retryUpserts = listOf(retry),
            ),
            followupOutboxItems = emptyList(),
            stagedLyricsId = scanId,
        )

        assertEquals("After", repository.loadCached()!!.songs.single().title)
        assertEquals(newLyrics, repository.lyricsById(newSong.id))
        assertEquals(listOf(checkpoint), repository.loadSyncCheckpoints(source))
        assertEquals(listOf(retry), repository.loadRetryItems(source))
        assertEquals(0, pendingLyricsCount(scanId))
        assertEquals(state, repository.loadLibraryState())
    }

    @Test
    fun autoSnapshotFailureRollsBackStagedLyricsCheckpointRetryAndSnapshotTogether() = runTest {
        val source = SourceIdentityKey.folder("content://provider/tree/music")
        val state = PersistedLibraryState(
            intent = LibraryIntentState.ACTIVE,
            access = LibraryAccessState.AVAILABLE,
            sourceState = LibrarySourceState(
                active = SourceActivation(source, activationEpoch = 8L),
            ),
            configFingerprint = "cfg",
        )
        val oldLyrics = SongFixtures.song("old-lyrics-failure").lyricsDocument.copy(
            format = LyricsFormat.LRC,
            origin = LyricsOrigin.EXTERNAL,
        )
        val oldSong = SongFixtures.song("saf-stage-failure").copy(
            title = "Before",
            dateModifiedMs = 1L,
            lyricsDocument = oldLyrics,
            lyricsLoaded = true,
        )
        repository.commitScanAuthority(
            songs = listOf(oldSong),
            lastScanAtMs = 100L,
            lastScanSource = ScanSource.FOLDER,
            totalSizeMb = 1,
            state = state,
        )
        val newSong = oldSong.copy(
            title = "After",
            dateModifiedMs = 2L,
            lyricsDocument = LyricsDocument(),
            lyricsLoaded = false,
        )
        val newLyrics = oldLyrics.copy(format = LyricsFormat.TTML)
        val scanId = "saf-auto-stage-failure"
        repository.stageLyrics(
            scanId,
            listOf(
                ScannedSongLyrics(
                    newSong.id,
                    newSong.lyricsCacheRevision,
                    LyricsSlots(externalTtml = newLyrics),
                ),
            ),
        )
        assertEquals(1, pendingLyricsCount(scanId))
        val checkpoint = LibrarySyncCheckpoint(
            sourceIdentity = source,
            partitionKey = "saf:tree",
            providerVersion = "saf-full-walk-v1",
            generation = 0L,
            configFingerprint = "cfg",
            lastSuccessfulAutoSyncAtMs = 1_000L,
        )
        val retry = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "saf-object-probe:" + newSong.id,
            activationEpoch = 8L,
            stableObjectKey = newSong.id,
            observedFingerprint = "fp",
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = "probe",
            attemptCount = 1,
            nextRetryAtMs = 2_000L,
        )
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_saf_auto_song_insert
            BEFORE INSERT ON songs
            WHEN NEW.id = 'saf-stage-failure'
            BEGIN
                SELECT RAISE(ABORT, 'forced saf auto snapshot failure');
            END
            """.trimIndent(),
        )

        try {
            repository.commitAutoSyncSnapshotAuthority(
                songs = listOf(newSong),
                lastScanAtMs = 100L,
                lastScanSource = ScanSource.FOLDER,
                totalSizeMb = 1,
                state = state,
                autoSyncStateMutation = LibraryAutoSyncStateMutation(
                    sourceIdentity = source,
                    checkpoints = listOf(checkpoint),
                    retryUpserts = listOf(retry),
                ),
                followupOutboxItems = emptyList(),
                stagedLyricsId = scanId,
            )
            fail("expected song insert to abort SAF AUTO authority transaction")
        } catch (_: Exception) {
            // Expected: lyrics promotion, auto state and snapshot are one Room transaction.
        }

        assertEquals("Before", repository.loadCached()!!.songs.single().title)
        assertEquals(oldLyrics, repository.lyricsById(oldSong.id))
        assertTrue(repository.loadSyncCheckpoints(source).isEmpty())
        assertTrue(repository.loadRetryItems(source).isEmpty())
        assertEquals(1, pendingLyricsCount(scanId))
        assertEquals(state, repository.loadLibraryState())
    }

    @Test
    fun clearAuthorityDropsCheckpointAndRetryButPreservesExclusionAndOutbox() = runTest {
        val source = SourceIdentityKey.device()
        val checkpoint = LibrarySyncCheckpoint(
            sourceIdentity = source,
            partitionKey = "external_primary",
            providerVersion = "v1",
            generation = 4L,
            configFingerprint = "cfg",
            lastSuccessfulAutoSyncAtMs = 100L,
        )
        val retry = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "retry-key",
            activationEpoch = 1L,
            stableObjectKey = "stable-song",
            observedFingerprint = "fingerprint",
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = "probe",
            attemptCount = 2,
            nextRetryAtMs = 500L,
        )
        val exclusion = LibraryUserExclusion(
            sourceIdentity = source,
            stableObjectKey = "excluded-song",
            exclusionRevision = 3L,
            createdAtMs = 10L,
        )
        val outbox = LibraryFollowupOutboxItem(
            eventId = "evt-1",
            libraryRevision = 8L,
            action = "PLAYLIST_REMOVE_USER_EXCLUDED",
            sourceIdentity = source,
            activationEpoch = null,
            stableObjectKey = exclusion.stableObjectKey,
            payload = "{}",
            createdAtMs = 11L,
        )
        repository.applyAutoSyncState(
            LibraryAutoSyncStateMutation(
                sourceIdentity = source,
                checkpoints = listOf(checkpoint),
                retryUpserts = listOf(retry),
            ),
        )
        repository.upsertUserExclusion(exclusion)
        repository.enqueueFollowupOutbox(outbox)
        repository.save(SongFixtures.queue(2), 100, ScanSource.DEVICE, 2)

        repository.clearAuthority(
            PersistedLibraryState(
                intent = LibraryIntentState.CLEARED_BY_USER,
                access = LibraryAccessState.AVAILABLE,
                sourceState = LibrarySourceState(),
                configFingerprint = "cfg",
            ),
        )

        assertNull(repository.loadCached())
        assertTrue(repository.loadSyncCheckpoints(source).isEmpty())
        assertTrue(repository.loadRetryItems(source).isEmpty())
        assertEquals(listOf(exclusion), repository.loadUserExclusions(source))
        assertEquals(listOf(outbox), repository.loadFollowupOutbox())
        assertEquals(LibraryIntentState.CLEARED_BY_USER, repository.loadLibraryState()?.intent)
    }

    @Test
    fun acknowledgeFollowupDeletesOnlyRequestedEvent() = runTest {
        val source = SourceIdentityKey.device()
        val first = LibraryFollowupOutboxItem(
            eventId = "evt-first",
            libraryRevision = 1L,
            action = "A",
            sourceIdentity = source,
            activationEpoch = 1L,
            stableObjectKey = "song-a",
            payload = "{}",
            createdAtMs = 1L,
        )
        val second = first.copy(
            eventId = "evt-second",
            stableObjectKey = "song-b",
            createdAtMs = 2L,
        )
        repository.enqueueFollowupOutbox(first)
        repository.enqueueFollowupOutbox(second)

        assertTrue(repository.acknowledgeFollowupOutbox(first.eventId))

        assertEquals(listOf(second), repository.loadFollowupOutbox())
    }

    @Test
    fun followupOutboxRollsBackWhenSnapshotMutationFails() = runTest {
        val source = SourceIdentityKey.device()
        val songs = SongFixtures.queue(2)
        repository.save(songs, 100, ScanSource.DEVICE, 2)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_song_delete_for_followup
            BEFORE DELETE ON songs
            BEGIN
                SELECT RAISE(ABORT, 'forced song delete failure');
            END
            """.trimIndent(),
        )
        val followup = LibraryFollowupOutboxItem(
            eventId = "evt-atomic-followup",
            libraryRevision = 9L,
            action = "PLAYLIST_REMOVE_LIBRARY_MEMBERSHIP",
            sourceIdentity = source,
            activationEpoch = 1L,
            stableObjectKey = songs.first().id,
            payload = "songId=${songs.first().id}",
            createdAtMs = 10L,
        )
        val state = PersistedLibraryState(
            intent = LibraryIntentState.ACTIVE,
            access = LibraryAccessState.AVAILABLE,
            sourceState = LibrarySourceState(),
            configFingerprint = "cfg",
        )

        try {
            repository.commitScanAuthorityWithFollowups(
                songs = listOf(songs.last()),
                lastScanAtMs = 101,
                lastScanSource = ScanSource.DEVICE,
                totalSizeMb = 1,
                state = state,
                followupOutboxItems = listOf(followup),
            )
            fail("expected snapshot mutation to abort transaction")
        } catch (_: Exception) {
            // Snapshot, authority state and outbox are one Room transaction.
        }

        assertTrue(repository.loadFollowupOutbox().isEmpty())
        assertEquals(
            songs.map(Song::id),
            repository.loadCached()!!.songs.map(Song::id),
        )
    }
    @Test
    fun userExclusionRollsBackWhenSnapshotMutationFails() = runTest {
        val source = SourceIdentityKey.device()
        val songs = SongFixtures.queue(2)
        repository.save(songs, 100, ScanSource.DEVICE, 2)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_song_delete_for_exclusion
            BEFORE DELETE ON songs
            BEGIN
                SELECT RAISE(ABORT, 'forced song delete failure');
            END
            """.trimIndent(),
        )
        val exclusion = LibraryUserExclusion(
            sourceIdentity = source,
            stableObjectKey = songs.first().id,
            exclusionRevision = 1L,
            createdAtMs = 1L,
        )

        try {
            repository.commitUserExclusionAuthority(
                songs = listOf(songs.last()),
                lastScanAtMs = 100,
                lastScanSource = ScanSource.DEVICE,
                totalSizeMb = 1,
                exclusion = exclusion,
            )
            fail("expected snapshot mutation to abort transaction")
        } catch (_: Exception) {
            // The exclusion and snapshot share one Room transaction.
        }

        assertTrue(repository.loadUserExclusions(source).isEmpty())
        assertEquals(
            songs.map(Song::id),
            repository.loadCached()!!.songs.map(Song::id),
        )
    }

    @Test
    fun browseGroupsSurviveColdCacheRoundTrip() = runTest {
        ArtistNames.configure(ArtistSplitConfig())
        val songs = listOf(
            SongFixtures.song("first").copy(artist = "Artist A / Artist B", album = "Album A"),
            SongFixtures.song("second").copy(artist = "Artist A", album = "Album B"),
        )

        repository.save(songs, 100, ScanSource.DEVICE, 2)

        val cached = repository.loadCached()!!
        assertEquals(listOf("Artist A", "Artist B"), cached.artistGroups?.map { it.title })
        assertEquals(listOf("Album A", "Album B"), cached.albumGroups?.map { it.title })
    }

    @Test
    fun browsePresentationOrderAndFastScrollIndexSurviveColdCacheRoundTrip() = runTest {
        repository.save(listOf(SongFixtures.song("cached")), 100, ScanSource.DEVICE, 1)
        val artists = listOf(
            BrowseGroup("Artist Z", "2 songs", 2),
            BrowseGroup("Artist A", "1 song", 1),
        )
        val albums = listOf(
            BrowseGroup("Album Z", "Artist Z", 2),
            BrowseGroup("Album A", "Artist A", 1),
        )

        repository.updateBrowseGroups(
            artistGroups = artists,
            albumGroups = albums,
            artistConfigKey = ArtistSplitConfig().cacheKey(),
            artistSortField = ArtistBrowseSortField.SONG_COUNT,
            artistSortDirection = SortDirection.DESC,
            artistFastScrollSectionTargets = null,
            albumSortField = AlbumBrowseSortField.TITLE,
            albumSortDirection = SortDirection.DESC,
            albumFastScrollSectionTargets = mapOf("Z" to 0, "A" to 1),
        )

        val cached = repository.loadCached()!!
        assertEquals(artists, cached.artistGroups)
        assertEquals(albums, cached.albumGroups)
        assertEquals(ArtistBrowseSortField.SONG_COUNT, cached.artistBrowseSortField)
        assertEquals(SortDirection.DESC, cached.artistBrowseSortDirection)
        assertNull(cached.artistBrowseFastScrollSectionTargets)
        assertEquals(AlbumBrowseSortField.TITLE, cached.albumBrowseSortField)
        assertEquals(SortDirection.DESC, cached.albumBrowseSortDirection)
        assertEquals(mapOf("Z" to 0, "A" to 1), cached.albumBrowseFastScrollSectionTargets)
    }

    @Test
    fun videoCoverUriSurvivesCacheRoundTrip() = runTest {
        val song = SongFixtures.song("video-cover").copy(videoCoverUri = "content://library/Album.mp4")

        repository.save(listOf(song), 100, ScanSource.FOLDER, 1)

        assertEquals(song.videoCoverUri, repository.loadCached()!!.songs.single().videoCoverUri)
    }

    @Test
    fun musicVideoPairingSurvivesCacheRoundTrip() = runTest {
        val song = SongFixtures.song("music-video").copy(
            musicVideoUri = "content://library/music-video.mp4",
            musicVideoRevision = "content://library/music-video.mp4|123|456",
        )

        repository.save(listOf(song), 100, ScanSource.FOLDER, 1)

        val cached = repository.loadCached()!!.songs.single()
        assertEquals(song.musicVideoUri, cached.musicVideoUri)
        assertEquals(song.musicVideoRevision, cached.musicVideoRevision)
    }

    @Test
    fun songByIdLoadsSingleSongWithLyrics() = runTest {
        val song = SongFixtures.song("with-lyrics")
        repository.save(listOf(song), 100, ScanSource.DEVICE, 1)

        val loaded = repository.songById(song.id)

        assertEquals(song.lyricsDocument, loaded?.lyricsDocument)
        assertEquals(song.id, loaded?.id)
    }

    @Test
    fun cachedCatalogOmitsLyricsUntilRequested() = runTest {
        val song = SongFixtures.song("summary-only")
        repository.save(listOf(song), 100, ScanSource.DEVICE, 1)

        val cachedSong = repository.loadCached()!!.songs.single()

        assertEquals(LyricsDocument(), cachedSong.lyricsDocument)
        assertEquals(false, cachedSong.lyricsLoaded)
        assertEquals(song.lyricsDocument, repository.lyricsById(song.id))
    }

    @Test
    fun syncingUnloadedSummaryPreservesStoredLyrics() = runTest {
        val song = SongFixtures.song("preserve-lazy")
        repository.save(listOf(song), 100, ScanSource.DEVICE, 1)
        val summary = repository.loadCached()!!.songs.single()

        repository.syncIncremental(
            songs = listOf(summary.copy(title = "Updated without lyrics")),
            lastScanAtMs = 200,
            lastScanSource = ScanSource.DEVICE,
            totalSizeMb = 1,
        )

        val stored = repository.songById(song.id)!!
        assertEquals("Updated without lyrics", stored.title)
        assertEquals(song.lyricsDocument, stored.lyricsDocument)
    }

    @Test
    fun threeSlotsAreStoredAndDefaultSelectionPrefersExternalTtml() = runTest {
        val song = SongFixtures.song("three-slots")
        repository.save(listOf(song.copy(lyricsLoaded = false)), 100, ScanSource.DEVICE, 1)
        val embedded = song.lyricsDocument.copy(format = LyricsFormat.SYLT, origin = LyricsOrigin.EMBEDDED)
        val lrc = song.lyricsDocument.copy(format = LyricsFormat.LRC, origin = LyricsOrigin.EXTERNAL)
        val ttml = song.lyricsDocument.copy(format = LyricsFormat.TTML, origin = LyricsOrigin.EXTERNAL)

        repository.applyLyricsBatch(
            listOf(ScannedSongLyrics(song.id, song.lyricsCacheRevision, LyricsSlots(embedded, lrc, ttml))),
        )
        repository.commitScan(
            listOf(song.copy(lyricsLoaded = false)),
            100,
            ScanSource.DEVICE,
            1,
        )

        assertEquals(3, database.songLyricsDao().getBySongId(song.id).size)
        assertEquals(ttml, repository.lyricsById(song.id))
        assertEquals(embedded, repository.lyricsById(song.id, listOf(LyricsSlot.EMBEDDED)))
        assertEquals(ttml, repository.lyricsById(song.id, revision = "stale-revision"))
    }

    @Test
    fun invalidPreferredSlotFallsBackToTheNextAvailableLyricsPayload() = runTest {
        val song = SongFixtures.song("invalid-preferred-slot")
        val lrc = song.lyricsDocument.copy(format = LyricsFormat.LRC, origin = LyricsOrigin.EXTERNAL)
        repository.save(listOf(song.copy(lyricsLoaded = false)), 100, ScanSource.DEVICE, 1)
        database.songLyricsDao().insertAll(
            listOf(
                SongLyricsEntity(
                    songId = song.id,
                    slot = LyricsSlot.EXTERNAL_LRC.name,
                    revision = song.lyricsCacheRevision,
                    lyricsJson = LyricsDocumentCodec.encode(lrc),
                ),
                SongLyricsEntity(
                    songId = song.id,
                    slot = LyricsSlot.EXTERNAL_TTML.name,
                    revision = song.lyricsCacheRevision,
                    lyricsJson = "not-json",
                ),
            ),
        )

        assertEquals(lrc, repository.lyricsById(song.id))
    }

    @Test
    fun completedBatchIsVisibleImmediatelyAndRevisionDoesNotHideConservativeLyrics() = runTest {
        val oldSong = SongFixtures.song("staged").copy(dateModifiedMs = 1L, lyricsLoaded = false)
        val oldLyrics = SongFixtures.song("old-lyrics").lyricsDocument.copy(
            format = LyricsFormat.LRC,
            origin = LyricsOrigin.EXTERNAL,
        )
        repository.save(
            listOf(oldSong.copy(lyricsDocument = oldLyrics, lyricsLoaded = true)),
            100,
            ScanSource.DEVICE,
            1,
        )

        val newSong = oldSong.copy(dateModifiedMs = 2L)
        val newLyrics = oldLyrics.copy(format = LyricsFormat.TTML)
        repository.applyLyricsBatch(
            listOf(
                ScannedSongLyrics(
                    newSong.id,
                    newSong.lyricsCacheRevision,
                    LyricsSlots(externalTtml = newLyrics),
                ),
            ),
        )

        assertEquals(newLyrics, repository.lyricsById(oldSong.id, revision = oldSong.lyricsCacheRevision))
        assertEquals(newLyrics, repository.lyricsById(newSong.id, revision = newSong.lyricsCacheRevision))

        repository.commitScan(listOf(newSong), 200, ScanSource.DEVICE, 1)

        assertEquals(newLyrics, repository.lyricsById(newSong.id, revision = newSong.lyricsCacheRevision))
        assertEquals(newLyrics, repository.lyricsById(oldSong.id, revision = oldSong.lyricsCacheRevision))
    }

    @Test
    fun authoritativeEmptyBatchDeletesAllThreeSlots() = runTest {
        val song = SongFixtures.song("empty-complete")
        val document = song.lyricsDocument
        repository.save(listOf(song.copy(lyricsLoaded = false)), 100, ScanSource.DEVICE, 1)
        repository.applyLyricsBatch(
            listOf(
                ScannedSongLyrics(
                    song.id,
                    song.lyricsCacheRevision,
                    LyricsSlots(document, document, document),
                ),
            ),
        )
        assertEquals(3, database.songLyricsDao().getBySongId(song.id).size)

        repository.applyLyricsBatch(
            listOf(ScannedSongLyrics(song.id, song.lyricsCacheRevision, LyricsSlots())),
        )

        assertEquals(0, database.songLyricsDao().getBySongId(song.id).size)
        assertEquals(LyricsDocument(), repository.lyricsById(song.id))
    }

    @Test
    fun presentationUpdatePreservesLyricsPayloadAndUpdatesCachedOrder() = runTest {
        val songs = SongFixtures.queue(2)
        repository.save(songs, 100, ScanSource.DEVICE, 2)
        val lyricsBefore = database.songDao().getById(songs[0].id)!!.lyricsJson

        repository.updatePresentation(
            songIds = songs.reversed().map { it.id },
            sortField = SongSortField.SIZE,
            sortDirection = SortDirection.DESC,
            fastScrollSectionTargets = mapOf("#" to 0),
        )

        val cached = repository.loadCached()!!
        assertEquals(songs.reversed().map { it.id }, cached.songs.map { it.id })
        assertEquals(SongSortField.SIZE, cached.sortField)
        assertEquals(SortDirection.DESC, cached.sortDirection)
        assertEquals(mapOf("#" to 0), cached.fastScrollSectionTargets)
        assertEquals(lyricsBefore, database.songDao().getById(songs[0].id)!!.lyricsJson)
    }

    private fun pendingLyricsCount(scanId: String): Int =
        database.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM song_lyrics_pending WHERE scanId = ?", arrayOf(scanId))
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }

    @Test
    fun daoReplaceAllIsAtomicFromCallerPerspective() = runTest {
        val dao = database.songDao()
        dao.replaceAll(SongFixtures.queue(2).mapIndexed { index, song -> song.toEntity(index) })
        dao.replaceAll(listOf(SongFixtures.song("replacement").toEntity(0)))
        assertEquals(listOf("replacement"), dao.getAllOrdered().map { it.id })
    }
}
