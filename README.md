# Mica — HiFi 本地音乐播放器（Android）

> 极简直角 · 多种主题 · 自定义

一款不怎么专业的 **本地 HiFi 播放器**，特色包括：多种主题样式、丰富的自定义选项、内嵌/外挂歌词优先级自由调整、USB独占功能与 ALAC/DSF/APE 支持。

---
<p align="center">
  <img src="docs/images/ss01.jpg" alt="Screenshot 1" width="160" style="border-radius:26px;"/>
  <img src="docs/images/ss02.jpg" alt="Screenshot 2" width="160" style="border-radius:26px;"/> 
  <img src="docs/images/ss03.jpg" alt="Screenshot 3" width="160" style="border-radius:26px;"/> 
  <img src="docs/images/ss04.jpg" alt="Screenshot 4" width="160" style="border-radius:26px;"/> 
  <img src="docs/images/ss05.jpg" alt="Screenshot 5" width="160" style="border-radius:26px;"/> 
</p>
<p align="center">
  
  <img src="docs/images/ss06.jpg" alt="Screenshot 6" width="160" style="border-radius:26px;"/>
  <img src="docs/images/ss07.jpg" alt="Screenshot 7" width="160" style="border-radius:26px;"/> 
  <img src="docs/images/ss08.jpg" alt="Screenshot 8" width="160" style="border-radius:26px;"/> 
  <img src="docs/images/ss09.jpg" alt="Screenshot 9" width="160" style="border-radius:26px;"/> 
  <img src="docs/images/ss10.jpg" alt="Screenshot 10" width="160" style="border-radius:26px;"/> 
</p>

---
### 环境要求

- **Android Studio**：Hedgehog (2023.1.1) 或更新
- **JDK**：17
- **Android SDK**：API 35（compileSdk）/ API 26+（minSdk）
- **设备**：Android 8.0+，**arm64-v8a**（项目目前仅编 64 位；Media3 FFmpeg 扩展包含在工程内）

### 自行编译

1. Android Studio → `File → Open`，选择**本仓库根目录**
2. 若未递归克隆，初始化 TagLib 的 utfcpp submodule：`git submodule update --init third_party/taglib/src/main/cpp/taglib/3rdparty/utfcpp`
3. 等待 Gradle Sync（首次会下载 Gradle 8.9 与依赖）
4. 连接 **arm64 真机**（或 arm64 模拟器），点击 `Run 'app'`

命令行编译：

```bash
.\gradlew.bat :app:assembleDebug
```

质量门（编译 + Lint + 单测 + 截图比对，无需真机）：

```powershell
.\gradlew :app:micaCheck --no-configuration-cache
```

Windows PowerShell 5.1 若看到中文乱码，先在当前会话启用 UTF-8：

```powershell
. .\scripts\use-utf8-console.ps1
```


---

## 主要功能

| 模块 | 说明 |
|------|------|
| **播放页主题** | 标准 / 自定义 / **粒子封面**（GLES）/ 平行封面带 / 复古立体 / **拍立得回忆** |
| **播放页背景** | 主题色、封面渐变、封面模糊、流光溢彩 |
| **播放页其他行为** | 沉浸模式、频谱条（可选）、封面底边进度、共享封面转场 |
| **播放** | USB独占功能，支持Native DSD；Jellyfin FFmpeg 扩展解码 ALAC/DSF/APE；音频管线协调 + offload 失速熔断；10 段软件 EQ；通知歌词（可选） |
| **曲库扫描** | MediaStore 或 SAF；深度元数据、封面缓存与取色、增量同步 |
| **持久化** | Room 曲库与歌单（schema v17）；`ServicePlaybackStateStore` 冷启动恢复队列与进度（外部队列仅在权限可存续时恢复） |
| **歌词** | 内嵌 + 外挂 `.lrc` `逐字歌词` `TTML歌词`；三行歌词、展开歌词页、双语/逐字等设置 |
| **浏览** | 歌曲 / 歌手 / 专辑 / 最近 / 歌单 / 音乐库分析 |
| **迷你栏** | 浮岛毛玻璃（BlurView）/ 极简 Hi‑Fi 底栏 |
| **界面** | 可自定义配置强调色&背景色、自定义背景图片、全局动效（`MicaMotion`）、横屏模式 |

---

## 项目结构（概要）

```
├── CONTEXT.md              # 领域词汇
├── DESIGN_SPEC.md
├── REASONIX.md             # AI/工具速览
├── docs/
│   ├── DOC_INDEX.md
│   ├── TODO.md
│   ├── PLAYER_PAGE_CONTRACT.md
│   ├── COVER_FLOW_IMPLEMENTATION.md
│   └── …
└── app/src/main/java/com/mica/music/
    ├── MainActivity.kt     # BlurTarget + 双 ComposeView
    ├── audio/              # 跨层共享的音频值模型/常量
    ├── data/               # 曲库、Room、偏好与持久化
    ├── playback/           # App 侧播放 facade/runtime/coordinators
    ├── media/              # Media3 service、音频管线与 transport
    ├── usb/                # 跨层共享的 USB identity
    └── ui/
```

`data/MockData.kt` 为早期占位，**主流程已不再使用**。

`playback/` 集中持有 `PlayerController`、`PlaybackRuntime`、connection、queue/timeline/tuning/statistics coordinator 与队列同步逻辑；`data/playback/ServicePlaybackStateStore` 只负责播放恢复快照持久化。`data/` 不得反向依赖 `media/` 或 `playback/` implementation package，该约束由 `DataLayerDependencyStructureTest` 守卫。`media/` 继续持有 `AudioPipelineCoordinator`、`AudioOffloadCircuitBreaker`、Media3 service 与 USB transport runtime；跨层共用的 `AudioQualityMode` / `EqBandConstants` 与 `UsbStableIdentity` 分别位于 neutral `audio/` / `usb/`。

---

## 开源与第三方代码

Mica 的 USB 独占播放底层并非完全从零实现。`third_party/sylvakru-usb-transport/`
包含从 SylvaKru USB-exclusive 实现直接复制、拆分重组或按行为适配的代码。
当前审计参考为 `huya688zdx/sylvakru` commit
`3f2578692499e403d7eddc6fdbe52d1b6a1b2206`；该参考 fork 的 README 说明其基于
原版 `AfalpHy/sylvakru`。相关代码按 Apache License 2.0 使用和再分发。

模块内保留参考许可证全文、归属/修改声明与详细 provenance：
[`third_party/sylvakru-usb-transport/README.md`](./third_party/sylvakru-usb-transport/README.md)、
[`third_party/sylvakru-usb-transport/NOTICE`](./third_party/sylvakru-usb-transport/NOTICE)。
项目级第三方声明见 [`docs/OPEN_SOURCE_NOTICES.md`](./docs/OPEN_SOURCE_NOTICES.md)。
USB 的逐函数来源/适配分类见
[`docs/USB_REFERENCE_FUNCTION_AUDIT.md`](./docs/USB_REFERENCE_FUNCTION_AUDIT.md)。

---


## 相关文件

- 文档索引：[`docs/DOC_INDEX.md`](./docs/DOC_INDEX.md)
- 领域词汇：[`CONTEXT.md`](./CONTEXT.md)
- 设计规范：[`DESIGN_SPEC.md`](./DESIGN_SPEC.md)
- 动效与 Compose/View 分工：[`docs/MOTION.md`](./docs/MOTION.md)
- 功能清单：[`docs/TODO.md`](./docs/TODO.md)
- 近期功能与验收边界：[`docs/CURRENT_FEATURE_STATUS.md`](./docs/CURRENT_FEATURE_STATUS.md)
- 设置矩阵：[`docs/SETTINGS_AUDIT_MATRIX.md`](./docs/SETTINGS_AUDIT_MATRIX.md)
- 测试：[`docs/TESTING.md`](./docs/TESTING.md)

---
#爱发电：https://ifdian.net/a/lwcoz
**Made with AI · 2026-08**
