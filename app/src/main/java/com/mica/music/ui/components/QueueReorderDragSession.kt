package com.mica.music.ui.components

internal data class QueueReorderCommit(
    val fromIndex: Int,
    val toIndex: Int,
)

/**
 * Presents one dragged item at [toIndex] without allocating a reordered queue.
 * Every visual-to-source lookup is O(1), regardless of queue size.
 */
internal data class QueueMoveProjection(
    val fromIndex: Int,
    val toIndex: Int,
) {
    fun sourceIndexAt(visualIndex: Int): Int = when {
        fromIndex == toIndex -> visualIndex
        fromIndex < toIndex && visualIndex in fromIndex until toIndex -> visualIndex + 1
        fromIndex < toIndex && visualIndex == toIndex -> fromIndex
        fromIndex > toIndex && visualIndex == toIndex -> fromIndex
        fromIndex > toIndex && visualIndex in (toIndex + 1)..fromIndex -> visualIndex - 1
        else -> visualIndex
    }
}

/** Collapses the many index crossings of one drag into one final queue mutation. */
internal class QueueReorderDragSession {
    private var originalIndex: Int? = null
    private var finalIndex: Int? = null

    fun recordPreviewMove(fromIndex: Int, toIndex: Int) {
        if (originalIndex == null) originalIndex = fromIndex
        finalIndex = toIndex
    }

    fun hasPendingPreview(): Boolean = originalIndex != null

    fun finish(): QueueReorderCommit? {
        val from = originalIndex
        val to = finalIndex
        originalIndex = null
        finalIndex = null
        return if (from != null && to != null && from != to) {
            QueueReorderCommit(fromIndex = from, toIndex = to)
        } else {
            null
        }
    }
}
