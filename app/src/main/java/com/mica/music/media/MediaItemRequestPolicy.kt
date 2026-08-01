package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Identifies the empty setMediaItem request emitted by controller test tools. */
internal object MediaItemRequestPolicy {
    fun isEmptyRequest(item: MediaItem): Boolean {
        val request = item.requestMetadata
        return item.mediaId.isBlank() &&
            item.localConfiguration == null &&
            item.mediaMetadata == MediaMetadata.EMPTY &&
            request.mediaUri == null &&
            request.searchQuery.isNullOrBlank() &&
            request.extras == null
    }
}
