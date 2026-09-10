package com.mica.music.util

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup

/**
 * Targeted diagnostics for reports where Mica remains visible in split-screen/floating-window
 * mode but stops responding to taps.
 *
 * Filter logcat/current-session.log with `DEBUG-WINDOW-TOUCH-C19E`.
 */
internal object WindowInteractionDiagnostics {
    const val DebugTag = "DEBUG-WINDOW-TOUCH-C19E"
    private const val Category = "WindowTouchTrace"

    private var lastActivityKey: String? = null
    private var lastPlayerSheetKey: String? = null

    fun logActivity(
        event: String,
        activity: Activity,
        playerExpanded: Boolean,
        overlayFullScreen: Boolean,
        overlayView: View? = null,
    ) {
        val config = activity.resources.configuration
        val decor = activity.window.decorView
        val overlayLayout = overlayView?.layoutParams
        val overlayHeightSpec = when (overlayLayout?.height) {
            ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH_PARENT"
            ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP_CONTENT"
            null -> "null"
            else -> overlayLayout.height.toString()
        }
        val multiWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            activity.isInMultiWindowMode
        } else {
            false
        }
        val key = buildString {
            append(event)
            append('|')
            append(multiWindow)
            append('|')
            append(config.screenWidthDp)
            append('x')
            append(config.screenHeightDp)
            append('|')
            append(decor.width)
            append('x')
            append(decor.height)
            append('|')
            append(playerExpanded)
            append('|')
            append(overlayFullScreen)
            append('|')
            append(overlayView?.width ?: -1)
            append('x')
            append(overlayView?.height ?: -1)
            append('|')
            append(overlayHeightSpec)
        }
        if (key == lastActivityKey) return
        lastActivityKey = key

        DiagnosticLog.event(
            Category,
            "$DebugTag event=$event multiWindow=$multiWindow " +
                "config=${config.screenWidthDp}x${config.screenHeightDp}dp orientation=${config.orientation} " +
                "decor=${decor.width}x${decor.height}px focus=${decor.hasWindowFocus()} shown=${decor.isShown} " +
                "playerExpanded=$playerExpanded overlayFullScreen=$overlayFullScreen " +
                "overlay=${overlayView?.width ?: -1}x${overlayView?.height ?: -1}px " +
                "overlayLpHeight=$overlayHeightSpec overlayShown=${overlayView?.isShown} " +
                "overlayClickable=${overlayView?.isClickable}",
        )
    }

    fun logPlayerSheet(
        viewportWidthDp: Int,
        viewportHeightDp: Int,
        expanded: Boolean,
        progress: Float,
        sheetPhase: String,
        showFullPlayer: Boolean,
        underlayOccluded: Boolean,
    ) {
        val progressBucket = (progress * 100f).toInt()
        val key = buildString {
            append(viewportWidthDp)
            append('x')
            append(viewportHeightDp)
            append('|')
            append(expanded)
            append('|')
            append(progressBucket)
            append('|')
            append(sheetPhase)
            append('|')
            append(showFullPlayer)
            append('|')
            append(underlayOccluded)
        }
        if (key == lastPlayerSheetKey) return
        lastPlayerSheetKey = key

        DiagnosticLog.event(
            Category,
            "$DebugTag player-sheet viewport=${viewportWidthDp}x${viewportHeightDp}dp " +
                "expanded=$expanded progress=${"%.3f".format(progress)} phase=$sheetPhase " +
                "showFullPlayer=$showFullPlayer underlayOccluded=$underlayOccluded",
        )
    }
}
