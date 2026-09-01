package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMimeResolverTest {

    @Test
    fun apeExtensionWinsOverGenericOrIncorrectStoreMime() {
        assertEquals(
            "audio/x-ape",
            PlaybackMimeResolver.resolve(
                storeMime = "application/octet-stream",
                probeMime = null,
                displayName = "album.ape",
                mediaUri = "content://media/external/audio/123",
            ),
        )
        assertEquals(
            "audio/x-ape",
            PlaybackMimeResolver.resolve(
                storeMime = "audio/mpeg",
                probeMime = null,
                displayName = "album.APE",
                mediaUri = "content://documents/tree/music",
            ),
        )
    }

    @Test
    fun macExtensionResolvesToApeContainer() {
        assertEquals(
            "audio/x-ape",
            PlaybackMimeResolver.resolve(
                storeMime = "",
                probeMime = null,
                displayName = "legacy.mac",
                mediaUri = "file:///music/legacy.mac",
            ),
        )
    }

    @Test
    fun rawAacExtensionUsesAacMimeInsteadOfMp4ContainerMime() {
        assertEquals(
            "audio/mp4a-latm",
            PlaybackMimeResolver.resolve(
                storeMime = "audio/aac",
                probeMime = "audio/mp4a-latm",
                displayName = "sample.aac",
                mediaUri = "content://documents/tree/music",
            ),
        )
    }

    @Test
    fun m4aExtensionStillUsesMp4ContainerMime() {
        assertEquals(
            "application/mp4",
            PlaybackMimeResolver.resolve(
                storeMime = "audio/mp4a-latm",
                probeMime = "audio/mp4a-latm",
                displayName = "sample.m4a",
                mediaUri = "content://documents/tree/music",
            ),
        )
    }

}
