package com.mica.music.playback

internal sealed class PlaybackQueueNavigationPlan {
    abstract val serviceIndex: Int

    data class SeekAligned(override val serviceIndex: Int) : PlaybackQueueNavigationPlan()
    data class CarryQueuePayload(override val serviceIndex: Int) : PlaybackQueueNavigationPlan()
    data class SyncQueue(override val serviceIndex: Int) : PlaybackQueueNavigationPlan()
}

internal object PlaybackQueueNavigation {
    fun plan(
        queueIds: List<String>,
        requestedIndex: Int,
        songId: String,
        currentMediaId: String?,
        serviceItemCount: Int,
        serviceMediaIdAt: (Int) -> String?,
    ): PlaybackQueueNavigationPlan? {
        if (queueIds.getOrNull(requestedIndex) != songId) return null
        val seekIndex = queueIds.indexOfFirst { it == songId }
            .takeIf { it >= 0 }
            ?: requestedIndex
        val targetAlreadyAligned = serviceItemCount == queueIds.size &&
            serviceMediaIdAt(seekIndex) == songId
        return when {
            targetAlreadyAligned -> PlaybackQueueNavigationPlan.SeekAligned(seekIndex)
            currentMediaId != null && currentMediaId != songId ->
                PlaybackQueueNavigationPlan.CarryQueuePayload(seekIndex)
            else -> PlaybackQueueNavigationPlan.SyncQueue(seekIndex)
        }
    }
}
