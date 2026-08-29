# Open Source Notices

Mica Music uses the following major open source components. This file is a
release checklist seed; before a public release, include the full license text
and copyright notice required by each dependency.

Last reviewed: **2026-08-29**

## Runtime Dependencies

| Component | License |
|---|---|
| AndroidX Core / Activity / Lifecycle / Navigation / Room / DocumentFile / Palette / Annotation | Apache License 2.0 |
| Jetpack Compose UI / Material 3 / Material Icons | Apache License 2.0 |
| AndroidX Media3 (`media3-exoplayer`, `media3-session`, …) **1.9.0** | Apache License 2.0 |
| Jellyfin Media3 FFmpeg decoder (`org.jellyfin.media3:media3-ffmpeg-decoder` **1.9.0+1**, Maven fallback when local DSD build absent) | Apache License 2.0 |
| Media3 FFmpeg extension — local DSD build (`third_party/media3-ffmpeg-decoder/`, Java + JNI; ships `libffmpegJNI.so`) | Apache License 2.0 (Java/JNI); see FFmpeg row below for native codec library |
| Kotlin / Kotlinx Coroutines | Apache License 2.0 |
| `sardine-android` **0.9** (WebDAV PROPFIND / DAV resource parsing) | Apache License 2.0 |
| `okhttp-digest` **3.1.1** (WebDAV HTTP Digest challenge authentication) | Apache License 2.0 |
| `smbj` **0.15.0** (SMB2/SMB3 protocol client; SMB1 is not enabled by Mica) | Apache License 2.0 |
| Coil | Apache License 2.0 |
| Guava | Apache License 2.0 |
| Calvin Reorderable | Apache License 2.0 |
| BlurView 3.x (`com.github.Dimezis:BlurView`, JitPack **version-3.2.0**) | Apache License 2.0 |
| Mica vendored TagLib Android wrapper（基于 Kyant0/taglib 1.0.6，`third_party/taglib/`） | Apache License 2.0（仓库根 `third_party/taglib/LICENSE`） |
| SylvaKru USB-exclusive transport / protocol code（`third_party/sylvakru-usb-transport/`；参考 `huya688zdx/sylvakru` commit `3f2578692499e403d7eddc6fdbe52d1b6a1b2206`，其 README 说明基于原版 `AfalpHy/sylvakru`） | Apache License 2.0；模块内保留参考 `LICENSE`、Mica `NOTICE` 与派生/修改声明 |
| libusb 1.0.30（vendored 于 `third_party/libusb-1.0.30/`，用于 USB 独占 native transport） | LGPL 2.1 或更高版本（见 `third_party/libusb-1.0.30/COPYING` / `README`） |
| TagLib C++ 2.2.1（vendored 于 `third_party/taglib/src/main/cpp/taglib/`） | LGPL 2.1 或 MPL 1.1（上游同时附带 `COPYING.LGPL` / `COPYING.MPL`） |
| utfcpp（TagLib submodule，`3rdparty/utfcpp`） | Boost Software License 1.0 |
| jAudiotagger | LGPL 2.1 |
| FFmpeg (linked inside `libffmpegJNI.so` for DSF / extended codec decode) | LGPL 2.1+ by default; current build script does not enable GPL or nonfree components |
| libebur128（R128 算法参考；未链接其二进制） | MIT License；Mica 的 Kotlin 响度分析器适配其 K-weighting 系数构造和默认声道映射；Copyright (c) 2011 Jan Kokemüller |

## Bundled Assets (non-Maven)

| Asset | License | Notes |
|---|---|---|
| Three.js (minified in `app/src/main/assets/particle_cover/mica-particle-cover.js`) | MIT License | **Legacy WebView fallback** for particle cover (`ThreeParticleCoverHost` when `UseNativeParticleCoverInPlayer = false`). Playback page **shipped path is native GLES** (`ParticleCoverHost`); remove this asset after WebView retirement (see `TODO.md`). |
| `mica-particle-mask-transition.js` | (bundled with particle_cover) | Same WebView fallback bundle; retire with Three.js assets above |

Particle cover **production rendering** uses Android **OpenGL ES 2.0** platform APIs (`ParticleCoverRenderer`); no additional third-party GL library.

## Release Notes

- Apache 2.0 dependencies require preserving copyright notices and a copy of the
  Apache License 2.0 text.
- **BlurView** (Dimezis): preserve copyright and Apache 2.0 notice; distributed
  via JitPack — verify `version-3.2.0` tag notice at release time.
- **SylvaKru USB-exclusive code**: preserve `third_party/sylvakru-usb-transport/LICENSE`
  and `NOTICE`, the source-level derivative/modification headers, and the provenance
  in that module's `README.md`. The audited reference is
  `huya688zdx/sylvakru@3f2578692499e403d7eddc6fdbe52d1b6a1b2206`; its own README
  credits the original `AfalpHy/sylvakru`, and its Apache-2.0 `LICENSE` states
  `Copyright 2025-2026 AfalpHy`. The reference snapshot had no separate `NOTICE`
  file. Do not describe the current Mica transport as wholly byte-for-byte
  upstream: substantial Media3/session/libusb adaptation is Mica-specific.
- **libusb 1.0.30**: it is vendored source under LGPL-2.1-or-later. Preserve its
  `COPYING`/copyright notices and keep the corresponding source available with
  binary distributions that include the USB-exclusive native transport; re-check
  relinking/source-offer obligations as part of the release packaging review.
- **Jellyfin `media3-ffmpeg-decoder`**: preserve Apache 2.0 notice for the Java/JNI
  wrapper; when the app ships the locally built `libffmpegJNI.so`, also satisfy FFmpeg
  / LGPL distribution requirements below.
- **libebur128**: Mica does not bundle or link libebur128 itself. The R128 analyzer
  adapts its K-weighting coefficient construction and default channel mapping under
  the MIT License; preserve the source-level attribution and upstream copyright notice.
- **FFmpeg** binary distribution requires preserving FFmpeg / LGPL notices and
  providing a way to obtain the corresponding source or build scripts
  (`scripts/build-media3-ffmpeg-dsd.ps1`, `third_party/media3-ffmpeg-decoder/`).
- The current FFmpeg build scripts pass selected decoder flags and do not pass
  `--enable-gpl` or `--enable-nonfree`; re-check this if the build scripts are
  changed before release.
- If FFmpeg build flags change to include GPL or nonfree components, update this
  notice and the app distribution terms before release.
- **Three.js** (legacy particle cover WebView path): preserve MIT copyright /
  license notice from the bundled script header until the asset is removed from the APK.
