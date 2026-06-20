# Mica — Reasonix Code Context

> AI / 工具用速览。**版本与依赖以 `gradle/libs.versions.toml` 为准**；动效与文档索引见 `docs/DOC_INDEX.md`。

## Stack

- **Language:** Kotlin 2.2.21 + Jetpack Compose (Material3 BOM 2024.10.00)
- **Android SDK:** minSdk 26, targetSdk 34, compileSdk 34; **arm64-v8a only**
- **Build:** AGP 8.7.0, Gradle 8.9, version catalog (`gradle/libs.versions.toml`)
- **Key deps:** Media3 1.4.1, Room 2.6.1 (KSP), Coil 2.7.0, Navigation Compose 2.8.2, Coroutines 1.8.1, reorderable 2.4.3, **BlurView 3.2.0** (JitPack)
- **FFmpeg native** — `libffmpegJNI.so` 作为 Media3 decoder 扩展随工程分发；无独立 FFmpeg CLI / `libmica_ffmpeg.so` 软件播放路径

## Layout

- `app/src/main/java/com/mica/music/`
  - `data/` — `Song`, Room, scanner, `PlayerController`, `AppUiSettings`
  - `media/` — Exo playback pipeline、Media3 FFmpeg 扩展、DSF、EQ、`MicaMediaService`
  - `ui/components/` — `MiniPlayer`, `SongRow`, `LyricsDisplay`, …
  - `ui/screens/` — `HomeScreen`, `NowPlayingScreen`, `SettingsScreen`, …
  - `ui/screens/player/` — 播放页布局引擎、封面流 View 岛、`CoverFlowRails`
  - `ui/theme/` — `MicaTheme`, 云母渐变, `MicaMaterialBackdrop` (BlurView)
  - `ui/navigation/` — `AppNavigationMain`, `PlayerSheetOverlay`, `AppNavigationCoordinator`
  - `ui/motion/` — `MicaMotion.kt`
- `app/src/test/java/.../player/` — `CoverFlowRailsTest`, `PlayerPageLayoutEngineTest`, …
- `MainActivity.kt` — **双 `ComposeView`**：`BlurTarget` 包裹主内容 + 底部 overlay（迷你栏 BlurView）

## Docs (read order)

1. `README.md` → `DESIGN_SPEC.md` → `docs/TODO.md` → `docs/MOTION.md` (§七 岛分工)
2. 播放页：`docs/PLAYER_PAGE_CONTRACT.md`, `docs/COVER_FLOW_IMPLEMENTATION.md`
3. 共享封面：`docs/SHARED_ELEMENT_ANIMATION_NOTES.md`
4. 索引：`docs/DOC_INDEX.md`

## Commands

- **assemble debug:** `.\gradlew.bat :app:assembleDebug`
- **clean assemble:** `.\scripts\clean-assemble-debug.ps1`
- **Media3 FFmpeg native build:** `.\scripts\build-media3-ffmpeg-dsd.ps1`
- **Gradle daemon stop:** `.\gradlew --stop`

## Conventions

- **UI shape:** 0dp corners (`RectangleShape`)
- **Accent:** user-selectable (`AppAccentColor`); default purple `#8B7AFF`
- **Comments:** Chinese
- **Motion:** `rememberMicaMotionEnabled()` + `MicaMotion.tween*`; 跟手几何 / backdrop blur → View 岛
- **Package:** `com.mica.music`

## Watch out for

- **Media3 FFmpeg extension required** — `third_party/media3-ffmpeg-decoder` 提供 `libffmpegJNI.so`
- **arm64 only** — x86 emulator unsupported
- **Backdrop blur** — 浮岛 `MicaMaterialBackdrop` + `BlurTarget`; 勿用 Compose `Modifier.blur()` 做毛玻璃
- **封面流** — 只改 `CoverFlowRails` / `CoverFlowCarouselView`; 勿在 Compose 重建槽位动画
- **MockData.kt** — legacy, unused
- **Configuration cache** — if builds act stale, run `clean-assemble-debug.ps1`
