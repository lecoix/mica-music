package com.mica.music.media

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.BuildConfig
import com.mica.music.MicaApp
import com.mica.music.testutil.ContractTestSupport.await
import com.mica.music.testutil.ContractTestSupport.connectMediaService
import com.mica.music.testutil.ContractTestSupport.onMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Live QQ branch smoke for older multi-platform LX sources. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LxLegacySourceQqPlaybackSmokeTest {
    private fun readAsset(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("lx-real/$name")
            .bufferedReader()
            .use { it.readText() }

    private val musicInfo = """{"source":"tx","songmid":"002tjmVF1Ce09U","strMediaMid":"000aJEne4IpPYR","name":"卡农 (经典治愈版)","singer":"失重"}"""

    private fun runLiveSmoke(assetName: String, label: String) = runBlocking {
        check(BuildConfig.APPLICATION_ID == "com.mica.music.lx") { "Requires isolated LX experiment package" }
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        var controller: androidx.media3.session.MediaController? = null
        try {
            withTimeout(25_000) { app.lxSourceOwner.importScript { readAsset(assetName) } }
            val platforms = app.lxSourceOwner.state.value.platforms
            check("128k" in platforms["tx"].orEmpty()) { "$label did not publish tx/128k: $platforms" }
            val track = app.lxSourceOwner.prepareTrial("tx", "128k", musicInfo)
            controller = connectMediaService(app)
            onMain {
                controller!!.setMediaItem(RemoteMediaItemCodec.encode(track))
                controller!!.prepare()
                controller!!.play()
            }
            await("$label QQ live playback", 45_000) {
                onMain {
                    controller!!.playerError?.let { err ->
                        throw AssertionError("$label playerError=${err.errorCodeName}: ${err.message}", err)
                    }
                    controller!!.playbackState == Player.STATE_READY && controller!!.currentPosition > 250
                }
            }
            Log.i("LX_QQ_SMOKE", "$label PASS mediaId=${track.mediaId} position=${onMain { controller!!.currentPosition }}")
            assertEquals(track.mediaId, onMain { controller!!.currentMediaItem!!.mediaId })
        } catch (t: Throwable) {
            Log.e("LX_QQ_SMOKE", "$label FAIL ${t.javaClass.simpleName}: ${t.message}", t)
            throw t
        } finally {
            controller?.let { c -> runCatching { onMain { c.stop(); c.clearMediaItems(); c.release() } } }
            runCatching { app.lxSourceOwner.delete() }
        }
    }

    @Test fun feicaiQqRealSongPlays() = runLiveSmoke("feicai-internal-source.js", "Feicai v1.1.2 tx")
    @Test fun freeListenQqRealSongPlays() = runLiveSmoke("freelisten.js", "FreeListen v1.1.1 tx")
}
