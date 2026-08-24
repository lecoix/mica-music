---
status: accepted
accepted: 2026-08-15
architecture: FROZEN_V1
---

# USB 独占播放采用单一 Playback Protocol Authority

## Context

USB 独占已经形成两套稳定但不同的 ownership：

- `UsbOutputSessionOwner` 负责 USB device/session generation、permission、attach/detach、recovery/fallback 与所有 USB side effects；
- Media3 playback 侧需要同时处理 PLAY/PAUSE、manual/auto navigation、seek、exact `MediaPeriodId` occurrence、PCM/DOP family handoff、Direct STARTED、DoP GAP 与 stack rebuild。

当前 playback authority 分散在 `MicaCompositePlayer`、`ManualNavigationTransitionBridge`、`DirectDsdTrackTransitionCoordinator`、PCM sink 与 Direct renderer。Paused DOP->PCM 的多轮 race 修复表明，继续通过 grant/revoke/boolean patch 扩展这些局部状态机会产生未定义 interleaving。

## Decision

采用两级但不重叠的播放 authority：

1. service/product-lifetime `PlaybackIntentLedger` 唯一保存跨 Exo stack 连续的 semantic PLAY/PAUSE `IntentRevision`；
2. per-Exo-stack `UsbExclusivePlaybackProtocol` 作为 **唯一 stack-local playback-transition authority**，adopt ledger snapshot 后统一处理 mutation/currentness/family/activation。

协议统一拥有：

- adopted application intent revision；
- logical playback mutation/navigation/seek epoch；
- exact Media3 playback occurrence binding；
- immutable `AdapterInstanceId` lifecycle attribution；
- PCM/DOP family ownership、typed source-retirement proof；
- bounded activation permit / side-effect receipt / commit disposition；
- committed `ActiveWriteLease` ownership/revocation；
- paused/playing semantic state；
- current output target / USB output generation binding。

`UsbOutputSessionOwner` 保持独立并继续作为唯一 USB side-effect/generation owner；不能把 Media3 queue/transition state 塞进它。Protocol permit 携带 USB generation，但真实 USB IO 仍必须在 P2 request/session lease seam 重新验证/执行。

PCM sink / Direct renderer 只做 Media3 adapter：携带 exact occurrence + adapter instance/lifecycle evidence，向 protocol 请求原子且**有界**的 activation stage permit，执行 side effect 后返回 typed receipt。成功 activation 再获得可撤销的 active write ownership，连续 PCM/Direct writer 不能把旧 activation permit 当永久写权限。Current partial/terminal-with-resource outcome 必须先完成 identity-scoped cleanup，再 retry/terminal；不能在残留 resource 上重试。禁止再使用跨对象 `has -> bind -> consume -> accept` 的 check-then-act authority。

PCM source ownership 切换不依赖“每次 occurrence 都会有 `AudioSink.configure()`”这一假设。Media3 复用 decoder/sink 时，protocol 通过 typed retained-runtime retirement proof，在关闭/drain A write ownership 后为 exact/current B 原子 mint 新 `FamilyOwnershipId`/`ActiveWriteLease`；若 compatibility/tail ordering 无法证明，则必须走真实 reconfiguration/rebuild。

Application semantic PAUSE/PLAY 先更新 `PlaybackIntentLedger`，stack protocol adopt 后再 dispatch/处理对应 Exo operation。技术性 quiesce/rebuild/flush 与 Media3 execution suppression 不得伪装成 semantic intent；任何技术性 restore 在恢复 Exo execution 前必须以当前 `IntentRevision` 重新 fence/adopt，禁止恢复旧 `playWhenReady/shouldResume` 快照。`AudioSink.play/pause`、renderer STARTED/STOPPED 只代表 runtime lifecycle，不是 application intent 的替代品。

Target architecture 不保留独立 resume-grant/revoke authority；PAUSED destination 是否可以 activation 直接由最新 intent + exact occurrence + adapter identity + typed source retirement + output generation 在一个 reducer transaction 中决定。

Activation permit mint 是 **logical acceptance-authority** linearization point，不是 USB IO linearization。Permit 之后到达的 PAUSE 不追溯撤销已经开始的有界 side effect；commit 按最新仍-current intent 形成 active-playing/active-paused。Supersede/seek/detach/output-generation change 会使旧 activation stale，并按 `ActivationId/resourceIdentity` 进入 no-effect 或 identity-scoped cleanup。Conflicting successor 在 required retirement/cleanup receipt terminal 前不得开始写。

Direct DSD 使用 staged create/prefill/arm/source-accept permit；`DEFER_UNTIL_RESUME`、STARTED-only arm、DoP single-writer/GAP/marker/pending-half/feeder chronology 不因本 ADR 改写。

完整设计见 `docs/USB_EXCLUSIVE_AUDIO_ARCHITECTURE.md`（`FROZEN_V1`，由 `COORDINATOR_CANDIDATE_V3` 冻结而来）。

## Consequences

- `ManualNavigationTransitionBridge` 与 `DirectDsdTrackTransitionCoordinator` 将被 de-authorize/supersede；helper 可临时保留，但 transition/family authority cutover 必须 one-writer，不能出现 legacy+protocol 双写。
- `DirectDsdSeekDiscontinuityCoordinator` 的 global authority 最终并入共同 mutation protocol；其已验证的 adapter causal evidence 在替代 seam 证明等价前保留。
- P2 `UsbOutputSessionOwner`、Direct STARTED arm、DoP GAP continuity、exact feeder/Native fail-closed 合同不因本 ADR 改写。
- 先做 ledger/pure protocol model + state-space tests，再 raw-event shadow wiring，再一次性切换 transition/family authority；真实 USB permit/write 从第一次物理 cutover 起就必须 redeem P2 lease。
- P4/P5/P6 分别负责 concurrency、DSD boundary、reference audit；shared architecture 决策权仍属于 coordinator。
