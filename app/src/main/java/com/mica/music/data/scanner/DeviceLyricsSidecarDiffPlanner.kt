package com.mica.music.data.scanner

import com.mica.music.data.Song

internal data class DeviceLyricsSignatureChange(
    val songId: String,
    val lyricsKey: String,
    val previousSignature: String,
    val observedSignature: String,
)

internal data class DeviceLyricsSidecarDiff(
    val changes: List<DeviceLyricsSignatureChange>,
    val unverifiableSongIds: Set<String>,
) {
    val affectedSongIds: Set<String>
        get() = changes.mapTo(linkedSetOf(), DeviceLyricsSignatureChange::songId)

    val safeToAdvance: Boolean
        get() = unverifiableSongIds.isEmpty()
}

internal object DeviceLyricsSidecarDiffPlanner {
    fun plan(
        currentSongs: List<Song>,
        inventory: MediaStoreLyricsSidecarInventoryResult,
    ): DeviceLyricsSidecarDiff {
        if (!inventory.complete) {
            return DeviceLyricsSidecarDiff(
                changes = emptyList(),
                unverifiableSongIds = currentSongs
                    .filter { it.externalLyricsSignature.isNotEmpty() }
                    .mapTo(linkedSetOf(), Song::id),
            )
        }

        val changes = mutableListOf<DeviceLyricsSignatureChange>()
        val unverifiable = linkedSetOf<String>()
        currentSongs.forEach { song ->
            val lyricsKey = mediaStoreSongLyricsKey(song)
            if (lyricsKey == null) {
                if (song.externalLyricsSignature.isNotEmpty()) {
                    unverifiable += song.id
                }
                return@forEach
            }
            val observed = inventory.signatureFor(lyricsKey)
            if (observed != song.externalLyricsSignature) {
                changes += DeviceLyricsSignatureChange(
                    songId = song.id,
                    lyricsKey = lyricsKey,
                    previousSignature = song.externalLyricsSignature,
                    observedSignature = observed,
                )
            }
        }
        return DeviceLyricsSidecarDiff(
            changes = changes.sortedBy(DeviceLyricsSignatureChange::songId),
            unverifiableSongIds = unverifiable,
        )
    }
}
