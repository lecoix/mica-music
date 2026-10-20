package com.mica.music.media

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mica.music.BuildConfig
import com.mica.music.MicaApp
import com.mica.music.data.lx.AndroidLxScriptStore
import com.mica.music.data.lx.LxSourceOwner
import com.mica.music.data.lx.QuickJsLxRuntime
import com.mica.music.testutil.ContractTestSupport.await
import com.mica.music.testutil.ContractTestSupport.connectMediaService
import com.mica.music.testutil.ContractTestSupport.createSilentWav
import com.mica.music.testutil.ContractTestSupport.onMain
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real Android/QuickJS + HTTP + MediaSession test; never target a user's existing install. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LxSourcePlaybackContractTest {
    @Test fun importRestartResolveAndPlayThroughService() = runBlocking {
        check(BuildConfig.APPLICATION_ID == "com.mica.music.lx") { "Requires isolated LX experiment package" }
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        val wav = createSilentWav(app.cacheDir, "lx", 5)
        val audio = wav.readBytes()
        val requests = AtomicInteger()
        val server = ServerSocket(0)
        val host = "http://127.0.0.1:${server.localPort}"
        val thread = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val body = if (path == "/resolve") "{\"url\":\"$host/audio.wav\"}".toByteArray() else audio
                        requests.incrementAndGet()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nContent-Type: ${if (path == "/resolve") "application/json" else "audio/wav"}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                } catch (_: Exception) { }
            }
        }.apply { isDaemon = true; start() }
        val script = """
            /**
             * @name Android contract fixture
             */
            const u = lx.utils;
            if (u.crypto.md5('abc') !== '900150983cd24fb0d6963f7d28e17f72') throw Error('md5');
            if (u.buffer.bufToString(u.buffer.from('00ff80', 'hex'), 'base64') !== 'AP+A') throw Error('binary');
            if (u.buffer.bufToString(u.buffer.from('音乐🎵')) !== '音乐🎵') throw Error('utf8');
            lx.on(lx.EVENT_NAMES.request, ({info}) => new Promise((resolve, reject) => {
              setTimeout(() => lx.request(info.musicInfo.url, {method:'get'}, (err, response, body) => {
                if(err) reject(err); else resolve(body.url);
              }), 10);
            }));
            lx.send(lx.EVENT_NAMES.inited, {sources:{wy:{type:'music', actions:['musicUrl'], qualitys:['flac']}}});
        """.trimIndent()
        val store = AndroidLxScriptStore(app)
        val initial = LxSourceOwner(store) { QuickJsLxRuntime(app) }
        var controller: androidx.media3.session.MediaController? = null
        try {
            withTimeout(20_000) { initial.importScript { script } }
            initial.close()
            assertEquals(script, store.read()!!.raw)
            app.lxSourceOwner.restore()
            assertEquals(listOf("flac"), app.lxSourceOwner.state.value.platforms["wy"])
            val track = app.lxSourceOwner.prepareTrial("wy", "flac", "{\"source\":\"wy\",\"name\":\"Contract silence\",\"url\":\"$host/resolve\"}")
            val connected = connectMediaService(app)
            controller = connected
            onMain {
                connected.setMediaItem(RemoteMediaItemCodec.encode(track))
                connected.prepare()
                connected.play()
            }
            await("LX audio playback", 25_000) { onMain { connected.playerError == null && connected.playbackState == Player.STATE_READY && connected.currentPosition > 100 } }
            assertTrue(requests.get() >= 2)
            assertEquals(track.mediaId, onMain { connected.currentMediaItem!!.mediaId })
        } finally {
            controller?.let { onMain { it.stop(); it.clearMediaItems(); it.release() } }
            initial.close()
            app.lxSourceOwner.delete()
            server.close(); thread.join(1000); wav.delete()
        }
    }
}
