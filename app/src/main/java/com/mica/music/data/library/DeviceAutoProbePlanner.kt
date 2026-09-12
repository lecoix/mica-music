package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.scanner.DeviceAudioDeltaCandidate
import com.mica.music.data.scanner.DeviceDeltaCandidatePlan
import com.mica.music.data.scanner.DeviceDeltaRow
import com.mica.music.data.scanner.DeviceLyricsSidecarDiff
import com.mica.music.data.scanner.DeviceObjectRef
import com.mica.music.data.scanner.deviceObjectRef
import com.mica.music.data.scanner.deviceObjectRevisionFingerprint
import com.mica.music.data.scanner.DeviceSidecarDeltaCandidate
import com.mica.music.data.scanner.LibraryEligibility

/**
 * Runtime facts used by AUTO before it is allowed to open media objects.
 *
 * "Active" deliberately includes paused/buffering instances: if the player still owns the current
 * media object, AUTO must not assume the object is safe to reopen just because isPlaying=false.
 */
internal data class LibraryPlaybackIoSnapshot(
    val currentStableObjectKey: String? = null,
    val currentMediaUri: String? = null,
    val hasActivePlaybackInstance: Boolean = false,
    val sourceSerializesHeavyIo: Boolean = false,
) {
    companion object {
        val Idle = LibraryPlaybackIoSnapshot()
    }
}

internal enum class DeviceAutoProbeReason {
    NEW_OBJECT,
    MEDIASTORE_REVISION_CHANGED,
    EXTERNAL_LYRICS_CHANGED,
    RETRY_LEDGER,
}

internal enum class DeviceAutoProbeWork {
    AUDIO_METADATA,
    EXTERNAL_LYRICS,
}

internal enum class DeviceAutoProbeDisposition {
    READY,
    REQUERY_OBJECT_REVISION,
    DEFER_UNTIL_PLAYBACK_RELEASE,
}

internal data class DeviceAutoProbeObjectPlan(
    val stableObjectKey: String,
    val mediaUri: String?,
    val reasons: Set<DeviceAutoProbeReason>,
    val work: Set<DeviceAutoProbeWork>,
    val disposition: DeviceAutoProbeDisposition,
    val observationStamp: ObjectObservationStamp?,
    val objectRef: DeviceObjectRef?,
) {
    val requiresHeavyProbe: Boolean
        get() = DeviceAutoProbeWork.AUDIO_METADATA in work
}

internal data class DeviceAutoProbePlan(
    val objects: List<DeviceAutoProbeObjectPlan>,
    val heavyProbeParallelism: Int,
) {
    val ready: List<DeviceAutoProbeObjectPlan>
        get() = objects.filter { it.disposition == DeviceAutoProbeDisposition.READY }

    val requeryRequired: List<DeviceAutoProbeObjectPlan>
        get() = objects.filter {
            it.disposition == DeviceAutoProbeDisposition.REQUERY_OBJECT_REVISION
        }

    val deferred: List<DeviceAutoProbeObjectPlan>
        get() = objects.filter {
            it.disposition == DeviceAutoProbeDisposition.DEFER_UNTIL_PLAYBACK_RELEASE
        }

    val isNoOp: Boolean
        get() = objects.isEmpty()
}

/**
 * AUTO-only probe eligibility.
 *
 * This planner intentionally does not call reusableCachedSong(): that function contains FULL /
 * maintenance cache-health misses (art cache, cover colour repair, metadata scan-version upgrades,
 * etc.) which must never turn an otherwise-clean AUTO pass into a probe wave.
 */
internal object DeviceAutoProbePlanner {
    private const val CONSERVATIVE_HEAVY_PROBE_PARALLELISM = 1

    fun plan(
        candidates: DeviceDeltaCandidatePlan,
        currentSongs: List<Song>,
        lyricsDiff: DeviceLyricsSidecarDiff = DeviceLyricsSidecarDiff(
            changes = emptyList(),
            unverifiableSongIds = emptySet(),
        ),
        retryItems: List<LibraryRetryItem>,
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
        nowMs: Long,
        playback: LibraryPlaybackIoSnapshot,
        retryObservationRowsByStableObjectKey: Map<String, DeviceDeltaRow> = emptyMap(),
        excludedStableObjectKeys: Set<String> = emptySet(),
    ): DeviceAutoProbePlan {
        val currentById = currentSongs.associateBy(Song::id)
        val builders = linkedMapOf<String, MutableProbe>()

        candidates.audioCandidates.forEach { candidate ->
            if (candidate.canonicalStableObjectKey in excludedStableObjectKeys) {
                return@forEach
            }
            if (candidate.primaryRow.eligibility != LibraryEligibility.ELIGIBLE) {
                return@forEach
            }
            val mutable = builders.getOrPut(candidate.canonicalStableObjectKey) {
                MutableProbe(
                    stableObjectKey = candidate.canonicalStableObjectKey,
                    mediaUri = candidate.primaryRow.mediaUri,
                )
            }
            mutable.mediaUri = candidate.primaryRow.mediaUri
            mutable.objectRef = candidate.primaryRow.deviceObjectRef()
            mutable.observationStamp = candidate.primaryRow.toObservationStamp(
                sourceIdentity = sourceIdentity,
                activationEpoch = activationEpoch,
                stableObjectKey = candidate.canonicalStableObjectKey,
            )
            mutable.reasons += if (candidate.existingSongId == null) {
                DeviceAutoProbeReason.NEW_OBJECT
            } else {
                DeviceAutoProbeReason.MEDIASTORE_REVISION_CHANGED
            }
            mutable.work += DeviceAutoProbeWork.AUDIO_METADATA
            if (candidate.sidecarChanged) {
                mutable.reasons += DeviceAutoProbeReason.EXTERNAL_LYRICS_CHANGED
                mutable.work += DeviceAutoProbeWork.EXTERNAL_LYRICS
            }
        }

        candidates.sidecarCandidates.forEach { candidate ->
            addSidecarWork(
                candidate = candidate,
                currentById = currentById,
                builders = builders,
                excludedStableObjectKeys = excludedStableObjectKeys,
            )
        }

        // Sidecar deletion has no MediaStore delta tombstone. A bounded inventory can still prove
        // that the external-lyrics signature changed, so turn that evidence into real probe work
        // even when there is no sidecar delta row to seed a candidate.
        lyricsDiff.affectedSongIds.forEach { songId ->
            if (songId in excludedStableObjectKeys) return@forEach
            val current = currentById[songId] ?: return@forEach
            val mutable = builders.getOrPut(songId) {
                MutableProbe(
                    stableObjectKey = songId,
                    mediaUri = current.mediaUri,
                )
            }
            mutable.reasons += DeviceAutoProbeReason.EXTERNAL_LYRICS_CHANGED
            mutable.work += DeviceAutoProbeWork.EXTERNAL_LYRICS
        }

        retryItems.asSequence()
            .filter { it.sourceIdentity == sourceIdentity }
            .filter { it.retryKind == LibraryRetryKind.OBJECT_PROBE }
            .filter { it.activationEpoch == null || it.activationEpoch == activationEpoch }
            .filter { it.nextRetryAtMs <= nowMs }
            .filterNot { it.stableObjectKey in excludedStableObjectKeys }
            .forEach { retry ->
                val current = currentById[retry.stableObjectKey]
                val observedRetryRow = retryObservationRowsByStableObjectKey[retry.stableObjectKey]
                val mutable = builders.getOrPut(retry.stableObjectKey) {
                    MutableProbe(
                        stableObjectKey = retry.stableObjectKey,
                        mediaUri = observedRetryRow?.mediaUri ?: current?.mediaUri,
                    )
                }
                if (observedRetryRow != null) {
                    mutable.mediaUri = observedRetryRow.mediaUri
                    mutable.objectRef = observedRetryRow.deviceObjectRef()
                    mutable.observationStamp = observedRetryRow.toObservationStamp(
                        sourceIdentity = sourceIdentity,
                        activationEpoch = activationEpoch,
                        stableObjectKey = retry.stableObjectKey,
                    )
                } else if (mutable.observationStamp == null) {
                    if (mutable.mediaUri == null) mutable.mediaUri = current?.mediaUri
                    mutable.observationStamp = ObjectObservationStamp(
                        sourceIdentity = retry.sourceIdentity,
                        activationEpoch = retry.activationEpoch ?: activationEpoch,
                        stableObjectKey = retry.stableObjectKey,
                        fingerprint = retry.observedFingerprint.takeIf(String::isNotBlank),
                    )
                }
                mutable.reasons += DeviceAutoProbeReason.RETRY_LEDGER
                mutable.work += DeviceAutoProbeWork.AUDIO_METADATA
            }

        val objects = builders.values
            .map { mutable ->
                val disposition = when {
                    !mutable.requiresHeavyProbe -> DeviceAutoProbeDisposition.READY
                    mutable.objectRef == null || mutable.observationStamp == null ->
                        DeviceAutoProbeDisposition.REQUERY_OBJECT_REVISION
                    playback.blocksHeavyProbe(
                        stableObjectKey = mutable.stableObjectKey,
                        mediaUri = mutable.mediaUri,
                    ) -> DeviceAutoProbeDisposition.DEFER_UNTIL_PLAYBACK_RELEASE
                    else -> DeviceAutoProbeDisposition.READY
                }
                DeviceAutoProbeObjectPlan(
                    stableObjectKey = mutable.stableObjectKey,
                    mediaUri = mutable.mediaUri,
                    reasons = mutable.reasons.toSet(),
                    work = mutable.work.toSet(),
                    disposition = disposition,
                    observationStamp = mutable.observationStamp,
                    objectRef = mutable.objectRef,
                )
            }
            .sortedBy(DeviceAutoProbeObjectPlan::stableObjectKey)

        return DeviceAutoProbePlan(
            objects = objects,
            heavyProbeParallelism = CONSERVATIVE_HEAVY_PROBE_PARALLELISM,
        )
    }

    private fun addSidecarWork(
        candidate: DeviceSidecarDeltaCandidate,
        currentById: Map<String, Song>,
        builders: MutableMap<String, MutableProbe>,
        excludedStableObjectKeys: Set<String>,
    ) {
        candidate.affectedSongIds.forEach { songId ->
            if (songId in excludedStableObjectKeys) return@forEach
            val current = currentById[songId]
            val mutable = builders.getOrPut(songId) {
                MutableProbe(
                    stableObjectKey = songId,
                    mediaUri = current?.mediaUri,
                )
            }
            mutable.reasons += DeviceAutoProbeReason.EXTERNAL_LYRICS_CHANGED
            mutable.work += DeviceAutoProbeWork.EXTERNAL_LYRICS
        }
        candidate.affectedAudioStableObjectKeys.forEach { stableObjectKey ->
            if (stableObjectKey in excludedStableObjectKeys) return@forEach
            val current = currentById[stableObjectKey]
            val mutable = builders.getOrPut(stableObjectKey) {
                MutableProbe(
                    stableObjectKey = stableObjectKey,
                    mediaUri = current?.mediaUri,
                )
            }
            mutable.reasons += DeviceAutoProbeReason.EXTERNAL_LYRICS_CHANGED
            mutable.work += DeviceAutoProbeWork.EXTERNAL_LYRICS
        }
    }

    private fun DeviceDeltaRow.toObservationStamp(
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
        stableObjectKey: String,
    ): ObjectObservationStamp = ObjectObservationStamp(
        sourceIdentity = sourceIdentity,
        activationEpoch = activationEpoch,
        stableObjectKey = stableObjectKey,
        fingerprint = deviceObjectRevisionFingerprint(),
        providerGeneration = observedGeneration,
    )

    private data class MutableProbe(
        val stableObjectKey: String,
        var mediaUri: String?,
        val reasons: MutableSet<DeviceAutoProbeReason> = linkedSetOf(),
        val work: MutableSet<DeviceAutoProbeWork> = linkedSetOf(),
        var observationStamp: ObjectObservationStamp? = null,
        var objectRef: DeviceObjectRef? = null,
    ) {
        val requiresHeavyProbe: Boolean
            get() = DeviceAutoProbeWork.AUDIO_METADATA in work
    }
}

internal fun LibraryPlaybackIoSnapshot.withSafProviderSerialization(
    treeAuthority: String?,
): LibraryPlaybackIoSnapshot {
    if (!hasActivePlaybackInstance || sourceSerializesHeavyIo) return this
    val scopedTreeAuthority = treeAuthority?.takeIf { it.isNotBlank() } ?: return this

    // Android's built-in ExternalStorageProvider safely supports opening a different document while
    // another document from the same provider is owned by playback. Keep exact-object protection in
    // blocksHeavyProbe(), but do not serialize the entire provider: otherwise every newly discovered
    // song is deferred for as long as any SAF playback instance (including PAUSED) exists.
    if (scopedTreeAuthority == SYSTEM_EXTERNAL_STORAGE_PROVIDER_AUTHORITY) return this

    val playbackAuthority = currentMediaUri
        ?.substringAfter("content://", missingDelimiterValue = "")
        ?.substringBefore('/')
        ?.takeIf { it.isNotBlank() }
        ?: return this
    return if (scopedTreeAuthority == playbackAuthority) {
        copy(sourceSerializesHeavyIo = true)
    } else {
        this
    }
}

private const val SYSTEM_EXTERNAL_STORAGE_PROVIDER_AUTHORITY =
    "com.android.externalstorage.documents"

internal fun LibraryPlaybackIoSnapshot.blocksHeavyProbe(
    stableObjectKey: String,
    mediaUri: String?,
): Boolean {
    if (!hasActivePlaybackInstance) return false
    if (sourceSerializesHeavyIo) return true
    if (currentStableObjectKey != null && currentStableObjectKey == stableObjectKey) {
        return true
    }
    return currentMediaUri != null && currentMediaUri == mediaUri
}
