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
    fun legacyUnknownDiscNumberPreventsDeepMetadataCacheReuse() {
        val draft = draft()
        val cached = SongFixtures.song(id = draft.scanSongId()).copy(
            albumArtUri = null,
            mediaUri = draft.mediaUri,
            sizeBytes = draft.sizeBytes,
            dateModifiedMs = draft.dateModifiedMs,
            discNumber = -1,
        )

        assertNull(
            draft.reusableCachedSong(
                context = ApplicationProvider.getApplicationContext(),
                cachedById = mapOf(cached.id to cached),
                requireDeepMetadata = true,
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

    @Test
    fun dsdDraftDetectionUsesExtensionOrMimeOnly() {
        assertTrue(draft(displayName = "album.dsf", mimeType = "audio/*").isDsdDraft())
        assertTrue(draft(displayName = "album.bin", mimeType = "audio/x-dsf").isDsdDraft())

        assertTrue(!draft(displayName = "song.flac", mimeType = "audio/flac").isDsdDraft())
        assertTrue(!draft(displayName = "song.wav", mimeType = "audio/wav").isDsdDraft())
    }

    @Test
    fun audioTrackProbeIsSkippedForPlainContainersOnly() {
        assertTrue(!draft(displayName = "song.mp3", mimeType = "audio/mpeg").requiresAudioTrackProbe())
        assertTrue(!draft(displayName = "song.flac", mimeType = "audio/flac").requiresAudioTrackProbe())
        assertTrue(!draft(displayName = "song.wav", mimeType = "audio/wav").requiresAudioTrackProbe())
        assertTrue(!draft(displayName = "song.ogg", mimeType = "audio/ogg").requiresAudioTrackProbe())
        assertTrue(!draft(displayName = "song.opus", mimeType = "audio/opus").requiresAudioTrackProbe())
        assertTrue(!draft(displayName = "song.wma", mimeType = "audio/x-ms-wma").requiresAudioTrackProbe())
        assertTrue(!draft(displayName = "song.dsf", mimeType = "audio/x-dsf").requiresAudioTrackProbe())

        assertTrue(draft(displayName = "song.m4a", mimeType = "audio/mp4").requiresAudioTrackProbe())
        assertTrue(draft(displayName = "song.aac", mimeType = "audio/aac").requiresAudioTrackProbe())
        assertTrue(draft(displayName = "song.alac", mimeType = "audio/alac").requiresAudioTrackProbe())
        assertTrue(draft(displayName = "song.bin", mimeType = "audio/*").requiresAudioTrackProbe())
        assertTrue(draft(displayName = "song.ape", mimeType = "audio/x-ape").requiresAudioTrackProbe())
    }

    private fun draft(
        externalLyricsSignature: String = "",
        displayName: String = "song.flac",
        mimeType: String = "audio/flac",
    ): TrackDraft = TrackDraft(
        mediaStoreId = 42L,
        title = "song",
        artist = "artist",
        album = "album",
        albumId = 7L,
        durationSec = 180,
        mimeType = mimeType,
        displayName = displayName,
        sizeBytes = 1_000L,
        bitrateBpsFromStore = 0,
        mediaUri = "content://media/song",
        coverColorArgb = 0,
        dateModifiedMs = 3_000L,
        externalLyricsUris = listOf("lyrics://song.lrc"),
        externalLyricsSignature = externalLyricsSignature,
    )
}
