package com.mica.music.data

import androidx.compose.ui.graphics.Color
import com.mica.music.ui.theme.HifiPalette

/** 应用强调色（按钮、当前曲高亮、频谱等）。 */
enum class AppAccentColor(
    val storageValue: String,
    val settingsLabel: String,
    private val lightColor: Color,
    private val darkColor: Color? = null,
) {
    PURPLE("purple", "紫韵", HifiPalette.PurplePrimary),
    GOLD("gold", "鎏金", HifiPalette.HiResGold),
    TEAL("teal", "青釉", Color(0xFF5BA8A0)),
    CORAL("coral", "珊瑚", Color(0xFFE07A7A)),
    /** Android 12+ 跟随系统 Material You 主色；低版本回退紫韵。 */
    DYNAMIC("dynamic", "动态取色", HifiPalette.PurplePrimary),
    ;

    fun resolve(isDark: Boolean): Color = if (isDark && darkColor != null) darkColor else lightColor

    companion object {
        fun fromStorage(value: String?): AppAccentColor =
            entries.find { it.storageValue == value } ?: PURPLE
    }
}
