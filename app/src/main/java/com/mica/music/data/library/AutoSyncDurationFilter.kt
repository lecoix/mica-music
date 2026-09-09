package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.scanner.shouldKeepScannedDuration

/** Settings filtering is not evidence of physical deletion and must not remove playlist entries. */
internal fun applyAutoSyncDurationFilter(
    nextById: MutableMap<String, Song>,
    previousIds: Set<String>,
    sourceIdentity: SourceIdentityKey,
    minDurationMs: Long,
): List<MembershipChange> {
    val changes = mutableListOf<MembershipChange>()
    val iterator = nextById.iterator()
    while (iterator.hasNext()) {
        val (key, song) = iterator.next()
        if (shouldKeepScannedDuration(song.durationSec, minDurationMs)) continue
        iterator.remove()
        if (key in previousIds) {
            changes += MembershipChange(
                stableObjectKey = key,
                songId = song.id,
                reason = MembershipRemovalReason.FILTERED_OUT,
                evidenceRevision = "min-duration:$minDurationMs:duration:${song.durationSec}",
                sourceIdentity = sourceIdentity,
            )
        }
    }
    return changes
}
