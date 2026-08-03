package com.mica.music.media

import com.mica.music.data.ExternalLyricsStyle
import com.mica.music.data.LyricCue
import com.mica.music.data.DEFAULT_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.LyricsBilingualDisplayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The only lyric payload retained by either external surface: the currently displayed line. */
data class ExternalLyricsText(
    val text: String,
    val cues: List<LyricCue> = emptyList(),
)

data class ExternalLyricsLine(
    val lineIndex: Int,
    val startMs: Int,
    val endMs: Int?,
    val original: ExternalLyricsText? = null,
    val translation: ExternalLyricsText? = null,
)

/** Applies the selected external bilingual mode at the final rendering boundary. */
internal fun ExternalLyricsLine.forExternalDisplay(
    mode: LyricsBilingualDisplayMode,
): ExternalLyricsLine = when (mode) {
    LyricsBilingualDisplayMode.ALL -> this
    LyricsBilingualDisplayMode.ORIGINAL ->
        if (original != null) copy(translation = null) else this
    LyricsBilingualDisplayMode.TRANSLATION ->
        if (translation != null) copy(original = null) else this
}

data class ExternalLyricsSurfaceState(
    val line: ExternalLyricsLine? = null,
    val positionMs: Int = 0,
    val isPlaying: Boolean = false,
    val enabled: Boolean = false,
) {
    val visible: Boolean
        get() = enabled && isPlaying && line != null
}

/**
 * Process-lifetime projection for the desktop and status-bar lyric surfaces.
 *
 * The media-service coordinator owns lyric interpretation. Both windows observe this bounded
 * snapshot and therefore never parse the library or run their own position ticker.
 */
data class DesktopLyricsOverlayState(
    val desktop: ExternalLyricsSurfaceState = ExternalLyricsSurfaceState(),
    val statusBar: ExternalLyricsSurfaceState = ExternalLyricsSurfaceState(),
    val style: ExternalLyricsStyle = ExternalLyricsStyle(
        desktopOriginalFontSizeSp = DEFAULT_LYRICS_PAGE_FONT_SIZE_SP,
        desktopTranslationFontSizeSp = DEFAULT_LYRICS_PAGE_FONT_SIZE_SP,
        statusBarOriginalFontSizeSp = DEFAULT_LYRICS_PAGE_FONT_SIZE_SP,
        statusBarTranslationFontSizeSp = DEFAULT_LYRICS_PAGE_FONT_SIZE_SP,
    ),
    val appInForeground: Boolean = false,
) {
    /** Compatibility projections retained for existing callers and phase-one tests. */
    val text: String?
        get() = desktop.line?.original?.text ?: desktop.line?.translation?.text
    val lineIndex: Int?
        get() = desktop.line?.lineIndex
    val isPlaying: Boolean
        get() = desktop.isPlaying
    val visible: Boolean
        get() = desktop.visible
}

class DesktopLyricsOverlayStateStore {
    private val _state = MutableStateFlow(DesktopLyricsOverlayState())
    val state: StateFlow<DesktopLyricsOverlayState> = _state.asStateFlow()

    /** Compatibility entry point for the original single-line desktop projection. */
    fun publish(text: String, lineIndex: Int) {
        publish(
            line = ExternalLyricsLine(
                lineIndex = lineIndex,
                startMs = 0,
                endMs = null,
                original = ExternalLyricsText(text),
            ),
            positionMs = 0,
            desktopEnabled = true,
            statusBarEnabled = false,
        )
    }

    fun publish(
        line: ExternalLyricsLine,
        positionMs: Int,
        desktopEnabled: Boolean,
        statusBarEnabled: Boolean,
    ) {
        val current = _state.value
        _state.value = current.copy(
            desktop = current.desktop.copy(
                line = line,
                positionMs = positionMs,
                enabled = desktopEnabled,
            ),
            statusBar = current.statusBar.copy(
                line = line,
                positionMs = positionMs,
                enabled = statusBarEnabled,
            ),
        )
    }

    fun setSurfaceEnabled(desktopEnabled: Boolean, statusBarEnabled: Boolean) {
        val current = _state.value
        _state.value = current.copy(
            desktop = current.desktop.copy(enabled = desktopEnabled),
            statusBar = current.statusBar.copy(enabled = statusBarEnabled),
        )
    }

    fun setPlaying(isPlaying: Boolean) {
        val current = _state.value
        _state.value = current.copy(
            desktop = current.desktop.copy(isPlaying = isPlaying),
            statusBar = current.statusBar.copy(isPlaying = isPlaying),
        )
    }

    fun updatePosition(positionMs: Int) {
        val current = _state.value
        if (!current.desktop.needsPositionUpdates() && !current.statusBar.needsPositionUpdates()) return
        val desktop = current.desktop.takeIf { it.needsPositionUpdates() }
            ?.takeUnless { it.positionMs == positionMs }
            ?.copy(positionMs = positionMs)
            ?: current.desktop
        val statusBar = current.statusBar.takeIf { it.needsPositionUpdates() }
            ?.takeUnless { it.positionMs == positionMs }
            ?.copy(positionMs = positionMs)
            ?: current.statusBar
        if (desktop != current.desktop || statusBar != current.statusBar) {
            _state.value = current.copy(desktop = desktop, statusBar = statusBar)
        }
    }

    fun setStyle(style: ExternalLyricsStyle) {
        _state.value = _state.value.copy(style = style)
    }

    fun setAppInForeground(inForeground: Boolean) {
        _state.value = _state.value.copy(appInForeground = inForeground)
    }

    fun clear() {
        val current = _state.value
        _state.value = current.copy(
            desktop = current.desktop.copy(line = null),
            statusBar = current.statusBar.copy(line = null),
        )
    }
}

private fun ExternalLyricsSurfaceState.needsPositionUpdates(): Boolean =
    visible && (
        line?.original?.cues?.isNotEmpty() == true ||
            line?.translation?.cues?.isNotEmpty() == true
        )
