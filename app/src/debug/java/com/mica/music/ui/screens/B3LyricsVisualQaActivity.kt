package com.mica.music.ui.screens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mica.music.data.LyricLine
import com.mica.music.data.renderStateAt
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.lightPlayerContentColors
import kotlinx.coroutines.delay

/**
 * Debug APK only. Runs the real ExpandedLyricsPanel with controlled timed lines, but neither
 * accesses playback state nor user music. Designed to expose interlude and row-height transitions
 * without requiring a first-install scan of the device's entire MediaStore library.
 */
class B3LyricsVisualQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MicaTheme(darkTheme = true) {
                B3LyricsVisualQaContent()
            }
        }
    }
}

private val qaLyrics = listOf(
    LyricLine(1_000, "第一句，正常短歌词。", endTimeMs = 3_900),
    LyricLine(5_000, "第二句，逐行滚动。", endTimeMs = 8_800),
    LyricLine(9_000, "第三句，当前行加粗会改变测量高度。", endTimeMs = 12_000),
    LyricLine(13_000, "间奏前最后一句", endTimeMs = 17_000),
    LyricLine(29_000, "间奏之后，下方歌词应保持稳定", endTimeMs = 34_000),
    LyricLine(35_000, "这一句刻意很长很长，用来验证歌词变为当前行时由于加粗和自动换行产生的行高变化，也要保持平滑且不能让离场歌词向下飞出屏幕。", endTimeMs = 42_000),
    LyricLine(43_000, "第七句，继续滚动。", endTimeMs = 48_000),
    LyricLine(49_000, "第二次间奏前一句", endTimeMs = 55_000),
    LyricLine(68_000, "第二次间奏后一句歌词", endTimeMs = 73_000),
    LyricLine(74_000, "测试尾声", endTimeMs = 82_000),
)

@Composable
private fun B3LyricsVisualQaContent() {
    var positionMs by remember { mutableIntStateOf(10_000) }
    var revision by remember { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var anchor by remember { mutableIntStateOf(25) }
    var rapidSeek by remember { mutableIntStateOf(0) }

    LaunchedEffect(playing) {
        while (playing && positionMs < 85_000) {
            delay(100)
            positionMs += 100
        }
    }
    LaunchedEffect(rapidSeek) {
        if (rapidSeek > 0) {
            for (time in listOf(12_000, 36_000, 15_000, 69_000, 21_000)) {
                positionMs = time
                revision++
                delay(230)
            }
        }
    }
    val state = remember(positionMs, revision) {
        qaLyrics.renderStateAt(positionMs).copy(positionRevision = revision)
    }
    val colors = lightPlayerContentColors()
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Text("Mica B3 FIX QA · ${positionMs / 1000}s · anchor=${anchor}%", color = Color.White)
        Row(Modifier.fillMaxWidth()) {
            QaButton("13s") { positionMs = 13_000; revision++ }
            QaButton("19s") { positionMs = 19_000; revision++ }
            QaButton("28s") { positionMs = 28_000; revision++ }
            QaButton("35s") { positionMs = 35_000; revision++ }
        }
        Row(Modifier.fillMaxWidth()) {
            QaButton("50s") { positionMs = 50_000; revision++ }
            QaButton("60s") { positionMs = 60_000; revision++ }
            QaButton("PLAY") { playing = !playing }
            QaButton("SEEK") { rapidSeek++ }
        }
        Row(Modifier.fillMaxWidth()) {
            QaButton("25%") { anchor = 25 }
            QaButton("60%") { anchor = 60 }
            Text("revision=${revision}", color = Color.White, modifier = Modifier.padding(10.dp))
        }
        ExpandedLyricsPanel(
            renderState = state,
            isPlaying = playing,
            colors = colors,
            onLineClick = { index ->
                positionMs = qaLyrics.getOrNull(index)?.timeMs ?: positionMs
                revision++
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            lyricsCurrentLinePositionPercent = anchor,
            lyricsFontSizeSp = 34,
            lyricsLineSpacingDp = 18,
        )
    }
}

@Composable
private fun QaButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.padding(horizontal = 3.dp)) {
        Text(label)
    }
}
