# Mica — Reasonix Code Context

## Stack

- **Language:** Kotlin 2.0.21 + Jetpack Compose (Material3)
- **Android SDK:** minSdk 26, targetSdk 34, compileSdk 34; arm64-v8a only
- **Build:** AGP 8.7.0, Gradle 8.9, version catalog (`gradle/libs.versions.toml`)
- **Key deps:** Media3 ExoPlayer 1.4.1, Room 2.6.1 (KSP compiler), Coil 2.7.0, Navigation Compose 2.8.2, Coroutines 1.8.1, reorderable 2.4.3
- **FFmpeg native** for ALAC decoding (arm64-v8a `.so` synced from assets at build time)

## Layout

- `app/src/main/java/com/mica/music/` — main source tree
  - `data/` — models (`Song`, `TrackMetadata`), Room DAOs/entities, scanner (MediaStore + SAF), player controller, preferences
  - `media/` — ALAC engines (`AlacAudioTrackEngine`, `AlacPcmPlayer`), software EQ, `MicaMediaService`, `MicaCompositePlayer`
  - `ui/components/` — reusable composables (`MiniPlayer`, `SongRow`, `LyricsDisplay`, `HiFiSeekBar`, etc.)
  - `ui/screens/` — screen-level composables (`HomeScreen`, `NowPlayingScreen`, `SettingsScreen`, etc.)
  - `ui/theme/` — `MicaTheme`, `HifiTypography`, shapes (`RectangleShape`), background variants
  - `ui/navigation/` — `AppNavigation` (NavHost)
  - `util/` — `SongActions`, `AppIntents`
- `app/src/main/assets/ffmpeg/arm64-v8a/ffmpeg` — FFmpeg native binary source
- `ffmpeg/docker/` — Docker-based cross-compile for FFmpeg
- `scripts/` — `build-ffmpeg-arm64.ps1`, `clean-assemble-debug.ps1`
- `DESIGN_SPEC.md` — UI/UX design spec (0dp rounded corners = `RectangleShape`, purple accent `#8B7AFF`)
- `docs/TODO.md` — feature checklist (implemented / planned)

## Commands

- **assemble debug:** `.\gradlew.bat :app:assembleDebug`
- **clean assemble (fix stale cache):** `.\scripts\clean-assemble-debug.ps1` (stops daemon, deletes APK output, runs with `--no-configuration-cache`)
- **FFmpeg native build:** `.\scripts\build-ffmpeg-arm64.ps1` (must run once before first APK build)
- **Gradle daemon stop:** `.\gradlew --stop`

## Conventions

- **UI shape:** all 0dp rounded corners (`RectangleShape`), no circular/capsule elements
- **Accent color:** purple `#8B7AFF` for active/now-playing states
- **Code comments:** Chinese (Mandarin) throughout
- **Room:** KSP annotation processor, `SongDao` + `LibraryMetaDao`, migration objects in `MicaDatabaseMigrations`
- **Scanning:** two modes — MediaStore (system DB) and SAF folder picker; results merged into Room with incremental sync
- **Package name:** `com.mica.music`
- **No test directory** found in the project
- **Gradle config:** configuration cache enabled by default; parallel builds; `kotlin.code.style=official`

## Watch out for

- **FFmpeg binary must be pre-built.** Build warns at preBuild if `app/src/main/assets/ffmpeg/arm64-v8a/ffmpeg` is missing. Without it ALAC playback silently fails at runtime.
- **arm64-v8a only.** No 32-bit ABI support; will crash on x86 emulators or old 32-bit devices.
- **FFmpeg asset renamed at build time.** `syncFfmpegNative` copies `ffmpeg` → `libmica_ffmpeg.so` into jniLibs.
- **Configuration cache staleness.** If builds behave unexpectedly, run `clean-assemble-debug.ps1` which passes `--no-configuration-cache`.
- **MockData.kt** is legacy UI scaffolding — main flow no longer uses it.
- **`data/MockData.kt`** — early UI placeholder, not used in production flow.
