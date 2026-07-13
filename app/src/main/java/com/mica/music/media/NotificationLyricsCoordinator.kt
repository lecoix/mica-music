package com.mica.music.media

import android.content.Context
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.data.Song
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsSession
import com.mica.music.data.local.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 在播放服务进程内按歌词行更新媒体通知元数据。
 * 仅当行索引变化时 [Player.replaceMediaItem]；播放中每 [TICK_MS] 检查一次，seek 后立即检查。
 */
@UnstableApi
internal class NotificationLyricsCoordinator(
    private val context: Context,
    private val player: Player,
    private val handler: Handler,
    private val songLoader: suspend (String) -> Song? = { id ->
        LibraryRepository(context.applicationContext).songById(id)
    },
) {
    private var released = false
    private var lastSignature: String? = null
    private var syncing = false
    private var sessionDocument: LyricsDocument? = null
    private var lyricsSession: LyricsSession? = null
    private val lyricsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val songCache = NotificationLyricsSongCache(
        scope = lyricsScope,
        handler = handler,
        loadSong = songLoader,
    )

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            maybeSync()
            if (player.isPlaying) {
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            handler.removeCallbacks(tick)
            if (playing) {
                handler.post(tick)
            } else {
                maybeSync()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastSignature = null
            handler.removeCallbacks(tick)
            maybeSync()
            if (player.isPlaying) {
                handler.postDelayed(tick, TICK_MS)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                maybeSync()
            }
        }
    }

    fun start() {
        player.addListener(listener)
        if (player.isPlaying) {
            handler.post(tick)
        }
    }

    fun release() {
        released = true
        handler.removeCallbacks(tick)
        player.removeListener(listener)
        lyricsScope.cancel()
        songCache.clear()
    }

    private fun maybeSync() {
        if (syncing || released) return
        val song = currentSong() ?: run {
            clearLyricMetadataIfNeeded()
            return
        }

        if (!LyricsPreferences.notificationLyricsEnabled(context)) {
            clearLyricMetadataIfNeeded(song)
            return
        }

        val display = NotificationLyrics.displayOptions(context)
        val session = sessionFor(song.lyricsDocument)
        val lyricIndex = NotificationLyrics.lyricIndexForPosition(
            session,
            player.currentPosition.toInt(),
        )
        if (lyricIndex < 0) {
            clearLyricMetadataIfNeeded(song)
            return
        }

        val displayLine = NotificationLyrics.lyricLineText(session.lyrics, lyricIndex, display) ?: return
        val signature = NotificationLyrics.signature(song.id, lyricIndex, displayLine)
        if (signature == lastSignature) return

        val item = player.currentMediaItem ?: return
        if (item.mediaId != song.id) return
        val metadata = NotificationLyrics.metadataWithLyric(song, displayLine, item.mediaMetadata)
            ?: return
        if (metadataMatches(item.mediaMetadata, metadata)) {
            lastSignature = signature
            return
        }

        val itemIndex = player.currentMediaItemIndex
        if (itemIndex < 0) return

        syncing = true
        try {
            player.replaceMediaItem(itemIndex, item.buildUpon().setMediaMetadata(metadata).build())
            lastSignature = signature
        } finally {
            syncing = false
        }
    }

    private fun clearLyricMetadataIfNeeded(song: Song? = currentSong()) {
        if (lastSignature == null) return
        val resolved = song ?: return
        restoreDefaultMetadata(resolved)
        lastSignature = null
    }

    private fun restoreDefaultMetadata(song: Song) {
        val item = player.currentMediaItem ?: return
        if (item.mediaId != song.id) return
        val itemIndex = player.currentMediaItemIndex
        if (itemIndex < 0) return
        val metadata = NotificationLyrics.defaultPlaybackMetadata(song, item.mediaMetadata)
        if (metadataMatches(item.mediaMetadata, metadata)) return
        syncing = true
        try {
            player.replaceMediaItem(itemIndex, item.buildUpon().setMediaMetadata(metadata).build())
        } finally {
            syncing = false
        }
    }

    private fun currentSong(): Song? {
        val item = player.currentMediaItem ?: return null
        val decoded = SongMediaItemCodec.decode(item) ?: return null
        return songCache.songWithLyrics(decoded, SongMediaItemCodec.lyricsRevision(item)) {
            if (!released) maybeSync()
        }
    }

    private fun sessionFor(document: LyricsDocument): LyricsSession {
        if (sessionDocument !== document) {
            sessionDocument = document
            lyricsSession = LyricsSession(document)
        }
        return checkNotNull(lyricsSession)
    }

    private fun metadataMatches(current: MediaMetadata, target: MediaMetadata): Boolean =
        current.title?.toString() == target.title?.toString() &&
            current.displayTitle?.toString() == target.displayTitle?.toString() &&
            current.artist?.toString() == target.artist?.toString()

    private companion object {
        /** 与播放页普通歌词轮询一致；仅检查索引，行未变时不 replace。 */
        const val TICK_MS = 500L
    }
}
