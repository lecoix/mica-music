# 切歌卡顿 / 闪退 — 测试问题与分支改进记录

> **分支**：`wip`（已从 `experiment/coverflow-prebaked-reflection` 合入预烘焙 + 诊断 cherry-pick）  
> **当前版本**：`0.1.7-diag5`（`versionCode 7`，工作区未提交）  
> **整理日期**：2026-06-14  
> **测试设备**：小米 25102RKBEC + 漫步者 Comfo Clip 蓝牙，Android 16；开发者自测机（realme）相对流畅  
> **WIP 归属**：本文档随性能诊断 WIP 一并合并，**不单独留在实验分支**

---

## 0. WIP 与实验分支的关系（合并时注意）

| 内容 | 在哪 | 说明 |
|------|------|------|
| **主体 WIP** | `stash@{0}`：`wip: pause while testing coverflow prebaked reflection` | 未 `pop`；Settings、MainActivity、MusicLibrary 等大包仍在 |
| **commit 2** `1c24423` | 已提交 | **来自 WIP**：从 stash 未跟踪树捞出 `DiagnosticLog.kt` + `TrackSwitchPerformance.kt`；另补预烘焙编译常量 |
| **commit 3** `94d1d7f` | 已提交 | **来自 WIP**：从 stash 摘诊断接线（`MicaApp.install`、`AboutScreen` 导出、`FileProvider`、`PlayerController` 基础日志） |
| **commit 1** `b593f9e` 预烘焙 | 已提交 | 实验用；**合并回 WIP**，不长期留在独立实验线 |
| **diag4/diag5 增量** | 工作区未提交 | stash 之后新加（`DecodePerformance`、`AudioEnvironmentDiagnostics` 等） |
| **本文档** | `docs/PERFORMANCE_INVESTIGATION.md` | **随 WIP 合并**，与诊断代码同批进主开发线 |

恢复 WIP 时建议顺序：`stash pop` → 解决与 2/3/工作区的冲突 → 把预烘焙 + 本文档 + diag 增量一并收进同一分支再提交。

---

## 1. 背景

测试人员在封面流 + 主题背景下反馈：**切歌卡顿、发热（约 42°C）、曾出现黑屏**。开发者机型的体感明显好于测试机。

本轮工作分两条线并行：

1. **止血与定位**：加诊断、修已确认闪退、做可观测的性能实验。
2. **对标调研**：阅读 Icey、PixelPlayer、LunaBeat（APK 反查），确认行业常见做法与 Mica 架构差异。

**结论已足够支撑动刀方向**：在「标准主题 + 主题色 + 标准封面」（最轻 UI）下仍卡，根因在**解码/拷贝管线**，不是封面 Canvas 动画。

---

## 2. 测试反馈的问题清单

### 2.1 已确认并修复（本分支）

| 问题 | 现象 | 根因 | 处理 |
|------|------|------|------|
| 封面流闪退 | 启用复古/平行封面流时崩溃 | Coil 返回 `Bitmap.Config.HARDWARE`，预烘焙倒影 `Canvas` 无法绘制 | `CoverFlowReflectionBake` 拷贝为 `ARGB_8888` |
| 歌词区越界 | 切歌或歌词索引异常时崩溃 | `NowPlayingCompactLyrics` 的 `displayIndex` 未夹紧 | `coerceIn(0, lyrics.lastIndex)` |
| 诊断不可用 | 测试无法导出日志 | 缺少导出入口与文件共享配置 | `DiagnosticLog` + 关于页「导出诊断日志」+ `FileProvider` |

### 2.2 已定位、尚未根治（P0）

| 问题 | 证据 | 当前状态 |
|------|------|----------|
| **每切歌整文件拷贝** | diag5：`decode-input-copy` 对 93MB FLAC 耗时 **735ms**，占 pipeline 大头；小 mp3 亦 36–103ms | `copyUriToTemp` 仍对每首执行；`releaseSession()` 删除 temp 导致 `reused=false` 永远命中不了 |
| **连切无节流** | diag5：快速 `button-next` 出现多条 `superseded` 记录，重活叠加 | 仅有 `TrackSwitchPerformance.finish("superseded")` 观测，**未**实现请求作废 / 合并 |
| **全格式走 FFmpeg 自建管线** | 几乎总走 `startAlacStream`；ExoPlayer 仅 MediaSession 壳 | `MicaCompositePlayer` 已有 ExoPlayer，**未**将 MP3/FLAC 路由到直读播放 |
| **标准模式音频不延后** | `audioStartDeferMs()` 仅在 `coverFlowStageActive` 时返回 120ms；STANDARD 为 0 | 最轻 UI 下切歌与解码同帧硬碰 |

### 2.3 已定位、待处理（P1）

| 问题 | 证据 | 说明 |
|------|------|------|
| AudioTrack 无法复用 | diag3：244 次 `audio-track-create`，0 reuse | 蓝牙输出格式跳变（采样率/位深变化）导致复用条件不满足；已加复用逻辑但日志显示几乎不命中 |
| 封面发光取色/模糊 | diag3：`COVER_GLOW` + 取色时 `maxFrame` 最高 826ms | diag5 在 STANDARD+THEME 下 `coverDraws=0`，**已排除为主因**；封面流场景仍可能加重 |
| 系统音效干扰 | diag4/5：`registeredEffects` 含 **Dolby、MiSound、EqualizerBundle** | 第三方音效 App 未安装，但小米系统音效仍注册；影响程度未量化 |
| 蓝牙路由抖动 | diag3：多轮 `devices-removed` | 已加 `BluetoothAudioDiagnostics`，未改播放策略 |
| 发热 / 黑屏 | 测试口述 42°C、曾黑屏 | 与重 I/O + 持续 FFmpeg 解码 + 蓝牙 + 系统音效叠加一致；无单独崩溃栈，需结合 `ApplicationExitInfo` 与导出日志继续跟 |

### 2.4 实验性优化、体感有限

| 实验 | 结果 |
|------|------|
| 封面流倒影**预烘焙**（`CoverFlowReflectionBake`） | 功能正常；`maxDraw` 约 0.47ms，对卡顿**体感帮助不明显** |
| 封面流活跃时 **音频延后 120ms** | 仅 cover flow 舞台生效；测试人员在标准模式下无效 |
| **AudioTrack 同格式复用** | 代码已写；实际曲库格式多样 + 蓝牙跳变，日志几乎全是 create |

---

## 3. 诊断版本与关键日志结论

| 版本 | 构建标识 | 重点 |
|------|----------|------|
| diag3 | `0.1.5-diag3` | 82 次切歌，封面流 PAUSE_FOLD + THEME/COVER_GLOW；`maxFrame` 平均 ~283ms；确认 UI 帧与蓝牙/AudioTrack 行为 |
| diag4 | `0.1.6-diag4` | 新增 `AudioEnvironmentDiagnostics`；发现小米系统杜比/MiSound |
| diag5 | `0.1.7-diag5` | 新增 `DecodePerformance` 全链路打点；**标准主题 + 主题色 + STANDARD 封面** 仍卡，锁定 **copy 为主因** |

### diag5 典型一条（93MB FLAC）

```
decode=release=… | copy=735ms | ffmpeg=42ms | pipeline=846ms
coverDraws=0, coverFlow=STANDARD, stage=false
```

### 两个易混淆设置（测试沟通用）

| 用户说法 | 实际设置 | 性能影响 |
|----------|----------|----------|
| 「标准主题」 | `PlayerCoverFlowMode.STANDARD`（无封面流） | 无 cover flow 绘制 |
| 「主题色」背景 | `PlayerLowerBackgroundMode.THEME`（不运行时取色/模糊） | 无 COVER_GLOW 重活 |

二者独立；「标准 + 主题色」= 当前最轻 UI 组合。

---

## 4. 我们已做的努力（按时间线）

### 4.1 可观测性

- **`DiagnosticLog`**：环形面包屑、崩溃落盘、关于页一键导出分享。
- **`TrackSwitchPerformance`**：切歌后 1.5s 帧采样（avg/max frame、掉帧估计）、封面 Canvas 耗时、阶段时间线、`superseded` 检测；汇入 `coverFlow` / 背景模式上下文。
- **`DecodePerformance`**（diag5）：`release` → `input-copy` → `ffmpeg` → `pipeline-done` → `audio-track` 全链路毫秒打点。
- **`AudioEnvironmentDiagnostics`**（diag4）：已安装音效 App、系统 `registeredEffects`、`activePlaybackCount` 等（仅公开 API）。
- **`BluetoothAudioDiagnostics`**：输出设备增删、播放时路由快照。

### 4.2 播放与解码实验

- `PlayerController.playSong`：封面流舞台活跃时 UI 先更新，`startAlacStream` 延后 120ms。
- `AlacPcmPlayer`：同格式 `AudioTrack` 复用 + 创建/复用诊断日志。
- `AlacAudioTrackEngine`：`copyUriToTemp` 增加 `reused` / `sizeMB` 日志；pipeline 取消/完成打点。

### 4.3 UI 与稳定性

- 封面流倒影预烘焙（减少每帧 `saveLayer`）。
- HARDWARE 位图兼容、歌词 `displayIndex` 越界修复。
- 封面加载 / 取色 / 模糊背景 / 封面流拖动等处接入 `TrackSwitchPerformance.mark`，便于对照 UI 与解码。

### 4.4 参考项目调研（未改代码）

| 项目 | 核心做法 | 对 Mica 的启示 |
|------|----------|----------------|
| [Icey](https://github.com/TroilOryan/Icey) | Flutter → ExoPlayer，`content://` 直读，队列 `seek` | 常见格式不应 copy；不能照搬（无自研 ALAC） |
| [PixelPlayer](https://github.com/theovilardo/PixelPlayer) | Media3 ExoPlayer + FFmpeg 扩展；队列 `seekTo` 复用；token 作废；小米 Android 16 关 offload | 主路径应对齐 ExoPlayer；设备特化值得借鉴 |
| [LunaBeat](https://github.com/2755337087/LunaBeat) | 同源场景（歌词+ALAC+TagLib）；ExoPlayer 主路径；ALAC 直解失败才 `copyUriToTempFile`；DSD 专线 | **与 Mica 最接近**；应用「常见格式 ExoPlayer、特殊格式才 copy/转码」 |

三家共识：**流畅切歌 = URI 直读 + 队列 seek + 快切作废**。Mica 反向模式：**每首 copy + 重启 FFmpeg/AudioTrack**。

---

## 5. 根因排序（当前共识）

1. **P0** — 每切 `copyUriToTemp` + `releaseSession` 删缓存（diag5 实测 copy 为大头）
2. **P0** — 连切无节流，快速切歌叠加重活
3. **架构** — `playSong` 几乎总走 `startAlacStream`，ExoPlayer 未承担常见格式出声
4. **P1** — 蓝牙格式跳变 → AudioTrack 无法复用
5. **P1** — COVER_GLOW 取色/模糊（标准模式已排除）
6. **环境** — 小米杜比/MiSound、蓝牙、可能录屏

---

## 6. 建议的下一步（尚未实施）

| 阶段 | 内容 | 参考 |
|------|------|------|
| 止血（2–3 天） | temp 按 `songId` 缓存 + 连切请求 serial 作废 | LunaBeat / PixelPlayer |
| 主路径（3–5 天） | MP3/FLAC/AAC → `MicaCompositePlayer` ExoPlayer 直读 | 三家共识 |
| ALAC 专线（+2–3 天） | 直解探测 + 按需转码缓存，非每切 copy | LunaBeat |
| 设备（0.5 天） | 小米 Android 16 ExoPlayer offload 策略 | PixelPlayer |

安装测试包：`.\gradlew.bat installPerf --no-configuration-cache`

---

## 7. 本分支全部改进清单

### 7.1 已提交（`f5616ed` 之后 3 个 commit）

| Commit | 摘要 | 与 WIP 关系 |
|--------|------|-------------|
| `b593f9e` | **封面流倒影预烘焙**：新增 `CoverFlowReflectionBake.kt` + 单测；`CoverFlowCarouselView` 改为绘制烘焙位图，避免每帧 `saveLayer` | 实验产物，**待合入 WIP** |
| `1c24423` | 从 stash 捞出 `DiagnosticLog` + `TrackSwitchPerformance`；补预烘焙编译常量 | **WIP 里本来就有** |
| `94d1d7f` | 从 stash 摘诊断接线：`MicaApp.install`、关于页导出、`FileProvider`、`PlayerController` 切歌日志 | **WIP 里本来就有** |

### 7.2 工作区未提交（当前 `git status`）

> 版本号：`0.1.7-diag5`。下列为相对已提交内容的增量。

#### 新增文件

| 文件 | 作用 |
|------|------|
| `util/DecodePerformance.kt` | 解码链路分阶段耗时，汇入切歌 summary |
| `util/AudioEnvironmentDiagnostics.kt` | 系统/第三方音效环境快照 |
| `util/BluetoothAudioDiagnostics.kt` | 蓝牙/有线设备变化与播放路由 |

#### 播放 / 解码

| 文件 | 改动要点 |
|------|----------|
| `PlayerController.kt` | 切歌 `TrackSwitchPerformance.begin`；封面流 120ms 音频延后；`DecodePerformance.bindSwitch`；`cancelPendingAudioStart` |
| `AlacAudioTrackEngine.kt` | `decode-session-release` / `decode-input-copy` / pipeline 完成/取消打点；copy 日志含 `reused`/`sizeMB` |
| `AlacFfmpegHelper.kt` | FFmpeg 启动/就绪/输入拷贝分阶段 `DecodePerformance.mark` |
| `AlacPcmPlayer.kt` | 同格式 `AudioTrack` 复用；`audio-track-create`/`reuse` 日志；蓝牙路由记录 |

#### UI / 封面流

| 文件 | 改动要点 |
|------|----------|
| `CoverFlowReflectionBake.kt` | HARDWARE → ARGB_8888 兼容（闪退修复） |
| `CoverFlowCarouselView.kt` | 预烘焙集成 + 大量 `TrackSwitchPerformance` 阶段标记（加载/动画/拖动） |
| `NowPlayingCompactLyrics.kt` | `displayIndex` 越界夹紧（闪退修复） |
| `NowPlayingCoverSection.kt` | 向 `TrackSwitchPerformance` 上报视觉上下文 |
| `NowPlayingScreen.kt` | 播放页 visual context 更新 |
| `PlayerPageState.kt` / `CoverGestureCoordinator.kt` | 配合诊断上下文与手势 |
| `RememberCoverColor.kt` | 取色缓存/采样阶段打点 |
| `BlurredCoverBackground.kt` | 模糊背景构建阶段打点 |

#### 应用壳

| 文件 | 改动要点 |
|------|----------|
| `MicaApp.kt` | 安装 `BluetoothAudioDiagnostics`、`AudioEnvironmentDiagnostics` |
| `DiagnosticLog.kt` | 导出时附加音频环境；与 diag4/5 配套 |
| `TrackSwitchPerformance.kt` | 扩展：visualContext、`audioStartDeferMs`、decode summary、mark 去重、`recordCoverDraw` |
| `app/build.gradle.kts` | `0.1.7-diag5` / `versionCode 7` |

#### 本地参考克隆（未纳入产品代码）

- `.icey-ref/`、`.pixelplayer-ref/`、`.lunabeat-ref/` — 仅供调研，**不应提交**。

---

## 8. 相关文件索引

| 路径 | 作用 |
|------|------|
| `media/AlacAudioTrackEngine.kt` | `play()` → `releaseSession` + `copyUriToTemp` + 解码 |
| `media/AlacFfmpegHelper.kt` | FFmpeg 流式解码 |
| `media/AlacPcmPlayer.kt` | AudioTrack 创建/复用 |
| `data/PlayerController.kt` | `playSong` → `startAlacStream` |
| `media/MicaCompositePlayer.kt` | ExoPlayer + ALAC 桥接（待扩展主路径） |
| `util/TrackSwitchPerformance.kt` | 切歌 UI/帧诊断 |
| `util/DecodePerformance.kt` | 解码耗时诊断 |
| `util/AudioEnvironmentDiagnostics.kt` | 音效环境 |
| `ui/screens/player/view/CoverFlowReflectionBake.kt` | 倒影预烘焙实验 |

---

## 9. 未决事项与依赖

- [ ] 测试人员用 **diag5** 在「标准 + 主题色 + STANDARD」下复测并导出日志（确认 copy 仍占主导）。
- [ ] 产品确认：是否批准 **P0 止血**（temp 缓存 + 连切节流）与 **ExoPlayer 主路径** 分阶段上线。
- [ ] 黑屏个案：若有 `ApplicationExitInfo` 或 tombstone，需与 `mica-diagnostics/*.txt` 对照。
- [ ] LunaBeat 源码未公开，ALAC 专线细节来自 APK 字符串反查，实施时需以 Mica 实测为准。
