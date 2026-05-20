package com.mica.music.data

/** 一次性用户提示（Snackbar 等），[id] 用于 LaunchedEffect 去重。 */
data class UserMessage(
    val text: String,
    val id: Long = System.nanoTime(),
)
