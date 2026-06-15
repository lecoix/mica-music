# 切歌卡顿 / 闪退 — 测试问题与分支改进记录

> **分支**：`wip`（已从 `experiment/coverflow-prebaked-reflection` 合入预烘焙 + 诊断 cherry-pick）  
> **当前版本**：`0.1.8-hybrid7`（`versionCode 14`，工作区未提交）
> **整理日期**：2026-06-14  
> **测试设备**：小米 25102RKBEC + 漫步者 Comfo Clip 蓝牙，Android 16；开发者自测机（realme）相对流畅  
> **WIP 归属**：本文档随性能诊断 WIP 一并合并，**不单独留在实验分支**

---

## 0. 2026-06-14 混合播放架构落地状态

### 2026-06-15 hybrid7：撤销音频延迟并阻止旧曲回写

- hybrid6 在 UI 选中新曲后延迟 50/120ms 才向 Service 发送切歌命令。播放中的旧曲会在
  这段窗口继续发布状态，Controller 又把旧索引同步回 UI，导致封面切过去几帧后回弹。
- 切歌命令恢复为立即发送。Controller 在命令发出前记录目标 song ID，目标到达前忽略
  旧曲的索引事件；确认 MediaSession 当前项已是目标曲后再恢复正常状态同步。

### 2026-06-15 hybrid6：音频启动隔离实验（已撤销）

- 旧版与新版使用相同的封面模糊背景，因此背景相关性不能解释重构回归。
- 关键差异是旧 FFmpeg 路径的输入复制与启动主要在后台线程；新版在 UI 状态更新后
  立即执行 `MediaController.seekTo + play`，Media3 准备与切歌首帧竞争主线程。
- 曾尝试标准模式延后 50ms、封面流舞台延后 120ms；真机发现播放中的旧曲会在延迟窗口
  把 UI 索引写回，因此 hybrid7 已完整撤销该延迟策略。

### 2026-06-15 hybrid4/hybrid5：软件切歌回归定位

- 小米 Android 12 日志中，FFmpeg 启动仅需 2–7ms；每首 24–60MB SAF 文件复制
  耗时 51–180ms。旧架构同样执行整首复制且该设备流畅，因此复制不是本次回归的
  根因，只是新架构下可以消除的一段并发成本。
- 外部存储文档 URI 在确认真实文件可读时改为直接交给 FFmpeg；其他 provider
  仍使用有界输入缓存兜底。
- 软件播放切到 Media3 时暂停并清空旧 writer，但保留已初始化的 AudioTrack；
  后续相同输出格式的软件曲目可以复用，避免重新建立音频会话。
- 新架构的实际新增负担是软件状态回调反复重建并广播完整的 256 首自定义 timeline。
  hybrid5 改为只在队列、索引或已知时长变化时刷新 timeline；buffering、播放/暂停
  和位置变化只更新播放状态。

本轮已将播放主路径从“所有格式统一 FFmpeg”翻转为：

- MP3、FLAC、AAC/MP4、Opus、Ogg/Vorbis、WAV 默认由 Media3 直接读取原始 URI。
- ALAC 先尝试 Media3；仅解析器、解码器初始化或解码错误允许一次 FFmpeg 回退。
- DSD 直接进入软件解码；有线/内置优先 24-bit/176.4kHz，蓝牙限制为 48kHz。
- 每次播放都有递增 request ID 和 source revision，旧软件解码回调不能覆盖新请求。
- 连续三首失败后暂停，防止损坏曲库造成无限跳歌。
- ALAC 扫描期预转 FLAC 路径已移除，同一源不再因缓存状态改变后端。

同时完成：

- 输入缓存按源加锁、原子 `.part` 写入、主动关闭被取消的 provider 流、4 首/512MB LRU。
- EQ 每声道独立滤波，支持 PCM 16/24/32/float，加入自动 preamp、dither 和限幅。
- EQ 关闭使用 HIFI 模式并允许 Media3 offload；EQ 开启强制 DSP 并关闭 offload。
- 软件后端由 Service 统一处理音频焦点和 `AUDIO_BECOMING_NOISY`。
- 播放控制器提升为 Application 进程级实例，Activity/ViewModel 销毁不再停止软件播放。

真机冒烟结果（小米 `22081212C`，Perf 构建）：

- 应用启动无 Mica 崩溃/ANR。
- FLAC 已确认日志为 `route backend=MEDIA3 reason=platform-format`。
- HIFI 日志为 `dsp=false offload=true`。
- ALAC 在设备解码器报错后已确认可回退 FFmpeg，AudioTrack 正常建立且无线程崩溃；Media3 成功路径、DSD、蓝牙断开、后台划卡仍需按 `docs/TESTING.md` 人工验收。
- 2026-06-15 realme RMX8899 连续切歌日志中出现 60 次相同的
  `c2.qti.alac.sw.decoder` 稳定失败。每个新 ALAC source 都先等待 Media3 失败，
  再进入软件路径，AudioTrack 建立相对点击平均约 278ms，且错误切换附近出现
  77–145ms 长帧。现增加进程级保护：同一具名 ALAC decoder 在两个不同
  source revision 上失败后，本次进程后续 ALAC 直接走 Software；单文件损坏
  和未具名的普通解码错误不会开启该保护，应用重启后会重新探测 Media3。
- 2026-06-15 Xiaomi 22081212C 后续日志确认进程级 ALAC 保护按预期在两个
  source 失败后生效，但普通 FLAC/MP3 仍有明显卡顿。定位到
  `TrackSwitchPerformance.begin()` 每次切歌都在主线程调用完整
  `AudioEnvironmentDiagnostics`，重新枚举已安装应用、系统音效、运行进程
  和播放配置，单次阻塞约 25–194ms。逐曲环境扫描已删除，环境信息仅在应用
  启动和用户导出诊断时采集。软件曲目切换同时保留同格式 AudioTrack，避免
  每首都销毁并重建设备输出。
- `hybrid2 (9)` 日志确认入口响应已降到约 1ms，AudioTrack 复用开始生效，
  但普通 FLAC 仍可出现 100ms 以上长帧。进一步定位到播放页重复读取完整封面、
  二次解码并生成 Palette；该颜色在扫描阶段已经写入 `coverColorArgb`。
  播放时二次取色已删除，直接使用扫描结果。封面流预热也从前后 7 张封面和
  7 张背景的串行阻塞缓存，收敛为相邻两张的非阻塞异步预取。

独立代码审查后的收口修复：

- 软件解码结果改为 generation 校验后原子提交，旧任务只能清理自己的 FFmpeg、PCM 和输入 lease。
- Media3 错误处理校验当前 backend、media ID、source revision 和播放器当前错误，旧错误不再触发新曲回退。
- MediaController 增加断连回调；Service 停止但进程仍存活时可重新建立连接。
- 输入缓存增加引用 lease，LRU 不得删除正在解码或播放会话持有的源文件。
- 软件后端音频焦点申请失败时立即保持暂停，不允许与其他播放器叠播。
- limiter 改为连续软拐点；连续失败计数仅在实际播放位置超过 1 秒后清零。
- FFmpeg stdout 与 stderr 已分离；ALAC/DSD 裸 PCM 通过 stdout 直接写入 AudioTrack，不再创建增长中的 PCM 文件。
- 软件 seek 会关闭旧 stdout、销毁旧 FFmpeg，并使用 `-ss` 从目标位置启动新管道。

此前开发样本中，stdout 迁移后的 ALAC 路径约在 618ms 出现过 `audio-playing`。该结果不能代表当前打包产物已经验收；2026-06-15 的独立 Perf 真机回归发现，APK 内 FFmpeg 不包含 `pipe` 协议，当前软件回退会失败，详见下一节。

### 2026-06-15 独立 Perf 真机回归

设备为 Xiaomi `22081212C`，Android 12 / API 31。为避免覆盖现有签名不同的正式测试包，使用
`-Pmica.qaSideBySide=true` 构建并行安装的 `com.mica.music.qa`。

已通过：

- MediaStore 扫描识别 533 首、约 22.0 GB，包含 FLAC、MP3 和 ALAC。
- 普通 FLAC 由 Media3 播放，队列 533 首，未启动 FFmpeg 进程。
- HIFI 诊断为 `dsp=false offload=true`。
- Activity 退到后台后，播放位置持续前进，MediaSession、前台 Service 和媒体通知保持。
- 进程终止后恢复同一首、533 首队列和约 26.97 秒位置，且未自动续播。
- 恢复后系统媒体播放/暂停按键有效，位置连续，无 Mica 崩溃或 ANR。

发现：

1. **P0：当前 APK 内 FFmpeg 不支持 stdout pipe。** ALAC 的 Media3 解码失败后约 712ms 正确触发一次
   SOFTWARE 回退，但 FFmpeg 报 `Error opening output pipe:1: Protocol not found`，随后跳到下一首 FLAC。
   在重新编译 FFmpeg 并启用 `pipe` protocol 前，ALAC 回退和 DSD 软件播放均不可交付。
2. **P1（已修复并通过真机确认）：嵌套权限异常被分类为 UNKNOWN。** 分类器现会遍历 cause 链，
   将 `UnexpectedLoaderException -> SecurityException` 映射为 `SOURCE_PERMISSION`；权限错误和取消请求
   不再触发自动跳歌。
3. **P1（已修复并通过真机确认）：恢复后的 MediaSession 状态为 IDLE。** 恢复流程现按
   `playWhenReady=false -> seekTo -> prepare` 执行，恢复后稳定报告 PAUSED，并保持不自动续播。
4. **性能观察：冷扫描较重。** 无可复用元数据缓存时，35 秒仅完成 94/533，约 125 秒完成；
   扫描期间出现多次 50-100MB 级大对象回收。播放性能验收必须避开扫描并单独优化扫描内存峰值。
5. **P2：进入软件后端时迷你播放栏短暂显示错误曲目。** 用户选择目标曲后，迷你播放栏会先从
   列表第一首切换到目标曲。音频最终曲目正确，优先级较低；后续应让 Service 在软件后端准备前
   原子发布目标索引和元数据，避免中间 timeline 默认到索引 0。

本轮未形成有效结论：

- 设备无 DSD 测试文件、USB DAC、已连接蓝牙耳机或有线耳机。
- API 31 无法验证 API 33+ direct playback capability；实现已接入，仍需 API 33+ 与 USB DAC 真机验收。
- MIUI 的 `media_session dispatch next` 自身报空 package，列表密集点击也只登记最后一次有效选择，
  因此“连续快速切歌 20 次”仍需手工或 instrumentation 回归。
- 播放页自绘 seek 控件无法由当前 UI 自动化稳定命中，Media3/Software seek 指标未验收。

尚未完成的深层改造：

- Service 已持久化完整队列 ID、当前索引、位置、repeat/shuffle、播放意图和质量模式；恢复按歌曲 ID 匹配并强制暂停。
- `MainViewModel` / Activity 已停止读取和写入旧播放会话；播放模式由 MediaController 状态反向驱动 UI。
- 插入下一首和移动队列项已改用标准 MediaController playlist 命令。
- 播放 request、一次性 ALAC fallback、软件后端回调、连续失败计数与失败跳过已迁入 `ServicePlaybackEngineCoordinator`。
- `PlayerController` 不再注入或访问 composite player / software engine；播放、暂停、seek 和切歌只发送标准 MediaController 命令。
- MediaItem extras 携带软件路由所需的源 URI、revision 字段与 PCM 技术参数，Service 可独立重建 `Song`。
- 软件播放期间的插入、移动、删除和整队列替换由 `MicaCompositePlayer` 同步到隐藏 Exo 队列和 MediaSession timeline。
- 旧 Controller 软件播放实现已从活动代码隔离，后续仅需做物理删除和命名清理，不再构成第二状态源。
- API 33+ 已使用 direct playback capability 过滤 DSD PCM 候选；API 26–32 使用实际
  `AudioTrack` 初始化探测。软件输出会记录实际 routed device，DSD 路由变化时按当前位置重建，
  USB DAC/direct 能力仍待真机确认。

后续性能优化项（不阻塞当前架构迁移）：

- 压缩用户切歌操作到 `audio-start` 的前置调度时间；当前真机样本约 405ms，主要位于 UI、队列状态同步和延迟策略。
- 评估 AudioTrack 跨曲预热与格式相同场景复用，减少创建和设备输出启动开销；必须继续满足单 writer、无双音频约束。

---

## 1. WIP 与实验分支的关系（合并时注意）

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
| **音频延后实验已撤销** | hybrid7 恢复立即切歌，并以目标 song ID 屏蔽旧曲回调 | 延迟窗口会导致播放中切歌回弹 |

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
| `TrackSwitchPerformance.kt` | 扩展：visualContext、decode summary、mark 去重、`recordCoverDraw` |
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
