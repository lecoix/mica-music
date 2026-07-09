package com.mica.music.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

data class PlayerInfoVisibility(
    val showFormat: Boolean = true,
    val showSampleRate: Boolean = true,
    val showBitrate: Boolean = true,
    val showPlaybackSpeed: Boolean = false,
    val showPlaybackPitch: Boolean = false,
    val showCurrentTime: Boolean = false,
    val showCustomText: Boolean = false,
    val customText: String = "",
) {
    fun hasAnyEnabledSegment(): Boolean =
        showFormat ||
            showSampleRate ||
            showBitrate ||
            showPlaybackSpeed ||
            showPlaybackPitch ||
            showCurrentTime ||
            (showCustomText && customText.trim().isNotEmpty())
}

fun formatPlayerInfoCurrentTime(
    nowMillis: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String =
    DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(nowMillis).atZone(zoneId))

/** SHORT 时间格式不含秒，对齐到下一分钟刷新即可。 */
fun millisUntilNextMinuteBoundary(nowMillis: Long = System.currentTimeMillis()): Long {
    val intoMinute = nowMillis % 60_000L
    return (60_000L - intoMinute).coerceAtLeast(1L)
}

fun buildPlayerInfoSegments(
    song: Song,
    visibility: PlayerInfoVisibility,
    currentTimeLabel: String? = null,
    playbackTuning: PlaybackTuning? = null,
): List<String> {
    val segments = mutableListOf<String>()
    if (visibility.showFormat) {
        segments += song.formatLabel
    }
    if (visibility.showSampleRate) {
        segments += song.sampleRateLabel
    }
    if (visibility.showBitrate) {
        segments += song.bitrateLabel
    }
    if (visibility.showPlaybackSpeed) {
        playbackTuning?.let { segments += formatPlayerInfoPlaybackSpeed(it.speed) }
    }
    if (visibility.showPlaybackPitch) {
        playbackTuning?.let { segments += formatPlayerInfoPlaybackPitch(it.pitchSemitones) }
    }
    if (visibility.showCurrentTime) {
        currentTimeLabel?.takeIf { it.isNotBlank() }?.let { segments += it }
    }
    if (visibility.showCustomText) {
        visibility.customText.trim().takeIf { it.isNotEmpty() }?.let { segments += it }
    }
    return segments
}

private fun formatPlayerInfoPlaybackSpeed(speed: Float): String =
    String.format(Locale.US, "%.2fx", speed)

private fun formatPlayerInfoPlaybackPitch(semitones: Float): String {
    val rounded = semitones.roundToInt()
    return when {
        rounded > 0 -> "+$rounded 半音"
        rounded < 0 -> "$rounded 半音"
        else -> "0 半音"
    }
}
