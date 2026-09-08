package com.mica.music.data.library

import com.mica.music.data.LyricsProbeResult
import com.mica.music.data.LyricsSlots
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.Song
import com.mica.music.data.scanner.AudioMetadataProbeArtifactPolicy
import com.mica.music.data.scanner.DeviceDeltaFolderCasingPlan
import com.mica.music.data.scanner.DeviceObjectRevisionQueryApi
import com.mica.music.data.scanner.DeviceObjectRevisionRead
import com.mica.music.data.scanner.DeviceObjectRevisionReader
import com.mica.music.data.scanner.DeviceShadowCanonicalAspect
import com.mica.music.data.scanner.DeviceShadowProbeDraftQueryApi
import com.mica.music.data.scanner.DeviceShadowProbeDraftRead
import com.mica.music.data.scanner.DeviceShadowProbeDraftReader
import com.mica.music.data.scanner.ExternalLyricsReader
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.data.scanner.MediaStoreLyricsSidecarInventory
import com.mica.music.data.scanner.MediaStoreLyricsSidecarInventoryResult
import com.mica.music.data.scanner.ScannedSong
import com.mica.music.data.scanner.TrackDraft
import com.mica.music.data.scanner.externalLyricsUris
import com.mica.music.data.scanner.mediaStoreReadableFilePath
import com.mica.music.data.scanner.mediaStoreSongLyricsKey
import com.mica.music.data.scanner.retainScannedSong
import com.mica.music.data.scanner.toDeviceShadowCanonicalSong

internal data class DeviceShadowProbeRequest(
    val probePlan: DeviceAutoProbePlan,
    val scanOptions: com.mica.music.data.scanner.ScanOptions,
    val lyricsInventory: MediaStoreLyricsSidecarInventoryResult,
    val currentSongs: List<Song>,
    val currentSourceIdentity: SourceIdentityKey,
    val currentActivationEpoch: Long,
    val folderCasingPlan: DeviceDeltaFolderCasingPlan,
    val playbackSnapshotProvider: () -> LibraryPlaybackIoSnapshot,
)

internal fun interface DeviceShadowProbeRuntime {
    fun execute(request: DeviceShadowProbeRequest): DeviceShadowProbeExecutionResult
}

internal object NoopDeviceShadowProbeRuntime : DeviceShadowProbeRuntime {
    override fun execute(request: DeviceShadowProbeRequest): DeviceShadowProbeExecutionResult =
        DeviceShadowProbeExecutionResult(
            resolvedObjectsByStableObjectKey = emptyMap(),
            issues = emptyList(),
        )
}

internal class AndroidDeviceShadowProbeRuntime(
    private val context: android.content.Context,
) : DeviceShadowProbeRuntime {
    override fun execute(request: DeviceShadowProbeRequest): DeviceShadowProbeExecutionResult =
        DeviceShadowObjectProbeExecutor.execute(
            probePlan = request.probePlan,
            draftApi = com.mica.music.data.scanner.AndroidDeviceShadowProbeDraftQueryApi(
                context = context,
                options = request.scanOptions,
            ),
            revisionApi = com.mica.music.data.scanner.AndroidDeviceObjectRevisionQueryApi(
                context = context,
                options = request.scanOptions,
            ),
            audioProbeApi = defaultReadOnlyDeviceShadowAudioProbe(
                context = context,
                options = request.scanOptions,
            ),
            externalLyricsProbeApi = defaultDeviceShadowExternalLyricsProbe(context),
            lyricsInventoryRecheck = { MediaStoreLyricsSidecarInventory.load(context) },
            lyricsInventory = request.lyricsInventory,
            currentSongs = request.currentSongs,
            currentSourceIdentity = request.currentSourceIdentity,
            currentActivationEpoch = request.currentActivationEpoch,
            folderCasingPlan = request.folderCasingPlan,
            playbackSnapshotProvider = request.playbackSnapshotProvider,
        )
}

internal fun interface DeviceShadowAudioProbeApi {
    fun probe(
        draft: TrackDraft,
        cachedSong: Song?,
    ): ScannedSong
}

internal fun interface DeviceShadowExternalLyricsProbeApi {
    fun probe(
        lrcUris: List<String>,
        ttmlUris: List<String>,
    ): LyricsProbeResult
}

internal enum class DeviceShadowProbeIssueKind {
    DRAFT_MISSING,
    DRAFT_UNAVAILABLE,
    PRE_OBSERVATION_CHANGED,
    BECAME_INELIGIBLE,
    PLAYBACK_DEFERRED,
    PROBE_FAILED,
    POST_OBSERVATION_CHANGED,
}

internal data class DeviceShadowProbeIssue(
    val stableObjectKey: String,
    val kind: DeviceShadowProbeIssueKind,
    val detail: String = "",
)

internal data class DeviceShadowProbeExecutionResult(
    val resolvedObjectsByStableObjectKey: Map<String, DeviceShadowResolvedCanonicalObject>,
    val resolvedSongsByStableObjectKey: Map<String, Song> = emptyMap(),
    val resolvedLyricsByStableObjectKey: Map<String, ScannedSongLyrics> = emptyMap(),
    val fullReplaceLyricsStableObjectKeys: Set<String> = emptySet(),
    val issues: List<DeviceShadowProbeIssue>,
) {
    val playbackDeferredKeys: Set<String>
        get() = issues.asSequence()
            .filter { it.kind == DeviceShadowProbeIssueKind.PLAYBACK_DEFERRED }
            .mapTo(linkedSetOf(), DeviceShadowProbeIssue::stableObjectKey)

    val successfulCount: Int
        get() = resolvedObjectsByStableObjectKey.size
}

/**
 * Executes only already-READY heavy shadow work.
 *
 * The caller remains responsible for running this on the IO dispatcher. Each object is rechecked
 * before open, the playback lease is sampled again immediately before probe, and the provider row
 * is re-read after probe. A result is canonical-authoritative only when both observation checks
 * match the same source/activation/object revision.
 */
internal object DeviceShadowObjectProbeExecutor {
    private val BASE_HEAVY_PROBE_RESOLVED_ASPECTS = setOf(
        DeviceShadowCanonicalAspect.TAG_METADATA,
        DeviceShadowCanonicalAspect.REPLAY_GAIN,
        DeviceShadowCanonicalAspect.VIDEO_COVER,
        DeviceShadowCanonicalAspect.MUSIC_VIDEO,
    )

    fun execute(
        probePlan: DeviceAutoProbePlan,
        draftApi: DeviceShadowProbeDraftQueryApi,
        revisionApi: DeviceObjectRevisionQueryApi,
        audioProbeApi: DeviceShadowAudioProbeApi,
        lyricsInventory: MediaStoreLyricsSidecarInventoryResult,
        externalLyricsProbeApi: DeviceShadowExternalLyricsProbeApi =
            DeviceShadowExternalLyricsProbeApi { _, _ -> LyricsProbeResult.Complete(LyricsSlots()) },
        lyricsInventoryRecheck: () -> MediaStoreLyricsSidecarInventoryResult = { lyricsInventory },
        currentSongs: List<Song>,
        currentSourceIdentity: SourceIdentityKey,
        currentActivationEpoch: Long,
        folderCasingPlan: DeviceDeltaFolderCasingPlan,
        playbackSnapshotProvider: () -> LibraryPlaybackIoSnapshot,
    ): DeviceShadowProbeExecutionResult {
        val currentById = currentSongs.associateBy(Song::id)
        val resolved = linkedMapOf<String, DeviceShadowResolvedCanonicalObject>()
        val resolvedSongs = linkedMapOf<String, Song>()
        val resolvedLyrics = linkedMapOf<String, ScannedSongLyrics>()
        val fullReplaceLyricsKeys = linkedSetOf<String>()
        val resolvedLyricsKeys = linkedMapOf<String, String>()
        val issues = mutableListOf<DeviceShadowProbeIssue>()

        if (!lyricsInventory.complete) {
            probePlan.ready.asSequence()
                .filter(DeviceAutoProbeObjectPlan::requiresHeavyProbe)
                .forEach { plan ->
                    issues += DeviceShadowProbeIssue(
                        stableObjectKey = plan.stableObjectKey,
                        kind = DeviceShadowProbeIssueKind.DRAFT_UNAVAILABLE,
                        detail = "lyrics-sidecar-inventory-partial",
                    )
                }
            return DeviceShadowProbeExecutionResult(
                resolvedObjectsByStableObjectKey = emptyMap(),
                issues = issues,
            )
        }

        probePlan.ready.asSequence()
            .filter(DeviceAutoProbeObjectPlan::requiresHeavyProbe)
            .forEach { plan ->
                val ref = plan.objectRef
                if (ref == null || plan.observationStamp == null) {
                    issues += DeviceShadowProbeIssue(
                        stableObjectKey = plan.stableObjectKey,
                        kind = DeviceShadowProbeIssueKind.DRAFT_UNAVAILABLE,
                        detail = "provider object reference or observation stamp missing",
                    )
                    return@forEach
                }

                val draftRead = DeviceShadowProbeDraftReader.read(
                    api = draftApi,
                    ref = ref,
                    lyricsInventory = lyricsInventory,
                )
                val observation = when (draftRead) {
                    DeviceShadowProbeDraftRead.Missing -> {
                        issues += DeviceShadowProbeIssue(
                            plan.stableObjectKey,
                            DeviceShadowProbeIssueKind.DRAFT_MISSING,
                        )
                        return@forEach
                    }
                    is DeviceShadowProbeDraftRead.Unavailable -> {
                        issues += DeviceShadowProbeIssue(
                            plan.stableObjectKey,
                            DeviceShadowProbeIssueKind.DRAFT_UNAVAILABLE,
                            draftRead.detail,
                        )
                        return@forEach
                    }
                    is DeviceShadowProbeDraftRead.Observed -> draftRead.observation
                }

                val preValidation = DeviceObjectPostProbeValidator.validate(
                    plan = plan,
                    currentSourceIdentity = currentSourceIdentity,
                    currentActivationEpoch = currentActivationEpoch,
                    recheck = DeviceObjectRevisionRead.Observed(observation.row),
                )
                if (preValidation !is ObjectObservationValidation.Match) {
                    issues += DeviceShadowProbeIssue(
                        plan.stableObjectKey,
                        DeviceShadowProbeIssueKind.PRE_OBSERVATION_CHANGED,
                        preValidation.toString(),
                    )
                    return@forEach
                }
                if (observation.row.eligibility != LibraryEligibility.ELIGIBLE) {
                    issues += DeviceShadowProbeIssue(
                        plan.stableObjectKey,
                        DeviceShadowProbeIssueKind.BECAME_INELIGIBLE,
                        observation.row.eligibility.name,
                    )
                    return@forEach
                }

                val livePlayback = playbackSnapshotProvider()
                if (
                    livePlayback.blocksHeavyProbe(
                        stableObjectKey = plan.stableObjectKey,
                        mediaUri = plan.mediaUri,
                    )
                ) {
                    issues += DeviceShadowProbeIssue(
                        plan.stableObjectKey,
                        DeviceShadowProbeIssueKind.PLAYBACK_DEFERRED,
                    )
                    return@forEach
                }

                val draft = observation.draft.withEffectiveFolderCasing(
                    stableObjectKey = plan.stableObjectKey,
                    folderCasingPlan = folderCasingPlan,
                )
                val cached = currentById[plan.stableObjectKey]
                val scanned = runCatching {
                    audioProbeApi.probe(draft, cached)
                }.getOrElse { error ->
                    issues += DeviceShadowProbeIssue(
                        plan.stableObjectKey,
                        DeviceShadowProbeIssueKind.PROBE_FAILED,
                        error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                    )
                    return@forEach
                }

                val postRead = DeviceObjectRevisionReader.recheck(revisionApi, ref)
                val postValidation = DeviceObjectPostProbeValidator.validate(
                    plan = plan,
                    currentSourceIdentity = currentSourceIdentity,
                    currentActivationEpoch = currentActivationEpoch,
                    recheck = postRead,
                )
                if (postValidation !is ObjectObservationValidation.Match) {
                    issues += DeviceShadowProbeIssue(
                        plan.stableObjectKey,
                        DeviceShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
                        postValidation.toString(),
                    )
                    return@forEach
                }

                val retained = retainScannedSong(scanned).copy(
                    id = plan.stableObjectKey,
                    // DEVICE Full Scan intentionally has no folder-sidecar video authority.
                    // Keep shadow canonical semantics identical to MediaStoreScanner publication.
                    videoCoverUri = null,
                    videoCoverRevision = "",
                    musicVideoUri = null,
                    musicVideoRevision = "",
                )
                val resolvedAspects = BASE_HEAVY_PROBE_RESOLVED_ASPECTS.toMutableSet()
                when (scanned.lyrics) {
                    is LyricsProbeResult.Complete -> {
                        resolvedAspects += DeviceShadowCanonicalAspect.EXTERNAL_LYRICS
                        resolvedAspects += DeviceShadowCanonicalAspect.EMBEDDED_LYRICS_PROBE
                        resolvedLyrics[plan.stableObjectKey] = ScannedSongLyrics(
                            songId = plan.stableObjectKey,
                            revision = retained.lyricsCacheRevision,
                            slots = scanned.lyrics.slots,
                        )
                        fullReplaceLyricsKeys += plan.stableObjectKey
                        mediaStoreSongLyricsKey(retained)?.let { lyricsKey ->
                            resolvedLyricsKeys[plan.stableObjectKey] = lyricsKey
                        }
                    }
                    LyricsProbeResult.ReadFailed -> {
                        issues += DeviceShadowProbeIssue(
                            stableObjectKey = plan.stableObjectKey,
                            kind = DeviceShadowProbeIssueKind.PROBE_FAILED,
                            detail = "lyrics-read-failed",
                        )
                    }
                    LyricsProbeResult.NotProbed -> {
                        issues += DeviceShadowProbeIssue(
                            stableObjectKey = plan.stableObjectKey,
                            kind = DeviceShadowProbeIssueKind.PROBE_FAILED,
                            detail = "lyrics-not-probed",
                        )
                    }
                }
                resolved[plan.stableObjectKey] = DeviceShadowResolvedCanonicalObject(
                    song = retained.toDeviceShadowCanonicalSong(plan.stableObjectKey),
                    resolvedAspects = resolvedAspects,
                )
                resolvedSongs[plan.stableObjectKey] = retained.copy(
                    lyricsLoaded = false,
                )
            }

        probePlan.ready.asSequence()
            .filter { plan ->
                !plan.requiresHeavyProbe &&
                    DeviceAutoProbeWork.EXTERNAL_LYRICS in plan.work
            }
            .forEach { plan ->
                val current = currentById[plan.stableObjectKey] ?: return@forEach
                val lyricsKey = mediaStoreSongLyricsKey(current)
                if (lyricsKey == null) {
                    issues += DeviceShadowProbeIssue(
                        stableObjectKey = plan.stableObjectKey,
                        kind = DeviceShadowProbeIssueKind.DRAFT_UNAVAILABLE,
                        detail = "sidecar-lyrics-key-unavailable",
                    )
                    return@forEach
                }
                val refs = lyricsInventory.refsByLyricsKey[lyricsKey].orEmpty()
                val lyrics = runCatching {
                    externalLyricsProbeApi.probe(
                        lrcUris = refs.externalLyricsUris("lrc"),
                        ttmlUris = refs.externalLyricsUris("ttml"),
                    )
                }.getOrElse { error ->
                    issues += DeviceShadowProbeIssue(
                        stableObjectKey = plan.stableObjectKey,
                        kind = DeviceShadowProbeIssueKind.PROBE_FAILED,
                        detail = error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                    )
                    return@forEach
                }
                val slots = when (lyrics) {
                    is LyricsProbeResult.Complete -> lyrics.slots.copy(embedded = null)
                    LyricsProbeResult.ReadFailed -> {
                        issues += DeviceShadowProbeIssue(
                            stableObjectKey = plan.stableObjectKey,
                            kind = DeviceShadowProbeIssueKind.PROBE_FAILED,
                            detail = "external-lyrics-read-failed",
                        )
                        return@forEach
                    }
                    LyricsProbeResult.NotProbed -> {
                        issues += DeviceShadowProbeIssue(
                            stableObjectKey = plan.stableObjectKey,
                            kind = DeviceShadowProbeIssueKind.PROBE_FAILED,
                            detail = "external-lyrics-not-probed",
                        )
                        return@forEach
                    }
                }
                val updated = current.copy(
                    externalLyricsSignature = lyricsInventory.signatureFor(lyricsKey),
                    lyricsLoaded = false,
                )
                resolvedSongs[plan.stableObjectKey] = updated
                resolvedLyrics[plan.stableObjectKey] = ScannedSongLyrics(
                    songId = plan.stableObjectKey,
                    revision = updated.lyricsCacheRevision,
                    slots = slots,
                )
                resolvedLyricsKeys[plan.stableObjectKey] = lyricsKey
            }

        if (resolvedLyricsKeys.isNotEmpty()) {
            val rechecked = lyricsInventoryRecheck()
            resolvedLyricsKeys.forEach { (stableKey, lyricsKey) ->
                val beforeSignature = lyricsInventory.signatureFor(lyricsKey)
                val afterSignature = rechecked.signatureFor(lyricsKey)
                if (!rechecked.complete || beforeSignature != afterSignature) {
                    resolved.remove(stableKey)
                    resolvedSongs.remove(stableKey)
                    resolvedLyrics.remove(stableKey)
                    fullReplaceLyricsKeys.remove(stableKey)
                    issues += DeviceShadowProbeIssue(
                        stableObjectKey = stableKey,
                        kind = if (rechecked.complete) {
                            DeviceShadowProbeIssueKind.POST_OBSERVATION_CHANGED
                        } else {
                            DeviceShadowProbeIssueKind.DRAFT_UNAVAILABLE
                        },
                        detail = if (rechecked.complete) {
                            "external-lyrics-signature-changed-during-probe"
                        } else {
                            "lyrics-sidecar-recheck-partial"
                        },
                    )
                }
            }
        }

        return DeviceShadowProbeExecutionResult(
            resolvedObjectsByStableObjectKey = resolved.toMap(),
            resolvedSongsByStableObjectKey = resolvedSongs.toMap(),
            resolvedLyricsByStableObjectKey = resolvedLyrics.toMap(),
            fullReplaceLyricsStableObjectKeys = fullReplaceLyricsKeys.toSet(),
            issues = issues.toList(),
        )
    }

    private fun TrackDraft.withEffectiveFolderCasing(
        stableObjectKey: String,
        folderCasingPlan: DeviceDeltaFolderCasingPlan,
    ): TrackDraft {
        val effective = folderCasingPlan.effectiveFolderPath(stableObjectKey)
            ?: return this
        if (effective == folderPath) return this
        val effectiveFilePath = if (filePath.trim().startsWith('/')) {
            filePath
        } else {
            mediaStoreReadableFilePath(effective, displayName.orEmpty())
        }
        return copy(
            folderPath = effective,
            filePath = effectiveFilePath,
        )
    }
}

internal fun defaultReadOnlyDeviceShadowAudioProbe(
    context: android.content.Context,
    options: com.mica.music.data.scanner.ScanOptions,
): DeviceShadowAudioProbeApi = DeviceShadowAudioProbeApi { draft, cachedSong ->
    if (options.deepMetadataProbe) {
        com.mica.music.data.scanner.AudioMetadataProbe.probeTrack(
            context = context,
            draft = draft,
            cachedSong = cachedSong,
            artifactPolicy = AudioMetadataProbeArtifactPolicy.READ_ONLY_CANONICAL,
        )
    } else {
        com.mica.music.data.scanner.AudioMetadataProbe.quickSong(
            context = context,
            draft = draft,
            cachedSong = cachedSong,
            artifactPolicy = AudioMetadataProbeArtifactPolicy.READ_ONLY_CANONICAL,
        )
    }
}

internal fun defaultDeviceShadowExternalLyricsProbe(
    context: android.content.Context,
): DeviceShadowExternalLyricsProbeApi = DeviceShadowExternalLyricsProbeApi { lrcUris, ttmlUris ->
    val lrc = ExternalLyricsReader.probeDirectDocuments(context, lrcUris)
    val ttml = ExternalLyricsReader.probeDirectDocuments(context, ttmlUris)
    if (
        lrc is com.mica.music.data.scanner.ProbeResult.Failed ||
        ttml is com.mica.music.data.scanner.ProbeResult.Failed
    ) {
        LyricsProbeResult.ReadFailed
    } else {
        LyricsProbeResult.Complete(
            LyricsSlots(
                externalLrc =
                    (lrc as com.mica.music.data.scanner.ProbeResult.Ok).value,
                externalTtml =
                    (ttml as com.mica.music.data.scanner.ProbeResult.Ok).value,
            ),
        )
    }
}
