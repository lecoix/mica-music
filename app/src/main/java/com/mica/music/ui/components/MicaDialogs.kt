package com.mica.music.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
fun MicaConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = title, style = MicaTheme.typography.titleMd)
        },
        text = {
            Text(text = message, style = MicaTheme.typography.bodyMd)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) HifiPalette.LikeRed else MicaTheme.colors.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = MicaTheme.colors.textSecondary)
            }
        },
    )
}

@Composable
fun MicaTextInputDialog(
    visible: Boolean,
    title: String,
    hint: String,
    initialValue: String = "",
    confirmLabel: String = "确定",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    saving: Boolean = false,
    errorMessage: String? = null,
) {
    if (!visible) return
    var value by remember(visible, initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(text = title, style = MicaTheme.typography.titleMd)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(hint) },
                    singleLine = true,
                    enabled = !saving,
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = HifiPalette.LikeRed,
                        style = MicaTheme.typography.caption,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = !saving && value.isNotBlank(),
            ) {
                Text(text = confirmLabel, color = MicaTheme.colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(text = "取消", color = MicaTheme.colors.textSecondary)
            }
        },
    )
}
