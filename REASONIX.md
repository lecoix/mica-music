# Mica — Reasonix Code Context

> 现状复核：2026-11-25。`exoplayer-only` HEAD `90bdd12a`（tag **`v0.4.5`** / code 60 / Room v35 / targetSdk 36，Release 已启用 R8 压缩）。正式发布验收见 `docs/CURRENT_FEATURE_STATUS.md`。

> AI / 工具用速览。**版本与依赖以 `gradle/libs.versions.toml` 为准**；领域词汇见 `CONTEXT.md`；动效与文档索引见 `docs/DOC_INDEX.md`。

## Stack

- **Language:** Kotlin 2.2.21 + Jetpack Compose (Material3 BOM 2024.10.00)
- **Android SDK:** minSdk 26, targetSdk 36, compileSdk 36; packaged ABIs: **arm64-v8a + armeabi-v7a**
- **Build:** AGP 8.9.1, Gradle 8.11.1, NDK r28c (28.2.13676358), version catalog (`gradle/libs.versions.toml`)
- **License:** Apache-2.0（仓库根 `LICENSE`）；第三方清单 `docs/OPEN_SOURCE_NOTICES.md`，与关于页同步
- **Key deps:** Media3 **1.9.0**, Jellyfin `media3-ffmpeg-decoder` **1.9.0+1**, Room 2.6.1 (KSP), Coil 2.7.0, Navigation Compose 2.8.2, Coroutines 1.8.1, WorkManager 2.10.5, Lyricon provider 0.1.70, Glance 1.2.0, `sardine-android` 0.9 / `okhttp-digest` 3.1.1 / `smbj` 0.15.0, reorderable 2.4.3, **BlurView 3.2.0** (JitPack), **Mica vendored TagLib fork**（基于 Kyant0 1.0.6 / TagLib C++ 2.2.1）, jAudiotagger
- **Test:** JUnit 4, Robolectric 4.16.1 (API 36 requires JDK 21), MockK, Roborazzi 1.34
- **FFmpeg native** — `libffmpegJNI.so` 作为 Media3 decoder 扩展（`third_party/media3-ffmpeg-decoder`）随工程分发；无独立 FFmpeg CLI / `libmica_ffmpeg.so` 软件播放路径

## Layout

- `app/src/main/java/com/mica/music/`
  - `data/` — `Song`, Room v35（本地曲库/歌词/自动同步状态/歌单/远端 catalog）, scanner, preferences 与 shared domain；`data/remote/` 持有 Navidrome/OpenSubsonic、WebDAV、SMB、Emby 与远端自动同步；`data/playback/` 持有 `ServicePlaybackStateStore`。`data/` 不得 import 顶层 `media/` / `playback/` implementation package
  - `playback/` — `PlayerController`（Compose/UI facade）、`PlaybackRuntime`（非 Compose application runtime）、connection、queue/timeline/tuning/statistics coordinator、MediaController queue sync 与睡眠定时器
  - `media/` — Exo 播放管线、`MicaMediaService`、`AudioPipelineCoordinator`、`AudioOffloadCircuitBreaker`、`MicaCompositePlayer`、`ServicePlaybackEngineCoordinator`、DSF、EQ implementation（`media/eq/`）
  - `audio/` / `usb/` — 跨 data/media/playback 共享的 neutral contract/value：`AudioQualityMode`、`EqBandConstants`、`UsbStableIdentity`
  - `ui/components/` — `PlayerSheetHost`, `MiniPlayer`, `SongRow`, `LyricsDisplay`, …
  - `ui/screens/` — `HomeScreen`, `NowPlayingScreen`, `CustomPlayerLowerPanel`（`CUSTOM_STANDARD` 竖屏自由布局编辑/渲染）, `SettingsScreen`, …
  - `ui/screens/player/` — `PlayerPageLayoutEngine`、封面流 View 岛（`CoverFlowRails`）、粒子封面（纯 GLES：`ParticleCoverHost` / `ParticleCoverRenderer`，WebView 旧管线已退役）、拍立得（`PhotoStackTransitionHost`）
  - `ui/theme/` — `MicaTheme`, 云母渐变, `MicaMaterialBackdrop` (BlurView)
  - `ui/navigation/` — `AppNavigation`, `AppNavigationMain`, `PlayerSheetOverlay`, `AppNavigationCoordinator`
  - `ui/motion/` — `MicaMotion.kt`
  - `widget/` — Glance 桌面播放小组件（自适应 / 大封面 / 四宫格）
- `app/src/test/java/.../player/` — `CoverFlowRailsTest`, `PlayerPageLayoutEngineTest`, …
- `MicaMainActivity.kt` — **双 `ComposeView`**：`BlurTarget` 包裹主内容 + 底部 overlay（迷你栏 BlurView）

## 2026-10—11 已提交架构增量

- **R8 & Target SDK 36**：`targetSdk 36` + NDK `r28c` + 16 KB ELF LOAD 对齐（`408e1c77`，真机 16 KB 运行待验收）；Release 开启 `isMinifyEnabled = true`，`app/proguard-rules.pro` 保留 Compose、FFmpeg JNI、Jaudiotagger ID3 及 SMBJ/MBassador `@Handler` 与 `ReflectiveHandlerInvocation` 构造函数（`0b28e223` / `73727779`）。
- **Emby 远程源**：`data/remote/emby/` 提供 API Key（Keystore `BearerToken` + `X-Emby-Token`）、10,000 首分页上限、静态直出流、音频元数据、256 KiB read-ahead 按需内嵌封面与 `EmbyLyricsLoader` 按需歌词（`976c056e` / `0585bc0c` / `81eca7d5`）。
- **歌词逐字注音与斜杠契约**：`LyricTextPart.ruby` 严格按源提供范围对齐并扩展到桌面/状态栏悬浮窗（`8ce53841` / `7b10a12d`）；源词尾时间跨渲染器保留（`6616e940`）；普通斜杠视觉分行（`SPLIT`）与语言内容选择（`ALL / ORIGINAL / TRANSLATION`）完全解耦（`d9be0a58`）。
- **队列性能与安全**：`018191c1` 消除重复全队列元数据重算并针对大队列收拢媒体通知控制器命令以防 ANR；`b9a6e9cf` 在队列关闭过渡期禁用列表与滚动条交互；`7991239d` 让扫描器验证到的专辑标签优先于 MediaStore 默认目录回退。
- **队列有效顺序**：`PlaybackOrderState` / `ServicePlaybackOrderOwner` / `ServicePlaybackStateCoordinator` 负责 accepted permutation、源顺序、逻辑随机标志、游标与串行快照；成员增删以物理下标做 Media3 增量操作。详见 ADR-0008。
- **SMB**：`SmbBrowserPane` 只浏览当前层；`SmbFolderLibraryLoader` 是进程级显式导入 owner；`SmbFolderLibraryIndexer` 先完整发现并提交可播条目，再至多四路 64 KiB tag read-ahead 补齐元数据。原始音频播放 DataSource 的预读策略独立。详见 ADR-0007。
- **测试**：Behavior / Architecture / Implementation 三类角色，OBSOLETE 是退役标签。分类校验见 `docs/testing/README.md`，数量以 manifest 的重新校验结果为准。

## Playback UI boundaries

- **播放页：** `PlaybackSurfaceState` / `PlaybackProgressState` / `PlaybackQueueState` + `NowPlayingActions`（`PlayerSheetHost` / `NowPlayingScreen` 不得直接收 `PlayerController`）
- **主页 / 列表：** `HomePlaybackState` + `HomePlaybackActions`（`HomeScreen`、搜索与列表组件同上）
- **装配层例外：** `MicaMainActivity` / `AppNavigation` 持有 `PlayerController` 并翻译 state/actions
- **状态所有权：** `PlayerController` 只做 Compose/UI facade；`PlaybackRuntime` 持有 connection/listener 与 playback application coordination，内部队列 / 时间轴 / 调音由 `Playback*Coordinator` 收口；`PlaybackConnectionSession` 用连接 generation 拒绝旧连接回调，陈旧队列镜像不会覆盖新状态
- **冷启动恢复：** `ServicePlaybackStateStore` + `PlayerController.bootstrapQueue()`（service_wins）；`PlaybackSessionStore` 仅补 shuffle 等 App 偏好

## Docs (read order)

1. `CONTEXT.md` → `README.md` → `DESIGN_SPEC.md` → `docs/TODO.md` → `docs/MOTION.md` (§七 岛分工)
2. 播放页：`docs/PLAYER_PAGE_CONTRACT.md`, `docs/COVER_FLOW_IMPLEMENTATION.md`
3. 共享封面：`docs/SHARED_ELEMENT_ANIMATION_NOTES.md`
4. 索引：`docs/DOC_INDEX.md`；开源声明：`docs/OPEN_SOURCE_NOTICES.md`

## Commands

- **assemble debug:** `.\gradlew.bat :app:assembleDebug`
- **quality gate:** `.\gradlew :app:micaCheck --no-configuration-cache`（编译 + Lint + JVM/Robolectric + Roborazzi）
- **clean assemble:** `.\scripts\clean-assemble-debug.ps1`
- **Media3 FFmpeg native build:** `.\scripts\build-media3-ffmpeg-dsd.ps1`
- **Gradle daemon stop:** `.\gradlew --stop`

## Conventions

- **UI shape:** 0dp corners (`RectangleShape`)
- **Accent:** user-selectable (`AppAccentColor`); default purple `#8B7AFF`
- **Comments:** Chinese
- **Motion:** `rememberMicaMotionEnabled()` + `MicaMotion.tween*`; 跟手几何 / backdrop blur / 封面流 / 粒子封面 → View 岛
- **Package:** `com.mica.music`

## Watch out for

- **Media3 FFmpeg extension required** — `third_party/media3-ffmpeg-decoder` 提供 `libffmpegJNI.so`（Jellyfin 打包，非 Maven Central 默认 artifact）
- **arm64 only** — x86 emulator unsupported
- **Backdrop blur** — 浮岛 `MicaMaterialBackdrop` + `BlurTarget`; 勿用 Compose `Modifier.blur()` 做毛玻璃
- **封面流**（`PAUSE_FOLD` / `RETRO_3D`）— 只改 `CoverFlowRails` / `CoverFlowCarouselView`; 勿在 Compose 重建槽位动画
- **粒子封面**（`PARTICLE_COVER`）— 现网纯 **GLES**（`ParticleCoverPlayerLayer` → `ParticleCoverHost`）；WebView 旧管线 `ThreeParticleCoverHost` / `assets/particle_cover` 已于 `d0d5d56e` 退役；见 `docs/PARTICLE_COVER_OPENGL_MIGRATION.md` §0
- **R8 / ProGuard** — Release 已启用 `isMinifyEnabled = true`；新增反射、JNI 或事件总线依赖时须同步核查 `app/proguard-rules.pro`（参考 SMBJ/MBassador 构造函数 keep 规则 `73727779`）
- **`.dff`** — 可扫描，播放前路由拒绝（`.dsf` 走 Exo 扩展）
- **MockData.kt** — legacy, unused
- **Configuration cache** — if builds act stale, run `clean-assemble-debug.ps1`
