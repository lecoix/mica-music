package com.mica.music.media

import androidx.media3.common.MediaItem

/**
 * App 进程内、跨 MediaController binder 的短暂导航意图。
 * 在 [androidx.media3.session.MediaController.setMediaItems] 尚未反映到服务队列前，
 * 让 [ServicePlaybackEngineCoordinator] 仍能按 UI 侧目标 songId / 队列出声。
 */
internal object PendingPlaybackNavigation {
    @Volatile
    private var payload: PendingNavigationPayload? = null

    data class NavigationOverride(
        val queue: PlaybackQueueSnapshot,
        val targetSongId: String?,
    )

    fun prepare(targetSongId: String, items: List<MediaItem>) {
        payload = PendingNavigationPayload(targetSongId, items)
    }

    fun clear() {
        payload = null
    }

    fun consumeNavigationOverride(): NavigationOverride? {
        val current = payload ?: return null
        payload = null
        return current.toNavigationOverride()
    }
}

internal data class PendingNavigationPayload(
    val targetSongId: String,
    val items: List<MediaItem>,
) {
    fun toNavigationOverride(): PendingPlaybackNavigation.NavigationOverride? {
        if (items.isEmpty()) return null
        val index = items.indexOfFirst { it.mediaId == targetSongId }
            .takeIf { it >= 0 }
            ?: 0
        return PendingPlaybackNavigation.NavigationOverride(
            queue = PlaybackQueueSnapshot(items, index, revision = Long.MAX_VALUE),
            targetSongId = targetSongId,
        )
    }
}
