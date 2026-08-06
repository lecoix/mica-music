package com.mica.music.util

import com.mica.music.ui.theme.isWallpaperBarAnchorValid

/**
 * 自定义壁纸 Hi‑Fi 底栏闪烁排查：过滤 logcat / current-session.log 用
 * `DEBUG-WALLPAPER-BAR-A7F2`。
 */
internal object WallpaperBarSliceDiagnostics {
    const val DebugTag = "DEBUG-WALLPAPER-BAR-A7F2"

    private var lastSliceRenderKey: String? = null
    private var lastSliceLayoutKey: String? = null
    private var lastPlayerSheetKey: String? = null
    private var lastOverlayLayoutKey: String? = null
    private var lastViewportFrameKey: String? = null

    fun logSliceRender(
        source: String,
        render: String,
        reason: String,
        hasWallpaperFile: Boolean,
        viewportFrame: Boolean,
        sliceTopPx: Float,
        sliceHeightPx: Float,
        viewportTopPx: Float,
        viewportHeightPx: Float,
        cachedBarTopPx: Float,
        cachedBarHeightPx: Float,
        anchorValid: Boolean,
    ) {
        val key = buildString {
            append(source)
            append('|')
            append(render)
            append('|')
            append(reason)
            append("|slice=")
            append(formatPx(sliceTopPx))
            append('x')
            append(formatPx(sliceHeightPx))
            append("|viewport=")
            append(formatPx(viewportTopPx))
            append('x')
            append(formatPx(viewportHeightPx))
            append("|frame=")
            append(viewportFrame)
        }
        if (key == lastSliceRenderKey) return
        lastSliceRenderKey = key
        DiagnosticLog.event(
            "WallpaperBar",
            "$DebugTag render source=$source mode=$render reason=$reason " +
                "file=$hasWallpaperFile frame=$viewportFrame anchor=$anchorValid " +
                "sliceTop=${formatPx(sliceTopPx)} sliceH=${formatPx(sliceHeightPx)} " +
                "viewportTop=${formatPx(viewportTopPx)} viewportH=${formatPx(viewportHeightPx)} " +
                "cachedBarTop=${formatPx(cachedBarTopPx)} cachedBarH=${formatPx(cachedBarHeightPx)}",
        )
    }

    fun logSliceLayout(
        source: String,
        sliceTopPx: Float,
        sliceHeightPx: Float,
        anchorValid: Boolean,
    ) {
        val key = buildString {
            append(source)
            append('|')
            append(formatPx(sliceTopPx))
            append('|')
            append(formatPx(sliceHeightPx))
            append('|')
            append(anchorValid)
        }
        if (key == lastSliceLayoutKey) return
        lastSliceLayoutKey = key
        DiagnosticLog.event(
            "WallpaperBar",
            "$DebugTag layout source=$source sliceTop=${formatPx(sliceTopPx)} " +
                "sliceH=${formatPx(sliceHeightPx)} anchor=$anchorValid",
        )
    }

    fun logPlayerSheet(
        expanded: Boolean,
        progress: Float,
        sheetPhase: String,
        showFullPlayer: Boolean,
        miniPlayerChromeVisible: Boolean,
        miniPlayerStyle: String,
        overlayFullScreen: Boolean,
    ) {
        val key = buildString {
            append(expanded)
            append('|')
            append(formatPx(progress))
            append('|')
            append(sheetPhase)
            append('|')
            append(showFullPlayer)
            append('|')
            append(miniPlayerChromeVisible)
            append('|')
            append(miniPlayerStyle)
            append('|')
            append(overlayFullScreen)
        }
        if (key == lastPlayerSheetKey) return
        lastPlayerSheetKey = key
        DiagnosticLog.event(
            "WallpaperBar",
            "$DebugTag player-sheet expanded=$expanded progress=${formatPx(progress)} " +
                "phase=$sheetPhase showFull=$showFullPlayer miniVisible=$miniPlayerChromeVisible " +
                "miniStyle=$miniPlayerStyle overlayFullScreen=$overlayFullScreen",
        )
    }

    fun logOverlayLayout(fullScreen: Boolean, composeRoot: String) {
        val key = "$composeRoot|$fullScreen"
        if (key == lastOverlayLayoutKey) return
        lastOverlayLayoutKey = key
        DiagnosticLog.event(
            "WallpaperBar",
            "$DebugTag overlay-layout root=$composeRoot fullScreen=$fullScreen",
        )
    }

    fun logViewportFrame(
        owner: String,
        hasFrame: Boolean,
        viewportTopPx: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        requestId: Int? = null,
    ) {
        val key = buildString {
            append(owner)
            append('|')
            append(hasFrame)
            append('|')
            append(formatPx(viewportTopPx))
            append('x')
            append(formatPx(viewportWidthPx))
            append('x')
            append(formatPx(viewportHeightPx))
            append('|')
            append(requestId)
        }
        if (key == lastViewportFrameKey) return
        lastViewportFrameKey = key
        DiagnosticLog.event(
            "WallpaperBar",
            "$DebugTag viewport-frame owner=$owner hasFrame=$hasFrame " +
                "top=${formatPx(viewportTopPx)} size=${formatPx(viewportWidthPx)}x" +
                "${formatPx(viewportHeightPx)} requestId=${requestId ?: "-"}",
        )
    }

    private fun formatPx(value: Float): String =
        if (value.isNaN()) "NaN" else "%.1f".format(value)
}

internal fun customWallpaperBarSliceFallbackReason(
    hasWallpaperFile: Boolean,
    viewportFrame: Boolean,
    sliceTopPx: Float,
    sliceHeightPx: Float,
    viewportTopPx: Float,
    viewportHeightPx: Float,
): String = when {
    !hasWallpaperFile -> "no-wallpaper-file"
    !viewportFrame -> "viewport-frame-null"
    sliceTopPx.isNaN() -> "slice-top-nan"
    sliceHeightPx <= 0f -> "slice-height-zero"
    viewportHeightPx <= 0f -> "viewport-height-zero"
    !isWallpaperBarAnchorValid(
        sliceTopPx = sliceTopPx,
        sliceHeightPx = sliceHeightPx,
        viewportTopPx = viewportTopPx,
        viewportHeightPx = viewportHeightPx,
    ) -> "anchor-invalid"
    else -> "none"
}

/** overlay 改 wrap_content 时首帧 window top 可能暂为 0；回退到上次有效锚点。 */
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
