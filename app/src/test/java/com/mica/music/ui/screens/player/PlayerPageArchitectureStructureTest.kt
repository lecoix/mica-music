package com.mica.music.ui.screens.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPageArchitectureStructureTest {
    @Test
    fun nowPlayingScreenDoesNotReintroduceStableGeometryOrSpectrumOverrides() {
        val screen = File(findMainSourceRoot(), "com/mica/music/ui/screens/NowPlayingScreen.kt").readText()
        val forbidden = listOf(
            Regex("spectrumEnabled\\s*=\\s*false"),
            Regex("val\\s+playerLayoutWidth\\b"),
            Regex("val\\s+classicCoverSize\\s*=\\s*minOf"),
            Regex("val\\s+landscapeCoverSize\\s*=\\s*landscapePlan\\?\\.let"),
        )
        val violations = forbidden.filter { it.containsMatchIn(screen) }

        assertTrue(
            "NowPlayingScreen must consume PlayerPageFrame/LandscapePlayerPolicy authority instead of " +
                "recomputing stable geometry or spectrum policy: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun coverRenderersDoNotReadLayoutEngineInternalPhotoStackConstants() {
        val renderer = File(
            findMainSourceRoot(),
            "com/mica/music/ui/screens/NowPlayingCoverRenderers.kt",
        ).readText()
        val forbidden = listOf(
            "PlayerPageLayoutEngine.PhotoStackImmersiveScreenFraction",
            "PlayerPageLayoutEngine.PhotoStackArtworkInsetHorizontalFraction",
        )
        val violations = forbidden.filter(renderer::contains)

        assertTrue(
            "cover renderers must consume PhotoStackFrame geometry rather than LayoutEngine constants: " +
                violations,
            violations.isEmpty(),
        )
    }

    @Test
    fun particleTitleVisibilityDoesNotGateCoverRendererBranches() {
        val section = File(
            findMainSourceRoot(),
            "com/mica/music/ui/screens/NowPlayingCoverSection.kt",
        ).readText().replace("\r\n", "\n")

        val titleGuard = section.indexOf("if (particleFrame.normalLayerVisible) {")
        val coverFlowBranch = section.indexOf("if (frame.coverFlowStageActive) {", startIndex = titleGuard)
        val expectedBoundary =
            "onLongPress = onCoverLongPress,\n" +
                "                )\n" +
                "            }\n" +
                "            if (frame.coverFlowStageActive) {"

        assertTrue("particle title guard must exist", titleGuard >= 0)
        assertTrue("cover-flow branch must exist after particle title guard", coverFlowBranch > titleGuard)
        assertTrue(
            "particle title visibility must not gate cover-flow/photo-stack/standard renderer branches",
            section.substring(titleGuard, coverFlowBranch + "if (frame.coverFlowStageActive) {".length)
                .contains(expectedBoundary),
        )
    }

    @Test
    fun coverSectionDelegatesSpecializedRenderingToRendererOwners() {
        val section = File(
            findMainSourceRoot(),
            "com/mica/music/ui/screens/NowPlayingCoverSection.kt",
        ).readText()
        val requiredDelegates = listOf(
            "CoverFlowCoverRenderer(",
            "PhotoStackCoverRenderer(",
            "StandardOrParticleCoverRenderer(",
        )
        val missing = requiredDelegates.filterNot(section::contains)

        assertTrue(
            "NowPlayingCoverSection must remain a host over specialized cover renderers; missing=$missing",
            missing.isEmpty(),
        )
    }

    private fun findMainSourceRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        repeat(8) {
            listOf(
                File(current, "app/src/main/java"),
                File(current, "src/main/java"),
            ).firstOrNull { File(it, "com/mica/music/ui/screens").isDirectory }?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate app/src/main/java from ${System.getProperty("user.dir")}")
    }
}
