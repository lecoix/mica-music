package com.mica.music.data

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransientPlaybackCatalogTest {
    @Test
    fun replacementKeepsOnlyCurrentExternalSong() {
        val catalog = TransientPlaybackCatalog()
        val first = catalog.replace(SongFixtures.song("external-1"))
        val second = catalog.replace(SongFixtures.song("external-2"))

        assertEquals(SongSource.TRANSIENT_EXTERNAL, first.source)
        assertNull(catalog.songById(first.id))
        assertEquals(second, catalog.songById(second.id))
    }

    @Test
    fun catalogClearRemovesSessionOnlySong() {
        val catalog = TransientPlaybackCatalog()
        val song = catalog.replace(SongFixtures.song("external"))

        catalog.clear()

        assertNull(catalog.songById(song.id))
    }
}
