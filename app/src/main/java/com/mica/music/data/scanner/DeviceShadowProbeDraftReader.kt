package com.mica.music.data.scanner

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.mica.music.data.DsdSupport
import java.io.IOException

internal data class DeviceShadowProbeDraftObservation(
    val row: DeviceDeltaRow,
    val draft: TrackDraft,
)

internal sealed interface DeviceShadowProbeDraftRead {
    data class Observed(
        val observation: DeviceShadowProbeDraftObservation,
    ) : DeviceShadowProbeDraftRead

    data object Missing : DeviceShadowProbeDraftRead

    data class Unavailable(
        val detail: String,
    ) : DeviceShadowProbeDraftRead
}

internal fun interface DeviceShadowProbeDraftQueryApi {
    fun query(
        ref: DeviceObjectRef,
        lyricsInventory: MediaStoreLyricsSidecarInventoryResult,
    ): DeviceShadowProbeDraftObservation?
}

internal object DeviceShadowProbeDraftReader {
    fun read(
        api: DeviceShadowProbeDraftQueryApi,
        ref: DeviceObjectRef,
        lyricsInventory: MediaStoreLyricsSidecarInventoryResult,
    ): DeviceShadowProbeDraftRead =
        runCatching { api.query(ref, lyricsInventory) }.fold(
            onSuccess = { observation ->
                when {
                    observation == null -> DeviceShadowProbeDraftRead.Missing
                    observation.row.channel != ref.channel ||
                        observation.row.volumeName != ref.volumeName ||
                        observation.row.mediaStoreId != ref.mediaStoreId ->
                        DeviceShadowProbeDraftRead.Unavailable(
                            "provider returned mismatched draft identity",
                        )
                    else -> DeviceShadowProbeDraftRead.Observed(observation)
                }
            },
            onFailure = { error ->
                DeviceShadowProbeDraftRead.Unavailable(
                    error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                )
            },
        )
}

/**
 * Exact-object draft reader used only by S3 shadow probes.
 *
 * Field defaults deliberately mirror [MediaStoreScanner] so a read-only point probe can be
 * compared against an independently executed Full Scan without inventing a second metadata model.
 */
internal class AndroidDeviceShadowProbeDraftQueryApi(
    context: Context,
    private val options: ScanOptions,
) : DeviceShadowProbeDraftQueryApi {
    private val resolver = context.applicationContext.contentResolver

    @RequiresApi(Build.VERSION_CODES.R)
    override fun query(
        ref: DeviceObjectRef,
        lyricsInventory: MediaStoreLyricsSidecarInventoryResult,
    ): DeviceShadowProbeDraftObservation? {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        check(lyricsInventory.complete) {
            "shadow probe requires complete lyrics sidecar inventory"
        }
        return when (ref.channel) {
            DeviceDeltaChannel.AUDIO -> queryAudio(ref, lyricsInventory)
            DeviceDeltaChannel.FILES_FALLBACK -> queryFallbackFile(ref, lyricsInventory)
            DeviceDeltaChannel.LYRICS_SIDECAR ->
                error("lyrics sidecar is not an audio TrackDraft")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun queryAudio(
        ref: DeviceObjectRef,
        lyricsInventory: MediaStoreLyricsSidecarInventoryResult,
    ): DeviceShadowProbeDraftObservation? {
        val collection = MediaStore.Audio.Media.getContentUri(ref.volumeName)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.BITRATE,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
        )
        val cursor = queryById(collection, projection, ref.mediaStoreId)
            ?: throw IOException("MediaStore shadow audio draft query returned no cursor")
        return cursor.use { c ->
            if (!c.moveToFirst()) return@use null
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val bitrateCol = c.getColumnIndex(MediaStore.Audio.Media.BITRATE)
            val yearCol = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
            val dateAddedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val modifiedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val isMusicCol = c.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
            val relativePathCol = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val pendingCol = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            val trashedCol = c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
            val addedGenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_ADDED)
            val modifiedGenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)

            val id = c.getLong(idCol)
            val title = c.getString(titleCol)?.takeIf(String::isNotBlank) ?: "未知标题"
            val artist = c.getString(artistCol)
                ?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }
                ?: "未知艺人"
            val album = c.getString(albumCol)
                ?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }
                ?: "未知专辑"
            val durationMs = c.longOrZero(durationCol)
            val mime = c.stringOrEmpty(mimeCol)
            val displayName = c.getString(nameCol)
            val sizeBytes = c.longOrZero(sizeCol)
            val modifiedMs = c.longOrZero(modifiedCol) * 1000L
            val relativePath = c.stringOrEmpty(relativePathCol)
            val folderPath = relativePath.trim('/')
            val filePath = mediaStoreReadableFilePath(relativePath, displayName.orEmpty())
            val pending = c.intOrZero(pendingCol) != 0
            val trashed = c.intOrZero(trashedCol) != 0
            val eligibility = MediaStorePresenceInventory.mediaStoreEligibility(
                pending = pending,
                trashed = trashed,
                eligibleByType = c.intOrZero(isMusicCol) != 0 ||
                    (options.includeNonMusicByMime &&
                        mime.startsWith("audio/", ignoreCase = true)),
                eligibleByDuration = options.minDurationMs <= 0L ||
                    durationMs <= 0L ||
                    durationMs >= options.minDurationMs,
                excluded = ExcludedScanDirectories.isExcluded(
                    folderPath,
                    options.excludedDirectories,
                ),
            )
            val row = DeviceDeltaRow(
                channel = DeviceDeltaChannel.AUDIO,
                volumeName = ref.volumeName,
                mediaStoreId = id,
                mediaUri = MediaStore.Audio.Media.getContentUri(ref.volumeName, id).toString(),
                displayName = displayName.orEmpty(),
                mimeType = mime,
                relativePath = relativePath,
                sizeBytes = sizeBytes,
                dateModifiedMs = modifiedMs,
                generationAdded = c.getLong(addedGenCol),
                generationModified = c.getLong(modifiedGenCol),
                eligibility = eligibility,
            )
            val lyricsRefs = lyricsRefsFor(
                row = row,
                lyricsInventory = lyricsInventory,
            )
            DeviceShadowProbeDraftObservation(
                row = row,
                draft = TrackDraft(
                    mediaStoreId = id,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = c.longOrZero(albumIdCol),
                    durationSec = (durationMs / 1000L).toInt(),
                    mimeType = mime,
                    displayName = displayName,
                    sizeBytes = sizeBytes,
                    bitrateBpsFromStore = c.intOrZero(bitrateCol),
                    mediaUri = row.mediaUri,
                    coverColorArgb = CoverColorExtractor.FALLBACK_ARGB,
                    year = c.intOrZero(yearCol).coerceAtLeast(0),
                    folderPath = folderPath,
                    filePath = filePath,
                    externalLyricsUris = lyricsRefs.externalLyricsUris(),
                    externalLrcUris = lyricsRefs.externalLyricsUris("lrc"),
                    externalTtmlUris = lyricsRefs.externalLyricsUris("ttml"),
                    externalLyricsSignature = lyricsRefs.externalLyricsSignature(),
                    dateAddedMs = c.longOrZero(dateAddedCol) * 1000L,
                    dateModifiedMs = modifiedMs,
                ),
            )
        }
    }

    private fun queryFallbackFile(
        ref: DeviceObjectRef,
        lyricsInventory: MediaStoreLyricsSidecarInventoryResult,
    ): DeviceShadowProbeDraftObservation? {
        val collection = MediaStore.Files.getContentUri(ref.volumeName)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
        )
        val cursor = queryById(collection, projection, ref.mediaStoreId)
            ?: throw IOException("MediaStore shadow file draft query returned no cursor")
        return cursor.use { c ->
            if (!c.moveToFirst()) return@use null
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val dateAddedCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
            val modifiedCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val relativePathCol = c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val pendingCol = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            val trashedCol = c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
            val addedGenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_ADDED)
            val modifiedGenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)

            val id = c.getLong(idCol)
            val displayName = c.stringOrEmpty(nameCol)
            val extension = displayName.substringAfterLast('.', "").lowercase()
            val mime = c.stringOrEmpty(mimeCol).ifBlank {
                if (extension == "ape") "audio/x-ape" else DsdSupport.mimeForExtension(extension)
            }
            val sizeBytes = c.longOrZero(sizeCol)
            val modifiedMs = c.longOrZero(modifiedCol) * 1000L
            val relativePath = c.stringOrEmpty(relativePathCol)
            val filePath = mediaStoreReadableFilePath(relativePath, displayName)
            val folderPath = mediaStoreLyricsFolderPath(relativePath, filePath)
            val pending = c.intOrZero(pendingCol) != 0
            val trashed = c.intOrZero(trashedCol) != 0
            val eligibility = MediaStorePresenceInventory.mediaStoreEligibility(
                pending = pending,
                trashed = trashed,
                eligibleByType = extension in MediaStoreScanner.FILE_EXTENSION_FALLBACKS,
                eligibleByDuration = true,
                excluded = ExcludedScanDirectories.isExcluded(
                    folderPath,
                    options.excludedDirectories,
                ),
            )
            val row = DeviceDeltaRow(
                channel = DeviceDeltaChannel.FILES_FALLBACK,
                volumeName = ref.volumeName,
                mediaStoreId = id,
                mediaUri = Uri.withAppendedPath(collection, id.toString()).toString(),
                displayName = displayName,
                mimeType = mime,
                relativePath = relativePath,
                sizeBytes = sizeBytes,
                dateModifiedMs = modifiedMs,
                generationAdded = c.getLong(addedGenCol),
                generationModified = c.getLong(modifiedGenCol),
                eligibility = eligibility,
            )
            val lyricsRefs = lyricsRefsFor(
                row = row,
                lyricsInventory = lyricsInventory,
            )
            val baseName = mediaStoreBaseName(displayName)
            DeviceShadowProbeDraftObservation(
                row = row,
                draft = TrackDraft(
                    mediaStoreId = id,
                    title = baseName.ifBlank { displayName },
                    artist = "未知艺人",
                    album = "未知专辑",
                    albumId = 0L,
                    durationSec = 0,
                    mimeType = mime,
                    displayName = displayName,
                    sizeBytes = sizeBytes,
                    bitrateBpsFromStore = 0,
                    mediaUri = row.mediaUri,
                    coverColorArgb = CoverColorExtractor.FALLBACK_ARGB,
                    folderPath = folderPath,
                    filePath = filePath,
                    externalLyricsUris = lyricsRefs.externalLyricsUris(),
                    externalLrcUris = lyricsRefs.externalLyricsUris("lrc"),
                    externalTtmlUris = lyricsRefs.externalLyricsUris("ttml"),
                    externalLyricsSignature = lyricsRefs.externalLyricsSignature(),
                    dateAddedMs = c.longOrZero(dateAddedCol) * 1000L,
                    dateModifiedMs = modifiedMs,
                ),
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

    private fun lyricsRefsFor(
        row: DeviceDeltaRow,
        lyricsInventory: MediaStoreLyricsSidecarInventoryResult,
    ): List<ExternalLyricsRef> {
        val key = mediaStoreDeltaRowLyricsKey(row) ?: return emptyList()
        return lyricsInventory.refsByLyricsKey[key].orEmpty()
    }

    private fun Cursor.stringOrEmpty(columnIndex: Int): String =
        if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex).orEmpty() else ""

    private fun Cursor.longOrZero(columnIndex: Int): Long =
        if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else 0L

    private fun Cursor.intOrZero(columnIndex: Int): Int =
        if (columnIndex >= 0 && !isNull(columnIndex)) getInt(columnIndex) else 0
}
