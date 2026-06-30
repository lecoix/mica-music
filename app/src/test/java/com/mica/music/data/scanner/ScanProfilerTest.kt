package com.mica.music.data.scanner

import androidx.test.core.app.ApplicationProvider
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanProfilerTest {

    @Test
    fun externalLyricsSignatureChangePreventsCachedSongReuse() {
        val draft = draft(externalLyricsSignature = "lyrics://song.lrc\u0001120\u00012000")
        val cached = SongFixtures.song(id = draft.scanSongId()).copy(
            albumArtUri = null,
            mediaUri = draft.mediaUri,
            sizeBytes = draft.sizeBytes,
            dateModifiedMs = draft.dateModifiedMs,
            externalLyricsSignature = "lyrics://song.lrc\u0001100\u00011000",
        )

        assertNull(
            draft.reusableCachedSong(
                context = ApplicationProvider.getApplicationContext(),
                cachedById = mapOf(cached.id to cached),
                requireDirectLyrics = true,
            ),
        )
    }

    @Test
    fun matchingExternalLyricsSignatureAllowsCachedSongReuse() {
        val signature = "lyrics://song.lrc\u0001120\u00012000"
        val draft = draft(externalLyricsSignature = signature)
        val cached = SongFixtures.song(id = draft.scanSongId()).copy(
            albumArtUri = null,
            mediaUri = draft.mediaUri,
            sizeBytes = draft.sizeBytes,
            dateModifiedMs = draft.dateModifiedMs,
            externalLyricsSignature = signature,
        )

        assertEquals(
            cached,
            draft.reusableCachedSong(
                context = ApplicationProvider.getApplicationContext(),
                cachedById = mapOf(cached.id to cached),
                requireDirectLyrics = true,
            ),
        )
    }

    @Test
    fun forceRefreshLyricsClearsCachedLyricsForProbe() {
        val draft = draft()
        val cached = SongFixtures.song(id = draft.scanSongId()).copy(
            mediaUri = draft.mediaUri,
            sizeBytes = draft.sizeBytes,
            dateModifiedMs = draft.dateModifiedMs,
        )

        assertNull(
            draft.reusableCachedSong(
                context = ApplicationProvider.getApplicationContext(),
                cachedById = mapOf(cached.id to cached),
                forceRefreshLyrics = true,
            ),
        )

        val probeCached = draft.unchangedCachedSongForProbe(
            cachedById = mapOf(cached.id to cached),
            forceRefreshLyrics = true,
        )
        assertTrue(probeCached?.lyrics?.isEmpty() == true)
    }

    private fun draft(
        externalLyricsSignature: String = "",
    ): TrackDraft = TrackDraft(
        mediaStoreId = 42L,
        title = "song",
        artist = "artist",
        album = "album",
        albumId = 7L,
        durationSec = 180,
        mimeType = "audio/flac",
        displayName = "song.flac",
        sizeBytes = 1_000L,
        bitrateBpsFromStore = 0,
        mediaUri = "content://media/song",
        coverColorArgb = 0,
        dateModifiedMs = 3_000L,
        externalLyricsUris = listOf("lyrics://song.lrc"),
        externalLyricsSignature = externalLyricsSignature,
    )
}
