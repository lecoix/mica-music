package com.mica.music.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-lifetime projection for the desktop lyric surface.
 *
 * The media-service coordinator owns lyric interpretation. The overlay only observes this small,
 * bounded snapshot and therefore never parses the library or runs its own position ticker.
 */
data class DesktopLyricsOverlayState(
    val text: String? = null,
    val lineIndex: Int? = null,
    val isPlaying: Boolean = false,
    val visible: Boolean = false,
)

class DesktopLyricsOverlayStateStore {
    private val _state = MutableStateFlow(DesktopLyricsOverlayState())
    val state: StateFlow<DesktopLyricsOverlayState> = _state.asStateFlow()

    fun publish(text: String, lineIndex: Int) {
        _state.value = _state.value.copy(
            text = text,
            lineIndex = lineIndex,
            visible = _state.value.isPlaying,
        )
    }

    fun setPlaying(isPlaying: Boolean) {
        _state.value = _state.value.copy(
            isPlaying = isPlaying,
            visible = isPlaying && _state.value.text != null,
        )
    }

    fun clear() {
        _state.value = _state.value.copy(
            text = null,
            lineIndex = null,
            visible = false,
        )
    }
}
