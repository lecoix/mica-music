package com.mica.music.media

import androidx.media3.common.MediaItem

/**
 * App 进程内、跨 MediaController binder 的短暂导航意图。
 * 在 [androidx.media3.session.MediaController.setMediaItems] 尚未反映到服务队列前，
 * 让 [ServicePlaybackEngineCoordinator] 仍能按 UI 侧目标 songId / 队列出声。
 */
internal object PendingPlaybackNavigation {
    @Volatile
    private var targetSongId: String? = null

    @Volatile
    private var queueItems: List<MediaItem>? = null

    data class NavigationOverride(
        val queue: PlaybackQueueSnapshot,
        val targetSongId: String?,
    )

    fun prepare(targetSongId: String, items: List<MediaItem>) {
        this.targetSongId = targetSongId
        this.queueItems = items
    }

    fun clear() {
        targetSongId = null
        queueItems = null
    }

    fun consumeNavigationOverride(): NavigationOverride? {
        val items = queueItems ?: return null
        val songId = targetSongId
        queueItems = null
        targetSongId = null
        if (items.isEmpty()) return null
        val index = songId
            ?.let { id -> items.indexOfFirst { it.mediaId == id }.takeIf { it >= 0 } }
            ?: 0
        return NavigationOverride(
            queue = PlaybackQueueSnapshot(items, index, revision = Long.MAX_VALUE),
            targetSongId = songId,
        )
    }
}
