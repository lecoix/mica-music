package com.mica.music.ui.screens.player.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mica.music.data.scanner.VideoCoverPosterStore
import com.mica.music.util.DiagnosticLog

@Composable
internal fun VideoAlbumCoverHost(
    uri: String,
    isPlaying: Boolean,
    onPlaybackError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return

    var poster by remember(uri) { mutableStateOf(VideoCoverPosterStore.get(context, uri)) }
    var videoReady by remember(uri) { mutableStateOf(false) }

    Box(modifier = modifier) {
        val frame = poster
        if (frame != null && !frame.isRecycled && !videoReady) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> VideoAlbumCoverView(ctx) },
            update = { view ->
                view.onPlaybackError = onPlaybackError
                view.onFirstFrame = { captured ->
                    if (captured != null) {
                        VideoCoverPosterStore.put(context, uri, captured)
                        poster = captured
                    }
                    videoReady = true
                }
                view.setSource(uri)
                view.setPlaying(isPlaying)
            },
            onRelease = VideoAlbumCoverView::release,
        )
    }
}

@UnstableApi
private class VideoAlbumCoverView(context: Context) : FrameLayout(context), Player.Listener {
    private val textureView = TextureView(context).also {
        it.alpha = 0f
        addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    private var player: ExoPlayer? = null
    private var source: String? = null
    private var videoSize: VideoSize = VideoSize.UNKNOWN

    var onPlaybackError: () -> Unit = {}
    var onFirstFrame: (Bitmap?) -> Unit = {}
    private var wantPlaying = false
    private var reportedFirstFrame = false

    fun setSource(uri: String) {
        if (source == uri && player != null) return
        releasePlayer()
        source = uri
        reportedFirstFrame = false
        textureView.alpha = 0f
        player = ExoPlayer.Builder(context).build().also { exoPlayer ->
            exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
            exoPlayer.volume = 0f
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            exoPlayer.addListener(this)
            exoPlayer.setVideoTextureView(textureView)
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.playWhenReady = true
            exoPlayer.prepare()
        }
    }

    fun setPlaying(playing: Boolean) {
        wantPlaying = playing
        if (textureView.alpha >= 1f) {
            player?.playWhenReady = playing
        } else {
            player?.playWhenReady = true
        }
    }

    override fun onRenderedFirstFrame() {
        if (!reportedFirstFrame) {
            reportedFirstFrame = true
            onFirstFrame(captureFrame())
        }
        textureView.alpha = 1f
        player?.playWhenReady = wantPlaying
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        this.videoSize = videoSize
        updateCenterCrop()
    }

    override fun onPlayerError(error: PlaybackException) {
        DiagnosticLog.event(
            "VideoCover",
            "playback-failed uri=$source code=${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName}",
        )
        textureView.alpha = 0f
        onPlaybackError()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateCenterCrop()
    }

    fun captureFrame(): Bitmap? {
        if (!textureView.isAvailable) return null
        val viewW = width
        val viewH = height
        if (viewW <= 0 || viewH <= 0) return null
        val videoW = videoSize.width
        val videoH = videoSize.height
        if (videoW <= 0 || videoH <= 0) return null
        return try {
            val raw = textureView.getBitmap(videoW, videoH) ?: return null
            val cropped = centerCropVideoFrame(
                raw = raw,
                viewWidth = viewW,
                viewHeight = viewH,
                pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
            )
            if (cropped !== raw) {
                raw.recycle()
            }
            cropped
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun updateCenterCrop() {
        val sourceWidth = videoSize.width * videoSize.pixelWidthHeightRatio
        val sourceHeight = videoSize.height.toFloat()
        if (width <= 0 || height <= 0 || sourceWidth <= 0f || sourceHeight <= 0f) return
        val sourceAspect = sourceWidth / sourceHeight
        val viewAspect = width.toFloat() / height
        val scaleX = if (sourceAspect > viewAspect) sourceAspect / viewAspect else 1f
        val scaleY = if (sourceAspect < viewAspect) viewAspect / sourceAspect else 1f
        textureView.setTransform(
            Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) },
        )
    }

    fun release() {
        releasePlayer()
        source = null
        textureView.alpha = 0f
        reportedFirstFrame = false
    }

    private fun releasePlayer() {
        player?.let { exoPlayer ->
            exoPlayer.removeListener(this)
            exoPlayer.clearVideoTextureView(textureView)
            exoPlayer.release()
        }
        player = null
        videoSize = VideoSize.UNKNOWN
    }
}
