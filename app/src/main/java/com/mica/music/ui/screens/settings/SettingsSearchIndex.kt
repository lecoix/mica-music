package com.mica.music.ui.screens.settings

import java.util.Locale

internal enum class SettingsIndexSurface {
    SETTINGS,
    SONG_LIST,
    BROWSE,
    PLAYER_MENU,
    EQUALIZER,
    SOUND_FX,
}

internal data class SettingsIndexTarget(
    val surface: SettingsIndexSurface,
    val category: SettingsCategory? = null,
    val sectionId: String? = null,
)

internal data class SettingsIndexEntry(
    val id: String,
    val title: String,
    val keywords: Set<String>,
    val target: SettingsIndexTarget,
    val availability: String? = null,
    val isExperimental: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Settings index id must not be blank" }
        require(title.isNotBlank()) { "Settings index title must not be blank" }
    }

    private val searchableText = buildString {
        append(title)
        append(' ')
        append(keywords.joinToString(" "))
        append(' ')
        target.category?.let { append(it.title) }
        append(' ')
        availability?.let { append(it) }
    }.lowercase(Locale.ROOT)

    internal fun matches(tokens: List<String>): Boolean = tokens.all(searchableText::contains)
}

internal object SettingsIndexSections {
    const val APPEARANCE = "appearance"
    const val MINI_PLAYER = "mini-player"
    const val PLAYBACK_THEME = "playback-theme"
    const val PLAYBACK_COVER = "playback-cover"
    const val PLAYBACK_INFO = "playback-info"
    const val LYRICS_THEME = "lyrics-theme"
    const val LYRICS_LETTER = "lyrics-letter"
    const val LYRICS_GENERAL = "lyrics-general"
    const val LYRICS_OUTPUT = "lyrics-output"
    const val LYRICS_CLASSIC = "lyrics-classic"
    const val LYRICS_FONT = "lyrics-font"
    const val LIBRARY_SOURCE = "library-source"
    const val LIBRARY_SCAN = "library-scan"
    const val LIBRARY_ARTIST = "library-artist"
    const val AUDIO = "audio"
    const val DIAGNOSTICS = "diagnostics"
    const val TUTORIAL = "tutorial"
}

internal object SettingsSearchIndex {
    val entries: List<SettingsIndexEntry> = listOf(
        setting("appearance.theme", "主题", "主题模式", "浅色", "深色"),
        setting("appearance.accent", "强调色", "颜色", "自定义颜色", "动态取色"),
        setting("appearance.mica-background", "云母背景", "背景", "渐变", "自定义背景"),
        setting("appearance.wallpaper", "自定义壁纸", "壁纸", "主界面背景"),
        setting("appearance.wallpaper-overlay", "壁纸遮罩强度", "壁纸", "遮罩", "透明度"),
        setting("appearance.wallpaper-blur", "壁纸模糊度", "壁纸", "模糊", "清晰度"),
        setting("appearance.wallpaper-crop", "调整壁纸裁切", "壁纸", "裁切", "缩放", "拖动"),
        setting("appearance.restore-wallpaper", "恢复默认壁纸", "壁纸", "恢复", "云母背景"),
        setting(
            "appearance.hide-status-bar",
            "隐藏状态栏",
            "关闭、仅播放页隐藏、仅非播放页隐藏、全部隐藏",
            "状态栏",
        ),
        setting("appearance.mini-player-style", "迷你播放栏", "迷你播放", "底栏", "样式", section = SettingsIndexSections.MINI_PLAYER),
        setting("appearance.playlist-sidebar-style", "侧栏歌单样式", "歌单", "侧栏", "总览", "默认形式"),
        setting("appearance.mini-player-lyrics", "迷你播放栏歌词", "迷你播放", "歌词", section = SettingsIndexSections.MINI_PLAYER),
        setting(
            "appearance.mini-player-word-lyrics",
            "迷你播放栏逐字歌词",
            "迷你播放", "逐字", "卡拉 OK",
            section = SettingsIndexSections.MINI_PLAYER,
            availability = "仅在迷你播放栏歌词开启且有逐字时间轴时明显生效",
        ),
        setting("appearance.mini-player-swipe", "迷你播放栏滑动切歌", "迷你播放", "滑动", "切歌", section = SettingsIndexSections.MINI_PLAYER),
        setting(
            "appearance.mini-player-left-swipe",
            "左滑动作",
            "迷你播放", "左滑", "上一首", "下一首",
            section = SettingsIndexSections.MINI_PLAYER,
            availability = "仅在迷你播放栏滑动切歌开启时生效",
        ),
        setting(
            "appearance.mini-player-right-swipe",
            "右滑动作",
            "迷你播放", "右滑", "上一首", "下一首",
            section = SettingsIndexSections.MINI_PLAYER,
            availability = "仅在迷你播放栏滑动切歌开启时生效",
        ),

        setting(
            "playback.theme", "播放页特殊主题", "播放页", "主题", "粒子", "拍立得", "复古", "自定义标准",
            category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_THEME,
        ),
        setting("playback.cover-display", "封面显示", "封面", "原样比例", "裁切", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_COVER, availability = "粒子、平行、复古、拍立得主题强制裁切时不显示"),
        setting("playback.lower-background", "播放页背景", "背景", "云母", "模糊", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_COVER),
        setting("playback.content-color", "播放页 UI 颜色", "文字颜色", "前景色", "动态取色", "稳定主色", "语义色阶", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_COVER),
        setting("playback.music-video", "音乐 MV", "视频", "MP4", "音乐唯一声音来源", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_COVER, availability = "仅标准播放页；同目录同基本文件名配对；从下一首歌曲生效"),
        setting("playback.video-album-cover", "视频专辑封面", "视频", "MP4", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_COVER, availability = "仅标准播放页；开启后需要重扫曲库"),
        setting("playback.custom-layout", "进入播放页布局编辑", "布局", "拖动", "缩放", "显隐", "自定义标准", "点击封面", "点击封面暂停/播放", "暂停/播放", "专辑图阴影", "阴影", "柔影", "浮岛", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_COVER, availability = "仅自定义标准主题；需要当前有歌曲"),
        setting("playback.cover-edge-progress", "封面底边进度", "进度条", "频谱", "封面边缘", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_COVER, availability = "标准主题需主题色或模糊背景；特殊主题有独立能力条件"),
        setting("playback.keep-screen-on", "播放时屏幕常亮", "常亮", "熄屏", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_COVER),
        setting("playback.immersive-lower", "沉浸模式", "下半屏沉浸", "沉浸", "下半屏", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_COVER, availability = "自定义标准、粒子封面不支持；拍立得回忆把歌名收进相纸白边"),
        setting("playback.photo-stack-immersive-lyrics", "沉浸时标题显示歌词", "拍立得", "沉浸", "歌词", "标题", "翻译", "走马灯", "逐字", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_COVER, availability = "仅拍立得回忆；播放中替换相纸白边歌名，过长走马灯，有逐字时间轴时填充"),
        setting("playback.compact-lyrics", "折叠歌词行数", "歌词行数", "一行", "三行", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_COVER, availability = "标准、粒子、平行、复古主题；自定义标准和拍立得不消费"),
        setting("playback.strip-title-parentheses", "隐藏歌名括号内容", "歌名", "括号", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_COVER),
        setting("playback.spectrum", "频谱条", "频谱", "功耗", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_COVER),
        setting("playback.info-format", "信息行：格式", "格式", "FLAC", "MP3", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_INFO),
        setting("playback.info-sample-rate", "信息行：位深/采样率", "采样率", "位深", "96kHz", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_INFO),
        setting("playback.info-bitrate", "信息行：比特率", "码率", "320 kbps", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_INFO),
        setting("playback.info-speed", "信息行：速度", "变速", "播放速度", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_INFO),
        setting("playback.info-pitch", "信息行：音高", "变调", "半音", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_INFO),
        setting("playback.info-time", "信息行：当前时间", "时间", "时钟", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_INFO),
        setting("playback.info-custom-text", "信息行：自定义文字", "自定义文字", "信息行", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_INFO),
        setting("playback.hires-badge", "Hi-Res 标志样式", "Hi-Res", "高解析度", "徽标", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_INFO),
        setting("playback.hires-custom-image", "Hi-Res 自定义图片", "Hi-Res", "图片", "徽标", "选择图片", "清除自定义图片", category = SettingsCategory.PLAYBACK, section = SettingsIndexSections.PLAYBACK_INFO, availability = "仅选择自定义图片样式时显示"),

        setting("lyrics.theme", "歌词页主题", "歌词", "经典列表", "歌词云", "信笺", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_THEME),
        setting("lyrics.priority", "歌词优先级", "TTML", "LRC", "内嵌", "歌词来源", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_GENERAL),
        setting("lyrics.letter-seal-image", "信笺朱印图片", "印章", "图片", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_LETTER, availability = "仅信笺主题"),
        setting("lyrics.letter-seal-restore", "恢复默认印章", "印章", "恢复", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_LETTER, availability = "仅信笺主题且已导入自定义图片"),
        setting("lyrics.letter-seal-size", "信笺朱印大小", "印章", "尺寸", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_LETTER, availability = "仅信笺主题"),
        setting("lyrics.letter-seal-opacity", "信笺朱印浓度", "印章", "透明度", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_LETTER, availability = "仅信笺主题"),
        setting("lyrics.letter-seal-rotation", "信笺朱印旋转", "印章", "旋转", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_LETTER, availability = "仅信笺主题"),
        setting("lyrics.split-bilingual", "分割双语歌词", "双语", "拆分", "翻译", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_GENERAL),
        setting("lyrics.reading", "显示读音 / 罗马音", "读音", "罗马音", "音译", "x-roman", "transliteration", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_GENERAL),
        setting("lyrics.bilingual-display", "双语歌词显示", "双语", "原文", "翻译", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_GENERAL, availability = "需要启用双语拆分且当前歌词行可拆分"),
        setting("lyrics.color", "歌词颜色", "颜色", "浅色", "深色", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_GENERAL),
        setting("lyrics.info-row", "信息行歌词", "歌词输出", "歌曲列表", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_OUTPUT),
        setting("lyrics.global-offset", "全局歌词偏移", "歌词同步", "提前", "延后", "微调", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_GENERAL),
        setting("lyrics.info-row-word", "信息行逐字歌词", "逐字", "歌词输出", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_OUTPUT, availability = "仅信息行歌词开启且有逐字时间轴时明显生效"),
        setting("lyrics.notification", "通知栏歌词", "通知", "媒体通知", "车载蓝牙", "车机", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_OUTPUT, availability = "车载蓝牙输出与通知栏歌词共用开关；受系统通知和媒体会话条件影响"),
        setting(
            "lyrics.external",
            "外部歌词",
            "桌面歌词", "状态栏歌词", "悬浮窗", "overlay", "外部歌词输出",
            category = SettingsCategory.LYRICS,
            section = SettingsIndexSections.LYRICS_OUTPUT,
            availability = "需要悬浮窗权限；桌面歌词与状态栏歌词互斥",
        ),
        setting("lyrics.classic-word-animation", "经典列表：逐字动画", "逐字", "动画", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_CLASSIC, availability = "仅经典列表主题；需要真实逐字时间轴"),
        setting("lyrics.classic-line-fill", "强制使用逐字歌词样式", "逐字", "填充", "逐字歌词样式", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_CLASSIC, availability = "仅经典列表主题；无逐字时间轴时使用播放进度"),
        setting("lyrics.classic-alignment", "经典列表：歌词页对齐", "对齐", "左对齐", "居中", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_CLASSIC, availability = "仅经典列表主题"),
        setting("lyrics.classic-font-size", "经典列表：原歌词字号", "字号", "字体", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_CLASSIC, availability = "仅经典列表主题"),
        setting("lyrics.classic-translation-size", "经典列表：翻译歌词字号", "字号", "翻译", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_CLASSIC, availability = "仅经典列表主题"),
        setting("lyrics.classic-line-spacing", "经典列表：行间距", "行距", "间距", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_CLASSIC, availability = "仅经典列表主题"),
        setting("lyrics.classic-immersive", "经典列表：沉浸模式", "沉浸", "全屏", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_CLASSIC, availability = "仅经典列表主题"),
        setting("lyrics.font", "歌词字体", "字体", "TTF", "OTF", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_FONT),
        setting("lyrics.font-clear", "清除导入字体", "字体", "恢复", "系统默认", category = SettingsCategory.LYRICS, section = SettingsIndexSections.LYRICS_FONT, availability = "仅导入字体后显示为可用"),

        setting("library.remote", "远程曲库", "Navidrome", "OpenSubsonic", "WebDAV", "SMB", "网络音乐", "远程来源", "自动同步", "后台同步", category = SettingsCategory.LIBRARY, section = SettingsIndexSections.LIBRARY_SOURCE),
        setting(
            "library.remote-sidebar",
            "在侧栏显示远程曲库",
            "侧栏", "启用", "远程曲库",
            category = SettingsCategory.LIBRARY,
            section = SettingsIndexSections.LIBRARY_SOURCE,
            availability = "默认关闭；关闭后仍可从曲库设置管理远程来源",
        ),
        setting("library.folder", "曲库文件夹", "文件夹", "目录", "SAF", category = SettingsCategory.LIBRARY, section = SettingsIndexSections.LIBRARY_SOURCE),
        setting("library.rescan", "重新扫描曲库", "扫描", "刷新", category = SettingsCategory.LIBRARY, section = SettingsIndexSections.LIBRARY_SOURCE),
        setting("library.scan-all", "扫描全部音乐", "扫描", "MediaStore", category = SettingsCategory.LIBRARY, section = SettingsIndexSections.LIBRARY_SOURCE),
        setting("library.excluded-directories", "排除目录", "扫描", "排除", category = SettingsCategory.LIBRARY, section = SettingsIndexSections.LIBRARY_SCAN),
        setting("library.min-duration", "最短曲目时长", "时长", "过滤", category = SettingsCategory.LIBRARY, section = SettingsIndexSections.LIBRARY_SCAN),
        setting("library.deep-probe", "深度分析音质与封面", "扫描", "封面", "音质", category = SettingsCategory.LIBRARY, section = SettingsIndexSections.LIBRARY_SCAN, availability = "会增加扫描时间和耗电"),
        setting("library.artist-split", "艺术家分割", "艺人", "艺术家", "分隔符", category = SettingsCategory.LIBRARY, section = SettingsIndexSections.LIBRARY_ARTIST),

        setting("audio.replaygain", "ReplayGain", "音量", "标准化", "按曲目", "按专辑", category = SettingsCategory.AUDIO, section = SettingsIndexSections.AUDIO, availability = "优先使用文件 ReplayGain 标签；按曲目缺少标签时可使用 Mica 响度分析结果"),
        setting("audio.loudness-scan", "扫描曲库响度", "R128", "LUFS", "响度", "标准化", "ReplayGain", category = SettingsCategory.AUDIO, section = SettingsIndexSections.AUDIO, availability = "分析完整音频但不保存 PCM；文件未变且已有结果时自动复用"),
        setting("audio.channel-balance", "左右声道平衡", "声道", "平衡", "左声道", "右声道", "balance", category = SettingsCategory.AUDIO, section = SettingsIndexSections.AUDIO, availability = "仅 Shared PCM 软件处理路径生效；USB 独占输出保持原始声道"),
        setting(
            "audio.sound-fx",
            "音效实验室",
            "音效", "混响", "立体声宽度", "低音", "高音", "音色", "房间大小", "阻尼", "湿比", "360", "环绕", "DSP",
            category = SettingsCategory.AUDIO,
            section = SettingsIndexSections.AUDIO,
            availability = "默认关闭；仅 Shared PCM。打开且参数非中性时进入软件 DSP 并关闭 offload；USB 独占旁路",
        ),
        setting("audio.focus", "独占音频焦点", "音频焦点", "暂停其他应用", category = SettingsCategory.AUDIO, section = SettingsIndexSections.AUDIO, availability = "下次开始播放或切歌时生效"),
        setting(
            "audio.usb-exclusive",
            "USB 独占输出",
            "USB", "DAC", "Exact PCM", "Native DSD", "DoP", "传输状态", "诊断报告",
            "音量控制", "DSD 增益", "平滑接管", "quirk", "授权并重试",
            category = SettingsCategory.AUDIO,
            section = SettingsIndexSections.AUDIO,
            availability = "支持单一已连接 USB Audio 输出设备；进入音频与设备后打开独立子页",
            isExperimental = true,
        ),

        setting("diagnostics.metadata", "元数据调试", "ID3", "Vorbis", "解析器", category = SettingsCategory.DIAGNOSTICS, section = SettingsIndexSections.DIAGNOSTICS),
        setting("diagnostics.audio-offload", "音频硬件卸载（Offload）", "offload", "硬件解码", "DSP", "PCM", "省电", "失速", "mp3", category = SettingsCategory.DIAGNOSTICS, section = SettingsIndexSections.DIAGNOSTICS),
        setting("diagnostics.spatial-audio", "系统空间音频", "Spatializer", "输出", category = SettingsCategory.DIAGNOSTICS, section = SettingsIndexSections.DIAGNOSTICS),
        setting("diagnostics.app-settings", "系统权限与应用信息", "权限", "通知", "应用信息", category = SettingsCategory.DIAGNOSTICS, section = SettingsIndexSections.DIAGNOSTICS),

        setting(
            "help.tutorial",
            "重新查看教程",
            "教程", "使用技巧", "新手", "示意",
            category = null,
            section = SettingsIndexSections.TUTORIAL,
        ),

        context("song-list.sort", "歌曲列表排序", SettingsIndexSurface.SONG_LIST, "排序", "升序", "降序", "自定义"),
        context("song-list.info", "歌曲列表信息显示", SettingsIndexSurface.SONG_LIST, "格式", "时长", "文件大小", "歌曲列表"),
        context("browse.display", "浏览分组显示", SettingsIndexSurface.BROWSE, "专辑", "艺术家", "网格", "列数"),
        context("browse.stats", "专辑/艺术家统计信息", SettingsIndexSurface.BROWSE, "专辑", "艺术家", "统计", "曲目数"),
        context("browse.folder-mode", "文件夹浏览模式", SettingsIndexSurface.BROWSE, "文件夹", "深度", "统合"),
        context("player-menu.sleep-timer", "睡眠定时", SettingsIndexSurface.PLAYER_MENU, "定时", "睡眠", "倒计时"),
        context("equalizer", "均衡器", SettingsIndexSurface.EQUALIZER, "EQ", "预设", "频段", "自定义曲线"),
        context("sound-fx", "音效实验室", SettingsIndexSurface.SOUND_FX, "音效", "混响", "立体声宽度", "音色", "房间大小", "湿比", "360", "环绕"),
    )

    fun search(query: String, surface: SettingsIndexSurface? = null): List<SettingsIndexEntry> {
        val tokens = query.trim()
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
        if (tokens.isEmpty()) return emptyList()

        return entries.asSequence()
            .filter { surface == null || it.target.surface == surface }
            .filter { it.matches(tokens) }
            .sortedWith(compareBy<SettingsIndexEntry> { it.title }.thenBy { it.id })
            .toList()
    }

    fun searchFromSettingsRoot(query: String): List<SettingsIndexEntry> = search(query).filter {
        it.target.surface == SettingsIndexSurface.SETTINGS ||
            it.target.surface == SettingsIndexSurface.EQUALIZER
    }

    private fun setting(
        id: String,
        title: String,
        vararg keywords: String,
        category: SettingsCategory? = SettingsCategory.APPEARANCE,
        section: String? = null,
        availability: String? = null,
        isExperimental: Boolean = false,
    ): SettingsIndexEntry = SettingsIndexEntry(
        id = id,
        title = title,
        keywords = keywords.toSet(),
        target = SettingsIndexTarget(
            surface = SettingsIndexSurface.SETTINGS,
            category = category,
            sectionId = section ?: SettingsIndexSections.APPEARANCE.takeIf { category == SettingsCategory.APPEARANCE },
        ),
        availability = availability,
        isExperimental = isExperimental,
    )

    private fun context(
        id: String,
        title: String,
        surface: SettingsIndexSurface,
        vararg keywords: String,
    ): SettingsIndexEntry = SettingsIndexEntry(
        id = id,
        title = title,
        keywords = keywords.toSet(),
        target = SettingsIndexTarget(surface = surface),
    )
}
