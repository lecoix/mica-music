# 本地人声分离调研记录

> 状态：**调研完成，暂不实施**（成本与收益不成比例）
> 最近更新：2026-09-02
> 结论摘要：端上人声分离在 2026 年已有可执行的开放实现路径。RawS-Music 证明 UVR_MDXNET_9482 + ONNX Runtime + 原生 STFT/iSTFT 可以在 Android 工程中完整接通，并补齐了模型契约、分块与渐进式播放方案；但目标设备上的持续 RTF、峰值内存、热降频和分离质量仍无可信实测，因此仍不进入正式实施。下一步不再是“继续找模型”，而是做目标设备 benchmark PoC。
> 本文不是已批准的实施方案。音质影响见 §6.1，未验证事项见 §7。

---

## 1. 为什么记录

一次性的评估结论会随人员和时间流失。本文要保留的是四件在下次评估时不必重做的工作：Mica 现有基建里哪些能直接复用、开源模型的体积与许可证边界、一个已上线产品在实时链路上的具体做法，以及 RawS-Music 这份 Apache-2.0 Android 源码已经验证到什么程度。

---

## 2. Mica 现有基建盘点

### 2.1 可直接复用

| 能力 | 位置 | 说明 |
|------|------|------|
| 整曲离线解码为 float PCM | `third_party/media3-ffmpeg-decoder/.../OfflineFfmpegPcmDecoder.java` | `MediaExtractor` + `FfmpegAudioDecoder`，输出交错 `float[]`，流式不落盘。DSF/APE 走 `app/.../media/loudness/OfflineMicaExtractorPcmDecoder.kt` |
| 离线整曲重算任务编排 | `app/.../media/loudness/LoudnessScanManager.kt` | 进程级 `SupervisorJob + Dispatchers.IO`、`decodeMutex` 单并发、`StateFlow` 进度、离开设置页不取消 |
| 磁盘缓存模板 | `app/.../data/scanner/AlbumArtCache.kt` | 内容寻址、200 MB 上限、LRU 修剪至 75%、`.part` 原子写、按曲库引用 prune |
| 播放换源先例 | `app/.../media/MusicVideoMediaSourceFactory.kt` | 已在包装 `DefaultMediaSourceFactory`，是替换 audio URI 最自然的位置 |
| AudioProcessor 插入点 | `app/.../media/MicaAudioProcessorChain.kt` | 继承 `DefaultAudioSink.AudioProcessorChain`，已挂 DSD 降采样 / 频谱 tap / EQ / Sonic |
| 设置页套路 | `SoundFxPreferences` + `SoundFxScreen` + `SettingsAudioPanel` + `SettingsSearchIndex` | 新增「开关 + 参数页」的固定改动面 |

### 2.2 缺口

- **没有任何 ML 推理栈。** 全库检索确认无 TensorFlow Lite / LiteRT / ONNX Runtime / MNN / ncnn 的依赖或源码引用。这是纯增量。
- **FFmpeg 是 decoder-only 构建。** `ffmpeg/docker/build-media3-ffmpeg.sh` 的 `ENABLED_DECODERS` 只含解码器。若分离产物需落盘为 FLAC，必须重建 FFmpeg 带 flac encoder（Docker 链改动 + `.so` 增大 + 重走 release 门禁）；替代方案是落 WAV（体积约 ×2.5）或用 `MediaCodec` 编 AAC（有损）。
- **app 模块自身无 `externalNativeBuild`，也未 pin `ndkVersion`。** 现有 native 由 `:taglib` / `:sylvakru-usb-transport` 各自的 CMake 承载，FFmpeg 为 Docker 预构建后放入 jniLibs。
- **`armeabi-v7a` 仍在发布 ABI 内**（`app/build.gradle.kts` abiFilters）。32 位地址空间与 GPU delegate 支持都更差，建议该 ABI 直接不提供此功能。

### 2.3 输出路径约束（决定实时方案的边界）

- `PlaybackOutputMode.requiresMinimalProcessorChain` 对一切非 `SharedPcm` 成立。`UsbDirectPcm` 走 `UsbHybridPcmAudioSink`（无 Mica processor），`UsbDop` / `UsbNativeDsdExperimental` 走 `UsbHybridDsdRenderer` 直写 USB，**完全绕过 AudioSink 与 AudioProcessor 链**。
- `AudioPipelineCoordinator.offloadEnabled` 要求全部软件 DSP 关闭。offload 生效时音频不经过 App 的 processor 链。

因此任何基于 `AudioProcessor` 的实时分离**只在 `SharedPcm` 且 offload 关闭时可用**，USB 独占三条路径下必然失效。这一点与音质取向存在结构性冲突，是选型时必须先接受的前提。

---

## 3. 端上模型现状（2026-09 核实）

### 3.1 体积与可行性

| 方案 | 参数量 | 端上文件体积 | 端上可行性 |
|------|--------|--------------|-----------|
| HT-Demucs FT（ONNX 导出） | — | 单 stem specialist 316 MB，4-stem bag 约 1.26 GB | 不可行 |
| UVR-MDX-NET Voc_FT / Inst_HQ_3（LiteRT） | 16.7 M | fp32 权重 64 MB / fp16 权重 34 MB | 勉强 |
| UVR_MDXNET_9482 | 7.4 M | RawS 固定 ONNX 文件 29,704,436 bytes；第三方 LiteRT 转换约 fp32 28 MB / fp16 15 MB | 可行，当前最值得做端上 PoC 的开放候选 |

MDX-Net 一类是纯 spectrogram U-Net，模型只吃频域张量 `[1, 4, dim_f, dim_t]`（2 声道 × 实部/虚部），**STFT 与 iSTFT 由宿主 App 负责**，且 `n_fft` 因模型而异（4096 / 6144 / 16384）。这块是自研工作量，也是最容易出错的地方。

一个已知的部署陷阱：float16 量化的 `.tflite` 会带显式 `DEQUANTIZE` 节点，打碎 GPU delegate 的图分区，实测反而比 fp32 慢一个数量级。应发 fp32 文件并让 delegate 内部以 fp16 执行。

RawS-Music 对 `UVR_MDXNET_9482.onnx` 固定了可执行契约，显著降低了“宿主 DSP 参数靠猜”的风险：

- 采样率 `44,100 Hz`，双声道 float32。
- 输入 / 输出张量 `[1, 4, 2048, 256]`，plane 顺序为 L real / L imaginary / R real / R imaginary。
- `n_fft=6144`、`hop_length=1024`、periodic Hann、`center=true`、reflect padding。
- 模型输入块 `261,120` samples，global edge trim `3,072` samples。
- 频率只保留实 FFT 的 `0..2047` bins。
- 10% overlap，输出用加权 overlap-add；补偿系数 `1.035`。

这组参数在 RawS 的 `AiRecommendedModels.kt` 与 `ai_separation_engine.cpp` 中互相对应，后者已经实现 STFT → ONNX → iSTFT → center trim → overlap-add，而不是只有配置或 UI 壳。

### 3.2 许可证边界

- **Demucs 权重不可商用。** 代码是 MIT，但作者在 demucs#267 / #327 / #508 中多次说明预训练权重不在 MIT 覆盖内、仅供科研用途，原因是训练使用了 MusDB 数据集。HuggingFace 上近期出现的 `license: mit` 标签正被公开质疑为导出工具默认值（`adefossez/HTDemucs` discussions/1），作者未回应。**不要采用。**
- **MDX-Net（kuielab，MDX Challenge 2021）为 MIT**，是许可证干净的选项。UVR 系权重的归属较模糊，采用前需逐个核对。

---

## 4. 参考实现拆解：LunaBeat.apk

> 来源：第三方 Android APK 静态分析，样本 37,869,350 bytes。分析目的仅为架构参考。
> **合规边界：不得复制其代码，不得提取或使用其模型包**（该模型经加密存放，作者意图明确）。本节记录的是可公开观察到的设计思路，不是可搬运的实现。

该 App 的 `libffmpegJNI.so` 与 `libtaglib.so` 与 Mica 相同，Java 包名为 `yos.music.player` 与 `com.example.LyricBox`，与本项目同源。它走的正是 §2.3 所述的实时 `AudioProcessor` 路线。

### 4.1 体积构成

分离功能合计约 13.5 MB：`libLiteRt.so` 5.14 MB、`libLiteRtClGlAccelerator.so` 2.70 MB（OpenCL/GL）、`assets/mss/model.msspack` 4.68 MB、`liblitert_jni.so` 0.51 MB、`libmss_engine.so` 0.48 MB。仅 `arm64-v8a`，无 32 位。

模型包为自定义容器：magic `MSPK`，头部 560 字节，其后为高熵数据；头中 payload size 与 uncompressed size 均为 `0x4ACF04`，两者相等表明**未压缩、仅加密**。引擎内版本标识为 `mss-model-v1`。

**4.68 MB 是整件事的关键。** 开源里最小的 UVR_MDXNET_9482 是它的三倍。实时可行性完全建立在这个模型规模上，而这个模型是自研资产，无法从外部获得。

### 4.2 推理栈

主路径使用 LiteRT 的 **CompiledModel C API**（`LiteRtCreateCompiledModel`、`LiteRtSetOptionsHardwareAccelerators`、`LiteRtCompiledModelIsFullyAccelerated`），同时保留旧 `TfLiteInterpreter` C API 作回退。JNI 暴露 `nativeCpuBenchmarkMs` / `nativeGpuBenchmarkMs`，**启动时跑基准来选择后端**，而非硬编码。`libmss_engine.so` 仅 0.48 MB，承担 STFT/iSTFT、环形缓冲与调度。

### 4.3 值得借鉴的设计

**JNI 做成 MediaCodec 风格的异步流式接口**，而非同步的「进一段出一段」：

```
queueInput(buffer, frames, generation) -> Int   // 返回消费帧数；< 0 表示 ring 满，暂不接受
dequeueOutput(buffer, maxFrames, generation) -> Int
queueEndOfStream() / isDrained() / flush(generation)
```

配套遥测：`deadlineMisses`、`lastInferenceMs`、`inputRingHighWater`、`outputRingHighWater`、`outputRingFullWaits`、`outputRingWaitUs`。实时音频下推理跟不上是常态，这些计数器用于观测而非报错。输出单次上限 32768 帧（约 0.74 s @ 44.1 kHz）。

**generation 贯穿三个 native 调用。** 处理器持有 `generation`，在 flush、reset、控制参数变更、回退原始音频时递增；`queueInput` / `dequeueOutput` / `flush` 均携带该值，native 侧据此丢弃旧代数据。与本仓 `AGENTS.md` 的异步共享状态规则同构。

**背压时输出保护性静音，而不是透传原始 PCM。** 当 `queueInput` 返回负值但引擎状态仍为 READY 时，输出等长静音并保持时间轴对齐。若此时透传原始 PCM，用户会听到一声突兀的人声漏出；该实现选择宁可短暂无声。这是产品层面的取舍，值得记住。

**三级降级。** 瞬时背压 → 静音；runtime failure → 关闭全局开关、close bridge、后续全部透传；每 500 ms 尝试重建一次 native 会话。

**同一个 processor 兼做直通。** 开关关闭时 `configure` 返回 `inputAudioFormat` 而非 `NOT_SET`，processor 保持 active，仅在 `queueInput` 内改走内存拷贝。这样开关切换无需重建 AudioSink 与整条管线。只有格式本身不受支持（非 int16、声道数不在 1~2）时才返回 `NOT_SET` 彻底停用。

**等功率交叉淡化混音**，非线性混音：

```
vocalGain        = sin(mix · π/2)
accompanimentGain = cos(mix · π/2)
```

默认 `(0.0, 1.0)`，即纯伴奏。UI 对应一根从伴奏到人声的连续滑杆。

**切歌时主动临时禁用。** 自动切歌期间关闭分离走直通，稳定后再启用；播放界面退到后台则不再重新启用（省电）。相关常量 `MSS_AUTOMATIC_REENABLE_{INITIAL_DELAY,RETRY,MAX_ATTEMPTS,MIN_ADVANCE}_MS` 与 `SETTLE_DELAY` 的具体数值被 R8 内联，未取得。

### 4.4 三层伴奏策略

端上实时分离只是兜底。该 App 实际提供三条路径，按质量降序：

1. **Companion 伴奏 sidecar 文件** — 用户为歌曲关联现成伴奏音频（`resolveCompanionAudioPath`、`accompanimentSourceUri`、`metadata_edit_remove_accompaniment`）。与 Mica 的 music video sidecar（ADR-0005）是同一套思路。
2. **LAN 分离** — 连接局域网内主机跑大模型（`LanSeparationHealth`、`settings_lan_separation_*`）。
3. **端上实时分离** — 质量最低但任何时候都可用。

这个分层比单押端上模型更务实：把质量与即时性的矛盾交给用户按场景选择。如果 Mica 将来要做，**第 1 层的成本远低于第 3 层，且不需要任何 ML 栈**，应当优先。

---

## 5. 参考实现拆解：RawS-Music

> 来源：本地参考树 `.codex-tmp/RawS-Music-ref`，审计提交 `7ebec7a5a17a1a3de26515ac92d29b50a8a6dc22`（2026-08-10）。仓库主许可证为 Apache-2.0。
> 本节记录源码中实际存在的工程实现。模型权重本体不在参考树中，模型许可证仍须逐模型独立核验；不能因为宿主仓库是 Apache-2.0 就推导权重授权。

RawS 的价值与 LunaBeat 不同：LunaBeat 提供“已经上线产品如何做严格实时音频调度”的观察样本；RawS 则提供了一套可以逐函数审计的 Android + ONNX Runtime + C++ 分离实现，尤其适合减少 Mica 在 MDX 宿主 DSP 上的重复试错。

### 5.1 模型与执行契约

RawS 内置两个数据型推荐配置，但模型文件均按需下载：

| 模型 | 文件大小 | 估算内存 | RawS 定位 |
|------|----------|----------|-----------|
| `UVR_MDXNET_9482` | 29,704,436 bytes | 420 MB | 唯一标记为 realtime 的推荐模型 |
| `UVR-MDX-NET-Voc_FT` | 66,762,795 bytes | 720 MB | 高质量移动版，源码明确限制为离线 |

`UVR_MDXNET_9482` 的完整执行契约已列于 §3.1。RawS 在打开模型时还会核对输入输出数量、float32 类型、张量形状，并用全零输入执行一次推理，拒绝 NaN / Inf 输出。模型文件本身使用固定 size + SHA-256 校验，避免远端对象被静默替换。

这意味着原调研中“MDX-Net 的 STFT/iSTFT 与 chunk 参数属于高风险自研区”的判断应下调：**算法细节仍需我们验证，但已经有公开源码可以函数级对齐，不再是从空白开始。**

### 5.2 ONNX Runtime 接入与体积控制

RawS 使用 `onnxruntime-android 1.26.0`，但把 Java API 与运行核心拆开：APK 保留较小的 Java/JNI 桥，约 27.4 MB 的 `arm64-v8a/libonnxruntime.so` 在首次使用 AI 功能时下载、校验后动态加载。官方 Maven AAR fallback 约 43.6 MB，同样固定 size + SHA-256。

执行后端按 `NNAPI → XNNPACK → CPU` 尝试，失败自动回退；线程数根据 CPU 核数和模型 contract 调节，最多 6 个 worker。session 打开后会校验图结构，并使用预分配的 direct input/output buffer 与 pinned output，减少每次推理的 Java 对象分配。

对 Mica 的直接启发是：**基础 APK 不必因为 AI 功能永久携带完整 ORT 核心。** 代价是必须增加 runtime 下载、ABI、完整性校验、版本迁移和持久存储生命周期管理。

### 5.3 离线分离实现

RawS 的离线路径已经完整接通：源音频 → 44.1 kHz stereo/s32le PCM → native 分块 → STFT → ONNX → iSTFT → vocals / instrumental float WAV → 最终 FLAC 或 AAC。原曲不覆盖，结果按任务提交。

C++ 侧只保留相邻少量 chunk 在内存，而不是整首频谱常驻；每个 chunk 都统计 STFT、inference、iSTFT、output 耗时，并把累计 RTF 发布给 UI。伴奏按 `mixture - vocal` 重建。

这一实现可以作为 Mica 离线 PoC 的主要工程参考，但 RawS 自己先把整首解成临时 PCM，磁盘峰值较大。若 Mica 真实施，更合理的方向仍是复用现有流式 decoder，做 decoder → bounded PCM producer → separator，避免整首中间 PCM 落盘。

### 5.4 RawS 的“实时 ONNX”不能当作产品级实时证据

RawS 还实现了 `AiRealtimeOnnxPcmProcessor`，直接处理当前播放 PCM，不落中间 stem 文件。它证明“模型能够接进播放 PCM 链”，但调度策略与 LunaBeat 的严格实时设计有明显差距：

- 开始输出前要求先提交 **2 个模型 segment**。考虑 `edgeTrim=3072` 后，每次推进的 useful region 为 254,976 frames，提交第二块前累计需要约 513,024 frames 源 PCM，即约 **11.6 s @ 44.1 kHz**。
- 推理由单线程 executor 串行完成。
- 结果队列断粮时，播放调用会同步等待输出，`MAX_WAIT_MS = 15,000`。
- 没有 LunaBeat 那种 deadline miss、ring high-water、明确 backpressure 和“跟不上就保护性静音”的完整机制。
- 当前参考树没有提交可引用的 `infer_ms` / RTF benchmark 结果，也没有人声分离专项 unit / instrumentation test。

因此 RawS 能支持的结论只是：**Android 上存在完整的实时模型接线实现。** 它不能支持“9482 在目标手机上可以持续低延迟实时运行”。正式实时方案仍应以 §4.3 的异步 ring + generation + deadline/backpressure 设计为质量下限。

### 5.5 更值得 Mica 关注：渐进式本地分离 / 准实时预听

RawS 还提供了一条介于“整首离线完成后再播”和“模型硬塞进 AudioProcessor”之间的方案：

1. 前台任务按 chunk 生成 vocals / instrumental 两个不断增长的 float WAV。
2. native 每完成可安全读取的一段就 flush，再发布 `availableFrames`。
3. 独立播放器 tail 这两个文件；首次预缓冲 **2 秒**后开始播放。
4. 如果分离速度追不上播放，只进入 `waitingForData`，而不是阻塞当前播放器的音频回调。
5. 完成后切到最终 FLAC / AAC 结果；已缓存的分离结果可直接复用。

这条路线应正式加入 Mica 的候选架构。它的产品体验取决于持续 **RTF < 1**，但即使 RTF 偶尔超过 1，也只是缓冲等待，不会把推理延迟直接传播进正常 AudioTrack 链。相比硬实时，它更容易隔离故障，也更适合先做 PoC。

RawS 自己明确禁止这条 secondary `AudioTrack` 路径在 USB exclusive 活跃时接管播放；Mica 同样必须保持 §2.3 的边界，不能为了 AI 分轨悄悄绕开 Exact PCM / DoP / Native DSD 输出。

### 5.6 RawS 对当前调研结论的修正

RawS 出现后，原结论“核心阻塞是找不到足够小且开放的模型”已经过时。现在更准确的判断是：

- **候选模型已经有了：** 9482 约 29.7 MB ONNX，许可证来源仍需最终核验，但工程上不是未知黑盒。
- **宿主 DSP 路径已有公开实现：** STFT/iSTFT、center trim、overlap-add、图校验、ORT 后端回退都能直接对照。
- **真正 blocker 转为性能证据：** 目标设备上的单 segment 推理耗时、持续 RTF、峰值 RSS、热降频后 RTF、NNAPI/XNNPACK/CPU 稳定性，以及实际分离质量。
- **产品架构不应只分“离线/实时”两档：** 应新增“渐进式本地分离 / 准实时预听”作为中间路线。

因此重新评估时的第一步不应再继续广泛搜模型，而应直接围绕 9482 做一个不接 UI 的 benchmark PoC。

---

## 6. 若将来实施：约束与改动面

### 6.1 音质影响（须事先获得明确许可）

按 `.cursor/rules/audio-quality-consent.mdc` 与 `CONTEXT.md` 的 **Audio quality consent**，实施前必须先说明并获准：

- MDX-Net 类模型固定工作在 **44.1 kHz**，hi-res（96/192 kHz）与 DSD 源在分离时会被降采样。
- 输出为模型重建信号，本质上不是无损。
- 模型工作率固定为 44.1 kHz，因此实时方案必须在播放链中做采样率适配；具体 PCM 输入格式取决于实现。LunaBeat 观察到的是 int16 路径，而 RawS 的 processor 已覆盖 16-bit、32-bit integer 与 float PCM，不能把 int16 当作模型本身的硬限制。offload 仍必须关闭。
- USB Exact / DoP / Native DSD 三条独占路径下功能不可用。

可接受的前提是：**默认关闭、作为独立播放模式存在、不触碰原曲的现有播放链路与音质**。即便如此仍需单独获准，不得默认启用。

### 6.2 容量基线

按 `AGENTS.md` 的 10,000 首 / 8 GB 设备基线：

- 全库预处理不可行。一首 4 分钟伴奏轨 FLAC 约 25~35 MB、WAV 约 42 MB。
- 只能按需单曲处理 + 有上限的 LRU 缓存。
- 缓存 key 必须含源文件 identity 与模型版本，参考 `LoudnessAnalysis` 已有的 `sourceSizeBytes` / `sourceModifiedMs` / `analyzerRevision`。

### 6.3 成本估算

初版给出的 **25~50 人天** 是在“推理栈、MDX 宿主 DSP、分块实现都要从零做”的假设下估算。RawS 审计后，这个单一数字不应继续直接引用：STFT/iSTFT、UVR chunk geometry、ORT session 与后端回退已有可对照实现，算法接线的不确定性显著下降；但播放链隔离、runtime / model 生命周期、缓存、USB 模式边界、真机性能与热稳定性仍是 Mica 自己的工程成本。

在 benchmark PoC 前不重新给总人天数字。PoC 应先把成本最大的未知量测出来，再分别估离线、渐进式和硬实时三条路线。

体积方面也不再只参考 LunaBeat 的 +13.5 MB：RawS 证明可以把约 27.4 MB 的 arm64 ORT 核心与约 29.7 MB 的 9482 模型都改成首次使用时下载，使基础 APK 只保留桥接代码；代价是新增下载、校验、ABI 与版本迁移体系。

### 6.4 暂不实施的理由

技术路径已经比初版调研时清楚很多，当前暂不实施的原因不再是“没有模型”，而是**缺少目标设备上的性能与质量证据**。9482 的文件规模和完整 Android 接线都已经可接受到值得 PoC，但在不知道持续 RTF、峰值内存、热降频和真实听感前，直接把它做进正式播放链仍属于高风险施工。

如果将来重启，建议顺序改为：

1. 先做 §4.4 的 companion sidecar（零 ML 成本）。
2. 做 9482 benchmark PoC，只验证模型 + DSP + runtime，不接正式 UI / 播放链。
3. 若持续 RTF 明显小于 1，优先试 §5.5 的渐进式本地分离 / 准实时预听。
4. 只有在长期 RTF、热稳定性和延迟余量都足够时，才评估 §4.3 风格的硬实时 AudioProcessor。

---

## 7. 未验证事项

以下为本次调研的边界，不得当作事实引用：

- **目标 Android 设备上的 9482 实际性能仍无可信实测。** RawS 源码会记录 `infer_ms`、各 DSP 阶段耗时和累计 RTF，但当前参考树没有提交 benchmark 结果，因此不能从“有实时代码”推导“能实时跑”。
- 需要至少测：NNAPI / XNNPACK / CPU 的模型加载时间、首 segment 耗时、稳态 p50/p95 segment 耗时、整曲 RTF、峰值 RSS、30~60 分钟热降频后的 RTF，以及前后台切换后的稳定性。
- RawS 给出的 9482 `estimatedMemoryMb = 420` 与 Voc_FT `720` 是配置中的估算值，不是本项目设备上的实测峰值。
- RawS 参考树中没有人声分离专项 unit / instrumentation test，也没有 ONNX 权重本体；模型正确性与音质仍需我们自己验证。
- LunaBeat 的模型架构不明（已加密）。UI 字符串 `inlineMdxMixMenuUsesMss` 暗示 MDX 系，但不足为证。
- LunaBeat 的推理耗时、deadline 阈值、chunk 大小、STFT 参数均在 native 内，需反汇编 arm64 才能取得，本次未做。
- `MSS_AUTOMATIC_*` 系列常量数值被 R8 内联，未取得。
- LunaBeat **未经运行验证**，§4 全部结论来自静态分析（DEX 反编译、ELF 符号与字符串、ZIP 结构），非实测行为。
- LiteRT 与 ONNX Runtime 在目标机型上的相对稳定性未做对比。
- UVR 系具体模型权重的许可证仍需在真正下载与分发前逐个核验，RawS 中的声明不能替代上游模型文件的最终授权确认。
- 开源转换权重（HuggingFace 第三方 `.tflite`）的实际分离质量与长期可用性未验证；其模型卡自述 `.fp16w` 构建「尚未在设备上验证」。

---

## 8. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-09-02 | 增补 RawS-Music 源码审计：固定 9482 执行契约、ORT 动态运行时、native STFT/iSTFT 与分块实现、实时路径局限、渐进式本地分离方案；将 blocker 从“缺模型”修正为“缺目标机性能与质量证据”，下一步改为 benchmark PoC |
| 2026-09-01 | 初版：基建盘点、开源模型与许可证核实、LunaBeat.apk 架构拆解、成本估算与暂不实施结论 |
