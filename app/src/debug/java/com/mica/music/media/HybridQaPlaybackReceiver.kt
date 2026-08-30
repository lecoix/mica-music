package com.mica.music.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.mica.music.MicaApp
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.remote.AndroidTagLibRemoteTrackMetadataProbe
import com.mica.music.data.remote.RemoteArtworkRef
import com.mica.music.data.remote.RemoteArtworkUriCodec
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.smb.SmbException
import com.mica.music.data.remote.smb.SmbFailureKind
import com.mica.music.data.remote.smb.SmbLogin
import com.mica.music.data.remote.smb.SmbPathCodec
import com.mica.music.data.remote.smb.SmbSourceSync
import com.mica.music.data.remote.smb.SmbjSessionFactory
import com.mica.music.data.preferences.UsbHybridOutputMode
import com.mica.music.data.preferences.UsbHybridPreferences
import kotlinx.coroutines.runBlocking

/** Debug-only physical-validation control seam. Never packaged in Perf/Release. */
class HybridQaPlaybackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val action = intent.action.orEmpty()
        if (action == "${appContext.packageName}.debug.HYBRID_QA_SET_MODE_PREFERENCE") {
            runCatching {
                val mode = UsbHybridOutputMode.valueOf(checkNotNull(intent.getStringExtra("mode")))
                UsbHybridPreferences.setOutputMode(appContext, mode)
                Log.i(TAG, "complete action=$action mode=$mode")
            }.onFailure { error ->
                Log.e(TAG, "control failed action=$action", error)
            }
            pending.finish()
            return
        }
        if (action == "${appContext.packageName}.debug.SMB_QA_SYNC_METADATA") {
            val forceProbe = intent.getBooleanExtra("forceProbe", false)
            Log.i(TAG, "start action=$action forceProbe=$forceProbe")
            pending.finish()
            Thread {
                runCatching {
                    val app = appContext as MicaApp
                    runBlocking {
                        val source = app.remoteCatalogRepository.sources(enabledOnly = true)
                            .firstOrNull { it.type == RemoteSourceType.SMB }
                            ?: error("No enabled SMB source is configured")
                        val startedMs = SystemClock.elapsedRealtime()
                        val result = if (forceProbe) {
                            SmbSourceSync(
                                catalogRepository = app.remoteCatalogRepository,
                                credentialStore = app.remoteCredentialStore,
                                metadataProbe = AndroidTagLibRemoteTrackMetadataProbe(appContext),
                            ).sync(
                                sourceInstanceId = source.id,
                                allowMetadataReuse = false,
                            )
                        } else {
                            app.remoteSourceManager.syncSmb(source.id)
                        }
                        val tracks = app.remoteCatalogRepository.tracksForSource(source.id)
                        Log.i(
                            TAG,
                            "complete action=$action forceProbe=$forceProbe tracks=${result.trackCount} " +
                                "probed=${result.metadataProbedCount} reused=${result.metadataReusedCount} " +
                                "artists=${tracks.count { it.artist.isNotBlank() }} " +
                                "albums=${tracks.count { it.album.isNotBlank() }} " +
                                "durations=${tracks.count { it.durationSec > 0 }} " +
                                "artworks=${tracks.count { it.artworkOpaqueId.isNotBlank() }} " +
                                "embeddedArtworks=${tracks.count { RemoteEmbeddedArtworkIdCodec.decode(it.artworkOpaqueId) != null }} " +
                                "elapsedMs=${SystemClock.elapsedRealtime() - startedMs}",
                        )
                    }
                }.onFailure { error ->
                    Log.e(TAG, "control failed action=$action forceProbe=$forceProbe", error)
                }
            }.start()
            return
        }
        if (
            action == "${appContext.packageName}.debug.SMB_QA_READ_ARTWORK" ||
            action == "${appContext.packageName}.debug.SMB_QA_READ_EMBEDDED_ARTWORK"
        ) {
            val embeddedOnly = action.endsWith("SMB_QA_READ_EMBEDDED_ARTWORK")
            pending.finish()
            Thread {
                runCatching {
                    val app = appContext as MicaApp
                    runBlocking {
                        val source = app.remoteCatalogRepository.sources(enabledOnly = true)
                            .firstOrNull { it.type == RemoteSourceType.SMB }
                            ?: error("No enabled SMB source is configured")
                        val track = app.remoteCatalogRepository.tracksForSource(source.id)
                            .firstOrNull { candidate ->
                                candidate.artworkOpaqueId.isNotBlank() &&
                                    (!embeddedOnly || RemoteEmbeddedArtworkIdCodec.decode(candidate.artworkOpaqueId) != null)
                            }
                            ?: error(
                                if (embeddedOnly) {
                                    "SMB catalog has no published embedded artwork reference"
                                } else {
                                    "SMB catalog has no published artwork reference"
                                },
                            )
                        val uri = Uri.parse(
                            RemoteArtworkUriCodec.encode(RemoteArtworkRef(source.id, track.artworkOpaqueId)),
                        )
                        val startedMs = SystemClock.elapsedRealtime()
                        val bytes = checkNotNull(appContext.contentResolver.openInputStream(uri)).use { input ->
                            input.readBytes()
                        }
                        require(bytes.isNotEmpty()) { "SMB artwork provider returned no bytes" }
                        Log.i(
                            TAG,
                            "complete action=$action bytes=${bytes.size} embedded=$embeddedOnly " +
                                "elapsedMs=${SystemClock.elapsedRealtime() - startedMs}",
                        )
                    }
                }.onFailure { error ->
                    Log.e(TAG, "control failed action=$action", error)
                }
            }.start()
            return
        }
        if (action == "${appContext.packageName}.debug.SMB_QA_NEGATIVE_CONTRACT") {
            Thread {
                runCatching {
                    val app = appContext as MicaApp
                    runBlocking {
                        val source = app.remoteCatalogRepository.sources(enabledOnly = true)
                            .firstOrNull { it.type == RemoteSourceType.SMB }
                            ?: error("No enabled SMB source is configured")
                        val material = app.remoteCredentialStore.resolve(source.credentialRef)?.material
                            as? RemoteCredentialMaterial.UsernamePassword
                            ?: error("Configured SMB source has no username/password credential")
                        val endpoint = SmbPathCodec.parse(source.endpoint)
                        val validLogin = SmbLogin.parse(material.username, material.password)
                        val factory = SmbjSessionFactory()

                        val authStartedMs = SystemClock.elapsedRealtime()
                        val authFailure = runCatching {
                            factory.open(
                                endpoint,
                                SmbLogin.parse(material.username, material.password + "#mica-invalid"),
                            ).close()
                        }.exceptionOrNull()
                        val authElapsedMs = SystemClock.elapsedRealtime() - authStartedMs
                        require(authFailure is SmbException && authFailure.kind == SmbFailureKind.AUTH) {
                            "Wrong-password SMB attempt did not fail as AUTH"
                        }

                        val unreachablePort = if (endpoint.port == 65535) 65534 else endpoint.port + 1
                        val unreachableStartedMs = SystemClock.elapsedRealtime()
                        val unreachableFailure = runCatching {
                            factory.open(endpoint.copy(port = unreachablePort), validLogin).close()
                        }.exceptionOrNull()
                        val unreachableElapsedMs = SystemClock.elapsedRealtime() - unreachableStartedMs
                        require(unreachableFailure is SmbException && unreachableFailure.kind == SmbFailureKind.CONNECT) {
                            "Unreachable SMB attempt did not fail as CONNECT"
                        }

                        val recoveredEntryCount = factory.open(endpoint, validLogin).use { session ->
                            session.list(endpoint.serverPath()).size
                        }
                        Log.i(
                            TAG,
                            "complete action=$action authKind=AUTH authMs=$authElapsedMs " +
                                "unreachableKind=CONNECT unreachableMs=$unreachableElapsedMs recoveredEntries=$recoveredEntryCount",
                        )
                    }
                }.onFailure { error ->
                    Log.e(TAG, "control failed action=$action", error)
                }
                pending.finish()
            }.start()
            return
        }
        val future = MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, MicaMediaService::class.java)),
        ).buildAsync()
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrElse { error ->
                    Log.e(TAG, "controller-connect failed action=$action", error)
                    pending.finish()
                    return@addListener
                }
                if (action == "${appContext.packageName}.debug.HYBRID_QA_LOAD_LIBRARY_INDEX") {
                    val index = intent.getIntExtra("mediaIndex", 0)
                    Thread {
                        val songs = runCatching {
                            runBlocking { LibraryRepository(appContext).loadCached()?.songs.orEmpty() }
                        }.getOrElse { error ->
                            Log.e(TAG, "library-load failed action=$action", error)
                            controller.release()
                            pending.finish()
                            return@Thread
                        }
                        ContextCompat.getMainExecutor(appContext).execute {
                            try {
                                require(index in songs.indices) { "mediaIndex=$index libraryCount=${songs.size}" }
                                val items = songs.map(SongMediaItemCodec::encode)
                                controller.setMediaItems(items, index, 0L)
                                controller.prepare()
                                val handler = Handler(Looper.getMainLooper())
                                handler.postDelayed({
                                    controller.play()
                                    when (intent.getStringExtra("warmupIntentSequence")) {
                                        "PLAY_PAUSE" -> handler.postDelayed({ controller.pause() }, 250L)
                                        "PAUSE_PLAY" -> {
                                            handler.postDelayed({ controller.pause() }, 150L)
                                            handler.postDelayed({ controller.play() }, 300L)
                                        }
                                    }
                                    intent.getLongExtra("warmupSeekMs", -1L)
                                        .takeIf { it >= 0L }
                                        ?.let { seekMs -> handler.postDelayed({ controller.seekTo(seekMs) }, 200L) }
                                    Log.i(
                                        TAG,
                                        "complete action=$action index=$index libraryCount=${songs.size} " +
                                            "mediaItemCount=${controller.mediaItemCount} target=${songs[index].id}",
                                    )
                                    handler.postDelayed({
                                        controller.release()
                                        pending.finish()
                                    }, 1_500L)
                                }, 2_000L)
                            } catch (error: Throwable) {
                                Log.e(TAG, "control failed action=$action", error)
                                controller.release()
                                pending.finish()
                            }
                        }
                    }.start()
                    return@addListener
                }
                try {
                    when (action) {
                        "${appContext.packageName}.debug.HYBRID_QA_REPEAT_ONE" ->
                            controller.repeatMode = Player.REPEAT_MODE_ONE
                        "${appContext.packageName}.debug.HYBRID_QA_REPEAT_OFF" ->
                            controller.repeatMode = Player.REPEAT_MODE_OFF
                        "${appContext.packageName}.debug.HYBRID_QA_PLAY" -> controller.play()
                        "${appContext.packageName}.debug.HYBRID_QA_PAUSE" -> controller.pause()
                        "${appContext.packageName}.debug.HYBRID_QA_SEEK_MS" ->
                            controller.seekTo(intent.getLongExtra("positionMs", 0L).coerceAtLeast(0L))
                        "${appContext.packageName}.debug.HYBRID_QA_NEXT" -> controller.seekToNextMediaItem()
                        "${appContext.packageName}.debug.HYBRID_QA_SET_MODE" -> {
                            val mode = UsbHybridOutputMode.valueOf(checkNotNull(intent.getStringExtra("mode")))
                            UsbHybridPreferences.setOutputMode(appContext, mode)
                        }
                        "${appContext.packageName}.debug.HYBRID_QA_SELECT_INDEX" -> {
                            val index = intent.getIntExtra("mediaIndex", -1)
                            require(index in 0 until controller.mediaItemCount) {
                                "mediaIndex=$index count=${controller.mediaItemCount}"
                            }
                            controller.seekToDefaultPosition(index)
                            controller.play()
                        }
                        else -> error("unknown action=$action")
                    }
                    Log.i(
                        TAG,
                        "complete action=$action index=${controller.currentMediaItemIndex} " +
                            "positionMs=${controller.currentPosition} repeat=${controller.repeatMode} " +
                            "playWhenReady=${controller.playWhenReady}",
                    )
                } catch (error: Throwable) {
                    Log.e(TAG, "control failed action=$action", error)
                } finally {
                    controller.release()
                    pending.finish()
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private companion object {
        const val TAG = "MicaHybridQa"
    }
}
