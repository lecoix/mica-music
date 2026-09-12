package com.mica.music.data.scanner

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.io.IOException

/**
 * Bounded DEVICE presence authority for ordinary AUTO passes.
 *
 * Only objects already tracked by Mica plus objects visible in the current generation delta are
 * queried. Absence remains authoritative for those requested ids, while unrelated MediaStore rows
 * never enter the hot path.
 */
internal object MediaStoreTrackedPresenceInventory {
    private const val ID_QUERY_CHUNK = 400

    fun load(
        context: Context,
        options: ScanOptions,
        trackedStableObjectKeys: Set<String>,
        deltaBatch: DeviceMediaStoreDeltaBatch,
    ): PresenceInventory {
        val targetIds = targetMediaStoreIds(trackedStableObjectKeys, deltaBatch)
        if (targetIds.isEmpty()) {
            return PresenceInventory.fromEntries(
                entries = emptyList(),
                discoveryReport = DiscoveryReport.of(
                    DiscoveryPartitionStatus(
                        DiscoveryPartitions.MEDIASTORE_AUDIO,
                        DiscoveryCompleteness.COMPLETE,
                    ),
                    DiscoveryPartitionStatus(
                        DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                        DiscoveryCompleteness.COMPLETE,
                    ),
                ),
            )
        }

        val entries = linkedMapOf<PresenceKey, PresenceEntry>()
        val statuses = mutableListOf<DiscoveryPartitionStatus>()
        val capabilities = linkedMapOf<String, DeviceMediaStoreChannelCapability>()
        val normalizedExcludedDirectories =
            ExcludedScanDirectories.normalizeAll(options.excludedDirectories)

        statuses += loadChannel(DiscoveryPartitions.MEDIASTORE_AUDIO) {
            loadAudioRows(
                context = context,
                options = options,
                targetIds = targetIds,
                normalizedExcludedDirectories = normalizedExcludedDirectories,
                out = entries,
                capabilities = capabilities,
            )
        }
        statuses += loadChannel(DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK) {
            loadFallbackRows(
                context = context,
                options = options,
                targetIds = targetIds,
                normalizedExcludedDirectories = normalizedExcludedDirectories,
                out = entries,
                capabilities = capabilities,
            )
        }

        return PresenceInventory(
            entries = entries,
            discoveryReport = DiscoveryReport.of(*statuses.toTypedArray()),
            deviceMediaStoreCapabilityProfile = DeviceMediaStorePresenceCapabilityProfile(
                channels = capabilities.toMap(),
            ),
        )
    }

    internal fun targetMediaStoreIds(
        trackedStableObjectKeys: Set<String>,
        deltaBatch: DeviceMediaStoreDeltaBatch,
    ): Set<Long> = buildSet {
        trackedStableObjectKeys.forEach { key ->
            mediaStoreIdFromStableObjectKey(key)?.let(::add)
        }
        deltaBatch.rows.asSequence()
            .filter { it.channel != DeviceDeltaChannel.LYRICS_SIDECAR }
            .mapTo(this) { it.mediaStoreId }
    }

    internal fun mediaStoreIdFromStableObjectKey(key: String): Long? =
        key.takeIf { it.startsWith("ms_") }
            ?.removePrefix("ms_")
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }

    private inline fun loadChannel(
        partitionKey: String,
        block: () -> Unit,
    ): DiscoveryPartitionStatus = runCatching(block).fold(
        onSuccess = {
            DiscoveryPartitionStatus(partitionKey, DiscoveryCompleteness.COMPLETE)
        },
        onFailure = { error ->
            DiscoveryPartitionStatus(
                partitionKey = partitionKey,
                completeness = DiscoveryCompleteness.PARTIAL,
                detail = error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            )
        },
    )

    private fun loadAudioRows(
        context: Context,
        options: ScanOptions,
        targetIds: Set<Long>,
        normalizedExcludedDirectories: List<String>,
        out: MutableMap<PresenceKey, PresenceEntry>,
        capabilities: MutableMap<String, DeviceMediaStoreChannelCapability>,
    ) {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.IS_MUSIC)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
                add(MediaStore.MediaColumns.IS_PENDING)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.IS_TRASHED)
            }
        }.toTypedArray()

        targetIds.sorted().chunked(ID_QUERY_CHUNK).forEach { ids ->
            val selection = idSelection(MediaStore.Audio.Media._ID, ids.size)
            val cursor = queryIncludingHiddenRows(
                resolver = context.contentResolver,
                uri = uri,
                projection = projection,
                selection = selection,
                selectionArgs = ids.map(Long::toString).toTypedArray(),
            ) ?: throw IOException("MediaStore tracked audio presence query returned no cursor")

            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val isMusicCol = c.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
                val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val durationCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val modifiedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1
                @Suppress("DEPRECATION")
                val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Audio.Media.DATA)
                } else -1
                val pendingCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                } else -1
                val trashedCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                } else -1
                capabilities[DiscoveryPartitions.MEDIASTORE_AUDIO] = mediaStoreChannelCapability(
                    partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
                    cursor = c,
                    context = context,
                )

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val isMusic = c.getIntOrZero(isMusicCol) != 0
                    val mime = c.getStringOrEmpty(mimeCol)
                    val durationMs = c.getLongOrZero(durationCol)
                    val sizeBytes = c.getLongOrZero(sizeCol)
                    val modifiedMs = c.getLongOrZero(modifiedCol) * 1000L
                    val pending = c.getIntOrZero(pendingCol) != 0
                    val trashed = c.getIntOrZero(trashedCol) != 0
                    val folderPath = when {
                        relativePathCol >= 0 -> c.getString(relativePathCol).orEmpty().trim('/')
                        dataCol >= 0 -> c.getString(dataCol)?.substringBeforeLast('/', "").orEmpty()
                        else -> ""
                    }
                    val eligibleByType = if (options.includeNonMusicByMime) {
                        isMusic || mime.startsWith("audio/", ignoreCase = true)
                    } else isMusic
                    val eligibleByDuration = options.minDurationMs <= 0L ||
                        durationMs <= 0L || durationMs >= options.minDurationMs
                    val excluded = ExcludedScanDirectories.isExcludedNormalized(
                        folderPath,
                        normalizedExcludedDirectories,
                    )
                    putEntry(
                        out = out,
                        id = id,
                        partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
                        sizeBytes = sizeBytes,
                        modifiedMs = modifiedMs,
                        pending = pending,
                        trashed = trashed,
                        eligibility = MediaStorePresenceInventory.mediaStoreEligibility(
                            pending = pending,
                            trashed = trashed,
                            eligibleByType = eligibleByType,
                            eligibleByDuration = eligibleByDuration,
                            excluded = excluded,
                        ),
                    )
                }
            }
        }
    }

    private fun loadFallbackRows(
        context: Context,
        options: ScanOptions,
        targetIds: Set<Long>,
        normalizedExcludedDirectories: List<String>,
        out: MutableMap<PresenceKey, PresenceEntry>,
        capabilities: MutableMap<String, DeviceMediaStoreChannelCapability>,
    ) {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.SIZE)
            add(MediaStore.Files.FileColumns.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Files.FileColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.IS_PENDING)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Files.FileColumns.DATA)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.IS_TRASHED)
            }
        }.toTypedArray()
        val extensionSelection = MediaStoreScanner.FILE_EXTENSION_FALLBACKS.joinToString(" OR ") {
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?"
        }
        val extensionArgs = MediaStoreScanner.FILE_EXTENSION_FALLBACKS.map { "%.$it" }

        targetIds.sorted().chunked(ID_QUERY_CHUNK).forEach { ids ->
            val idsSelection = idSelection(MediaStore.Files.FileColumns._ID, ids.size)
            val cursor = queryIncludingHiddenRows(
                resolver = context.contentResolver,
                uri = uri,
                projection = projection,
                selection = "($extensionSelection) AND ($idsSelection)",
                selectionArgs = (extensionArgs + ids.map(Long::toString)).toTypedArray(),
            ) ?: throw IOException("MediaStore tracked fallback presence query returned no cursor")

            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val sizeCol = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val modifiedCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                } else -1
                @Suppress("DEPRECATION")
                val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                } else -1
                val pendingCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                } else -1
                val trashedCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                } else -1
                capabilities[DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK] =
                    mediaStoreChannelCapability(
                        partitionKey = DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                        cursor = c,
                        context = context,
                    )

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val sizeBytes = c.getLongOrZero(sizeCol)
                    val modifiedMs = c.getLongOrZero(modifiedCol) * 1000L
                    val pending = c.getIntOrZero(pendingCol) != 0
                    val trashed = c.getIntOrZero(trashedCol) != 0
                    val folderPath = when {
                        relativePathCol >= 0 -> c.getString(relativePathCol).orEmpty().trim('/')
                        dataCol >= 0 -> c.getString(dataCol)?.substringBeforeLast('/', "").orEmpty()
                        else -> ""
                    }
                    putEntry(
                        out = out,
                        id = id,
                        partitionKey = DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                        sizeBytes = sizeBytes,
                        modifiedMs = modifiedMs,
                        pending = pending,
                        trashed = trashed,
                        eligibility = MediaStorePresenceInventory.mediaStoreEligibility(
                            pending = pending,
                            trashed = trashed,
                            eligibleByType = true,
                            eligibleByDuration = true,
                            excluded = ExcludedScanDirectories.isExcludedNormalized(
                                folderPath,
                                normalizedExcludedDirectories,
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun putEntry(
        out: MutableMap<PresenceKey, PresenceEntry>,
        id: Long,
        partitionKey: String,
        sizeBytes: Long,
        modifiedMs: Long,
        pending: Boolean,
        trashed: Boolean,
        eligibility: LibraryEligibility,
    ) {
        val entry = PresenceEntry(
            stableObjectKey = MediaStorePresenceInventory.mediaStoreStableObjectKey(id),
            partitionKey = partitionKey,
            eligibility = eligibility,
            evidenceRevision = "$sizeBytes|$modifiedMs|${if (pending) 1 else 0}|" +
                "${if (trashed) 1 else 0}|${eligibility.name}",
        )
        out[PresenceKey(entry.stableObjectKey, entry.partitionKey)] = entry
    }

    private fun idSelection(column: String, count: Int): String =
        "$column IN (${List(count) { "?" }.joinToString(",")})"

    private fun queryIncludingHiddenRows(
        resolver: ContentResolver,
        uri: Uri,
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>,
    ): Cursor? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
            val queryArgs = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            }
            resolver.query(uri, projection, queryArgs, null)
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            @Suppress("DEPRECATION")
            resolver.query(
                MediaStore.setIncludePending(uri), projection, selection, selectionArgs, null,
            )
        }
        else -> resolver.query(uri, projection, selection, selectionArgs, null)
    }

    private fun mediaStoreChannelCapability(
        partitionKey: String,
        cursor: Cursor,
        context: Context,
    ): DeviceMediaStoreChannelCapability {
        val permission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> Manifest.permission.READ_EXTERNAL_STORAGE
            else -> null
        }
        val permissionGranted = permission == null ||
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val permissionScope = permission?.let { name ->
            "${name.substringAfterLast('.')}=" + if (permissionGranted) "granted" else "denied"
        } ?: "install-time-storage-permission"
        return DeviceMediaStoreChannelCapability(
            partitionKey = partitionKey,
            columnsAvailable = cursor.columnNames.toSet(),
            rowInclusionSemanticsKnown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            permissionScope = permissionScope,
            permissionScopeComplete = permissionGranted,
            volumeScope = "external-aggregate:tracked-ids",
            apiLevel = Build.VERSION.SDK_INT,
        )
    }

    private fun Cursor.getStringOrEmpty(columnIndex: Int): String =
        if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex).orEmpty() else ""

    private fun Cursor.getLongOrZero(columnIndex: Int): Long =
        if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else 0L

    private fun Cursor.getIntOrZero(columnIndex: Int): Int =
        if (columnIndex >= 0 && !isNull(columnIndex)) getInt(columnIndex) else 0
}
