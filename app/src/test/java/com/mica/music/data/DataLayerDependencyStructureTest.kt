package com.mica.music.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLayerDependencyStructureTest {
    @Test
    fun dataLayerDoesNotImportPlaybackOrMediaImplementationPackages() {
        val dataRoot = File(findMainSourceRoot(), "com/mica/music/data")
        val forbiddenImport = Regex("^import com\\.mica\\.music\\.(media|playback)\\.")
        val violations = dataRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence()
                    .filter(forbiddenImport::containsMatchIn)
                    .map { line -> "${file.relativeTo(dataRoot).invariantSeparatorsPath}: $line" }
            }
            .toList()

        assertTrue(
            "data layer must not depend upward on media/playback implementations:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun mediaLayerDoesNotImportPlaybackApplicationPackage() {
        val mediaRoot = File(findMainSourceRoot(), "com/mica/music/media")
        val forbiddenImport = Regex("^import com\\.mica\\.music\\.playback\\.")
        val violations = mediaRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence()
                    .filter(forbiddenImport::containsMatchIn)
                    .map { line -> "${file.relativeTo(mediaRoot).invariantSeparatorsPath}: $line" }
            }
            .toList()

        assertTrue(
            "media layer must not depend upward on playback application code:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun libraryStoreWritePrimitivesStayOwnedByMusicLibraryBacking() {
        val dataRoot = File(findMainSourceRoot(), "com/mica/music/data")
        val backingPath = "library/MusicLibraryBacking.kt"
        val libraryFiles = sequenceOf(File(dataRoot, "MusicLibrary.kt")) +
            File(dataRoot, "library").walkTopDown().filter { it.isFile && it.extension == "kt" }
        val forbidden = listOf(
            "storeSyncMutex",
            "nextStoreRevision(",
            "isLatestStoreRevision(",
        )
        val violations = libraryFiles
            .filter { it.relativeTo(dataRoot).invariantSeparatorsPath != backingPath }
            .flatMap { file ->
                file.readLines().asSequence()
                    .filter { line -> forbidden.any(line::contains) }
                    .map { line -> "${file.relativeTo(dataRoot).invariantSeparatorsPath}: $line" }
            }
            .toList()

        assertTrue(
            "library store write primitives must stay inside MusicLibraryBacking:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    private fun findMainSourceRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        repeat(8) {
            listOf(
                File(current, "app/src/main/java"),
                File(current, "src/main/java"),
            ).firstOrNull { File(it, "com/mica/music/data").isDirectory }?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate app/src/main/java from ${System.getProperty("user.dir")}")
    }
}
