# ReplayGain 实际应用状态计划

> 状态：2026-07-13 R1–R3 已实现；定向编译与兼容测试通过。
>
> 关联决策：[`ADR-0001`](adr/0001-usb-host-exclusive-output.md) 只规定远期 USB Host 输出，不属于本计划的实现范围。
>
> 音质边界：本计划不得改变 ReplayGain 算法、增益上限、音量乘法、输出格式、offload preference 或 processor chain。

## 1. 目标

让“播放器实际应用了多少 ReplayGain”成为 service 内可读取、可测试的事实，而不再只能从设置和歌曲标签重复推测。第一版只深化 ReplayGain module，不同时建立涵盖 EQ、频谱、变速、offload 和 USB 的总音频状态模型。

该事实未来可作为 USB 独占/bit-perfect eligibility 的一个输入，但当前不实现 USB 输出、不显示“bit-perfect”，也不向 UI 暴露新的音频路径信息。

## 2. 当前行为基线

1. `ReplayGainPolicy.linearGain(tags, mode)` 根据 TRACK/ALBUM 标签计算线性系数；关闭或缺少标签时返回 `1f`。
2. 正增益和 peak 防削波结果最终限制在 `0f..1f`，所以当前 ReplayGain 只会保持或衰减信号。
3. 选择 ALBUM 但缺少 album gain 时返回 `1f`，不会自动 fallback 到 track gain。
4. `MicaMediaService` 在曲目切换和 ReplayGain 设置变化时重新计算系数。
5. `MicaCompositePlayer` 将用户请求音量与 ReplayGain 系数相乘后写入 `ExoPlayer.volume`。
6. ReplayGain 不进入自定义 PCM processor；当前代码也不会因为 ReplayGain 主动改变 offload preference。

以上六点是兼容契约，不在本轮修改。

## 3. 当前实施范围

### R1 — 建立 ReplayGain 事实来源 ✅

- 引入内部 `AppliedReplayGain` 值对象，至少记录：选择的 `ReplayGainMode`、最终 `linearFactor`、是否实际修改信号，以及系数来源（track tag、album tag、关闭或无可用标签）。
- 以最终传给播放器并经过约束的系数为准；不能仅因为设置是 TRACK/ALBUM 就声称信号已改变。
- applied state 是运行时派生事实，不写入播放队列/位置快照；service 重建后从当前设置和当前 `MediaItem` 重新计算。

### R2 — 深化 ReplayGain module ✅

- 将曲目切换 listener、设置 listener、标签解析、计算、应用和当前状态收进一个 ReplayGain owner module。
- module interface 只需要生命周期、重新应用命令和只读当前状态；`MicaMediaService` 保留 orchestration，不继续持有 ReplayGain 的内部时序知识。
- 保持 `MicaCompositePlayer` 的用户音量 × ReplayGain 音量乘法语义；不要把 ReplayGain 塞进 EQ 或 float DSP processor。

### R3 — 诊断与兼容测试 ✅

- 诊断只记录 mode、来源和最终系数，不宣称平台实际 offload、硬件音量位置或 bit-perfect。
- 覆盖 OFF、缺标签、TRACK、ALBUM、正增益限制、peak 防削波、曲目切换、设置切换和 release 后不再响应。
- 保留并扩展 `replayGainMultipliesRequestedVolume`，验证用户音量先后变化均维持相同乘法结果。

## 4. 明确不做

- 不实现或解除 USB P6 fail-fast。
- 不新增 USB permission、设备绑定、interface claim 或传输代码。
- 不修改 ReplayGain 计算公式、`0f..1f` 限制或默认设置。
- 不修改 EQ、频谱、变速变调、offload preference、采样率、位深或 Sink 选择。
- 不把 `AudioQualityMode` 扩成更多枚举；其持久化清理另立范围。
- 不增加音频路径 UI，也不把 Hi-Res 源信息改成运行时质量标志。
- 不提前创建只有一个 implementation 的通用 `AudioOutputAdapter` 空壳。

## 5. 验收与验证

### 可由自动化保证

- 相同标签、模式和用户音量产生与当前代码相同的最终 `ExoPlayer.volume`。
- 切到关闭或无标签曲目时 applied factor 回到 `1f`。
- applied state 与实际传给播放器的受限系数一致。
- service/owner release 后 listener 被解除，不留下重复应用。
- 现有 ReplayGain、播放状态、PlayerController 与队列测试继续通过。

### 静态检查或单元测试不能保证

- 特定 ROM 最终在哪一级应用 `ExoPlayer.volume`。
- ReplayGain 生效时平台是否仍实际使用 offload。
- gapless 切歌的最早音频帧在所有设备上都不会短暂沿用上一曲系数。

本计划本身不改变声音，完成代码后编译和单元测试是必须项；普通曲目、带 ReplayGain 标签曲目各做一次实机冒烟即可。若以后要对 gapless 首帧或 offload 作强保证，需要单独的实机诊断方案。
