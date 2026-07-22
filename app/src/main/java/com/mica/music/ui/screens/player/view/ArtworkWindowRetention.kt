package com.mica.music.ui.screens.player.view

import com.mica.music.data.Song
import com.mica.music.imaging.CoverDecodeTarget

/**
 * Builds the only album-art keys a player renderer may retain locally.
 *
 * The shared Coil cache owns decoded Bitmap lifetime. Native player Views keep only a bounded
 * set of strong references for their visible lane/card window plus an explicitly small set of
 * transition targets.
 */
internal fun retainedArtworkKeys(
    queue: List<Song>,
    centerIndex: Int,
    visibleOffsets: IntRange,
    decodeTarget: CoverDecodeTarget,
    extraIndices: Iterable<Int> = emptyList(),
    extraSongs: Iterable<Song> = emptyList(),
): Set<String> = buildSet {
    visibleOffsets.forEach { offset ->
        queue.getOrNull(centerIndex + offset)?.albumArtUri?.let { uri ->
            add(decodeTarget.memoryCacheKey(uri))
        }
    }
    extraIndices.forEach { index ->
        queue.getOrNull(index)?.albumArtUri?.let { uri ->
            add(decodeTarget.memoryCacheKey(uri))
        }
    }
    extraSongs.forEach { song ->
        song.albumArtUri?.let { uri ->
            add(decodeTarget.memoryCacheKey(uri))
        }
    }
}

/**
 * A target equality check alone is insufficient for A -> B -> A viewport changes: the first A
 * request may finish after the second A becomes active. The monotonically increasing generation
 * distinguishes those otherwise identical targets.
 */
internal fun shouldAcceptArtworkLoad(
    requestGeneration: Long,
    activeGeneration: Long,
    requestTarget: CoverDecodeTarget,
    activeTarget: CoverDecodeTarget,
    bitmapKey: String,
    retainedKeys: Set<String>,
): Boolean =
    requestGeneration == activeGeneration &&
        requestTarget == activeTarget &&
        bitmapKey in retainedKeys
