package com.mica.music.ui.components

internal data class QueueReorderCommit(
    val fromIndex: Int,
    val toIndex: Int,
)

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
