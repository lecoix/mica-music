package com.mica.music.ui.components

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.mica.music.data.PlayerController

@Composable
fun UserMessageHost(
    playerController: PlayerController,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val message = playerController.userMessage
    LaunchedEffect(message?.id) {
        val msg = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg.text)
        playerController.clearUserMessage()
    }
    SnackbarHost(hostState = snackbarHostState, modifier = modifier)
}
