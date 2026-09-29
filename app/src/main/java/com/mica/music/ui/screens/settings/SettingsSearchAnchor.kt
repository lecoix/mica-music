package com.mica.music.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.mica.music.ui.theme.MicaTheme

/** Owned by a single search visit; a hidden conditional row never registers itself. */
internal class SettingsSearchFocus(val entryId: String?) {
    var present by mutableStateOf(false)
}

internal val LocalSettingsSearchFocus = staticCompositionLocalOf<SettingsSearchFocus?> { null }

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun settingsSearchAnchor(id: String): Modifier {
    val focus = LocalSettingsSearchFocus.current
    val tagged = Modifier.testTag("settings:$id")
    if (focus?.entryId != id) return tagged

    val requester = remember { BringIntoViewRequester() }
    var laidOut by remember { mutableStateOf(false) }
    DisposableEffect(focus) {
        focus.present = true
        onDispose { focus.present = false }
    }
    LaunchedEffect(focus, laidOut) {
        if (laidOut) {
            // Allow the conditional availability notice to leave the layout first.
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    return tagged
        .background(MicaTheme.colors.accent.copy(alpha = 0.12f))
        .semantics { stateDescription = "搜索定位" }
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { laidOut = true }
}
