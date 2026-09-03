package com.mica.music.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.mica.music.media.SongMediaItemCodec
import com.mica.music.util.DiagnosticLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class PlaybackWidgetCoordinator(
    context: Context,
    private val player: Player,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val artworkGeneration = AtomicLong(0L)
    private val artworkWriteLock = Any()
    private var released = false

    private val publishRunnable = Runnable { publishNow() }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = schedulePublish()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = schedulePublish()
        override fun onIsPlayingChanged(isPlaying: Boolean) = schedulePublish()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = schedulePublish()
        override fun onPlaybackStateChanged(playbackState: Int) = schedulePublish()
    }

    fun start() {
        player.addListener(listener)
        publishNow()
    }

    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacks(publishRunnable)
        player.removeListener(listener)
        artworkGeneration.incrementAndGet()
        ioScope.cancel()
    }

    private fun schedulePublish() {
        if (released) return
        mainHandler.removeCallbacks(publishRunnable)
        mainHandler.postDelayed(publishRunnable, UPDATE_DEBOUNCE_MS)
    }

    private fun publishNow() {
        if (released) return
        val item = player.currentMediaItem
        val song = item?.let(SongMediaItemCodec::decode)
        val metadata = item?.mediaMetadata
        val artworkKey = song?.albumArtUri
            ?.takeIf(String::isNotBlank)
            ?: metadata?.artworkUri?.toString().orEmpty()
        val previous = WidgetPlaybackStateStore.load(appContext)
        val reusableArtwork = previous.artworkPath.takeIf {
            previous.artworkKey == artworkKey && !it.isNullOrBlank() && File(it).isFile
        }
        val snapshot = WidgetPlaybackSnapshot(
            mediaId = item?.mediaId.orEmpty(),
            title = song?.title?.takeIf(String::isNotBlank)
                ?: metadata?.title?.toString().orEmpty(),
            artist = song?.artist?.takeIf(String::isNotBlank)
                ?: metadata?.artist?.toString().orEmpty(),
            album = song?.album?.takeIf(String::isNotBlank)
                ?: metadata?.albumTitle?.toString().orEmpty(),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            artworkKey = artworkKey,
            artworkPath = reusableArtwork,
        )
        if (snapshot != previous) {
            WidgetPlaybackStateStore.save(appContext, snapshot)
            DiagnosticLog.event(
                "PlaybackWidget",
                "state-publish media=${snapshot.mediaId.takeLast(12)} " +
                    "playing=${snapshot.isPlaying} buffering=${snapshot.isBuffering}",
            )
            requestWidgetUpdate(snapshot)
        }
        if (artworkKey.isNotBlank() && reusableArtwork == null) {
            resolveArtwork(artworkKey, snapshot)
        }
    }

    private fun resolveArtwork(artworkKey: String, baseSnapshot: WidgetPlaybackSnapshot) {
        val generation = artworkGeneration.incrementAndGet()
        ioScope.launch {
            val bitmap = decodeArtwork(artworkKey) ?: return@launch
            val scaled = bitmap.scaleDown(MAX_ARTWORK_SIZE_PX)
            val output = File(appContext.cacheDir, "playback_widget/artwork.png")
            val writeSucceeded = synchronized(artworkWriteLock) {
                if (generation != artworkGeneration.get() || released) {
                    false
                } else {
                    output.parentFile?.mkdirs()
                    runCatching {
                        FileOutputStream(output, false).use { stream ->
                            scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        }
                    }.getOrDefault(false)
                }
            }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            if (!writeSucceeded || generation != artworkGeneration.get() || released) return@launch

            val latest = WidgetPlaybackStateStore.load(appContext)
            if (latest.mediaId != baseSnapshot.mediaId || latest.artworkKey != artworkKey) return@launch
            val updated = latest.copy(artworkPath = output.absolutePath)
            WidgetPlaybackStateStore.save(appContext, updated)
            requestWidgetUpdate(updated)
        }
    }

    private fun decodeArtwork(raw: String): Bitmap? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return runCatching {
            when (uri.scheme?.lowercase()) {
                "content", "file" -> appContext.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                null, "" -> FileInputStream(raw).use(BitmapFactory::decodeStream)
                else -> null
            }
        }.getOrNull()
    }

    private fun requestWidgetUpdate(snapshot: WidgetPlaybackSnapshot) {
        ioScope.launch {
            runCatching {
                val manager = GlanceAppWidgetManager(appContext)
                val targets = listOf(
                    PlaybackWidgetTarget(
                        widget = MicaPlaybackWidget(),
                        widgetClass = MicaPlaybackWidget::class.java,
                    ),
                    PlaybackWidgetTarget(
                        widget = MicaArtworkPlaybackWidget(),
                        widgetClass = MicaArtworkPlaybackWidget::class.java,
                    ),
                    PlaybackWidgetTarget(
                        widget = MicaMinimalSquarePlaybackWidget(),
                        widgetClass = MicaMinimalSquarePlaybackWidget::class.java,
                    ),
                )
                var renderedCount = 0
                targets.forEach { target ->
                    val ids = manager.getGlanceIds(target.widgetClass)
                    renderedCount += ids.size
                    ids.forEach { id ->
                        updateAppWidgetState(appContext, id) { preferences ->
                            WidgetPlaybackGlanceState.write(preferences, snapshot)
                        }
                        target.widget.update(appContext, id)
                    }
                }
                DiagnosticLog.event(
                    "PlaybackWidget",
                    "render-requested widgets=$renderedCount media=${snapshot.mediaId.takeLast(12)} " +
                        "playing=${snapshot.isPlaying}",
                )
            }.onFailure { error ->
                DiagnosticLog.event(
                    "PlaybackWidget",
                    "render-failed media=${snapshot.mediaId.takeLast(12)} " +
                        "error=${error.javaClass.simpleName}",
                    error,
                )
            }
        }
    }

    private fun Bitmap.scaleDown(maxSizePx: Int): Bitmap {
        if (width <= maxSizePx && height <= maxSizePx) return this
        val scale = maxSizePx.toFloat() / max(width, height).toFloat()
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private companion object {
        const val UPDATE_DEBOUNCE_MS = 180L
        const val MAX_ARTWORK_SIZE_PX = 512
    }
}

private data class PlaybackWidgetTarget(
    val widget: GlanceAppWidget,
    val widgetClass: Class<out GlanceAppWidget>,
)
