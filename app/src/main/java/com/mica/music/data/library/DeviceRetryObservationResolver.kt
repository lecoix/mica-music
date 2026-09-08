package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.scanner.DeviceDeltaChannel
import com.mica.music.data.scanner.DeviceDeltaRow
import com.mica.music.data.scanner.DeviceObjectRef
import com.mica.music.data.scanner.DeviceObjectRevisionQueryApi
import com.mica.music.data.scanner.DeviceObjectRevisionRead
import com.mica.music.data.scanner.DeviceObjectRevisionReader
import com.mica.music.data.scanner.MediaStoreLyricsSidecarInventory
import com.mica.music.data.scanner.MediaStoreLyricsSidecarInventoryResult
import com.mica.music.data.scanner.ScanOptions

internal data class DeviceRetryObservationResult(
    val observedRowsByStableObjectKey: Map<String, DeviceDeltaRow>,
    val missingStableObjectKeys: Set<String>,
    val unavailableStableObjectKeys: Set<String>,
    val lyricsInventory: MediaStoreLyricsSidecarInventoryResult? = null,
)

internal data class DeviceRetryObservationRequest(
    val currentSongs: List<Song>,
    val retryItems: List<LibraryRetryItem>,
    val nowMs: Long,
    val scanOptions: ScanOptions,
)

internal fun interface DeviceRetryObservationRuntime {
    fun resolve(request: DeviceRetryObservationRequest): DeviceRetryObservationResult
}

internal object NoopDeviceRetryObservationRuntime : DeviceRetryObservationRuntime {
    override fun resolve(request: DeviceRetryObservationRequest): DeviceRetryObservationResult {
        val dueKeys = request.retryItems.asSequence()
            .filter { it.retryKind == LibraryRetryKind.OBJECT_PROBE }
            .filter { it.nextRetryAtMs <= request.nowMs }
            .mapTo(linkedSetOf(), LibraryRetryItem::stableObjectKey)
        return DeviceRetryObservationResult(
            observedRowsByStableObjectKey = emptyMap(),
            missingStableObjectKeys = emptySet(),
            unavailableStableObjectKeys = dueKeys,
            lyricsInventory = null,
        )
    }
}

internal class AndroidDeviceRetryObservationRuntime(
    private val context: android.content.Context,
) : DeviceRetryObservationRuntime {
    override fun resolve(request: DeviceRetryObservationRequest): DeviceRetryObservationResult =
        DeviceRetryObservationResolver.resolve(
            currentSongs = request.currentSongs,
            retryItems = request.retryItems,
            nowMs = request.nowMs,
            queryApi = com.mica.music.data.scanner.AndroidDeviceObjectRevisionQueryApi(
                context = context,
                options = request.scanOptions,
            ),
        ).copy(
            lyricsInventory = MediaStoreLyricsSidecarInventory.load(context),
        )
}

/**
 * Re-locates durable DEVICE OBJECT_PROBE debt after the original generation window has already
 * advanced. The persisted Song mediaUri owns volume/channel identity; never infer a volume from the
 * global stable key because MediaStore ids are only volume-local.
 */
internal object DeviceRetryObservationResolver {
    fun resolve(
        currentSongs: List<Song>,
        retryItems: List<LibraryRetryItem>,
        nowMs: Long,
        queryApi: DeviceObjectRevisionQueryApi,
    ): DeviceRetryObservationResult {
        val songsById = currentSongs.associateBy(Song::id)
        val observed = linkedMapOf<String, DeviceDeltaRow>()
        val missing = linkedSetOf<String>()
        val unavailable = linkedSetOf<String>()

        retryItems.asSequence()
            .filter { it.retryKind == LibraryRetryKind.OBJECT_PROBE }
            .filter { it.nextRetryAtMs <= nowMs }
            .groupBy(LibraryRetryItem::stableObjectKey)
            .toSortedMap()
            .forEach { (stableKey, _) ->
                val song = songsById[stableKey]
                val ref = song?.let(::deviceObjectRefFromSong)
                if (ref == null) {
                    unavailable += stableKey
                    return@forEach
                }
                when (val read = DeviceObjectRevisionReader.recheck(queryApi, ref)) {
                    is DeviceObjectRevisionRead.Observed -> observed[stableKey] = read.row
                    DeviceObjectRevisionRead.Missing -> missing += stableKey
                    is DeviceObjectRevisionRead.Unavailable -> unavailable += stableKey
                }
            }

        return DeviceRetryObservationResult(
            observedRowsByStableObjectKey = observed,
            missingStableObjectKeys = missing,
            unavailableStableObjectKeys = unavailable,
        )
    }

    internal fun deviceObjectRefFromSong(song: Song): DeviceObjectRef? {
        val prefix = "content://media/"
        if (!song.mediaUri.startsWith(prefix, ignoreCase = true)) return null
        val segments = song.mediaUri
            .substring(prefix.length)
            .substringBefore('?')
            .substringBefore('#')
            .split('/')
            .filter(String::isNotBlank)
        if (segments.isEmpty()) return null
        val volumeName = segments.firstOrNull()?.takeIf(String::isNotBlank) ?: return null
        val mediaStoreId = segments.lastOrNull()?.toLongOrNull()
            ?: song.id.removePrefix("ms_").toLongOrNull()
            ?: return null
        val channel = when {
            segments.any { it.equals("audio", ignoreCase = true) } ->
                DeviceDeltaChannel.AUDIO
            segments.any { it.equals("file", ignoreCase = true) } ->
                DeviceDeltaChannel.FILES_FALLBACK
            else -> return null
        }
        return DeviceObjectRef(
            channel = channel,
            volumeName = volumeName,
            mediaStoreId = mediaStoreId,
        )
    }
}
