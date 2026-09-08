package com.mica.music.data.library

import com.mica.music.data.LyricsProbeResult
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.Song
import com.mica.music.data.scanner.AudioMetadataProbe
import com.mica.music.data.scanner.AudioMetadataProbeArtifactPolicy
import com.mica.music.data.scanner.SafFingerprintReliability
import com.mica.music.data.scanner.SafTreeMetadataEntry
import com.mica.music.data.scanner.SafTreeMetadataSnapshot
import com.mica.music.data.scanner.ScanOptions
import com.mica.music.data.scanner.ScannedSong
import com.mica.music.data.scanner.hasSameObservedRevision
import com.mica.music.data.scanner.retainScannedSong
import java.security.MessageDigest

internal data class SafShadowProbeRequest(
    val probePlan: SafAutoProbePlan,
    val scanOptions: ScanOptions,
    val currentSongs: List<Song>,
    val playbackSnapshotProvider: () -> LibraryPlaybackIoSnapshot,
    val unknownVerifyWallTimeBudgetMs: Long =
        UNKNOWN_VERIFY_WALL_TIME_BUDGET_MS,
    val monotonicTimeMsProvider: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        require(unknownVerifyWallTimeBudgetMs >= 0L)
    }
}

internal fun interface SafShadowProbeRuntime {
    fun execute(request: SafShadowProbeRequest): SafShadowProbeExecutionResult
}

internal object NoopSafShadowProbeRuntime : SafShadowProbeRuntime {
    override fun execute(request: SafShadowProbeRequest): SafShadowProbeExecutionResult =
        SafShadowProbeExecutionResult()
}

internal class AndroidSafShadowProbeRuntime(
    private val context: android.content.Context,
) : SafShadowProbeRuntime {
    override fun execute(request: SafShadowProbeRequest): SafShadowProbeExecutionResult =
        SafShadowObjectProbeExecutor.execute(
            request = request,
            audioProbeApi = defaultReadOnlySafShadowAudioProbe(
                context = context,
                options = request.scanOptions,
            ),
            strongFingerprintApi = defaultSafStrongResourceFingerprintApi(context),
        )
}

internal fun interface SafShadowAudioProbeApi {
    fun probe(
        entry: SafTreeMetadataEntry,
        cachedSong: Song?,
    ): ScannedSong
}

internal fun interface SafStrongResourceFingerprintApi {
    fun fingerprint(entry: SafTreeMetadataEntry): String?
}

internal object NoopSafStrongResourceFingerprintApi : SafStrongResourceFingerprintApi {
    override fun fingerprint(entry: SafTreeMetadataEntry): String? = null
}

internal enum class SafShadowProbeIssueKind {
    DRAFT_UNAVAILABLE,
    PLAYBACK_DEFERRED,
    UNKNOWN_VERIFY_BUDGET_DEFERRED,
    PROBE_FAILED,
    POST_OBSERVATION_CHANGED,
    UNVERIFIABLE_FINGERPRINT,
}

internal data class SafShadowProbeIssue(
    val stableObjectKey: String,
    val kind: SafShadowProbeIssueKind,
    val detail: String = "",
)

internal data class SafShadowProbeExecutionResult(
    /** Probe output before the caller performs the post-walk observation validation. */
    val provisionalSongsByStableObjectKey: Map<String, Song> = emptyMap(),
    /** Bounded lyrics payloads for the same provisional objects; never persisted before validation. */
    val provisionalLyricsByStableObjectKey: Map<String, ScannedSongLyrics> = emptyMap(),
    val strongValidatedFingerprintsByStableObjectKey: Map<String, String> = emptyMap(),
    val issues: List<SafShadowProbeIssue> = emptyList(),
    val attemptedCount: Int = 0,
    val unknownVerifyWallTimeMs: Long = 0L,
) {
    val playbackDeferredKeys: Set<String>
        get() = issues.asSequence()
            .filter { it.kind == SafShadowProbeIssueKind.PLAYBACK_DEFERRED }
            .mapTo(linkedSetOf(), SafShadowProbeIssue::stableObjectKey)
}

internal data class SafShadowPostValidationResult(
    val resolvedSongsByStableObjectKey: Map<String, Song>,
    val resolvedLyricsByStableObjectKey: Map<String, ScannedSongLyrics> = emptyMap(),
    val issues: List<SafShadowProbeIssue>,
) {
    val fullyResolved: Boolean
        get() = issues.isEmpty()
}

internal object SafShadowObjectProbeExecutor {
    fun execute(
        request: SafShadowProbeRequest,
        audioProbeApi: SafShadowAudioProbeApi,
        strongFingerprintApi: SafStrongResourceFingerprintApi =
            NoopSafStrongResourceFingerprintApi,
    ): SafShadowProbeExecutionResult {
        val cachedById = request.currentSongs.associateBy(Song::id)
        val provisional = linkedMapOf<String, Song>()
        val provisionalLyrics = linkedMapOf<String, ScannedSongLyrics>()
        val strongValidated = linkedMapOf<String, String>()
        // Budget-deferred work already lives in probePlan.budgetDeferred. Do not allocate one
        // shadow issue per deferred object: on a 10k dirty pass that would create ~10k transient
        // objects every round even though RetryLedger intentionally ignores this condition.
        val issues = mutableListOf<SafShadowProbeIssue>()
        var attempted = 0
        var unknownVerifyWallTimeMs = 0L

        request.probePlan.ready.forEach { plan ->
            val entry = plan.entry
            if (
                request.playbackSnapshotProvider().blocksHeavyProbe(
                    stableObjectKey = entry.stableObjectKey,
                    mediaUri = entry.mediaUri,
                )
            ) {
                issues += SafShadowProbeIssue(
                    stableObjectKey = entry.stableObjectKey,
                    kind = SafShadowProbeIssueKind.PLAYBACK_DEFERRED,
                )
                return@forEach
            }
            if (entry.probeDraft == null) {
                issues += SafShadowProbeIssue(
                    stableObjectKey = entry.stableObjectKey,
                    kind = SafShadowProbeIssueKind.DRAFT_UNAVAILABLE,
                    detail = "metadata snapshot did not retain transient probe draft",
                )
                return@forEach
            }

            val requiresStrongValidation =
                SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY in plan.reasons
            if (
                requiresStrongValidation &&
                unknownVerifyWallTimeMs >= request.unknownVerifyWallTimeBudgetMs
            ) {
                issues += SafShadowProbeIssue(
                    stableObjectKey = entry.stableObjectKey,
                    kind = SafShadowProbeIssueKind.UNKNOWN_VERIFY_BUDGET_DEFERRED,
                    detail = "unknown deep-verify wall-time budget exhausted",
                )
                return@forEach
            }

            val unknownStartedAtMs = if (requiresStrongValidation) {
                request.monotonicTimeMsProvider()
            } else {
                null
            }
            try {
                val strongBefore = if (requiresStrongValidation) {
                    runCatching { strongFingerprintApi.fingerprint(entry) }.getOrNull()
                } else {
                    null
                }
                if (requiresStrongValidation && strongBefore == null) {
                    issues += SafShadowProbeIssue(
                        stableObjectKey = entry.stableObjectKey,
                        kind = SafShadowProbeIssueKind.PROBE_FAILED,
                        detail = "strong-fingerprint-before-unavailable",
                    )
                    return@forEach
                }

                attempted += 1
                val scanned = runCatching {
                    audioProbeApi.probe(entry, cachedById[entry.stableObjectKey])
                }.getOrElse { error ->
                    issues += SafShadowProbeIssue(
                        stableObjectKey = entry.stableObjectKey,
                        kind = SafShadowProbeIssueKind.PROBE_FAILED,
                        detail = error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                    )
                    return@forEach
                }

                when (val lyrics = scanned.lyrics) {
                    is LyricsProbeResult.Complete -> {
                        provisionalLyrics[entry.stableObjectKey] = ScannedSongLyrics(
                            songId = entry.stableObjectKey,
                            revision = scanned.song.lyricsCacheRevision,
                            slots = lyrics.slots,
                        )
                    }
                    LyricsProbeResult.ReadFailed -> {
                        issues += SafShadowProbeIssue(
                            stableObjectKey = entry.stableObjectKey,
                            kind = SafShadowProbeIssueKind.PROBE_FAILED,
                            detail = "lyrics-read-failed",
                        )
                        return@forEach
                    }
                    LyricsProbeResult.NotProbed -> {
                        issues += SafShadowProbeIssue(
                            stableObjectKey = entry.stableObjectKey,
                            kind = SafShadowProbeIssueKind.PROBE_FAILED,
                            detail = "lyrics-not-probed",
                        )
                        return@forEach
                    }
                }

                if (requiresStrongValidation) {
                    val strongAfter =
                        runCatching { strongFingerprintApi.fingerprint(entry) }.getOrNull()
                    if (strongAfter == null) {
                        issues += SafShadowProbeIssue(
                            stableObjectKey = entry.stableObjectKey,
                            kind = SafShadowProbeIssueKind.PROBE_FAILED,
                            detail = "strong-fingerprint-after-unavailable",
                        )
                        return@forEach
                    }
                    if (strongBefore != strongAfter) {
                        issues += SafShadowProbeIssue(
                            stableObjectKey = entry.stableObjectKey,
                            kind = SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
                            detail = "strong-resource-fingerprint-changed-during-probe",
                        )
                        return@forEach
                    }
                    strongValidated[entry.stableObjectKey] = strongAfter
                }

                provisional[entry.stableObjectKey] = retainScannedSong(scanned).copy(
                    id = entry.stableObjectKey,
                )
            } finally {
                if (unknownStartedAtMs != null) {
                    unknownVerifyWallTimeMs += (
                        request.monotonicTimeMsProvider() - unknownStartedAtMs
                        ).coerceAtLeast(0L)
                }
            }
        }

        return SafShadowProbeExecutionResult(
            provisionalSongsByStableObjectKey = provisional.toMap(),
            provisionalLyricsByStableObjectKey = provisionalLyrics.toMap(),
            strongValidatedFingerprintsByStableObjectKey = strongValidated.toMap(),
            issues = issues.toList(),
            attemptedCount = attempted,
            unknownVerifyWallTimeMs = unknownVerifyWallTimeMs,
        )
    }
}

internal object SafShadowPostProbeValidator {
    fun validate(
        initialSnapshot: SafTreeMetadataSnapshot,
        postSnapshot: SafTreeMetadataSnapshot,
        execution: SafShadowProbeExecutionResult,
    ): SafShadowPostValidationResult {
        val initialByKey = initialSnapshot.entries.associateBy(SafTreeMetadataEntry::stableObjectKey)
        val postByKey = postSnapshot.entries.associateBy(SafTreeMetadataEntry::stableObjectKey)
        val resolved = linkedMapOf<String, Song>()
        val issues = execution.issues.toMutableList()

        execution.provisionalSongsByStableObjectKey.forEach { (stableKey, song) ->
            val initial = initialByKey[stableKey]
            val post = postByKey[stableKey]
            when {
                initial == null || post == null -> {
                    issues += SafShadowProbeIssue(
                        stableObjectKey = stableKey,
                        kind = SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
                        detail = if (post == null) "object-missing-after-probe" else "initial-observation-missing",
                    )
                }
                !initial.hasSameObservedRevision(post) -> {
                    issues += SafShadowProbeIssue(
                        stableObjectKey = stableKey,
                        kind = SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
                        detail = "metadata revision changed during probe window",
                    )
                }
                initial.fingerprintReliability == SafFingerprintReliability.UNKNOWN ||
                    post.fingerprintReliability == SafFingerprintReliability.UNKNOWN -> {
                    if (
                        execution.strongValidatedFingerprintsByStableObjectKey
                            .containsKey(stableKey)
                    ) {
                        resolved[stableKey] = song
                    } else {
                        issues += SafShadowProbeIssue(
                            stableObjectKey = stableKey,
                            kind = SafShadowProbeIssueKind.UNVERIFIABLE_FINGERPRINT,
                            detail = "provider fingerprint unavailable and no strong verify",
                        )
                    }
                }
                else -> resolved[stableKey] = song
            }
        }

        val resolvedKeys = resolved.keys
        return SafShadowPostValidationResult(
            resolvedSongsByStableObjectKey = resolved.toMap(),
            resolvedLyricsByStableObjectKey =
                execution.provisionalLyricsByStableObjectKey.filterKeys { it in resolvedKeys },
            issues = issues.toList(),
        )
    }
}

internal fun defaultReadOnlySafShadowAudioProbe(
    context: android.content.Context,
    options: ScanOptions,
): SafShadowAudioProbeApi = SafShadowAudioProbeApi { entry, cachedSong ->
    val draft = requireNotNull(entry.probeDraft)
    if (options.deepMetadataProbe) {
        AudioMetadataProbe.probeTrack(
            context = context,
            draft = draft,
            cachedSong = cachedSong,
            artifactPolicy = AudioMetadataProbeArtifactPolicy.READ_ONLY_CANONICAL,
        )
    } else {
        AudioMetadataProbe.quickSong(
            context = context,
            draft = draft,
            cachedSong = cachedSong,
            artifactPolicy = AudioMetadataProbeArtifactPolicy.READ_ONLY_CANONICAL,
        )
    }
}


internal fun defaultSafStrongResourceFingerprintApi(
    context: android.content.Context,
): SafStrongResourceFingerprintApi = SafStrongResourceFingerprintApi { entry ->
    strongResourceFingerprint(context, entry)
}

private fun strongResourceFingerprint(
    context: android.content.Context,
    entry: SafTreeMetadataEntry,
): String? {
    val resourceUris = buildList {
        add("audio" to entry.mediaUri)
        entry.probeDraft?.externalLyricsUris
            .orEmpty()
            .distinct()
            .sorted()
            .forEach { add("lyrics" to it) }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_STRONG_FINGERPRINT_BUFFER_BYTES)

    resourceUris.forEach { (kind, uriString) ->
        digest.update(kind.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(uriString.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull() ?: return null
        val stream = runCatching {
            context.contentResolver.openInputStream(uri)
        }.getOrNull() ?: return null
        stream.use { input ->
            while (true) {
                val read = runCatching { input.read(buffer) }.getOrElse { return null }
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        digest.update(0x7f.toByte())
    }

    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

/**
 * S4-frozen between-object wall budget for one UNKNOWN deep-verify pass.
 *
 * This is intentionally much larger than the app-owned 10k profiler's normal 32-object batch
 * time; it remains a safety stop for slow/remote providers rather than a target runtime. The
 * executor does not interrupt an object already being read/probed: once accumulated UNKNOWN work
 * reaches this budget it refuses to start the next UNKNOWN object and leaves that debt deferred.
 */
internal const val UNKNOWN_VERIFY_WALL_TIME_BUDGET_MS = 30_000L
private const val DEFAULT_STRONG_FINGERPRINT_BUFFER_BYTES = 64 * 1024
