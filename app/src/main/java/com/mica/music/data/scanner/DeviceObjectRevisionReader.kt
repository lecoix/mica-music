package com.mica.music.data.scanner

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.IOException

internal fun DeviceDeltaRow.deviceObjectRevisionFingerprint(): String =
    listOf(
        channel.name,
        volumeName,
        mediaStoreId.toString(),
        sizeBytes.toString(),
        dateModifiedMs.toString(),
        eligibility.name,
        eligibilityAuthority.name,
    ).joinToString(separator = "|")

internal fun DeviceDeltaRow.deviceObjectRef(): DeviceObjectRef =
    DeviceObjectRef(
        channel = channel,
        volumeName = volumeName,
        mediaStoreId = mediaStoreId,
    )

internal data class DeviceObjectRef(
    val channel: DeviceDeltaChannel,
    val volumeName: String,
    val mediaStoreId: Long,
) {
    init {
        require(channel != DeviceDeltaChannel.LYRICS_SIDECAR) {
            "audio object revision reader does not own lyrics sidecars"
        }
        require(volumeName.isNotBlank())
        require(mediaStoreId >= 0L)
    }
}

internal sealed interface DeviceObjectRevisionRead {
    data class Observed(
        val row: DeviceDeltaRow,
    ) : DeviceObjectRevisionRead

    data object Missing : DeviceObjectRevisionRead

    data class Unavailable(
        val detail: String,
    ) : DeviceObjectRevisionRead
}

internal fun interface DeviceObjectRevisionQueryApi {
    fun query(ref: DeviceObjectRef): DeviceDeltaRow?
}

internal object DeviceObjectRevisionReader {
    fun recheck(
        api: DeviceObjectRevisionQueryApi,
        ref: DeviceObjectRef,
    ): DeviceObjectRevisionRead =
        runCatching { api.query(ref) }.fold(
            onSuccess = { row ->
                when {
                    row == null -> DeviceObjectRevisionRead.Missing
                    row.channel != ref.channel ||
                        row.volumeName != ref.volumeName ||
                        row.mediaStoreId != ref.mediaStoreId ->
                        DeviceObjectRevisionRead.Unavailable("provider returned mismatched object identity")
                    else -> DeviceObjectRevisionRead.Observed(row)
                }
            },
            onFailure = { error ->
                DeviceObjectRevisionRead.Unavailable(
                    error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                )
            },
        )
}

/**
 * Exact-object MediaStore re-query for post-probe validation.
 *
 * This deliberately does not use a generation-window predicate. The caller has already opened the
 * object based on a bounded delta observation and now needs the current row facts to prove that the
 * observed revision did not move while the probe was running.
 */
internal class AndroidDeviceObjectRevisionQueryApi(
    context: Context,
    private val options: ScanOptions,
) : DeviceObjectRevisionQueryApi {
    private val resolver = context.applicationContext.contentResolver

    @RequiresApi(Build.VERSION_CODES.R)
    override fun query(ref: DeviceObjectRef): DeviceDeltaRow? {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        return when (ref.channel) {
            DeviceDeltaChannel.AUDIO -> queryAudio(ref)
            DeviceDeltaChannel.FILES_FALLBACK -> queryFile(ref)
            DeviceDeltaChannel.LYRICS_SIDECAR ->
                error("lyrics sidecar is not an audio object revision")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun queryAudio(ref: DeviceObjectRef): DeviceDeltaRow? {
        val uri = MediaStore.Audio.Media.getContentUri(ref.volumeName)
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
        val cursor = queryById(uri, projection, ref.mediaStoreId)
            ?: throw IOException("MediaStore audio object re-query returned no cursor")
        return cursor.use { c ->
            if (!c.moveToFirst()) return@use null
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
            val id = c.getLong(idCol)
            val mime = c.stringOrEmpty(mimeCol)
            val durationMs = c.longOrZero(durationCol)
            val relativePath = c.stringOrEmpty(relativePathCol).trim('/')
            val eligibility = MediaStorePresenceInventory.mediaStoreEligibility(
                pending = c.intOrZero(pendingCol) != 0,
                trashed = c.intOrZero(trashedCol) != 0,
                eligibleByType = c.intOrZero(isMusicCol) != 0 ||
                    mime.startsWith("audio/", ignoreCase = true),
                eligibleByDuration = options.minDurationMs <= 0L ||
                    durationMs <= 0L ||
                    durationMs >= options.minDurationMs,
                excluded = ExcludedScanDirectories.isExcluded(
                    relativePath,
                    options.excludedDirectories,
                ),
            )
            DeviceDeltaRow(
                channel = DeviceDeltaChannel.AUDIO,
                volumeName = ref.volumeName,
                mediaStoreId = id,
                mediaUri = MediaStore.Audio.Media.getContentUri(ref.volumeName, id).toString(),
                displayName = c.stringOrEmpty(nameCol),
                mimeType = mime,
                relativePath = relativePath,
                sizeBytes = c.longOrZero(sizeCol),
                dateModifiedMs = c.longOrZero(modifiedCol) * 1000L,
                generationAdded = c.getLong(addedGenCol),
                generationModified = c.getLong(modifiedGenCol),
                eligibility = eligibility,
            )
        }
    }

    private fun queryFile(ref: DeviceObjectRef): DeviceDeltaRow? {
        val uri = MediaStore.Files.getContentUri(ref.volumeName)
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
        val cursor = queryById(uri, projection, ref.mediaStoreId)
            ?: throw IOException("MediaStore file object re-query returned no cursor")
        return cursor.use { c ->
            if (!c.moveToFirst()) return@use null
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
            val id = c.getLong(idCol)
            val relativePath = c.stringOrEmpty(relativePathCol).trim('/')
            val eligibility = MediaStorePresenceInventory.mediaStoreEligibility(
                pending = c.intOrZero(pendingCol) != 0,
                trashed = c.intOrZero(trashedCol) != 0,
                eligibleByType = true,
                eligibleByDuration = true,
                excluded = ExcludedScanDirectories.isExcluded(
                    relativePath,
                    options.excludedDirectories,
                ),
            )
            DeviceDeltaRow(
                channel = DeviceDeltaChannel.FILES_FALLBACK,
                volumeName = ref.volumeName,
                mediaStoreId = id,
                mediaUri = Uri.withAppendedPath(uri, id.toString()).toString(),
                displayName = c.stringOrEmpty(nameCol),
                mimeType = c.stringOrEmpty(mimeCol),
                relativePath = relativePath,
                sizeBytes = c.longOrZero(sizeCol),
                dateModifiedMs = c.longOrZero(modifiedCol) * 1000L,
                generationAdded = c.getLong(addedGenCol),
                generationModified = c.getLong(modifiedGenCol),
                eligibility = eligibility,
            )
        }
    }

    private fun queryById(
        uri: Uri,
        projection: Array<String>,
        mediaStoreId: Long,
    ): Cursor? {
        val queryArgs = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns._ID} = ?",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(mediaStoreId.toString()),
            )
        }
        return resolver.query(uri, projection, queryArgs, null)
    }

    private fun Cursor.stringOrEmpty(columnIndex: Int): String =
        if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex).orEmpty() else ""

    private fun Cursor.longOrZero(columnIndex: Int): Long =
        if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else 0L

    private fun Cursor.intOrZero(columnIndex: Int): Int =
        if (columnIndex >= 0 && !isNull(columnIndex)) getInt(columnIndex) else 0
}
