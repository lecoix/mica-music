package com.mica.music.ui.screens.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mica.music.data.preferences.UsageTutorialPreferences
import com.mica.music.ui.theme.MicaTheme

/** Observes the live scan flag directly, without waiting for a frame/recomposition or changing the scan. */
@Composable
internal fun UsageTutorialScanInvitation(
    scanInProgress: () -> Boolean,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val latestScan by rememberUpdatedState(scanInProgress)
    val latestEnabled by rememberUpdatedState(enabled)
    var showQuestion by rememberSaveable { mutableStateOf(false) }
    var showTutorial by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(context) {
        snapshotFlow { latestEnabled && latestScan() }.collect { scanning ->
            // Recheck live inputs after the collector suspension; the preference owner claims atomically.
            if (scanning && latestEnabled && latestScan() && UsageTutorialPreferences.claimScanInvitation(context)) {
                showQuestion = true
            }
        }
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            showQuestion = false
            showTutorial = false
        }
    }
    if (enabled && showQuestion) {
        UsageTutorialScanQuestion(
            onYes = { showQuestion = false; showTutorial = true },
            onNo = {
                UsageTutorialPreferences.complete(context)
                showQuestion = false
            },
        )
    }
    if (enabled && showTutorial) {
        UsageTutorialDialog(firstRun = true) { showTutorial = false }
    }
}

@Composable
internal fun UsageTutorialScanQuestion(onYes: () -> Unit, onNo: () -> Unit) {
    val colors = MicaTheme.colors
    Dialog(onDismissRequest = onNo, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceCard, RectangleShape)
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 0.dp),
        ) {
            Text(
                "扫描中，是否打开使用技巧？",
                modifier = Modifier.fillMaxWidth(),
                style = MicaTheme.typography.titleMd,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onYes, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RectangleShape) {
                    Text("是", fontSize = 16.sp, color = colors.accent)
                }
                TextButton(onClick = onNo, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RectangleShape) {
                    Text("否", fontSize = 16.sp, color = colors.like)
                }
            }
        }
    }
}
