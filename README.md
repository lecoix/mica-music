# Mica — HiFi 本地音乐播放器（Android）

> 云母氛围 + 极简尖角 · 发烧友定位 · Jetpack Compose

基于 [`DESIGN_SPEC.md`](./DESIGN_SPEC.md) 实现的 **本地 HiFi 播放器**：真实扫描曲库、软件解码播放、Room 持久化、内嵌歌词与 ALAC 支持。完整功能清单见 [`docs/TODO.md`](./docs/TODO.md)。

---

## 快速上手

### 环境要求

- **Android Studio**：Hedgehog (2023.1.1) 或更新
- **JDK**：17
- **Android SDK**：API 34（compileSdk）/ API 26+（minSdk）
- **设备**：Android 8.0+，**arm64-v8a**（项目仅编 64 位；含 FFmpeg native；是否“FFmpeg-only 出声路径”待讨论）

### 打开并运行

1. Android Studio → `File → Open`，选择**本仓库根目录**
2. 等待 Gradle Sync（首次会下载 Gradle 8.9 与依赖）
3. 连接 **arm64 真机**（或 arm64 模拟器），点击 `Run 'app'`

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
| **曲库扫描** | MediaStore 或 SAF；深度元数据、封面缓存与取色、增量同步 |
| **持久化** | Room；冷启动恢复队列与播放进度 |
| **播放** | 出声路径与后端路由**待详细讨论/暂未定稿**（历史实现曾为 FFmpeg → PCM → AudioTrack）；顺序/循环/随机；10 段软件 EQ |
| **歌词** | 内嵌 + 外挂 `.lrc`；三行歌词、展开歌词页 |
| **浏览** | 歌曲 / 歌手 / 专辑 / 最近 / 歌单 / 音乐库分析 |
| **播放页** | 多种背景；封面流（平行/复古）；沉浸模式；频谱条（可选） |
| **迷你栏** | 浮岛毛玻璃（BlurView）或极简 Hi‑Fi 通栏 |
| **界面** | 云母渐变主题、可配置强调色、全局动效（`MicaMotion`） |

---

## 项目结构（概要）

```
├── DESIGN_SPEC.md
├── docs/
│   ├── DOC_INDEX.md        # 文档索引与阅读顺序
│   ├── TODO.md
│   ├── MOTION.md
│   ├── PLAYER_PAGE_CONTRACT.md
│   ├── COVER_FLOW_IMPLEMENTATION.md
│   └── …
└── app/src/main/java/com/mica/music/
    ├── MainActivity.kt     # BlurTarget + 双 ComposeView
    ├── data/
    ├── media/
    └── ui/
```

`data/MockData.kt` 为早期占位，**主流程已不再使用**。

---

## 设计语言（新增页面请对照）

详见 [`DESIGN_SPEC.md`](./DESIGN_SPEC.md)。要点：全直角、留白分组、用户可选强调色、Hi‑Res 暖金独立语义。

---

## 常见问题

### Gradle Sync 慢或失败

可在 `settings.gradle.kts` 的 `repositories` 中增加国内镜像，并检查网络。

### 「Cannot find Kotlin Compose plugin」

请使用 Android Studio **Hedgehog 2023.1.1+**。

### 扫描不到歌

- 确认已授予音频权限
- 文件夹扫描需授权整个音乐目录
- 设置中可调最短时长、非 `IS_MUSIC` 条目

### ALAC / 无法播放

- 需 **arm64** 真机且含 `libmica_ffmpeg.so`
- 首次构建前可运行 `.\scripts\build-ffmpeg-arm64.ps1` 生成 assets 内 FFmpeg

---

## 后续规划

**以 [`docs/TODO.md`](./docs/TODO.md) 未勾选项为准**，主要包括：

- 列表项 → 播放页共享元素；共享转场架构整理
- 歌词双语与行切换动效；倒影模糊渐变等播放页背景
- 横屏播放页；原生解码优先（FFmpeg 兜底）
- 内置第二套字体；APK 瘦身（远期）

---

## 相关文件

- 文档索引：[`docs/DOC_INDEX.md`](./docs/DOC_INDEX.md)
- 设计规范：[`DESIGN_SPEC.md`](./DESIGN_SPEC.md)
- 动效与 Compose/View 分工：[`docs/MOTION.md`](./docs/MOTION.md)
- 功能清单：[`docs/TODO.md`](./docs/TODO.md)

---

**Made with care · 2026-06**
