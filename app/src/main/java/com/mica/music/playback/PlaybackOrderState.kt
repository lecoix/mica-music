package com.mica.music.playback

import kotlin.random.Random

internal data class PlaybackOrderState(
    val sourceIds: List<String> = emptyList(),
    val playbackIds: List<String> = emptyList(),
    val currentId: String? = null,
    val shuffleEnabled: Boolean = false,
    val shuffleSeed: Long? = null,
) {
    val currentOrderIndex: Int
        get() = currentId
            ?.let { playbackIds.indexOf(it) }
            ?.takeIf { it >= 0 }
            ?: 0

    fun nextId(manualSkip: Boolean, repeatAll: Boolean, repeatOne: Boolean): String? {
        if (playbackIds.isEmpty()) return null
        val current = currentOrderIndex.coerceIn(0, playbackIds.lastIndex)
        return when {
            repeatOne && !manualSkip -> playbackIds[current]
            current < playbackIds.lastIndex -> playbackIds[current + 1]
            repeatAll || manualSkip -> playbackIds.first()
            // Natural completion consumes one playback order exactly once. Returning null keeps
            // the terminal boundary explicit so callers cannot mistake the last item for a replay.
            else -> null
        }
    }

    fun previousId(repeatAll: Boolean): String? {
        if (playbackIds.isEmpty()) return null
        val current = currentOrderIndex.coerceIn(0, playbackIds.lastIndex)
        return when {
            current > 0 -> playbackIds[current - 1]
            repeatAll -> playbackIds.last()
            else -> playbackIds[current]
        }
    }

    fun moveTo(id: String): PlaybackOrderState =
        if (playbackIds.contains(id)) copy(currentId = id) else this

    fun withQueue(
        ids: List<String>,
        preserveId: String? = currentId,
        random: Random = Random.Default,
    ): PlaybackOrderState {
        val distinctIds = ids.distinct()
        val nextCurrent = preserveId
            ?.takeIf { distinctIds.contains(it) }
            ?: distinctIds.firstOrNull()
        return fromSource(
            sourceIds = distinctIds,
            currentId = nextCurrent,
            shuffleEnabled = shuffleEnabled,
            shuffleSeed = shuffleSeed,
            random = random,
        )
    }

    fun insertPlayNext(id: String): PlaybackOrderState {
        if (id.isBlank()) return this
        if (playbackIds.isEmpty()) {
            return copy(
                sourceIds = listOf(id),
                playbackIds = listOf(id),
                currentId = id,
            )
        }
        val current = currentOrderIndex.coerceIn(0, playbackIds.lastIndex)
        val playback = playbackIds.toMutableList()
        val existingPlayback = playback.indexOf(id)
        if (existingPlayback == current) return this
        if (existingPlayback >= 0) playback.removeAt(existingPlayback)
        val adjustedCurrent = current - if (existingPlayback in 0 until current) 1 else 0
        playback.add((adjustedCurrent + 1).coerceAtMost(playback.size), id)

        val source = sourceIds.toMutableList()
        if (!source.contains(id)) source.add(id)
        return copy(sourceIds = source, playbackIds = playback)
    }

    fun move(fromIndex: Int, toIndex: Int): PlaybackOrderState {
        if (fromIndex !in playbackIds.indices || toIndex !in playbackIds.indices || fromIndex == toIndex) {
            return this
        }
        val playback = playbackIds.toMutableList()
        val moved = playback.removeAt(fromIndex)
        playback.add(toIndex, moved)
        return copy(sourceIds = playback, playbackIds = playback)
    }

    fun removeAt(index: Int): PlaybackOrderState {
        if (index !in playbackIds.indices) return this
        val removedId = playbackIds[index]
        val playback = playbackIds.toMutableList().also { it.removeAt(index) }
        val source = sourceIds.filterNot { it == removedId }
        val nextCurrent = when {
            playback.isEmpty() -> null
            currentId != removedId -> currentId?.takeIf { playback.contains(it) }
            else -> playback[index.coerceAtMost(playback.lastIndex)]
        } ?: playback.firstOrNull()
        return copy(sourceIds = source, playbackIds = playback, currentId = nextCurrent)
    }

    fun setShuffleEnabled(enabled: Boolean, random: Random = Random.Default): PlaybackOrderState =
        fromSource(
            sourceIds = sourceIds.ifEmpty { playbackIds },
            currentId = currentId,
            shuffleEnabled = enabled,
            shuffleSeed = if (enabled) shuffleSeed ?: random.nextLong() else null,
            random = random,
        )

    companion object {
        fun fromSource(
            sourceIds: List<String>,
            currentId: String?,
            shuffleEnabled: Boolean,
            shuffleSeed: Long? = null,
            random: Random = Random.Default,
        ): PlaybackOrderState {
            val distinctIds = sourceIds.filter { it.isNotBlank() }.distinct()
            if (distinctIds.isEmpty()) {
                return PlaybackOrderState(shuffleEnabled = shuffleEnabled)
            }
            val safeCurrent = currentId
                ?.takeIf { distinctIds.contains(it) }
                ?: distinctIds.first()
            val effectiveSeed = if (shuffleEnabled) shuffleSeed ?: random.nextLong() else null
            val playback = if (shuffleEnabled && effectiveSeed != null) {
                PlaybackShuffleOrder.orderedIds(distinctIds, safeCurrent, effectiveSeed)
            } else {
                distinctIds
            }
            return PlaybackOrderState(
                sourceIds = distinctIds,
                playbackIds = playback,
                currentId = safeCurrent,
                shuffleEnabled = shuffleEnabled,
                shuffleSeed = effectiveSeed,
            )
        }
    }
}
