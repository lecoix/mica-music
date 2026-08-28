package com.mica.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mica.music.data.Song
import com.mica.music.data.playback.ServiceRemoteSongSnapshot
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.media.SongMediaItemCodec

internal data class QueueOrderSignature(
    val mediaIds: List<String>,
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
        QueueOrderSignature(items.map { it.mediaId })

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
        val localSongsById = localQueue.associateBy { it.id }
        val resolver: (String) -> Song? = { id ->
            localSongsById[id] ?: fallbackResolver?.invoke(id)
        }
        return QueueMirrorBuild(
            signature = signature,
            songs = if (signature == previousSignature) {
                null
            } else {
                rebuildSongs(items, resolver)
            },
        )
    }
}

/**
 * MediaItem 只承载播放/会话所需的轻量字段；曲库中的完整 Song（例如歌词）优先。
 */
internal fun resolveMirroredSong(
    item: MediaItem,
    resolver: ((String) -> Song?)?,
): Song? {
    val mediaId = item.mediaId.takeIf { it.isNotBlank() } ?: return null
    resolver?.invoke(mediaId)?.let { return it }
    if (RemoteMediaIdCodec.isRemoteId(mediaId)) {
        return ServiceRemoteSongSnapshot.fromMediaItem(item)?.toSong()
    }
    return SongMediaItemCodec.decode(item)
}
