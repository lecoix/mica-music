# Mica — HiFi 本地音乐播放器（Android）

> 云母氛围 + 极简尖角 · 发烧友定位 · Jetpack Compose

基于 [`DESIGN_SPEC.md`](./DESIGN_SPEC.md) 实现的 **本地 HiFi 播放器**：真实扫描曲库、Media3 播放、Room 持久化、内嵌歌词与 ALAC 支持。完整功能清单见 [`docs/TODO.md`](./docs/TODO.md)。

---

## 快速上手

### 环境要求

- **Android Studio**：Hedgehog (2023.1.1) 或更新
- **JDK**：17
- **Android SDK**：API 34（compileSdk）/ API 26+（minSdk）
- **设备**：Android 8.0+，**arm64-v8a**（项目仅编 64 位；含 FFmpeg native）

### 打开并运行

1. Android Studio → `File → Open`，选择本仓库根目录 `mica-android`（不要选上级 `d:\AI\2`）
2. 等待 Gradle Sync（首次会下载 Gradle 8.9 与依赖）
3. 连接真机或模拟器，点击 `Run 'app'`

命令行编译：

```bash
.\gradlew.bat :app:assembleDebug
```

### 首次使用

1. 授予**音频读取**权限（Android 13+ 为 `READ_MEDIA_AUDIO`）
2. 侧栏进入**设置**，选择 **MediaStore 全库扫描** 或 **指定文件夹（SAF）**
3. 扫描完成后在**歌曲 / 歌手 / 专辑**中浏览，点击曲目进入播放页
4. 底部**迷你播放器**可展开全屏播放；顶栏可**搜索**曲库

---

## 主要功能

| 模块 | 说明 |
|------|------|
| **曲库扫描** | MediaStore 或 SAF 文件夹；最短时长、非音乐条目、深度元数据（格式/采样率/码率、封面缓存与取色） |
| **持久化** | Room 保存扫描结果；冷启动恢复；**增量同步**（新增/更新/移除 + 摘要提示） |
| **播放** | Media3 ExoPlayer + 前台 `MediaService`；顺序/列表循环/单曲循环/随机 |
| **ALAC** | FFmpeg 解码（流式 AudioTrack 或转 FLAC 缓存，可在设置切换） |
| **歌词** | 内嵌 ID3/FLAC/APE 等；LRC 时间轴；播放页三行歌词、约 50ms 同步 |
| **浏览** | 歌曲、歌手（多艺术家 `/` 拆分）、专辑、最近播放、**音乐库分析** |
| **播放列表** | 查看队列、点击切歌、**拖拽排序**、删除 |
| **播放页** | 封面、Hi‑Fi 信息、进度条 seek；背景可选主题色 / 封面渐变 / 封面模糊 |
| **其它** | 播放次数、列表排序、顶栏搜索、侧栏导航 |

---

## 项目结构（概要）

```
mica-android/
├── DESIGN_SPEC.md          # UI/交互设计规范
├── docs/TODO.md            # 功能清单（已实现 / 待办）
├── README.md
└── app/src/main/java/com/mica/music/
    ├── MainActivity.kt
    ├── MainViewModel.kt
    ├── data/               # 曲库、播放控制、扫描、Room、偏好
    ├── data/local/         # Room：SongEntity、LibraryRepository
    ├── data/scanner/       # MediaStore、文件夹、元数据、歌词、封面
    ├── media/              # ALAC / FFmpeg 播放引擎
    └── ui/
        ├── theme/          # MicaTheme、云母渐变、播放页背景
        ├── components/     # SongRow、MiniPlayer、HiFiSeekBar 等
        ├── screens/        # Home、NowPlaying、Settings、LibraryAnalysis
        └── navigation/
```

`data/MockData.kt` 为早期 UI 占位，**主流程已不再使用**。

---

## 设计语言（新增页面请对照）

| # | 规则 | 实现 |
|---|------|------|
| 1 | 全 0dp 圆角 | `HifiShapes` → `RectangleShape` |
| 2 | 激活态 = 紫色 + 2dp 直线 | Tab / 侧栏 / 正在播放行 |
| 3 | Hi-Res = 金色圆点 + 文字 | `HiResIndicator` |
| 4 | 开关 =「开/关」文字 + 紫点 | `TextToggle` |
| 5 | 分组用留白，少用卡片边框 | 以 `Spacer` 与列表分隔为主 |

---

## 常见问题

### Gradle Sync 慢或失败

可在 `settings.gradle.kts` 的 `repositories` 中增加国内镜像（阿里云等），并检查代理/网络。

### 「Cannot find Kotlin Compose plugin」

请使用 Android Studio **Hedgehog 2023.1.1+**（支持 Kotlin 2.0 Compose Compiler）。

### 扫描不到歌

- 确认已授予音频权限
- Android 11+ 若用文件夹扫描，需在系统文件选择器里**授权整个音乐目录**
- 设置中可调最短时长、是否包含非 `IS_MUSIC` 条目

### ALAC 无法播放

- 需 **arm64** 真机且 APK 内已包含 `libmica_ffmpeg.so`（由 `syncFfmpegNative` 从 assets 拷贝）
- 可在设置中切换 ALAC 播放方式（流式 / 转 FLAC 缓存）

---

## 后续规划

详见 [`docs/TODO.md`](./docs/TODO.md)，主要包括：

- 外挂 `.lrc`、歌词双语与切换动效
- 全局界面动效（沉浸模式、页面/侧栏/浮层过渡等）
- 主页迷你播放器底栏：封面避开系统圆角裁切
- 播放页频谱条（标准模式：进度条伴生细条；封面底边进度：封面下缘律动条）
- 分享、横屏播放页
- 可配置主题色；播放页背景候选：流光溢彩、倒影模糊渐变等
- 设置可选第二套内置字体（须可再分发授权，OFL/Apache 等）
- 从子集「加入队列」而不替换整库队列
- 远期（低优先级）：APK / 运行时占用瘦身

---

## 相关文件

- 设计规范：[`DESIGN_SPEC.md`](./DESIGN_SPEC.md)
- 功能清单：[`docs/TODO.md`](./docs/TODO.md)

---

**Made with care · v0.1.0 · 2026-05**
