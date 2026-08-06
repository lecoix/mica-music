# Mica TagLib fork

基于 [Kyant0/taglib](https://github.com/Kyant0/taglib) **1.0.6**（TagLib C++ 2.2.1）的 vendored 模块，供 Mica 扫描器使用。

## 相对 upstream 的扩展

1. **`AudioProperties.bitsPerSample`**：JNI 通过格式专用 `Properties::bitsPerSample()` 暴露位深（FLAC、WAV、AIFF、MP4/ALAC、APE、WavPack、DSF、DSDIFF、Matroska 等）；未知时为 `0`。
2. **`TagLib.probeTrack(fd)`**：单次 `FileRef` 会话同时返回 `Metadata` + `AudioProperties`，避免 `getMetadata` / `getAudioProperties` 各开一次 fd。

## 构建

作为 `:taglib` 子工程编入 Mica，需 Android NDK 与 CMake。当前仅编 `arm64-v8a`（与 app 一致）。

上游 C++ 库源码位于 `src/main/cpp/taglib/`（TagLib v2.2.1）。

## 发布

长期可提 PR 回 Kyant0；Mica 侧通过 `implementation(project(":taglib"))` 引用，Maven 坐标 `io.github.kyant0:taglib:1.0.5` 已替换。
