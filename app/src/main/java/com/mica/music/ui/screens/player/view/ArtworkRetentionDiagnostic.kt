package com.mica.music.ui.screens.player.view

internal data class ArtworkRetentionDiagnostic(
    val retainedBitmapCount: Int,
    val pendingLoadCount: Int,
    val queueSize: Int,
)
