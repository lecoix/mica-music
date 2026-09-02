package com.mica.music.playback

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTestPackageStructureTest {
    @Test
    fun dataRootDoesNotOwnPlaybackImplementationTests() {
        val testRoot = findTestSourceRoot()
        val dataRoot = File(testRoot, "com/mica/music/data")
        val playbackImport = Regex("^import com\\.mica\\.music\\.playback\\.")
        val violations = dataRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence()
                    .filter(playbackImport::containsMatchIn)
                    .map { line -> "${file.name}: $line" }
            }
            .toList()

        assertTrue(
            "playback implementation tests belong under com.mica.music.playback; " +
                "root data tests must not import playback implementations:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    private fun findTestSourceRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        repeat(8) {
            listOf(
                File(current, "app/src/test/java"),
                File(current, "src/test/java"),
            ).firstOrNull { File(it, "com/mica/music/playback").isDirectory }?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate app/src/test/java from ${System.getProperty("user.dir")}")
    }
}
