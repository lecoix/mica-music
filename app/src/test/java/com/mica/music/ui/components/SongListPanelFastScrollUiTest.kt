package com.mica.music.ui.components

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.mica.music.data.FastScrollIndex
import com.mica.music.data.SongSortField
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.collections.AbstractList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongListPanelFastScrollUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stableSongsReuseFastScrollIndexAcrossRecompositionAndChangesInvalidateIt() {
        val initialSongs = listOf(
            SongFixtures.song(id = "a", title = "Alpha"),
            SongFixtures.song(id = "b", title = "Beta"),
        )
        var songs by mutableStateOf(initialSongs)
        var recompositionTick by mutableIntStateOf(0)
        var latestIndex: FastScrollIndex? = null

        composeRule.setContent {
            @Suppress("UNUSED_EXPRESSION")
            recompositionTick
            val index = rememberSongListFastScrollIndex(songs, SongSortField.TITLE)
            SideEffect { latestIndex = index }
        }

        composeRule.waitForIdle()
        val firstIndex = checkNotNull(latestIndex)
        assertEquals(mapOf("A" to 0, "B" to 1), firstIndex.sectionTargets)

        composeRule.runOnIdle { recompositionTick++ }
        composeRule.waitForIdle()
        assertSame(firstIndex, latestIndex)

        composeRule.runOnIdle {
            songs = initialSongs + SongFixtures.song(id = "c", title = "Charlie")
        }
        composeRule.waitForIdle()
        assertNotSame(firstIndex, latestIndex)
        assertEquals(listOf("Alpha", "Beta", "Charlie"), latestIndex?.labels)
    }

    @Test
    fun tenThousandSongsAreReadOnlyWhenIndexIsFirstBuilt() {
        val song = SongFixtures.song(id = "same", title = "Same")
        val songs = CountingSongList(List(10_000) { song })
        var recompositionTick by mutableIntStateOf(0)
        var latestIndex: FastScrollIndex? = null

        composeRule.setContent {
            @Suppress("UNUSED_EXPRESSION")
            recompositionTick
            val index = rememberSongListFastScrollIndex(songs, SongSortField.TITLE)
            SideEffect { latestIndex = index }
        }

        composeRule.waitForIdle()
        val initialReads = songs.getCount
        assertEquals(10_000, initialReads)

        repeat(30) {
            composeRule.runOnIdle { recompositionTick++ }
            composeRule.waitForIdle()
        }

        assertEquals(initialReads, songs.getCount)
        assertEquals(10_000, latestIndex?.labels?.size)
    }

    private class CountingSongList(
        private val values: List<com.mica.music.data.Song>,
    ) : AbstractList<com.mica.music.data.Song>() {
        var getCount: Int = 0
            private set

        override val size: Int
            get() = values.size

        override fun get(index: Int): com.mica.music.data.Song {
            getCount++
            return values[index]
        }
    }
}
