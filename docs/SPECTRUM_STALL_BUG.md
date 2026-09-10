# 频谱停滞（Spectrum Stall）问题档案

> 2026-09-11 状态复核：§0 的 sink media clock / PCM timeline / analysis engine 属于**当前未提交工作树的 staged 修复**。桌面 focused/full JVM 已有通过记录，设备验收仍在进行；不要把它描述成 0.4.0 clean HEAD 或 release 已交付。

> 最后更新：2026-09-11
> 当前改动：频谱改用 sink 媒体时钟选择 PCM 窗口；验证记录见 §0。§1–10 为历史调查，不代表现行实现或本次验收。

## 0. 2026-09-10：播放时钟与频谱旁路

### 行为与边界

- Shared PCM 的三个 sink（平台整数、DSD 解码后 PCM、Float DSP）各有独立 `SpectrumSinkSession`，由 `SpectrumClockAudioSink` 在播放线程观察输入 PTS 与 `getCurrentPositionUs`。不从 FFT/UI 线程调用 sink，不改变 AudioTrack buffer、音频写入重试或 EQ/Sonic 顺序。
- Float tap 使用每块输入的 PTS；整数 tap 在 Sonic 前，以当前 sink 输入 PTS 加累计 PCM 帧数建立时间线。Media3 1.9.0 的 `DefaultAudioSink.setupAudioProcessors()` 调用无时间参数的 pipeline flush，因此不能假定 `StreamMetadata` 已提供有效偏移。内部变速 flush 在下一块输入处重新建立时间基准，保留已采集历史；pending format 直到 processor flush 才生效。
- `SpectrumPcmTimeline` 保留有界 mono PCM 历史，按 sink 媒体位置复制 2048 样本 FFT 窗口。60Hz 回调只决定刷新机会，不再按调用次数消耗 FIFO。不加固定 1.1 秒视觉延迟，不裁成“最新 1.1 秒”。
- 普通前后台切换保留 PCM，后台继续采集但停 FFT；关闭频谱不采集。暂停保留历史并清空可见投影。清空、seek、格式应用、sink reset/release 和服务 release 使旧结果失效。
- PCM 不覆盖播放位置，或媒体时钟 250ms 未推进时，按单调时间以 120ms 时间常数回落。恢复有效数据后重新分析当前位置；回落不是合成假频谱，也不算有效分析帧。
- 原有频段数、FFT 窗口、频率权重、攻放曲线与 UI 几何不变。USB Exact/DoP/Native 路径不接入这条旁路。

### 并发协议评审

| Owner / 边界 | 副作用与同步 | 确定性证明 |
|---|---|---|
| `SpectrumAnalysisEngine` 持有 stream generation、当前 sink identity、可见性 revision | reset、PCM append、clock update、levels/envelope 发布统一在 owner lock 下 | `oldFftCannotPublishAfterResetAndNewStreamAppend` |
| PCM 字节转换（锁外） | 转换前捕获 token，转换后 append 在 owner lock 下复验；旧 token 不可重新占有 source | 同测试的旧 PCM append，以及 `retiredSinkCannotClearOrPublishOverItsSuccessor` |
| FFT 计算与测试 barrier（锁外、不可取消） | worker lock 独占 FFT scratch；计算后在 owner lock 下复验 generation/revision，再发布两路投影；生命周期不修改 FFT scratch | latch 在发布边界停旧 FFT，reset 并写新 PCM，再放行，检查真实 levels/envelope 和历史 |
| 隐藏/暂停 | revision 使在途分析不能发布；PCM 不清空 | `SpectrumResumeRegressionTest` 与 engine tests |
| sink 退出 | 仅当前 source 能 reset；旧 sink clock/reset 不可影响接替者 | retired-sink test；clock sink flush test |

没有以取消任务作为正确性条件，也没有新增持久化、曲库写入或文件缓存。

### 容量与测试

沿用 2 秒基本容量、按最大输入 burst 扩到至多 4 秒。最多 512 块，边界裁剪只移动 offset；单个过大输入仅转换最后至多 4 秒 mono。保留的有效样本最多 `C = 4 × sampleRate`，块边界的已淘汰 backing 最多额外 C，转换与加入时的临时数组再至多 2C；频谱 mono 数组保守峰值为 `16C` bytes，即 384kHz 时约 24.6MB、768kHz 时约 49.2MB，另加小型 FFT/频段数据及原音频链已有输入字节缓冲。它只属于当前播放流，与 10,000 首曲库和逐字歌词数量无关；该上界不是整机内存或耗电验收。

新增时间线、引擎、sink 适配与真实 Media3/Sonic 测试。旧 `FloatDspSpectrumTapDecouplingTest` 已改为 `false` 后重试同一个 buffer，收到 `true` 后才送下一块，不能再用违规的连续新 buffer 注入声称 decoder 不受背压。

验证进度与设备证据记录于 `.scratch/spectrum-clock/REPORT.md`。JVM/受控 AudioOutput 证明不等于 OEM AudioTrack、蓝牙和视觉同步验收。

---

## 1. 概述

播放页频谱条在**看着屏幕、连续播放**时偶发「冻住」——UI 仍在刷新，但柱状高度长时间不变或几乎不变。  
问题集中在 **Float DSP 路径**（24-bit FLAC 等 hi-res PCM → `MicaFloatDspAudioSink`），与 APE 大帧、蓝牙输出、曲末 seek 等场景叠加时更容易暴露。

本文档记录 **2026-08-06** 一轮完整诊断的结论与修复，供后续回归与类似问题对照。

---

## 2. 时间线（2026-08-06）

| 时间 | 事件 |
|------|------|
| 日间 | 用户报告 VORACITY.flac + 蓝牙 FIIL GS 播放时频谱停滞 |
| 21:27–21:40 | 诊断日志 (3)(4)(5) 前半段：坏模式复现（`pcm-gap` 数秒、`queuedSamples=0`、`pcm-starvation`） |
| 当日 | 加诊断：`SpectrumPcmPipelineDiagnostics`、`FloatDspSink` 阈值事件、`SpectrumProbe` |
| 当日 | 根因确认：`inner-reject` 期间 tap 与 write 耦合 → 分析队列被抽干 |
| 当日 | 实现修复：`MicaFloatDspAudioSink` **tap 与 inner AudioTrack 写入解耦**（`pendingWrites`） |
| 22:15–22:19 | 日志 (5) 后半段 / 日志 (6) 全新进程：**稳定播放 ~3.5 分钟 `targetFps≈60`**，无 `pcm-starvation` |
| 待定 | `versionCode` 未 bump，设备上仍显示 `(50)`；需新包 + 清日志再验收 |

---

## 3. 表现（用户可见 vs 日志）

### 3.1 用户可见

- 播放页进度条/时间在走，**频谱条像卡住**（尤其全屏看着播放器时）。
- 蓝牙连接、从曲末 seek 到开头、长时间播同一首时更容易遇到。
- **不是**「analysis-active=false」或用户关掉频谱开关导致的（坏时段日志里 `analysisActive=true`）。

### 3.2 日志特征（坏模式，日志 3/4 / (5) 21:27 段）

| 指标 | 典型值 |
|------|--------|
| `FloatDspSink: inner-reject` | 长时间刷屏，**同一 `ptsUs` 不变** |
| `FloatDspSink: pcm-gap` | 开头 **~11s**，之后约每 50s **~1s** |
| gap 时 `queuedSamples` | **0**（分析队列被吃空） |
| `Spectrum: pcm-starvation` | 100–600ms+ |
| `SpectrumProbe: targetFps` | 从 ~60 跌到 10–40，甚至 ~0 |
| `analysis fps` | 曾到 0.1 或 40+，与 UI 不同步 |

### 3.3 日志特征（修复后 / 健康段，日志 (5) 22:16+、(6) 22:24–22:28）

| 指标 | 典型值 |
|------|--------|
| `targetFps` | **57–60.5** 持续数分钟 |
| `analysis fps` | **60**，`emptyTicks=0` |
| `pcm-gap` | **~250–440ms**（仍周期性出现） |
| gap 时 `queuedSamples` | **2.3万–4.9万**（有粮） |
| `pcm-starvation` | **无** |
| inner-reject | 仍在，但 **`ptsUs` 持续变化** + `pcm-queue-burst` |

### 3.4 曲末 / 切歌（预期内，非 mid-play 卡死）

- decoder EOS 后队列被 60fps 分析线程抽干 → 短暂 `targetFps` 下降。
- 最后一包长时间 inner-reject + `playback-advancing=false` → 自动下一曲。
- 与「播到一半周期性冻住」不是同一类问题。

---

## 4. 根因

### 4.1 主因（已修复）

**`MicaFloatDspAudioSink` 中频谱 tap 与 inner `AudioSink.handleBuffer` 写入同拍耦合。**

机制：

1. ExoPlayer 每来一帧 PCM 调用 `handleBuffer`。
2. 旧逻辑：tap + 写 AudioTrack 在同一调用里；若 inner AudioTrack **背压 reject**（`handleBuffer` 返回 false），ExoPlayer **重试同一 buffer**。
3. 重试期间 **不再调用 tap** → 无新 PCM 入分析队列。
4. 分析线程仍以 ~60fps 消费队列 → **队列归零** → `pcm-starvation` → UI `targetFps` 下跌 → 用户看到频谱停住。

```
Decoder ──► handleBuffer ──► [tap + write 绑定]
                │
                ▼ inner-reject（AudioTrack 满）
           重试同一 buffer（无新 tap）
                │
                ▼
     分析线程 60fps 抽队列 ──► queuedSamples → 0 ──► 频谱冻住
```

**不是主因（此次复现）**：`analysis-active=false`、passthrough 误关 tap（有单独诊断 `pcm-passthrough-unexpected`）。

### 4.2 加剧因素（未单独修）

| 因素 | 说明 |
|------|------|
| **inner-reject / AudioTrack 背压** | 蓝牙 A2DP、大 buffer、OEM 栈仍频繁 reject；修复后不再拖死频谱，但日志仍刷屏 |
| **蓝牙路由变化** | Mica **仅打日志**（`BluetoothAudioDiagnostics`），不在路由变化时 flush/reconfigure sink；切换瞬间 gap 变大 |
| **曲末 / seek / flush** | `buffer-reset` 后短暂时序紊乱，~1s 内 `targetFps` 可偏低 |

---

## 5. 解决方案（2026-08-06）

### 5.1 代码：tap 与 write 解耦

文件：`app/src/main/java/com/mica/music/media/MicaFloatDspAudioSink.kt`

要点：

- 每个新 buffer：**立即 tap** + 拷贝进 `pendingWrites` + **消费** ExoPlayer 传入的 buffer（`position = limit`）。
- 每次 `handleBuffer` 末尾：**尽力 drain** `pendingWrites` 到 inner AudioTrack；写满则保留 pending，返回 `pendingWrites.isEmpty()`。
- passthrough 路径同样拷贝，避免 buffer 复用污染。
- `flush` / `configure` / `reset` 清空 pending。

**音质**：纯增量；EQ 关且频谱不 active 时仍 bit-exact 直通，**无降质**。

### 5.2 诊断（保留，用于回归）

| 组件 | 作用 |
|------|------|
| `SpectrumPcmPipelineDiagnostics.kt` | `pcm-gap`、`pcm-starvation`、`inner-reject`、`pcm-queue-burst` |
| `MicaSpectrumAnalyzer.kt` | `SpectrumProbe: analysis fps=…` |
| UI | `SpectrumProbe: ui fps=… targetFps=…` |

导出前建议在关于页**清日志**，避免 `inner-reject` 刷屏导致文件过大。

### 5.3 测试

| 文件 | 覆盖 |
|------|------|
| `MicaFloatDspAudioSinkTest.kt` | reject / 重试语义 |
| `FloatDspSpectrumTapDecouplingTest.kt` | FLAC/APE 大帧、连续入队、write 全 reject 时队列仍保留 |

---

## 6. 验收与回归清单

### 6.1 日志（VORACITY.flac，蓝牙，看着屏幕 ≥3 分钟）

- [ ] `targetFps` 多数时间 **≥55**
- [ ] **无** `pcm-starvation`（或仅曲末/EOS 毫秒级）
- [ ] `pcm-gap` 时 `queuedSamples` **不长期为 0**
- [ ] inner-reject 期间 **`ptsUs` 仍前进**（多帧不同 pts）

### 6.2 真机

- [ ] 全屏播放页盯频谱 3+ 分钟，主观连续变化
- [ ] 蓝牙连/断、seek 到 0、曲末自动下一曲各测一次
- [ ] 确认安装包 **versionCode 已 bump**（避免与旧 (50) 混淆）

---

## 7. 已知残留 / 后续（未做）

1. **inner-reject 根因**：AudioTrack buffer / 蓝牙 HAL；可考虑降日志频率，非功能性 bug。
2. **输出设备变化**：`PlaybackRouteMonitor` → `flushAudioPipeline`（2026-08-06）；inner-reject 根因仍待观察。
3. **曲末队列抽干**：EOS 时短暂掉帧可接受；若需「播完最后一刻仍满帧频谱」需单独设计。

---

## 8. 输出设备变化：参考项目 vs Mica（2026-08-06 调研）

> 参考来源：`docs/PERFORMANCE_INVESTIGATION.md` §4.4 列出的 Icey / PixelPlayer / LunaBeat；LunaBeat 公开仓**仅有 README**，无法读源码。

### 8.1 对照表

| 项目 | 拔出耳机 / becoming noisy | 设备插入 / 路由变化 | AudioTrack / ExoPlayer 重建 | 备注 |
|------|---------------------------|---------------------|----------------------------|------|
| **[PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer)** | `setHandleAudioBecomingNoisy(true)`（`DualPlayerEngine`） | `MusicService`：`AudioDeviceCallback` + **耳机重连自动恢复播放**（noisy 暂停后窗口内） | **Audio offload 失速**时 `rebuildPlayersPreservingMasterState`；设备黑名单禁用 offload | 还处理 offload READY 但无声、小米/Android 16 等 |
| **[Icey](https://github.com/TroilOryan/Icey)** | `audio_session`：`becomingNoisyEventStream` → **pause** | `interruptionEventStream`（来电等暂停/恢复）；**无**专用 BT 路由 rebuild | 交 Flutter / ExoPlayer + `audio_session` | 设置页仅「音频焦点」开关 |
| **LunaBeat** | （公开仓无源码） | — | 文档称 ExoPlayer 主路径 + ALAC 按需 copy | 与 Mica 架构接近，细节不可审 |
| **Mica（当前）** | `ExoPlaybackStack`：`setHandleAudioBecomingNoisy(true)` | `BluetoothAudioDiagnostics` + `SpatialAudioMonitor`：**仅日志/空间音频状态** | `AUDIO_PIPELINE_REFACTOR` **P1：route 变化只打日志，不强制 rebuild** | 无 PixelPlayer 式 offload 监控 / 耳机重连续播 |

### 8.2 PixelPlayer 做法摘要（可借鉴）

1. **Becoming noisy**  
   ExoPlayer 内置处理；并在 `onPlayWhenReadyChanged(..., AUDIO_BECOMING_NOISY)` 记录「待重连恢复」。

2. **Headset reconnect**（`MusicService.kt`）  
   - `registerHeadsetReconnectMonitor()` → `AudioDeviceCallback.onAudioDevicesAdded`  
   - 若刚因 noisy 暂停且在时间窗内 → `player.play()`  
   - 支持 A2DP / 有线 / USB / BLE 等 `isReconnectableHeadsetOutput`

3. **Offload / 无声 stall**（`DualPlayerEngine.kt`）  
   - `ExoPlayer.AudioOffloadListener` + 超时 fallback  
   - 检测到 HAL offload 异常 → **disable offload + rebuild player**（保留 master 状态）  
   - 与 **自定义 AudioProcessor 链**（EQ/频谱）冲突时 CPU 解码 — 与 Mica float DSP 路径类似

### 8.3 Icey 做法摘要

- 统一走 `audio_session` 包：noisy → pause；中断结束 → 可选 resume。  
- **不**在 native 层监听 `AudioDeviceCallback` 做 sink flush。  
- 输出设备 UI 设置仅控制是否抢音频焦点，不控制路由 rebuild。

### 8.4 Media3 / 平台限制

- [androidx/media#2080](https://github.com/androidx/media/issues/2080)：`AudioDeviceCallback` / `MediaRouter` **不可靠**覆盖「系统 UI 手动切输出」；ExoPlayer 内部 `DefaultAudioSink` 的 `routedDevice` 才是 ground truth，**尚未对 app 暴露**。  
- 因此多数播放器组合：**becoming noisy +（可选）device callback + ExoPlayer 自身 AudioTrack 重建**，而非 app 手动 flush 每个 buffer。

### 8.5 对 Mica 的建议优先级（尚未实现）

| 优先级 | 项 | 说明 |
|--------|-----|------|
| P2 | 路由变化时 **sink flush / 轻量 reconfigure** | 蓝牙连上瞬间 `pcm-gap` 仍可见；需与 `storeSyncMutex`/playback coordinator 对齐，避免与切歌 rebuild 打架 |
| P3 | 可选「耳机重连续播」（PixelPlayer 式） | 产品决策；与 `setHandleAudioBecomingNoisy` 叠加 |
| P3 | Offload stall 监控 | Mica 有 float processor 链，offload 本就受限；可参考 PixelPlayer **黑名单**思路，非必须 |

---

## 9. 相关文件

| 路径 | 角色 |
|------|------|
| `app/.../MicaFloatDspAudioSink.kt` | Float 路径 EQ + 频谱 tap + pending write 队列 |
| `app/.../MicaEqualizerSpectrumTap.kt` | PCM 入分析队列 |
| `app/.../MicaSpectrumAnalyzer.kt` | 60fps 分析线程与 UI 发布 |
| `app/.../SpectrumPcmPipelineDiagnostics.kt` | 阈值诊断 |
| `app/.../SpectrumQueueCapacityPolicy.kt` | APE 等大 burst 队列容量 |
| `app/.../ExoPlaybackStack.kt` | `setHandleAudioBecomingNoisy(true)` |
| `app/.../util/BluetoothAudioDiagnostics.kt` | 设备增删日志 |
| `docs/ADDING_AUDIO_FORMAT_SUPPORT.md` | §3.2 频谱队列时钟（APE 案例） |
| `docs/AUDIO_PIPELINE_REFACTOR.md` | §5.2 路由变化阶段规划 |

---

## 10. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-08-06 | 初版：VORACITY 停滞根因、解耦修复、日志 (3)–(6) 结论、参考项目路由调研 |
| 2026-08-06 | 实现 `PlaybackRouteMonitor`：输出设备变化 → `flushAudioPipeline`（stop/seek/prepare，含 FloatDspSink flush） |
