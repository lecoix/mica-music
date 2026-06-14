package com.mica.music.data

import androidx.test.core.app.ApplicationProvider
import com.mica.music.testutil.SongFixtures
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerControllerQueueModelTest {

    @Test
    fun deterministicRandomOperationsMatchIndependentReferenceModel() {
        val controller = PlayerController(ApplicationProvider.getApplicationContext())
        val model = QueueReferenceModel()
        val random = Random(SEED)
        var nextSongId = 0

        repeat(10_000) { step ->
            when (random.nextInt(6)) {
                0 -> {
                    val songs = List(random.nextInt(0, 12)) {
                        SongFixtures.song(id = "reset-${nextSongId++}")
                    }
                    controller.setQueue(songs)
                    model.setQueue(songs.map { it.id })
                }
                1 -> if (model.queue.isNotEmpty()) {
                    val selected = random.nextInt(model.queue.size)
                    val session = PlaybackSession(model.queue[selected], random.nextInt(0, 30_000))
                    controller.restoreSession(session)
                    model.select(selected)
                }
                2 -> {
                    val song = if (model.queue.isNotEmpty() && random.nextBoolean()) {
                        SongFixtures.song(id = model.queue[random.nextInt(model.queue.size)])
                    } else {
                        SongFixtures.song(id = "insert-${nextSongId++}")
                    }
                    if (model.queue.isEmpty()) {
                        controller.setQueue(listOf(song))
                        model.setQueue(listOf(song.id))
                    } else {
                        controller.insertPlayNext(song)
                        model.insertNext(song.id)
                    }
                }
                3 -> if (model.queue.size >= 2) {
                    val from = random.nextInt(model.queue.size)
                    val to = random.nextInt(model.queue.size)
                    controller.moveInQueue(from, to)
                    model.move(from, to)
                }
                4 -> if (model.queue.isNotEmpty()) {
                    val index = random.nextInt(model.queue.size)
                    controller.removeFromQueue(index)
                    model.remove(index)
                }
                5 -> {
                    controller.cyclePlaybackQueueMode()
                    model.cycleMode()
                }
            }

            assertEquals("queue mismatch at step=$step seed=$SEED", model.queue, controller.songQueue.map { it.id })
            assertEquals("index mismatch at step=$step seed=$SEED", model.currentIndex, controller.currentIndex)
            assertEquals("mode mismatch at step=$step seed=$SEED", model.mode, controller.playbackQueueMode)
            assertEquals(
                "current song mismatch at step=$step seed=$SEED",
                model.queue.getOrNull(model.currentIndex),
                controller.currentSong?.id,
            )
        }

        controller.release()
    }

    private class QueueReferenceModel {
        var queue: List<String> = emptyList()
            private set
        var currentIndex: Int = 0
            private set
        var mode: PlaybackQueueMode = PlaybackQueueMode.OFF
            private set

        fun setQueue(newQueue: List<String>) {
            val currentId = queue.getOrNull(currentIndex)
            queue = newQueue
            currentIndex = when {
                queue.isEmpty() -> 0
                currentId == null -> currentIndex.coerceIn(0, queue.lastIndex)
                else -> queue.indexOf(currentId).takeIf { it >= 0 } ?: currentIndex.coerceIn(0, queue.lastIndex)
            }
        }

        fun select(index: Int) {
            currentIndex = index.coerceIn(0, queue.lastIndex)
        }

        fun insertNext(songId: String) {
            val list = queue.toMutableList()
            var selected = currentIndex.coerceIn(0, list.lastIndex)
            val existing = list.indexOf(songId)
            if (existing == selected) return
            if (existing >= 0) {
                list.removeAt(existing)
                if (existing < selected) selected--
            }
            list.add((selected + 1).coerceAtMost(list.size), songId)
            queue = list
            currentIndex = selected
        }

        fun move(from: Int, to: Int) {
            if (from == to) return
            val list = queue.toMutableList()
            val moved = list.removeAt(from)
            list.add(to, moved)
            currentIndex = when {
                currentIndex == from -> to
                from < currentIndex && to >= currentIndex -> currentIndex - 1
                from > currentIndex && to <= currentIndex -> currentIndex + 1
                else -> currentIndex
            }.coerceIn(0, list.lastIndex)
            queue = list
        }

        fun remove(index: Int) {
            val list = queue.toMutableList()
            list.removeAt(index)
            queue = list
            currentIndex = when {
                list.isEmpty() -> 0
                index < currentIndex -> currentIndex - 1
                index == currentIndex -> index.coerceAtMost(list.lastIndex)
                else -> currentIndex
            }.let { if (list.isEmpty()) 0 else it.coerceIn(0, list.lastIndex) }
        }

        fun cycleMode() {
            mode = when (mode) {
                PlaybackQueueMode.OFF -> PlaybackQueueMode.REPEAT_ALL
                PlaybackQueueMode.REPEAT_ALL -> PlaybackQueueMode.REPEAT_ONE
                PlaybackQueueMode.REPEAT_ONE -> PlaybackQueueMode.SHUFFLE
                PlaybackQueueMode.SHUFFLE -> PlaybackQueueMode.OFF
            }
        }
    }

    private companion object {
        const val SEED = 0x4D494341
    }
}
