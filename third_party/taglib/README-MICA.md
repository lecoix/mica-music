# Mica TagLib fork

基于 [Kyant0/taglib](https://github.com/Kyant0/taglib) **1.0.6**（TagLib C++ 2.2.1）的 **vendored 模块**，供 Mica 扫描器使用。

**维护策略**：补丁留在 Mica 主仓 `third_party/taglib/`，不单独开库、不依赖 Kyant0 Maven 后续更新。上游若长期无响应，Mica 自行演进 JNI 扩展即可。

## 相对 Kyant0 1.0.6 的扩展

1. **`AudioProperties.bitsPerSample`**：JNI 通过格式专用 `Properties::bitsPerSample()` 暴露位深（FLAC、WAV、AIFF、MP4/ALAC、APE、WavPack、DSF、DSDIFF、Matroska 等）；未知时为 `0`。
2. **`TagLib.probeTrack(fd)`**：单次 `FileRef` 会话同时返回 `Metadata` + `AudioProperties`，避免 `getMetadata` / `getAudioProperties` 各开一次 fd。

## 依赖

| 依赖 | 方式 |
|------|------|
| TagLib C++ 2.2.1 | vendored 于 `src/main/cpp/taglib/` |
| [utfcpp](https://github.com/nemtrif/utfcpp) | **Git submodule**：`src/main/cpp/taglib/3rdparty/utfcpp` |

克隆 Mica 后若 submodule 为空：

```bash
git submodule update --init third_party/taglib/src/main/cpp/taglib/3rdparty/utfcpp
```

或克隆时使用：

```bash
git clone --recurse-submodules <mica-repo-url>
```

## 构建

作为 `:taglib` 子工程编入 Mica（`settings.gradle.kts` → `implementation(project(":taglib"))`）。需 Android NDK 与 CMake；ABI 与 app 一致（当前 **arm64-v8a**）。

`build.gradle.kts` 含 **perf** buildType（与 `:app` perf 变体对齐）。

```bash
.\gradlew :taglib:assembleDebug :app:assembleDebug
```

## 修改 JNI 时的注意点

- `utils.h` 中 `buildTrackProbe` 须定义在 `getPropertyMap` / `getPictures` **之后**（否则 C++ 编译报 undeclared identifier）。
- 扫描器接入见 `app/.../scanner/TagLibReader.kt` 与 `docs/LIBRARY_SCAN.md`。
