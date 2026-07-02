package com.mica.music.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class PlayerInfoVisibility(
    val showFormat: Boolean = true,
    val showSampleRate: Boolean = true,
    val showBitrate: Boolean = true,
    val showCurrentTime: Boolean = false,
    val showCustomText: Boolean = false,
    val customText: String = "",
) {
    fun hasAnyEnabledSegment(): Boolean =
        showFormat ||
            showSampleRate ||
            showBitrate ||
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
    if (visibility.showCurrentTime) {
        currentTimeLabel?.takeIf { it.isNotBlank() }?.let { segments += it }
    }
    if (visibility.showCustomText) {
        visibility.customText.trim().takeIf { it.isNotEmpty() }?.let { segments += it }
    }
    return segments
}
