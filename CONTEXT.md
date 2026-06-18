# Mica Music — 播放

Mica 本地音乐播放器的领域语言：曲目与队列、出声路径、播放页 UI 与布局。本文只定义**叫什么**、**指什么**。

## 曲库与曲目

**Song（曲目）**：
曲库中的一首可播放音频，携带元数据、封面、歌词与媒体 URI。
_Avoid_: track（除切歌动画 `TrackSkipDirection` 外）、media item

**LyricLine（歌词行）**：
带时间戳的一句歌词，用于同步滚动与行内 seek。
_Avoid_: caption、subtitle

**Playlist（歌单）**：
用户保存的静态曲目集合；选中后**装入**播放队列，本身不驱动出声。
_Avoid_: 与「播放队列」混用

## 播放队列与控制

**Playback queue（播放队列）**：
当前会话中待播与在播的 `Song` 有序列表，含 `currentIndex`；上一曲 / 下一曲、队列 Sheet、封面流邻槽均以此为准。
_Avoid_: playlist、播放列表（指歌单时）

**PlaybackQueueMode（播放模式）**：
队列推进策略：顺序（OFF）→ 列表循环（REPEAT_ALL）→ 单曲循环（REPEAT_ONE）→ 随机（SHUFFLE）。
_Avoid_: shuffle mode、repeat mode（拆开描述时仍用枚举名）

**PlaybackQueueNavigator**：
根据播放模式计算下一首 / 上一首索引的纯规则；UI 与 `PlayerController` 均不得自行重写跳转逻辑。
_Avoid_: 在业务层手写 `index + 1`

**PlayerController**：
App 侧播放**命令门面** + Compose 状态镜像：队列变更经 `MediaController` 写入服务；`songQueue` / `PlaybackQueueState` 由服务队列镜像填充，**不是**出声权威源。
_Avoid_: player view model、media player（指底层引擎时）

**Authoritative playback queue（权威播放队列）**：
服务侧 `MicaCompositePlayer.playlistItems`（经 `playbackQueueSnapshot()` 暴露）为唯一真相源；`ServicePlaybackEngineCoordinator` 的 `onEnded` / `startAt` / 失败跳曲均读此快照。App 内 `PendingPlaybackNavigation` 在 binder 延迟时携带切歌意图。
_Avoid_: 在 `PlayerController` 内维护与服务等长的并行队列并驱动出声

**PlaybackSession（播放会话）**（legacy，逐步由 `ServicePlaybackStateStore` 替代）：
上次退出时的「当前曲 ID + 进度毫秒」，用于曲库就绪后恢复，**不**自动开始播放。
_Avoid_: session token、playback state（指 UI 三态时）

**Insert play next（插播下一首）**：
将一首曲插入当前项之后；经 `MediaController` 更新 Exo 播放列表，当前曲不中断，曲终或用户显式切歌时出声到插入项。
_Avoid_: add to queue（未强调「紧挨下一首」时）

## 播放状态（UI 只读输入）

播放页 UI **只读**以下三态；不得绕过它们直接摸 `PlayerController` 内部字段。

**PlaybackSurfaceState**：
表面播放态：当前曲、播放 / 缓冲 / 错误、播放模式、队列当前下标。
_Avoid_: player state（笼统说法）、alacStreamActive

**PlaybackProgressState**：
进度态：当前位置、总时长、`pendingSeekMs`（seek 尚未反映到进度前）。
_Avoid_: timeline、position state

**PlaybackQueueState**：
队列态：完整队列列表与 `currentIndex`。
_Avoid_: queue snapshot（非持久化语境时）

## 播放操作（UI 输出）

**NowPlayingActions**：
播放页对 `PlayerController` / 设置的**唯一**写操作集合：播放控制、seek、队列编辑、播放模式、沉浸切换、插播与整队替换。
_Avoid_: 在子组件里散落 `playerController.xxx()` 调用

**Seek UI active（进度 UI 钉住）**：
用户拖动进度条或歌词 seek 期间，进度展示与播放器进度解耦，避免条在手指下跳动。
_Avoid_: scrubbing（文档与 issue 中用中文描述）

## 出声与媒体服务

**MicaMediaService**：
独立于 Activity 的 `MediaSessionService`：持有 `ExoPlayer`、`MediaSession` 与播放协调器，对接通知栏、锁屏、蓝牙与系统媒体控制。
_Avoid_: playback service（无专名时）、background service

**Exo playback pipeline（Exo 播放管线）**：
唯一出声路径：`ExoPlayer` → `MicaExtractorsFactory` / `MicaRenderersFactory` → `libffmpegJNI.so`（ALAC、DSD 等扩展）→ `MicaAudioProcessorChain`（DSD 降采样 / 频谱 / EQ）→ `AudioTrack`。
_Avoid_: 软件播、双后端、libmica_ffmpeg

**PlaybackRouteDecision**：
对一首 `Song` 判定 Exo 是否可播；`.dsf` 与常规格式为 `Supported`，`.dff` 为 `Unsupported`（可扫描入库，播放时拒绝并提示）。
_Avoid_: codec path、PlaybackBackendKind

## 播放页与壳层

**播放页（Now Playing page）**：
全屏播放界面：封面区、下半屏、队列 Sheet；入口为迷你播放栏展开或 `PlayerSheetHost`。
_Avoid_: player screen（文档用中文）、full player

**PlayerSheetHost**：
迷你栏与播放页之间的展开 / 收起宿主，管理全屏 overlay 与返回键。
_Avoid_: player modal、bottom sheet（指播放页整体时）

**迷你播放栏（MiniPlayer）**：
主页底部的紧凑播放控件；点击展开播放页。样式由 `MiniPlayerStyle` 决定。
_Avoid_: mini bar、bottom player、now playing bar

**下半屏（lower panel）**：
播放页封面以下的区域：元数据、歌词、进度与控制区（chrome）。
_Avoid_: bottom sheet、footer

**Playback chrome（播放控制区）**：
下半屏中的进度条、播放按钮五件套、频谱与相关间距；与封面区相对。
_Avoid_: controls only、transport

**Playback queue sheet（队列 Sheet）**：
播放页内编辑当前播放队列的浮层：跳转、排序、删除。
_Avoid_: queue dialog、playlist sheet

## 播放页场景与布局

**PlayerPageScene**：
播放页互斥主场景，优先级为歌词 > 沉浸 > 普通（`Lyrics` > `Immersive` > `Normal`）。
_Avoid_: mode、state（与三态 `Playback*State` 混淆时）

**Lyrics focus（歌词聚焦）**：
从普通播放页过渡到全屏歌词页的连续布局动画进度（`lyricsProgress` / `lyricsFocus`）；驱动封面缩小与底栏几何插值。
_Avoid_: lyrics mode（指布尔 `lyricsExpanded` 时）、歌词页（指动画结束后的稳定态时）

**Lyrics page（歌词页）**：
`lyricsExpanded == true` 时的稳定 UI：全屏歌词列表、专用进度与控制布局。
_Avoid_: expanded lyrics（指组件名时可用「展开歌词」）

**Compact lyrics（紧凑歌词）**：
普通播放页下半屏中的少量歌词行（通常三行），点击可进入歌词页。
_Avoid_: inline lyrics、mini lyrics

**Expanded lyrics（展开歌词）**：
歌词页中的全屏可滚动歌词列表，支持自动滚动与行内 seek。
_Avoid_: fullscreen lyrics（与场景名重复时）

**Immersive lower（沉浸模式）**：
隐藏或弱化下半屏 chrome、突出封面的浏览态；由 `playerImmersiveLower` 设置项控制。
_Avoid_: fullscreen mode、cinema mode

**PlayerPageLayoutEngine**：
单帧原子布局计算器：给定冻结后的尺寸与动画 progress，产出整页几何与 alpha。
_Avoid_: 在组件内各自算一套布局

**PlayerPageFrame**：
布局引擎一帧的输出：场景、各 progress、封面区 `CoverFrame`、下半屏 `LowerPanelFrame` 及 `spectrumEnabled` 等。
_Avoid_: layout state（泛指时）

**Layout freeze（布局冻结）**：
模式切换前先快照 `panelHeight`、`layoutMode`、间距等，再算帧、再跑动画；禁止在动画途中用正在变的测量值重算目标。
_Avoid_: debounce layout

**Track skip direction（切歌方向）**：
上一曲 / 下一曲时供封面擦除动画消费的方向信号（`TO_PREVIOUS` / `TO_NEXT`），一次性消费。
_Avoid_: swipe direction（封面手势未切歌时）

**Cover switch guard（切歌保护期）**：
切歌或封面流手势按下起，至回弹 / 切歌动画结束前；此期间关闭频谱，避免旧封面或旧频谱闪帧。
_Avoid_: debounce、loading

## 封面与进度呈现

**PlayerCoverFlowMode（封面流模式）**：
封面区交互形态：`STANDARD`（标准大封面）、`PAUSE_FOLD`（平行封面带）、`RETRO_3D`（复古立体封面流）。
_Avoid_: carousel（无专名时）、cover flow（小写泛指）

**Cover flow（封面流）**：
非标准封面模式下的邻曲封面带与手势切歌。
_Avoid_: 3D cover（未指 RETRO_3D 时）

**CoverDisplayMode（封面显示模式）**：
列表 / 迷你栏 / 播放页封面的缩放策略：`CROP_FILL` 或 `FIT_ORIGINAL`（原样比例）。
_Avoid_: aspect mode、fit mode

**Cover edge progress（封面底边进度）**：
进度条叠在封面底边，而非标准进度区；与「标准进度区」互斥显示（交叉淡化）。
_Avoid_: edge seek bar、overlay progress

**Standard progress（标准进度区）**：
下半屏 chrome 中的常规进度条与时间显示；与封面底边进度二选一为主显示。
_Avoid_: normal progress bar（契约外简称）

**Chrome progress alpha（控制区进度透明度）**：
标准进度与封面底边进度交叉淡化用的统一 alpha；切换期间标准进度条保持挂载。
_Avoid_: 用条件挂载 / 卸载切换两套进度

## 频谱与视觉设置

**Spectrum（频谱）**：
附着在**当前有效进度布局**上的实时频谱条；是否显示由 `PlayerPageFrame.spectrumEnabled` 统一决定，组件不得自行推导。
_Avoid_: visualizer、频谱模式（暗示独立布局时）

**Spectrum eligibility（频谱资格）**：
组件优先级：歌词切换 > 沉浸 > 切歌保护期 > 稳定播放且设置开启。不满足时频谱必须关闭。
_Avoid_: 根据 `lyricsProgress` 或 `showStandardProgress` 在组件层再判一次

**AppUiSettings**：
界面偏好：主题、播放页下半背景、封面流、沉浸、封面底边进度、频谱、迷你栏样式等；播放页只读、经 `NowPlayingActions` 或设置页写入。
_Avoid_: preferences（无专名时）、theme settings（仅颜色时）

**PlayerLowerBackgroundMode（下半屏背景）**：
播放页下半屏背景样式：主题色、封面渐变、封面模糊。
_Avoid_: player theme、background preset

**Shared cover transition（共享封面转场）**：
迷你播放栏与播放页之间同一封面矩形的连续转场（共享元素动画）。
_Avoid_: hero animation（无专名时）
