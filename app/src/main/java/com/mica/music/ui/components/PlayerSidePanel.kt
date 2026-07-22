package com.mica.music.ui.components

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun PlayerSidePanel(
    onDismiss: () -> Unit,
    containerColor: Color,
    scrimColor: Color,
    widthFraction: Float = 0.44f,
    minPanelWidth: androidx.compose.ui.unit.Dp = 400.dp,
    maxPanelWidth: androidx.compose.ui.unit.Dp = 600.dp,
    paneTitle: String = "播放页面板",
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val view = LocalView.current
    DisposableEffect(view, scrimColor) {
        val window = (view.context as Activity).window
        val previousStatusBarColor = window.statusBarColor
        val previousNavigationBarColor = window.navigationBarColor
        window.statusBarColor = scrimColor.toArgb()
        window.navigationBarColor = scrimColor.toArgb()
        onDispose {
            window.statusBarColor = previousStatusBarColor
            window.navigationBarColor = previousNavigationBarColor
        }
    }

    val dismissInteraction = remember { MutableInteractionSource() }
    val panelInteraction = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(scrimColor)
            .clickable(
                interactionSource = dismissInteraction,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val panelWidth = (maxWidth * widthFraction).coerceIn(minPanelWidth, maxPanelWidth)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(panelWidth)
                .background(containerColor)
                .systemBarsPadding()
                .semantics {
                    this.paneTitle = paneTitle
                    isTraversalGroup = true
                }
                .clickable(
                    interactionSource = panelInteraction,
                    indication = null,
                    onClick = {},
                ),
        ) {
            content()
        }
    }
}
