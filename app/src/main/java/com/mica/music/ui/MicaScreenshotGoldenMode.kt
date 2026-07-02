package com.mica.music.ui

/**
 * Roborazzi 截图回归使用的确定性渲染开关。
 * 启用后跳过 BlurView / RenderEffect，频谱条使用固定高度，避免 OS 间像素差异。
 */
object MicaScreenshotGoldenMode {
    val enabled: Boolean
        get() = System.getProperty("mica.screenshotGolden") == "true"
}
