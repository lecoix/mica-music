package com.mica.music.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWallpaperStoreTest {

    @Test
    fun olderImportReleasedAfterNewerImportCannotDeleteOrPublishOverNewerWallpaper() = runTest {
        val directory = Files.createTempDirectory("mica-wallpaper-store").toFile()
        var publishedPath: String? = null
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = AppWallpaperStore(
            directory = directory,
            publishPath = { publishedPath = it },
            ioDispatcher = dispatcher,
            idProvider = sequenceOf("old", "new").iterator()::next,
        )
        val oldPrepared = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()

        val oldImport = async {
            store.replace { candidate ->
                candidate.writeText("old")
                oldPrepared.complete(Unit)
                releaseOld.await()
                true
            }
        }
        oldPrepared.await()

        val newResult = store.replace { candidate ->
            candidate.writeText("new")
            true
        }
        releaseOld.complete(Unit)
        val oldResult = oldImport.await()

        assertEquals(AppWallpaperStore.ReplaceResult.APPLIED, newResult)
        assertEquals(AppWallpaperStore.ReplaceResult.SUPERSEDED, oldResult)
        val finalPath = requireNotNull(publishedPath)
        assertEquals("new", File(finalPath).readText())
        assertEquals(listOf(File(finalPath).name), directory.listFiles().orEmpty().map(File::getName))
    }

    @Test
    fun clearReleasedBeforeOlderImportFinishesPreventsLateFileAndPathPublication() = runTest {
        val directory = Files.createTempDirectory("mica-wallpaper-store").toFile()
        val existing = File(directory, "wallpaper-existing.jpg").apply { writeText("existing") }
        var publishedPath: String? = existing.absolutePath
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = AppWallpaperStore(
            directory = directory,
            publishPath = { publishedPath = it },
            ioDispatcher = dispatcher,
            idProvider = { "old" },
        )
        val oldPrepared = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()

        val oldImport = async {
            store.replace { candidate ->
                candidate.writeText("old")
                oldPrepared.complete(Unit)
                releaseOld.await()
                true
            }
        }
        oldPrepared.await()

        store.clear()
        releaseOld.complete(Unit)
        val oldResult = oldImport.await()

        assertEquals(AppWallpaperStore.ReplaceResult.SUPERSEDED, oldResult)
        assertEquals(null, publishedPath)
        assertFalse(existing.exists())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
}
