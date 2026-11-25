# Mica 测试指南

> 现状复核：2026-11-25（`v0.4.5 / 90bdd12a`）。Device Flow harness / `androidTest` compile gate 已接入；分级发布门禁与发版前 15 分钟必测清单见 [`RELEASE_GATE.md`](RELEASE_GATE.md)。不能把单机 Debug 结果写成正式签名 R8 包或全硬件矩阵通过。

## 测试角色（2026-10-09）

测试分类与测试运行环境是正交的：**Behavior Contract** 保障用户/数据安全、持久化、并发边界；**Architecture Contract** 保障依赖方向、owner/authority 与 commit 边界；**Implementation Verification** 验证可替换的 planner、scheduler、UI geometry、回调和 Robolectric 机制。`OBSOLETE` 是待退役标记，不是第四个验收合同。参见 [`testing/README.md`](testing/README.md)。

```powershell
python scripts/generate-test-classification.py
.\scripts\check-test-classification.ps1
```

2026-09-29 的测试数量是历史快照；10 月新增的有效播放顺序/SMB staged scope/GLES 像素测试必须重新分类并重新校验 manifest。新增定向重点：队列随机编辑后的真实自然遍历与冷恢复；SMB 取消/来源删除与 late completion 的 Room 交错；粒子正方形的 2 个 songId parity × 4 个播放进度像素验证。

## Windows 终端编码

Windows PowerShell 5.1 若看到中文乱码，先在当前会话启用 UTF-8：

```powershell
. .\scripts\use-utf8-console.ps1
```

## 单命令质量门

targetSdk 36 使用 Robolectric 4.16.1。运行 API 36 的 JVM/Robolectric 测试需要 **JDK 21**；应用 Java/Kotlin 字节码目标仍为 17，TagLib 仍需 JDK 17 编译工具链。`android-tests` 的 JVM job、`android-nightly` 和发布前测试 job 同时安装 17/21，默认使用 21；Gradle 从 `JAVA_HOME_17_X64` / `JAVA_HOME_21_X64` 识别两套工具链。本地已安装的 JDK 17 也可自动识别。固定 SDK 34 的截图 job 继续使用 JDK 17，其结果不代表 Android 16 真机视觉验收。迁移范围与待验收项见 [`TARGET_SDK_36_MIGRATION.md`](TARGET_SDK_36_MIGRATION.md)。

默认 JVM 平台通过 `app/src/test/resources/robolectric.properties` 固定为 API 34，避免 targetSdk 提升时隐式改变所有测试的平台以及 Robolectric native 初始化路径；显式 `@Config` 继续覆盖原有多 API 和新增 API 36 用例。默认 JVM 通过不能表述为全量 API 36 通过。

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

真实 App 自动化流程测试使用连接的 ARM 设备和独立 QA application id，不接触普通 `com.mica.music` 数据：

```powershell
.\scripts\run-android-flow-tests.ps1
# 多设备时：
.\scripts\run-android-flow-tests.ps1 -Serial <adb-serial>
```

流程测试分层、首批矩阵和 CI 晋级规则见 [`AUTOMATED_FLOW_TESTING.md`](AUTOMATED_FLOW_TESTING.md)。当前 GitHub Hosted runner 只负责**编译** `androidTest`；项目仅打包 ARM native ABI，因此不使用 x86_64 emulator 冒充运行时流程门。

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

分级发版门禁（**Gate 0 CI / Gate 1 每次发版 15 分钟必测 / Gate 2 按变更触发 / Gate 3 大版本与社区观察**）统一维护在 [`RELEASE_GATE.md`](RELEASE_GATE.md)，不再在本文重复堆叠多设备笛卡尔积。

- 可稳定自动化的用户流程由 `run-android-flow-tests.ps1` / Device Flow 承担；
- 每次发版只需在 1 台主力真机上用 **正式签名 R8 Release APK** 跑完 `RELEASE_GATE.md` 的 Gate 1 必过清单（覆盖升级、核心格式/焦点、随机模式冷启恢复、后台/通知/小组件、R8 远端源连播、浅色可读性与缓存体积）；
- 蓝牙/USB DAC/Native/大库扫描/悬浮窗/频谱等按 `RELEASE_GATE.md` 的 Gate 2 变更触发条件补充。

Gate 1 必过项失败时，即使 JVM 测试全部通过也不得发布。

### 2026-09-11 单机发布前 smoke（历史参考）

Android 12 / Xiaomi 22081212C 上从空库扫描至 10,665 首（37 GB，`technicalFailed=0`），并实播 ALAC/FLAC/DSF，覆盖暂停态 seek、上下曲、搜索单曲队列、循环回卷、标准横屏、后台/熄屏与冷启动暂停恢复。该记录为旧 `0.4.0/code54` debuggable 工作树包参考，不能替代当前 `v0.4.5` 正式签名 R8 Release 的 Gate 1 验收。

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
- Service 重启后必须按歌曲 ID 恢复队列中的当前曲和位置；恢复状态必须为暂停，即使持久化时 `playWhenReady=true`。`auto_play_on_launch` 默认关闭；开启时由 App `PlaybackRuntime` 在队列就绪后额外请求一次播放，不得改写 Service 恢复契约。
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
.\gradlew :app:testDebugUnitTest --tests com.mica.music.LibraryQueueSyncPolicyTest --tests com.mica.music.data.LibraryPlaybackQueueCoordinatorTest --tests com.mica.music.util.SongActionsTest --tests com.mica.music.data.LibraryBrowseDetailsTest --tests com.mica.music.data.AlbumArtRepairCoordinatorTest --tests com.mica.music.data.MusicLibraryTest --tests com.mica.music.data.library.LibraryScanOrchestratorTest --tests com.mica.music.data.DataLayerDependencyStructureTest --tests com.mica.music.data.preferences --tests com.mica.music.data.scanner.ProbeResultTest --tests com.mica.music.ui.navigation.AppNavigationCoordinatorTest --no-configuration-cache
.\gradlew :app:compileDebugKotlin --no-configuration-cache
```

`LibraryScanOrchestratorTest` 还固定曲库 store seam 的锁顺序：依赖当前 catalog 的异步写入必须等待 `scanExecutionMutex`，等待期间 generation 失效则确定 no-op。`DataLayerDependencyStructureTest.libraryStoreWritePrimitivesStayOwnedByMusicLibraryBacking` 禁止 `MusicLibrary.kt` / `data/library/**` 重新直接持有 `storeSyncMutex` 或手写 store revision recipe。`musicLibraryFacadeDoesNotOwnBrowseOrLyricsHydrationState` 进一步冻结 facade 边界：search index/query LRU、artist/album/folder browse cache 与 persisted browse state 只能由 `LibraryBrowseCoordinator` 持有，`SharedLyricsMemoryCache` hydration policy 只能由 `LibraryLyricsHydrator` 组合。对应行为继续由 `MusicLibraryTest`、`LibraryBrowseTest` 与 `LibraryScaleTest` 覆盖，包括冷启动 browse restore、stale prewarm fencing、artist split 失效、歌词 cache/version/retry 与 10k 曲库查询规模。

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
4. **真实 Media3 契约（设备/模拟器）**：`NotificationLyricsMedia3ContractTest` 使用运行时生成的 4 秒静音 WAV、真实 ExoPlayer/MediaSession/MediaController、repeat-one 和 `NotificationLyricsCoordinator`，覆盖歌词 `replaceMediaItem` 后三次真实连续循环各计一次、**后续同曲回卷在 Controller 丢掉 discontinuity 时进度仍归零**、暂停恢复与手动 seek 为 0、自然下一首与显式重播各计一次，以及播放中连接第二个 Controller 为 0。该测试需要已连接的 arm64 设备，不属于纯 JVM 门禁。

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
- `RoomMigrationContractTest`：使用 `MigrationTestHelper` 和导出的 Room schema 验证历史迁移契约；当前数据库为 **v35**。`DatabaseMigrationTest` / `RemoteDatabaseMigrationTest` 对本地曲库、歌单、歌词偏移、响度/MV、remote source/catalog 等关键迁移做代表数据读回；v25 之后的 automatic-sync checkpoint/retry/outbox/index 及 v33–v35 SMB selected tracks/scopes 演进由对应 repository 与迁移测试约束。新增 schema 必须同时更新导出 schema、migration 注册与相应数据契约测试。
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
- `AudioPipelineCoordinatorTest` / `AudioOffloadCircuitBreakerTest` / `AudioOffloadPreferencesRobolectricTest`：EQ / 频谱 / ReplayGain / 声道平衡 / 音效实验室 / offload 偏好变化使旧熔断任务失效；确认失速后回 PCM、按 build fingerprint 记录失败、手动重试与系统更新后重试。
- `ExternalAudioOpenTest` / `TransientPlaybackCatalogTest` / `ServicePlaybackStateCoordinatorTest`：只有 MediaStore authority 或已持久化 grant 的外部 URI 才进入恢复快照，不可存续临时队列不落盘。
- `PlaylistStoreTest` / `RoomMigrationContractTest` / `DatabaseMigrationTest`：歌单写库成功后才更新内存；`mica_playlists` JSON 一次性迁移与完整 `16→21` schema 演进契约。
- `LyricDisplayRowsTest` / `LyricsCloudLayoutTest` / `LyricsTimelineEngineTest`：入库不把显示分隔符改写成 `TRANSLATION`，歌词云逐字进度按 token 角色与行结束时间计算。

### 2026-08-30 均衡器页面重做

EQ 页把只读频响曲线与 2×5 竖推子网格合并成单一的 `EqualizerCurveEditor`（曲线即推子）。音频链路、`EqualizerPreferences` 与 `EqCustomProfileStore` 的读写未改动，回归集中在手势与坐标映射：

- `EqualizerCurveEditorInputTest`：频段列映射（绘图区从 `DbScaleWidth` 起十段等宽）、绘图区正中恒为 0 dB、上下边界钳到 `MIN/MAX_MILLIBELS`、**按下即锁定起始频段列**（斜向拖动不得漂到相邻频段）、嵌套在 `verticalScroll` 中竖向拖动不被外层抢走且外层不滚动、`enabled = false` 时不产生任何更新。
- 手势不走 `detectDragGestures`：起始列必须在 `awaitFirstDown` 时确定。若改回按触摸 slop 之后的 `onDragStart` 取列，斜向起手会错列，`dragStaysOnStartBandDespiteHorizontalMovement` 会失败。
- 单测跑在 Robolectric 注入的指针事件上，可以证明手势归属与坐标换算，**不能**证明真机的甩动速度、多指、触觉反馈手感与大字号下频率标签的排版。

### 2026-08-30 音效实验室

实用档（宽度 / 低高架 / 混响房间·阻尼·湿比 / 360° 环绕），默认湿比与环绕强度为 0，只进现有 Shared PCM `SoftwareEqualizer`。JVM 覆盖激活条件、引擎身份与处理、EQ 直通不被中性 FX 破坏、offload latch：

- `SoundFxSettingsTest`：默认与「仅开开关」不请求 DSP；湿比为 0 时房间/阻尼不激活 DSP；环绕转速单独不激活；越界钳位。
- `SoundFxEngineTest`：中性设置不改帧；宽度 0 变单声道；低架对低频能量高于高频；混响脉冲后有尾音，且首反射峰值受 `fixedgain` 约束；360° 环绕把左脉冲延迟送到右耳，且开环绕时宽度 0 不再塌成单声道。
- `EqDspTest`：FX 关或仅中性时 PCM16 bit-exact；宽度 0 时立体声 float 塌成中点。
- `AudioPipelineCoordinatorTest.soundFxLeavesOffloadOnceThenStaysInPcmWhenBypassed`：激活一次后关回中性，本会话仍 latch PCM（与声道平衡相同），不把 `AudioQualityMode` 写成 DSP。
- `PreferencesFallbackRobolectricTest.soundFxPreferencesClampAndStayInactiveByDefault`：默认不激活；读写钳位；旧 `sound_fx_reverb_preset` 不打开混响。
- `SettingsSearchIndexTest`：搜「音效实验室」「混响」「360」命中 `audio.sound-fx`。

单测不能证明听感接近参考播放器，也不能证明 USB 独占真机旁路；见下方真机项。

### 历史重构与旧迁移手测清单（已由自动化 + `RELEASE_GATE.md` 替代）

原 `a257a0f` 队列/删除手测、`MusicLibrary` 拆分回归（`LibraryScanOrchestrator` 已退役为 `LibraryOperationExecutor`）、`AppPreferences` 分域回归、旧 `mica_playlists` JSON 迁移与 `Room v15→v21` 等一次性历史重构清单，现已由 `RoomMigrationContractTest`、`DataLayerDependencyStructureTest`、`LibraryQueueSyncPolicyTest`、`SongActionsTest`、`MusicLibraryTest`、`PreferencesFallbackRobolectricTest` 与 `micaCheck` 持续守卫。

- 每次发版前的真机必测项与按变更触发项（扫描/缓存、USB/DSD/EQ/音效实验室、MV、悬浮歌词、频谱）统一见 [`RELEASE_GATE.md`](RELEASE_GATE.md)。
- 本地音乐 MV 自动化契约仍由 `MusicVideoMatcherTest` 及 lease/fallback 交错单测守卫；触及 MV 播放链时按 `RELEASE_GATE.md` Gate 2 补真机首帧与回退回归。

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
