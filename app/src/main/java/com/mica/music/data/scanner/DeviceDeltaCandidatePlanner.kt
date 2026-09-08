package com.mica.music.data.scanner

import com.mica.music.data.Song

internal data class DeviceAudioDeltaCandidate(
    val canonicalStableObjectKey: String,
    val observedStableObjectKeys: Set<String>,
    val primaryRow: DeviceDeltaRow,
    val duplicateKey: String,
    val lyricsKey: String?,
    val existingSongId: String?,
    val sidecarChanged: Boolean,
) {
    val requiresProbe: Boolean
        get() = primaryRow.eligibility == LibraryEligibility.ELIGIBLE

    val hasTransientProviderClassification: Boolean
        get() = primaryRow.eligibilityAuthority ==
            DeviceEligibilityAuthority.PROVIDER_METADATA_TRANSIENT
}

internal data class DeviceSidecarDeltaCandidate(
    val lyricsKey: String,
    val rows: List<DeviceDeltaRow>,
    val affectedSongIds: Set<String>,
    val affectedAudioStableObjectKeys: Set<String>,
)

internal data class DeviceDeltaCandidateContradiction(
    val key: String,
    val detail: String,
)

internal data class DeviceDeltaCandidatePlan(
    val audioCandidates: List<DeviceAudioDeltaCandidate>,
    val sidecarCandidates: List<DeviceSidecarDeltaCandidate>,
    val contradictions: List<DeviceDeltaCandidateContradiction>,
) {
    val affectedExistingSongIds: Set<String>
        get() = buildSet {
            audioCandidates.mapNotNullTo(this, DeviceAudioDeltaCandidate::existingSongId)
            sidecarCandidates.forEach { addAll(it.affectedSongIds) }
        }

    val hasContradictions: Boolean
        get() = contradictions.isNotEmpty()

    val transientProviderClassificationKeys: Set<String>
        get() = audioCandidates
            .asSequence()
            .filter(DeviceAudioDeltaCandidate::hasTransientProviderClassification)
            .mapTo(linkedSetOf(), DeviceAudioDeltaCandidate::canonicalStableObjectKey)
}

internal object DeviceDeltaCandidatePlanner {

    fun plan(
        batch: DeviceMediaStoreDeltaBatch,
        currentSongs: List<Song>,
    ): DeviceDeltaCandidatePlan {
        val currentByDuplicateKey = currentSongs
            .groupBy(::mediaStoreSongDuplicateKey)
        val currentByLyricsKey = currentSongs
            .mapNotNull { song -> mediaStoreSongLyricsKey(song)?.let { it to song } }
            .groupBy({ it.first }, { it.second })

        val changedSidecarsByLyricsKey = batch.rows(DeviceDeltaChannel.LYRICS_SIDECAR)
            .mapNotNull { row ->
                mediaStoreDeltaRowLyricsKey(row)?.let { key -> key to row }
            }
            .groupBy({ it.first }, { it.second })

        val contradictions = mutableListOf<DeviceDeltaCandidateContradiction>()
        val audioGroups = batch.rows
            .asSequence()
            .filter {
                it.channel == DeviceDeltaChannel.AUDIO ||
                    it.channel == DeviceDeltaChannel.FILES_FALLBACK
            }
            .groupBy(::mediaStoreDeltaRowDuplicateKey)

        val audioCandidates = audioGroups.map { (duplicateKey, rows) ->
            val primary = rows.sortedWith(
                compareBy<DeviceDeltaRow> { row ->
                    when (row.channel) {
                        DeviceDeltaChannel.AUDIO -> 0
                        DeviceDeltaChannel.FILES_FALLBACK -> 1
                        DeviceDeltaChannel.LYRICS_SIDECAR -> 2
                    }
                }.thenByDescending(DeviceDeltaRow::observedGeneration),
            ).first()
            val currentMatches = currentByDuplicateKey[duplicateKey].orEmpty()
            if (currentMatches.size > 1) {
                contradictions += DeviceDeltaCandidateContradiction(
                    key = duplicateKey,
                    detail = "multiple current songs share MediaStore duplicate key: " +
                        currentMatches.joinToString { it.id },
                )
            }
            val existing = currentMatches.singleOrNull()
            val lyricsKey = mediaStoreDeltaRowLyricsKey(primary)
            DeviceAudioDeltaCandidate(
                canonicalStableObjectKey = existing?.id ?: primary.stableObjectKey,
                observedStableObjectKeys = rows.mapTo(linkedSetOf(), DeviceDeltaRow::stableObjectKey),
                primaryRow = primary,
                duplicateKey = duplicateKey,
                lyricsKey = lyricsKey,
                existingSongId = existing?.id,
                sidecarChanged = lyricsKey != null && lyricsKey in changedSidecarsByLyricsKey,
            )
        }.sortedBy(DeviceAudioDeltaCandidate::canonicalStableObjectKey)

        val audioByLyricsKey = audioCandidates
            .mapNotNull { candidate -> candidate.lyricsKey?.let { it to candidate } }
            .groupBy({ it.first }, { it.second })

        val sidecarCandidates = changedSidecarsByLyricsKey.map { (lyricsKey, rows) ->
            DeviceSidecarDeltaCandidate(
                lyricsKey = lyricsKey,
                rows = rows.sortedBy(DeviceDeltaRow::mediaUri),
                affectedSongIds = currentByLyricsKey[lyricsKey]
                    .orEmpty()
                    .mapTo(linkedSetOf(), Song::id),
                affectedAudioStableObjectKeys = audioByLyricsKey[lyricsKey]
                    .orEmpty()
                    .mapTo(linkedSetOf(), DeviceAudioDeltaCandidate::canonicalStableObjectKey),
            )
        }.sortedBy(DeviceSidecarDeltaCandidate::lyricsKey)

        return DeviceDeltaCandidatePlan(
            audioCandidates = audioCandidates,
            sidecarCandidates = sidecarCandidates,
            contradictions = contradictions,
        )
    }

}
