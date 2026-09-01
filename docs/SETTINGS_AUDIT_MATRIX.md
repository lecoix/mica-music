# 设置审计矩阵

> 审计日期：2026-08-29
> 基线：当前 `exoplayer-only` 工作树；静态追踪设置入口、持久化与运行时消费点
> 方法：静态追踪“设置入口 → 持久化 owner/key → 运行时消费点 → 生效条件”。本文件不把编译或 JVM 测试当作真实设备、音频质量或视觉验收。

## 状态定义

| 状态 | 含义 | 建议 |
| --- | --- | --- |
| `ACTIVE` | 设置入口、持久化和运行时消费点均能对应 | 保留，可整理入口 |
| `CONDITIONAL` | 只在指定主题、页面、设备或内容条件下生效 | UI 应显示条件，或进入对应详情页 |
| `MISLEADING` | 用户能看到或点击，但当前条件下实际不产生效果 | 优先隐藏/禁用；不要先删除存储 key |
| `ORPHANED` | 有持久化或运行时接线，但没有正常用户入口 | 决定补入口还是保留兼容；不要直接清库 |
| `INTERNAL` | 仅供调试、预览或内部能力使用 | 保持入口关闭，除非明确允许暴露 |
| `LEGACY` | 旧 key、旧文档或旧导航接线，仍可能承担兼容职责 | 先迁移/观测，再删除 |

## 设置页用户入口

### 外观与主题

| 设置 | 持久化 owner / key | 运行时消费点与条件 | 状态 | 处理建议 |
| --- | --- | --- | --- | --- |
| 主题模式 | `AppearancePreferences` / `theme_mode` | `MicaAppRoot`、`Theme.kt`；全局生效 | `ACTIVE` | 保留在“外观”首页 |
| 强调色 | `AppearancePreferences` / `app_accent_color`、`custom_accent_color` | `MicaTheme.colors.accent`；`CUSTOM` 时额外读取自定义色 | `ACTIVE` | 自定义颜色作为该行详情，不单独占主页面 |
| 云母背景 | `AppearancePreferences` / `mica_background_preset`、`custom_mica_*` | `micaAppBackground()`、主题背景；`CUSTOM` 时额外读取自定义色 | `ACTIVE` | 与强调色合并为“颜色与背景” |
| 自定义壁纸 | `AppearancePreferences` / `custom_wallpaper_path` | 主界面背景和设置页背景；播放页/歌词页有明确排除 | `ACTIVE` | 保留；恢复默认作为同一详情页动作 |
| 隐藏状态栏 | `AppearancePreferences` / `status_bar_visibility_mode` | Activity 状态栏控制和播放页/非播放页顶部 inset | `ACTIVE` | 四档：关闭、仅播放页隐藏、仅非播放页隐藏、全部隐藏；旧 `hide_status_bar` 与 `immersive_player_status_bar` 兼容迁移为关闭/全部隐藏 |
| 迷你播放栏样式 | `PlaybackUiPreferences` / `mini_player_style` | `MiniPlayer`、底栏高度/清除空间；样式能力影响频谱 tap 资格 | `ACTIVE` | 单独放入“迷你播放栏” |
| 迷你播放栏歌词 | `PlaybackUiPreferences` / `mini_player_lyrics_enabled` | `MiniPlayer`、`HomeScreen`、`PlayerSheetHost` | `ACTIVE` | 保留 |
| 迷你播放栏逐字歌词 | `PlaybackUiPreferences` / `mini_player_word_lyrics_enabled` | 仅当迷你播放栏歌词开启且有逐字时间轴时有明显效果 | `CONDITIONAL` | 作为“迷你播放栏歌词”详情项；关闭父开关时折叠 |
| 迷你播放栏滑动切歌 | `PlaybackUiPreferences` / `mini_player_swipe_enabled` | `MiniPlayer` 的左右手势 | `ACTIVE` | 保留总开关 |
| 左滑/右滑动作 | `PlaybackUiPreferences` / `mini_player_left_swipe_action`、`mini_player_right_swipe_action` | 只有滑动切歌总开关开启时消费 | `MISLEADING` | 总开关关闭时不要显示两个动作选择器 |
| 信息行歌词 | `LyricsPreferences` / `info_row_lyrics_enabled` | `HomeScreen` 列表信息行 | `ACTIVE` | 移入“歌词输出” |
| 信息行逐字歌词 | `LyricsPreferences` / `info_row_word_lyrics_enabled` | 父开关开启、播放中且有逐字时间轴时生效 | `CONDITIONAL` | 跟随信息行歌词折叠 |
| 通知栏歌词 | `LyricsPreferences` / `notification_lyrics_enabled` | `NotificationLyricsCoordinator`；通知/媒体会话条件 | `CONDITIONAL` | 移入“歌词输出”并注明系统通知条件 |
| 车载蓝牙歌词输出 | `LyricsPreferences` / `notification_lyrics_enabled` | 与通知栏歌词共用 `NotificationLyricsCoordinator` 的歌词加载和边界调度；通知栏歌词开启时额外更新兼容车机的无队列媒体会话 | `CONDITIONAL` | 已合入通知栏歌词；保留 legacy session，需真车验收 |
| 外部歌词输出 | `LyricsPreferences` / `external_lyrics_mode` 及桌面/状态栏子项（含 `status_bar_lyrics_horizontal_offset_dp`） | `DesktopLyricsOverlayService`；桌面歌词和状态栏歌词互斥，并要求悬浮窗权限；状态栏左右微调直接更新悬浮窗 `LayoutParams.x` | `CONDITIONAL` | 已独立为“歌词 → 外部歌词”子页；只显示当前输出模式对应的位置、字号和排版子项 |
| 已填充歌词透明度/阴影/发光 | `LyricsPreferences` / `external_lyrics_opacity_percent`、`external_lyrics_shadow_strength_percent`、`external_lyrics_glow_strength_percent` | `DesktopLyricsOverlayService.ExternalLyricsLineText`；桌面与状态栏共享，0–100%；透明度只控制已填充文字且覆盖颜色内置 alpha，未填充文字固定为 42%；阴影为右下方向性窄柔影，发光为零偏移宽色晕 | `CONDITIONAL` | 放在外部歌词颜色之后；默认 100/100/0；阴影/发光不再受填充透明度联动，两种效果同时开启时相互衰减 |

### 播放与封面

| 设置 | 持久化 owner / key | 运行时消费点与条件 | 状态 | 处理建议 |
| --- | --- | --- | --- | --- |
| ReplayGain | `ReplayGainPreferences` / `replaygain_mode` | `ReplayGainStateOwner`；必须有可用 ReplayGain 标签才改变增益 | `CONDITIONAL` | 保留在“音频”或“播放音量” |
| 封面显示 | `PlaybackUiPreferences` / `cover_display_mode` | `SongCover`、播放页和列表/歌词页的显示策略 | `ACTIVE` | 保留 |
| 播放页背景 | `PlaybackUiPreferences` / `player_lower_background` | `NowPlayingBackground`；不同背景消耗不同封面/渲染能力 | `ACTIVE` | 保留为播放页核心设置 |
| 播放页 UI 颜色 | `LyricsPreferences` / `player_page_text_color` | 播放页文字、信息行、进度条和底部控件 | `ACTIVE` | 与背景放在同一详情页 |
| 播放页特殊主题 | `PlaybackUiPreferences` / `player_cover_flow_mode` | `PlayerCoverFlowMode` 决定标准、粒子、折叠、复古、拍立得等页面路径 | `ACTIVE` | 作为主题选择入口；专属选项进入主题详情 |
| 视频专辑封面 | `PlaybackUiPreferences` / `video_album_cover_enabled` | 扫描预取和播放页视频封面；播放页说明限定标准主题/特定页面条件 | `CONDITIONAL` | 仅在支持视频封面的主题下显示；需设备和文件样本验收 |
| 音乐 MV | `PlaybackUiPreferences` / `music_video_enabled` | Service 队列项有效策略 + 标准播放页 Surface；默认关闭，当前曲不重建，下一首不同歌曲生效 | `CONDITIONAL` | 音乐唯一出声；MP4 永久 video-only；真机音画与各输出路径待验收 |
| 自定义播放页下半区布局 | `PlaybackUiPreferences` / `custom_player_lower_*` | 仅 `CUSTOM_STANDARD`；`PlayerLowerLayoutConfig` 保存顺序、显隐、缩放、间距、边界、歌词行数、`freeformEnabled` / 每组件二维 offset，以及 `custom_player_lower_text_aligns`（歌名/副标题/紧凑歌词各自的靠左·居中·靠右）、`custom_player_lower_hidden_controls`（播放控制五键逐个显隐）、`custom_player_lower_cover_tap_play_pause` 与 `custom_player_lower_cover_shadow`（选中封面时的上下文开关）。设置页只保留“进入播放页布局编辑”入口；竖屏播放页空白处长按也可进入编辑，连续手势与上下文控件先写局部草稿，保存后才持久化。旧独立 key `custom_standard_cover_tap_play_pause` / `custom_standard_cover_shadow` 仅在对应新 key 缺失时回退 | `CONDITIONAL` | 保留播放页内自由布局编辑器；横屏不提供编辑入口。新封面开关 key 缺失且无旧 key 即视为关闭 |
| 封面底边进度 | `PlaybackUiPreferences` / `cover_edge_progress` | `SettingsPlaybackPanel` 先按主题/背景 capability 判断；`CUSTOM_STANDARD`、拍立得及标准主题无效背景组合不显示该入口，支持的特殊主题继续使用各自覆盖规则 | `CONDITIONAL` | capability 隐藏已落地；后续只需保持主题契约与 UI 条件同步 |
| 播放时屏幕常亮 | `PlaybackUiPreferences` / `keep_screen_on_when_playing` | 播放页打开且正在播放时生效 | `ACTIVE` | 保留 |
| 下半屏沉浸 | `PlaybackUiPreferences` / `player_immersive_lower` | 设置入口直接按 `playerCoverFlowMode.supportsImmersiveLower` 条件显示，运行时 `PlayerPageState` 继续做 capability 防线 | `CONDITIONAL` | 无效主题入口已隐藏；继续保持 UI capability 与运行时判断双重一致 |
| 折叠歌词行数 | `PlaybackUiPreferences` / `compact_lyrics_line_mode` | 标准、粒子、折叠、复古主题有效；自定义标准和拍立得不消费该值 | `CONDITIONAL` | 跟随播放页主题详情显示 |
| 隐藏歌名括号内容 | `PlaybackUiPreferences` / `strip_song_title_parentheses` | 播放页和歌词页共享标题显示 helper | `ACTIVE` | 保留在“标题显示”或播放页详情 |
| 频谱条 | `PlaybackUiPreferences` / `spectrum_enabled` | 播放页进度/封面区域；部分样式仍会因频谱 tap 资格取样 | `CONDITIONAL` | 显示“可能影响渲染/功耗”；真机验收后再决定默认值 |
| 信息行：格式、位深/采样率、比特率 | `PlaybackUiPreferences` / `player_info_show_*` | `PlayerInfoVisibility` → 播放页信息行 | `ACTIVE` | 合并为“信息行内容”编辑器 |
| 信息行：速度、音高、当前时间 | 同上 | 由实时播放 tuning/系统时间提供；分别控制显示 | `ACTIVE` | 保留独立开关，但不必全部占主页面 |
| 信息行自定义文字 | `PlaybackUiPreferences` / `player_info_show_custom`、`player_info_custom_text` | 自定义开关开启时追加到信息行 | `CONDITIONAL` | 与信息行内容编辑器合并 |
| Hi-Res 标志样式 | `PlaybackUiPreferences` / `hi_res_badge_style`、`hi_res_badge_custom_image_path` | 播放页信息行右侧；自定义图片路径失效时回退默认样式 | `CONDITIONAL` | 放入“信息行内容”详情；标明当前仅播放页消费 |

### 音频与 USB 设备

| 设置 | 持久化 owner / key | 运行时消费点与条件 | 状态 | 处理建议 |
| --- | --- | --- | --- | --- |
| USB 独占输出模式 | `UsbHybridPreferences` / `usb_hybrid_output_mode` | `MicaMediaService` 只把偏好变化提交给 `UsbOutputCoordinator`；Coordinator 通过窄 playback port 与 `UsbHybridSessionOwner` 以 break-before-make 重建输出栈，默认 Shared PCM，失败不自动回退 | `CONDITIONAL` | 保留为“音频与设备”的独立子页；Shared、Exact PCM、DoP、实验 Native 必须显式选择 |
| USB 当前状态与格式 | 不持久化；`UsbHybridRuntimeMonitor.facts` | owner 发布 permission、claimed、exclusive、transportExact、signalExact、实际格式、epoch/session 与失败原因 | `ACTIVE` | UI 只显示 facts，不得从所选模式推断 ACTIVE；状态必须有文字，不能只靠颜色 |
| USB 授权并重试 / 切回 Shared PCM | 无独立 key；动作交给 Hybrid owner / 模式偏好 | 重试产生新 request epoch；切回 Shared PCM 同步执行输出栈切换 | `ACTIVE` | 作为动作行，不和偏好状态混写；失败后保持请求模式、队列和位置 |
| USB 诊断导出 | 无普通偏好；`UsbHybridDiagnosticsReport` | 导出 APK、stable/runtime identity、descriptor digest、协商结果、URB telemetry 与最近错误，不导出原始 serial | `ACTIVE` | 保留在 USB 子页“支持与诊断”；v1 不提供运行时 JSON quirk 导入 |

### 歌词页

| 设置 | 持久化 owner / key | 运行时消费点与条件 | 状态 | 处理建议 |
| --- | --- | --- | --- | --- |
| 歌词页主题 | `LyricsPreferences` / `lyrics_page_theme` | 经典、歌词云、Letter 等主题分支 | `ACTIVE` | 作为歌词页首页选择 |
| Letter 朱印图片 | `LyricsPreferences` / `letter_seal_custom_image_path` | 仅 `LyricsPageTheme.LETTER`；播放页信笺渲染消费 | `CONDITIONAL` | 放入 Letter 专属详情 |
| Letter 朱印大小/浓度/旋转 | `LyricsPreferences` / `letter_seal_size_dp`、`letter_seal_opacity_percent`、`letter_seal_rotation_degrees` | 仅 Letter 主题 | `CONDITIONAL` | 同上，不能出现在通用歌词设置 |
| 分割双语歌词 | `LyricsPreferences` / `lyric_split_enabled` | parser/显示行选择路径 | `ACTIVE` | 保留 |
| 双语歌词显示 | `LyricsPreferences` / `lyrics_bilingual_display_mode` | 仅父开关开启且当前行可拆分时生效 | `CONDITIONAL` | 跟随父开关折叠 |
| 歌词颜色 | `LyricsPreferences` / `lyrics_page_text_color` | 全屏歌词与播放页迷你歌词 | `ACTIVE` | 保留 |
| 逐字动画 | `LyricsPreferences` / `lyrics_word_animation_preset` | 经典列表及歌词云不可用时的回退页；需要真实逐字时间轴 | `CONDITIONAL` | 放入“经典列表/动画”详情 |
| 强制使用逐字歌词样式 | `LyricsPreferences` / `lyric_line_fill_enabled` | 经典列表和播放页迷你歌词；无逐字时间轴时使用播放进度填充 | `CONDITIONAL` | 与逐字动画合并说明，避免两个概念重复 |
| 歌词页对齐 | `LyricsPreferences` / `lyrics_page_alignment` | 全屏歌词、间奏点和列表布局 | `ACTIVE` | 保留 |
| 原歌词字号/翻译字号 | `LyricsPreferences` / `lyrics_page_font_size`、`lyrics_page_translation_font_size` | 歌词页排版 | `ACTIVE` | 合并成“字号与间距” |
| 行间距 | `LyricsPreferences` / `lyrics_page_line_spacing` | 经典列表等歌词页列表排版 | `ACTIVE` | 保留；已有独立持久化路径 |
| 歌词页沉浸模式 | `LyricsPreferences` / `lyrics_page_immersive` | 歌词页隐藏进度条和底部控件 | `ACTIVE` | 保留 |
| 歌词字体 | `FontPreferences` / `lyric_font_*` | `Theme.kt` 的 lyric font family | `ACTIVE` | “歌词字体”和“导入字体文件”合并为一个更换入口 |
| 全局字体 | `FontPreferences` / `global_font_*` | `MicaAppRoot` / `Theme.kt` 有消费链路，但当前设置页没有导入或选择入口 | `ORPHANED` | 补齐全局字体入口，或在确认废弃后做兼容迁移；暂不删除 key |

### 曲库、扫描与高级

| 设置 | 持久化 owner / key | 运行时消费点与条件 | 状态 | 处理建议 |
| --- | --- | --- | --- | --- |
| 曲库文件夹 | `LibraryScanSettings` / `library_tree_uri`、`library_folder_label` | `MusicLibrary`、SAF 文件夹扫描 | `ACTIVE` | 保留在“曲库来源” |
| 重新扫描曲库/扫描全部音乐 | `LibraryScanSettings` 及扫描入口 | `FolderScanner`、`MediaStoreScanner`；受权限和扫描状态控制 | `ACTIVE` | 作为动作区，不和偏好开关混排 |
| 排除目录 | `LibraryScanSettings` / `excluded_scan_directories` | 两种扫描源共享过滤 | `ACTIVE` | 保留 |
| 最短曲目时长 | `LibraryScanSettings` / `min_track_duration_sec` | `ScanOptions` → 两种 scanner | `ACTIVE` | 保留 |
| 纳入非“音乐”标记音频（已移除 UI） | `LibraryScanSettings` / `include_non_music_audio` | `MediaStoreScanner`；兼容筛选固定开启，旧 key 不再参与配置 | `LEGACY` | 不再暴露开关；保留旧 key，避免历史安装数据迁移时丢失 |
| 深度分析音质与封面 | `LibraryScanSettings` / `deep_metadata_probe` | `ScanOptions` → `FolderScanner`、`MediaStoreScanner` | `ACTIVE` | 运行时有效；应改写说明，不能保留“我也不知道有什么用” |
| 艺术家分割 | `LibraryBrowseSettings` / `artist_split_*` | 艺术家名称归一化和曲库分组 | `ACTIVE` | 放入“曲库解析”详情 |
| 歌词优先级 | `LyricsPreferences` / `lyrics_slot_priority` | 歌词读取链路选择 TTML/LRC/内嵌槽位 | `ACTIVE` | 从“高级”移入“歌词来源” |
| 独占音频焦点 | `PlaybackUiPreferences` / `audio_focus_enabled` | `ExoPlaybackStack` 在开始播放前重新应用 | `ACTIVE` | 移入“音频与设备”；保留下次开始播放生效说明 |
| 音频硬件卸载（Offload） | `AudioOffloadPreferences` / `audio_offload_enabled`、`audio_offload_verified_failure_build` | HIFI 且 EQ/频谱不需要 PCM 时允许；实际 offload 失速后一次性切回 PCM，恢复确认后按系统 fingerprint 自动关闭 | `ACTIVE` | 放入“诊断与系统”；默认开启，明确显示内置或本机故障禁用原因，用户可手动重试 |
| 元数据调试 | 无普通用户偏好；导航到 `MetadataDebug` | 逐首诊断页面 | `INTERNAL` | 从用户设置中分离到“诊断”或保留隐藏入口 |
| 系统空间音频 | 无普通用户偏好；导航到 `SpatialAudio` | 读取系统 Spatializer/输出能力 | `INTERNAL` | 属于诊断工具，不应和偏好开关混排 |
| 系统权限与应用信息 | Android 系统设置 Intent | 权限和应用管理 | `ACTIVE` | 作为“系统”动作保留，不算普通偏好 |

## 不在设置首页的持久化设置

| 设置入口 | 持久化 owner / key | 当前入口 | 状态 | 审计结论 |
| --- | --- | --- | --- | --- |
| 歌曲排序、升降序、自定义顺序/锁定 | `LibraryBrowseSettings` / `song_sort_*`、`custom_song_order*` | 歌曲排序 Sheet | `ACTIVE` | 保持上下文入口，不必搬到全局设置 |
| 专辑/艺术家排序与网格列数 | `LibraryBrowseSettings` / `album_browse_*`、`artist_browse_*` | 专辑/艺术家浏览 Sheet | `ACTIVE` | 保持上下文入口 |
| 文件夹浏览模式 | `LibraryBrowseSettings` / `folder_browse_mode` | 文件夹页切换 | `ACTIVE` | 保持上下文入口 |
| 歌曲列表信息显示 | `PlaybackUiPreferences` / `song_list_info_*` | 歌曲排序 Sheet 的信息行区域 | `ACTIVE` | 语义属于曲库显示，但当前入口是合理的上下文设置 |
| 专辑/艺术家统计信息显示 | `PlaybackUiPreferences` / `artist_info_*`、`album_info_*` | 浏览分组显示 Sheet | `ACTIVE` | 不要因为不在 SettingsScreen 就判定失效 |
| 睡眠定时最近使用时长 | `SleepTimerPreferences` / `sleep_timer_last_duration_minutes` | 播放页长按菜单的睡眠定时 Sheet | `ACTIVE` | 属于快捷动作记忆，不应塞进普通设置页 |
| 均衡器开关、预设、频段、自定义曲线 | `EqualizerPreferences` + `EqCustomProfileStore` | 独立 `EqualizerScreen` | `ACTIVE` | 保持独立音频工具入口 |
| 音效实验室开关、宽度、音色、混响、360° 环绕 | `SoundFxPreferences` | 独立 `SoundFxScreen`（设置 → 音频） | `ACTIVE` | 默认湿比与环绕强度为 0；仅 Shared PCM；USB 独占旁路 |

## 内部、隐藏和兼容项

| 项目 | 证据 | 状态 | 处理建议 |
| --- | --- | --- | --- |
| `DYNAMIC_LIGHT` 播放页背景 | 枚举、背景渲染和转场仍有消费点；设置选项主动过滤它 | `INTERNAL` / `CONDITIONAL` | 不要当成死代码删除；如果长期不重新开放，应补充存储值迁移策略 |
| 粒子封面调参 `particle_cover_*` | `ParticleCoverTuning` 被预览路由和播放页消费；普通 Settings 没有入口 | `INTERNAL` / `ORPHANED` | 继续保持预览入口关闭；另行决定是否需要开发构建入口 |
| `global_font_*` | 有读取、写入和主题消费，但没有正常入口 | `ORPHANED` | 见上方全局字体项 |
| `player_info_show_duration` | `playerInfoVisibility()` 只在新 `showCurrentTime` 不存在时读取 | `LEGACY` | 保留兼容读取；新写入只写 `showCurrentTime` |
| `immersive_player_status_bar` | `AppearancePreferences` 对旧 key 做兼容读取 | `LEGACY` | 保留读取，不再新增写入 |
| `hide_status_bar` | `AppearancePreferences` 对旧布尔 key 做兼容读取 | `LEGACY` | `false` → 关闭，`true` → 全部隐藏；不再新增写入 |
| SettingsScreen 的粒子/拍立得预览回调 | `SettingsScreen` 接收，但当前页面没有消费；路由仍存在 | `LEGACY` | 清理无用参数和导航接线；不要因此删除预览页面 |
| 设计文档中的旧设置分类 | 2026-08-12 已同步 `DESIGN_SPEC.md`：稳定分类为 `APPEARANCE / PLAYBACK / LYRICS / LIBRARY / AUDIO / DIAGNOSTICS`，列表/专辑/艺术家显示设置保留上下文入口 | `ACTIVE` | 后续调整 `SettingsCategory` 时同时更新本矩阵与 `DESIGN_SPEC.md` |

## 当前优先级

## 本轮已实施

- 播放页和歌词页子页面把主题选择置于最上方；主题专属设置按当前主题条件显示。
- 稳定分类调整为“外观 / 播放页 / 歌词 / 曲库与扫描 / 音频与设备 / 诊断与系统”。
- 迷你播放栏移入外观；歌词优先级、信息行和通知栏（含车载蓝牙输出）移入歌词；扫描行为移入曲库与扫描；ReplayGain 与音频焦点移入音频与设备。
- 按实际能力隐藏视频专辑封面、封面底边进度、下半屏沉浸的无效组合；迷你播放左右动作和信息行逐字歌词继续按父开关折叠。
- 合并歌词字体的重复入口；移除 SettingsScreen 未消费的粒子/拍立得预览回调，但保留内部预览路由和相关 preference key。
- 移除“纳入非‘音乐’标记的音频”入口；MediaStore 兼容筛选固定开启，旧 preference key 保留但不再提供用户控制。
- 新增统一 `SettingsSearchIndex`：为设置页和上下文入口提供稳定 ID、关键词、分类、目标区段、生效条件和实验标记；设置根页已接入搜索。
- 搜索结果当前进入对应设置分类；未改动现有 preference key、EQ/睡眠定时等独立入口。
- 音频硬件卸载（Offload）熔断已落地：`AudioOffloadCircuitBreaker` 检测真实 offload AudioTrack 缓冲但未起播，确认回 PCM 且播放推进后按 build fingerprint 记录失败；“诊断与系统”面板展示禁用原因，用户可手动重试，系统/固件更新会给一次新尝试。

### P0：误导性入口（已完成）

1. `playerImmersiveLower` 已按 `supportsImmersiveLower` 条件显示，运行时仍保留 capability 防线。
2. `coverEdgeProgress` 已按主题/背景 capability 条件显示。
3. 迷你播放栏左右动作已跟随总开关折叠。
4. 歌词字体重复入口已合并。
5. SettingsScreen 未消费的预览回调已清理。

### P1：设置入口重组（已完成）

1. 稳定分类已调整为“外观 / 播放页 / 歌词 / 曲库与扫描 / 音频与设备 / 诊断与系统”。
2. Letter 朱印进入主题详情；`CUSTOM_STANDARD` 只保留“进入播放页布局编辑”入口，实际二维编辑在竖屏播放页完成。
3. 歌曲排序、浏览显示、睡眠定时、EQ 和音效实验室保持上下文/独立入口。
4. UI 重组未迁移既有 preference key 和运行时 owner。

### P2：为搜索准备统一索引（已完成）

索引实现位于 `SettingsSearchIndex.kt`，覆盖设置分类以及歌曲排序、浏览显示、文件夹模式、睡眠定时、EQ 和音效实验室等上下文入口。后续搜索功能应直接消费该索引，结果跳转到对应详情页，不要重新创建一套独立的设置读写路径。

### 后续：补齐搜索定位

设置根页已经支持中文/英文关键词搜索、清空和结果跳转分类。结果高亮、分类内区段定位和上下文入口跳转仍未实现；后续需要为各设置行补充稳定的滚动定位锚点，并在真机上验收返回行为和键盘遮挡。

## 验证边界

本矩阵已经完成代码级入口和消费点追踪，但以下内容仍不能仅凭静态代码宣称有效：车载蓝牙歌词、视频封面在不同主题下的最终表现、频谱功耗、offload 熔断在不同 ROM/固件上的实际表现、音频焦点在不同 OEM 上的行为、`DYNAMIC_LIGHT`/粒子主题的真实设备表现，以及 USB 子页与其他设置的返回手势、TalkBack 和视觉层级。这些应单独做真机验收。
