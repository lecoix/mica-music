# Mica 音频播放链路改造计划

> **状态（2026-07-13）**：**Gate 1 ✅ Gate 2 ✅ Gate 4 ✅**；**G3-0 ✅**；**G3-1a 证伪**；**G3-1b 已废弃**；**候选 R = 终选架构（全 build type，2026-07-08 推广 release）**：R0–R4 ✅（log 33–41）；DsdOnly int sink（频谱+EQ）+ PcmOnly float sink（频谱+EQ+硬件变速）+ 平台 fallback。远期 USB 独占已确定采用 **USB Host 独立输出**（[`ADR-0001`](adr/0001-usb-host-exclusive-output.md)），当前不实现；近期实施仅限 [`ReplayGain 实际应用状态`](REPLAYGAIN_SIGNAL_STATE_PLAN.md)。
> **目标分支**：`exoplayer-only`  
> **整理日期**：2026-07-07（§18 终态/Gate：2026-07-08）  
> **关联文档**：[`AUDIO_PIPELINE_DISCUSSION.md`](AUDIO_PIPELINE_DISCUSSION.md)（背景讨论）、[`DSD_EXO_PLAYBACK.md`](DSD_EXO_PLAYBACK.md)（DSD 现网行为）、[`CONTEXT.md`](../CONTEXT.md) → **Audio quality consent**  
> **适用范围**：Exo PCM 播放链路、24-bit FLAC、DSD PCM 降采样、频谱 tap、EQ、变速/变调、未来 USB Direct / Native DSD  
> **架构集成风险**：§15（审查清单）、§7.4–§7.5（Service 重建与互斥）、§4.6–§4.7（调用点与持久化）  
> **拿去问人**：先看 **§0 一页摘要** → **§18 Gate 与验收**；细节再查 §4–§7、§15。  
> **执行默认（2026-07-08）**：**候选 X（统一固定链）**；G3-1b 仅 debug/perf 探索交付格式；Y 不再主推；**候选 R 仅作为 R0→R4 gated spike**；**P1 不再追 bit-preserve**。

---

## 0. 一页摘要（问问题用）

### 我们要端出什么

**单链路、整体较轻、兼容性好**的播放器：

- **单链路**：一个 Service、一个 Exo、一个 MediaSession、一套 UI 同步；无软件播/Exo 双后端。
- **较轻**：稳态不 rebuild Exo、release Session；无诊断 trace（release）；切歌/EQ 以 flush/configure 为主。
- **兼容性好**：FLAC/ALAC/DSD、系统控件、大队列、EQ/频谱/变速（设备能力内）、kill restore。

### 三件事别混谈

| 层 | 是什么 | 典型症状 |
|----|--------|----------|
| **A 集成** | MediaController 与 Service 是否断链 | 切歌 UI 不更新、开 EQ 进度条停 |
| **B 链拓扑** | 一套固定 Processor 链 vs 多套 Profile + Exo rebuild | 重、脆、日志里满屏 pipeline-rebuild |
| **C 交付格式** | 24-bit 是否在 Sink 变 16-bit | P0 已证；与 Profile **无关**，属 P2 |

**P1 踩坑主因**：B 用 rebuild 硬做 → 触发 A；补丁若不看 Gate，会陷入「看日志打补丁」循环。

### 当前在哪（如实，不夸大）

| 项 | 状态 |
|----|------|
| P0 诊断 | ✅ 接入；release **不**打 ProcessorFormat/AudioTrackDelivery（Gate 4） |
| P1 Profile/rebuild 代码 | ✅ **已收束**（Gate 2 删驱动；Gate 4 清债） |
| Gate 1（Session/Controller） | ✅ **已通过**（log 19；G1-1a setPlayer） |
| Gate 2（统一链 X） | ✅ **已通过**（log 20；G2-2 全表 #1–#7） |
| Gate 4（删债 / trace→debug） | ✅ **已合入**（2026-07-08） |
| 24-bit 交付 | ✅ **G3-0 ✅**；**G3-1a 证伪**；**G3-1b 已废弃**（被 R 取代，AUTO 时机 race 随之作废、不再修） |
| 候选 R（Renderer-split Sinks） | ✅ **终选，全 build type**（2026-07-08 推广 release）：R0–R4 全过（log 33–41）；DsdOnly int sink（频谱+EQ）+ PcmOnly float sink（频谱+EQ+硬件变速）+ 平台 fallback |

### 计划修订纪律（防「每版都夸、炸了再改」）

| 规则 | 说明 |
|------|------|
| **已证失败的不重写活** | 如 P1 实验 `enableFloatOutput=true`、Profile 解 24-bit —— 只写结论，不重新包装成新方案 |
| **未过 Gate 不用「可行」「接近正确」** | 只能说「待验假设」或「默认执行 X，证伪再改」 |
| **P1 目标不含 bit-preserve** | 核心原则 §1 仍是**长期方向**；**当前阶段 P1 不承诺**（见 §8 P1） |
| **一次只删一条路线** | Gate 2 验 X 时 **禁用** rebuild/预切换；不过验收 **不** 给 Y 加补丁 |
| **验收失败 → 改 Gate/文档，不先堆代码** | 贴 §18.5 整段日志，开会再动刀 |

**历史教训（简短）**：

```text
P1 文档曾写「bit-preserve + Profile 拆分」→ P0 证 Split 不解决 toInt16 → 实机 rebuild 引爆 UI
→ 若继续围着 rebuildPlayerForPipelineProfile 补，只会重复同一循环
```

### 不问「回全 FFmpeg 软件播」的原因（一句）

解码仍用 `libffmpegJNI`；删掉的是**第二套播放引擎**。回全 FFmpeg **不消除** C，且捡回双后端/UI 类问题。见 §18.9。

---

## 核心原则

```text
1. 无 DSP 时不改变音频格式（bit-preserve）。
2. 有 DSP 时优先 float in / float out。
3. 只有设备不支持 float AudioTrack 时，才在链路末尾做一次整数 PCM 量化（Quantizer fallback）。
4. Quantizer 是 fallback，不是默认交付路径。
5. FloatBridge 是 DSP path 的入口，不是所有播放的默认入口。
6. 频谱 tap 不改变主链格式。
7. USB Host 真独占是绕过系统共享 AudioTrack 的独立 output adapter，不是 SharedPcm 上的开关或最小 processor chain。
8. 任何可能降音质的默认行为变更，须遵守 Audio quality consent（事先说明 + 明确允许）。
```

---

## 1. 背景与问题

### 1.1 当前链路（仓库基线）

> **实施以当前 `exoplayer-only` 工作区为准**；勿按「远程默认无 Sonic」理解起点。

Exo PCM 自定义链（`MicaAudioProcessorChain`）当前为：

```text
DsdDecimationAudioProcessor
→ SpectrumAudioProcessor
→ SoftwareEqualizerAudioProcessor
→ MicaPlaybackTuningAudioProcessor（Sonic 包装）
→ AudioTrack
```

`MicaRenderersFactory` 当前典型配置：

```kotlin
DefaultAudioSink.Builder(context)
    .setEnableFloatOutput(false)
    .setEnableAudioOutputPlaybackParameters(false)
```

`MicaPlaybackTuningAudioProcessor` 对 **24-bit packed 会 passthrough**（Sonic 不支持），故 Hi-Res 曲目上变速/变调实际无效；频谱与 PlaybackParams 冲突在本地已通过关 PlaybackParams 缓解，但 24-bit 降 bit 问题仍在。

**历史远程默认**（改造前）：`enableAudioOutputPlaybackParameters(true)`，Sonic 未接入链内（`applyPlaybackParameters` 透传）。文档中若提及「远程默认」均指该状态，**非当前实现**。

### 1.2 交织的问题

| # | 问题 | 根因 |
|---|------|------|
| 1 | 24-bit FLAC 等 Hi-Res PCM 可能被 Sink 预处理降至 16-bit | `setEnableFloatOutput(false)` 为 DSD 服务，Sink 入口常 toInt16 |
| 2 | 变速/变调 + 频谱冲突 | `AudioTrack.setPlaybackParams` 导致 Processor 链 backpressure，频谱 tap 断供 |
| 3 | Sonic 与 24-bit packed 不兼容 | Media3 Sonic 仅接受 16-bit 或 float |
| 4 | USB Direct 无法塞进单链 | bit-perfect / 最小 Processor 与 EQ/频谱/DSD 降采样互斥 |

### 1.3 与讨论文档的关系

[`AUDIO_PIPELINE_DISCUSSION.md`](AUDIO_PIPELINE_DISCUSSION.md) 记录了格式语义、Sonic/频谱机制、Hi-Res 探测模型与 USB 前瞻。**本文是可执行改造计划**；讨论文档中的结论已吸收进下文，不再重复论证。

---

## 2. 当前链路的关键约束

### 2.1 变速尚未在链内完成

关闭 `AudioTrack.setPlaybackParams` 后，必须正确接入 Sonic 并实现 `applyPlaybackParameters` / `getMediaDuration`，否则变速/变调失效。

### 2.2 频谱 tap 不应改变主链格式

`SpectrumAudioProcessor` 职责是 duplicate buffer 给分析器并原样输出。只开频谱时：

- 不应进入 float DSP path  
- 不应触发 PcmQuantizer  

### 2.3 EQ 在整数域会重新量化

`SoftwareEqualizerAudioProcessor` 对 24-bit packed 输入会 **24-bit → double 处理 → 写回 24-bit**。

因此 DSP 链顺序必须是：

```text
FloatBridge → EQ → Sonic → FloatOut / Quantizer fallback
```

**不能** `EQ → FloatBridge → Sonic`（会产生多次整数回写）。

> **代码现状**：`SoftwareEqualizer` 已支持 `ENCODING_PCM_FLOAT`（`processPcmFloatLocked`）。P3 重点是链顺序与 format 传递，而非从零实现 float EQ。

### 2.4 DSD 内部已是 float 处理

`DsdDecimationAudioProcessor` 在 float 域累加/滤波，最后量化成 24-bit 或 16-bit。

后续若需 EQ / Sonic，应扩展为：

```kotlin
enum class DsdDecimationOutputMode { IntPcm, FloatPcm }
```

- 无 DSP：`IntPcm`（保持现网 DSD 整数交付）  
- 有 DSP：`FloatPcm`（避免 DSD → 24-bit → FloatBridge 无效往返）

### 2.5 `flushPlaybackPipeline()`（原 `rebuildAudioPipeline`）

`MicaCompositePlayer.flushPlaybackPipeline()` 仅为 `stop → seekTo → prepare → resume`，**不会**重建 `RenderersFactory` / `DefaultAudioSink`。

Sink Builder 参数（`enableFloatOutput`、Processor 链）在 ExoPlayer 创建后已固定；改变 profile 必须 **重建 ExoPlayer**（或等价机制）。

### 2.6 P1 核心矛盾：不能用一个 Sink 兼顾 FLAC 与 DSD

| 目标 | 需要 |
|------|------|
| 24-bit FLAC bit-preserve | 避免 Sink toInt16；链内无 DsdDecimation / Quantizer |
| DSD decimation | `enableFloatOutput(false)`；强制超高采样率 PCM 进 `DsdDecimationAudioProcessor` |

**结论**：在 `PlaybackOutputMode.SharedPcm` 内部先拆 **Sink Profile**，而不是用一个 Sink + Processor active/inactive 硬扛。

---

## 3. 改造目标

### 3.1 总目标

1. 24-bit FLAC 在无 DSP 场景下不被降到 16-bit。  
2. 只开频谱时不改变主链格式。  
3. EQ / Sonic / 变速开启时，优先 float DSP 域。  
4. DSP 结束后优先 float AudioTrack，不做 app 侧默认整数量化。  
5. 仅当设备不支持 float AudioTrack 时，链路末尾 **一次** 量化 fallback（优先 24-bit，其次 16-bit + dither）。  
6. USB Direct / Native DSD 独立 path，不与 SharedPcm 强耦合。

### 3.2 非目标（本计划阶段不做）

1. Native DSD / DoP 完整实现  
2. USB DAC 实机矩阵适配  
3. 自定义 24-bit SilenceSkipping  
4. 全平台 bit-perfect 承诺  
5. 对普通 PCM 主动升采样到 DAC 最高档  

**说明**：「最终输出 24-bit」≠ bit-perfect。经 EQ / Sonic / 变速处理后内容已被修改；本计划目标是 **减少不必要降质**，而非宣称 DSP 后仍 bit-perfect。

### 3.3 远期 TODO（backlog，均需 consent + 实机）

> 主线（候选 R：renderer-split + float DSP）已在 debug/perf 收官，以下为**已知但暂不做**的延伸项，非当前阶段目标。

1. **DSD 变速变调**（P4 延伸）：当前 DSD 走 int 链、解码为 24-bit int，`SonicAudioProcessor` 只支持 16-bit/float → 变速变调失效。**兼容骨架已落地**（`DsdDecimationOutputMode.FloatPcm` + processor float 输出路径）；`PRODUCTION` 仍 IntPcm，启用 FloatPcm 须 consent + 实机验 176.4k float。
2. ~~**R 上 release**~~ **✅ 已推广（2026-07-08）**：`PcmDeliveryExperiment.rendererSplit` 去掉 debug/perf 门控，全 build type 走 renderer-split。release 实机回归仍建议跑 §18.5 矩阵。
3. **USB Host 真独占 / Native DSD**（P6，见 §P6、[`ADR-0001`](adr/0001-usb-host-exclusive-output.md)）：未来由独立 USB output adapter 管理 permission、interface claim、格式协商和传输，绕过系统共享 `AudioTrack`。现有 `PlaybackOutputMode`、`AudioOutputPathConfig` 和 USB 最小 `DefaultAudioSink` 仅为兼容骨架；`requireSupportedForPlayback()` 继续 fail-fast。USB 传输层、实机矩阵和 Native DSD 路径未实现，且不在当前 ReplayGain 实施范围。
4. **清理**：✅ 已删除 G3-1b 的 inert 代码（`PcmSinkDeliveryDecider` / service per-song rebuild 逻辑）。

**兼容骨架（2026-07-08，无运行时行为变更）**：
- `PlaybackOutputMode` / `DsdDecimationOutputMode` / `AudioOutputPathConfig` 已入代码；经 `ExoPlaybackStackFactory` → `MicaRenderersFactory` 传递。
- DSD：`DsdDecimationAudioProcessor` 接受 `decimationOutputMode`；FloatPcm 输出路径已实现但 `PRODUCTION` 仍 IntPcm，`requireSupportedForPlayback()` 拒绝 FloatPcm 直至 P4 consent。
- USB：`buildUsbDirectMinimalSink` / `buildUsbDirectDsdSink`（P6 最小链，无 EQ/频谱/Sonic/FloatDsp 包装）；`UsbDirectPcm` / `UsbNativeDsd` 在 stack 构建时被拒绝直至 P6。

---

## 4. 架构总览

### 4.1 输出模式（PlaybackOutputMode）

```kotlin
enum class PlaybackOutputMode {
    SharedPcm,      // 默认：频谱/EQ/Sonic/DSD 降采样
    UsbDirectPcm,   // USB PCM 直通（未来）
    UsbNativeDsd,   // Native DSD / DoP（未来，仅预留）
}
```

| 模式 | 频谱 | EQ | Sonic | DSD 降采样 | 本计划 |
|------|------|-----|-------|-----------|--------|
| SharedPcm | 按设置 | 按设置 | 按设置 | ✅ | P0–P5 实现 |
| UsbDirectPcm | 默认关 | ❌ | ❌ | ❌ | P6 架构预留 |
| UsbNativeDsd | ❌ | ❌ | ❌ | 不走 Exo PCM 链 | 仅预留 |

### 4.2 SharedPcm 三条子路径（按功能状态）

#### Path A — Bit-preserve

**条件**：无 EQ、无 Sonic、无变速/变调；可选频谱。

```text
普通 PCM:  Decoder → Spectrum? → AudioTrack
DSD:       Decoder → DsdDecimation(Int) → Spectrum? → AudioTrack
```

- 不主动进 float  
- 不启用 Quantizer  
- 24-bit FLAC 尽量以 24-bit PCM 交付 AudioTrack  

#### Path B — Float DSP

**条件**：EQ 和/或 Sonic 开启，且设备支持 float AudioTrack。

```text
普通 PCM:  Decoder → Spectrum? → FloatBridge → EQ? → Sonic? → AudioTrack(float)
DSD:       Decoder → DsdDecimation(Float) → Spectrum? → EQ? → Sonic? → AudioTrack(float)
```

#### Path C — Quantized fallback

**条件**：EQ / Sonic 开启，但 route 不支持 float AudioTrack。

```text
… → FloatBridge → EQ? → Sonic? → PcmQuantizer → AudioTrack(24/16)
```

- Quantizer **最多出现一次**  
- 禁止 `24-bit → 16-bit → float → 24-bit` 往返  

### 4.3 SharedPcm Sink Profile（P1 关键拆法）

在 `PlaybackOutputMode.SharedPcm` 内，按 **曲目类型 + 后续功能状态** 选择 Sink Profile：

```kotlin
enum class SharedPcmSinkProfile {
    PcmBitPreserve,      // P1 实现
    DsdDecimation,       // P1 实现
    LegacyFullChain,     // P1 临时：EQ 或 Sonic 活跃时的现网等价链
    FloatDsp,            // P3 实现
    QuantizedFallback,   // P5 实现
}
```

```text
SharedPcm
├─ PcmBitPreserveProfile    // 普通 FLAC / WAV / ALAC / AAC / MP3 …
└─ DsdDecimationProfile     // DSF / DSD metadata（Exo PCM 降采样路径）
```

两者 **Sink Builder 参数与 Processor 链不同**，但同属 SharedPcm。

#### PcmBitPreserveProfile

**适用**（P1 决策）：

```text
非 DSD 曲目
且无 EQ（P1 边界，见 §4.4）
且无 Sonic / 变速 / 变调（P1 边界）
```

**Processor 链**：

```text
SpectrumAudioProcessor?    // 仅 tap，可选
```

**不要放**：DsdDecimation、FloatBridge、EQ、Sonic、PcmQuantizer。

**Sink**（参数待 P0 验证）：

```kotlin
DefaultAudioSink.Builder(context)
    .setEnableFloatOutput(pcmBitPreserveFloatOutputFlag)  // 不先拍死，见 P0
    .setEnableAudioOutputPlaybackParameters(false)
    .setAudioProcessorChain(MicaAudioProcessorChain(SpectrumAudioProcessor()))
```

P0/P1 须用日志回答：

- `enableFloatOutput=false` 时 24-bit FLAC 是否仍 toInt16？  
- `enableFloatOutput=true` 时交付是 float 还是 24-bit packed？  

若仅 `true` 能避免 16-bit 损失，可暂作 **no-16bit-loss profile**，日志中明确标注，**不得**称为 bit-perfect。

#### DsdDecimationProfile

**适用**：`DsdSupport.isDsdMetadata` / `PlaybackRouter` 判定的 DSF Exo 路径（`.dff` 现网不可播，决策函数可只对 DSF 生效）。

**Processor 链**（P1）：

```text
DsdDecimationAudioProcessor → SpectrumAudioProcessor?
```

P1 不接 EQ / Sonic / FloatBridge / Quantizer。

**Sink**：

```kotlin
DefaultAudioSink.Builder(context)
    .setEnableFloatOutput(false)
    .setEnableAudioOutputPlaybackParameters(false)
    .setAudioProcessorChain(
        MicaAudioProcessorChain(
            DsdDecimationAudioProcessor(context),
            SpectrumAudioProcessor(),
        ),
    )
```

`enableFloatOutput(false)` 保留现网行为：超高采样率 DSD PCM 必须进自定义 Processor，不被 float 直通绕过。

#### Profile 配置表

| Profile | Source | `enableFloatOutput` | Processor 链 | 目标 |
|---------|--------|--------------------:|--------------|------|
| `PcmBitPreserve` | FLAC/WAV/ALAC/AAC/MP3 | 待 P0 验证 | `Spectrum?` | 不降 16-bit |
| `DsdDecimation` | DSF / DSD-like | `false` | `DsdDecimation → Spectrum?` | 保持 DSD 降采样 |
| `LegacyFullChain` | EQ 或 Sonic 活跃 | `false` | 现网全链（含 Sonic tail） | P1 临时，非验收路径 |
| `FloatDsp` | EQ 和/或 Sonic | P2 probe 后定 | `FloatBridge → EQ? → Sonic?` | float in/out |
| `QuantizedFallback` | float 不支持 | — | `… → Quantizer` | 最后一次量化 |

**注意**：普通 PCM path **不要**挂 inactive 的 `DsdDecimationAudioProcessor`，减少 P1 排查变量。

### 4.4 P1 阶段边界（EQ / Sonic / Legacy 链）

P1 **仅**按曲目类型在 `PcmBitPreserve` ↔ `DsdDecimation` 间切换；**不**实现 FloatDsp / QuantizedFallback。

#### P1 默认策略（**选项 A，强制**）

直至 P3/P4 就绪：

```text
1. bit-preserve 验收路径：EQ 关、Sonic 关（speed=1.0 / pitch=0）、PlaybackTuning 默认。
2. EQ 或 Sonic（speed≠1 / pitch≠0）开启时：走 LegacyFullChain profile（见下），
   不参与 P1 bit-preserve 验收。
3. P1 PR 说明须声明采用选项 A；禁止默认启用选项 B。
```

**LegacyFullChain**（P1 临时，等价现网全链）：

```text
DsdDecimation? → Spectrum? → EQ → MicaPlaybackTuningAudioProcessor
Sink: enableFloatOutput=false, enableAudioOutputPlaybackParameters=false
```

Profile 决策扩展（P1）：

```kotlin
data class SharedPcmPipelineDecision(
    val sinkProfile: SharedPcmSinkProfile,
    val reason: String,
)

fun decideSharedPcmProfile(song: Song, legacyDspActive: Boolean): SharedPcmPipelineDecision =
    when {
        legacyDspActive -> SharedPcmPipelineDecision(LegacyFullChain, "legacy-eq-or-sonic")
        DsdSupport.isDsdMetadata(song.metadata) || isDsfExtension(song) ->
            SharedPcmPipelineDecision(DsdDecimation, "dsd-decimation")
        else -> SharedPcmPipelineDecision(PcmBitPreserve, "pcm-bit-preserve")
    }

// legacyDspActive = EQ enabled || speed != 1f || pitch != 1f（见 PlaybackTuning）
```

#### PcmBitPreserve 与 Sonic tail

`PcmBitPreserve` 链 **不含** `MicaPlaybackTuningAudioProcessor`（§4.3）。Legacy 路径保留 Sonic tail。

P1 进入 `PcmBitPreserve` 时，`PlayerController` 须保证对 MediaSession 下发 `speed=1 / pitch=1`（或 UI 禁用变速），避免用户以为在变奏而链内无 Sonic。

#### EQ toggle 与 profile 切换

现网 EQ 开关仅触发 `flushAudioPipeline`（stop/seek/prepare），**不足以**在 `PcmBitPreserve` ↔ `LegacyFullChain` 间切换 Sink。

P1 起：EQ 开/关若导致 profile 变化 → **Pipeline rebuild**（§7.4），不能仅 flush。

**选项 B**（P3 前禁止默认）：EQ/Sonic 开 → 立即 rebuild 到 `FloatDsp`（未实现则 throw / 回退 LegacyFullChain）。

### 4.5 Profile 决策函数（P1 初版）

见 §4.4 `decideSharedPcmProfile(song, legacyDspActive)`。P2 起扩展为完整 `PlaybackPipelineConfig`（含 EQ、Sonic、probe 结果）。

### 4.6 Profile 决策调用点（架构集成）

Profile 须在 **出声前** 与当前活跃 profile 比较；变化则触发 Pipeline rebuild（§7.4）。**不能**只在 `MicaRenderersFactory` 构造时读一次。

| 调用点 | 文件 | 须比较 profile | 备注 |
|--------|------|----------------|------|
| 首次起播 / 切歌 | `ServicePlaybackEngineCoordinator.start` | ✅ | 主路径 |
| 队列内跳转 | `startExistingAt` / `startAt` | ✅ | 含通知/锁屏 `onSelectMediaItem` |
| **自动下一曲** | `onMediaItemTransition(AUTO)` → `beginAutoTransition` | ✅ **P1 易漏** | 现网仅 `begin()` request state，**不**经过 `start()`；**G3-1b** 在此 `prepareSinkDelivery`，但 hook **晚于** Exo 内部切轨 configure（见 **§18.13.4**，log 29 仍可能先 `OUTPUT_FAILED`） |
| 用户 skip next/prev | `onSkipToNext` / `onSkipToPrevious` | ✅ | 经 `startExistingAt` |
| EQ 开关 | `MicaEqualizerManager.onEnabledChanged` | ✅ | legacyDspActive 变化 → Pipeline rebuild，非仅 flush |
| PlaybackTuning 变更 | `PlayerController.syncEffectivePlaybackTuning` | ✅（P1 Legacy 路径） | speed/pitch 脱离 1.0 进入 LegacyFullChain |
| 冷启动 restore | `PlayerController.bootstrapQueue` → 服务首帧 prepare | ✅ | 见 §4.7 |
| 频谱 tap 开关 | `MicaSpectrumAnalyzer.onEnabledChanged` | 通常否（P1） | 仅影响 tap active 与 offload；**不改变** Sink profile，除非未来 tap 强制 Legacy |
| App 队列同步 | `PlayerController.syncQueueToService` | 否（直接） | rebuild 期间须互斥，见 §7.5 |

`PlaybackRouter.decide(song)` 仍负责 **可否播放**（如 DFF unsupported）；**Sink profile** 由 `decideSharedPcmProfile` 负责，二者正交。

### 4.7 AudioQualityMode 与 SinkProfile 映射

现网 `AudioQualityMode`（`HIFI` / `DSP`）由 EQ 开关驱动，经 `ServicePlaybackStateCoordinator` 持久化到 `ServicePlaybackStateStore`，**当前不驱动 Sink 选型**。

| 字段 / 概念 | 现网语义 | 改造后关系（P1） |
|-------------|----------|------------------|
| `AudioQualityMode.DSP` | EQ 开 | 投影自 `legacyDspActive`；与 `LegacyFullChain` 对齐，**不等于** FloatDsp |
| `AudioQualityMode.HIFI` | EQ 关 | EQ 关且 Sonic 默认时，可与 `PcmBitPreserve` / `DsdDecimation` 共存 |
| `SharedPcmSinkProfile` | （新增） | **出声权威**；restore / UI 须与真实 profile 一致 |

**P1 规则**：

1. `setQualityMode` 仍随 EQ 更新（兼容设置页与持久化）。  
2. 冷启动 `tryRestore` 后，在 **首次 prepare 前** 对当前曲调用 `decideSharedPcmProfile` 并 rebuild 到对应 Sink。  
3. 禁止出现：持久化 `HIFI` 但 Exo 仍挂 Legacy 全链（或反之）——以 **实际 profile + 日志** 为准做集成测试。

**P3 后**：`DSP` 可能对应 `FloatDsp` / `QuantizedFallback`；届时在 ADR 中更新映射表。

---

## 5. 交付格式模型（PcmDeliveryFormat）

`AlacPcmFormat` 现仅表达整数 PCM。扩展：

```kotlin
sealed class PcmDeliveryFormat {
    data class IntPcm(
        val sampleRateHz: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
    ) : PcmDeliveryFormat()

    data class FloatPcm(
        val sampleRateHz: Int,
        val channelCount: Int,
    ) : PcmDeliveryFormat()
}
```

### 5.1 Probe 顺序

**无 DSP / 无 Sonic — 普通 PCM**：

```text
1. source sampleRate + source bitDepth（不主动升采样到 DAC 最高档）
2. 若不支持，同 sampleRate 下降 bitDepth
```

**无 DSP — DSD PCM 降采样**：继续 `DsdOutputPolicy` ladder（非蓝牙 176.4k/24 → …；蓝牙 48k/24 → 48k/16）。

**有 EQ / Sonic**：

```text
1. FloatPcm(source rate, channels)
2. IntPcm(source rate, channels, 24)
3. IntPcm(source rate, channels, 16)
```

**不能假设**「支持 24-bit packed」⇒「同采样率 float 也支持」——须独立 probe（P2）。

**UsbDirectPcm**：绑定 USB 设备 ID；优先 source format match；不走 SharedPcm DSP ladder（P6）。

### 5.2 音频路由变化（蓝牙 / USB 插拔）

`DsdOutputPolicy` / `DsdDecimationAudioProcessor` 的目标格式随 **当前 route** 变化（如蓝牙封顶 48k）。

| 阶段 | 行为 |
|------|------|
| **P1** | route 变化时 **打日志**（`AudioRouteSnapshot` + 当前 profile + DSD target）；若 DSD 正在播放且 `AudioTrack` init 失败，走现有 `OUTPUT_FAILED` 分类；**不强制** rebuild |
| **P2+** | route 变化且 **delivery 候选 ladder 变化** → Pipeline rebuild + 重新 probe |
| **P6** | USB 插拔 → Full mode rebuild（§7.3） |

P1 验收须包含：DSD 播放中连蓝牙，日志可见 route 与 decimation target；失败可接受，但须可诊断。

---

## 6. Processor 设计

### 6.1 PipelineFormatTraceAudioProcessor（P0 诊断）

只记录格式，不改变音频：`processorName`、input/output encoding、sampleRate、channelCount、active、buffer size。

### 6.2 FloatBridgeAudioProcessor

- **职责**：16/24/32-bit integer → float PCM  
- **启用**：后续有 EQ 或 Sonic，或下游不支持当前 integer encoding  
- **禁用**：无 EQ/Sonic、仅频谱、已是 float、UsbDirectPcm  
- **要点**：24-bit sign extension；避免每 buffer 分配 ByteArray；单元测试覆盖极值与小信号  

### 6.3 PcmQuantizerAudioProcessor

- **职责**：float → 最终整数 PCM（**链路中最多一次**）  
- **启用**：Float DSP 完成且 route 不支持 float AudioTrack  
- **目标**：优先 24-bit packed；fallback 16-bit + dither  

### 6.4 DsdDecimationAudioProcessor 扩展

见 §2.4 `DsdDecimationOutputMode`。

### 6.5 Sonic 接入（P4）

`MicaAudioProcessorChain` 正式持有 Sonic（或 `MicaPlaybackTuningAudioProcessor` 包装），实现：

- `applyPlaybackParameters` / `getMediaDuration`  
- flush/reset 参数同步  
- configure 失败降级（默认 **策略 A**：回 1.0x，**不** fallback 到 AudioTrack PlaybackParams）  

**顺序**：Spectrum → FloatBridge → EQ → Sonic → 交付  

#### DSD 24-bit 输出与 Sonic 不可用（P1–P3）

`DsdDecimationProfile` P1 输出为 **24-bit packed**；Sonic 不支持 → `MicaPlaybackTuningAudioProcessor` passthrough。

| 情况 | 行为 |
|------|------|
| DSD + speed/pitch = 1.0 | 正常 DSD 降采样播放 |
| DSD + 用户已设 speed≠1 或 pitch≠0 | P1：走 `LegacyFullChain` 仍无法对 24-bit 做 Sonic；**策略 A**——实际 1.0x 播放 + 日志 `sonic-disabled`；UI 应提示「当前格式不支持变速」或切到 Legacy 后仍无效则禁用滑块 |
| 切歌 FLAC↔DSD | rebuild 后 `PlayerController` 重新 `syncEffectivePlaybackTuning`；DSD 曲上勿 silent 保留无效 speed 状态 |

P4 + `FloatDsp` 完成后：DSD 改 `DsdDecimationOutputMode.FloatPcm`，Sonic 在 float 域生效。

---

## 7. Sink / ExoPlayer 重建策略

### 7.1 PlaybackPipelineConfig（P2 完整版）

```kotlin
data class PlaybackPipelineConfig(
    val outputMode: PlaybackOutputMode,
    val sinkProfile: SharedPcmSinkProfile,
    val deliveryFormat: PcmDeliveryFormat,
    val needsSpectrumTap: Boolean,
    val needsEq: Boolean,
    val needsSonic: Boolean,
    val needsFloatBridge: Boolean,
    val needsQuantizer: Boolean,
    val dsdOutputMode: DsdDecimationOutputMode,
)
```

配置来源：曲目 metadata、route、用户设置（EQ/频谱/USB 独占）、PlaybackTuning、probe 结果。

### 7.2 MicaRenderersFactory

```kotlin
class MicaRenderersFactory(
    context: Context,
    private val sinkProfile: SharedPcmSinkProfile,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(...): AudioSink? = when (sinkProfile) {
        PcmBitPreserve -> buildPcmBitPreserveSink(context)
        DsdDecimation -> buildDsdDecimationSink(context)
        LegacyFullChain -> buildLegacyFullChainSink(context)
        FloatDsp -> buildFloatDspSink(context)
        QuantizedFallback -> buildQuantizedFallbackSink(context)
    }
}
```

P1 只实现 `PcmBitPreserve`、`DsdDecimation`、`LegacyFullChain`；`FloatDsp` / `QuantizedFallback` 可 `TODO()` 或 throw。

#### Factory 构建约束（各 Profile 共用）

重建 ExoPlayer 时 **必须保留**，否则 DSD / ALAC 路径整体失效：

| 约束 | 来源 | 说明 |
|------|------|------|
| `MicaExtractorsFactory.create()` | `MicaMediaService.onCreate` | DSF 解复用 |
| `alacBlockingSelector` | `MicaRenderersFactory` | ALAC 走 FFmpeg |
| `setExtensionRendererMode(PREFER)` | `MicaRenderersFactory.init` | FFmpeg 扩展渲染器 |
| `setEnableDecoderFallback(true)` | 同上 | |
| 同一 `mediaSourceFactory` 实例逻辑 | Service 层 | rebuild 时重新 `build()` 即可 |

**EQ processor 单例**：`MicaEqualizerManager.audioProcessor` 为全局单例，注入各 profile 的链。P1 从 `PcmBitPreserve` 移除 EQ 时：

- 单例 band 状态仍保留；仅 `isActive()` 为 false。  
- EQ 参数变更在 Legacy 路径仍触发 flush；**禁止**在 inactive 时误调 `configure` 导致链格式错乱。  
- P3 多 profile 并存时，EQ toggle → profile rebuild → 新链 `configure` 一次。

**audioSessionId**：Exo 重建后 sessionId 变化。须在 rebuild 完成回调中走现有 `onAudioSessionIdChanged` → `MicaEqualizerManager.attach`，避免 EQ preset 读取失败或重复 attach 泄漏。

### 7.3 重建分级

| 级别 | 触发 | 动作 |
|------|------|------|
| **轻量 flush** | EQ 参数、频谱开关、Sonic 参数（链结构不变） | flush processors；保留 ExoPlayer/Sink |
| **Pipeline rebuild** | bit-preserve ↔ float DSP；deliveryFormat 变化；Quantizer 开关；DSD output mode | 保存 queue/index/position/playWhenReady → **重建 ExoPlayer 或 Sink** → 恢复 → prepare |
| **Full mode rebuild** | SharedPcm ↔ UsbDirectPcm；USB 插拔 | 完整释放 output path → 按 mode 重建 |

**Profile 变化（如 24-bit FLAC → DSD）必须 Pipeline rebuild 及以上**，不能仅调用现有 `rebuildAudioPipeline()`。

> **§18 勘误（2026-07-08）**：上句仅在 **§4 三套 Profile、Processor 列表不同**（如 `PcmBitPreserve` **不含** `DsdDecimation`）时成立。  
> **hybrid8 单链**（始终含 `DsdDecimation`，FLAC 上 isActive=false passthrough）历史上靠 **configure + flush**，**不** rebuild Exo。  
> 终态若选 **§18.4 候选 X（统一链）**，FLAC↔DSD **不应**以 rebuild 为默认路径，须 **Gate 2** 实机验证。

新增入口示意：

```kotlin
fun rebuildPlayerForPipelineProfile(
    profile: SharedPcmSinkProfile,
    positionMs: Long,
    resumePlayback: Boolean,
)
```

### 7.4 MicaMediaService 重建序列（Pipeline rebuild）

现网 `MicaCompositePlayer.rebuildAudioPipeline()` **不满足** profile 切换。Pipeline rebuild 须由 **Service 层** orchestrate，建议顺序：

```text
1. 进入 rebuild 锁（§7.5），拒绝/排队并发导航与队列写入
2. 快照：mediaItems、currentIndex、positionMs、playWhenReady、
         playbackParameters（PlaybackTuning）、activeRequest（coordinator）
3. compositePlayer.playWhenReady = false；stop
4. playbackEngineCoordinator.release()（removeListener）
5. exoPlayer.release()
6. val factory = MicaRenderersFactory(context, sinkProfile = newProfile)
7. val exo = ExoPlayer.Builder(context)
       .setRenderersFactory(factory)
       .setMediaSourceFactory(MicaExtractorsFactory.create() + DataSource 同现网)
       .setAudioAttributes(..., audioFocus)
       .build()
8. 重挂：MediaSession 若绑定旧 player → release session 并
       MediaSession.Builder(this, exo).build()（或项目采用的 swap 模式）
9. MicaCompositePlayer(exo) 替换 compositePlayer；playbackEngineCoordinator.start()
10. setMediaItems(snapshot)；seekTo(index, positionMs)
11. configureQualityMode(exo, dsp, spectrumTap)
12. MicaEqualizerManager.attach（sessionId 回调或立即读 sessionId）
13. prepare；playWhenReady = snapshot
14. 释放 rebuild 锁；flush PendingPlaybackNavigation / pending seek（§7.5）
15. DiagnosticLog: pipeline-rebuild profile=… reason=…
```

**注意**：步骤 8 的具体 API 以 Media3 版本为准；implementer 须在 P1 PR 中写明 Session 换芯方案。**仅改 Factory 构造参数而不 release Exo = P1 最高频失败模式**。

### 7.5 Rebuild 与队列 / 导航 / Seek 互斥

| 风险 | 现网位置 | 策略 |
|------|----------|------|
| rebuild 中途 `insertPlayNext` / `setQueue` | `PlayerController` → `MediaController` | rebuild 锁期间：App 侧队列写入排队或返回「pipeline busy」；服务恢复后一次 sync |
| `PendingPlaybackNavigation` 与 rebuild 竞态 | `PendingPlaybackNavigation` + `onSelectMediaItem` | rebuild 开始前 `consume` 或保存 override；rebuild 完成后仅应用一次 |
| 用户拖进度（pending seek） | `PlayerController` pending seek | rebuild 开始时取消 UI pending seek；rebuild 后以快照 `positionMs` 为准，再允许新 seek |
| duplicate-start 误判 | `ServicePlaybackEngineCoordinator.start` | rebuild 后 `requestState` 须 `begin` 新 request，避免旧 request 导致 skip start |
| `MicaCompositePlayerCommandRoutingTest` | 测试名暗示已 recreate renderers | **测试名误导**；implementer 勿以为现 API 够用——须新测覆盖 profile 切换 |

rebuild 锁建议持有方：`MicaMediaService` 或 `ServicePlaybackEngineCoordinator`（单线程 mainHandler 串行即可）。

### 7.6 频谱双开关与隐式 tap

详见 **§16**。集成与验收须区分 pipeline tap 与 UI `analysisActive`，并处理 audiophile mini player / photo stack 触发的隐式 tap。

---

## 8. 分阶段实施计划

### P0 — 格式诊断与基线确认

> **实现状态（2026-07-07）**：诊断代码已接入；**实机播放验收**仍须人工完成。

**目标**：确认 24-bit FLAC 在哪一步变 16-bit；确认 PlaybackParams 与频谱 gap 关系。

**已落地代码**：

| 组件 | 路径 | 日志 tag |
|------|------|----------|
| 链内格式 trace | `PipelineFormatTraceAudioProcessor.kt` | `ProcessorFormat` |
| Sink / 解码 / AudioTrack | `PcmFormatDiagnostics.kt` | `AudioSinkBuild` / `DecoderInput` / `AudioTrackDelivery` |
| Renderer 包装 | `PipelineAudioRendererEventListener.kt` | 同上 |
| 链出口 + PlaybackTuning | `MicaAudioProcessorChain.kt` | `ProcessorFormat` / `PlaybackTuning` |
| 频谱 gap（既有） | `SpectrumAudioProcessor.kt` | `SpectrumTap` |

Processor 链 trace 位置：`sink-entry` → DsdDecimation → `after-dsd` → Spectrum → `after-spectrum` → EQ → `after-eq` → PlaybackTuning → `chain-exit`。

**任务**：

1. 加 `PipelineFormatTraceAudioProcessor` — ✅  
2. 各 Processor `configure` 输入/输出打日志 — ✅  
3. 记录 Sink 入口与 AudioTrack 最终 delivery format — ✅  
4. 记录 `SpectrumTap` 的 `maxInputGapMs` / `maxTapGapMs` — ✅（既有，每秒窗口）  
5. 样本：24/96 FLAC、小信号 24-bit FLAC、16-bit FLAC、DSD — ⏳ 实机  

**实机验收步骤**：

1. 安装 debug 包，播放 24-bit/96kHz FLAC（EQ 关、变速 1.0x）。  
2. 设置 → 导出诊断日志，或 `adb logcat -s MICA_DIAGNOSTICS`。  
3. 对照日志顺序：  
   - `DecoderInput`：解码器输出格式  
   - `ProcessorFormat name=sink-entry`：**Sink 预处理后进链格式**（若此处已是 `PCM_16BIT`，toInt16 发生在 Sink 内）  
   - `after-dsd` / `after-spectrum` / `after-eq` / `chain-exit`：各段是否改格式  
   - `AudioTrackDelivery`：最终 AudioTrack encoding  
4. 变速 1.5x + 开频谱，观察 `SpectrumTap` 的 `maxInputGapMs` 是否出现秒级尖峰。  

**验收**：能定位 toInt16；能记录变速 + 频谱 gap 基线数据。

**P1 最小实验（2026-07-08）**：在完整 Profile 拆分前，将 `PcmBitPreserveExperiment.ENABLE_FLOAT_SINK_OUTPUT = true`（`MicaRenderersFactory` → `setEnableFloatOutput(true)`）。播 24/96 FLAC，对照 `P1Experiment` / `sink-entry` / `AudioTrackDelivery`；**测完改回 `false` 或合入正式 P1**。

---

### P1 — 稳定 SharedPcm 链路（**非** bit-preserve）

> **名称勘误**：早期标题「Bit-preserve + Profile 拆分」**已失效**（P0 已证 Profile 不解决 toInt16）。  
> **实现状态（2026-07-08）**：过渡代码在仓；**默认收束方向 = 候选 X**；**冻结** Profile/rebuild 新功能。  
> **实机**：kill restore、DSF 可播；FLAC↔DSD/EQ 曾 UI/进度失败（Session 断链，Gate 1 未过）。

**P1 目标（当前阶段，可验收）**：

1. FLAC / DSD / EQ / 频谱 / restore **不炸**（§18.5 冻结表）。  
2. 回到 **轻量统一链**（候选 X）：切歌/EQ 以 **configure + flush**，非常规 rebuild。  
3. **保留** P0 格式诊断（release 前改 debug）。  
4. **不承诺** 24-bit 不降级 —— **全部留给 P2 Delivery**。

**P1 明确不做**：

- 用 Profile/rebuild **解决** 24-bit（P0 已证走不通）。  
- 在 Gate 1 未过前继续加 `rebuildPlayerForPipelineProfile` / 曲末预切换补丁。  
- 把「pipeline-rebuild 日志出现」当作验收通过。

**过渡代码（待 Gate 2 删或废）**：

| 组件 | 状态 |
|------|------|
| `SharedPcmSinkProfile` + `decide…` | **Deprecated**：仅日志；**禁止**驱动 rebuild（Gate 2 前） |
| `rebuildPlayerForPipelineProfile` | **Deprecated**：X 为默认终态，Gate 2 通过后 **删除** |
| `maybePreRebuildForUpcomingProfile` | **Gate 2 验 X 时必须禁用**；仅 Y fallback 才讨论 |
| `MicaRenderersFactory(profile)` 三分支 | Gate 2 通过后 **合并为单链**（hybrid8 顺序） |

**推荐终态链（候选 X，与 hybrid8 一致）**：

```text
DsdDecimation → Spectrum → EQ → Sonic → AudioTrack
（各段 isActive；FLAC 上 DsdDec inactive passthrough）
```

**原 P1 Profile 任务记录（归档，勿当路线图）**：

1. 实现 `SharedPcmSinkProfile` + `decideSharedPcmProfile` — ✅（**Deprecated 驱动 rebuild**）  
2. `MicaRenderersFactory` 按 profile 分支 — ✅（**待合并单链**）  
3. `rebuildPlayerForPipelineProfile` — ✅（**Deprecated**）  
4. 三分支 Processor 列表 — ✅（**待删**）  
5. `pcmBitPreserveFloatOutputFlag` — ✅ 结论：`false`；float **非 P1**  
6. §4.4 / §4.6 调用点 — ✅（ensurePipeline **应下线**）  
7. §7.4–§7.5 rebuild — ⚠️ **不应继续补**  

**验收**（§18.5；**不含** bit-preserve / toInt16 项）：

| # | 场景 | 状态 |
|---|------|------|
| 1 | 24/96 FLAC 稳播 2min | ⏳ 有声/进度；**不验** 24-bit |
| 2 | EQ 开→关→开 | ❌ UI/进度曾失败（Gate 1） |
| 3 | DSD/DSF 稳播 | ✅ |
| 4 | FLAC AUTO↔DSD 自动切歌 | ❌ UI 曾失败 |
| 5 | 手动 next/prev 混格式 | ⏳ |
| 6 | kill + restore | ✅ |
| 7 | 大队列 seek-existing | ⏳ |

**24-bit / 小信号 / 频谱 encoding**：移 **P2**；P1 **不验收**。

**过渡补丁（仅止血，非终态）**：attach 误 flush（已修）；EQ rebuild 后 flush（已修）；`playWhenReady` flush；ensurePipeline / 曲末预切换 —— **Gate 2 验 X 时禁用后三条**。

**P1 必打日志**：

```text
AudioPipelineProfile: songId, sampleRate, bitDepth, isDsd, profile, reason
AudioSinkBuild: profile, enableFloatOutput, processors
ProcessorFormat: name, inputEncoding, outputEncoding, sampleRate, active
```

---

### P2 — Float delivery probe

**目标**：支持「不量化 DSP path」的设备能力探测。

**任务**：

1. `PcmDeliveryFormat` + `AudioOutputCapabilities.supports(FloatPcm)`  
2. 普通 PCM 与 DSD decimation target 分别 probe float  
3. 日志：float / int support、selected delivery format  

**验收**：能区分 FloatPcm 与 24-bit IntPcm 支持；不假设二者等价。

---

### P3 — Float DSP path

**目标**：EQ 开启时 float in / float out，默认不 Quantizer。

**任务**：

1. `FloatBridgeAudioProcessor`  
2. 链顺序：Spectrum? → FloatBridge → EQ? → AudioTrack(float)  
3. `DsdDecimationOutputMode.FloatPcm`  
4. 优化 buffer 分配  

**验收**：24-bit FLAC + EQ + float 支持 → Quantizer inactive，delivery 为 FloatPcm。

---

### P4 — Sonic 变速/变调

**目标**：SharedPcm 内变速，频谱稳定，不依赖 PlaybackParams。

**任务**：

1. `setEnableAudioOutputPlaybackParameters(false)`（若 P1 未统一）  
2. Sonic 正式接入 + `applyPlaybackParameters` / `getMediaDuration`  
3. Spectrum 在 Sonic 前  
4. 接入 `FloatDsp` profile；float 不支持时走 P5  

**验收**：

1. 1.5x + 频谱：`maxInputGapMs` 无数秒尖峰  
2. pitch 生效；时间轴与 `getMediaDuration` 一致  

---

### P5 — Quantized fallback

**任务**：`PcmQuantizerAudioProcessor`；float→24/16 + dither；单次量化约束；单元测试。

**验收**：float 不支持时优先 24-bit fallback；同链路 Quantizer 最多 active 一次。

---

### P6 — USB Host 真独占预留（远期）

**任务**：

1. 独立 USB output adapter：绕过系统共享 `AudioTrack`，拥有 USB permission、interface claim、格式协商、传输和释放。
2. USB output session owner：处理 attach/detach、设备 identity、重连、失败原因和显式 fallback。
3. 输出事实：区分 requested、active、fallback、实际设备、协商格式和信号修改；USB exclusive 与 bit-perfect 分开表达。
4. SharedPcm ↔ USB Host 切换采用 full mode rebuild，并保全队列、当前曲目、位置、播放意图、歌词与通知状态。
5. Native DSD/DoP 以后作为 USB adapter 的独立传输能力，不通过 Exo PCM processor chain。

**当前不实现**：上述全部运行时能力。`PlaybackOutputMode.UsbDirectPcm`、`usbAudioDeviceId` 和 `buildUsbDirectMinimalSink()` 保留为历史兼容骨架，但不得通过解除 fail-fast 冒充 USB 独占。

**与候选 R（renderer-split）的兼容 seam（远期实现时按此接，无需返工）**：

- **真实 seam**：当前 SharedPcm/AudioTrack 是第一个 implementation；未来 USB Host adapter 是第二个 implementation。不要把 USB 协议实现塞进 `MicaRenderersFactory` 的 Sink builder。
- **renderer 复用**：现有 DsdOnly/PcmOnly 解码与 support policy 可提供 PCM/DSD 输入，但 USB adapter 如何取得并发送帧必须单独设计，不能假定最小 `DefaultAudioSink` 可复用。
- **PlaybackOutputMode 正交于 renderer-split**：插拔或用户切换 → full-mode rebuild，不改变 renderer-split 的 SharedPcm 稳态策略。
- **consent 边界**：USB 模式如何处理 ReplayGain、EQ、频谱、变速、重采样和 fallback 必须逐项定义；默认改变交付格式或信号处理前仍需明确允许。

> 结论：候选 R 不阻塞 USB Host，但现有最小 Sink 分支也不构成 USB Host 实现。真正实现必须新增独立 adapter/session；当前先完成 ReplayGain 实际应用状态，不触碰 P6 运行时。

---

## 9. 降级策略

### 9.1 Float output 不支持

```text
FloatBridge → EQ/Sonic → PcmQuantizer → IntPcm（优先 24-bit，其次 16-bit + dither）
```

### 9.2 Sonic configure 失败

**默认策略 A**：关闭 Sonic，speed/pitch 回 1.0，提示用户。  
**禁止默认** fallback 到 AudioTrack PlaybackParams（频谱断供来源）。

### 9.3 24-bit delivery 不支持（无 DSP）

允许 fallback 到 16-bit，但必须记录：fallback reason、route、source/target bitDepth。

---

## 10. 测试计划

### 10.1 单元测试

- **FloatBridge**：16/24/32-bit 极值、sign extension、ByteOrder  
- **Quantizer**：clipping、dither、24-bit packed 写入顺序、单次 guard  
- **Pipeline decision**：无 DSP 24-bit → PcmBitPreserve；DSD → DsdDecimation；UsbDirectPcm 无 EQ/Sonic  

### 10.2 集成矩阵

| 样本 | 功能组合 | Route |
|------|----------|-------|
| 16/44.1 FLAC、24/96、24/192、AAC、DSD64–256 | 无 DSP / 仅频谱 / EQ / Sonic / 组合 | 内置、有线、蓝牙、USB |

### 10.3 性能

- 96k/192k CPU  
- Spectrum + EQ + Sonic 同开：allocation、爆音、`maxInputGapMs`  
- 现有 Spectrum/EQ 均有 ByteArray 拷贝，新增 Bridge/Quantizer 须监控  

### 10.4 Offload 与 PcmBitPreserve

现网 `MicaMediaService.configureQualityMode`：

```text
offloadDisabled = dspEnabled（EQ 开）|| spectrumTapEnabled
```

**P1 决策**：

| 条件 | offload |
|------|---------|
| `PcmBitPreserve` + EQ 关 + **无 spectrum tap**（`spectrumTapEnabled()==false`） | **enable**（与现网 HIFI 无 EQ 无 tap 对齐） |
| 任意 spectrum tap 活跃（含隐式，§7.6） | **disable** |
| EQ 开 / LegacyFullChain | **disable** |
| `FloatDsp`（P3+） | 集成测试记录；float + offload 兼容性因设备而异 |

P3 集成测试须记录 float AudioTrack 路径下的 offload 与功耗，避免「音质对但延迟/发热异常」未被发现。

### 10.5 现有测试迁移

| 测试 | 现网假设 | 改造后动作 |
|------|----------|------------|
| `MicaAudioProcessorChainTest` | 固定 3 processors + Sonic tail | 按 profile 拆分断言；PcmBitPreserve 无 Sonic |
| `MicaCompositePlayerCommandRoutingTest.rebuildAudioPipelineRecreatesRenderers*` | 名称暗示 recreate renderers | **rename** + 新测 `rebuildPlayerForPipelineProfile` 真换 Sink |
| `PlayerControllerBoundaryTest`（DSD + tuning） | 假定全链 Sonic | 区分 Legacy / DSD passthrough |
| `SpectrumAudioProcessorTest` | flush 与 enabled 耦合 | profile rebuild 后 flush 行为 |
| 新增 | — | `decideSharedPcmProfile` 矩阵；P1 restore profile；AUTO 切歌 rebuild |

### 10.6 频谱双开关与隐式 tap（§7.6 摘要）

见 §7.6 完整说明；集成测试须用 **audiophile mini player** 或 **photo stack cover** 覆盖隐式 tap 路径。

---

## 11. 验收标准总表

| 场景 | 期望 |
|------|------|
| 24-bit FLAC，无 DSP | 不降 16-bit，不进 Quantizer |
| 24-bit FLAC，只开频谱 | 主链不变，频谱正常 |
| 24-bit FLAC + EQ，float 支持 | FloatBridge active，Quantizer inactive，AudioTrack float |
| 24-bit FLAC + Sonic，float 支持 | Sonic active，频谱无长时间断供 |
| 24-bit FLAC + Sonic，float 不支持 | 仅最后一次 Quantizer，优先 24-bit |
| DSD，无 DSP | DsdDecimation 整数输出，策略不变 |
| DSD + EQ/Sonic | DsdDecimation float 输出，优先 float AudioTrack |
| FLAC ↔ DSD 切歌 | Profile 切换，Sink 参数更新 |
| 服务 kill restore | 首曲 profile 与 Sink 一致 |
| UsbDirectPcm | 独立 path，不污染 SharedPcm |
| UsbNativeDsd | 仅预留 |

---

## 12. 推荐执行顺序

```text
P0  格式诊断与基线
 ↓
P1  SharedPcm 双 Profile（PcmBitPreserve + DsdDecimation）+ 真重建 ExoPlayer
 ↓
P2  Float delivery probe
 ↓
P3  Float DSP path（EQ float in/out）
 ↓
P4  Sonic 变速/变调 + 频谱共存
 ↓
P5  Quantized fallback
 ↓
P6  PlaybackOutputMode + USB DirectPcm 预留
```

---

## 13. 最终架构决策摘要

**不采用**：

```text
链内 float → 默认量化回 24/16-bit 交付
一个 SharedPcm Sink 配置 + 所有 Processor 靠 active/inactive
```

**采用**：

```text
无 DSP：bit-preserve（SharedPcm 内按 Profile 分 Sink）
有 DSP：float in / float out
float 不支持：链路末尾一次 Quantizer fallback
DSD 与普通 PCM：不共用 Sink Builder 配置
USB Host 真独占：独立 output adapter；现有 PlaybackOutputMode 仅为兼容骨架
```

---

## 14. 关键源码索引

| 主题 | 路径 |
|------|------|
| Processor 链 | `app/.../media/MicaAudioProcessorChain.kt` |
| P0 格式 trace | `app/.../media/PipelineFormatTraceAudioProcessor.kt` |
| P0 格式日志 | `app/.../media/PcmFormatDiagnostics.kt` |
| P0 Renderer 日志 | `app/.../media/PipelineAudioRendererEventListener.kt` |
| Sink / Renderers | `app/.../media/MicaRenderersFactory.kt` |
| Extractors（DSF） | `app/.../media/MicaExtractorsFactory.kt` |
| 播放协调 / 切歌 | `app/.../media/ServicePlaybackEngineCoordinator.kt` |
| 复合 Player | `app/.../media/MicaCompositePlayer.kt` → `rebuildAudioPipeline` |
| 服务生命周期 | `app/.../media/MicaMediaService.kt` → `onCreate` / `flushAudioPipeline` / `configureQualityMode` |
| 持久化 / restore | `app/.../media/ServicePlaybackStateStore.kt`、`ServicePlaybackStateCoordinator.kt` |
| App 队列 bootstrap | `app/.../data/PlayerController.kt` → `bootstrapQueue` / `syncQueueToService` |
| 导航 override | `app/.../media/PendingPlaybackNavigation.kt` |
| DSD 降采样 | `app/.../media/DsdDecimationAudioProcessor.kt` |
| 频谱 tap / 分析 | `app/.../media/SpectrumAudioProcessor.kt`、`MicaSpectrumAnalyzer.kt` |
| 频谱资格（含隐式） | `app/.../data/preferences/PlaybackUiPreferences.kt` → `spectrumTapEnabled` |
| EQ 单例 | `app/.../media/MicaEqualizerManager.kt` |
| EQ processor | `app/.../media/eq/SoftwareEqualizerAudioProcessor.kt` |
| Hi-Res / route | `app/.../media/DsdOutputPolicy.kt`、`AudioOutputCapabilities.kt` |
| 格式模型 | `app/.../media/AlacPcmFormat.kt` |
| DSD 判定 / 路由 | `app/.../data/DsdSupport.kt`、`PlaybackArchitecture.kt` → `PlaybackRouter` |
| 音质模式枚举 | `app/.../media/PlaybackArchitecture.kt` → `AudioQualityMode` |
| 变速 API | `app/.../data/PlaybackTuning.kt` |
| 音质许可 | `.cursor/rules/audio-quality-consent.mdc`、`CONTEXT.md` |

---

## 15. 架构集成风险清单

> 子 Agent 架构审查（2026-07-07）结论：Sink/Processor 设计覆盖约 **65–70%**；本节补齐与现网 Service / 队列 / 持久化接线风险。实施 P1 前须逐项过表。

### 15.1 风险总表

| ID | 风险 | 严重度 | 文档章节 | P1 必须处理 |
|----|------|--------|----------|-------------|
| R1 | 只改 Factory 不 release Exo，profile 不生效 | 高 | §7.4 | ✅ |
| R2 | 自动下一曲 `onMediaItemTransition(AUTO)` 不 rebuild | 高 | §4.6 | ✅ |
| R3 | 文档基线与代码不一致（已有 Sonic tail） | 高 | §1.1、§4.4 | ✅ |
| R4 | EQ toggle 仅 flush，不换 Legacy ↔ bit-preserve | 高 | §4.4、§4.6 | ✅ |
| R5 | 冷启动 restore 不选 profile | 高 | §4.7、P1 验收 | ✅ |
| R6 | rebuild 与队列 / PendingNavigation / seek 竞态 | 中 | §7.5 | ✅ |
| R7 | `AudioQualityMode` 与真实 Sink 不一致 | 中 | §4.7 | ✅ |
| R8 | 频谱隐式开启（mini player / cover flow）误判「无 tap」 | 中 | §7.6 | 验收时注意 |
| R9 | 路由变化后 DSD target 与 AudioTrack 不匹配 | 中 | §5.2 | P1 日志 |
| R10 | EQ 单例跨 profile configure 时序 | 中 | §7.2 | ✅ |
| R11 | audioSessionId 重建后 EQ attach | 中 | §7.2、§7.4 | ✅ |
| R12 | rebuild 漏接 Extractors / ALAC selector | 高 | §7.2、§7.4 | ✅ |
| R13 | DSD 24-bit + 用户 speed≠1  silent 无效 | 中 | §6.5 | P1 UX/日志 |
| R14 | 误导性测试名 `rebuildAudioPipelineRecreatesRenderers` | 低 | §10.5 | P1 改测 |
| R15 | PcmBitPreserve offload 与现网 HIFI 不一致 | 低 | §10.4 | P1 决策 |
| R16 | USB / Full mode 与 SharedPcm 污染 | 高 | §4.1、P6 | P6 |
| R17 | rebuild 后 `attach` 误触发 `onEnabledChanged` → 误 flush | 高 | §18.7 | ✅ 已修（2026-07-08） |
| R18 | `mediaSession.release()` 导致 MediaController 断链、UI/进度冻结 | 高 | §18.1 Gate 1 | ⏳ **Gate 1** |
| R19 | 把 P1 split Profile 当作终态，持续堆 rebuild 补丁 | 中 | §18 | 按 Gate 2 收敛 |

### 15.2 验收检查清单（随 Gate 更新）

**Gate 1 — 集成（与链拓扑无关，优先）**

```text
□ rebuild / EQ / 切歌 时 mediaSession 不断开（优先 setPlayer；禁止 release 为默认）
□ onControllerDisconnected 有 retryConnect + 全量 resync（兜底）
□ 冻结验收 §18.6 条目 2、4、6 全过
```

**Gate 2 — 链拓扑（二选一，见 §18.4）**

```text
□ 候选 X 或 Y 在同一验收表上对比完成
□ 选定方案写入 §18.4「已选」
```

**原 P1 split Profile 清单（仅当 Gate 2 选候选 Y 时适用）**

```text
□ ExoPlayer.release() 在 profile 切换时确实调用
□ AUTO 下一曲 FLAC→DSD 日志出现 pipeline-rebuild
□ bootstrap restore 后 AudioSinkBuild 与首曲类型一致
□ EQ 开：LegacyFullChain + offload disabled
□ EQ 关 + 无 tap：PcmBitPreserve + offload enabled（§10.4）
□ enableFloatOutput 实验结论已写入（false；float 留 P3）
□ MediaSession 换芯后 MediaController 仍可连接  ← 当前未过
```

### 15.3 覆盖状态说明

| 类别 | 状态 |
|------|------|
| Sink Profile / Processor 语义 | ✅ 已覆盖（§4–§6） |
| Service 重建 / 切歌 / 队列 | ✅ 本节 + §7.4–§7.5 |
| 持久化 / AudioQualityMode | ✅ §4.7 |
| 频谱 / offload / 路由 | ✅ §7.6、§10.4–§10.6、§5.2 |
| USB / Native DSD | ⏳ P6 预留 |
| SilenceSkipping | 非目标 §3.2 |

---

## 16. 频谱：双开关与隐式 tap（§7.6）

### 16.1 两套开关

| 开关 | 位置 | 作用 |
|------|------|------|
| `SpectrumAudioProcessor.setEnabled` / pipeline | `MicaSpectrumAnalyzer.setEnabled` | Processor 是否在链上 tap；影响 offload（经 `spectrumTapEnabled()`） |
| `setAnalysisActive` | `MainActivity` 生命周期 | UI 前台是否跑 FFT/绘制；**不改变** Processor 链结构 |

文档与验收中的「只开频谱」指 **pipeline tap 活跃**；与 analysis 绘制可独立。

### 16.2 隐式 tap（非设置页「频谱开关」）

`PlaybackUiPreferences.spectrumTapEnabled(context)` 为 true 当：

```text
spectrumEnabled（设置）
|| miniPlayerStyle == AUDIOPHILE
|| playerCoverFlowMode.usesPhotoStack
```

**风险**：用户未开设置页频谱，仍可能 tap 活跃 → offload 关闭。P1 bit-preserve 验收须 **显式关闭** 上述条件，或在日志中记录 `spectrumTapEnabled=true (implicit=…)`。

### 16.3 Pipeline flush 触发

| 事件 | 现网 | P1+ |
|------|------|-----|
| 设置页频谱开关 | `flushAudioPipeline` | 仍 flush；通常 **不换** profile |
| mini player 样式 / cover mode 变化 | 可能改变 `spectrumTapEnabled` | 至少 `configureQualityMode`；若 tap 从 off→on 仅影响 offload |
| profile 变化 | — | **Deprecated**：Gate 2 验 X 时不 rebuild；仅 Y fallback |

---

## 18. 终态目标与实施 Gate（2026-07-08）

> **用途**：实施前对齐「端什么、先验什么、不过 Gate 不写代码」。  
> **原则**：改造期可以脏；**终态**不保留 Session release、不保留 log 驱动补丁。

### 18.1 产品终态（三句话）

1. **一个 Exo + 一个 MediaSession**，App 侧 **MediaController 长连接**（断则自动重连 + resync）。  
2. **稳态播放**以 **flush / Processor configure** 为主，**不以** Exo/Session 重建为常规路径。  
3. **24-bit 交付**单独在 **P2 Delivery** 攻关，**不**与 Profile 拆分绑在一起承诺。

### 18.2 已证实 vs 待验

| 已证实（可当约束） | 待验（Gate 前不得写死终态） |
|--------------------|---------------------------|
| `enableFloatOutput=false` → Sink 入口常 toInt16（P0） | 统一链 FLAC↔DSD **无需** Exo rebuild（Gate 2a） |
| `enableFloatOutput=true` → bypass 链（P1 实验） | `MediaSession.setPlayer` 在本项目可用（Gate 1） |
| 无 `DsdDecimation` 时 ~1.4MHz PCM → OUTPUT_FAILED | P2 任一方案在 target 机保 24-bit |
| `mediaSession.release()` → UI/进度断（P1 实机） | Sonic 常挂链尾的 CPU/发热 |
| `DsdDecimation` 对普通 PCM passthrough（代码） | 曲末预切换是否必要 |

### 18.3 终态候选（**默认 X**；Y 仅 fallback）

| | **X 统一固定链（默认执行）** | **Y Profile + rebuild Exo（fallback）** | **Z 暂停 P1** |
|--|------------------------------|------------------------------------------|---------------|
| **链** | DsdDec → Spectrum → EQ → Sonic（isActive） | 三套 Factory | pre-P1 单链 |
| **切歌/EQ** | configure + flush | Exo rebuild（Session **不断**） | flush |
| **24-bit** | 不改善（P2） | 不改善（P2） | 不改善 |
| **集成风险** | **低**（不常规 rebuild） | **高**（已引爆 A） | 低 |
| **已选** | **☑ 默认** | ☐ 仅 X 证伪后 | ☐ 短期止血 |

**执行判断（非「倾向」，是默认）**：

```text
Gate 2 只验 X，直到 §18.5 冻结表失败。
失败 → 贴整段日志 → 开会 → 才允许启用 Y。
Gate 2 验 X 期间：禁用 rebuildPlayerForPipelineProfile、maybePreRebuild、ensurePipeline 驱动 rebuild。
```

**Gate 2 对比时看什么（不看日志漂不漂亮）**：

1. 有无无谓 `pipeline-rebuild`  
2. MediaController 是否断链  
3. `currentSong` 是否错  
4. 进度是否停  
5. AUTO 切歌是否稳定  
6. DSD 是否仍进 DsdDecimation  
7. EQ toggle 是否只触发必要 flush  

**X 过表 → 删 Y 代码；X 不过 → Y 可试，但 Session 禁止 release。**

### 18.4 Gate 顺序

```text
Gate 0  冻结 §18.5 验收表 + 测试曲库；P1 Profile 代码标 Deprecated
Gate 1  只修 A（集成）：Session 不断 + Controller 重连 + resync
        **见 §18.8 实验设计；设计未冻结前不写代码**
        禁止：改 Processor 链 / Profile 决策 / 预切换 / rebuild 新补丁
Gate 2  默认只验 X（统一链）；**见 §18.9 实验设计；设计未冻结前不写代码**
        禁用 rebuild 路径；过 §18.5 全部
        X 证伪 → 才启用 Y（Session 仍禁止 release）
Gate 3  P2 Delivery（须 Audio quality consent）
Gate 4  删债：trace→debug；删 Y/rebuild 死代码  ✅
```

**Gate 1 最小任务**：

1. 过渡 rebuild 若仍存在：**禁止** `mediaSession.release()` 作常规路径（优先 `setPlayer`）。  
2. `PlayerController` disconnected → **自动 retryConnect**。  
3. 重连后 **全量 resync**（queue / current / position / state）。  
4. EQ toggle、AUTO 切歌、kill restore **不** 造成 UI 永久断链。

**纪律**：没过当前 Gate，不开下一 Gate 的播放代码。**失败先改文档/Gate，不 log 驱动补丁。**

### 18.5 冻结验收表（Gate 1 起用）

> **Gate 分工**：**Gate 1** 只硬卡 **子集**（§18.8.8）；**Gate 2** 硬卡 **全表 #1–#7**。  
> Gate 1 阶段 #1/#3/#7 可冒烟记录，**不过不算 Gate 1 失败**。

| # | 场景 | 通过标准 | Gate 1 | Gate 2 |
|---|------|----------|--------|--------|
| 1 | 24/96 FLAC 稳播 2min | 有声、进度走；无 OUTPUT_FAILED | 冒烟 | **硬** |
| 2 | EQ 开→关→开（同曲） | 进度连续；currentSong 不变；Controller 非永久 null | **硬** | **硬** |
| 3 | DSD/DSF 稳播 | 符合 DsdOutputPolicy；有声 | 冒烟 | **硬** |
| 4 | FLAC **AUTO**→DSD→**AUTO**→FLAC | 封面/标题随切歌；进度正常 | **硬** | **硬** |
| 5 | 手动 next/prev 混格式 | 同 4 | 冒烟（建议同测） | **硬** |
| 6 | kill + restore | 首曲类型对；UI/进度对 | **硬** | **硬** |
| 7 | 大队列 seek-existing | 优先 `exo-seek-existing`；避免无谓整队 rebuild | 冒烟 | **硬** |

**失败时**：贴 `AudioPipeline*` / `Player` / `PlaybackEngine` / `OUTPUT_FAILED` **整段**；不单条 log 改逻辑。

### 18.6 P1 过渡态代码（2026-07-08）

| 组件 | Gate 2 后 | Gate 4（2026-07-08） |
|------|-----------|----------------------|
| `SharedPcmPipelineDecider` | ✅ → `SharedPcmPipelineDiagnostics` | ✅ 仅 debug `logSongFormat` |
| `rebuildPlayerForPipelineProfile` | ✅ **已删** | — |
| `maybePreRebuildForUpcomingProfile` | ✅ **已删** | — |
| `ensurePipeline` → rebuild | ✅ **已删** | — |
| `MicaRenderersFactory(profile)` 三分支 | ✅ **单链** | — |
| P0 `PipelineFormatTrace*` | release 仍打 | ✅ **debug only**（release 链内无 trace processor） |
| `MicaCompositePlayer.rebuildAudioPipeline` | 误导命名 | ✅ **`flushPlaybackPipeline`** |

**命名**：过渡 enum `PcmBitPreserve` 易误导（不 preserve 24-bit）；合并单链时 **整 enum 删除**，勿改名后继续用。

### 18.8 Gate 1 实验设计（**先设计，后编码**）

> **状态（2026-07-08）**：验收集 **已冻结（子集）**；实现顺序/改动边界见下文；**编码**仍待「按 18.8 做」。  
> **范围**：只修 **A 层（集成）**；**不**改 Processor 链、Profile 决策、Gate 2 统一链。  
> **目的**：验证「Exo 仍可 rebuild（过渡代码保留）时，UI/Session **不断链**」——为 Gate 2 清障，**不是**证明 Profile/rebuild 正确。

#### 18.8.0 Gate 1 验收集（已确认：只过子集）

| 级别 | # | 说明 |
|------|---|------|
| **硬门槛** | **2、4、6** | EQ toggle；AUTO 混格式；kill restore（冷启动回归） |
| **建议同测** | 5 | 手动 next/prev；失败按 #4 同类处理 |
| **冒烟（不挡 Gate 1）** | 1、3、7 | 记录结果；失败 **不** 判 Gate 1 不过 → 留 Gate 2 |

**#6 失败时**：Gate 1 **不过**，但归因时区分 **冷启动/持久化** vs **setPlayer（H1）**——勿把 restore 问题误当成 Profile 补丁理由。

#### 18.8.1 问题与根因（已证实）

```text
rebuildPlayerForPipelineProfile()
  → mediaSession.release()          // L251–252 MicaMediaService.kt
  → App MediaController 断开
  → PlayerController.onControllerDisconnected()
       controller = null; connectStarted = false   // 无 retryConnect
  → syncPosition() early-return
  → UI：isPlaying 可能仍 true，进度条停，currentSong 可能 stale
```

**已证实**：`mediaSession.release()` → UI/进度断（P1 实机）。  
**待验**：`MediaSession.setPlayer()` 在本项目栈（Media3 **1.9.0** + `MicaCompositePlayer`）下是否 **不断 Controller**。

#### 18.8.2 假设（可证伪）

| ID | 假设 | 证伪条件 |
|----|------|----------|
| **H1** | Service 侧用 `mediaSession.setPlayer(newComposite)` 替代 `release()`+`Builder`，Controller **保持连接** | 仍出现 `onControllerDisconnected` 或系统控件失效 |
| **H2** | 若 H1 仍偶发断链，Client 侧 **自动 retryConnect + 全量 resync** 可在 2s 内恢复 UI | 重连后 §18.5 #2/#4 仍失败，或进入重连风暴 |
| **H3** | Gate 1 通过 **不依赖** Gate 2（统一链）；rebuild 路径仍可触发，只验集成韧性 | （非证伪）Gate 1 过、Gate 2 仍可能失败 —— 两 Gate 独立 |

#### 18.8.3 非目标（本 Gate 不做）

- 不删 `rebuildPlayerForPipelineProfile`（Gate 2 删 X 时一并删）。  
- 不验证 24-bit、OUTPUT_FAILED、DsdDecimation 时序（Gate 2 / P2）。  
- 不加曲末预切换、不改 `ensurePipeline` 决策逻辑。  
- 不承诺「EQ toggle 零 flush」——只要求 **UI 不断**。

#### 18.8.4 实验阶段（严格顺序）

| 阶段 | 做什么 | 改代码？ | 过/不过 |
|------|--------|----------|---------|
| **G1-0 基线** | 当前 HEAD；跑 §18.5 **#2、#4、#6**；录 log | 否 | 必须 **复现** UI 断链；不能复现则改验收场景 |
| **G1-1a Service** | `rebuildPlayerForPipelineProfile`：`setPlayer` 换 player；**不** `mediaSession.release()`；仍 `exoPlayer.release()` 旧实例 | 是（仅 Service 一条路径） | H1：无 disconnect 或 disconnect→立即恢复 |
| **G1-1b Client** | `onControllerDisconnected`：限次 retryConnect（如 3 次/5s）；`onConnected` 已有 resync **补全** queue mirror | 是（仅 PlayerController） | H2：断链可恢复；无永久 null |
| **G1-2 回归** | 再跑 **子集 #2、#4、#6**（+#5 建议）；#1/#3/#7 冒烟记录 | — | **子集全过** = Gate 1 过 |

**实施顺序**：G1-0 → G1-1a 单独验 → 若 1a 不够再加 1b → G1-2。**禁止** 1a+1b 同批合入（否则分不清谁生效）。

#### 18.8.5 Service 侧设计（G1-1a）

**文件**：`MicaMediaService.kt` — 仅 `rebuildPlayerForPipelineProfile`（及必要时 `onDestroy` 仍 release）。

**伪代码**：

```text
detach coordinators（不变）
oldExo = exoPlayer; oldComposite = compositePlayer

stack = ExoPlaybackStackFactory.build(...)
exoPlayer = stack.exoPlayer
compositePlayer = stack.compositePlayer
restoreQueue(...)

configureQualityMode; attach coordinators; attachEqualizerSessionListener

session = mediaSession
if (session != null) {
  check: stack.compositePlayer.applicationLooper == session.player.applicationLooper
  session.setPlayer(stack.compositePlayer)   // 主线程
} else {
  mediaSession = MediaSession.Builder(...).build()  // 仅首次
}

oldExo?.release()   // setPlayer 之后；Media3 不替 release 旧 player
```

**前置检查（编码前必做）**：

1. `MicaCompositePlayer` / 新 `ExoPlayer` 的 `canAdvertiseSession()` 为 true（Media3 要求）。  
2. 新旧 player **同一 application looper**（`ExoPlayer.Builder(context)` 默认主线程，应满足）。  
3. `setPlayer` 在 **主线程** 调用（与 `rebuildPlayerForPipelineProfile` 现有调用栈一致）。

**风险（待 1a 实机观察）**：

- 通知 / MediaStyle 是否在换 player 时闪断。  
- `attachEqualizerSessionListener` 是否需从 old exo 解绑再绑 new exo（与 Session 无关，但可能影响 EQ）。  
- 系统 MediaController 是否缓存旧 timeline（靠 Client resync 兜底）。

#### 18.8.6 Client 侧设计（G1-1b，仅 H1 不足时）

**文件**：`PlayerController.kt` — `onControllerDisconnected`、`onConnected`（及可选 `retryConnect` 策略）。

**行为**：

```text
onControllerDisconnected:
  若 disconnectReason == 用户杀 Service / 明确 teardown → 不重试（保持现逻辑）
  否则若 retryBudget 未用尽 → postDelayed(retryConnect, 200–500ms)
  仍清空 controller，但 connectStarted 策略需与 retry 协调（避免永久 false）

onConnected（已有部分 resync，需显式验收）:
  scheduleQueueMirrorFromPlayer / syncIndexFromPlayer
  syncPosition(); publishPlaybackStates()
  若 UI currentSong != controller.mediaId → 以 controller 为准（或一次 reconcile 日志）
```

**防风暴**：最多 3 次/5s；失败后 `postUserMessage` + 停止自动重试，等用户 `connectIfNeeded`。

**单元测试（可选、非 Gate 通过条件）**：

- Robolectric 模拟 disconnect → 断言调用了 retry（mock connector）。  
- **Gate 1 仍以 §18.5 实机为准**。

#### 18.8.7 观测与日志（G1-0 起统一）

| Tag | 字段 | 用途 |
|-----|------|------|
| `AudioPipeline` | `pipeline-rebuild`, `setPlayer=1` vs `session-released=1` | 区分 1a 是否生效 |
| `Player` | `controller-disconnected`, `retry=n`, `controller-connected` | H2 |
| `Player` | `position-sync-skipped` | 断链后 stale 检测 |
| `PlaybackEngine` | seek / AUTO transition | #4 切歌 |

**G1-0 基线**：应看到 `session-released`（或等价 release 路径）+ `controller-disconnected` + `position-sync-skipped`。  
**G1-1a 期望**：rebuild 时 **无** `controller-disconnected`；或有但 **无** `position-sync-skipped` 持续 >2s。

#### 18.8.8 Gate 1 通过 / 失败标准

**通过（全部满足）**：

- §18.8.0 **硬门槛 #2、#4、#6** 实机通过。  
- rebuild 触发时（EQ toggle 或 FLAC↔DSD 自动切歌）：`controller` 非永久 null；进度条连续或 2s 内恢复。  
- 无重连风暴（5s 内 retry ≤3）。  
- **音质**：G1 改动 **不改变** Processor/Sink 配置（无 Audio quality consent 项）。  
- #1/#3/#7 冒烟结果写入记录即可，**不挡** Gate 1。

**失败（任一条即 Gate 1 不过）**：

- 硬门槛 **#2 / #4 / #6** 任一失败。  
- setPlayer 抛 `IllegalArgumentException` / looper 不一致 → **先修设计**，不 patch Profile。  
- UI 仍永久断链 → 记录 log，**不得** 同时上 Profile/预切换补丁；回到 §18.8.4 拆 1a/1b。  
- **#6 失败**：Gate 1 不过；查冷启动/restore 路径，**不** 混入 Gate 2 链拓扑改动。

**回滚**：G1-1a/1b 各一个 commit；不过验收 **revert 该 commit**，不保留「半套 Session 修复」。

#### 18.8.9 与 Gate 2 的边界

| | Gate 1 | Gate 2 |
|--|--------|--------|
| rebuild Exo | **允许**（过渡代码） | **默认禁用**（验 X） |
| Session | setPlayer / 不断 | 不应再 rebuild Session |
| 链拓扑 | **不动** | 统一固定链 |
| 验收 | **子集 #2/#4/#6**（§18.8.0） | **全表 #1–#7** |

Gate 1 过 **只说明** A 层可支撑后续切链实验，**不说明** Profile/rebuild 是终态。

#### 18.8.10 待确认项（编码前）

| # | 决策 | 状态 |
|---|------|------|
| 1 | G1-1a 单独先发 | 推荐 **是**；待「按 18.8 做」 |
| 2 | 1a 通过后是否仍加 1b retry（单独 commit） | 推荐 **是**（保险）；待确认 |
| 3 | Gate 1 验收集 | ✅ **已确认：子集 #2/#4/#6；#5 建议；#1/#3/#7 冒烟** |
| 4 | Gate 1 禁止改 `MicaRenderersFactory` / `SharedPcmPipelineDecider` | 推荐 **禁止**；待确认 |

**动代码前**：回复「按 18.8 做」（或改上表 1/2/4）。

#### 18.8.11 G1-0 基线执行（**当前步骤**）

> **状态**：**Gate 1 已通过**（2026-07-08，log 19）  
> **下一步**：**Gate 2** — 默认验候选 X（统一链）；禁用 rebuild 路径

**0. 装包**

```powershell
cd d:\AI\3\mica-music-a8fa312e3b45477f922d0dde3ca38e99d203cebc
.\gradlew.bat installDebug
```

**1. 开 log（PC 连 OPPO USB 调试）**

```powershell
adb logcat -c
adb logcat -s MICA_DIAGNOSTICS:D | Tee-Object -FilePath gate1-g1-0-log.txt
```

过滤关键字（肉眼搜）：`AudioPipeline`、`Player`、`PlaybackEngine`、`pipeline-rebuild`、`position-sync-skipped`、`OUTPUT_FAILED`。

> **说明**：现网 **无** `controller-disconnected` 日志；G1-0 用 **UI 症状 + pipeline-rebuild + position-sync-skipped** 推断断链。

**2. 测试曲库（最少）**

| 用途 | 曲 |
|------|-----|
| #2 | 一首 **24/96 FLAC**，EQ **关**，Sonic 默认 |
| #4 | 同上 FLAC + 至少一首 **DSF/DSD**；AUTO 切歌开 |
| #6 | 任意在播曲；播到 ~30s 再 kill |

**3. 场景步骤与期望**

| 场景 | 操作 | UI 失败长什么样 | log 期望（基线应出现） |
|------|------|-------------------|------------------------|
| **#2** | 播 FLAC → 开 EQ → 关 EQ → 再开 | 进度条停；播放图标仍像在玩；标题可能对 | `pipeline-rebuild`（EQ 切换时）；随后 `position-sync-skipped` |
| **#4** | 队列：FLAC → DSD → FLAC；**AUTO** 播完切 | 切歌后封面/标题不更新；进度不走 | 曲间 `pipeline-rebuild`；可能 `OUTPUT_FAILED` |
| **#6** | 播 30s → 系统杀 App（上滑清任务）→ 再开 App | （基线 **应通过**）首曲/进度对 | 无 rebuild；有 restore 相关 log |

**4. G1-0 过关标准**

- **#2 或 #4** 至少一个 **稳定复现** UI 断链（与 P1 实机一致）。  
- log 里在断链窗口能看到 **`pipeline-rebuild`**，且 UI 卡死后常有 **`position-sync-skipped`**。  
- **#6** 记录结果；若失败也记，但归因归冷启动，不阻塞「复现 #2/#4」结论。

**5. 若 #2/#4 都复现不了**

- 换 OPPO 上 **确实会 rebuild 的曲**（FLAC+EQ、FLAC↔DSD）。  
- 确认装的是 **含 P1 rebuild 代码** 的 debug，不是旧 APK。  
- 仍不能复现 → 在 `baseline-run.md` 写「G1-0 未复现」，**暂停 G1-1a**，先查触发条件。

**6. 贴回内容（给我或写进 baseline-run.md）**

每个失败场景一段：`场景 #` + UI 现象 + 从 `pipeline-rebuild` 前 5s 到 `position-sync-skipped` 后 10s 的 `MICA_DIAGNOSTICS` 行。

### 18.9 Gate 2 实验设计（候选 X：统一固定链）

> **状态**：**G2-2 实机已通过**（2026-07-08，log 20）——**Gate 2 ✅**  
> **前提**：**Gate 1 已通过**（setPlayer；log 19）。  
> **目的**：去掉 **Profile 驱动的 Exo rebuild**，稳态只靠 **Processor `configure` / `isActive` + `flush`**；过 **§18.5 全表 #1–#7**。  
> **不承诺**：24-bit 不降级（仍 P2）。

#### 18.9.0 为什么要 Gate 2（Gate 1 之后仍有什么问题）

Gate 1 只修了 **A 层**（Session 不断）。log 19 仍可见：

| 现象 | 原因（B 层） | Gate 2 目标 |
|------|----------------|-------------|
| FLAC→DSD **播放键闪 2 下** | 曲末 **预切换 rebuild** + AUTO 切歌，Exo 短暂 `isPlaying` 脉冲 | **零** `pipeline-rebuild` |
| DSD→FLAC **`OUTPUT_FAILED`** | rebuild 窗口内 **错误 Factory 链** 接到 DSD 解码输出 | 单链 + `DsdDecimation.configure` 切换 |
| EQ / 切歌 log 满屏 `sessionStrategy=setPlayer` | 三套 Profile 换 Factory | 仅 service 启动时建 **一次** Sink |

#### 18.9.1 候选 X 定义（与现网代码的对应关系）

**一条 Processor 链，始终挂在同一个 Exo 上**（等价于现 `MicaRenderersFactory.buildLegacyFullChain`）：

```text
sink-entry
→ DsdDecimation          // 非 DSD：configure 后 isActive=false，passthrough
→ after-dsd
→ Spectrum               // isActive = 频谱 tap 开
→ after-spectrum
→ EQ (SoftwareEqualizer) // isActive = EQ 开
→ after-eq
→ PlaybackTuning (Sonic) // speed=1/pitch=1 或 24bit DSD 输出 → passthrough
→ chain-exit
→ AudioTrack
```

**与 P1 三分支的差异**：

| | P1 Profile | X 统一链 |
|--|------------|----------|
| FLAC 无 EQ | 无 DsdDec/EQ/Sonic 的短链 | **全挂**，inactive passthrough |
| DSD 无 EQ | 无 EQ/Sonic | 同上 |
| EQ 开 | rebuild → LegacyFullChain | **不 rebuild**，EQ `isActive=true` |
| 切歌 | 常 rebuild + 预切换 | **`configure` 随解码格式变** |

**已证可依赖**（代码现状，Gate 2 不重新发明）：

- `DsdDecimationAudioProcessor`：非 ~1.4MHz PCM → `isActive=false`（`DsdDecimationAudioProcessor.kt` L34–49）  
- `SoftwareEqualizerAudioProcessor.isActive()` ↔ EQ 开关  
- `SpectrumAudioProcessor.isActive()` ↔ 频谱 tap  
- `MicaPlaybackTuningAudioProcessor`：Sonic 不支持格式或 1.0/1.0 → passthrough  

#### 18.9.2 假设（可证伪）

| ID | 假设 | 证伪条件 |
|----|------|----------|
| **H4** | 单链 + 禁 rebuild 后，FLAC↔DSD / EQ **无需** Exo rebuild 即可播且 UI 正常 | §18.5 **#2/#4/#5** 任一硬项失败 |
| **H5** | 去掉预切换 rebuild 后，**无** log 19 类 `OUTPUT_FAILED`（或 self-heal 且用户不可感知） | 混格式 AUTO 仍 **有声失败/卡死** |
| **H6** | Gate 1 成果保持：无 `position-sync-skipped`、无永久断链 | 任一回归 A 层症状 |
| **H7** | 稳态 log **无** `pipeline-rebuild` / `sessionStrategy=setPlayer`（service 生命周期外） | 正常播放仍频繁 rebuild |

#### 18.9.3 非目标

- 不攻 24-bit / `sink-entry` toInt16（P2）。  
- 不改 Gate 1 的 `setPlayer` 路径为 **删除**——整段 rebuild 删除后，`setPlayer` 仅保留在 git 历史；`onDestroy` 仍 `mediaSession.release()`。  
- 不默认上 **Y**（Profile rebuild）；X 证伪后才讨论。  
- Gate 4 不做（trace→debug、删 enum 可部分随 G2-1 做，但 **不以 Gate 4 挡 G2**）。

#### 18.9.4 代码改动边界（G2-1 单批，避免半套状态）

| 文件 | 动作 |
|------|------|
| `MicaRenderersFactory.kt` | 去掉 `sinkProfile` 参数；**始终** `buildUnifiedChain()`（现 LegacyFullChain 内容） |
| `ExoPlaybackStack.kt` | 去掉 profile 分支；启动时 **一次** `build()` |
| `MicaMediaService.kt` | **删** `rebuildPlayerForPipelineProfile`、`maybePreRebuild*`、`ensurePipelineForSong` 的 rebuild 分支、曲末 watcher；EQ handler **只** flush/configure |
| `ServicePlaybackEngineCoordinator.kt` | **删** `ensurePipeline()` 调用（或改为 no-op）；保留现有 start/seek 逻辑 |
| `SharedPcmPipeline.kt` | **删** enum 驱动；可选保留 `logSongFormat(song)` 诊断 **不** 触发 rebuild |
| 测试 | 更新/删 `SharedPcmPipelineDeciderTest`；Coordinator/Chain 测试随行为改 |

**禁止同批**：P2 float delivery、Gate 4 大规模删 log、PlayerController retry（Gate 1 未做 1b 且不需要）。

**EQ 切换预期路径**（替代 rebuild）：

```text
EqualizerPreferences 变
→ configureQualityMode(offload)
→ MicaEqualizerManager.sync（已有）
→ flushAudioPipeline("equalizer-enabled=…")   // 已有；Legacy 链下 rebuild 后 flush 可去掉
```

**FLAC↔DSD 切歌预期路径**：

```text
Decoder 输出格式变
→ DefaultAudioSink 对各 Processor 调 configure()
→ DsdDecimation active/inactive 自动切换
→ （若 G2-2 仍 OUTPUT_FAILED）再加 **一条** 显式 flush（G2-1b，单独 commit）
```

#### 18.9.5 实验阶段

| 阶段 | 做什么 | 改代码？ | 过/不过 |
|------|--------|----------|---------|
| **G2-0 对照** | 用 **Gate 1 包**（log 19 已有）数 `pipeline-rebuild` 次数 / `OUTPUT_FAILED` | 否 | 记录基线即可 |
| **G2-1 X 实现** | 上表 **单批** 合入 | 是 | 编译 + 单元测试绿 |
| **G2-2 实机** | 跑 **§18.5 全表 #1–#7**（同 G1 曲库，补 #1/#3/#7） | — | **全过** = Gate 2 过 |
| **G2-1b（仅 G2-2 证伪 H5）** | 在 `onMediaItemTransition` 或 coordinator 边界 **仅** 加 `flushAudioPipeline` | 单独 commit | 重跑失败项 |

**G2-0 基线（log 19 已记录）**：单次会话 **≥6 次** `pipeline-rebuild`；至少 **2 次** `OUTPUT_FAILED` 自 heal。

#### 18.9.6 观测与 log 契约

| 稳态期望 | 说明 |
|----------|------|
| **无** `pipeline-rebuild` | 播放/EQ/切歌全程 |
| **无** `sessionStrategy=setPlayer` | 同上 |
| **有** `pipeline-flush` | EQ / 频谱开关 |
| **有** `DsdProcessor` `[DEBUG-dsd-output]` | DSD 曲 active；FLAC 曲无 output 行或 passthrough |
| **无** `position-sync-skipped` | 同 Gate 1 |
| `AudioSinkBuild` | **Service 启动 1 次**（+ 进程重建） |

#### 18.9.7 Gate 2 通过 / 失败

**通过（全部）**：

- §18.5 **#1–#7** 实机通过（#6 仍允许 **~1–2s** restore 误差，同 Gate 1 口径）。  
- H6/H7：无 A 层回归；稳态无 rebuild log。  
- DSD 仍符合 `DsdOutputPolicy`（#3）。  
- **音质路径不变**：仍 `enableFloatOutput=false`；无新降质（无 consent 项）。

**失败（任一条）**：

- 硬验收任一项失败 → **先 G2-1b 单次 flush 实验**；仍失败 → **开会**，可选启用 **Y**（Profile + rebuild，但 **必须** 保留 Gate 1 setPlayer，**禁** release Session）。  
- **不得** 在 X 失败后立刻「恢复预切换 + 再加 patch」而不更新 §18.9 / 判 Y。

**回滚**：G2-1 一个 revert 回到 Gate 1 通过态（仍有 setPlayer rebuild 路径）。

#### 18.9.8 与 Y / Gate 3 / Gate 4 关系

```text
X 过 → 删 rebuild / Profile / 预切换 死代码（可与 Gate 4 合并）
X 不过 → Y（rebuild + setPlayer）仅 fallback；仍不追 bit-preserve
Gate 3 P2 → 与 Gate 2 独立；24-bit 仍单开
```

#### 18.9.9 待你确认（编码前）

| # | 决策 | 状态 |
|---|------|------|
| 1 | G2-1 **单批**合入 | ✅ 2026-07-08 |
| 2 | `SharedPcmPipelineDecider` 删 enum 驱动 | ✅ → `SharedPcmPipelineDiagnostics` |
| 3 | G2-2 验收集 **§18.5 全表** | ✅ |
| 4 | G2-1b flush 仅 H5 失败时 | ✅ |
| 5 | 播放键闪 / OUTPUT_FAILED 观察项 | ✅ |

**G2-1 已合入**（2026-07-08）——**G2-2 实机已通过**（log 20；全表 #1–#7）。

### 18.10 Gate 4 删债（2026-07-08）✅

> **范围**：零音质行为变更；release 去掉 P0 trace 开销与误导命名。

| 项 | 动作 |
|----|------|
| `PipelineFormatTraceAudioProcessor` | release **不插入**链；debug 保留 sink-entry / after-* / chain-exit |
| `PcmFormatDiagnostics` / `PipelineAudioRendererEventListener` | `BuildConfig.DEBUG` 才打 log |
| `SharedPcmPipelineDiagnostics` | 删 `legacyDspActive`；`logSongFormat` debug only |
| `MicaCompositePlayer` | `rebuildAudioPipeline` → **`flushPlaybackPipeline`** |
| Y / Profile rebuild | G2-1 已删；Gate 4 无新增 |

**release 诊断 log 仍保留**：`AudioPipeline: pipeline-flush`、`PlaybackTuning: sonic-disabled`、`AudioCapability` / `DsdOutput` 等运行态事件（非 P0 格式 trace）。

**下一步**（历史，已被 R 取代）：~~G3-1b AUTO 时机修（§18.13.4）~~ —— G3-1b 已废弃，AUTO race 随之作废；hi-res 交付改由候选 R（renderer-split）承担，见 §18.14。

### 18.11 Gate 3 G3-0 delivery probe（2026-07-08）✅

> **范围**：只 probe + log；**不改** `enableFloatOutput`、Processor 链、AudioTrack 交付。

**触发**：切歌 / seek-existing / AUTO 切歌 / kill restore 时，对 Supported 曲目打一条 `PcmDeliveryProbe`（**release + debug** 均有）。

**日志 tag**：`PcmDeliveryProbe`

**字段**：`noDspLadder`（源格式→同率 16-bit）、`dspLadder`（float→24→16，**float 与 24-bit 独立 probe**）、`selectedNoDsp` / `selectedDsp`（预测，未启用）、`dsdIntCandidates`（DSD 时）、`dspActive`（EQ 或变速≠1）。

**实机**：播 24/96 FLAC → 导出 log → 对照 `selectedNoDsp` 与（debug 包）`ProcessorFormat sink-entry` / `AudioTrackDelivery`，确认 toInt16 发生在链内哪一步。

**代码**：`PcmDeliveryFormat.kt`、`PcmDeliveryProbe.kt`、`PcmDeliveryProbeDiagnostics.kt`；`AudioOutputCapabilities.queryIntSupport` / `queryFloatSupport`。

### 18.12 Gate 3-1a EQ-off passthrough 实验（2026-07-08）— **证伪**

> **Consent**：用户授权「EQ 关直通」实验。  
> **范围**：全局 `DefaultAudioSink.setEnableFloatOutput(true)`（整包一个 sink）。  
> **结论（log 26）**：96/24 FLAC 仍 `SpectrumTap enc=2` — **全局 float sink 不足以避免 toInt16**；已由 **§18.13 G3-1b** 取代。

**开关（历史）**：`PcmDeliveryExperiment.kt` → `G31_NO_DSP_FLOAT_SINK_ENABLED`；现网 **恒为 `false`**。

**回滚 / 勿再启用**：见 §18.13；G3-1a 代码路径保留作对照，默认不走。

### 18.13 Gate 3-1b per-song sink rebuild（2026-07-08）— **已废弃**（被候选 R 取代）

> **废弃说明（2026-07-08）**：候选 R（renderer-split，§18.14）定为终选架构后，G3-1b 的 per-song sink rebuild 方案连同其 AUTO 切歌时机 race（§18.13.4）一并作废。代码已删除：`PcmSinkDeliveryDecider`、`PcmSinkDeliveryConfig` 与 service per-song rebuild 逻辑不再存在。下文保留作历史记录，不再维护。
>
> **Consent**：用户授权 Gate 3 delivery 实验（EQ 关 hi-res 直通）。  
> **与 Gate 2 的关系**：Processor **链拓扑不变**（仍为候选 X 统一固定链）；仅在 **`enableFloatOutput` 须变** 时通过 `MediaSession.setPlayer` **重建 Exo + Sink**（不是 Profile 三分支 Factory）。这是 Gate 2「稳态不 rebuild」在 **Gate 3 交付格式** 上的 **有意例外**。

#### 18.13.1 开关与包类型

| 构建 | `g31bPerSongSink` | 行为 |
|------|-------------------|------|
| **release** | `false` | `PcmSinkDeliveryConfig.PRODUCTION`（`enableFloatOutput=false`） |
| **debug / perf** | `true` | `PcmSinkDeliveryDecider` 按曲目 + DSP 状态决策 |

**启动 log（debug/perf）**：

```text
PcmDeliveryExperiment: active=G3-1b-per-song-sink scope=debug-or-perf; …
```

**编译**：`assembleDebug` 或 `assemblePerf`（`-Pmica.qaSideBySide=true` 可选）。

#### 18.13.2 Sink 决策（`PcmSinkDeliveryDecider`）

| 曲目 / 条件 | `enableFloatOutput` | `profileLabel` | 说明 |
|-------------|---------------------|------------------|------|
| PCM hi-res，EQ 关，probe `selectedNoDsp` >16bit | `true` | `G3-1b-pcm-float-sink` | 目标：避免 24→16 toInt16 |
| PCM 16-bit，EQ 关 | `false` | `G3-1b-int16-sink` | 与 production 一致 |
| **DSD（DSF）** | **`false`** | **`G3-1b-dsd-int-sink`** | **不用 float sink**（见下节） |
| DSP 路径且 probe 选中 float | `true` | `G3-1b-dsp-float-sink` | EQ 开或变速/变调 ≠1 |

**比较与 rebuild**：`MicaMediaService.ensureSinkDeliveryForSong` 比较 **完整** `PcmSinkDeliveryConfig`（含 `profileLabel`），**不能**只比 `enableFloatOutput` boolean（log 28：FLAC 与 DSD 曾同为 `true` → 漏 rebuild）。

**rebuild log**：

```text
AudioPipeline: pipeline-rebuild reason=g31b-sink song=… enableFloatOutput=… profile=G3-1b-…
```

#### 18.13.3 为何 DSD 不用 float sink（≠「同样是 float」）

G3-1b 初版曾令 DSD 也 `enableFloatOutput=true`（log 28 **必挂**）。实机结论：

| | **PCM hi-res（FLAC）** | **DSD（DSF）** |
|--|------------------------|----------------|
| 解码器输出 | 96 kHz **24-bit int** | ~1.4 MHz / 11.3 MHz **float** |
| 链上处理 | 直通（EQ 关） | **`DsdDecimation` → 176.4 kHz 24-bit int（`enc=21`）** |
| float sink 作用 | 96 kHz float AudioTrack（probe 已支持） | **无额外保真收益**（decimation 已量化） |
| log 28 失败 | — | `DefaultAudioSink.configure` → `getAudioTrackMinBufferSize` → `IllegalStateException`；常 **无** `DsdProcessor` 行（configure 阶段即失败） |
| 稳定路径 | float sink（G3-1b） | **int sink**（与 §2.3、`DSD_EXO_PLAYBACK.md` 一致） |

**不是音质妥协**：DSD 交付本来就是 decimation 后的 **24-bit int**；float sink 对本机 **不能** 等价于 FLAC 的 float 直通。P4 `DsdDecimationOutputMode.FloatPcm` + Sonic 是 **远期** 另议（须 consent + 实机验 176.4k float）。

#### 18.13.4 AUTO 切歌时机 race（已知缺口，log 29）

**现象**：FLAC（float sink）播完 **自动** 切 DSF 时，可能先 `OUTPUT_FAILED`，再经失败恢复 + `pipeline-rebuild` 才正常播 DSF（log 29 §04:15:13）。

**原因**：

```text
Exo 内部 AUTO 切下一首 → 播放线程 DefaultAudioSink.configure（仍用上一首 sink）
    ↓
onMediaItemTransition(AUTO) → beginAutoTransition → prepareSinkDelivery → rebuild（主线程，偏晚）
```

手动 `start-existing` / `start` **无此问题**（先 `prepareSinkDelivery`，再播）。

**待修方向**（未实现，择一）：

| 方案 | 说明 |
|------|------|
| **A. 曲末预 rebuild** | 当前曲剩余 ~1–2s 时 probe **下一首**，若 `PcmSinkDeliveryConfig` 不同则先 rebuild |
| **B. 跨 profile 禁无缝 AUTO** | sink profile 变化时不依赖 Exo 内部 AUTO，改 `startAt` 手动切（可能丢 gapless） |

**验收（当前）**：手动 FLAC↔DSF ✅（log 29）；AUTO 允许偶发 `OUTPUT_FAILED` 后 self-heal，**不应** 连环失败（log 28 已修）。

#### 18.13.5 实机验收步骤（debug/perf）

1. 启动确认 `PcmDeliveryExperiment: active=G3-1b-per-song-sink`。  
2. **96/24 FLAC，EQ 关**：应有 `pipeline-rebuild … profile=G3-1b-pcm-float-sink`；`SpectrumTap enc=4` 或 `21`（非 `2`）。  
3. **DSF，EQ 关**：应有 `… profile=G3-1b-dsd-int-sink`；`DsdProcessor output=176400Hz/24bit`；`enc=21`。  
4. **手动** FLAC → DSF → FLAC：两次 rebuild，均出声。  
5. **AUTO** FLAC → DSF：记录是否仍有 §18.13.4 的单次 `OUTPUT_FAILED`（待 A/B 修完应消失）。  
6. **回归**：DSD + EQ、Gate 2 场景 #1–#7 不回归。

**历史代码（已删除）**：`PcmSinkDeliveryDecider.kt`、`PcmSinkDeliveryConfig.kt`、`MicaMediaService.ensureSinkDeliveryForSong` / `rebuildExoPlayerForSinkDelivery`、`ServicePlaybackEngineCoordinator.prepareSinkDelivery`。`PcmDeliveryExperiment.kt` 仅保留 renderer-split 开关与启动日志。

**实机 log**：log 26（G3-1a 证伪）、log 28（DSD float 失败 + boolean 漏 rebuild）、log 29（G3-1b 正常 + AUTO 时机残留）。

### 18.14 候选 R：Renderer-split Sinks（方案草案，未验证）

> **状态**：只记录为中期候选；**未过 R0，不得实现 R1，也不得默认启用**。  
> **目标**：在不按曲目重建 ExoPlayer / MediaSession 的前提下，让 DSD 与 PCM / Hi-Res 使用不同 `AudioSink` 配置。  
> **核心思想**：一个 ExoPlayer 内挂多个 audio renderer，每个 renderer 绑定自己的专用 sink；曲目路由交给 renderer support，而不是 Service 层 `rebuildExoPlayerForSinkDelivery`。  
> **与现状关系**：X 仍是短期稳定基线；G3-1b 是 debug/perf 的交付格式实验；R 是为了解决 G3-1b 的 AUTO 时机 race 与长期 DSD/PCM sink 冲突。  
> **音质 consent**：R0/R1 仅日志与 spike；若后续把 `enableFloatOutput=true`、禁用 EQ/Sonic、隐藏频谱或改变默认交付路径带入 release，必须另行说明影响范围并取得明确允许。

#### 18.14.0 一页摘要

当前单 sink 架构的问题已经很明确：

```text
同一个 DefaultAudioSink 同时服务 DSD 与 PCM/Hi-Res
→ DSD 需要 enableFloatOutput=false + DsdDecimation
→ PCM/Hi-Res 希望避开 enableFloatOutput=false 触发的 24→16 toInt16
→ G3-1b 只能靠 Service 层按曲目 rebuild sink
→ AUTO 切歌时 Exo 内部 configure 可能早于 Service rebuild（§18.13.4）
```

候选 R 把「按曲目换 sink」下沉到 renderer 层：

```text
ExoPlayer
├─ DsdOnlyFfmpegAudioRenderer
│   └─ DsdSink(enableFloatOutput=false, DsdDecimation → Spectrum?)
│
├─ PcmOnlyFfmpegAudioRenderer
│   └─ PcmSink(enableFloatOutput=true or PCM delivery strategy, Spectrum?)
│
└─ PlatformDefaultAudioRenderer
    └─ PlatformSink（AAC/MP3 fallback，第一阶段尽量保持默认）
```

如果 renderer support 能稳定互斥，FLAC → DSF → FLAC 的 AUTO 切歌不再需要 Service 抢在 Exo configure 前 rebuild sink；Exo 在选择 renderer 时就已经选定对应 sink。

#### 18.14.1 候选关系

| 候选 | 描述 | 当前判断 |
|------|------|----------|
| **X** | 统一固定 Processor 链 | 已过 Gate 2；release 稳定基线 |
| **G3-1b** | per-song sink rebuild（debug/perf） | **已废弃**（被 R 取代，AUTO 时机 race 作废、flag 关） |
| **Y** | Profile + rebuild Exo | 已踩坑；不再主推 |
| **R** | Renderer-split sinks | **终选架构**（R0–R4 全过；debug/perf，待 release 测试） |
| **Z** | 暂停 P1 / 回 pre-P1 单链 | 紧急止损路线 |

推荐顺序：

```text
短期稳定：X
交付格式实验：G3-1b（debug/perf）
中期正解候选：R
停止深挖：Y
紧急回退：Z
```

#### 18.14.2 R 的目标与非目标

**第一阶段目标**：

1. 保持一个 ExoPlayer。  
2. 保持一个 MediaSession。  
3. 不在 AUTO 切歌时按曲目 rebuild Exo / Sink。  
4. DSF / DSD 只走 DSD renderer + DSD sink。  
5. FLAC / ALAC / WAV / Hi-Res PCM 只走 PCM renderer + PCM sink。  
6. AAC / MP3 第一阶段继续走平台 fallback，减少变量。  
7. 通过日志证明 renderer support accept/reject 矩阵互斥。  

**第一阶段不承诺**：

1. 真正 bit-perfect 24-bit packed 交付。  
2. EQ / Sonic / 频谱在所有格式下的最终形态。  
3. float AudioTrack 不支持时的 Quantizer fallback。  
4. USB DirectPcm。  
5. Native DSD / DoP。  

第一阶段只回答一个问题：

```text
能否在一个 ExoPlayer 内，让 DSD 和 PCM 稳定走不同 renderer + sink？
```

#### 18.14.3 为什么 R 可能比 G3-1b 更干净

G3-1b 的优点是改动小，已经能在 debug/perf 包里验证 PCM hi-res float sink；缺点是 sink 选择仍在 Service 层发生：

```text
当前曲目变化
→ Service prepareSinkDelivery / ensureSinkDeliveryForSong
→ 如配置不同则 rebuild Exo + setPlayer
```

AUTO 切歌时，Exo 内部可能已经用上一首 sink 进入 `DefaultAudioSink.configure`，Service 才收到 `onMediaItemTransition(AUTO)`。这就是 §18.13.4 的 race。

R 的假设是：

```text
曲目进入 renderer 选择
→ DsdOnly / PcmOnly support policy 互斥
→ 选中的 renderer 天然持有正确 sink
→ AUTO 不需要 Service rebuild sink
```

这是**待验证假设**，不是已证事实。R0 必须先证明 renderer 层能可靠识别 DSF/DSD 与 PCM，否则 R 立即暂停。

#### 18.14.4 Renderer 分类与 support policy

新增内部角色：

```kotlin
enum class MicaAudioRendererRole {
    DsdOnly,
    PcmOnly,
    PlatformDefault,
}
```

新增 support policy：

```kotlin
interface AudioRendererSupportPolicy {
    val role: MicaAudioRendererRole
    fun supports(format: Format): Boolean
}
```

第一阶段互斥规则：

| 格式 | DsdOnly | PcmOnly | PlatformDefault |
|------|---------|---------|-----------------|
| DSF / DSD64 / DSD128 | accept | reject | reject 或低优先 fallback |
| FLAC / ALAC / WAV / Hi-Res PCM | reject | accept | fallback |
| AAC / MP3 | reject | reject（第一阶段） | accept |

**关键约束**：不要只靠 renderer 顺序。support policy 必须互斥，日志中必须能看到同一格式只有一个主 renderer accept。

#### 18.14.5 DSD 判定前置风险

R0 要确认 `supportsFormat(format)` 阶段是否拿得到足够信息。至少记录：

```text
Format.sampleMimeType
Format.codecs
Format.sampleRate
Format.channelCount
Format.pcmEncoding
container / extractor 暴露的信息
项目现有 DsdSupport / PlaybackRouter 判定结果（若该层能关联到 Song）
```

如果 renderer 的 `Format` 里 DSF 看起来只是普通高采样 PCM，且无法安全关联现有 `Song` / `DsdSupport` 判断，则：

```text
R0 失败
R 方案暂停
不得进入双 renderer 实现
不得把 DSD 判定硬塞进 renderer
```

#### 18.14.6 Sink 设计（第一阶段）

**DsdAudioSink**：

```kotlin
fun buildDsdAudioSink(context: Context): AudioSink {
    return DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(false)
        .setEnableAudioOutputPlaybackParameters(false)
        .setAudioProcessorChain(
            MicaAudioProcessorChain(
                DsdDecimationAudioProcessor(context),
                SpectrumAudioProcessor(),
            ),
        )
        .build()
}
```

第一阶段不接 EQ / Sonic / FloatBridge / Quantizer。DSD + EQ/Sonic 归 P3/P4 重新设计。

**PcmAudioSink**：

```kotlin
fun buildPcmAudioSink(context: Context): AudioSink {
    return DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(true)
        .setEnableAudioOutputPlaybackParameters(false)
        .setAudioProcessorChain(
            MicaAudioProcessorChain(
                SpectrumAudioProcessor(),
            ),
        )
        .build()
}
```

`enableFloatOutput=true` 可能绕过自定义 Processor 链；因此第一阶段必须验证 `SpectrumAudioProcessor` 是否还能 tap 到 PCM。如果 tap 不到，R1/R2 可以记录为 pure PCM float path，但不能声称频谱已支持。

允许的结论：

```text
24-bit FLAC → float AudioTrack
FloatDelivery / no-16bit-loss
```

禁止的结论：

```text
24-bit packed bit-perfect
```

**PlatformDefault Sink**：第一阶段建议保留默认平台 sink，AAC / MP3 继续走平台 fallback，避免把普通格式也卷入 R 的实验变量。

#### 18.14.7 MicaRenderersFactory 改造边界

R1 需要摆脱「一个 sink 传给所有 audio renderer」的默认结构：

```text
buildAudioSink()
→ 一个 DefaultAudioSink
→ super.buildAudioRenderers(..., audioSink, ...)
```

目标结构：

```kotlin
override fun buildAudioRenderers(
    context: Context,
    extensionRendererMode: Int,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    audioSink: AudioSink,
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    out: ArrayList<Renderer>,
) {
    val dsdSink = buildDsdAudioSink(context)
    val pcmSink = buildPcmAudioSink(context)

    out.add(
        MicaFfmpegAudioRenderer(
            role = MicaAudioRendererRole.DsdOnly,
            supportPolicy = DsdOnlySupportPolicy(),
            eventHandler = eventHandler,
            eventListener = eventListener,
            audioSink = dsdSink,
        )
    )

    out.add(
        MicaFfmpegAudioRenderer(
            role = MicaAudioRendererRole.PcmOnly,
            supportPolicy = PcmOnlySupportPolicy(),
            eventHandler = eventHandler,
            eventListener = eventListener,
            audioSink = pcmSink,
        )
    )

    addPlatformAudioRenderers(
        context = context,
        mediaCodecSelector = mediaCodecSelector,
        enableDecoderFallback = enableDecoderFallback,
        eventHandler = eventHandler,
        eventListener = eventListener,
        out = out,
    )
}
```

上述是方向性伪代码；实际实现前必须先确认 vendored FFmpeg renderer 构造参数、平台 renderer 手动构建方式、`alacBlockingSelector` 与当前 `MicaRenderersFactory` 的真实结构。

#### 18.14.8 Vendored FFmpeg renderer 最小改动

只改三件事：

1. `supportsFormat` allowlist。  
2. renderer role 日志。  
3. sink 注入。  

不要改：

1. FFmpeg 解码流程。  
2. buffer 输出逻辑。  
3. sample format conversion。  
4. timestamp 处理。  

建议日志：

```kotlin
override fun supportsFormat(format: Format): Int {
    if (!supportPolicy.supports(format)) {
        DiagnosticLog.event(
            "RendererSupport",
            "role=$role decision=reject mime=${format.sampleMimeType} " +
                "codecs=${format.codecs} sampleRate=${format.sampleRate} " +
                "channels=${format.channelCount} pcm=${format.pcmEncoding}",
        )
        return C.FORMAT_UNSUPPORTED_TYPE
    }

    val result = super.supportsFormat(format)
    DiagnosticLog.event(
        "RendererSupport",
        "role=$role decision=accept result=$result mime=${format.sampleMimeType} " +
            "codecs=${format.codecs} sampleRate=${format.sampleRate} " +
            "channels=${format.channelCount} pcm=${format.pcmEncoding}",
    )
    return result
}
```

#### 18.14.9 日志契约

| 日志 | 必含字段 |
|------|----------|
| `RendererSupport` | `role`、`decision=accept/reject`、`result`、`mime`、`codecs`、`sampleRate`、`channelCount`、`pcmEncoding` |
| `RendererSelected` | `role`、`sink`、`mime`、`sampleRate`、`channelCount`、`pcmEncoding`、`mediaItemId` |
| `AudioSinkBuild` | `sink=DsdSink/PcmSink/PlatformSink`、`enableFloatOutput`、`processors` |
| `ProcessorFormat` | 沿用 P0；增加 `sink` / `rendererRole` 字段（debug 包即可） |

R0/R1 的日志目标不是多，而是能画出 accept/reject 矩阵：

```text
DSF:  DsdOnly accept, PcmOnly reject, Platform reject/fallback
FLAC: DsdOnly reject, PcmOnly accept, Platform fallback
AAC:  DsdOnly reject, PcmOnly reject, Platform accept
```

#### 18.14.10 分阶段实施

| Gate | 目标 | 是否改播放行为 | 通过标准 | 失败处理 |
|------|------|----------------|----------|----------|
| **R0** | Format 可识别 | 否；只加日志 | DSF 与 PCM 在 renderer support 阶段可区分；互斥矩阵成立 | 停止 R |
| **R1** | 双 FFmpeg renderer + 双 sink spike | 是；debug/perf 优先 | 一个 ExoPlayer 内 DsdOnly/PcmOnly 同时存在；DSF/FLAC/ALAC 路由正确；AAC/MP3 可播 | 回 X/G3-1b |
| **R2** | AUTO 不 rebuild | 是 | FLAC→DSF→FLAC AUTO 无 `pipeline-rebuild`、无 `mediaSession.release`、UI 正常 | 不补 Y；先分析 renderer/sink 选择 |
| **R3** | PCM delivery 有收益 | 是 | 24-bit FLAC 交付为 24-bit packed，或 float 且不再 toInt16 | 若仍 toInt16，仅保留 DSD/PCM 分 sink 价值 |
| **R4** | 功能边界收口 | 是 | 明确 EQ/Sonic/频谱支持或降级策略 | 不允许模糊承诺 |

R0 任务：

```text
1. 在当前 renderer support 阶段增加 Format dump。
2. 播 DSF / 24-bit FLAC / 16-bit FLAC / ALAC / WAV / AAC / MP3。
3. 对比 renderer Format 与现有 DsdSupport / PlaybackRouter 判定。
4. 输出 accept/reject 矩阵草稿，但不改变 renderer 选择。
```

#### 18.14.10a R0 验证子计划（先测，不改路由）

> **目标**：用最小日志验证 R 方案的生死点：renderer support 阶段是否能稳定区分 DSD 与 PCM。  
> **范围**：只允许加日志 / dump；**不改变** renderer 选择、sink 配置、`enableFloatOutput`、Processor 链、MediaSession 行为。  
> **成功才进入**：R1 双 renderer + 双 sink spike。  
> **失败即停止**：不得靠格式猜测或曲名/扩展名硬补进 renderer。

**验证包型**：

| 包型 | 用途 | 要求 |
|------|------|------|
| debug | 首选，日志最全 | 可打 `RendererSupportProbe` / `FormatDump` / debug-only `ProcessorFormat` |
| perf | 可选，接近实机性能 | 只在 debug 结论清晰后补跑 |
| release | 不跑 R0 | release 不应带新增 spam log |

**要新增或打开的日志**：

| 日志 | 必含字段 | 判定用途 |
|------|----------|----------|
| `RendererSupportProbe` | `roleCandidate`、`decisionCandidate`、`mime`、`codecs`、`sampleRate`、`channelCount`、`pcmEncoding`、`containerHint`、`mediaItemId` | 判断 DSD/PCM 是否可互斥 |
| `PlaybackRouteProbe` | `songId`、`fileExt`、`dsdSupportResult`、`playbackRouterResult` | 与项目现有判定对照 |
| `RendererSelected`（如已有 hook） | `rendererClass`、`mime`、`sampleRate`、`pcmEncoding` | 确认现网实际 renderer 选择 |

**测试曲目矩阵**：

| 曲目 | R0 期望 | 必须记录 |
|------|---------|----------|
| DSF / DSD64 | 可被判为 `DsdOnly candidate` | DSD 证据字段；不能只靠扩展名 |
| DSF / DSD128 | 同上 | sampleRate / pcmEncoding 是否与 DSD64 可区分 |
| 24/96 FLAC | `PcmOnly candidate`，不是 DSD | `pcmEncoding`、`sampleRate`、是否有 hi-res 线索 |
| 24/192 FLAC | 同上 | 高采样 PCM 不得误判 DSD |
| 16/44.1 FLAC | `PcmOnly candidate` | 普通 PCM 也不能误判 |
| ALAC | `PcmOnly candidate` 或现有 FFmpeg 路由候选 | 保留 `alacBlockingSelector` 语义 |
| WAV | `PcmOnly candidate` 或 platform fallback | 明确第一版归属 |
| AAC / MP3 | `PlatformDefault candidate` | 不进入 DSD/PCM 手动 renderer |

**执行步骤**：

```text
1. 装 debug 包，确认没有启用 R1 双 renderer / 双 sink。
2. 清 logcat。
3. 逐首播放测试曲目，每首至少等待首帧出声后 3–5 秒。
4. 每首记录 RendererSupportProbe + PlaybackRouteProbe。
5. 对每首曲生成一行矩阵：DsdOnly candidate / PcmOnly candidate / Platform candidate。
6. 对 DSF、24-bit FLAC、ALAC 至少各做一次 seek-existing，确认日志稳定。
7. 混合队列跑一轮手动 next，不以 AUTO 为硬项，只看 Format 判定是否随曲目稳定变化。
```

**通过标准（全部满足）**：

1. DSF/DSD 在 renderer support 阶段能被稳定识别为 DSD 候选。  
2. 24-bit FLAC / 24-bit WAV 等高采样 PCM 不会被误判为 DSD。  
3. ALAC 的候选路由不破坏现有 FFmpeg / `alacBlockingSelector` 预期。  
4. AAC / MP3 不进入 DSD 手动 renderer，第一阶段仍可交给 platform。  
5. 对同一首曲，start / seek-existing / 手动 next 后 candidate 结果一致。  
6. 日志足够生成 accept/reject 矩阵，而不是只能肉眼猜。  

**失败标准（任一即停）**：

1. DSF 在 `Format` 中无法与普通 PCM 区分，且无法安全关联现有 `DsdSupport` 判定。  
2. 24/192 FLAC 或其他 hi-res PCM 被判成 DSD。  
3. 同一格式在不同入口得到不同 candidate。  
4. AAC / MP3 被 DsdOnly 或 PcmOnly 候选错误接管。  
5. 需要依赖曲名、目录名、UI 当前 song 之类不稳定信息才能判定。  

**记录模板**：

```text
R0 sample:
file=
format=
mime=
codecs=
sampleRate=
channelCount=
pcmEncoding=
containerHint=
dsdSupportResult=
playbackRouterResult=
candidate:
  DsdOnly=
  PcmOnly=
  PlatformDefault=
actualRenderer=
result=PASS/FAIL
notes=
```

**R0 输出物**：

```text
1. 一张格式矩阵：每种曲目的 candidate / actualRenderer / result。
2. 一段失败或通过结论：是否允许进入 R1。
3. 若失败，明确失败原因属于“信息不足”“互斥失败”“现有路由冲突”还是“普通格式回归风险”。
```

#### 18.14.10b R0 第一轮实机结果（log 33，2026-07-08）

> **结论**：R0 第一轮 **通过**，允许进入 **R1a supportsFormat 探针**。  
> **限制**：本轮 probe 落点是 `PlaybackRouteProbe` + decoder input `RendererSupportProbe`，**不是**真正 renderer `supportsFormat()` 钩子；因此不能直接跳到完整双 renderer + 双 sink。  
> **缺项**：未覆盖 24/192 FLAC；但已覆盖 24/96 FLAC，足以证明本轮没有把 hi-res PCM 误判为 DSD。192 FLAC 作为补强项，不阻塞 R1a。

**测试包与日志**：

```text
导出文件：mica-diagnostics (33).txt
App=0.2.0.1 (33)
设备：realme RMX8899 / Android 16
包型：debug/perf 行为（有 G3-1b 与 RendererSupportProbe 日志）
```

**覆盖矩阵**：

| 曲目 / 格式 | PlaybackRouteProbe | RendererSupportProbe / decoder input | 结果 |
|-------------|--------------------|--------------------------------------|------|
| DSF `09.Count Down.dsf`（metadata `11289600Hz/1bit`） | `route=supported:dsf-exo-extractor`；`roleCandidate=DsdOnly` | decoder `mime=audio/dsd`；`sampleRate=1411200`；`roleCandidate=DsdOnly` | ✅ |
| DSF `Chromatic Fantasia...dsf`（metadata `5644800Hz/1bit`） | `route=supported:dsf-exo-extractor`；`roleCandidate=DsdOnly` | decoder `mime=audio/dsd`；`sampleRate=705600`；`roleCandidate=DsdOnly` | ✅ |
| 24/96 FLAC | `roleCandidate=PcmOnly`；`mime=audio/flac`；`sampleRate=96000`；`bits=24` | decoder `mime=audio/flac`；`pcmEncoding=PCM_24BIT`；`roleCandidate=PcmOnly` | ✅ |
| 16/44.1 FLAC | `roleCandidate=PcmOnly`；`mime=audio/flac`；`sampleRate=44100`；`bits=16` | decoder `mime=audio/flac`；`pcmEncoding=PCM_16BIT`；`roleCandidate=PcmOnly` | ✅ |
| ALAC `.m4a` | `route=supported:alac-ffmpeg`；`roleCandidate=PcmOnly` | decoder `mime=audio/alac`；`pcmEncoding=PCM_16BIT`；`roleCandidate=PcmOnly` | ✅ |
| WAV | `roleCandidate=PcmOnly`；`mime=audio/x-wav` | decoder 可见 `mime=audio/raw` / `PCM_16BIT`；`roleCandidate=PcmOnly` | ✅ |
| MP3 | `roleCandidate=PlatformDefault`；`mime=audio/mpeg` | decoder `mime=audio/mpeg`；`roleCandidate=PlatformDefault` | ✅ |

**负项检查**：

```text
roleCandidate=DsdOnly: 19
roleCandidate=PcmOnly: 52
roleCandidate=PlatformDefault: 10
OUTPUT_FAILED: 0
误判搜索：无 FLAC/MP3 → DsdOnly；无 DSF → PcmOnly/PlatformDefault
```

**本轮能保证的事**：

1. 现有 `Song` / metadata 路由能稳定把 DSF 归为 `DsdOnly` 候选。  
2. Exo decoder input `Format` 到达后，DSF 暴露为 `mime=audio/dsd` + 高采样率 PCM-like 输出，能稳定归为 `DsdOnly` 候选。  
3. 24/96 FLAC 暴露为 `audio/flac` + `PCM_24BIT`，没有被误判为 DSD。  
4. ALAC 保持 `alac-ffmpeg` 路由语义，decoder input 为 `audio/alac`，可归为 `PcmOnly`。  
5. MP3 保持 `PlatformDefault` 候选。  

**本轮不能保证的事**：

1. 真正 renderer `supportsFormat()` 阶段一定能拿到完全相同的信息。  
2. 双 renderer + 双 sink 一定能稳定运行。  
3. AUTO 混格式切歌不 rebuild。  
4. 24-bit / float 交付收益；这仍属 R3 / G3-1b 范围。  

**后续新增必需信息**：

R1 不应直接做完整双 sink。下一步先做 **R1a supportsFormat 探针**：

```text
1. 在真正 renderer supportsFormat 阶段输出 accept/reject 矩阵。
2. 不换 sink，不改变 renderer 排序，不改变播放行为。
3. 对比 R1a 矩阵与 R0 log 33 是否一致。
4. 若一致，再进入 R1b 双 renderer + 双 sink spike。
5. 若不一致，暂停 R，不靠 decoder-input 结论硬推。
```

#### 18.14.10c R1a / R1b 拆分（R0 之后的新顺序）

| 阶段 | 目标 | 是否改播放行为 | 通过标准 | 失败处理 |
|------|------|----------------|----------|----------|
| **R1a** | 真正 `supportsFormat` accept/reject 探针 | 否 | DSF/FLAC/ALAC/WAV/MP3 矩阵与 R0 一致；互斥成立 | 停止 R 或重写判定输入，不进 R1b |
| **R1b** | 双 FFmpeg renderer + 双 sink spike | 是，debug/perf | 一个 ExoPlayer 内 DsdOnly/PcmOnly 共存；DSF/PCM 路由正确；AAC/MP3 可播 | 回 X/G3-1b |

R1a 任务：

```text
1. 找到可插入真正 supportsFormat 的最小 renderer seam。
2. 只打日志：roleCandidate、decision=accept/reject、mime、codecs、sampleRate、channelCount、pcmEncoding、rendererClass。
3. 保持当前单 sink / 当前 renderer 行为。
4. 实机复跑 log 33 的同一组曲目；24/192 FLAC 可选补强。
```

**R1a 代码实现（2026-07-08，已落地 / 待实机）**：

- **seam**：vendored `FfmpegAudioRenderer.supportsFormatInternal`（`third_party/media3-ffmpeg-decoder`）。这是真正的 renderer `supportsFormat` 决策点，不同于 R0 的 decoder-input 落点。
- **跨模块日志**：新增 `FfmpegRendererSupportProbe`（third_party，静态 listener hook）。app 侧在 `MicaRenderersFactory.init` 注册 listener → `RendererSupportProbeDiagnostics.logSupportsFormat`；**仅** `AudioPipelineDebugDiagnostics.formatTraceEnabled`（debug/perf）注册。release 不注册 listener → `report()` 为 no-op，**零播放行为变更**。
- **日志**：tag 沿用 `RendererSupportProbe`，新增 `stage=supports-format`；字段含 `rendererClass`、`roleCandidate`、`decision=accept/reject`、`result`（handled / unsupported-subtype / …）、`mime`、`codecs`、`sampleRate`、`channelCount`、`pcmEncoding`。
- **未改**：sink 配置、`enableFloatOutput`、Processor 链、renderer 排序、MediaSession 行为——因此**无 Audio quality consent 项**。
- **代码**：`FfmpegRendererSupportProbe.java`、`FfmpegAudioRenderer.supportsFormatInternal`（拆出 `computeSupportsFormatInternal` + `report`）、`RendererSupportProbeDiagnostics.logSupportsFormat` / `describeFormatSupport`、`MicaRenderersFactory.installRendererSupportProbe`；单测 `RendererSupportProbeDiagnosticsTest`（decision 映射）。
- **仅覆盖 FFmpeg renderer**：本 hook 只打 `FfmpegAudioRenderer` 的决策；MP3/AAC 虽 `roleCandidate=PlatformDefault`，但 native 构建含 mp3/aac 解码器，FFmpeg（`EXTENSION_RENDERER_MODE_PREFER`）实际 **accept** 并接管解码——与 R0 启发式不一致，属 PlatformDefault 桶细化（见 §18.14.10d）。

#### 18.14.10d R1a 第一轮实机结果（log 34 + AAC 补测 log 35，2026-07-08）— **通过**

> **结论**：R1a **通过**（用户拍板：WAV/MP3/AAC 的「真实决策 vs R0 启发式」不一致归为 **PlatformDefault 桶正常细化**，不判 DSD/PCM 互斥失败）。允许进入 **R1b**。
> **限制**：探针只覆盖 `FfmpegAudioRenderer.supportsFormat`；平台 `MediaCodecAudioRenderer` 的决策未打点。24/192 FLAC 仍未测（计划内可选补强，不阻塞）。

**设备**：realme（Android 16）；debug/perf 行为（有 `stage=supports-format` + `stage=decoder-input`）。

**`stage=supports-format` 矩阵**：

| 格式 | mime / codecs / 采样 / 编码 | roleCandidate | FFmpeg 决策 | 判定 |
|------|------|------|------|------|
| DSF #1 | audio/dsd / 1411200 / ENC_-1 | DsdOnly | accept / handled | ✅ |
| DSF #2 | audio/dsd / 705600 / ENC_-1 | DsdOnly | accept / handled | ✅ |
| 24/96 FLAC | audio/flac / 96000 / PCM_24BIT | PcmOnly | accept / handled | ✅ |
| 16/44.1 FLAC | audio/flac / 44100 / PCM_16BIT | PcmOnly | accept / handled | ✅ |
| ALAC | audio/alac / 44100 / PCM_16BIT | PcmOnly | accept / handled | ✅ |
| WAV | audio/raw / 44100 / PCM_16BIT | PcmOnly | **reject / unsupported-subtype** | PlatformDefault 桶细化 |
| MP3 | audio/mpeg / 44100 | PlatformDefault | **accept / handled** | PlatformDefault 桶细化 |
| AAC | audio/mp4a-latm / mp4a.40.2 / 48000 | PlatformDefault | **accept / handled**（decoder `ffmpeg…-aac`） | PlatformDefault 桶细化 |

**负项检查**：无 `OUTPUT_FAILED`；无 FLAC/MP3/AAC → DsdOnly；无 DSF → PcmOnly/PlatformDefault。**DSD ↔ PCM 互斥干净**。

**本轮能保证**：

1. 真正 `supportsFormat` 阶段，DSF 稳定 `DsdOnly + accept`；FLAC/ALAC 稳定 `PcmOnly + accept`；两者互斥。
2. hi-res PCM（24/96 FLAC）未被误判为 DSD。

**对 R1b 的设计输入（重要）**：

1. **MP3/AAC**：FFmpeg 在 PREFER 下会接管。R1b 若要 MP3/AAC 留平台，`DsdOnlySupportPolicy` / `PcmOnlySupportPolicy` 的 allowlist **必须都显式 reject** 它们（否则手动 PcmOnly renderer 会抢走）。
2. **WAV（`audio/raw`）**：FFmpeg **无 raw PCM 解码器**（`getCodecName` 无 case → `unsupported-subtype`），由平台解。基于 FFmpeg 的 `PcmOnly` renderer **解不了 WAV**；WAV 第一版应归 **PlatformDefault**，不能塞进 PcmSink。
3. 因此 §18.14.4 互斥表里 `WAV → PcmOnly accept` 是**理想值，与实机不符**；R1b 实现时以本轮实机为准。

**本轮不能保证**：双 renderer + 双 sink 能稳定运行（R1b）；AUTO 混格式不 rebuild（R2）；24-bit/float 交付收益（R3）。

R1b 任务（仅 R1a 通过后）：

```text
1. 新增 MicaAudioRendererRole。
2. 新增 AudioRendererSupportPolicy。
3. 新增 DsdOnlySupportPolicy / PcmOnlySupportPolicy。
4. 改 vendored FfmpegAudioRenderer 支持 allowlist。
5. MicaRenderersFactory 手动添加 DsdOnly + PcmOnly renderer。
6. DsdOnly 绑定 DsdAudioSink。
7. PcmOnly 绑定 PcmAudioSink。
8. 保留平台 renderer 作为 AAC/MP3 fallback。
```

**R1b 代码实现（2026-07-08，已落地 / 待实机）**：

> **Audio quality consent**：用户对话中「开始做R1b吧」明确批准本节方案，含 **debug/perf 包** `PcmOnly` sink 用 `enableFloatOutput=true`（hi-res PCM 避免 24→16 toInt16）与 **FLAC/DSD 第一版无 EQ**。**release 不受影响**（renderer split 关闭 → 仍走候选 X 统一固定链，`enableFloatOutput=false`）。

- **开关**：`PcmDeliveryExperiment.rendererSplit`（全 build type）。G3-1b per-song rebuild 代码已删除，不再需要互斥门控。
- **allowlist（据 R1a log 34/35）**：`MicaRendererSupportPolicies`——`DsdOnly` 接受 `audio/dsd`（`DsdSupport.isDsdMime`）；`PcmOnly` 只接受 `audio/flac` / `audio/alac`；MP3/AAC/WAV(`audio/raw`) 两者都 reject → 交平台 renderer。互斥已单测（`MicaRendererSupportPoliciesTest`）。
- **vendored renderer**：`FfmpegAudioRenderer` 加 `micaRole` + `micaPolicy`（新 `FfmpegFormatPolicy` 接口）。`supportsFormatInternal`：policy reject → `FORMAT_UNSUPPORTED_SUBTYPE`；否则原逻辑。`getName()` 带 role（如 `FfmpegAudioRenderer[DsdOnly]`），R1a 探针日志随之显示 role。**未改** FFmpeg 解码/buffer/timestamp。
- **renderer 装配**：`MicaRenderersFactory.buildRendererSplitAudioRenderers` 先加 `DsdOnly`→DsdSink、`PcmOnly`→PcmSink（extension-prefer 语义：靠前，平手时压过平台），再以 `EXTENSION_RENDERER_MODE_OFF` 调 `super` 只补平台 `MediaCodecAudioRenderer`（用统一链 sink，AAC/MP3/WAV 保持 X 行为）。`alacBlockingSelector` 保留 → 平台 ALAC 仍被挡、走 PcmOnly FFmpeg。
- **sink 链**：
  - `DsdSink`：`DsdDecimation → Spectrum → PlaybackTuning(Sonic)`，`enableFloatOutput=false`（= X 链去掉 EQ）。
  - `PcmSink`：`Spectrum → PlaybackTuning(Sonic)`，`enableFloatOutput=true`（= X 链去掉 DsdDecimation + EQ）。
  - **保留 `PlaybackTuning`（Sonic 尾）**——偏离 §18.14.6「Spectrum only」伪代码，理由：避免 `applyPlaybackParameters`/`getMediaDuration` 与实际播速不一致导致 media-clock 漂移；24-bit/DSD 上 Sonic 仍按 X passthrough。**唯一从 FLAC/DSD 移除的是 EQ**。
- **单 renderer 活跃**：ExoPlayer 同一时刻只启用当前音轨对应的 audio renderer，故三套 sink 不会同时跑；FLAC/DSD 走 FFmpeg renderer sink（无 EQ），AAC/MP3/WAV 走平台 sink（含 EQ/Sonic）。
- **代码**：`FfmpegFormatPolicy.java`、`FfmpegAudioRenderer`（role/policy）、`MicaRendererSupportPolicies.kt`、`MicaRenderersFactory.buildRendererSplitAudioRenderers` / `buildDsdAudioSink` / `buildPcmAudioSink`、`PcmDeliveryExperiment.rendererSplit`；单测 `MicaRendererSupportPoliciesTest`、`PcmDeliveryExperimentTest`。
- **第一版降级（已知，spike 接受）**：FLAC/DSD 无 EQ；FLAC/DSD 变速依赖 Sonic（24-bit/DSD 仍 passthrough=1.0x，与 X 同）；`enableFloatOutput=true` 可能绕过 PcmSink 的自定义 Processor 链 → **频谱 tap 可能失效**（§18.14.6 仅观察，非硬失败）。
- **实机验收：已闭环** —— R1b 的行为由 R2（路由/AUTO 矩阵，log 37）、R3（float 交付）、R4（频谱+EQ+变速，log 38–41）逐项覆盖并通过；下列 §18.14.10e 步骤保留作执行记录。第一版"无 EQ"降级已在 R4 收尾补回（float 路径 + DSD int 链均有 EQ）。

#### 18.14.10e R1b 实机验收步骤（debug/perf）— **已由 R2–R4 覆盖通过**

1. 启动确认 `PcmDeliveryExperiment: active=R1b-renderer-split`。
2. 逐首播放并看 `RendererSupportProbe stage=supports-format`：
   - DSF → `FfmpegAudioRenderer[DsdOnly]` accept；FLAC/ALAC → `[PcmOnly]` accept；MP3/AAC/WAV → 两 FFmpeg role reject（`unsupported-subtype`），平台接管。
   - 看 `AudioSinkBuild`：应有 `RendererSplit+R1b-dsd-int-sink`（enableFloatOutput=false）与 `RendererSplit+R1b-pcm-float-sink`（enableFloatOutput=true）各一次。
3. **R2 路由矩阵**（本步是 R2 硬项）：FLAC→DSF→FLAC AUTO、DSF→FLAC→DSF AUTO、手动 next/prev 混格式、大队列 seek-existing、kill+restore 到 DSF、kill+restore 到 FLAC。
   - **关注**：AUTO 混格式切歌是否 **不再** 出现 `pipeline-rebuild` / `mediaSession.release` / `OUTPUT_FAILED`（对照 §18.13.4 G3-1b 的 AUTO race）。
4. **回归**：DSD 仍符合 `DsdOutputPolicy`（`DsdProcessor` 176400/24）；无 `position-sync-skipped`；currentSong/进度正常。
5. **交付观察（R3）**：24-bit FLAC 的 `AudioTrackDelivery` 是 float 还是仍 toInt16；频谱在 PcmSink(float) 上是否还有 tap。
6. **已知第一版限制**：FLAC/DSD 开 EQ 无效；FLAC/DSD 24-bit 变速无效。属 spike 预期，不判失败。

#### 18.14.10f R2/R3 第一轮实机结果（log 37，2026-07-08）— **R2 通过 + R3 苗头**

> **结论**：**R2 通过**——一个 ExoPlayer 内 DsdOnly/PcmOnly/平台 renderer 各绑专用 sink，FLAC↔DSF AUTO/手动混切全程 **零 `pipeline-rebuild` / 零 `OUTPUT_FAILED` / 零 `position-sync-skipped` / 零 `session-released`**；§18.13.4 的 G3-1b AUTO race 消失。**R3 出现正向苗头**（24/96 FLAC 交付为 float，非 toInt16）。
> **设备**：realme（Android 16），debug/perf；启动 `active=R1b-renderer-split`；启动即建 3 套 sink（`UnifiedFixedChain+production-int16-sink`、`RendererSplit+R1b-dsd-int-sink`、`RendererSplit+R1b-pcm-float-sink`）。

**路由矩阵（真 `supportsFormat` + role，全 accept）**：

| 格式 | renderer | 交付（AudioTrackDelivery） |
|------|----------|----------------------------|
| DSF 705k/1411k | `FfmpegAudioRenderer[DsdOnly]` | DsdProcessor 176400/24 → **PCM_24BIT** |
| 24/96 FLAC | `[PcmOnly]` | **PCM_FLOAT 96000** |
| 16/44.1 FLAC | `[PcmOnly]` | PCM_FLOAT 44100 |
| ALAC | `[PcmOnly]` | PCM_FLOAT 44100 |
| MP3 | 平台 MediaCodec（统一链 sink） | PCM_16BIT 44100 |
| AAC | 平台 MediaCodec（统一链 sink） | PCM_16BIT 48000 |

**假设判定**：H4 ✅（无需 Exo rebuild）、H5 ✅（无 OUTPUT_FAILED）、H6 ✅（无 A 层回归；kill+restore 进度正常）、H7 ✅（稳态无 rebuild/setPlayer log）。

**R3 分类（§18.14.10c）**：命中 **B — PcmSink 输出 float（FloatDelivery / no-16bit-loss）**；24-bit FLAC 不再 toInt16。**非** bit-perfect 24-bit packed，不得如此宣称。

**重要限制（实测证实 §18.14.6 预告，非 R2 失败）**：`SpectrumTap: configure` 仅见于 **enc=21（DSD int）/ enc=2（平台 16-bit）**，**无** float（enc=4 / 96000）。即 **`enableFloatOutput=true` 的 PcmSink 绕过自定义 Processor 链** → FLAC/ALAC 经 PcmSink 时 **频谱 / Sonic 变速 / EQ 均不生效**（纯 float 直通）；DSD 与平台格式（AAC/MP3）的频谱/EQ 正常。

**对后续的意义（R4 必须收口）**：R 若要推向 release，FLAC/ALAC 将失去频谱/变速/EQ——这是功能边界的硬取舍，须在 R4 明确「支持 or 降级策略 + UI 提示」，且任何改变 release 默认交付/功能的变更需 **重新 Audio quality consent**。当前 R1b 仅 debug/perf，release 仍走 X，不受影响。

#### 18.14.10g R4 功能边界收口：float PcmSink 上恢复 频谱 + EQ + 变速变调（代码已落地，consent 已获）

> **目标（用户 2026-07-08 明确要求）**：让 hi-res FLAC/ALAC 在 float 交付下**同时**拥有频谱、EQ、变速变调。
> **consent**：用户已选「路线1 ForwardingAudioSink」+「先 debug/perf 生效，release 仍走 X」。本改动**纯增量**——EQ 关 & 频谱不活动时 bit-exact 直通，不降任何格式默认音质。

**根因（Media3 1.9.0 源码逐行确认）**：`DefaultAudioSink.configure()` 构建的处理链写死两条路：
- float（hi-res）路 = `[trimming, channelMapping, toFloatPcm]`，**不含**自定义 `AudioProcessorChain`；
- int 路 = `[trimming, channelMapping, toInt16Pcm, <自定义链>]`，链跑但 `ToInt16PcmAudioProcessor` 把 24/32/float **一律砍到 16-bit**。

`availableAudioProcessors` 硬编码为 `{trimming, channelMapping}`，无注入钩子。→ stock sink 下 **hi-res 与自定义 DSP 不可兼得**。**关键澄清**：Spectrum、EQ（`SoftwareEqualizer.processPcmFloatLocked`）、Sonic（1.9.0 `SonicAudioProcessor` 接受/输出 float）**三者本就 float-capable**，只是被 sink 的 float 路径排除。

**实现（路线1，不 vendor）**：
- `MicaFloatDspAudioSink : ForwardingAudioSink` 包住内层 `DefaultAudioSink`。在 `handleBuffer` 里对 float PCM 做 **EQ（原地）+ 频谱 tap**，不改帧数 → 内层媒体时钟不受影响。遵守 `handleBuffer` 契约：用 `duplicate()` 读取、拒绝可原样重传、in-flight buffer 不二次处理。
- **变速变调**交给内层 sink 的 **AudioTrack 硬件参数**（`setEnableAudioTrackPlaybackParams(true)` → float 路径 `useAudioOutputPlaybackParams`），由内层记账，时钟正确。注意：这是**硬件时间拉伸**，与 int 路径（DSD/平台）的 Sonic 是两套引擎。
- EQ 复用 `MicaEqualizerManager.equalizer` 同一实例（UI 调节即时生效）；renderer-split 同一时刻仅一个 renderer 活动，滤波器状态共享安全。
- 内层 PcmSink 链置空（`includePlaybackTuning=false`），日志 profile 改为 `RendererSplit+R4-pcm-float-dsp-sink enableFloatOutput=true enableAudioOutputPlaybackParameters=true`。
- 启动日志：`PcmDeliveryExperiment: active=R1b-renderer-split+R4-float-dsp ... PcmOnly=float-dsp-sink(EQ+spectrum+hw-speed)`。

**单测**：`MicaFloatDspAudioSinkTest`（passthrough 直通、active 处理一次并转发处理后 buffer、拒绝重试不二次处理并在接受时消费 source、flush 复位、非 RAW mime 直通）全绿。

**我能保证 / 不能保证（诚实）**：
- 保证：编译通过、单测覆盖 buffer 状态机核心分支并全绿；EQ/频谱算法本就支持 float。
- **不能保证**：`handleBuffer` 状态机在真实设备连续播放/seek/切歌下 100% 无 glitch（这是路线1的主要风险点，需实机验证）；硬件变速变调的音质/行为符合预期。
- **未覆盖**：DSD 仍走 int 链、EQ 仍关、变速受 Sonic 24-bit 限制（本次不改，range 外）。

**待实机验收（R4）**：
```text
1. FLAC(24/96) 播放中开/关 EQ、调 band → 声音随动，无爆音/循环
2. FLAC 播放中开频谱 → 有动态频谱（确认 float 路径 tap 生效）
3. FLAC 变速 0.5x/1.5x + 变调 → 生效，进度/seek 正常
4. FLAC↔DSF AUTO/手动混切多次 → 仍零 pipeline-rebuild / 零 OUTPUT_FAILED（R2 不回归）
5. EQ 关 & 频谱关时 → 确认为直通（听感无染色）
6. kill+restore 到 FLAC → 进度正常
```
跑完把日志给我，据实判定 R4。

##### R4 第一轮实机（log 38/39）+ 缓冲膨胀修复

**现象**：FLAC/ALAC 频谱不随律动/停滞；EQ 生效延迟几秒；DSD 的 EQ/变速/变调无效。

**根因（log 证实 + 源码定位）**：为变速开的 `setEnableAudioTrackPlaybackParams(true)` 让 Media3 按 `MAX_PLAYBACK_SPEED=8f` 预留 AudioTrack 缓冲——float PcmSink 缓冲从 R2 的 0.48s（96k=369024B）**恒定膨胀 8× 到 ~3.84s**（96k=2952192B、48k=1476096B）。后果：
- 频谱 tap 在 sink 入口，领先实际听到的音频 ~3.84s，且起播突发填充→节奏抖动 → 「不随律动/停滞」。
- 已入 AudioTrack 的大缓冲 → EQ 切换后要等旧缓冲放完 → 「延迟几秒」。
- 对比：旧统一链用 **Sonic 在链内变速**（`enableAudioOutputPlaybackParameters=false`），无膨胀，0.48s 缓冲下 FLAC 频谱本是好的。

**修复（本次，明确正确、非音质/consent 项——缓冲大小不改采样）**：新增 `MicaCappedSpeedBufferSizeProvider`，把 provider 收到的 maxSpeed 从 8 夹到 app 真实上限 `PlaybackTuning.MAX_SPEED=2.0`。缓冲 3.84s→**~0.96s**（96k 预计 738048B、48k 369024B）；2× 播放时实时余量 = 常规 1× 的 0.48s，无欠载回归。另给 `MicaFloatDspAudioSink` 加 `FloatDspSink` 诊断（configure + 每秒 handleCalls/processed/passthrough/maxGap），便于核对 float tap 节奏。

**我能保证 / 不能保证**：
- 保证：编译+单测通过；缓冲会显著变小（8×→2×，log 已证线性）；不改采样、无音质影响。
- **不能保证**：0.96s 领先是否让频谱同步「够好」（DSD 0.48s 用户满意，0.96s 待验）；EQ 延迟是否降到可接受（依赖 flush 丢缓冲，待验）。若仍不满意，退路是**恢复 Sonic-in-wrapper 变速**（回到 0.48s，但需在 wrapper 内自管媒体时钟，复杂度上升）。

##### R4 第二轮实机（log 40）— 频谱「二倍速/完全对不上」真凶：分析器缺 FLOAT 解析

**现象**：缓冲修复生效（96k float 2952192→738048B=0.96s，log 证实），EQ 延迟用户认可；**但 FLAC/ALAC 频谱仍对不上，用户描述「二倍速起步 / 完全对不上」**。

**根因（源码定位，非缓冲）**：`MicaSpectrumAnalyzer` 的 `appendMonoSamples`/`readSample` **没有 `ENCODING_PCM_FLOAT`（Android 常量=4）分支**，float 落到 `else` 被当 **16-bit int（2 字节/样本）** 处理：
- `frameBytes = 2×2ch = 4`（本应 8）→ `frameCount = length/4` = **实际帧数的 2 倍** → 每秒喂给 0.5s 上限队列的样本是实时的 **2×** → 队列被灌爆狂丢样本 → 频谱**字面以 2 倍速跑** = 「二倍速起步」。
- float 字节被按 int16 读 → 数值全是垃圾 → 「完全对不上」。
- DSD/int 路径交付 int16/24/32 都在分支内，故一直正常；只有 R4 新增的 **float 交付**撞上缺失分支。log `FloatDspSink configure … enc=4` 即 FLOAT。

**修复（本次，明确正确、纯诊断路径、无音质/consent 影响）**：给 `MicaSpectrumAnalyzer` 补 `ENCODING_PCM_FLOAT` 分支——`bytesPerSample=4`；`readSample` 用 `Float.fromBits`（native/little-endian，与既有 int24/32 一致）读回 [-1,1]。只影响频谱分析器读数，不碰音频样本。

**我能保证 / 不能保证**：
- 保证：编译 + 单测全绿；float 帧数不再翻倍、数值不再是垃圾（镜像已验证的 int 分支逻辑）。这能消除「2 倍速 / 完全对不上」。
- **不能保证**：修好帧数后，缓冲 0.96s > 队列上限 0.5s 仍会在**起播丢 ~0.46s、残留 ~0.46s 领先**（类似但略大于 DSD 的 ~0）。若这点残留领先仍觉得对不上，退路是把 float 缓冲夹到 ≤0.5s（clamp maxSpeed→1.0，与 DSD 一致，代价是 >1× 变速欠载余量变小），或把 `MaxQueuedAudioSeconds` 提到 ≥ 缓冲深度。待实机反馈再定，不预先改。

##### R4 第三轮实机（log 后续）— float 频谱「大体对上但快一点」→ 抬高队列上限

**现象**：float 解析修复后，FLAC/ALAC 频谱**节奏正确、大体对上**，但仍「快了一点」= 残留领先。

**根因**：如上，缓冲 0.96s > 分析器队列上限 `MaxQueuedAudioSeconds=0.5s`，队列吸收不满 → 残留 ≈ 0.46s 领先。DSD 缓冲 0.48s ≤ 0.5s 故领先≈0（用户满意）。

**修复（本次，用户选 A，纯分析侧、零音质影响）**：`MaxQueuedAudioSeconds` 0.5s→**1.2s**（盖过 float 2× 时 ~0.96s 缓冲）。队列在起播突发填充时自适应吸满 = 缓冲深度，稳态 feed=实时消费，视觉领先 → ~0，自动跟随任意缓冲深度；int/DSD（~0.48s）远低于上限、不受影响。内存代价可忽略（1.2s×96k×4B≈460KB）。

**我能保证 / 不能保证**：
- 保证：编译+单测全绿；不碰音频样本，无音质影响；DSD/int 行为不变。
- **不能保证**：队列均衡模型依赖「起播突发≈缓冲深度、稳态 feed=消费」；若实机某场景持续超喂（正常不应发生，2× bug 已修），领先可能非 0。极小概率下 float 可能反而略「慢」，以实机为准。

**DSD（DsdSink）现状（本节写于补 EQ 之前）**：EQ 未接入 int 链（24-bit EQ 算法支持，可加，纯增量）——**已在 R4 收尾补上，见后**；变速变调因 `SonicAudioProcessor` 只支持 16/float、DSD 解码为 24-bit int 而失效（要支持需改 DSD 输出位深/编码，涉及音质与 consent）。需要的话单独立项。

##### R4 验收通过（log 41，2026-07-08）— **R4 ✅**

log 41 实证 + 用户确认，R4 验收矩阵全过：
- **变速变调（item 3）**：用户确认「进度正常」——float 走 AudioTrack 硬件参数，媒体时钟由内层记账正确。
- **混切零回归（item 4）**：float(enc=4) ↔ DSD(176400/enc=21) 反复交替，全程 **0** `pipeline-rebuild` / `OUTPUT_FAILED` / `PlaybackException` / `onPlayerError`（全量 grep 证实，R2 不回归）。
- **直通（item 5）**：EQ 关 & 频谱关时 `FloatDspSink: processed=0 passthrough=N eqOn=false` → float buffer 原样转发，bit-exact 无染色。
- **频谱（item 2）+ EQ（item 1）**：前几轮已过（float 解析修复 + 队列上限 1.2s 抹平领先）。
- **kill+restore（item 6）**：`PlaybackRestore: service restored index=3 positionMs=50211` + 用户确认进度正常。
- DSD `PlaybackTuning: sonic-disabled reason=unsupported`（已知独立限制，符合预期，非 R4 范围）。

**结论**：R4 在 **全 build type** 生效（2026-07-08 推广 release）。float 路径同时兼容 **频谱 + EQ + 硬件变速变调**，纯增量、无音质回归。遗留（另立项、需 consent）：DSD 的 **变速变调**。

##### R4 收尾：DSD EQ 补线（2026-07-08）

**为何之前 DSD 无 EQ**：纯遗漏——R1b 拆链时 `buildDsdAudioSink` 只放了 `DsdDecimation→Spectrum→PlaybackTuning`，未加 EQ 处理器（统一链 `buildUnifiedFixedChain` 是有的）。非技术限制：`SoftwareEqualizer.processInterleaved` 本就有 `ENCODING_PCM_24BIT_PACKED` 分支，而 DSD 降采样输出正是 24-bit int。

**改动（纯增量、无音质回归）**：`buildDsdAudioSink` 在 Spectrum 之后加 `MicaEqualizerManager.createAudioProcessor()`——**新建**一个 `SoftwareEqualizerAudioProcessor`、复用共享 `SoftwareEqualizer`（不复用 platform sink 的 `audioProcessor` 实例，避免两条 sink 链共享内部 buffer）。renderer-split 同一时刻仅一个 renderer 活动，共享滤波器状态安全。EQ 默认关时 `isActive()=false` → DSD 链直通，默认音质不变。profile 改 `RendererSplit+R1b-dsd-int-sink+R4-eq`；启动日志 `DsdOnly=int-sink(EQ+spectrum)`。编译+全 media 单测绿。**实机通过（2026-07-08）**：DSD 播放中开/关 EQ、调 band 均正常生效、无爆音。

**DSD 变速变调仍不可用**：`SonicAudioProcessor` 只支持 16-bit/float，DSD 解码为 24-bit int → `PlaybackTuning: sonic-disabled reason=unsupported`。要支持须改 DSD 输出位深/编码（涉及音质与 consent），另立项。

R2 验收矩阵：

```text
1. FLAC → DSF → FLAC AUTO
2. DSF → FLAC → DSF AUTO
3. 手动 next / prev 混格式
4. 大队列 seek-existing 到不同格式
5. kill + restore 到 DSF
6. kill + restore 到 FLAC
```

R3 结论分类：

```text
A. PcmSink 可保 24-bit packed：
   最佳，继续推进 DSP path 设计。

B. PcmSink 输出 float：
   可接受，标记 FloatDelivery / no-16bit-loss。

C. PcmSink 仍 toInt16：
   R 对 24-bit 交付帮助有限，但仍可用于 DSD/PCM sink 分离。

D. PcmSink bypass 导致频谱不可用：
   第一阶段可接受；频谱留 P3/P4 重新设计。
```

#### 18.14.11 风险清单

| 风险 | 严重度 | 表现 | 处理 |
|------|--------|------|------|
| DSD Format 无法在 renderer 层可靠识别 | 高 | DSF 在 `supportsFormat` 看起来像普通 PCM | R0 停止，不进 R1 |
| 两个 renderer 同时 support 同一格式 | 高 | DsdOnly / PcmOnly 都 accept | support policy 必须互斥；日志必须可审计 |
| PCM float sink bypass 自定义 Processor | 中 | `enableFloatOutput=true` 后 Spectrum/EQ 不工作 | 第一版接受 pure PCM path；功能型 DSP 后移 |
| ALAC / FLAC 路由变化 | 中 | ALAC 未走 FFmpeg 或平台接管失败 | 保留 `alacBlockingSelector` 语义；PcmOnly 覆盖 ALAC |
| 平台 renderer 与手动 renderer 重复 | 中 | 同一格式被手动 renderer 与平台 renderer 同时支持 | 必要时手动构建平台 renderer，或限制 platform selector |
| AudioSession / EQ attach 变化 | 中 | 不同 renderer/sink 切换后 `audioSessionId` 变化 | R1 不启用 EQ；记录 `onAudioSessionIdChanged`；R4 再处理 |

#### 18.14.12 第一版功能边界

第一版支持：

1. DSF / DSD 走 DsdSink。  
2. FLAC / ALAC / WAV 走 PcmSink。  
3. AAC / MP3 走平台 fallback。  
4. AUTO 混格式切歌不依赖 Service rebuild Exo。  
5. 频谱只观察是否有 tap；不作为 R1/R2 硬失败条件。  

第一版不支持或允许降级：

1. DSD + Sonic。  
2. DSD + EQ。  
3. PCM + Sonic 的最终 float DSP path。  
4. float 不支持时 Quantizer fallback。  
5. USB DirectPcm / Native DSD。  

UI / 文案约束：

1. 当前 renderer/sink 不支持 Sonic 时，变速控制必须禁用或明确提示。  
2. 当前 path 无频谱 tap 时，不显示动态频谱，或显示静态占位。  
3. 当前 path 为 float delivery 时，只标记为 `FloatDelivery` / `no-16bit-loss`，不得标记 bit-perfect。  

#### 18.14.13 最终判断

Renderer-split sinks 是当前几个方向里更平衡的中期候选：

```text
稳定性目标接近 X
音频策略能力接近 Y / G3-1b
Service / MediaSession 侵入小于 Y
比自写完整 AudioSink 风险低
```

但它必须先回答 R0 的核心问题：

```text
renderer supportsFormat 阶段能否稳定地区分 DSD 与 PCM？
```

因此下一步不是直接大改 renderer，而是只做 R0：

```text
打印 Format 信息，确认 DSD / PCM 可在 renderer 层互斥识别。
```

R0 通过，R 才值得作为中期主线 spike；R0 不通过，立即停止，继续以 X + G3-1b 小步验证为准。

### 18.7 为何不回到「项目最初全 FFmpeg 软件播」

- 现网仍用 **FFmpeg 解码**（`libffmpegJNI`）；删的是 **第二播放引擎**（整文件复制、自建 timeline、`alacStreamActive` 双后端）。  
- 全 FFmpeg **不解决** Sink/AudioTrack 的 24-bit 问题（C 层）；**不减少** DSD/EQ/频谱链需求。  
- `docs/PERFORMANCE_INVESTIGATION.md`、`REFACTOR_PLAYBACK_ARCHITECTURE.md` 记录：双后端 UI/进度/性能问题 → 才迁 Exo-only。  
- **短期求稳**用 **候选 Z**（回 pre-P1 单链），成本 **远低于** resurrect 软件播。

---

## 17. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-07-07 | 初版：合并改造计划草案、P1 Sink Profile 拆法、讨论文档结论与代码现状 |
| 2026-07-07 | 增补架构集成风险：§4.6–§4.7、§7.4–§7.5、§7.2 Factory 约束、§5.2 路由、§10.4–§10.6、§15–§16；同步仓库基线（Sonic tail） |
| 2026-07-07 | **P0 落地**：PipelineFormatTrace、PcmFormatDiagnostics、Decoder/AudioTrack 日志；§8 P0 实机验收步骤 |
| 2026-07-08 | **P1 最小实验**：`enableFloatOutput=true` 结论（bypass 链）；实验已删 |
| 2026-07-08 | **§18.9 Gate 2 实验设计**（候选 X 统一链） |
| 2026-07-08 | **Gate 2 通过**（log 20；G2-2 全表 #1–#7） |
| 2026-07-08 | **Gate 3 G3-1a**：全局 float sink 实验（log 26 证伪） |
| 2026-07-08 | **§18.8 Gate 1 实验设计**（先设计后编码）；§18.4 链到 §18.8 |
| 2026-07-08 | **§18.13 G3-1b**：per-song sink rebuild（debug/perf）；DSD int sink；AUTO 时机 race 文档化（log 28–29） |
| 2026-07-08 | **§18.14 候选 R**：Renderer-split Sinks 中期方案草案；以 R0 Format 可识别作为进入双 renderer spike 的硬门槛 |
| 2026-07-08 | **§18.14.10a R0 验证子计划**：定义 debug 日志、测试曲目矩阵、通过/失败标准与记录模板；先测 Format 互斥，不改路由 |
| 2026-07-08 | **§18.14.10b–c R0 实机结果**：log 33 第一轮通过；记录 DSF/24-96 FLAC/ALAC/WAV/MP3 矩阵、192 FLAC 缺项不阻塞，并拆出 R1a supportsFormat 探针 |
| 2026-07-08 | **R1a 探针落地**：`FfmpegAudioRenderer.supportsFormatInternal` 真 seam；`FfmpegRendererSupportProbe` 跨模块 hook + `RendererSupportProbe stage=supports-format`；debug/perf 纯日志、release no-op、零播放行为变更 |
| 2026-07-08 | **§18.14.10d R1a 实机通过**（log 34 + AAC 补测 log 35）：DSD↔PCM 互斥干净；WAV(`audio/raw`)→FFmpeg reject、MP3/AAC→FFmpeg accept 归 PlatformDefault 桶细化（用户拍板）；记录 R1b allowlist 设计输入；下一步 R1b |
| 2026-07-08 | **§18.14.10c/e R1b 代码落地**（consent 已获）：`FfmpegFormatPolicy` + `FfmpegAudioRenderer` role/policy；`MicaRendererSupportPolicies`（DsdOnly/PcmOnly 互斥）；`MicaRenderersFactory` 双 sink（DsdSink int / PcmSink float=true）+ 平台 fallback；`PcmDeliveryExperiment.rendererSplit` 与 G3-1b 互斥；debug/perf 生效、release 仍 X；FLAC/DSD 第一版无 EQ；待实机 R2 矩阵 |
| 2026-07-08 | **§18.14.10g R4 代码落地**（consent 已获，路线1）：`MicaFloatDspAudioSink`(ForwardingAudioSink) 在 float 路径做 EQ+频谱，变速变调走 AudioTrack 硬件参数（`setEnableAudioTrackPlaybackParams(true)`）；`MicaEqualizerManager.equalizer` 复用；内层 PcmSink 空链；纯增量（EQ 关&频谱关时 bit-exact 直通）；`MicaFloatDspAudioSinkTest` 全绿；debug/perf 生效、release 仍 X；待实机 R4 验收 |
| 2026-07-08 | **§18.14.10f R2 通过 + R3 苗头**（log 37）：FLAC↔DSF AUTO/手动混切零 `pipeline-rebuild`/零 `OUTPUT_FAILED`/零 `position-sync-skipped`/零 `session-released`（H4–H7 ✅）；路由矩阵全 accept 到正确 role；24/96 FLAC 交付 `PCM_FLOAT`（R3 结论 B，非 bit-perfect packed）；实测证实 float PcmSink 绕过 Processor 链 → FLAC/ALAC 无频谱/Sonic/EQ，列为 R4 功能边界收口项 |
| 2026-07-08 | **R4 首/次轮修复**（log 38–40）：① `MicaCappedSpeedBufferSizeProvider` 把 float PcmSink 缓冲从 8×(3.84s) 夹到 2×(0.96s)，降 EQ 延迟；② **真凶** `MicaSpectrumAnalyzer` 缺 `ENCODING_PCM_FLOAT` 分支 → float 被当 16-bit（帧数翻倍→频谱 2 倍速/垃圾值），补 4-byte float 解析；③ `MaxQueuedAudioSeconds` 0.5→1.2s 吸满缓冲深度、抹平残留领先（用户选 A，纯分析侧、零音质）；均编译+单测全绿 |
| 2026-07-08 | **R 推广 release**：`PcmDeliveryExperiment.rendererSplit` 去掉 debug/perf 门控，全 build type 走 renderer-split+R4；启动日志 `scope=all-builds`；单测 `rendererSplit_enabledOnAllBuildTypes` |
| 2026-07-08 | **R 定为终选 + G3-1b 废弃 + DSD EQ 补线 + 文档同步**：`buildDsdAudioSink` 接入 `MicaEqualizerManager.createAudioProcessor()`（24-bit int EQ，纯增量，实机通过）；G3-1b per-song rebuild 代码已删除，AUTO race 作废；状态头/§0/§18.13/§18.14 候选表 + R1b 验收状态同步；提交 `codex/audio-renderer-split-r4` |
| 2026-07-08 | **§18.14.10g R4 验收通过**（log 41）：变速变调进度正常（AudioTrack 硬件参数、时钟正确）；FLAC↔DSD 混切 0 rebuild/OUTPUT_FAILED（R2 不回归）；EQ 关&频谱关 bit-exact 直通；kill+restore 进度正常。float 路径同时兼容 频谱+EQ+硬件变速变调，debug/perf 生效、release 仍 X。遗留另立项（需 consent）：DSD 的 EQ/变速变调、release 启用 R4/R1b |
