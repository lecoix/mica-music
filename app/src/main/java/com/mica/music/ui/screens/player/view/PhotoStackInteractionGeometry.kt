package com.mica.music.ui.screens.player.view

internal data class PhotoStackProgressHitBandDp(
    /** Distance from the card bottom to the upper edge of the hit band. */
    val topFromBottomDp: Float,
    /** Distance from the card bottom to the lower edge of the hit band. */
    val bottomFromBottomDp: Float,
) {
    val heightDp: Float
        get() = topFromBottomDp - bottomFromBottomDp

    val centerFromBottomDp: Float
        get() = (topFromBottomDp + bottomFromBottomDp) / 2f
}

/**
 * Keeps the seek target centered on the visual progress/waveform strip while guaranteeing
 * a stable 32dp vertical hit band. The band stays inside the Polaroid card and therefore
 * cannot overlap the playback-control interaction row below the card.
 */
internal fun photoStackProgressHitBandDp(
    immersiveProgress: Float,
): PhotoStackProgressHitBandDp {
    val t = immersiveProgress.coerceIn(0f, 1f)
    fun lerp(start: Float, end: Float): Float = start + (end - start) * t
    return PhotoStackProgressHitBandDp(
        topFromBottomDp = lerp(53f, 48f),
        bottomFromBottomDp = lerp(21f, 16f),
    )
}
