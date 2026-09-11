package com.mica.music.util

import com.mica.music.ui.theme.isWallpaperBarAnchorValid

/** Uses the last valid bar anchor while a wrap-content overlay is between layout frames. */
internal fun effectiveWallpaperBarSliceAnchor(
    liveTopPx: Float,
    liveHeightPx: Float,
    cachedTopPx: Float,
    cachedHeightPx: Float,
    viewportTopPx: Float,
    viewportHeightPx: Float,
): Pair<Float, Float> {
    if (isWallpaperBarAnchorValid(
            sliceTopPx = liveTopPx,
            sliceHeightPx = liveHeightPx,
            viewportTopPx = viewportTopPx,
            viewportHeightPx = viewportHeightPx,
        )
    ) {
        return liveTopPx to liveHeightPx
    }
    if (isWallpaperBarAnchorValid(
            sliceTopPx = cachedTopPx,
            sliceHeightPx = cachedHeightPx,
            viewportTopPx = viewportTopPx,
            viewportHeightPx = viewportHeightPx,
        )
    ) {
        return cachedTopPx to cachedHeightPx
    }
    return liveTopPx to liveHeightPx
}
