---
status: superseded
superseded_by: 0004-usb-exclusive-hybrid.md
---

> Superseded on 2026-08-20 by ADR-0004. The USB Host direction and the distinction
> between exclusive ownership and signal exactness remain authoritative; statements below
> saying that SharedPcm is the only implemented production path are historical.

# USB 独占采用 USB Host 独立输出

未来的 USB 独占输出采用 Android USB Host 路线：由独立 USB output adapter 管理设备权限、USB interface claim、格式协商、PCM/DoP 传输和断连释放，绕过系统共享 `AudioTrack` 输出。选择这条路线是因为产品目标是真正占用目标 USB audio interface，而不只是向 Android framework 请求 preferred device 或 direct playback。

## Consequences

- 当前 `PlaybackOutputMode.UsbDirectPcm`、`usbAudioDeviceId` 和 USB 最小 `DefaultAudioSink` 只是兼容骨架，不代表 USB 独占已经实现；`requireSupportedForPlayback()` 继续 fail-fast。
- 现有 `usbAudioDeviceId` 的名字不能直接成为 Host identity：`AudioDeviceInfo.id` 与 `UsbDevice.deviceId` 属于不同层，远期须定义可重连的设备身份与一次连接内的 runtime handle。
- `SharedPcm` 仍是唯一生产出声路径。USB Host adapter 以后作为第二个真实 implementation 接入，不把权限、claim、endpoint 和传输生命周期塞进 `MicaMediaService` 或 `MicaRenderersFactory`。
- “USB interface 已独占”和“bit-perfect”是两个事实。后者还取决于源格式与协商格式、ReplayGain、EQ、变速变调、重采样等处理，不能由独占状态直接推导。
- USB 传输层、Native DSD/DoP、插拔重建、失败回退和 DAC 实机矩阵均为远期工作；当前实施范围不包含这些内容。

## Deferred plan

1. 定义产品/协议合同、设备身份和显式 fallback 规则。
2. 只读枚举 USB audio device，完成 permission 与 descriptor/capability probe，不出声。
3. 建立 `UsbOutputSession`，验证 open/claim/release、attach/detach、重连与 deterministic fallback。
4. 实现 PCM 格式协商和 transport，再建立 requested/active/fallback/实际格式的输出事实。
5. 用 USB DAC 实机矩阵验证兼容性、错误恢复和长时间稳定性。
6. DoP/Native DSD 作为后续独立决策，不随 PCM transport 自动启用。

## 2026-08-14 Direct DSD addendum

后续实验已经验证：Direct DSD 不能从最终 `AudioOutputProvider` 接入，因为到该 seam 时 DSD 已被 FFmpeg / DSD decimation 转成 PCM。Direct DSD 因此采用 **Media3 renderer-level branch**，继续复用现有 ExoPlayer、MediaSession、timeline 与 queue，不创建第二套播放器。

Direct exact transport 的启动分成两个不同状态：prepare/ENABLED 阶段只允许真实内容预填 dormant ring 并形成 startup-ready；renderer 进入 STARTED 后才允许 arm USB exact transport。达到 prefill threshold 本身不等于允许启动硬件流。

未来 Direct DSD pause/曲间空窗需要一个独立的 carrier-liveness owner：source 暂停时不推进音乐数据，但仍通过既有 `DoPCarrierSession.writeGapFrames()` 提供合法 DSD gap；恢复时继续同一 session chronology。Native 层保持 exact-only，不生成 PCM zero-fill 或私自构造 DSD carrier。

参考项目只作为设计证据：GPLv3 NeriPlayer 支持“queued/preroll readiness 与 native start 分离”的 USB PCM 生命周期；Apache-2.0 sylvakru 的 DSD 路径把 source pause 与持续 DSD idle carrier 分离，并以单 writer/session 维持 marker 连续。Mica 只采用这些 ownership/lifecycle 原则，不复制参考实现代码或另建 DoP 算法。
