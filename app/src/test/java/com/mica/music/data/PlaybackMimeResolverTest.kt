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
}
