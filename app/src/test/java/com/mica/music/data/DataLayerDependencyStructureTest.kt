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
