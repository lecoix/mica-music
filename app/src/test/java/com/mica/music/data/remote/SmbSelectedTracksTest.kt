package com.mica.music.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmbSelectedTracksTest {
    @Test fun metadataHydrationPreservesLoadedLyricsStatsAndPlaybackPolicy() {
        val track = RemoteTrackSummary(RemoteTrackRef("smb", "song.flac"), "Filename", mimeTypeHint = "audio/flac")
        val lyrics = com.mica.music.data.LyricsDocument(format = com.mica.music.data.LyricsFormat.TTML)
        val current = track.toPlaybackSong().copy(
            lyricsDocument = lyrics, lyricsLoaded = true, playCount = 8, totalListenSeconds = 300,
            replayGain = com.mica.music.data.ReplayGainTags(trackGainDb = -5f), durationSec = 180,
        )
        val enriched = track.copy(title = "Tagged title", sampleRateHz = 96_000, bitsPerSample = 24)
            .mergePlaybackMetadata(current)
        assertEquals("Tagged title", enriched.title)
        assertEquals(96_000, enriched.metadata.sampleRateHz)
        assertSame(lyrics, enriched.lyricsDocument)
        assertTrue(enriched.lyricsLoaded)
        assertEquals(current.playCount, enriched.playCount)
        assertEquals(current.totalListenSeconds, enriched.totalListenSeconds)
        assertEquals(current.replayGain, enriched.replayGain)
        assertEquals(current.loudnessAnalysis, enriched.loudnessAnalysis)
        assertEquals(current.durationSec, enriched.durationSec)
        assertEquals(current.metadata.playbackMimeType, enriched.metadata.playbackMimeType)
        assertEquals(current.effectivePlaybackUri, enriched.effectivePlaybackUri)
    }

    @Test fun sourceDisabledAfterRegistrationCannotPublishPlayback() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MicaDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = RemoteCatalogRepository(db)
            val source = RemoteSourceInstance("smb", RemoteSourceType.SMB, "Home", "smb://host/share", "credential")
            repo.upsertSource(source)
            val token = repo.beginOperation(source.id)!!.token
            val track = RemoteTrackSummary(RemoteTrackRef(source.id, "song.flac"), "Song")
            val registered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var published = false
            val old = async {
                assertTrue(repo.registerSelectedTracks(token, listOf(track)))
                registered.complete(Unit)
                release.await()
                repo.publishIfCurrent(token) { published = true }
            }
            registered.await()
            repo.upsertSource(source.copy(enabled = false))
            release.complete(Unit)
            assertFalse(old.await())
            assertFalse(published)
            assertEquals(track, repo.find(listOf(track.ref))[track.ref])
        } finally { db.close() }
    }

    @Test fun lateMetadataCannotOverwriteNewFileRevisionOrAuthorizeItsArtwork() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MicaDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = RemoteCatalogRepository(db)
            val source = RemoteSourceInstance("smb", RemoteSourceType.SMB, "Home", "smb://host/share", "credential")
            repo.upsertSource(source)
            val token = repo.beginOperation(source.id)!!.token
            val old = RemoteTrackSummary(RemoteTrackRef(source.id, "song.flac"), "Old", contentRevision = "v1")
            repo.registerSelectedTracks(token, listOf(old))
            val request = repo.beginSelectedMetadata(old.ref)!!
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val late = async {
                entered.complete(Unit); release.await()
                repo.updateSelectedMetadata(request, old.copy(title = "Stale", artworkOpaqueId = "stale-art"))
            }
            entered.await()
            repo.registerSelectedTracks(token, listOf(old.copy(title = "New", contentRevision = "v2")))
            release.complete(Unit)
            assertFalse(late.await())
            assertEquals("New", repo.find(listOf(old.ref))[old.ref]!!.title)
            assertNull(repo.artworkCatalogRevisionIfPublishedForConfig(RemoteArtworkRef(source.id, "stale-art"), token.configRevision))
        } finally { db.close() }
    }

    @Test fun lateSelectionCannotWriteAfterSourceReplacementAndSurvivesCatalogRefresh() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MicaDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = RemoteCatalogRepository(db)
            val source = RemoteSourceInstance("smb", RemoteSourceType.SMB, "Home", "smb://host/share", "credential")
            repo.upsertSource(source)
            val old = repo.beginOperation(source.id)!!.token
            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val stale = RemoteTrackSummary(RemoteTrackRef(source.id, "old.flac"), "Old")
            val late = async {
                waiting.complete(Unit)
                release.await()
                repo.registerSelectedTracks(old, listOf(stale))
            }
            waiting.await()
            repo.upsertSource(source.copy(credentialRef = "new-credential"))
            val current = repo.beginOperation(source.id)!!.token
            val selected = RemoteTrackSummary(RemoteTrackRef(source.id, "new.flac"), "New")
            assertTrue(repo.registerSelectedTracks(current, listOf(selected)))
            release.complete(Unit)
            assertFalse(late.await())
            assertTrue(repo.publishCatalogIfCurrent(current, emptyList()))
            val reopened = RemoteCatalogRepository(db)
            assertEquals(selected, reopened.find(listOf(selected.ref))[selected.ref])
            assertNull(reopened.find(listOf(stale.ref))[stale.ref])
            assertTrue(reopened.tracksForEnabledSources().isEmpty())
        } finally { db.close() }
    }
}
