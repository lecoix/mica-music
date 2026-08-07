# Mica Music — 播放

Mica 本地音乐播放器的领域语言：曲目与队列、出声路径、播放页 UI 与布局。本文只定义**叫什么**、**指什么**。

## 曲库与曲目

**Song（曲目）**：
曲库中的一首可播放音频，携带元数据、封面、canonical `LyricsDocument` 与媒体 URI。`Song` 不提供第二个可写歌词列表。
_Avoid_: track（除切歌动画 `TrackSkipDirection` 外）、media item

**LyricLine（歌词行）**：
由 `LyricsSession` 从歌词文档派生的兼容歌词行，用于既有同步滚动与行内 seek UI；不得回写扫描或 Room。
_Avoid_: caption、subtitle

**LyricsDocument（歌词文档）**：
渲染器无关的版本化歌词事实来源：格式（`LyricsFormat`）、来源位置（`LyricsOrigin`）、行、原文/读音/译文分段、逐字 token 与行结束时间。parser 只判定格式，reader 只判定来源位置，因此外挂 TTML、内嵌 LRC 等组合可以被准确表达。`LyricTextRole` 含 `ORIGINAL`、`READING`（文件自带罗马音/音译，如 TTML `x-roman` / `x-romanization`、Apple `iTunesMetadata/transliterations`，以及 LRC/SPL 同戳三行中的第 2 行 / 主行后紧邻无戳续行在三轨时的读音行）、`TRANSLATION`、`EXTRA`。LRC/SPL：同戳 2 行→原文+译文；同戳 3 行及以上→原文+读音+译文（对齐网易三轨合并）；主行后紧邻无时间戳行按 SPL 挂为续行（空行断开）。读音默认显示在原文上方，可由 `LyricsPreferences.lyricReadingEnabled` 关闭。LRC/SPL 逐行只丢空、`//`、纯音符、元数据/容器噪声（`LyricsSanitizer.isIgnorableLyricText`）；内嵌字节解码的乱码/可渲染性仍由 `LyricsEncoding` 负责。自数据库 v9 起，v2 对象 JSON 按 `EMBEDDED`、`EXTERNAL_LRC`、`EXTERNAL_TTML` 三槽持久化在 `song_lyrics.lyricsJson`，运行时先读取轻量槽位目录，再按用户优先级只读取并解码第一个有效载荷；损坏载荷回退到下一个已存在槽。`songs.lyricsJson` 仅作为 `MIGRATION_8_9` 的旧数据输入保留，不再是运行时事实来源，并应在后续显式 Room schema migration 中删除。`song_lyrics.revision` 为兼容字段，不作为读取可见性条件；读取失败时旧歌词按稳定 `songId` 保持可见。`lyricsDataVersion` 表示数据库已成功提交的 parser 代次，并参与 UI 与通知歌词的内存缓存键；parser 升级只有在完整扫描零歌词读取失败时才推进。v1 `source` 与历史 `[{t,x,c,e}]` 数组仅由 `LyricsDocumentCodec` 兼容读取。
入库阶段不再用显示分隔符把普通文本改写成 `TRANSLATION`：`splitPartsAtIngest` 只保留源角色，文本分隔只是展示层启发式，结构化角色与逐字时间由源文档直接保留。
_Avoid_: 让 UI 从 `LyricLine.text` 再猜双语结构；把 `songs.lyricsJson` 重新作为运行时歌词事实来源

**Lyrics cache coordinator（歌词缓存协调器）**：
进程内共享的有界歌词缓存 owner：成功歌词批次按 `songId` 失效全部优先级缓存，运行时 content generation 防止失效前启动的旧查询重新污染缓存；同 key 请求 single-flight，不同 key 最多并发 2 个真实加载，预取最多占 1 个槽。播放页订阅按歌曲失效事件，通知歌词复用同一缓存。generation 只保留缓存中或加载中的少量歌曲，不随 10,000 首扫描常驻增长。
_Avoid_: 用 parser 版本代替内容失效版本；为全部曲库歌曲常驻 generation；恢复全局加载 mutex 或允许无界并行歌词解码

**Lyrics timeline（歌词时间轴）**：
`LyricsTimelineEngine` 基于 `LyricsDocument` 和播放位置输出行、行间空档、首行前与末行后阶段；当前行索引仍由兼容的 `LyricsSync` 计算，并在 `LyricsRenderState` 集中汇合。
_Avoid_: 每个歌词界面各自计算当前行或空档

**LyricsRenderState（歌词渲染状态）**：
全屏、紧凑与通知歌词共用的渲染输入，包含当前行索引和时间轴快照。歌词 UI 不直接调用 `LyricsSync.indexForPosition()`。

**LyricsSession（歌词会话）**：
一首歌在单个运行时消费链路中的歌词解释模块，直接接收 canonical `LyricsDocument`，持有稳定的兼容行视图、`LyricsTimelineEngine` 与定时歌词判定，并按播放位置产生 `LyricsRenderState`。播放页、迷你播放器与通知协调器复用各自会话；兼容 `LyricLine` 只在此处派生。
_Avoid_: 在 UI 或轮询回调中直接调用 `renderStateAt()` 重建会话；把运行时会话写入 Room
_Avoid_: 在不同显示面各自解释 `lyrics + positionMs`

**Y 间奏行（Y interlude）**：
全屏歌词中的合成三点间奏。仅当当前 active line id 集合为空、存在 `begin > playbackPositionMs` 的下一句、到下一句的 `delta >= 7000ms`，且上一句具有来源提供的 `endTimeMs` 并已结束时插入；普通 LRC 不得由大时间戳间隔推断间奏。
_Avoid_: 静态地按相邻歌词开始时间插入间奏

**歌词云间奏（lyrics cloud interlude）**：
歌词云对可信长空档的沉浸式表达。仅当上一句具有来源提供的 `endTimeMs`，且到下一句的完整空档至少为 7000ms 时覆盖整个空档：取消当前句高亮，歌词云轻微退远，主题色呼吸光团叠在歌词上方，镜头停留于前后句之间并在空档末段靠近下一句。暂停或系统关闭动效时保留静态柔光。此视觉状态不改变 Y 间奏行的插入规则。
_Avoid_: 从普通 LRC 的相邻开始时间推断歌词云间奏；让光团拦截歌词点击

**Playlist（歌单）**：
用户保存的静态曲目集合；选中后**装入**播放队列，本身不驱动出声。`MicaApp.playlistStore` 是进程内唯一 `PlaylistStore` owner；主页与播放页由装配层接收同一实例。持久化由 Room `playlists` / `playlist_songs`（schema v17）承载；首次启动把旧 `mica_playlists` JSON 一次性迁入，之后所有增删改先写 Room 成功再更新内存，写失败不发布内存变更。
_Avoid_: 与「播放队列」混用；在 Composable 内自行构造 `PlaylistStore`

**Library scan settings（曲库扫描设置）**：
曲库扫描相关偏好的窄门面（`LibraryScanSettings`）：最短时长、纳入非音乐音频、深度元数据探测、排除目录、SAF 曲库目录、上次扫描来源与歌词 parser 版本。与浏览排序、播放页 UI、外观、歌词、EQ 分属不同 preference 门面；物理存储仍为单一 `mica_settings`（经 `MicaSettingsStore`）。
_Avoid_: 把主题、播放页、歌词页、EQ 等非扫描偏好塞进此门面

**Library browse settings（曲库浏览设置）**：
`LibraryBrowseSettings`：歌曲列表排序、专辑/艺术家浏览排序与网格列数。Home 排序 Sheet 与 `LibraryCatalogPublisher` 经此读写。
_Avoid_: 在 Home 或 catalog 内直接读 `SharedPreferences`

**Playback UI preferences（播放页 UI 偏好）**：
`PlaybackUiPreferences`：播放页背景、迷你栏、封面行为、粒子参数、频谱资格相关开关、折叠歌词行数、列表/播放页信息可见性、常亮与沉浸等。`AppUiSettings` 的 playback 字段与媒体侧 `SpectrumAnalyzerStateOwner` 经此读取。
_Avoid_: 在 Service 或 Composable 内散落读取 mini player / spectrum key

**Wallpaper viewport state（壁纸视口状态）**：
Activity 窗口生命周期内的瞬时布局坐标，由同一 Activity 的背景与悬浮播放栏两个 Compose root 共享；不属于用户偏好，也不由 `AppUiSettings` / ViewModel 持有。
_Avoid_: 在每个 Compose root 内分别 `remember` viewport；把像素坐标持久化或塞回 AppUiSettings

**Appearance preferences（外观偏好）**：
`AppearancePreferences`：主题模式、隐藏状态栏、强调色、云母背景。`AppUiSettings` 与 `StatusBarController` 经此读写。
_Avoid_: 在 UI 层直接写 theme / accent key

**Lyrics preferences（歌词偏好）**：
`LyricsPreferences`：歌词拆分、双语展示、字号与对齐、列表主题逐字动画预设、通知歌词开关等。列表主题逐字动画预设只改变具有真实逐字时间轴的当前行渲染，默认保持音节抬升；`AppUiSettings`、`NotificationLyricsCoordinator` 与 `NotificationLyrics` 经此读写。
_Avoid_: 在通知或播放页组件内直接读 lyric key

**Equalizer preferences（均衡器偏好）**：
`EqualizerPreferences`：EQ 开关、预设索引、频段与全局增益。`MicaEqualizerManager`、`EqualizerScreen`、`EqCustomProfileStore` 与 `MicaMediaService` 经此读写；自定义预设命名仍由 `EqCustomProfileStore`（`mica_eq_profiles`）管理。
_Avoid_: 在音频链或 EQ UI 内直接读 equalizer_* key

**Album art repair coordinator（封面缓存修复协调器）**：
根据 `AlbumArtCache.health(...)`、上次扫描来源、SAF 目录可用性和设备音频权限，决定是否启动封面缓存修复以及从设备还是文件夹重扫。它只做修复计划；`MusicLibrary.launchArtworkCacheRepairIfNeeded` 调用 `plan(...)` 后，将可执行计划交给 `LibraryScanOrchestrator.launchArtworkCacheRepair(plan)` 执行（`forceRefreshArtwork=true`、不强制刷新歌词）。
_Avoid_: 在 `MusicLibrary` 或 `LibraryScanOrchestrator` 内继续堆封面缓存健康判断和修复来源选择

**MusicLibrary（曲库门面）**：
Compose 可见曲库状态与对外 API：`songs` / `songIds`、浏览查询、文件夹与权限、扫描触发入口。`MusicLibraryBacking` 随可见曲库快照维护 `songId → Song` 索引，`songById` 不得线性扫描曲库；大型歌单解析复用此索引。内部组合 backing 与子模块；**不**承载 `performScan`、排序发布或 `scannedSongs` 细节。封面修复在此做 plan + delegate；权限/扫描**决策**仍由 `LibraryAccessCoordinator` 负责。播放统计仅经 `applyPlayStats` 刷新展示，不拥有 `PlayHistoryStore` 写入。当前单实例架构允许 `ArtistNames` 持有进程级可变拆分规则；引入多个 `MusicLibrary` 实例前，必须将艺术家拆分规则及其 revision 改为同一实例所有，避免一个实例更新全局规则而其他实例继续使用旧缓存。
_Avoid_: 在 UI 直接调 `LibraryScanner` / `RoomLibraryStore`；在门面内恢复扫描编排或 catalog 逻辑；把播放统计持久化绑回 `MusicLibrary.ioScope`

**Library scan orchestrator（曲库扫描编排器）**：
`data/library/LibraryScanOrchestrator`：扫描生命周期、串行执行互斥、Room incremental sync、封面修复**执行**。歌词探测返回 `NotProbed` / `Complete` / `ReadFailed`：每个有界批次只把 `Complete` 结果用短 Room 事务直接替换到正式 `song_lyrics`，`ReadFailed` 不修改该歌曲任何歌词槽并持久化全局重试标记。批次可以在扫描结束前生效；歌曲摘要、删除、扫描元数据和 Compose 曲库仍仅在完整扫描成功后统一提交和发布。parser 升级或重试标记存在时，所有扫描类型（包括封面修复）都强制重新探测歌词；完整零失败扫描后才清除重试标记并推进 parser 版本。扫描失败不改变旧 snapshot，只设置 `lastScanError`。完整替换协议见 `Library snapshot publication` 与 `docs/adr/0002-library-snapshot-publication.md`。
_Avoid_: 把 `ReadFailed` 当成空歌词替换正式槽；让歌词批次直接发布 Compose 曲库；把全库歌词留在内存等结束后一次写入；用覆盖整次扫描的长 Room 事务；在 orchestrator 内做封面健康判断、权限 launcher、或 UI 侧扫描决策；失败路径把 `hasScanned` 置 true 或改写旧元数据

**Library snapshot publication（完整曲库快照发布）**：
能替换完整曲库真相的操作只有：cache hydrate、scan commit、clear library，以及 `release` 作废未完成发布。它们共用 `scanGeneration`（语义为 library generation）与 `storeRevision` + `storeSyncMutex`：需要写库时先 Room 成功，再同世代发布内存中的 `songs` 与 `hasScanned` / `lastScanAtMs` / `lastScanSource` / `totalSizeMb`。`applyPlayStats`、`removeSong`、排序 presentation、扫描中歌词 batch 不是完整替换。
_Avoid_: 先改内存扫描元数据再 `commitScan`；`clear` / `commitScan` 绕过 store mutex 与 revision；cache adopt 前不做 generation 校验

**Library catalog publisher（曲库目录发布器）**：
`data/library/LibraryCatalogPublisher`：私有 `scannedSongs`、排序、`songIds` / `catalogRevision` / `queueMetadataRevision` / fast scroll 发布、async persist、播放统计**展示**写回（`applyPlayStats`）、`removeSong`。外部只读曲库快照及其结构/元数据版本。播放次数权威持久化不在此，见 `PlaybackStatisticsRepository`。
_Avoid_: 在 orchestrator / UI 直接读写 `scannedSongs`；绕过 catalog 改可见列表或排序；在 catalog 内写 `PlayHistoryStore`

**Library browse details（曲库浏览详情模型）**：
专辑 / 艺术家详情页的展示模型与排序规则，例如专辑曲目排序、disc 分组、版权行、艺术家专辑分组。`HomeBrowseContent` 负责渲染和用户动作，不直接承载这些领域展示计算。
_Avoid_: 在 Composable 文件里继续散落专辑排序、disc section、artist album section 计算

**SongActions（歌曲操作流程）**：
主页、播放页等 UI 共享的歌曲动作流程。删除歌曲使用 `deleteSongEverywhere(...)` 统一串起物理文件删除、曲库移除、歌单移除和播放队列修正，并返回结构化结果给调用方展示提示。
_Avoid_: 在多个 Composable 内复制 `deleteSongFile -> removeFromLibrary -> removeFromAllPlaylists -> setQueue` 链路

## 播放队列与控制

**Playback queue（播放队列）**：
当前会话中待播与在播的 `Song` 有序列表，含 `currentIndex`；上一曲 / 下一曲、队列 Sheet、封面流邻槽均以此为准。
_Avoid_: playlist、播放列表（指歌单时）

**Library playback queue sync（曲库队列同步）**：
曲库可见列表变化时，将播放队列与曲库对齐的**唯一编排入口**：`MainViewModel.syncPlaybackQueueWithLibrarySongs` → `LibraryPlaybackQueueCoordinator`（执行）+ `LibraryQueueSyncPolicy`（决策：bootstrap / bootstrap-only / 整队替换 / 仅刷新元数据）。由 `MainActivity` 分别监听结构身份 `MusicLibrary.songIds` 与静态内容版本 `queueMetadataRevision`；后者排除播放次数、收听时长和最近播放时间，避免统计写回触发 MediaItem 刷新。同 ID 静态元数据通过 `replaceMediaItem` 增量写入服务，不重建权威队列。用户主动换队（点专辑、歌单、文件夹、「播放全部」）仍直接 `PlayerController.setQueue`，不经过此路径。App 内存队列写入服务仍走 `PlayerController` 内 `syncQueueToService`，与曲库同步分层。外部单曲/临时队列只有在 URI 权限可跨进程重启存续（MediaStore authority 或已持久化 grant）时才进入恢复快照；不可存续的临时队列不写 `ServicePlaybackStateStore`。
_Avoid_: 在 Composable / 扫描回调里对全库 `setQueue`、在 `init` 与 `LaunchedEffect` 各调一次 sync、用 `library.songs` 作 sync 触发键

**PlaybackQueueMode（播放模式）**：
队列推进策略：顺序（OFF）→ 列表循环（REPEAT_ALL）→ 单曲循环（REPEAT_ONE）→ 随机（SHUFFLE）。
_Avoid_: shuffle mode、repeat mode（拆开描述时仍用枚举名）

**PlaybackQueueNavigator**：
根据播放模式计算下一首 / 上一首索引的纯规则；UI 与 `PlayerController` 均不得自行重写跳转逻辑。
_Avoid_: 在业务层手写 `index + 1`

**PlayerController**：
App 侧播放**命令门面** + Compose 状态镜像：队列变更经 `MediaController` 写入服务；`songQueue` / `PlaybackQueueState` 由服务队列镜像填充，**不是**出声权威源。队列、时间轴与调音三块可变 UI 状态分别由 `PlaybackQueueCoordinator`、`PlaybackTimelineCoordinator`、`PlaybackTuningCoordinator` 收口；`PlaybackConnectionSession` 用连接 generation 拒绝旧连接回调，陈旧队列镜像结果在请求号、本地 revision 或当前连接变化时被丢弃。
_Avoid_: player view model、media player（指底层引擎时）

**SleepTimerController（睡眠定时器控制器）**：
`MicaApp` 持有的进程级播放策略；倒计时、淡出和到期暂停必须独立于 `MainViewModel` / Activity 生命周期。启动定时器时保存当前 App 播放增益，淡出只缩放该基线，到期或取消后恢复该基线；到期通知经短生命周期事件流交给当前 UI，不持有 Composable 或 ViewModel 回调。它不持久化倒计时，进程死亡后不恢复。
_Avoid_: 使用 `viewModelScope` 承载播放定时器；取消时固定写回 `1f`；让进程级对象持有 UI 回调

**Playback song resolver（播放曲目解析边界）**：
`PlayerController` 进程级只持有 `TransientPlaybackCatalog` 的解析器；曲库解析器只作为 `bootstrapQueue` 的一次性参数，用于恢复当前服务队列或首次镜像，调用返回后不得留在播放器、协调器或进程单例中。这样 Activity / `MainViewModel` 销毁时不会被播放控制器反向持有，也不会为了解析队列在进程级常驻整套曲库或歌词。
_Avoid_: 在 `PlayerController` 保存 `MusicLibrary` / `MainViewModel` 的 bound method；把全库快照复制到进程级 resolver；用 resolver 缺失接受不可信的 caller metadata

**PlaybackStatisticsTracker（播放统计跟踪器）**：
App 侧运行时统计状态机。用户明确点播/重播仍由 `PlayerController` 的请求加匹配的 seek/transition 证据确认；自然下一首和单曲循环的权威边界来自 Service 所持有的原始 `Player.onPositionDiscontinuity(AUTO_TRANSITION)`，经一次性 `MediaSession` custom command 送到 Controller，避免依赖 `MediaController` 对相同 `PositionInfo` / `MediaItem` 的差分合并。状态机只接受跨 mediaId 的自动边界，或同 mediaId 且位置从后向前回卷的自动边界；Controller 侧 AUTO/REPEAT 回调仅作状态同步，不能自行创建自动播放会话。每个确认边界最多创建一个待发布会话，且只在目标歌曲实际处于 playing 时发布一次；同曲 seek、暂停恢复、连接恢复和通知歌词 `replaceMediaItem` 元数据刷新均不得创建会话。连续收听时长仍按 session 向下取整发布整秒。它不写曲库、不持久化，也不参与出声或队列推进；发布结果由进程级 `PlaybackStatisticsRepository` 消费。
_Avoid_: 在 `PlayerController` callbacks 中重新实现去重、pending target 或收听 session 结算

**PlaybackStatisticsRepository（播放统计仓库）**：
`MicaApp` 持有的进程级播放统计持久化 owner。绑定 `PlayerController.onSongPlayStarted` / `onSongListenSecondsAdded`，在自有 IO scope 写入 `PlayHistoryStore`；可选 presentation sink（通常为当前 `MusicLibrary.applyPlayStats`）仅刷新 Compose 曲目展示。Activity/ViewModel 销毁不得取消其写入 scope；sink 缺失或已 `release` 的曲库不得阻塞持久化。冷启动或重新加载曲库时仍经 `withPlayStats()` 从 `PlayHistoryStore` 合并到 `Song`。
_Avoid_: 把统计持久化绑到 `MainViewModel` / `MusicLibrary.ioScope`；仅靠置空 Controller 回调“修泄漏”而丢掉后台播放统计

**Authoritative playback queue（权威播放队列）**：
服务侧 `MicaCompositePlayer.playlistItems`（经 `playbackQueueSnapshot()` 暴露）为唯一真相源；`ServicePlaybackEngineCoordinator` 的 `onEnded` / `startAt` / 失败跳曲均读此快照。App 内 `PendingPlaybackNavigation` 在 binder 延迟时携带切歌意图。
_Avoid_: 在 `PlayerController` 内维护与服务等长的并行队列并驱动出声

**ServicePlaybackStateStore（服务播放状态存储）**：
服务侧权威持久化：完整队列 `songId` 顺序、当前曲 ID、索引、进度毫秒、repeat/shuffle、`playWhenReady`、音质模式等 JSON 快照；由 `ServicePlaybackStateCoordinator` 刷盘。只持久化重启后可恢复的队列：临时外部歌曲必须具有可存续的 URI 权限（MediaStore authority 或已持久化 grant），否则整条临时队列不落盘。冷启动 `PlayerController.bootstrapQueue()` 的**主数据源**（service_wins），恢复后**不**自动开始播放。
_Avoid_: 在 App 侧另存一套与服务等长的队列并当作恢复真相源

**PlaybackSession（播放会话）** / **PlaybackSessionStore**：
App 侧轻量持久化：当前曲 ID、进度毫秒、shuffle 开关；`PlayerController` 运行中仍会写入。`bootstrapQueue` 仅从中读取 shuffle 等 App 偏好，**不再**承担完整队列恢复。`restoreSession()` 已废弃。
_Avoid_: 把 PlaybackSession 当作冷启动队列恢复的唯一来源、session token、playback state（指 UI 三态时）

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

**SpectrumAnalyzerStateOwner（频谱分析状态 owner）**：
媒体生命周期内将 `PlaybackUiPreferences.spectrumTapEnabled` 的派生资格应用到 `MicaSpectrumAnalyzer`：启动恢复不通知管线，三个资格偏好在运行时变化时沿既有 callback 重配 offload 并按需 flush；UI 只写偏好。
_Avoid_: 由 `AppUiSettings` 直接调用 `MicaSpectrumAnalyzer.setEnabled`

**Audio pipeline coordinator（音频管线协调器）**：
媒体生命周期内 EQ、频谱 tap、offload 偏好、offload 熔断与输出路由变化的状态转换 owner。所有外部 callback 先投递到 `MicaMediaService.mainHandler`，再由 `AudioPipelineCoordinator` 串行使旧熔断工作失效、推导有效 offload、写入 Exo 配置、持久化音质模式并按事件规则 flush；`AudioOffloadCircuitBreaker` 仍拥有其延迟任务 generation。确认 offload 失速后先回 PCM 并验证播放推进，再按 build fingerprint 记录失败并停用 offload；用户可在设置中手动重试，固件/策略更新会获得一次新尝试。`MicaMediaService` 只装配这些 adapter 与生命周期。
_Avoid_: 在 `MicaMediaService` 的各 callback 中分别重写 offload 推导、熔断失效或 flush 顺序；把 route monitor、熔断器或 EQ 实现吞进协调器

**Exo playback pipeline（Exo 播放管线）**：
唯一出声路径：`ExoPlayer` → `MicaExtractorsFactory` / `MicaRenderersFactory` → `libffmpegJNI.so`（ALAC、DSD 等扩展）→ `MicaAudioProcessorChain`（DSD 降采样 / 频谱 / EQ）→ `AudioTrack`。
_Avoid_: 软件播、双后端、libmica_ffmpeg

**USB-exclusive output（USB Host 真独占输出）**：
远期独立输出路径：App 通过 Android USB Host 持有目标 USB audio interface，负责权限、claim、格式协商、传输与释放，并绕过系统共享 `AudioTrack`。当前仅有输出模式兼容骨架，生产环境尚未实现；决策见 `docs/adr/0001-usb-host-exclusive-output.md`。
_Avoid_: 把 `AudioTrack.setPreferredDevice`、framework direct support 或现有 `UsbDirectPcm` 最小链称为 USB 独占

**Applied ReplayGain（实际 ReplayGain）**：
当前曲目最终传给播放器的 ReplayGain 线性系数；以实际受限后的系数为事实，`1f` 表示未修改信号。TRACK/ALBUM 设置本身不等于已衰减，缺少可用标签时仍为 `1f`。
_Avoid_: 仅从 ReplayGain 设置或标签推测实际增益

**Audio quality consent（音质改动许可）**：
任何可能降低播放保真度的实现（位深/采样率缩减、关闭 float 或 hi-res 直通、全格式共用劣化链路、有损转码、默认 EQ/限幅等）**必须先向用户明确说明影响范围与对象格式**，并**在得到明确允许之前不得实现或默认启用**。Agent 与贡献者均须遵守；细则见 `.cursor/rules/audio-quality-consent.mdc`。
_Avoid_: 为修单一格式（如 DSD）擅自改动全局 Sink 且不在文档与对话中事先披露对 FLAC 等的影响

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

**自定义下半屏布局（custom lower layout）**：
`CUSTOM_STANDARD` 专属的受约束布局：六个区域块（专辑封面、信息行、歌曲标题、紧凑歌词、进度条、播放控制）可长按拖拽排序、按 `50%..200%` 缩放、隐藏并统一调节间距；顶部与底部留白分别可调。封面虽然加入统一纵向布局，仍保留点击、长按与横向滑动切歌手势。该主题可单独开启“点击封面暂停/播放”，默认关闭。`PlayerLowerLayoutConfig` 经 `PlaybackUiPreferences` 持久化；旧五组件顺序读取时在顶部补入封面；配置总高度超过屏幕安全区域时统一按比例收敛并裁边兜底。它不是任意 XY 画布，不允许组件绕过安全布局自行偏移。
_Avoid_: layout editor（泛指设置页时）、自由画布、custom theme（未指封面行为时）

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
`CUSTOM_STANDARD` 不进入该布局变形，而是复用粒子封面进入歌词云的横向整页滑动；播放页保持正常态，歌词目标页预先挂载。
_Avoid_: lyrics mode（指布尔 `lyricsExpanded` 时）、歌词页（指动画结束后的稳定态时）

**Lyrics page（歌词页）**：
`lyricsExpanded == true` 时的稳定 UI：全屏歌词列表、专用进度与控制布局。
_Avoid_: expanded lyrics（指组件名时可用「展开歌词」）

**Compact lyrics（紧凑歌词）**：
普通播放页下半屏中的少量歌词行（通常三行），点击可进入歌词页。标准 / 粒子封面 / 平行封面带 / 复古立体封面可通过 `CompactLyricsLineMode`（`PlaybackUiPreferences`，默认自动）固定为一行、三行，或沿用布局引擎按高度自适应；自定义标准仍用布局编辑里的行数。
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

**PlayerCoverFlowMode（播放页封面行为）**：
封面区交互形态，设置 → 播放页封面行为：
`STANDARD`（标准大封面，横向轻扫切歌）；
`CUSTOM_STANDARD`（以标准大封面为基准，下半屏使用 `PlayerLowerLayoutConfig`，禁止下半屏沉浸，歌词页统一横向整页滑动）；
`PARTICLE_COVER`（粒子封面，封面边缘粒子化与切歌分解动画；现网 **GLES** `TextureView` + `ParticleCoverRenderer`，`UseNativeParticleCoverInPlayer = true`；WebView 回退见 `ThreeParticleCoverHost`；产品说明见 `docs/PARTICLE_COVER_OPENGL_MIGRATION.md` §0）；
`PAUSE_FOLD`（平行封面带）；
`RETRO_3D`（复古立体封面流）；
`PHOTO_STACK`（拍立得回忆，拍立得叠放转场）。
`PARTICLE_COVER`、`PAUSE_FOLD`、`RETRO_3D`、`PHOTO_STACK` 强制裁切填充、忽略「原样比例」；`CUSTOM_STANDARD` 保留标准封面的显示策略。`CUSTOM_STANDARD`、`PARTICLE_COVER` 与 `PHOTO_STACK` 不支持下半屏沉浸。
_Avoid_: carousel（无专名时）、cover flow（小写泛指全部非标准模式时）

**Cover flow（封面流）**：
`PAUSE_FOLD` / `RETRO_3D` 下的邻曲封面带与手势切歌；七轨 `CoverFlowRails` + `CoverFlowCarouselView` View 岛实现。不含粒子封面或拍立得主题。
_Avoid_: 3D cover（未指 RETRO_3D 时）、把粒子/拍立得称作 cover flow

**CoverDisplayMode（封面显示模式）**：
列表 / 迷你栏 / 播放页封面的缩放策略：`CROP_FILL` 或 `FIT_ORIGINAL`（原样比例）。
_Avoid_: aspect mode、fit mode

**Cover edge progress（封面底边进度）**：
进度条叠在封面底边，而非标准进度区；与「标准进度区」互斥显示（交叉淡化）。
_Avoid_: edge seek bar、overlay progress

**Video album cover（视频专辑封面）**：
默认关闭，仅 `PlayerCoverFlowMode.STANDARD` 的全屏播放页生效。文件夹扫描只索引歌曲同目录 `.mp4` 的名称与 URI：文件名精确等于专辑名优先，否则以 NFKC、空白折叠和 `Locale.ROOT` 大小写归一化匹配；不做标点/后缀模糊匹配，空/未知专辑或多候选歧义回退静态封面。独立短生命周期 Media3 播放器禁用音轨，随音乐暂停，后台、锁屏、离页或切歌释放；视频沿用静态封面矩形居中裁切，首帧前及错误时显示静态图或已缓存海报。索引构建为 O(歌曲数 + MP4 数)，文件夹遍历本身不打开视频；选项开启时在曲库发布后对去重后的匹配 URI 单线程后台抽首帧写入海报缓存（命中跳过、新扫描取消旧任务），不阻塞扫库进度。
_Avoid_: 复用主音频播放器、请求视频权限做设备全盘扫描、为匹配同步解析视频、对未匹配 MP4 解码、模糊匹配不同文件夹或同名歧义专辑

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
界面偏好的 Compose 即时镜像：主题与外观经 `AppearancePreferences`、歌词经 `LyricsPreferences`、播放页 UI 经 `PlaybackUiPreferences` 持久化。播放页只读、经 `NowPlayingActions` 或设置页写入。
_Avoid_: 在 Composable 内直接读 `SharedPreferences` 或绕过门面写 key

**PlayerLowerBackgroundMode（播放页背景）**：
播放页下半屏（及必要时全屏）背景样式，设置 → 播放页背景：
`THEME`（主题色）、`ARTWORK_GRADIENT`（封面渐变）、`COVER_GLOW`（封面模糊）、`DYNAMIC_LIGHT`（动态烟云，低分辨率封面纹理 + GLES 渲染）、`DYNAMIC_ARTWORK`（流光溢彩，多层封面纹理 + shader 切歌 crossfade）。
与 `PlayerCoverFlowMode` **并列、可任意组合**。
_Avoid_: player theme、background preset、把播放页背景与封面行为混为一项设置

**Shared cover transition（共享封面转场）**：
迷你播放栏与播放页之间同一封面矩形的连续转场（共享元素动画）。
_Avoid_: hero animation（无专名时）

## UI 播放适配层

**Playback UI adapter（播放 UI 适配层）**：
`MainActivity` / `AppNavigation` 中把 `PlayerController`、`AppUiSettings` 等 App 侧对象翻译成页面需要的 state/actions 的装配代码；它可以持有 `PlayerController`，但不承载播放业务规则。
_Avoid_: 把适配层拆成只有一个实现的 interface

**HomePlaybackState**：
主页、曲库浏览、搜索、歌单列表只读的播放摘要：当前 `Song`、播放中状态与当前播放队列。列表 UI 只用它判断高亮、迷你播放栏显示和删除后队列修正。
_Avoid_: 在列表组件里直接读取 `PlayerController.currentSong` / `isPlaying`

**HomePlaybackActions**：
主页输出到播放门面的最小动作集合：同步播放状态、插播下一首、替换播放队列、播放/暂停、下一首。主页子组件通过显式 action 触发播放，不直接接收 `PlayerController`。
_Avoid_: 在 `HomeScreen`、`SongListPanel`、`LibrarySearchPanel`、`HomeBrowseContent` 中散落 `playerController.xxx()`

播放页 UI 以 `PlaybackSurfaceState`、`PlaybackProgressState`、`PlaybackQueueState` 和 `NowPlayingActions` 为接口；主页 UI 以 `HomePlaybackState` 和 `HomePlaybackActions` 为接口。`PlayerSheetHost`、`NowPlayingScreen`、`HomeScreen`、歌曲列表和搜索面板不得直接接收 `PlayerController`；`AppNavigation` 和 `MainActivity` 作为装配层例外。
