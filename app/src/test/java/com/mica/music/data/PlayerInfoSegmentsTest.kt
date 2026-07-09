package com.mica.music.data

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class PlayerInfoSegmentsTest {

    @Test
    fun defaultVisibilityMatchesLegacyInfoRow() {
        val song = SongFixtures.song(
            title = "Test",
            durationSec = 245,
        )

        assertEquals(
            listOf("FLAC", "24bit/96kHz", "1411 kbps"),
            buildPlayerInfoSegments(song, PlayerInfoVisibility()),
        )
    }

    @Test
    fun customSegmentsRespectToggles() {
        val song = SongFixtures.song(durationSec = 125)

        assertEquals(
            listOf("FLAC", "14:30"),
            buildPlayerInfoSegments(
                song,
                PlayerInfoVisibility(
                    showSampleRate = false,
                    showBitrate = false,
                    showCurrentTime = true,
                ),
                currentTimeLabel = "14:30",
            ),
        )

        assertEquals(
            listOf("Hi-Res"),
            buildPlayerInfoSegments(
                song,
                PlayerInfoVisibility(
                    showFormat = false,
                    showSampleRate = false,
                    showBitrate = false,
                    showCurrentTime = false,
                    showCustomText = true,
                    customText = " Hi-Res ",
                ),
            ),
        )
    }

    @Test
    fun playbackTuningSegmentsRespectToggles() {
        val song = SongFixtures.song(durationSec = 125)

        assertEquals(
            listOf("1.25x", "+2 半音"),
            buildPlayerInfoSegments(
                song,
                PlayerInfoVisibility(
                    showFormat = false,
                    showSampleRate = false,
                    showBitrate = false,
                    showPlaybackSpeed = true,
                    showPlaybackPitch = true,
                ),
                playbackTuning = PlaybackTuning(speed = 1.25f, pitchSemitones = 2f),
            ),
        )
    }

    @Test
    fun allDisabledProducesEmptySegments() {
        val song = SongFixtures.song()

        assertTrue(
            buildPlayerInfoSegments(
                song,
                PlayerInfoVisibility(
                    showFormat = false,
                    showSampleRate = false,
                    showBitrate = false,
                    showCurrentTime = false,
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun currentTimeOnlyNeedsLabel() {
        val song = SongFixtures.song()

        assertTrue(
            PlayerInfoVisibility(
                showFormat = false,
                showSampleRate = false,
                showBitrate = false,
                showCurrentTime = true,
            ).hasAnyEnabledSegment(),
        )
        assertTrue(
            buildPlayerInfoSegments(
                song,
                PlayerInfoVisibility(
                    showFormat = false,
                    showSampleRate = false,
                    showBitrate = false,
                    showCurrentTime = true,
                ),
                currentTimeLabel = "09:41",
            ).contains("09:41"),
        )
        assertFalse(
            buildPlayerInfoSegments(
                song,
                PlayerInfoVisibility(
                    showFormat = false,
                    showSampleRate = false,
                    showBitrate = false,
                    showCurrentTime = true,
                ),
            ).isNotEmpty(),
        )
    }

    @Test
    fun formatPlayerInfoCurrentTimeUsesLocale() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val nowMillis = ZonedDateTime.of(2024, 1, 1, 15, 30, 0, 0, zoneId)
            .toInstant()
            .toEpochMilli()
        val label = formatPlayerInfoCurrentTime(
            nowMillis = nowMillis,
            locale = Locale.US,
            zoneId = zoneId,
        )

        assertEquals("3:30 PM", label.replace('\u202F', ' '))
    }

    @Test
    fun millisUntilNextMinuteBoundaryAlignsToMinute() {
        val minuteStart = 1_700_000_040_000L
        assertEquals(60_000L, millisUntilNextMinuteBoundary(minuteStart))
        assertEquals(30_000L, millisUntilNextMinuteBoundary(minuteStart + 30_000L))
        assertEquals(1L, millisUntilNextMinuteBoundary(minuteStart + 59_999L))
    }
}
