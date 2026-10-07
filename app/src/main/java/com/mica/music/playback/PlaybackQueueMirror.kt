package com.mica.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mica.music.data.Song
import com.mica.music.data.playback.ServiceRemoteSongSnapshot
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.media.SongMediaItemCodec

internal data class QueueOrderSignature(
    val mediaIds: List<String>,
    val remoteDescriptions: List<ServiceRemoteSongSnapshot?>,
)

internal data class QueueMirrorBuild(
    val signature: QueueOrderSignature,
    val songs: List<Song>?,
)

internal object PlaybackQueueMirror {
    fun snapshotItems(player: Player): List<MediaItem> {
        if (player.mediaItemCount <= 0) return emptyList()
        val items = ArrayList<MediaItem>(player.mediaItemCount)
        for (index in 0 until player.mediaItemCount) {
            val item = runCatching { player.getMediaItemAt(index) }.getOrNull()
                ?: return emptyList()
            items += item
        }
        return items
    }

    fun orderSignature(items: List<MediaItem>): QueueOrderSignature =
        QueueOrderSignature(
            items.map { it.mediaId },
            // Descriptions contain no artwork bytes or lyric payloads.
            items.map { ServiceRemoteSongSnapshot.fromMediaItem(it) },
        )

    fun rebuildSongs(
        items: List<MediaItem>,
        resolver: ((String) -> Song?)?,
    ): List<Song> = buildList(items.size) {
        for (item in items) {
            val song = resolveMirroredSong(item, resolver)
            if (song != null) add(song)
        }
    }

    fun buildIfChanged(
        items: List<MediaItem>,
        previousSignature: QueueOrderSignature?,
        localQueue: List<Song>,
        fallbackResolver: ((String) -> Song?)?,
    ): QueueMirrorBuild {
        val signature = orderSignature(items)
        if (signature == previousSignature) return QueueMirrorBuild(signature, null)
        val localSongsById = localQueue.associateBy { it.id }
        val resolver: (String) -> Song? = { id ->
            localSongsById[id] ?: fallbackResolver?.invoke(id)
        }
        return QueueMirrorBuild(
            signature = signature,
            songs = rebuildSongs(items, resolver),
        )
    }
}

/**
 * 本地完整 Song 优先；远程描述以可信会话为准，保留本地已加载的歌词、统计和播放策略。
 */
internal fun resolveMirroredSong(
    item: MediaItem,
    resolver: ((String) -> Song?)?,
): Song? {
    val mediaId = item.mediaId.takeIf { it.isNotBlank() } ?: return null
    val local = resolver?.invoke(mediaId)
    if (RemoteMediaIdCodec.isRemoteId(mediaId)) {
        val description = ServiceRemoteSongSnapshot.fromMediaItem(item) ?: return local
        if (local == null) return description.toSong()
        return local.copy(
            title = description.title,
            artist = description.artist,
            album = description.album,
            albumArtist = description.albumArtist,
            durationSec = description.durationSec,
            albumArtUri = description.albumArtUri,
            coverColorArgb = if (local.albumArtUri == description.albumArtUri) local.coverColorArgb else 0,
            fileName = description.fileName,
            sizeBytes = description.sizeBytes,
            year = description.year,
            trackNumber = description.trackNumber,
            discNumber = description.discNumber,
        )
    }
    return local ?: SongMediaItemCodec.decode(item)
}
