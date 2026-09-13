package com.mica.music.data.library

import com.mica.music.data.LyricsProbeResult
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.Song
import com.mica.music.data.scanner.AudioMetadataProbe
import com.mica.music.data.scanner.AudioMetadataProbeArtifactPolicy
import com.mica.music.data.scanner.SafFingerprintReliability
import com.mica.music.data.scanner.SafTargetedMetadataSnapshot
import com.mica.music.data.scanner.SafTreeMetadataEntry
import com.mica.music.data.scanner.SafTreeMetadataSnapshot
import com.mica.music.data.scanner.ScanOptions
import com.mica.music.data.scanner.ScannedSong
import com.mica.music.data.scanner.hasSameObservedRevision
import com.mica.music.data.scanner.retainScannedSong
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

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
    private val autoProbeThreadIds = AtomicInteger(0)
    private val autoProbeExecutor = Executors.newFixedThreadPool(
        SafAutoProbePlanner.SYSTEM_EXTERNAL_STORAGE_HEAVY_PROBE_PARALLELISM,
        ThreadFactory { runnable ->
            Thread(
                runnable,
                "mica-saf-auto-probe-${autoProbeThreadIds.incrementAndGet()}",
            ).apply { isDaemon = true }
        },
    )

    private data class ObjectProbeResult(
        val stableObjectKey: String,
        val provisionalSong: Song? = null,
        val provisionalLyrics: ScannedSongLyrics? = null,
        val strongValidatedFingerprint: String? = null,
        val issue: SafShadowProbeIssue? = null,
        val attempted: Boolean = false,
        val unknownVerifyWallTimeMs: Long = 0L,
    )

    fun execute(
        request: SafShadowProbeRequest,
        audioProbeApi: SafShadowAudioProbeApi,
        strongFingerprintApi: SafStrongResourceFingerprintApi =
            NoopSafStrongResourceFingerprintApi,
    ): SafShadowProbeExecutionResult {
        val cachedById = request.currentSongs.associateBy(Song::id)
        val concreteReady = request.probePlan.ready.filterNot { plan ->
            SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY in plan.reasons
        }
        val unknownReady = request.probePlan.ready.filter { plan ->
            SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY in plan.reasons
        }

        val concreteResults = executeConcreteBatch(
            plans = concreteReady,
            parallelism = request.probePlan.heavyProbeParallelism,
            request = request,
            cachedById = cachedById,
            audioProbeApi = audioProbeApi,
        )

        // UNKNOWN strong verification intentionally remains serialized. Its before/after fingerprint
        // pair and cumulative wall-time budget are a separate safety contract from ordinary
        // NEW/CHANGED burst probing.
        var unknownVerifyWallTimeMs = 0L
        val unknownResults = unknownReady.map { plan ->
            if (unknownVerifyWallTimeMs >= request.unknownVerifyWallTimeBudgetMs) {
                ObjectProbeResult(
                    stableObjectKey = plan.stableObjectKey,
                    issue = SafShadowProbeIssue(
                        stableObjectKey = plan.stableObjectKey,
                        kind = SafShadowProbeIssueKind.UNKNOWN_VERIFY_BUDGET_DEFERRED,
                        detail = "unknown deep-verify wall-time budget exhausted",
                    ),
                )
            } else {
                probeOne(
                    plan = plan,
                    request = request,
                    cachedSong = cachedById[plan.stableObjectKey],
                    audioProbeApi = audioProbeApi,
                    strongFingerprintApi = strongFingerprintApi,
                ).also { result ->
                    unknownVerifyWallTimeMs += result.unknownVerifyWallTimeMs
                }
            }
        }

        val provisional = linkedMapOf<String, Song>()
        val provisionalLyrics = linkedMapOf<String, ScannedSongLyrics>()
        val strongValidated = linkedMapOf<String, String>()
        val issues = mutableListOf<SafShadowProbeIssue>()
        var attempted = 0

        // Merge in planner order so result ordering stays deterministic even when concrete probes ran
        // concurrently.
        val resultsByKey = (concreteResults + unknownResults)
            .associateBy(ObjectProbeResult::stableObjectKey)
        request.probePlan.ready.forEach { plan ->
            val result = resultsByKey[plan.stableObjectKey] ?: return@forEach
            if (result.attempted) attempted += 1
            result.provisionalSong?.let { provisional[plan.stableObjectKey] = it }
            result.provisionalLyrics?.let { provisionalLyrics[plan.stableObjectKey] = it }
            result.strongValidatedFingerprint?.let {
                strongValidated[plan.stableObjectKey] = it
            }
            result.issue?.let(issues::add)
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

    private fun executeConcreteBatch(
        plans: List<SafAutoProbeObjectPlan>,
        parallelism: Int,
        request: SafShadowProbeRequest,
        cachedById: Map<String, Song>,
        audioProbeApi: SafShadowAudioProbeApi,
    ): List<ObjectProbeResult> {
        if (plans.isEmpty()) return emptyList()
        if (parallelism <= 1 || plans.size == 1) {
            return plans.map { plan ->
                probeOne(
                    plan = plan,
                    request = request,
                    cachedSong = cachedById[plan.stableObjectKey],
                    audioProbeApi = audioProbeApi,
                    strongFingerprintApi = NoopSafStrongResourceFingerprintApi,
                )
            }
        }

        return plans.chunked(parallelism).flatMap { chunk ->
            val futures = chunk.map { plan ->
                autoProbeExecutor.submit(Callable {
                    runCatching {
                        probeOne(
                            plan = plan,
                            request = request,
                            cachedSong = cachedById[plan.stableObjectKey],
                            audioProbeApi = audioProbeApi,
                            strongFingerprintApi = NoopSafStrongResourceFingerprintApi,
                        )
                    }.getOrElse { error ->
                        ObjectProbeResult(
                            stableObjectKey = plan.stableObjectKey,
                            issue = SafShadowProbeIssue(
                                stableObjectKey = plan.stableObjectKey,
                                kind = SafShadowProbeIssueKind.PROBE_FAILED,
                                detail = error.message.orEmpty().ifBlank {
                                    error.javaClass.simpleName
                                },
                            ),
                        )
                    }
                })
            }
            futures.mapIndexed { index, future ->
                runCatching { future.get() }.getOrElse { error ->
                    val plan = chunk[index]
                    ObjectProbeResult(
                        stableObjectKey = plan.stableObjectKey,
                        issue = SafShadowProbeIssue(
                            stableObjectKey = plan.stableObjectKey,
                            kind = SafShadowProbeIssueKind.PROBE_FAILED,
                            detail = error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                        ),
                    )
                }
            }
        }
    }

    private fun probeOne(
        plan: SafAutoProbeObjectPlan,
        request: SafShadowProbeRequest,
        cachedSong: Song?,
        audioProbeApi: SafShadowAudioProbeApi,
        strongFingerprintApi: SafStrongResourceFingerprintApi,
    ): ObjectProbeResult {
        val entry = plan.entry
        if (
            request.playbackSnapshotProvider().blocksHeavyProbe(
                stableObjectKey = entry.stableObjectKey,
                mediaUri = entry.mediaUri,
            )
        ) {
            return ObjectProbeResult(
                stableObjectKey = entry.stableObjectKey,
                issue = SafShadowProbeIssue(
                    stableObjectKey = entry.stableObjectKey,
                    kind = SafShadowProbeIssueKind.PLAYBACK_DEFERRED,
                ),
            )
        }
        if (entry.probeDraft == null) {
            return ObjectProbeResult(
                stableObjectKey = entry.stableObjectKey,
                issue = SafShadowProbeIssue(
                    stableObjectKey = entry.stableObjectKey,
                    kind = SafShadowProbeIssueKind.DRAFT_UNAVAILABLE,
                    detail = "metadata snapshot did not retain transient probe draft",
                ),
            )
        }

        val requiresStrongValidation =
            SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY in plan.reasons
        val unknownStartedAtMs = if (requiresStrongValidation) {
            request.monotonicTimeMsProvider()
        } else {
            null
        }
        fun unknownElapsedMs(): Long = unknownStartedAtMs?.let { started ->
            (request.monotonicTimeMsProvider() - started).coerceAtLeast(0L)
        } ?: 0L

        val strongBefore = if (requiresStrongValidation) {
            runCatching { strongFingerprintApi.fingerprint(entry) }.getOrNull()
        } else {
            null
        }
        if (requiresStrongValidation && strongBefore == null) {
            return ObjectProbeResult(
                stableObjectKey = entry.stableObjectKey,
                issue = SafShadowProbeIssue(
                    stableObjectKey = entry.stableObjectKey,
                    kind = SafShadowProbeIssueKind.PROBE_FAILED,
                    detail = "strong-fingerprint-before-unavailable",
                ),
                unknownVerifyWallTimeMs = unknownElapsedMs(),
            )
        }

        val scanned = runCatching {
            audioProbeApi.probe(entry, cachedSong)
        }.getOrElse { error ->
            return ObjectProbeResult(
                stableObjectKey = entry.stableObjectKey,
                issue = SafShadowProbeIssue(
                    stableObjectKey = entry.stableObjectKey,
                    kind = SafShadowProbeIssueKind.PROBE_FAILED,
                    detail = error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                ),
                attempted = true,
                unknownVerifyWallTimeMs = unknownElapsedMs(),
            )
        }

        val lyrics = when (val result = scanned.lyrics) {
            is LyricsProbeResult.Complete -> ScannedSongLyrics(
                songId = entry.stableObjectKey,
                revision = scanned.song.lyricsCacheRevision,
                slots = result.slots,
            )
            LyricsProbeResult.ReadFailed -> {
                return ObjectProbeResult(
                    stableObjectKey = entry.stableObjectKey,
                    issue = SafShadowProbeIssue(
                        stableObjectKey = entry.stableObjectKey,
                        kind = SafShadowProbeIssueKind.PROBE_FAILED,
                        detail = "lyrics-read-failed",
                    ),
                    attempted = true,
                    unknownVerifyWallTimeMs = unknownElapsedMs(),
                )
            }
            LyricsProbeResult.NotProbed -> {
                return ObjectProbeResult(
                    stableObjectKey = entry.stableObjectKey,
                    issue = SafShadowProbeIssue(
                        stableObjectKey = entry.stableObjectKey,
                        kind = SafShadowProbeIssueKind.PROBE_FAILED,
                        detail = "lyrics-not-probed",
                    ),
                    attempted = true,
                    unknownVerifyWallTimeMs = unknownElapsedMs(),
                )
            }
        }

        var strongValidated: String? = null
        if (requiresStrongValidation) {
            val strongAfter = runCatching { strongFingerprintApi.fingerprint(entry) }.getOrNull()
            if (strongAfter == null) {
                return ObjectProbeResult(
                    stableObjectKey = entry.stableObjectKey,
                    issue = SafShadowProbeIssue(
                        stableObjectKey = entry.stableObjectKey,
                        kind = SafShadowProbeIssueKind.PROBE_FAILED,
                        detail = "strong-fingerprint-after-unavailable",
                    ),
                    attempted = true,
                    unknownVerifyWallTimeMs = unknownElapsedMs(),
                )
            }
            if (strongBefore != strongAfter) {
                return ObjectProbeResult(
                    stableObjectKey = entry.stableObjectKey,
                    issue = SafShadowProbeIssue(
                        stableObjectKey = entry.stableObjectKey,
                        kind = SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
                        detail = "strong-resource-fingerprint-changed-during-probe",
                    ),
                    attempted = true,
                    unknownVerifyWallTimeMs = unknownElapsedMs(),
                )
            }
            strongValidated = strongAfter
        }

        return ObjectProbeResult(
            stableObjectKey = entry.stableObjectKey,
            provisionalSong = retainScannedSong(scanned).copy(id = entry.stableObjectKey),
            provisionalLyrics = lyrics,
            strongValidatedFingerprint = strongValidated,
            attempted = true,
            unknownVerifyWallTimeMs = unknownElapsedMs(),
        )
    }
}

internal object SafShadowPostProbeValidator {
    fun validate(
        initialSnapshot: SafTreeMetadataSnapshot,
        postSnapshot: SafTargetedMetadataSnapshot,
        execution: SafShadowProbeExecutionResult,
    ): SafShadowPostValidationResult {
        val validationKeys = execution.provisionalSongsByStableObjectKey.keys
        val initialByKey = initialSnapshot.entries.asSequence()
            .filter { it.stableObjectKey in validationKeys }
            .associateBy(SafTreeMetadataEntry::stableObjectKey)
        val postByKey = postSnapshot.entries.asSequence()
            .filter { it.stableObjectKey in validationKeys }
            .associateBy(SafTreeMetadataEntry::stableObjectKey)
        val resolved = linkedMapOf<String, Song>()
        val issues = execution.issues.toMutableList()

        execution.provisionalSongsByStableObjectKey.forEach { (stableKey, song) ->
            val initial = initialByKey[stableKey]
            val post = postByKey[stableKey]
            when {
                initial == null -> {
                    issues += SafShadowProbeIssue(
                        stableObjectKey = stableKey,
                        kind = SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
                        detail = "initial-observation-missing",
                    )
                }
                !postSnapshot.isFolderComplete(initial.folderPath) -> {
                    issues += SafShadowProbeIssue(
                        stableObjectKey = stableKey,
                        kind = SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
                        detail = "post-validation-folder-incomplete",
                    )
                }
                post == null -> {
                    issues += SafShadowProbeIssue(
                        stableObjectKey = stableKey,
                        kind = SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
                        detail = "object-missing-after-probe",
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
                    if (execution.strongValidatedFingerprintsByStableObjectKey.containsKey(stableKey)) {
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

    fun validate(
        initialSnapshot: SafTreeMetadataSnapshot,
        postSnapshot: SafTreeMetadataSnapshot,
        execution: SafShadowProbeExecutionResult,
    ): SafShadowPostValidationResult {
        val validationKeys = execution.provisionalSongsByStableObjectKey.keys
        val initialByKey = initialSnapshot.entries.asSequence()
            .filter { it.stableObjectKey in validationKeys }
            .associateBy(SafTreeMetadataEntry::stableObjectKey)
        val postByKey = postSnapshot.entries.asSequence()
            .filter { it.stableObjectKey in validationKeys }
            .associateBy(SafTreeMetadataEntry::stableObjectKey)
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
