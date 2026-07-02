package com.mica.music.ui.components

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.mica.music.data.UserMessage

@Composable
fun UserMessageHost(
    message: UserMessage?,
    onMessageConsumed: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(message?.id) {
        val msg = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg.text)
        onMessageConsumed()
    }
    SnackbarHost(hostState = snackbarHostState, modifier = modifier)
}
