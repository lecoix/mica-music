package com.mica.music.data.scanner

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.IOException

internal data class MediaStoreLyricsSidecarInventoryResult(
    val refsByLyricsKey: Map<String, List<ExternalLyricsRef>>,
    val status: DiscoveryPartitionStatus,
) {
    val complete: Boolean
        get() = status.completeness == DiscoveryCompleteness.COMPLETE

    fun signatureFor(lyricsKey: String): String =
        refsByLyricsKey[lyricsKey].orEmpty().externalLyricsSignature()
}

internal object MediaStoreLyricsSidecarInventory {

    fun load(context: Context): MediaStoreLyricsSidecarInventoryResult = runCatching {
        loadComplete(context)
    }.fold(
        onSuccess = { refs ->
            MediaStoreLyricsSidecarInventoryResult(
                refsByLyricsKey = refs,
                status = DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.MEDIASTORE_LYRICS_SIDECARS,
                    completeness = DiscoveryCompleteness.COMPLETE,
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

    fun loadComplete(context: Context): Map<String, List<ExternalLyricsRef>> {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = mutableListOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.FileColumns.RELATIVE_PATH
        } else {
            null
        }
        if (relativePathColumn != null) {
            projection += relativePathColumn
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            projection += MediaStore.Files.FileColumns.DATA
        }

        val out = LinkedHashMap<String, MutableList<ExternalLyricsRef>>()
        val cursor = context.contentResolver.query(
            filesUri,
            projection.toTypedArray(),
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ? OR " +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?",
            arrayOf("%.lrc", "%.ttml"),
            null,
        ) ?: throw IOException("MediaStore lyrics query returned no cursor")
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val modifiedCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val relativePathCol = relativePathColumn?.let(c::getColumnIndex) ?: -1
            @Suppress("DEPRECATION")
            val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            } else {
                -1
            }
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                if (!name.endsWith(".lrc", ignoreCase = true) &&
                    !name.endsWith(".ttml", ignoreCase = true)
                ) {
                    continue
                }
                val baseName = mediaStoreBaseName(name)
                if (baseName.isBlank()) continue
                val folderPath = when {
                    relativePathCol >= 0 -> c.getString(relativePathCol).orEmpty()
                    dataCol >= 0 -> c.getString(dataCol).orEmpty().substringBeforeLast('/', "")
                    else -> ""
                }
                val uri = ContentUris.withAppendedId(filesUri, c.getLong(idCol)).toString()
                out.getOrPut(mediaStoreLyricsKey(folderPath, baseName)) { mutableListOf() } +=
                    ExternalLyricsRef(
                        uri = uri,
                        sizeBytes = c.getLongOrZero(sizeCol),
                        dateModifiedMs = c.getLongOrZero(modifiedCol) * 1000L,
                        extension = name.substringAfterLast('.', "").lowercase(),
                    )
            }
        }
        return out.mapValues { (_, refs) -> refs.distinctBy(ExternalLyricsRef::uri) }
    }

    private fun android.database.Cursor.getLongOrZero(columnIndex: Int): Long =
        if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else 0L
}
