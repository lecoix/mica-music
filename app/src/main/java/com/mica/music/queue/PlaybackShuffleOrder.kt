package com.mica.music.queue

import kotlin.random.Random

/** Deterministic shuffle shared by the UI queue model and the playback service. */
internal object PlaybackShuffleOrder {
    fun orderedIds(
        ids: List<String>,
        currentId: String?,
        seed: Long,
    ): List<String> {
        val distinct = ids.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyList()
        val safeCurrent = currentId?.takeIf(distinct::contains) ?: distinct.first()
        val remaining = distinct
            .asSequence()
            .filterNot { it == safeCurrent }
            // Canonicalize first so the same media-id set produces the same shuffle even if
            // the controller and service currently expose different physical queue orders.
            .sorted()
            .toMutableList()
        val random = Random(seed)
        for (index in remaining.lastIndex downTo 1) {
            val swap = random.nextInt(index + 1)
            val value = remaining[index]
            remaining[index] = remaining[swap]
            remaining[swap] = value
        }
        return buildList(distinct.size) {
            add(safeCurrent)
            addAll(remaining)
        }
    }

    fun physicalIndices(
        physicalIds: List<String>,
        currentId: String?,
        seed: Long,
    ): IntArray {
        val indexById = physicalIds.withIndex().associate { it.value to it.index }
        return orderedIds(physicalIds, currentId, seed)
            .mapNotNull(indexById::get)
            .toIntArray()
    }
}
