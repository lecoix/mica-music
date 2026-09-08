package com.mica.music.data.scanner

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.mica.music.data.DsdSupport
import java.io.IOException

internal enum class DeviceDeltaChannel {
    AUDIO,
    FILES_FALLBACK,
    LYRICS_SIDECAR,
}

internal enum class DeviceEligibilityAuthority {
    AUTHORITATIVE,
    PROVIDER_PENDING_TRANSIENT,
    PROVIDER_METADATA_TRANSIENT,
}

internal data class DeviceEligibilityDecision(
    val eligibility: LibraryEligibility,
    val authority: DeviceEligibilityAuthority,
)

internal data class DeviceDeltaWindow(
    val volumeName: String,
    val providerVersion: String,
    val fromGenerationExclusive: Long,
    val toGenerationInclusive: Long,
) {
    init {
        require(volumeName.isNotBlank())
        require(providerVersion.isNotBlank())
        require(fromGenerationExclusive >= 0L)
        require(toGenerationInclusive >= fromGenerationExclusive)
    }
}

internal data class DeviceDeltaRow(
    val channel: DeviceDeltaChannel,
    val volumeName: String,
    val mediaStoreId: Long,
    val mediaUri: String,
    val displayName: String,
    val mimeType: String,
    val relativePath: String,
    val sizeBytes: Long,
    val dateModifiedMs: Long,
    val generationAdded: Long,
    val generationModified: Long,
    val eligibility: LibraryEligibility,
    val eligibilityAuthority: DeviceEligibilityAuthority =
        DeviceEligibilityAuthority.AUTHORITATIVE,
) {
    val stableObjectKey: String
        get() = when (channel) {
            DeviceDeltaChannel.AUDIO,
            DeviceDeltaChannel.FILES_FALLBACK,
            -> MediaStorePresenceInventory.mediaStoreStableObjectKey(mediaStoreId)
            DeviceDeltaChannel.LYRICS_SIDECAR -> "sidecar:$volumeName:$mediaStoreId"
        }

    val observedGeneration: Long
        get() = maxOf(generationAdded, generationModified)
}

internal data class DeviceDeltaChannelStatus(
    val volumeName: String,
    val channel: DeviceDeltaChannel,
    val completeness: DiscoveryCompleteness,
    val detail: String = "",
)

internal data class DeviceDeltaIdentityConflict(
    val stableObjectKey: String,
    val volumeNames: Set<String>,
)

internal data class DeviceMediaStoreDeltaBatch(
    val windows: List<DeviceDeltaWindow>,
    val rows: List<DeviceDeltaRow>,
    val statuses: List<DeviceDeltaChannelStatus>,
    val identityConflicts: List<DeviceDeltaIdentityConflict>,
) {
    val transientRows: List<DeviceDeltaRow>
        get() = rows.filter {
            it.eligibilityAuthority != DeviceEligibilityAuthority.AUTHORITATIVE
        }

    val complete: Boolean
        get() = statuses.isNotEmpty() &&
            statuses.all { it.completeness == DiscoveryCompleteness.COMPLETE } &&
            identityConflicts.isEmpty()

    fun rows(channel: DeviceDeltaChannel): List<DeviceDeltaRow> = rows.filter { it.channel == channel }
}

internal fun interface DeviceMediaStoreDeltaQueryApi {
    fun query(window: DeviceDeltaWindow, channel: DeviceDeltaChannel): List<DeviceDeltaRow>
}

internal object DeviceMediaStoreDeltaReader {
    fun windows(
        from: DeviceGenerationSnapshot.Available,
        to: DeviceGenerationSnapshot.Available,
    ): List<DeviceDeltaWindow> {
        require(from.volumes.keys == to.volumes.keys)
        return to.volumes.keys.sorted().map { volumeName ->
            val before = requireNotNull(from.volumes[volumeName])
            val after = requireNotNull(to.volumes[volumeName])
            require(before.providerVersion == after.providerVersion)
            require(after.generation >= before.generation)
            DeviceDeltaWindow(
                volumeName = volumeName,
                providerVersion = after.providerVersion,
                fromGenerationExclusive = before.generation,
                toGenerationInclusive = after.generation,
            )
        }
    }

    fun read(
        api: DeviceMediaStoreDeltaQueryApi,
        from: DeviceGenerationSnapshot.Available,
        to: DeviceGenerationSnapshot.Available,
    ): DeviceMediaStoreDeltaBatch {
        val windows = windows(from, to)
        val rows = mutableListOf<DeviceDeltaRow>()
        val statuses = mutableListOf<DeviceDeltaChannelStatus>()
        windows.forEach { window ->
            DeviceDeltaChannel.entries.forEach { channel ->
                if (window.fromGenerationExclusive == window.toGenerationInclusive) {
                    statuses += DeviceDeltaChannelStatus(
                        window.volumeName,
                        channel,
                        DiscoveryCompleteness.COMPLETE,
                    )
                    return@forEach
                }
                runCatching { api.query(window, channel) }.fold(
                    onSuccess = { channelRows ->
                        rows += channelRows
                        statuses += DeviceDeltaChannelStatus(
                            window.volumeName,
                            channel,
                            DiscoveryCompleteness.COMPLETE,
                        )
                    },
                    onFailure = { error ->
                        statuses += DeviceDeltaChannelStatus(
                            volumeName = window.volumeName,
                            channel = channel,
                            completeness = DiscoveryCompleteness.PARTIAL,
                            detail = error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                        )
                    },
                )
            }
        }
        val conflicts = rows
            .asSequence()
            .filter { it.channel != DeviceDeltaChannel.LYRICS_SIDECAR }
            .groupBy(DeviceDeltaRow::stableObjectKey)
            .mapNotNull { (stableObjectKey, sameIdRows) ->
                sameIdRows.mapTo(linkedSetOf(), DeviceDeltaRow::volumeName)
                    .takeIf { it.size > 1 }
                    ?.let { DeviceDeltaIdentityConflict(stableObjectKey, it) }
            }
        return DeviceMediaStoreDeltaBatch(
            windows = windows,
            rows = rows.distinctBy {
                "${it.channel}:${it.volumeName}:${it.mediaStoreId}:${it.observedGeneration}"
            },
            statuses = statuses,
            identityConflicts = conflicts,
        )
    }
}

internal fun deviceAudioEligibilityDecision(
    displayName: String,
    mimeType: String,
    isMusic: Boolean,
    durationMs: Long,
    pending: Boolean,
    trashed: Boolean,
    excluded: Boolean,
    options: ScanOptions,
): DeviceEligibilityDecision {
    val eligibleByType = if (options.includeNonMusicByMime) {
        isMusic || mimeType.startsWith("audio/", ignoreCase = true)
    } else {
        isMusic
    }
    val eligibleByDuration =
        options.minDurationMs <= 0L || durationMs <= 0L || durationMs >= options.minDurationMs
    val eligibility = MediaStorePresenceInventory.mediaStoreEligibility(
        pending = pending,
        trashed = trashed,
        eligibleByType = eligibleByType,
        eligibleByDuration = eligibleByDuration,
        excluded = excluded,
    )
    val extension = displayName.substringAfterLast('.', "").lowercase()
    val providerTypeMetadataStillUnresolved =
        !pending &&
            !trashed &&
            !excluded &&
            !eligibleByType &&
            durationMs <= 0L &&
            extension in DEVICE_SUPPORTED_AUDIO_EXTENSIONS
    return DeviceEligibilityDecision(
        eligibility = eligibility,
        authority = when {
            pending -> DeviceEligibilityAuthority.PROVIDER_PENDING_TRANSIENT
            providerTypeMetadataStillUnresolved ->
                DeviceEligibilityAuthority.PROVIDER_METADATA_TRANSIENT
            else -> DeviceEligibilityAuthority.AUTHORITATIVE
        },
    )
}

private val DEVICE_SUPPORTED_AUDIO_EXTENSIONS = setOf(
    "mp3",
    "flac",
    "m4a",
    "aac",
    "ogg",
    "opus",
    "wav",
    "ape",
    "wma",
    "alac",
    "aiff",
    "aif",
) + DsdSupport.extensions

internal class AndroidDeviceMediaStoreDeltaQueryApi(
    context: Context,
    private val options: ScanOptions,
) : DeviceMediaStoreDeltaQueryApi {
    private val resolver = context.applicationContext.contentResolver

    override fun query(
        window: DeviceDeltaWindow,
        channel: DeviceDeltaChannel,
    ): List<DeviceDeltaRow> {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        return when (channel) {
            DeviceDeltaChannel.AUDIO -> queryAudio(window)
            DeviceDeltaChannel.FILES_FALLBACK -> queryFiles(
                window = window,
                extensions = MediaStoreScanner.FILE_EXTENSION_FALLBACKS,
                channel = channel,
            )
            DeviceDeltaChannel.LYRICS_SIDECAR -> queryFiles(
                window = window,
                extensions = setOf("lrc", "ttml"),
                channel = channel,
            )
        }
    }

    private fun queryAudio(window: DeviceDeltaWindow): List<DeviceDeltaRow> {
        val uri = MediaStore.Audio.Media.getContentUri(window.volumeName)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
        )
        val cursor = queryGenerationWindow(uri, projection, window, extraSelection = null, extraArgs = emptyArray())
            ?: throw IOException("MediaStore audio delta query returned no cursor")
        return cursor.use { c ->
            val rows = mutableListOf<DeviceDeltaRow>()
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            val durationCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val isMusicCol = c.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
            val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val modifiedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val relativePathCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val pendingCol = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            val trashedCol = c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
            val addedGenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_ADDED)
            val modifiedGenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val displayName = c.getStringOrEmpty(nameCol)
                val mime = c.getStringOrEmpty(mimeCol)
                val durationMs = c.getLongOrZero(durationCol)
                val isMusic = c.getIntOrZero(isMusicCol) != 0
                val relativePath = c.getStringOrEmpty(relativePathCol).trim('/')
                val pending = c.getIntOrZero(pendingCol) != 0
                val trashed = c.getIntOrZero(trashedCol) != 0
                val excluded = ExcludedScanDirectories.isExcluded(
                    relativePath,
                    options.excludedDirectories,
                )
                val eligibilityDecision = deviceAudioEligibilityDecision(
                    displayName = displayName,
                    mimeType = mime,
                    isMusic = isMusic,
                    durationMs = durationMs,
                    pending = pending,
                    trashed = trashed,
                    excluded = excluded,
                    options = options,
                )
                rows += DeviceDeltaRow(
                    channel = DeviceDeltaChannel.AUDIO,
                    volumeName = window.volumeName,
                    mediaStoreId = id,
                    mediaUri = MediaStore.Audio.Media.getContentUri(window.volumeName, id).toString(),
                    displayName = displayName,
                    mimeType = mime,
                    relativePath = relativePath,
                    sizeBytes = c.getLongOrZero(sizeCol),
                    dateModifiedMs = c.getLongOrZero(modifiedCol) * 1000L,
                    generationAdded = c.getLong(addedGenCol),
                    generationModified = c.getLong(modifiedGenCol),
                    eligibility = eligibilityDecision.eligibility,
                    eligibilityAuthority = eligibilityDecision.authority,
                )
            }
            rows
        }
    }

    private fun queryFiles(
        window: DeviceDeltaWindow,
        extensions: Set<String>,
        channel: DeviceDeltaChannel,
    ): List<DeviceDeltaRow> {
        val uri = MediaStore.Files.getContentUri(window.volumeName)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
        )
        val extensionSelection = extensions.joinToString(" OR ") {
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?"
        }
        val extensionArgs = extensions.map { "%.$it" }.toTypedArray()
        val cursor = queryGenerationWindow(
            uri = uri,
            projection = projection,
            window = window,
            extraSelection = "($extensionSelection)",
            extraArgs = extensionArgs,
        ) ?: throw IOException("MediaStore files delta query returned no cursor for $channel")
        return cursor.use { c ->
            val rows = mutableListOf<DeviceDeltaRow>()
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val modifiedCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val relativePathCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val pendingCol = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            val trashedCol = c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
            val addedGenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_ADDED)
            val modifiedGenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val relativePath = c.getStringOrEmpty(relativePathCol).trim('/')
                val pending = c.getIntOrZero(pendingCol) != 0
                val trashed = c.getIntOrZero(trashedCol) != 0
                val eligibility = if (channel == DeviceDeltaChannel.LYRICS_SIDECAR) {
                    when {
                        pending -> LibraryEligibility.PENDING
                        trashed -> LibraryEligibility.TRASHED
                        else -> LibraryEligibility.ELIGIBLE
                    }
                } else {
                    MediaStorePresenceInventory.mediaStoreEligibility(
                        pending = pending,
                        trashed = trashed,
                        eligibleByType = true,
                        eligibleByDuration = true,
                        excluded = ExcludedScanDirectories.isExcluded(
                            relativePath,
                            options.excludedDirectories,
                        ),
                    )
                }
                rows += DeviceDeltaRow(
                    channel = channel,
                    volumeName = window.volumeName,
                    mediaStoreId = id,
                    mediaUri = Uri.withAppendedPath(uri, id.toString()).toString(),
                    displayName = c.getStringOrEmpty(nameCol),
                    mimeType = c.getStringOrEmpty(mimeCol),
                    relativePath = relativePath,
                    sizeBytes = c.getLongOrZero(sizeCol),
                    dateModifiedMs = c.getLongOrZero(modifiedCol) * 1000L,
                    generationAdded = c.getLong(addedGenCol),
                    generationModified = c.getLong(modifiedGenCol),
                    eligibility = eligibility,
                    eligibilityAuthority = if (pending) {
                        DeviceEligibilityAuthority.PROVIDER_PENDING_TRANSIENT
                    } else {
                        DeviceEligibilityAuthority.AUTHORITATIVE
                    },
                )
            }
            rows
        }
    }

    private fun queryGenerationWindow(
        uri: Uri,
        projection: Array<String>,
        window: DeviceDeltaWindow,
        extraSelection: String?,
        extraArgs: Array<String>,
    ): Cursor? {
        val added = MediaStore.MediaColumns.GENERATION_ADDED
        val modified = MediaStore.MediaColumns.GENERATION_MODIFIED
        val generationSelection =
            "(($added > ? AND $added <= ?) OR ($modified > ? AND $modified <= ?))"
        val selection = if (extraSelection.isNullOrBlank()) {
            generationSelection
        } else {
            "$generationSelection AND $extraSelection"
        }
        val args = arrayOf(
            window.fromGenerationExclusive.toString(),
            window.toGenerationInclusive.toString(),
            window.fromGenerationExclusive.toString(),
            window.toGenerationInclusive.toString(),
            *extraArgs,
        )
        val queryArgs = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        }
        return resolver.query(uri, projection, queryArgs, null)
    }

    private fun Cursor.getStringOrEmpty(columnIndex: Int): String =
        if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex).orEmpty() else ""

    private fun Cursor.getLongOrZero(columnIndex: Int): Long =
        if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else 0L

    private fun Cursor.getIntOrZero(columnIndex: Int): Int =
        if (columnIndex >= 0 && !isNull(columnIndex)) getInt(columnIndex) else 0
}
