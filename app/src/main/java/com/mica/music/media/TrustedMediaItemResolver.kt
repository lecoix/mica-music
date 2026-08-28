package com.mica.music.media

import androidx.media3.common.MediaItem
import com.mica.music.data.Song
import com.mica.music.data.TransientPlaybackCatalog
import com.mica.music.data.remote.RemoteMediaIdCodec

internal data class TrustedMediaItemsResolution(
    val mediaItems: List<MediaItem>,
    val resolvedStartIndex: Int?,
)

/**
 * Rebuilds controller-provided media items from Mica-owned IDs.
 *
 * The incoming URI, metadata and extras are deliberately ignored. Transient IDs never fall back
 * to the persistent library, which keeps external songs process/session-only. Remote IDs are
 * resolved by a trusted process-side provider instead of accepting a caller-supplied URI.
 */
internal class TrustedMediaItemResolver(
    private val transientSongById: (String) -> Song?,
    private val librarySongsById: suspend (List<String>) -> Map<String, Song>,
    private val remoteMediaItemsById: suspend (List<String>) -> Map<String, MediaItem> = { emptyMap() },
    private val mediaItemFactory: (Song) -> MediaItem = { song -> SongMediaItemCodec.encode(song) },
) {
    suspend fun resolve(
        requestedItems: List<MediaItem>,
        requestedStartIndex: Int? = null,
    ): TrustedMediaItemsResolution {
        val libraryIds = requestedItems
            .asSequence()
            .map(MediaItem::mediaId)
            .filter(String::isNotBlank)
            .filterNot(TransientPlaybackCatalog::isTransientId)
            .filterNot(RemoteMediaIdCodec::isRemoteId)
            .distinct()
            .toList()
        val remoteIds = requestedItems
            .asSequence()
            .map(MediaItem::mediaId)
            .filter(String::isNotBlank)
            .filter(RemoteMediaIdCodec::isRemoteId)
            .distinct()
            .toList()
        val librarySongs = librarySongsById(libraryIds)
        val remoteItems = remoteMediaItemsById(remoteIds)
        val resolvedItems = ArrayList<MediaItem>(requestedItems.size)
        var resolvedStartIndex: Int? = null

        requestedItems.forEachIndexed { requestedIndex, requestedItem ->
            val id = requestedItem.mediaId.takeIf(String::isNotBlank) ?: return@forEachIndexed
            val resolvedItem = when {
                TransientPlaybackCatalog.isTransientId(id) -> transientSongById(id)?.let(mediaItemFactory)
                RemoteMediaIdCodec.isRemoteId(id) -> remoteItems[id]
                else -> librarySongs[id]?.let(mediaItemFactory)
            } ?: return@forEachIndexed
            if (requestedIndex == requestedStartIndex) {
                resolvedStartIndex = resolvedItems.size
            }
            resolvedItems += resolvedItem
        }

        return TrustedMediaItemsResolution(
            mediaItems = resolvedItems,
            resolvedStartIndex = when {
                resolvedItems.isEmpty() -> null
                resolvedStartIndex != null -> resolvedStartIndex
                else -> 0
            },
        )
    }
}
