package com.mica.music.data

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPresentationBuilderTest {
    @Test
    fun prepareSortsVisibleSongsAndBuildsFastScrollIndex() {
        val songs = listOf(
            SongFixtures.song(id = "b", title = "Beta"),
            SongFixtures.song(id = "a", title = "Alpha"),
        )

        val presentation = LibraryPresentationBuilder.prepare(
            scannedSongs = songs,
            field = SongSortField.TITLE,
            direction = SortDirection.ASC,
        )

        assertEquals(listOf("a", "b"), presentation.visible.map { it.id })
        assertEquals(listOf("Alpha", "Beta"), presentation.fastScrollIndex?.labels)
        assertEquals(mapOf("A" to 0, "B" to 1), presentation.fastScrollIndex?.sectionTargets)
    }

    @Test
    fun prepareCanReuseInputOrderAndCachedSectionTargets() {
        val songs = listOf(
            SongFixtures.song(id = "b", title = "Beta"),
            SongFixtures.song(id = "a", title = "Alpha"),
        )
        val cachedTargets = mapOf("cached" to 7)

        val presentation = LibraryPresentationBuilder.prepare(
            scannedSongs = songs,
            field = SongSortField.TITLE,
            direction = SortDirection.ASC,
            useInputOrder = true,
            cachedSectionTargets = cachedTargets,
        )

        assertEquals(listOf("b", "a"), presentation.visible.map { it.id })
        assertEquals(cachedTargets, presentation.fastScrollIndex?.sectionTargets)
    }
}
