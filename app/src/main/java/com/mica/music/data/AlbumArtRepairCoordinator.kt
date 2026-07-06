package com.mica.music.data

import android.content.Context
import com.mica.music.data.scanner.AlbumArtCache
import com.mica.music.util.DiagnosticLog

internal enum class AlbumArtRepairAction {
    ScanDevice,
    ScanFolder,
    NoReadableSource,
}

internal data class AlbumArtRepairPlan(
    val reason: String,
    val health: AlbumArtCache.Health,
    val action: AlbumArtRepairAction,
)

internal object AlbumArtRepairCoordinator {
    fun plan(
        context: Context,
        songs: List<Song>,
        lastScanSource: ScanSource,
        hasLibraryFolder: Boolean,
        hasAudioReadPermission: Boolean,
        reason: String,
    ): AlbumArtRepairPlan? {
        val health = AlbumArtCache.health(context, songs)
        DiagnosticLog.event("AlbumArtCache", "repair-check reason=$reason ${health.toLogMessage()}")
        if (!health.needsRepair) return null
        val action = actionFor(
            lastScanSource = lastScanSource,
            hasLibraryFolder = hasLibraryFolder,
            hasAudioReadPermission = hasAudioReadPermission,
        )
        if (action == AlbumArtRepairAction.NoReadableSource) {
            DiagnosticLog.event("AlbumArtCache", "repair-skip reason=$reason no-readable-source")
        }
        return AlbumArtRepairPlan(reason, health, action)
    }

    fun actionFor(
        lastScanSource: ScanSource,
        hasLibraryFolder: Boolean,
        hasAudioReadPermission: Boolean,
    ): AlbumArtRepairAction =
        when (lastScanSource) {
            ScanSource.FOLDER -> {
                when {
                    hasLibraryFolder -> AlbumArtRepairAction.ScanFolder
                    hasAudioReadPermission -> AlbumArtRepairAction.ScanDevice
                    else -> AlbumArtRepairAction.NoReadableSource
                }
            }
            ScanSource.DEVICE -> {
                when {
                    hasAudioReadPermission -> AlbumArtRepairAction.ScanDevice
                    hasLibraryFolder -> AlbumArtRepairAction.ScanFolder
                    else -> AlbumArtRepairAction.NoReadableSource
                }
            }
        }
}
