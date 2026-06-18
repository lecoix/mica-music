package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMetadataContainerTest {
    @Test
    fun wavExtensionOverridesRawTrackMime() {
        assertEquals("WAV", TrackMetadata.containerFromMime("audio/raw", "track.wav"))
    }

    @Test
    fun flacExtensionOverridesRawTrackMime() {
        assertEquals("FLAC", TrackMetadata.containerFromMime("audio/raw", "track.flac"))
    }
}
