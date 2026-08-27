# Mica — Reasonix Code Context

> AI / 工具用速览。**版本与依赖以 `gradle/libs.versions.toml` 为准**；领域词汇见 `CONTEXT.md`；动效与文档索引见 `docs/DOC_INDEX.md`。

## Stack

- **Language:** Kotlin 2.2.21 + Jetpack Compose (Material3 BOM 2024.10.00)
- **Android SDK:** minSdk 26, targetSdk 34, compileSdk 35; **arm64-v8a only**
- **Build:** AGP 8.7.0, Gradle 8.9, version catalog (`gradle/libs.versions.toml`)
- **Key deps:** Media3 **1.9.0**, Jellyfin `media3-ffmpeg-decoder` **1.9.0+1**, Room 2.6.1 (KSP), Coil 2.7.0, Navigation Compose 2.8.2, Coroutines 1.8.1, reorderable 2.4.3, **BlurView 3.2.0** (JitPack), **Mica vendored TagLib fork**（基于 Kyant0 1.0.6 / TagLib C++ 2.2.1）, jAudiotagger
- **Test:** JUnit 4, Robolectric 4.13, MockK, Roborazzi 1.34
- **FFmpeg native** — `libffmpegJNI.so` 作为 Media3 decoder 扩展（`third_party/media3-ffmpeg-decoder`）随工程分发；无独立 FFmpeg CLI / `libmica_ffmpeg.so` 软件播放路径

## Layout

- `app/src/main/java/com/mica/music/`
  - `data/` — `Song`, Room（曲库 + `PlaylistStore` 歌单）, scanner, preferences、`AppUiSettings`、`PlaybackQueueMode`、`PlaybackTuning` 等共享 domain；`data/playback/` 持有 `ServicePlaybackStateStore` 与恢复快照 persistence model。`data/` 不得 import 顶层 `media/` / `playback/` implementation package
  - `playback/` — `PlayerController`（Compose/UI facade）、`PlaybackRuntime`（非 Compose application runtime）、connection、queue/timeline/tuning/statistics coordinator、MediaController queue sync 与睡眠定时器
  - `media/` — Exo 播放管线、`MicaMediaService`、`AudioPipelineCoordinator`、`AudioOffloadCircuitBreaker`、`MicaCompositePlayer`、`ServicePlaybackEngineCoordinator`、DSF、EQ implementation（`media/eq/`）
  - `audio/` / `usb/` — 跨 data/media/playback 共享的 neutral contract/value：`AudioQualityMode`、`EqBandConstants`、`UsbStableIdentity`
  - `ui/components/` — `PlayerSheetHost`, `MiniPlayer`, `SongRow`, `LyricsDisplay`, …
  - `ui/screens/` — `HomeScreen`, `NowPlayingScreen`, `CustomPlayerLowerPanel`（`CUSTOM_STANDARD` 竖屏自由布局编辑/渲染）, `SettingsScreen`, …
  - `ui/screens/player/` — `PlayerPageLayoutEngine`、封面流 View 岛（`CoverFlowRails`）、粒子封面（现网 `ParticleCoverHost` / `ParticleCoverRenderer` GLES；`ThreeParticleCoverHost` WebView 回退）、拍立得（`PhotoStackTransitionHost`）
  - `ui/theme/` — `MicaTheme`, 云母渐变, `MicaMaterialBackdrop` (BlurView)
  - `ui/navigation/` — `AppNavigation`, `AppNavigationMain`, `PlayerSheetOverlay`, `AppNavigationCoordinator`
  - `ui/motion/` — `MicaMotion.kt`
- `app/src/test/java/.../player/` — `CoverFlowRailsTest`, `PlayerPageLayoutEngineTest`, …
- `MainActivity.kt` — **双 `ComposeView`**：`BlurTarget` 包裹主内容 + 底部 overlay（迷你栏 BlurView）

## Playback UI boundaries

- **播放页：** `PlaybackSurfaceState` / `PlaybackProgressState` / `PlaybackQueueState` + `NowPlayingActions`（`PlayerSheetHost` / `NowPlayingScreen` 不得直接收 `PlayerController`）
- **主页 / 列表：** `HomePlaybackState` + `HomePlaybackActions`（`HomeScreen`、搜索与列表组件同上）
- **装配层例外：** `MainActivity` / `AppNavigation` 持有 `PlayerController` 并翻译 state/actions
- **状态所有权：** `PlayerController` 只做 Compose/UI facade；`PlaybackRuntime` 持有 connection/listener 与 playback application coordination，内部队列 / 时间轴 / 调音由 `Playback*Coordinator` 收口；`PlaybackConnectionSession` 用连接 generation 拒绝旧连接回调，陈旧队列镜像不会覆盖新状态
- **冷启动恢复：** `ServicePlaybackStateStore` + `PlayerController.bootstrapQueue()`（service_wins）；`PlaybackSessionStore` 仅补 shuffle 等 App 偏好

## Docs (read order)

1. `CONTEXT.md` → `README.md` → `DESIGN_SPEC.md` → `docs/TODO.md` → `docs/MOTION.md` (§七 岛分工)
2. 播放页：`docs/PLAYER_PAGE_CONTRACT.md`, `docs/COVER_FLOW_IMPLEMENTATION.md`
3. 共享封面：`docs/SHARED_ELEMENT_ANIMATION_NOTES.md`
4. 索引：`docs/DOC_INDEX.md`

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
- **粒子封面**（`PARTICLE_COVER`）— 现网 **GLES**（`ParticleCoverPlayerLayer` → `ParticleCoverHost`）；WebView 回退 `ThreeParticleCoverHost` 仅 `UseNativeParticleCoverInPlayer = false` 或预览对比；见 `docs/PARTICLE_COVER_OPENGL_MIGRATION.md` §0
- **`.dff`** — 可扫描，播放前路由拒绝（`.dsf` 走 Exo 扩展）
- **MockData.kt** — legacy, unused
- **Configuration cache** — if builds act stale, run `clean-assemble-debug.ps1`
