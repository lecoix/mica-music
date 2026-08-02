package com.mica.music.data

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSongResolverTest {
    @Test
    fun resolvesOnlyCurrentTransientCatalogSongs() {
        val externalSong = SongFixtures.song("external-song")
        val transientCatalog = TransientPlaybackCatalog()
        val resolver = ProcessPlaybackSongResolver(transientCatalog)
        transientCatalog.replace(externalSong)

        val resolved = resolver.resolve(externalSong.id)

        assertEquals(SongSource.TRANSIENT_EXTERNAL, resolved?.source)
        assertEquals(externalSong.id, resolved?.id)
        assertNull(resolver.resolve(SongFixtures.song("library-song").id))
    }
}
