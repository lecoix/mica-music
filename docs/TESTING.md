# Mica 测试指南

## Windows 终端编码

Windows PowerShell 5.1 若看到中文乱码，先在当前会话启用 UTF-8：

```powershell
. .\scripts\use-utf8-console.ps1
```

## 单命令质量门

```powershell
.\gradlew :app:micaCheck --no-configuration-cache
```

该任务包含 Debug 编译、Lint、JVM/Robolectric 测试和 Roborazzi 截图比对，不需要模拟器、真机、网络或真实媒体库。

完整截图矩阵：

```powershell
.\gradlew :app:micaScreenshotFull --no-configuration-cache
```

夜间强化测试（包含 10,000 次固定种子解析模糊测试）：

```powershell
.\gradlew :app:micaNightlyCheck --no-configuration-cache
```

更新截图前先人工检查差异，再运行：

```powershell
.\gradlew :app:micaRecordScreenshotFull --no-configuration-cache
```

基线位于 `app/src/test/snapshots`。测试固定 API、尺寸、字体缩放和静态背景，禁止把动态动画或网络图片带入基线。

## 测试分层

### Embedded lyrics contracts

Run the focused embedded-lyrics suite before changing scanner metadata readers:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.mica.music.data.scanner.LyricsEncodingContractTest" --tests "com.mica.music.data.scanner.EmbeddedLyricsResolverTest" --tests "com.mica.music.data.scanner.EmbeddedLyricsContainerContractTest" --tests "com.mica.music.data.scanner.EmbeddedSyltLyricsTest" --tests "com.mica.music.data.scanner.BinaryParserGoldenTest" --tests "com.mica.music.data.scanner.BinaryParserFuzzTest" --no-configuration-cache
```

Container ownership: MP3/AAC/WAV/AIFF use ID3; FLAC uses Vorbis Comment (with an ID3 compatibility path); APE uses APEv2 (with an ID3 compatibility path); M4A/MP4/ALAC use MP4 atoms; OGG/Opus/WMA are TagLib-only; DSF supplies its already-read ID3 document; DFF/DSDIFF remain best-effort and must not crash. `EmbeddedLyricsNativeContractTest` is the device contract for real TagLib plus DSF metadata pointers.

Capacity boundary: scan-time text candidates are capped at 1,000,000 characters each, TagLib success bypasses retriever/binary fallbacks, and the scanner does not retain raw embedded bytes after each song. The 10,000-song resolver test proves that a successful TagLib path invokes zero binary fallbacks. This is a code-level memory bound, not a measured 8 GB device peak; keep device profiling as release acceptance.

涉及框架回调、Service/IPC、系统组件或外部文件提供者时，先阅读 [`EXTERNAL_EVENT_CONTRACT_TESTING.md`](EXTERNAL_EVENT_CONTRACT_TESTING.md)。内部状态机的高覆盖率不能证明生产事件会无损到达。

**开发规则：所有 Player 写操作都必须审计观察者副作用。** 包括仅修改元数据的 `replaceMediaItem`；必须检查 timeline、transition、discontinuity、状态回调及其对统计、队列镜像、持久化、通知和 UI 的影响。依赖真实 Media3 派发行为时补真实组件契约测试。

- 纯 JVM：队列策略、播放时钟、音频算法、歌词和二进制解析。
- Robolectric：Room、迁移、偏好损坏恢复、Activity 多 API 创建。
- Roborazzi：主界面边缘覆盖和关键尺寸布局。
- 设备验收：真实解码、AudioTrack、蓝牙、音频焦点、系统通知及 SAF。

测试样本只能使用自行生成的最小合法片段、损坏片段或固定种子随机字节，不提交完整歌曲。

## 覆盖目标

- 纯算法和解析器：行覆盖至少 90%。
- 播放状态机、队列和数据库：分支覆盖至少 80%。
- Compose UI 不用覆盖率数字代替场景审查，以截图矩阵完整性为准。

这些是新增和修改风险模块的合并要求。遗留模块在触及时补齐，不能通过降低阈值掩盖缺口。

## 发布前设备验收

当前真机协同与设备矩阵维护成本较高，设备验收暂时保留为人工清单，不纳入自动化门禁改造项。

- Android 8/8.1 与 Android 14+ 各至少一台真机。
- MP3、FLAC、ALAC、DSF 实际播放；DFF 播放应被拒绝并提示；长曲 seek 与连续切歌。
- 蓝牙、耳机拔出、音频焦点、锁屏控制和后台播放。
- 通知权限拒绝、划掉任务、进程恢复和重启。
- SAF 文件夹授权及重启后的持久权限。
- 文件管理器 `ACTION_VIEW` 打开 `content://` 音频；验证冷启动、warm `onNewIntent()`、曲库复用和临时单曲队列。
- 曲库层级浏览与扁平浏览、删除、分享、睡眠定时和 EQ。
- 手势导航、三键导航、横屏、浅色和深色模式；歌词页的 Letter/信笺主题与 Hi‑Res 标志样式。
- 手势提示线周围必须由应用背景完整覆盖。

设备验收失败时，即使 JVM 测试全部通过也不得发布。

## Exo 单链路播放验收

每次播放测试都应导出诊断日志，并确认 `route`、`request`、`source` 与 `AudioQuality` 字段符合预期。

- 所有可播格式均走 Exo；日志不得出现 `backend=SOFTWARE`、`libmica_ffmpeg` 或 `decode-input-copy`。
- ALAC 必须由 `FfmpegAudioRenderer` / `libffmpegJNI` 解码，不得回退平台 ALAC 解码器。
- DSF 必须显示 Exo 路由；蓝牙实际输出候选不得高于 48kHz（DSD 降采样策略）。
- DFF 必须在播放前拒绝，UI 显示「不支持 DFF/DSDIFF 格式，请使用 DSF」。
- 频谱在稳定播放且设置开启时，Exo 链上 `SpectrumAudioProcessor` 应有 PCM tap（与软件播无关）。
- HIFI 必须显示 `dsp=false offload=true`；DSP 必须显示 `dsp=true offload=false`。
- 耳机/蓝牙断开必须暂停，禁止切到扬声器继续播放。
- 划掉 Activity 后播放应继续并可从通知控制。
- 暂停后划掉任务并重新打开应用，MediaController 必须重新连接 Service。
- Service 重启后必须按歌曲 ID 恢复队列中的当前曲和位置；恢复状态必须为暂停，即使持久化时 `playWhenReady=true`。
- repeat/shuffle 必须从 MediaSession 恢复并反映到 UI，Activity/ViewModel 不得用默认模式覆盖。
- 旧 request 的 prepared、position、playing、ended 和 error 回调不得改变当前 Service request。
- 插入、移动、删除队列项后，通知 timeline、当前索引和自然下一首顺序必须一致。

自动回归已覆盖 Controller 断连重连，以及 limiter 单调性和 `-1 dBFS` 峰值门槛。

性能门槛：

- 常见格式热切歌 p95 ≤ 250ms，冷切歌 p95 ≤ 500ms。
- ALAC / DSF Exo 起播 p95 ≤ 500ms。
- Exo seek p95 ≤ 350ms。

## 播放 UI / 队列重构回归清单

本清单用于验证播放页、主页、曲库列表和队列同步收紧后的行为。它不替代 `micaCheck`，必须在至少一台真机上手测。

结构收口后的最小自动化检查：

```powershell
.\gradlew :app:testDebugUnitTest --tests com.mica.music.LibraryQueueSyncPolicyTest --tests com.mica.music.data.LibraryPlaybackQueueCoordinatorTest --tests com.mica.music.util.SongActionsTest --tests com.mica.music.data.LibraryBrowseDetailsTest --tests com.mica.music.data.AlbumArtRepairCoordinatorTest --tests com.mica.music.data.MusicLibraryTest --tests com.mica.music.data.library.LibraryScanOrchestratorTest --tests com.mica.music.data.preferences --tests com.mica.music.data.scanner.ProbeResultTest --tests com.mica.music.ui.navigation.AppNavigationCoordinatorTest --no-configuration-cache
.\gradlew :app:compileDebugKotlin --no-configuration-cache
```

### a257a0f 架构重构：P0 JVM 单测（队列 sync 语义）

提交 `a257a0f`（MusicLibrary / AppPreferences / Home·Settings 拆分、统一 `songIds` 队列 sync）后，下列行为已由单测写死。**产品语义（P0.1）**：曲库仅排序变化时 **不重排** 当前播放队列，只 `RefreshMetadata`；仅当播放队列仍含已从曲库移除的 id 时才 `SetQueue`。

| 场景 | 测试类 | 测试方法 |
|------|--------|----------|
| 曲库排序 `[a,b,c]`→`[c,b,a]`，队列仍为 `[a,b,c]` | `LibraryQueueSyncPolicyTest` | `librarySortReorderDoesNotReplacePlayerQueue` |
| 同上（coordinator 执行层） | `LibraryPlaybackQueueCoordinatorTest` | `librarySortReorderOnlyRefreshesMetadata` |
| 删当前曲后队列已更新，`songIds` sync 不二次 `setQueue` | `LibraryQueueSyncPolicyTest` | `librarySongRemovedAfterDeleteRefreshesWithoutReplacingQueue` |
| 删中间曲后同上 | `LibraryQueueSyncPolicyTest` | `librarySongRemovedFromMiddleOfQueueRefreshesWithoutReplacingQueue` |
| 删曲后 coordinator 仅 refresh、队列顺序不变 | `LibraryPlaybackQueueCoordinatorTest` | `deleteSongLibrarySyncRefreshesWithoutSecondSetQueue` |
| 冷启动空队列 + `bootstrapQueue` 成功 → 不 `setQueue` | `LibraryPlaybackQueueCoordinatorTest` | `coldStartBootstrapSuccessDoesNotReplaceRestoredServiceQueue` |
| 冷启动空队列 + bootstrap 失败 → `setQueue` 整库 | `LibraryPlaybackQueueCoordinatorTest` | `bootstrapFailureSetsLibraryQueue` |
| 队列仍含已移除曲库 id → `SetQueue` | `LibraryQueueSyncPolicyTest` / `LibraryPlaybackQueueCoordinatorTest` | `queueContainingRemovedLibrarySongIsRebuiltFromCurrentLibrary` / `removedLibrarySongTriggersSetQueue` |
| 删当前播放曲 | `SongActionsTest` | `deleteSongEverywhereRemovesCurrentPlayingSongFromQueue` |
| 删非当前曲 | `SongActionsTest` | `deleteSongEverywhereRemovesNonCurrentSongFromQueue` |
| 删文件失败仍移库、移歌单、修正队列 | `SongActionsTest` | `deleteSongEverywhereKeepsRemovalFlowWhenFileDeleteFails` |

仅跑 P0 队列/删除回归：

```powershell
.\gradlew :app:testDebugUnitTest --tests com.mica.music.LibraryQueueSyncPolicyTest --tests com.mica.music.data.LibraryPlaybackQueueCoordinatorTest --tests com.mica.music.util.SongActionsTest --no-configuration-cache
```

### 播放统计状态机覆盖矩阵

播放次数测试按四层维护，不能用 `MediaController` 的单个 mocked reason 代替 Service 原始 Player 边界：

1. **纯状态机契约（JVM）**：显式播放等待新的 transition/seek 证据，无关 batch 不消耗请求，新请求替换尚未发布的旧会话；Service 确认的跨 mediaId 自动边界和同 mediaId 位置回卷各计一次，连续三个确认边界每轮各计一次；Controller 的 AUTO/REPEAT 组合、重复 callback、歌词元数据伪 REPEAT、同曲 seek、暂停恢复、无位置回卷和 mediaId 不匹配均为 0。
2. **Service 边界与 Session 事件（Robolectric）**：`ServicePlaybackEngineCoordinatorTest` 验证原始 Player 的 AUTO discontinuity 原样产生自然切歌/单曲循环边界，而 SEEK 不产生边界；`PlaybackBoundarySessionEventTest` 验证 custom command 参数无损往返且忽略无关 command。
3. **PlayerController 接线（Robolectric）**：覆盖自动下一首、连续单曲循环、playing 延迟到达和显式同曲 seek 重播；确认旧 Controller callback 不计数、不跨 batch 配对，也不与 Service 边界重复计数；从结尾手动 seek 到开头、暂停恢复、歌词元数据替换和连接恢复均为 0。
4. **真实 Media3 契约（设备/模拟器）**：`NotificationLyricsMedia3ContractTest` 使用运行时生成的 4 秒静音 WAV、真实 ExoPlayer/MediaSession/MediaController、repeat-one 和 `NotificationLyricsCoordinator`，覆盖歌词 `replaceMediaItem` 后三次真实连续循环各计一次、暂停恢复与手动 seek 为 0、自然下一首与显式重播各计一次，以及播放中连接第二个 Controller 为 0。该测试需要已连接的 arm64 设备，不属于纯 JVM 门禁。

仅运行真实 Media3 契约测试：

```powershell
.\gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.mica.music.media.NotificationLyricsMedia3ContractTest" --no-configuration-cache
```

以后新增或修改 `replaceMediaItem`、`setMediaItems`、队列增删移动、`seekTo`、repeat 或 shuffle 写操作时，必须检查其 timeline/transition/discontinuity 回调是否会影响播放次数、收听时长、当前曲、队列索引或进度；无法由前两层证明时补真实 Player 契约测试。

### P0 外部组件契约

下列测试使用真实 Android/Media3 组件，不以 mocked callback 代替生产输入：

- `ServicePlaybackRestorationContractTest`：真实 `MicaMediaService` + MediaSession/MediaController 保存并重建；重新注入队列后恢复当前曲、进度和 repeat，且不自动播放。它验证 Service 重建，不代替进程死亡/OEM 后台限制验收。
- `SafFolderContractTest`：debug-only DocumentsProvider 发放持久读写 URI 权限，经 `DocumentFile` 与真实 provider cursor 递归扫描合成 WAV，最后释放授权。
- `RealAudioDecodeContractTest`：运行时生成最短合法 DSF，并使用 1 秒生成静音 ALAC；两者均须由生产 `ExoPlaybackStackFactory` 初始化 FFmpeg decoder、到达 AudioTrack，DSF 另验证 seek。

仅运行这组三项：

```powershell
.\gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.mica.music.media.ServicePlaybackRestorationContractTest,com.mica.music.data.SafFolderContractTest,com.mica.music.media.RealAudioDecodeContractTest" --no-configuration-cache
```

### P1 状态写入、迁移与系统事件契约

- `NotificationLyricsMedia3ContractTest.realQueueWritesKeepPlayerAndMirrorAlignedWithoutFalsePlayCounts`：真实 ExoPlayer/MediaSession/MediaController/PlayerController 连续执行三次 metadata replacement、preserve-current 重排、移动、删除当前/非当前项及删空；同时断言 Player 队列、UI 镜像、当前曲、进度和播放计数回调。
- `RoomMigrationContractTest`：使用 `MigrationTestHelper` 和导出的 Room schema 验证历史迁移契约；当前数据库为 v17，`DatabaseMigrationTest` 另覆盖 `14→15`（重建 album browse group 纳入专辑艺术家）、`15→16`（`songs.embeddedLyricsProbeRevision`）与 `16→17`（`playlists` / `playlist_songs`），迁移后通过真实 `SongDao`/`LibraryMetaDao` 读回代表数据，`16→17` 建表另有独立契约测试。
- `SongIdentityMigrationTest`：验证旧 URI hash ID 到稳定文档 ID 的一次性迁移，以及播放列表、播放历史、播放会话和曲库浏览偏好的引用同步。
- `MicaMediaServiceNoisyReceiverTest`：Robolectric 验证 Service 只保留 Media3 内置 noisy receiver，一次广播只暂停一次，销毁时完成注销。设备端普通 App/shell UID 不能伪造受保护的 `AUDIO_BECOMING_NOISY`；OEM 音频焦点、真实耳机拔出和连续系统事件仍属于设备矩阵。

Room schema 由 `app/schemas` 打包进 androidTest assets；不得用手工极简表代替 `runMigrationsAndValidate`。

### USB 输出 operation 与旧回调隔离

- `MicaMediaServiceUsbOperationTest`：现主要验证 `UsbOutputCoordinator` 的 operation/phase/generation 交错、拔出后快速重插、无关 attach/detach、销毁后旧任务失效，并有结构断言禁止 `MicaMediaService` 重新持有 `usbEffects`、`usbOwner`、facts job、AudioDeviceCallback 或 telemetry sampler；Service 字段必须以 `UsbOutputCoordinator` interface 暴露，而不是实现类。
- `AndroidUsbPermissionLifetimeTest`：验证权限广播 action 按 adapter 实例隔离、attach 必须发布 `runtimeHandle` 与音频相关性，并验证相同数值的 `UsbOutputOperationId` 与 `UsbRequestEpoch` 处在独立 pending/broadcast domain，任何一方回调都不能消费另一方请求。
- 正确性来自 `mode generation` / `UsbOutputOperationId` / `UsbRequestEpoch` 三域隔离，以及 operation ID / phase / generation 的重新校验；移除 Handler 回调和清空 pending map 仅是资源清理，不能代替有效性协议。

软件测试不能证明扩展坞供电、总线带宽、OEM USB 路由、权限弹窗或持续出声。涉及这些结论时仍需真实 DAC、扩展坞与目标手机验收。

### 2026-08-07 播放状态、歌单与外部事件回归

最近一批状态所有权与持久化边界改动，除既有的 `micaCheck` 全量门禁外，重点回归套件：

- `PlaybackTimelineCoordinatorTest` / `PlaybackTuningCoordinatorTest` / `PlayerControllerQueueModelTest`：时间轴、调音与队列 UI 状态的单一 owner 语义；`PlayerControllerQueueModelTest.playerControllerFacadeDoesNotOwnRuntimeInternals` 另冻结 `PlayerController → PlaybackRuntime` 的 facade/runtime ownership 边界；`PlaybackQueueMirrorTest` / `MediaControllerQueueSyncTest` / `PlayerControllerBoundaryTest` 覆盖陈旧队列镜像与旧连接回调被拒绝。
- `AudioPipelineCoordinatorTest` / `AudioOffloadCircuitBreakerTest` / `AudioOffloadPreferencesRobolectricTest`：EQ / 频谱 / offload 偏好变化使旧熔断任务失效；确认失速后回 PCM、按 build fingerprint 记录失败、手动重试与系统更新后重试。
- `ExternalAudioOpenTest` / `TransientPlaybackCatalogTest` / `ServicePlaybackStateCoordinatorTest`：只有 MediaStore authority 或已持久化 grant 的外部 URI 才进入恢复快照，不可存续临时队列不落盘。
- `PlaylistStoreTest` / `RoomMigrationContractTest` / `DatabaseMigrationTest`：歌单写库成功后才更新内存；`mica_playlists` JSON 一次性迁移与 `16→17` 建表契约。
- `LyricDisplayRowsTest` / `LyricsCloudLayoutTest` / `LyricsTimelineEngineTest`：入库不把显示分隔符改写成 `TRANSLATION`，歌词云逐字进度按 token 角色与行结束时间计算。

### a257a0f 架构重构：P1 真机验收清单

P0 单测无法覆盖 Compose 生命周期、Room 冷启动时序、SAF/权限与 Service 持久化。下列清单在 **至少一台真机** 上手测；失败时导出诊断日志（`LibraryQueue`、`LibraryStartup`、`LibraryScan`、`Player`、`PlaybackRestore`）。

#### 1. 冷启动与队列恢复

- [ ] **有 Service 持久化队列**：上次播放中/暂停有队列与进度 → 杀进程 → 冷启动 → 迷你栏/播放页/通知为同一首；**播放队列顺序不被整库列表排序覆盖**；恢复后处于暂停（不自动续播，除非产品另有约定）。
- [ ] **无 Service 持久化、Room 有缓存**：清数据后只扫过库、从未形成 service 队列 → 冷启动 → 列表快速出现；若播放器队列为空，应装入曲库（或 bootstrap 行为与旧版一致）。

## 本地音乐 MV

- JVM：`MusicVideoMatcherTest`（精确/归一化/目录/歧义/10k+10k）、Room 20→21、Entity/Summary/MediaItem codec。
- 交错：开关旧 generation 不得覆盖最新值；旧 Surface detach/首帧/Controller 事件不得影响当前 lease；播放栈重建只重绑当前 controller+mediaId。
- 错误：坏 MP4 首次只重建当前项为纯音频并保留位置/意图/模式；相同 revision 不二次尝试，纯音频错误走原路径。
- 真机待验：MP3、FLAC、ALAC/DSF Shared PCM；蓝牙；USB Exact PCM、DoP、Native DSD；暂停、seek、0.5×–2×、循环、短/长视频、后台恢复和 30 分钟漂移。构建/JVM 结果不得替代音画和音质结论。
- [ ] **Room 空库**：首次安装或空库 → 授权扫描 → 扫描完成后列表与队列行为正常。
- [ ] **从通知/桌面图标二次进入**：划掉 Activity 后从通知或桌面再开，`MediaController` 重连，UI 队列/当前曲不回退默认。
- [ ] **诊断**：日志中 `LibraryStartup loadCached` 与 `LibraryQueue libraryIds` 各出现一次为主；不应短时间连续多次 `setQueue` 刷屏。

#### 2. 曲库排序 vs 播放队列（P0.1 选项 A）

- [ ] 当前队列为「播放全部」或等于上次整库顺序（如 `[A,B,C]`）→ 在设置/主页改 **全部歌曲排序**（如标题升序↔降序）→ **正在播放的切歌顺序不变**（仍为 A→B→C），仅列表顺序变；元数据（播放次数等）仍刷新。
- [ ] 改排序后切歌、seek、迷你栏与通知仍正常。

#### 3. 删除歌曲

- [ ] **删当前播放曲**：播放 A，队列 `[A,B,C]` → 删除 A → 立即切到 B（或暂停，与现网一致）；曲库、歌单、队列、迷你栏均无 A；**不应**再闪一次整库重排队列。
- [ ] **删非当前曲**：播放 A，删 B → 当前仍播 A，队列变为 `[A,C]`。
- [ ] **删文件失败**：SAF/无写权限等导致文件删不掉 → Snackbar「已从曲库移除（无法删除文件）」；歌单与队列仍移除该曲。
- [ ] **删后 sync**：导出日志，删曲后 `LibraryQueue libraryIds` 为 `RefreshMetadata` 路径为主，非连续两次 `setQueue`。

#### 4. 权限与扫描（Home + Settings）

- [ ] **Home 首次授权**：无权限 → 授权 → 自动扫描 → 列表与进度文案正常。
- [ ] **Settings 重扫无权限**：撤销权限 → 设置页点重扫 → 有明确错误/引导，不崩溃。
- [ ] **SAF 选文件夹**：选目录 → 扫描 → 杀进程再开 → 目录与曲库仍在。
- [ ] **取消选文件夹**：清除 SAF 目录后行为符合预期（空库或回退设备扫描，与现网一致）。
- [ ] **扫描中连点重扫**：最终以最后一次扫描为准。

#### 5. 设置迁移（升级用户）

- [ ] 在已有 `mica_settings` 的设备上 **覆盖安装**（不清数据）：主题、强调色、云母、迷你栏样式、播放页背景/封面行为、歌词/通知歌词、EQ 开关与预设、扫描选项（最短时长、深度探测、排除目录）均保留。
- [ ] 浏览排序（歌曲/专辑/艺术家）杀进程后仍保持。

#### 6. 扫描诊断（ProbeResult 线 A）

- [ ] 深度扫描完成后，诊断日志 `LibraryScan performScan scannerResult` 含 `technicalFailed=N`（通常 N=0；有失败时应有 `AudioTechnicalProbe: probe-failed` 逐首记录，但歌曲仍入库）。

### MusicLibrary 结构拆分回归（真机）

`MusicLibrary` 拆分为门面 + `LibraryScanOrchestrator` / `LibraryCatalogPublisher` 后，generation 取消与 catalog 边界已有 JVM 单测；下列场景仍需真机或 SAF 手测（行为未改，但涉及 Room、MediaStore、SAF 与 Compose 状态）：

- **冷启动缓存恢复**：已有曲库时杀进程再开，列表应快速出现且不必全量重扫；`songIds` 与列表一致。
- **设备扫描**：授权后首次/手动重扫，进度文案、完成后的歌曲数与封面/歌词正常。
- **SAF 文件夹扫描**：选文件夹 → 扫描 → 杀进程再开，目录权限与曲库仍在；上次来源为文件夹时重扫走文件夹路径。
- **扫描中再次触发重扫**：设置页或主页连点重扫，最终列表以最后一次为准（不出现旧结果覆盖新结果）。
- **排序切换**：标题/艺术家/播放次数等排序后列表顺序与 fast scroll 索引正确；杀进程再开排序偏好仍生效。
- **播放统计**：播放一首后，按「播放次数」「最近播放」排序时该曲位置更新；列表内元数据（次数）刷新。明确点歌、手动切歌、自动下一曲和真实单曲循环各新增一次；同曲 seek、暂停恢复、通知栏逐行歌词更新、队列元数据刷新和连接恢复均不新增。开启通知栏歌词并使用单曲循环完整播放一轮，播放次数应只在真实循环边界增加一次，不能随歌词逐行增长。
- **从曲库移除**：菜单移除歌曲后列表消失；若该曲在播放队列中，队列同步后也应移除（见下方队列清单）。
- **撤销权限清空曲库**：无 SAF 文件夹时撤销音频读取权限，曲库应被清空。
- **封面缓存修复**：故意让缓存封面缺失或损坏后冷启动，日志应有 `AlbumArtCache repair-check` / `repair-start`，且修复扫描仅刷新封面（不强制重拉歌词）；列表封面恢复。

- 冷启动后等待曲库缓存加载完成，确认迷你播放栏、播放页和通知栏显示同一首歌。
- 从全部歌曲列表点击播放，确认播放队列等于当前列表，当前歌曲高亮正确。
- 从搜索结果点击播放，确认播放队列只包含当前搜索结果，返回主页后播放不中断。
- 从艺术家、专辑、最近播放和文件夹页点击播放，确认队列范围分别为当前浏览结果。
- 进入歌单详情点击播放，确认队列等于该歌单当前排序；自定义排序歌单拖动后，列表顺序和再次点击播放的队列一致。
- 长按歌曲执行「插播下一首」，确认当前歌曲不被打断，下一次自然切歌进入插播歌曲。
- 删除当前队列中的歌曲后，确认曲库、歌单、播放队列和迷你播放栏不会保留已删除歌曲。
- 删除文件失败但曲库移除成功时，确认播放队列和所有歌单仍移除该歌曲，Snackbar 文案表达“已从曲库移除但无法删除文件”。
- 展开播放页，测试播放/暂停、上一首、下一首、seek、歌词行内 seek、队列 Sheet 跳转、移动和删除。
- 切换 repeat/shuffle，确认播放页图标、通知控制和自然下一首顺序一致。
- 后台、锁屏、蓝牙控制、划掉 Activity 后重新打开，确认 `MediaController` 重连后 UI 状态不回退到默认队列或默认播放模式。
- 开启通知歌词，播放带逐字/逐行歌词的歌曲，确认通知歌词随进度更新；关闭后确认通知恢复普通元数据。
- 曲库扫描设置修改后重新扫描，确认最短时长、纳入非音乐音频、深度元数据探测和排除目录仍生效；相关偏好应经 `LibraryScanSettings` 进入 `ScanOptions`。
- 若封面重启后丢失或显示纯色块，导出诊断日志检查 `AlbumArtCache` 的 `repair-check` / `repair-start`，以及是否从正确来源执行 `forceRefreshArtwork=true` 修复扫描。
- 若 FLAC/WAV/ALAC 位深或容器显示异常，导出诊断日志检查 `AudioTechnicalProbe: probe-failed`；该日志表示增强技术字段探测失败，但不应导致整首歌扫描失败。
- 导出诊断日志，重点检查 `QueueSync`、`Player`、`PlaybackRestore`、`NotificationLyrics`、`AlbumArtCache`、`AudioTechnicalProbe` 中没有明显异常或重复刷屏。

### AppPreferences 分域回归（真机）

偏好已拆为 `data/preferences/` 下各域门面，物理文件仍为 `mica_settings`（key 未改）。下列场景验证读写路径与杀进程恢复；**不必**重装或清数据。

**浏览与曲库（`LibraryBrowseSettings` + `LibraryScanSettings`）**

- 全部歌曲排序切换（标题/艺术家/播放次数等）→ 杀进程再开，顺序保持。
- 专辑/艺术家根层排序与网格列数修改 → 杀进程再开，Home 浏览态恢复。
- 设置页修改最短时长、排除目录、深度探测 → 重扫后行为符合新选项。

**播放页 UI（`PlaybackUiPreferences` + `AppUiSettings`）**

- 切换迷你栏样式、封面行为（标准/粒子/拍立得等）、播放页背景 → UI 即时刷新；杀进程再开仍生效。
- 「折叠歌词行数」自动/三行/一行（标准、粒子封面、平行封面带、复古立体封面）→ 播放页紧凑歌词行数符合选择；自定义标准仍走布局编辑。
- 开关频谱 / 发烧友迷你栏 / 拍立得封面 → 播放时频谱 tap 是否按预期启停（无旧封面闪频谱）。
- 修改播放页信息行、歌曲列表信息行可见性 → 对应位置显示/隐藏正确。
- 「播放时保持屏幕常亮」→ 播放中屏幕不熄、暂停后恢复系统策略。

**外观（`AppearancePreferences`）**

- 切换浅色/深色/跟随系统 → 全局主题即时变化。
- 修改强调色、云母背景预设或自定义渐变 → 主页/设置/播放页背景一致；杀进程再开保持。
- 逐项切换「隐藏状态栏」四种模式 → 播放页/非播放页分别按范围显示或隐藏，页面顶部 inset 不跳动，边缘下滑临时显示正常。

**歌词（`LyricsPreferences`）**

- 歌词页字号、对齐、沉浸式、文字颜色 → 进入歌词页验证；列表主题分别切换硬边逐字填充、音节抬升、柔边逐字填充、纯逐字切换，确认仅带真实逐字时间轴的当前行改变效果，默认仍为音节抬升。
- 开关通知歌词 → 通知栏逐行/逐字更新；关闭后恢复普通元数据。
- 双语拆分/展示模式 → 播放页与歌词页行数符合设置。
- 从数据库 v8 覆盖安装到当前版本后不重扫，旧 `songs.lyricsJson` 已迁入 `song_lyrics` 并仍可显示和播放；随后重扫同一首歌，TTML 原文/译文、逐字与行结束时间保持正常。旧列不再作为运行时歌词事实来源。
- 在至少一个歌词批次已完成后取消扫描：已确认 `Complete` 的正式 `song_lyrics` 保留，曲库摘要与 UI 不发布未完成扫描；制造单曲 `ReadFailed` 时，该曲全部旧槽保持不变并置 `lyricsRetryRequired`，下一次扫描强制重试。完整零失败扫描后才清除重试标记并推进 parser 版本。
- 含明确 TTML `end`/`dur` 的长空档：仅满足「无 active line + 下一句在未来 + `delta >= 7000ms` + 上一句有 `endTimeMs`」时显示三点 Y 间奏；普通 LRC 的长时间戳间隔不显示。
- 歌词云遇到含明确 `end`/`dur` 且完整时长至少 7000ms 的空档：整个空档取消当前句高亮，歌词轻微退远，歌词上方显示主题色呼吸柔光，镜头先停在前后句之间再靠近下一句；暂停或关闭系统动效后柔光静止。普通 LRC 的大时间戳间隔不得触发。
- 同一播放位置下，全屏、紧凑与通知歌词显示同一当前行；歌词行点击 seek 后三处同步更新。

**均衡器（`EqualizerPreferences` + `EqCustomProfileStore`）**

- 设置页开关 EQ、切换系统预设 → 出声有/无效果；杀进程再开选择与开关保持。
- 拖动自定义频段、保存/加载命名预设 → 再次进入 EQ 页数值与选中项一致。
- EQ 开启时切换曲目、后台/前台 → 无崩溃、无无声（pipeline 仍走 `MicaMediaService`）。

**旧数据兼容（升级用户）**

- 在已有 `mica_settings` 的设备上直接安装新包（不清数据）：上述各项应读出旧值，无需迁移步骤。
- 已有旧 `mica_playlists` JSON 的设备首次启动后，歌单列表、歌曲顺序、排序、封面与自定义封面应一次性迁入 Room，后续增删改不再依赖 JSON；旧 key 可保留但不作为运行时事实来源。
- 已有 Room v15/v16 数据库的设备升级后，`15→16` 只补 `embeddedLyricsProbeRevision` 列，`16→17` 建 `playlists` / `playlist_songs` 空表；不应丢失歌曲、歌词、浏览分组或播放历史。

### 并行真机 QA 包

设备上已有不同签名的 `com.mica.music` 时，可构建并行安装包，不需要卸载或清除现有数据：

```powershell
.\gradlew :app:assemblePerf "-Pmica.qaSideBySide=true" --no-configuration-cache
adb install -r app/build/outputs/apk/perf/app-perf.apk
```

并行包名为 `com.mica.music.qa`，版本名追加 `-qa`。该参数还会仅对 QA Perf 包启用调试，
便于使用 `run-as` 读取 `files/diagnostics/current-session.log`；普通 Perf/Release 仍不可调试。

覆盖安装后必须重新确认运行时权限和 AppOps。部分 MIUI 版本会把
`READ_EXTERNAL_STORAGE` 恢复为拒绝，即使安装命令成功。
