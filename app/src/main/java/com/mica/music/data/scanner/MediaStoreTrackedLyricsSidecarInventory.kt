package com.mica.music.data.scanner

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.IOException

/** Sidecar inventory bounded to lyrics keys owned by the active DEVICE library and current delta. */
internal object MediaStoreTrackedLyricsSidecarInventory {
    private const val NAME_QUERY_CHUNK = 300

    fun load(
        context: Context,
        trackedLyricsKeys: Set<String>,
        deltaBatch: DeviceMediaStoreDeltaBatch,
    ): MediaStoreLyricsSidecarInventoryResult {
        val requestedKeys = requestedLyricsKeys(trackedLyricsKeys, deltaBatch)
        return runCatching { loadComplete(context, requestedKeys) }.fold(
            onSuccess = { refs ->
                MediaStoreLyricsSidecarInventoryResult(
                    refsByLyricsKey = refs,
                    status = DiscoveryPartitionStatus(
                        DiscoveryPartitions.MEDIASTORE_LYRICS_SIDECARS,
                        DiscoveryCompleteness.COMPLETE,
                    ),
                )
            },
            onFailure = { error ->
                MediaStoreLyricsSidecarInventoryResult(
                    refsByLyricsKey = emptyMap(),
                    status = DiscoveryPartitionStatus(
                        partitionKey = DiscoveryPartitions.MEDIASTORE_LYRICS_SIDECARS,
                        completeness = DiscoveryCompleteness.PARTIAL,
                        detail = error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                    ),
                )
            },
        )
    }

    internal fun requestedLyricsKeys(
        trackedLyricsKeys: Set<String>,
        deltaBatch: DeviceMediaStoreDeltaBatch,
    ): Set<String> = buildSet {
        addAll(trackedLyricsKeys)
        deltaBatch.rows.mapNotNullTo(this, ::mediaStoreDeltaRowLyricsKey)
    }

    private fun loadComplete(
        context: Context,
        requestedKeys: Set<String>,
    ): Map<String, List<ExternalLyricsRef>> {
        if (requestedKeys.isEmpty()) return emptyMap()
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = mutableListOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.FileColumns.RELATIVE_PATH
        } else null
        if (relativePathColumn != null) {
            projection += relativePathColumn
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            projection += MediaStore.Files.FileColumns.DATA
        }

        val candidateNames = requestedKeys.asSequence()
            .map { it.substringAfter('\u0001', missingDelimiterValue = "") }
            .filter(String::isNotBlank)
            .flatMap { base -> sequenceOf("$base.lrc", "$base.ttml") }
            .map(String::lowercase)
            .distinct()
            .sorted()
            .toList()
        if (candidateNames.isEmpty()) return emptyMap()

        val out = LinkedHashMap<String, MutableList<ExternalLyricsRef>>()
        candidateNames.chunked(NAME_QUERY_CHUNK).forEach { names ->
            val placeholders = List(names.size) { "?" }.joinToString(",")
            val selection = "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) IN ($placeholders)"
            val cursor = context.contentResolver.query(
                filesUri,
                projection.toTypedArray(),
                selection,
                names.toTypedArray(),
                null,
            ) ?: throw IOException("MediaStore tracked lyrics query returned no cursor")
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val modifiedCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val relativePathCol = relativePathColumn?.let(c::getColumnIndex) ?: -1
                @Suppress("DEPRECATION")
                val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                } else -1
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    if (!name.endsWith(".lrc", ignoreCase = true) &&
                        !name.endsWith(".ttml", ignoreCase = true)
                    ) continue
                    val baseName = mediaStoreBaseName(name)
                    if (baseName.isBlank()) continue
                    val folderPath = when {
                        relativePathCol >= 0 -> c.getString(relativePathCol).orEmpty()
                        dataCol >= 0 -> c.getString(dataCol).orEmpty().substringBeforeLast('/', "")
                        else -> ""
                    }
                    val lyricsKey = mediaStoreLyricsKey(folderPath, baseName)
                    if (lyricsKey !in requestedKeys) continue
                    val uri = ContentUris.withAppendedId(filesUri, c.getLong(idCol)).toString()
                    out.getOrPut(lyricsKey) { mutableListOf() } += ExternalLyricsRef(
                        uri = uri,
                        sizeBytes = c.getLongOrZero(sizeCol),
                        dateModifiedMs = c.getLongOrZero(modifiedCol) * 1000L,
                        extension = name.substringAfterLast('.', "").lowercase(),
                    )
                }
            }
        }
        return out.mapValues { (_, refs) -> refs.distinctBy(ExternalLyricsRef::uri) }
    }

    private fun android.database.Cursor.getLongOrZero(columnIndex: Int): Long =
        if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else 0L
}
