# 播放架构重构分支审查

> **分支**：`refactor/playback-architecture`  
> **基线**：`main`（three-dot：`git diff main...HEAD`）  
> **审查日期**：2026-06-16  
> **范围**：约 150 文件，+11 166 / −1 438 行；核心为 Media3/软件双后端、Service 侧播放协调器、输入缓存与测试体系

---

## 审查方法

本审查按三条**独立轴线**并行执行，轴线之间不合并排序，便于分别判断「写得对不对」与「做得对不对」：

| 轴线 | 问题 | 依据 |
|------|------|------|
| **Bugbot** | 运行时缺陷、并发与状态不同步 | 分支 diff + 播放路径代码走读 |
| **Standards** | 是否违反项目已记录的约定 | `CONTEXT.md`、`PLAYER_PAGE_CONTRACT.md`、`TESTING.md`、`MOTION.md`、`AGENTS.md` |
| **Spec** | 是否实现来源 spec / 提交意图 | `CONTEXT.md`、`PLAYER_PAGE_CONTRACT.md`、`docs/TODO.md`、顶提交说明 |

**未纳入本次审查**：Security Review（用户未要求）；`MOTION.md` / 播放页布局条款（本 diff 以数据层与媒体服务为主）。

---

## Bugbot（运行时缺陷）

### 2026-06-16 后续修订（hybrid8）：队列错位止血

本分支后续通过「服务侧队列权威 + 导航意图传递」修复了真机上“UI 已切歌但实际仍播原曲”的错位问题：

- `PlayerController` 切歌前将目标 `songId` 与完整队列写入 `PendingPlaybackNavigation`，规避 `setMediaItems`/`seekTo` 的 binder 竞态。
- `ServicePlaybackEngineCoordinator.onSelectMediaItem` 优先消费该意图，保证 `startAt` 能按目标 `songId` 解析索引与出声曲目。

| Severity | Location | Finding |
|----------|----------|---------|
| **High** | `PlayerController.kt:353-407` | 重构后 `MediaController.Listener` 移除了 `onPlayerError`；`ServicePlaybackEngineCoordinator.handleFailure` 只在服务内跳曲/停播，**不更新** `playbackError` / `userMessage`。播放页依赖 `surfaceState.playbackError`，解码失败时用户可能**无任何提示**。 |
| **High** | `PlayerController.kt:462` | `PendingMediaSelection` 在 `playSong` 时锁定目标 `mediaId`，`syncIndexFromPlayer` 在目标到达前拒绝其它回调。若 `seekTo`/`play` 失败，锁仅在断连/`release` 时清除，`currentIndex` 已乐观更新但索引同步被阻塞 → **UI 显示新曲、实际仍播旧曲**。 |
| **Medium** | `PlayerController.kt:619-621` | `setPlaybackVolume` 只设 `MediaController.volume`（Exo），不再调 `AlacAudioTrackEngine.setVolume`。软件播走 `AudioTrack`，**睡眠定时器渐弱对 DSD/FFmpeg 曲目无效**。 |
| **Medium** | `PlayerController.kt:354-357` | `onMediaItemTransition` 无条件 `setPositionMsClamped(0)`，无 `alacStreamActive` 早退。软件播经 `MicaCompositePlayer` 上报的 session transition 会把 **UI 进度瞬间归零**。 |
| **Medium** | `PlayerController.kt:808-819` | `syncExoQueuePreservingPlayback` 用 `setMediaItems` 重建整队并可能 `play()`，**未区分软件播 session**。插播重排已有曲时可能误启 Exo，与软件引擎并行出声。 |

### Bugbot（第二轮：修复后复查）

> 复查结果以本分支当前代码为准；以下条目与上一轮可能有“同问题不同落点”的情况。

| Severity | Location (file:line) | Finding |
|----------|-----------------------|---------|
| **High** | `app/src/main/java/com/mica/music/data/PlayerController.kt:261` | 软件播 `alacStreamActive` 已不再被写入，但 `previous()` 仍依赖它做逻辑分支，导致服务侧走软件播时上一曲行为可能错误（seek 0 而不是切上一首）。 |
| **High** | `app/src/main/java/com/mica/music/media/ServicePlaybackEngineCoordinator.kt:458-472` | 软件解码失败只记日志/自动切歌，不会更新 UI 的 `playbackError`/`userMessage`；客户端也收不到错误回调，用户可能“静默失败”。 |
| **High** | `app/src/main/java/com/mica/music/data/PlayerController.kt:359-362` | `onMediaItemTransition` 无条件把进度清零，队列重排/保留位置等情况下会把 UI/歌词进度打到 0。 |
| **Medium** | `app/src/main/java/com/mica/music/data/PlayerController.kt:813-828` | `PendingMediaSelection` 选择后，在某些提前 return 分支不清除，可能导致索引/进度同步被长期阻塞。 |
| **Medium** | `app/src/main/java/com/mica/music/data/PlayerController.kt:370-376` | 播放错误提示映射被移除，可能直接暴露英文/原始异常文本，用户提示质量下降。 |
| **Medium** | `app/src/main/java/com/mica/music/media/ServicePlaybackEngineCoordinator.kt:507-518` | 失败跳曲仍手写 +1/回0/随机，未使用 `PlaybackQueueNavigator.nextIndex`，与领域约定不一致；部分模式下自动跳过行为可能不符合预期。 |

### 未覆盖到的代码路径定位（对应第二轮复查）

本节把“为何修复后仍会被 Bugbot 抓到”的**触发路径**与**缺口点**明确到函数级，便于对照补齐。

1. **`alacStreamActive` 不再更新，但仍被业务逻辑读取**
   - **触发路径**：`PlayerController.previous()` → `if (!alacStreamActive && positionMs > 3_000) seekToMs(0) else playSong(prev)`（`PlayerController.kt:1240-1253`）
   - **缺口点**：`PlayerController.alacStreamActive` 在当前文件内无任何写入（除 `publishSurfaceState` 传递），导致其值可能长期保持默认 `false`，从而把“软件播语义”误判成 Exo 语义。

2. **软件播解码失败对 UI 静默**
   - **触发路径**：软件引擎回调 `onError(message)` → `handleFailure(requestId, PlaybackFailure(...))`（`ServicePlaybackEngineCoordinator.kt:449-455`）
   - **缺口点**：`handleFailure` 仅做日志、停止引擎、`endAlacSession()`，并按策略自动跳曲（`resolveFailureIndex()?.let(::startAt)`），**没有任何向 UI 层的错误透传**（`playbackError` / `userMessage`）。

3. **`onMediaItemTransition` 无条件清零进度**
   - **触发路径**：`MediaController.Listener.onMediaItemTransition` → `syncIndexFromPlayer` → `clearPendingSeek()` → `setPositionMsClamped(0)`（`PlayerController.kt:355-366`）
   - **缺口点**：这里不区分 transition 的 `reason`，也不考虑此前通过 `setMediaItems(..., position)` 保留的 `position`，会让 UI/歌词进度在某些切换/重排场景跳到 0。

4. **`PendingMediaSelection` 可能永久阻塞**
   - **触发路径 A（设置锁）**：`PlayerController.playSong` 末尾 `pendingMediaSelection.select(song.id)`（`PlayerController.kt:773-815`）
   - **触发路径 B（未清锁的提前 return）**：`startControllerPlayback` 中 `if (controller !== expectedController) return` 或 `if (currentIndex != index || ...) return`（`PlayerController.kt:821-832`）
   - **缺口点**：上述提前 return 不会调用 `clearPendingMediaSelection()`；随后 `syncIndexFromPlayer` 会先走 `pendingMediaSelection.shouldAccept(c.currentMediaItem?.mediaId)`，当回调 mediaId 与 target 不一致时持续 `return`，导致索引/进度同步长期被拒绝。

5. **错误提示映射被移除（提示质量回退）**
   - **触发路径**：`MediaController.Listener.onPlayerError` → `val message = error.message ?: "Playback failed"` → `playbackError = message`（`PlayerController.kt:370-379`）
   - **缺口点**：未再经统一的用户可读映射（例如旧的 `PlaybackErrorMapper` 风格），导致出现英文或底层异常消息直出。

6. **失败跳曲未使用 `PlaybackQueueNavigator`**
   - **触发路径**：软件播 `handleFailure` 自动跳曲 → `resolveFailureIndex()`（`ServicePlaybackEngineCoordinator.kt:458-472`）
   - **缺口点**：`resolveFailureIndex` 手写 +1/回0/随机（`ServicePlaybackEngineCoordinator.kt:507-518`），与 `resolveNextIndex(manual=false)` 走 `PlaybackQueueNavigator.nextIndex(...)` 的路径不一致，且与 `CONTEXT.md` 的“不得重写跳转逻辑”约定冲突。

---

## Standards（是否符合项目约定）

### 硬性违规

1. **`insertPlayNext`（`PlayerController.kt` 约 648–686 行）**  
   `CONTEXT.md`「Insert play next」：软件播出声时须**等当前曲结束**再播插入项。实现改为对 `MediaController` 立即 `addMediaItem` / `syncExoQueuePreservingPlayback`，KDoc 仍写旧「ALAC 等结束」语义。

2. **`ServicePlaybackEngineCoordinator.resolveFailureIndex`（503–514 行）**  
   `CONTEXT.md`「PlaybackQueueNavigator」：不得手写 `index + 1`。失败跳曲用 `queue.currentIndex + 1` 自算，未走 `PlaybackQueueNavigator.nextIndex`，与 `resolveNextIndex` 可能分叉。

3. **文档与实现矛盾**  
   - `PlayerController` 类 KDoc（约 68–70 行）仍写「全部走 FFmpeg」  
   - `docs/PLAYER_PAGE_CONTRACT.md` §架构注意（121 行）仍称 `playSong()` 全走 FFmpeg  
   与同分支 `CONTEXT.md` 双后端定义及 Service 协调器实现冲突。

### 判断项（非明确违规）

- 大块注释掉的 legacy 软件播代码（`PlayerController.kt` 约 822–1060 行）增加误读风险  
- `ServicePlaybackEngineCoordinatorTest` 仅 2 例，相对 `TESTING.md`「播放状态机/队列分支 ≥80%」覆盖不足  
- 命名仍用 `alacStreamActive` 表示「软件播活跃」，与 `CONTEXT.md` 术语不完全一致  

### 已对齐

- `resolveNextIndex` / `previousIndex` 已委托 `PlaybackQueueNavigator`  
- `ServicePlaybackStateCoordinator` 恢复时 `playWhenReady = false`（符合 `TESTING.md`）  
- `PlayerControllerBoundaryTest` 验证 `PlaybackSession` 不自动播放  

### 跳过（工具已覆盖）

`micaCheck`、Lint、JaCoCo 等工具链强制项。

---

## Spec（是否实现预期目标）

### 缺失 / 部分实现

| Spec 来源 | 要求 | 状态 |
|-----------|------|------|
| `CONTEXT.md` | 双后端 `MEDIA3` / `SOFTWARE`、`PlaybackRouteDecision` | ✅ `PlaybackArchitecture.kt`、`PlaybackRouter` |
| 提交说明 | 「输入缓存」 | ✅ `AudioInputCache` 缓存 URI 源文件 |
| `TODO.md` L129 | 原生解码优先，FFmpeg 仅兜底 | ⚠️ Exo 作主路径；无 `MediaExtractor`+`MediaCodec` 直通 |
| `TODO.md` L130 | PCM 流式，减少整首落盘 | ❌ `AlacAudioTrackEngine` 仍整首解码 PCM |
| 提交说明 | 架构迁移收尾 | ⚠️ 大段 legacy 注释代码未删，文档未同步 |

### 范围蔓延（spec 未要求）

- 服务侧队列/游标持久化（`ServicePlaybackStateCoordinator` / `Store`）— spec 仅定义 `PlayerController` 的 `PlaybackSession`  
- ALAC 解码器进程级熔断（`recordAlacDecoderFailure`）  
- `AudioQualityMode` + Exo offload 切换（EQ 开关联动）

### 已实现但存疑

- **软件播插播「重排已有曲」**：`syncExoQueuePreservingPlayback` → `setMediaItems`+`play()`，未区分 `alacStreamActive`（与 `CONTEXT` 插播语义冲突）  
- **队列模式双源**：协调器从 `player.shuffleModeEnabled` / `repeatMode` 反推 `PlaybackQueueMode`，与 Controller 侧 `playbackQueueMode` 可能不一致  

---

## 积极面

- 播放架构从 Controller 内嵌软件播，迁到 **Service 侧协调器**（`ServicePlaybackEngineCoordinator`、`PlaybackRequest` 状态机），边界更清晰  
- 测试体系大幅补强：CI（`android-tests.yml`）、`micaCheck`、大量 JVM / Robolectric / Roborazzi 测试  
- `CONTEXT.md`、`docs/TESTING.md` 等领域文档与分支同步更新  

---

## 总结

| 轴线 | Findings 数 | 最严重问题 |
|------|-------------|------------|
| **Bugbot** | 5（第一轮）/ 6（第二轮） | 软件解码失败对 UI 静默（错误无法 surfaced） |
| **Standards** | 3 硬性 + 若干判断项 | 软件播插播语义与 `CONTEXT.md` 不符 |
| **Spec** | 核心目标基本落地；2 项长期 TODO 未做；3 项行为存疑 | 软件播队列重同步可能误触 Exo |

---

## 合并前建议

在真机验收（见 `docs/TESTING.md` 设备清单：MP3/FLAC/ALAC/DSD、蓝牙、睡眠定时、插播、失败跳曲）之前，建议至少修复两项 **High**：

1. 播放失败 surfaced 到 UI（恢复或替代 `onPlayerError` → `playbackError` / `userMessage` 路径）  
2. `PendingMediaSelection` 在 `seekTo`/`play` 失败时释放，避免索引永久不同步  

文档同步：`PLAYER_PAGE_CONTRACT.md` §架构注意、`PlayerController` 类 KDoc 应与双后端实现对齐。

---

## 修订记录

| 日期 | 说明 |
|------|------|
| 2026-06-16 | 初版：三轴审查结果归档 |
| 2026-06-16 | 第二轮 Bugbot 复查：补充 findings + 未覆盖代码路径定位 |
