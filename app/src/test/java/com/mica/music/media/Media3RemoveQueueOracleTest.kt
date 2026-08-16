package com.mica.music.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Media3RemoveQueueOracleTest {
    @Test
    fun rawMedia3AndCanonicalReplacementMatchRemoveCurrentMatrix() {
        val cases = listOf(
            Case(listOf("A", "B", "C"), current = 0, from = 0, to = 1),
            Case(listOf("A", "B", "C"), current = 1, from = 1, to = 2),
            Case(listOf("C", "D", "X"), current = 2, from = 2, to = 3),
            Case(listOf("A", "B", "C", "D"), current = 1, from = 1, to = 3),
            Case(listOf("A", "B", "C"), current = 1, from = 1, to = 3),
            Case(listOf("A", "B", "C"), current = 2, from = 0, to = 1),
            Case(listOf("A", "B", "C"), current = 0, from = 2, to = 3),
            Case(listOf("A", "B", "C"), current = 1, from = 0, to = 1),
            Case(listOf("A", "B", "C"), current = 1, from = 2, to = 3),
        )
        cases.forEach { case ->
            val raw = applyRaw(case)
            val expectedIndex = Media3PlaylistIndexSemantics.currentIndexAfterRemove(
                queueSize = case.ids.size,
                currentIndex = case.current,
                fromIndex = case.from,
                effectiveToIndex = case.to.coerceAtMost(case.ids.size),
            )
            assertEquals("helper vs raw Media3 for $case", raw.currentIndex, expectedIndex)
            val mica = applyMica(case)
            assertEquals("Mica ids vs raw Media3 for $case", raw.ids, mica.ids)
            assertEquals("Mica index vs raw Media3 for $case", raw.currentIndex, mica.currentIndex)
            assertEquals("Mica current id vs raw Media3 for $case", raw.currentId, mica.currentId)
        }
    }

    @Test
    fun removeCurrentLastItemMatchesP4Media3Oracle() {
        val raw = applyRaw(Case(listOf("C", "D", "X"), current = 2, from = 2, to = 3, positionMs = 0L))
        assertEquals(listOf("C", "D"), raw.ids)
        assertEquals(0, raw.currentIndex)
        assertEquals("C", raw.currentId)
        val mica = applyMica(Case(listOf("C", "D", "X"), current = 2, from = 2, to = 3, positionMs = 0L))
        assertEquals(raw.ids, mica.ids)
        assertEquals(0, mica.currentIndex)
        assertEquals("C", mica.currentId)
    }

    @Test
    fun legalClampAndNoOpBoundsDoNotChangeQueue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = ExoPlayer.Builder(context).build()
        try {
            player.setMediaItems(items("A", "B", "C"), 1, 12345L)
            player.prepare()
            player.removeMediaItems(3, 4)
            assertEquals(listOf("A", "B", "C"), ids(player))
            assertEquals(1, player.currentMediaItemIndex)
            player.removeMediaItems(1, 1)
            assertEquals(listOf("A", "B", "C"), ids(player))
            assertEquals(1, player.currentMediaItemIndex)
            player.removeMediaItems(2, 99)
            assertEquals(listOf("A", "B"), ids(player))
            assertEquals(1, player.currentMediaItemIndex)
        } finally {
            player.release()
        }
    }

    private fun applyRaw(case: Case): Snapshot {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = ExoPlayer.Builder(context).build()
        return try {
            player.setMediaItems(items(*case.ids.toTypedArray()), case.current, case.positionMs)
            player.prepare()
            player.removeMediaItems(case.from, case.to)
            snapshot(player)
        } finally {
            player.release()
        }
    }

    private fun applyMica(case: Case): Snapshot {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exo = ExoPlayer.Builder(context).build()
        val player = MicaCompositePlayer(exo, testPlaybackStack())
        return try {
            player.setMediaItems(items(*case.ids.toTypedArray()), case.current, case.positionMs)
            player.prepare()
            player.removeMediaItems(case.from, case.to)
            snapshot(player)
        } finally {
            player.release()
        }
    }

    private fun snapshot(player: Player): Snapshot = Snapshot(
        ids = ids(player),
        currentIndex = player.currentMediaItemIndex,
        currentId = player.currentMediaItem?.mediaId,
        positionMs = player.currentPosition,
    )

    private fun items(vararg ids: String): List<MediaItem> = ids.map { id ->
        MediaItem.Builder()
            .setMediaId(id)
            .setUri("file:///music/$id.flac")
            .build()
    }

    private fun ids(player: Player): List<String> =
        List(player.mediaItemCount) { index -> player.getMediaItemAt(index).mediaId }

    private data class Case(
        val ids: List<String>,
        val current: Int,
        val from: Int,
        val to: Int,
        val positionMs: Long = 12345L,
    )

    private data class Snapshot(
        val ids: List<String>,
        val currentIndex: Int,
        val currentId: String?,
        val positionMs: Long,
    )
}
