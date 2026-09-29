package com.mica.music.data

/** 桌面图标配色；每项对应 AndroidManifest 中一个 activity-alias。 */
enum class AppLauncherIcon(
    val aliasClassName: String,
    val settingsLabel: String,
) {
    DAWN("com.mica.music.MainActivity", "晨曦"),
    CREAM("com.mica.music.LauncherIconCream", "奶白墨"),
    PURPLE("com.mica.music.LauncherIconPurple", "紫韵"),
    MIDNIGHT("com.mica.music.LauncherIconMidnight", "午夜紫"),
    GOLD("com.mica.music.LauncherIconGold", "鎏金夜"),
    TEAL("com.mica.music.LauncherIconTeal", "青釉"),
    CORAL("com.mica.music.LauncherIconCoral", "珊瑚"),
    ;

    companion object {
        /** 与 manifest 中唯一 `android:enabled="true"` 的 alias 保持一致。 */
        val DEFAULT = DAWN
    }
}
