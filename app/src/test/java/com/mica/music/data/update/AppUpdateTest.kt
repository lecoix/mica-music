package com.mica.music.data.update

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateTest {

    @Test
    fun manifestKeepsDownloadMirrors() {
        val manifest = manifest()

        assertEquals("0.2.5.0", manifest.versionName)
        assertEquals(49L, manifest.versionCode)
        assertEquals("https://123.example/update", manifest.domesticUrl)
        assertEquals("https://github.com/lecoix/mica-music/releases/latest", manifest.githubUrl)
    }

    @Test
    fun newerVersionIsDetectedByVersionCode() {
        val result = AppUpdateResult(
            currentVersion = AppVersion("0.2.4.1", 48L),
            manifest = manifest(versionCode = 49L),
        )

        assertTrue(result.hasUpdate)
    }

    @Test
    fun staleCheckCannotPublishOverNewerCheck() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val callCount = AtomicInteger()
        val coordinator = AppUpdateCoordinator {
            if (callCount.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                manifest(versionCode = 49L, versionName = "old")
            } else {
                manifest(versionCode = 50L, versionName = "new")
            }.let { AppUpdateResult(AppVersion("0.2.4.1", 48L), it) }
        }

        coordinator.check(this)
        firstStarted.await()
        coordinator.check(this)
        advanceUntilIdle()

        releaseFirst.complete(Unit)
        advanceUntilIdle()

        val state = coordinator.state as AppUpdateState.Success
        assertEquals("new", state.result.manifest.versionName)
        assertEquals(50L, state.result.manifest.versionCode)
    }

    private fun manifest(
        versionCode: Long = 49L,
        versionName: String = "0.2.5.0",
    ) = AppUpdateManifest(
        versionName = versionName,
        versionCode = versionCode,
        changelog = "测试",
        domesticUrl = "https://123.example/update",
        githubUrl = "https://github.com/lecoix/mica-music/releases/latest",
    )
}
