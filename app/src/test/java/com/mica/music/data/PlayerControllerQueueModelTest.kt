package com.mica.music.data

import androidx.test.core.app.ApplicationProvider
import com.mica.music.media.SongMediaItemCodec
import com.mica.music.testutil.SongFixtures
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerControllerQueueModelTest {

    @Test
    fun playerControllerFacadeDoesNotOwnRuntimeInternals() {
        val fields = PlayerController::class.java.declaredFields
        assertEquals(
            PlaybackRuntime::class.java,
            fields.single { it.name == "runtime" }.type,
        )
        val names = fields.map { it.name }.toSet()
        listOf(
            "connectionSession",
            "queueCoordinator",
            "timelineCoordinator",
            "tuningCoordinator",
            "playbackStatistics",
            "pendingMediaSelection",
        ).forEach { forbidden ->
            assertFalse("PlayerController facade must not own $forbidden", forbidden in names)
        }
    }

    @Test
    fun serviceQueueMirrorPrefersCompleteLibrarySong() {
        val complete = SongFixtures.song(id = "with-lyrics")
        val lightweightItem = SongMediaItemCodec.encode(complete)

        val mirrored = resolveMirroredSong(lightweightItem) { id ->
            complete.takeIf { it.id == id }
        }

        assertEquals(complete, mirrored)
        assertEquals(2, mirrored?.lyricsDocument?.lines?.size)
    }

    @Test
    fun deterministicRandomOperationsMatchIndependentReferenceModel() {
        val controller = PlayerController(ApplicationProvider.getApplicationContext())
        val model = QueueReferenceModel()
        val random = Random(SEED)
        var nextSongId = 0
        var lastOperation = ""
        var beforeState = ""

        repeat(10_000) { step ->
            beforeState = "beforeModel=${model.queue.getOrNull(model.currentIndex)}@${model.currentIndex} " +
                "beforeController=${controller.playbackSurfaceState.currentSong?.id}@${controller.playbackQueueState.currentIndex}"
            when (random.nextInt(6)) {
                0 -> {
                    val songs = List(random.nextInt(0, 12)) {
                        SongFixtures.song(id = "reset-${nextSongId++}")
                    }
                    lastOperation = "setQueue(${songs.map { it.id }})"
                    controller.setQueue(songs)
                    model.setQueue(songs.map { it.id })
                }
                1 -> if (model.queue.isNotEmpty()) {
                    val selected = random.nextInt(model.queue.size)
                    val session = PlaybackSession(model.queue[selected], random.nextInt(0, 30_000))
                    lastOperation = "restoreSession(index=$selected id=${session.songId})"
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
                        lastOperation = "setQueue(single=${song.id})"
                        controller.setQueue(listOf(song))
                        model.setQueue(listOf(song.id))
                    } else {
                        lastOperation = "insertPlayNext(${song.id})"
                        controller.insertPlayNext(song)
                        model.insertNext(song.id)
                    }
                }
                3 -> if (model.queue.size >= 2) {
                    val from = random.nextInt(model.queue.size)
                    val to = random.nextInt(model.queue.size)
                    lastOperation = "move(from=$from,to=$to)"
                    controller.moveInQueue(from, to)
                    model.move(from, to)
                }
                4 -> if (model.queue.isNotEmpty()) {
                    val index = random.nextInt(model.queue.size)
                    lastOperation = "remove(index=$index)"
                    controller.removeFromQueue(index)
                    model.remove(index)
                }
                5 -> {
                    lastOperation = "cyclePlaybackQueueMode()"
                    controller.cyclePlaybackQueueMode()
                    // Playback mode is now mirrored from MediaController callbacks. This
                    // disconnected queue model test should keep mode unchanged.
                }
            }

            assertEquals(
                "queue mismatch at step=$step seed=$SEED operation=$lastOperation $beforeState",
                model.queue,
                controller.playbackQueueState.queue.map { it.id },
            )
            assertEquals(
                "index mismatch at step=$step seed=$SEED operation=$lastOperation $beforeState",
                model.currentIndex,
                controller.playbackQueueState.currentIndex,
            )
            assertEquals(
                "mode mismatch at step=$step seed=$SEED operation=$lastOperation",
                model.mode,
                controller.playbackSurfaceState.playbackQueueMode,
            )
            assertEquals(
                "current song mismatch at step=$step seed=$SEED operation=$lastOperation",
                model.queue.getOrNull(model.currentIndex),
                controller.playbackSurfaceState.currentSong?.id,
            )
        }

        controller.release()
    }

    @Test
    fun setQueueWithUnchangedPlaybackContentRefreshesQueueMetadata() {
        val controller = PlayerController(ApplicationProvider.getApplicationContext())
        val songs = listOf(
            SongFixtures.song(id = "a", title = "Alpha"),
            SongFixtures.song(id = "b", title = "Beta"),
        )
        controller.setQueue(songs)

        val metadataOnly = songs.map { song ->
            if (song.id == "a") {
                song.copy(
                    title = "Alpha (remastered)",
                    lyricsDocument = listOf(LyricLine(0, "updated lyric")).toLyricsDocumentCompat(),
                )
            } else {
                song
            }
        }
        controller.setQueue(metadataOnly)

        assertEquals(metadataOnly, controller.playbackQueueState.queue)
        assertEquals("Alpha (remastered)", controller.playbackQueueState.queue[0].title)
        assertEquals(
            "updated lyric",
            controller.playbackQueueState.queue[0].lyricsDocument.lines.single().parts.single().text,
        )
        controller.release()
    }

    @Test
    fun metadataOnlySetQueuePreservesHiddenShuffleOrder() {
        val controller = PlayerController(ApplicationProvider.getApplicationContext())
        val source = listOf(
            SongFixtures.song(id = "a", title = "Alpha"),
            SongFixtures.song(id = "b", title = "Beta"),
            SongFixtures.song(id = "c", title = "Gamma"),
            SongFixtures.song(id = "d", title = "Delta"),
        )
        val playback = listOf(source[2], source[0], source[3], source[1])
        controller.setQueue(playback)
        controller.restoreSession(PlaybackSession("a", 0))
        setPlaybackOrderState(
            controller,
            PlaybackOrderState(
                sourceIds = source.map { it.id },
                playbackIds = playback.map { it.id },
                currentId = "a",
                shuffleEnabled = true,
            ),
        )

        val metadataOnly = playback.map { song ->
            song.copy(title = "${song.title} (updated)")
        }
        controller.setQueue(metadataOnly)

        val state = playbackOrderState(controller)
        assertEquals(source.map { it.id }, state.sourceIds)
        assertEquals(playback.map { it.id }, state.playbackIds)
        assertEquals("a", state.currentId)
        assertEquals(metadataOnly, controller.playbackQueueState.queue)
        controller.release()
    }

    @Test
    fun refreshQueueMetadataReplacesMatchingSongsInSpecialQueue() {
        val controller = PlayerController(ApplicationProvider.getApplicationContext())
        val specialQueue = listOf(
            SongFixtures.song(id = "b", title = "Beta"),
            SongFixtures.song(id = "missing", title = "Not in library"),
            SongFixtures.song(id = "a", title = "Alpha"),
        )
        controller.setQueue(specialQueue)

        controller.refreshQueueMetadata(
            listOf(
                SongFixtures.song(id = "a", title = "Alpha (rescanned)"),
                SongFixtures.song(id = "b", title = "Beta (rescanned)").copy(
                    lyricsDocument = listOf(LyricLine(0, "fresh lyric")).toLyricsDocumentCompat(),
                ),
            ),
        )

        assertEquals(listOf("b", "missing", "a"), controller.playbackQueueState.queue.map { it.id })
        assertEquals("Beta (rescanned)", controller.playbackQueueState.queue[0].title)
        assertEquals(
            "fresh lyric",
            controller.playbackQueueState.queue[0].lyricsDocument.lines.single().parts.single().text,
        )
        assertEquals("Not in library", controller.playbackQueueState.queue[1].title)
        assertEquals("Alpha (rescanned)", controller.playbackQueueState.queue[2].title)
        controller.release()
    }

    @Test
    fun setQueueWithSameListReferenceIsNoOp() {
        val controller = PlayerController(ApplicationProvider.getApplicationContext())
        val songs = listOf(SongFixtures.song(id = "a"))
        controller.setQueue(songs)
        val queueBefore = controller.playbackQueueState.queue

        controller.setQueue(songs)

        assertEquals(queueBefore, controller.playbackQueueState.queue)
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

    }

    private companion object {
        const val SEED = 0x4D494341

        private fun playbackOrderState(controller: PlayerController): PlaybackOrderState {
            return queueCoordinator(controller).order
        }

        private fun setPlaybackOrderState(
            controller: PlayerController,
            state: PlaybackOrderState,
        ) {
            queueCoordinator(controller).replaceOrder(state)
        }

        private fun queueCoordinator(controller: PlayerController): PlaybackQueueCoordinator {
            val runtimeField = PlayerController::class.java.getDeclaredField("runtime")
            runtimeField.isAccessible = true
            val runtime = runtimeField.get(controller) as PlaybackRuntime
            val queueField = PlaybackRuntime::class.java.getDeclaredField("queueCoordinator")
            queueField.isAccessible = true
            return queueField.get(runtime) as PlaybackQueueCoordinator
        }
    }
}
