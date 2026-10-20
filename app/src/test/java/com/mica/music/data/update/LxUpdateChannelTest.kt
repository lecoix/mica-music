package com.mica.music.data.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LxUpdateChannelTest {
    @Test fun stableManifestAndLatestDownloadAreRejected() = runTest {
        for (manifest in listOf(
            AppUpdateManifest("0.4.4", 59, "", "", "https://github.com/lecoix/mica-music/releases/tag/v0.4.4"),
            AppUpdateManifest("0.4.4-lx.2", 2, "", "", "https://github.com/lecoix/mica-music/releases/latest"),
        )) {
            val repo = AppUpdateRepository("", "https://example.org/lx.json") { manifest }
            assertTrue(runCatching { repo.check(AppVersion("0.4.4-lx.1", 1)) }.isFailure)
        }
    }
    @Test fun dedicatedLxReleaseIsAccepted() = runTest {
        val manifest = AppUpdateManifest("0.4.4-lx.2", 2, "", "", "https://github.com/lecoix/mica-music/releases/tag/lx-v0.4.4-lx.2")
        val repo = AppUpdateRepository("", "https://example.org/lx.json") { manifest }
        assertTrue(repo.check(AppVersion("0.4.4-lx.1", 1)).hasUpdate)
    }
}
