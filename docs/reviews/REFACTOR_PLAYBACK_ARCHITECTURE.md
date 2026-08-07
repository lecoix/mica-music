# 播放架构重构分支审查

> **分支**：`refactor/playback-architecture` → `exoplayer-only`（当前）
>
> **基线**：`main`（three-dot：`git diff main...HEAD`）
>
> **审查日期**：2026-06-16（初版）· 2026-06-19（exoplayer-only 第三～四轮）· 2026-08-07（状态所有权收拢）
>
> **范围**：
> - `refactor/playback-architecture`：约 150 文件，+11 166 / −1 438 行；Media3/软件双后端、Service 侧协调器、输入缓存
> - `exoplayer-only` / `fe2457a`：相对 `main` 约 197 文件，+14 976 / −3 726 行；ExoPlayer-only 播放栈，移除 FFmpeg 软件播管线，新增 DSF extractor 与 Media3 FFmpeg decoder 模块
>
> **仓库分支状态（2026-06-19 清理后）**：
> - **本地**：`exoplayer-only`（当前）、`main`（可能落后 `origin/main`）
> - **远程**：`origin/main`、`origin/exoplayer-only`
> - **已删除**：`experiment/coverflow-prebaked-reflection`、`origin/master`、三个 `origin/cursor/*` 实验/发版分支、`wip`（内容已被 `exoplayer-only` 祖先链包含）

---

## 审查方法

本审查按四条**独立轴线**并行执行，轴线之间不合并排序，便于分别判断「写得对不对」「做得对不对」与「还能不能更短」：

| 轴线 | 问题 | 依据 |
|------|------|------|
| **Bugbot** | 运行时缺陷、并发与状态不同步 | 分支 diff + 播放路径代码走读 |
| **Standards** | 是否违反项目已记录的约定 | `CONTEXT.md`、`PLAYER_PAGE_CONTRACT.md`、`TESTING.md`、`MOTION.md`、`AGENTS.md` |
| **Spec** | 是否实现来源 spec / 提交意图 | `CONTEXT.md`、`PLAYER_PAGE_CONTRACT.md`、`docs/TODO.md`、顶提交说明 |
| **Ponytail** | 过度工程、可删减复杂度 | `main..exoplayer-only` diff；只列删减项，不评正确性/安全/性能 |

**未纳入本次审查**：Security Review（用户未要求）；`MOTION.md` / 播放页布局条款（Bugbot/Standards 轮次以数据层与媒体服务为主）。

**第三轮（2026-06-19）**：仅 Bugbot 单轴，审查范围限定为 `exoplayer-only` 分支最新提交 `fe2457a`（`HEAD` vs `HEAD~1`）。

**第四轮（2026-06-19）**：仅 Ponytail 单轴，审查范围 `main..exoplayer-only`（`fe2457a`）。

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

### Bugbot（第三轮：exoplayer-only / fe2457a）

> 提交 `fe2457a9932681d3ac1fbfdf67b179387a6bdf56` — `feat(media): ExoPlayer-only 播放栈，移除 FFmpeg 软件播管线`
>
> 审查工具：Bugbot（`/review-bugbot`）；审查日期：2026-06-19

#### 随 exoplayer-only 已解决（相对第一、二轮）

| 原 Finding | 状态 | 说明 |
|------------|------|------|
| `alacStreamActive` 未写入仍被读取 | ✅ 已移除 | 软件播管线删除，`PlayerController` 不再引用 |
| 软件播解码失败对 UI 静默 | ✅ 架构变更 | `AlacAudioTrackEngine` / `FfmpegRunner` 等已删；Exo 错误经 `onPlayerError`  surfaced（`PlayerController.kt:443-455`） |
| `onMediaItemTransition` 无条件清零进度 | ⚠️ 部分修复 | 现排除 `SEEK` / `AUTO` reason（`PlayerController.kt:430-435`），保留 `PLAYLIST_CHANGED` 等场景清零 |
| `resolveFailureIndex` 未走 `PlaybackQueueNavigator` | ✅ 已对齐 | `ServicePlaybackEngineCoordinator.kt:266-279` 委托 `PlaybackQueueNavigator.nextIndex` |
| 软件播插播 / `syncExoQueuePreservingPlayback` 误触 Exo | ✅ 架构变更 | 双后端移除，插播统一走 Exo 播放列表 |
| `setPlaybackVolume` 对软件播无效 | ✅ 架构变更 | 睡眠定时器渐弱仅作用于 Exo 管线 |

#### 第三轮 Findings

| Severity | Location (file:line) | Finding |
|----------|----------------------|---------|
| **High（实机确认）** | `ServicePlaybackEngineCoordinator.kt:197-243` | **索引 seek 会强制自动播放。** `MicaCompositePlayer.seekTo(index, …)` 路由到 `onSelectMediaItem` → `startAt` → `start()`，而 `start()` 默认 `playWhenReady=true`。首次启动没有持久化快照，因此不触发；保存过播放状态后的后续冷启动会自动播放。该现象已在实机确认，违背「恢复进度但不自动播放」契约。 |
| **High** | `ServicePlaybackEngineCoordinator.kt:33-47` | **不支持格式时残留旧播放。** `PlaybackRouter` 判定格式不支持（如 DFF）时，`start()` 只 `handleFailure` 并 return，不更新 Exo 当前条目；随后 `playCurrent()` 在 active request 与 `currentMediaItem` 不一致时会 `start()` 仍在 Exo 上的旧曲，导致 UI 已切到不可播曲目却继续播放上一首。`MicaMediaService` 未设置 `onPlaybackFailure`，预 Exo 阶段的失败无法传到 UI。 |

#### 两项 P0 修复状态（2026-06-19）

| Finding | 状态 | 修复与证据 |
|---------|------|------------|
| 冷启动恢复 seek 强制 autoplay | ✅ 已解决并通过实机复验 | `onSelectMediaItem/startAt/start` 现在传递当前 `playWhenReady`；暂停状态下恢复索引与位置不会被默认值重新打开播放。`ServicePlaybackEngineCoordinatorTest.indexedSelectionPreservesPausedIntentDuringRestore` 覆盖真实 `MicaCompositePlayer` 路由；2026-06-19 实机确认冷启动不再强制播放。 |
| Unsupported/DFF 残留旧播放且无提示 | ➖ 代码已有保护，降为最低优先级 | 拒绝前先暂停/停止旧 Exo 输出，把目标条目选为当前项但不 prepare/play；失败请求再次收到 `play()` 时不会复活旧曲。当前没有 DFF 测试资源，且产品明确不支持 DFF，因此不再阻塞发布；待真实资源或用户反馈出现时机会性复验。 |

完整 `:app:testDebugUnitTest` 于 2026-06-19 通过。冷启动 autoplay 已最终关闭；DFF 保留现有防护与测试，不进入近期验收计划。

#### 未覆盖代码路径定位（第三轮）

1. **冷启动 / 服务恢复时 seek 触发 autoplay**
   - **触发路径 A**：`ServicePlaybackStateCoordinator.tryRestore()` → `player.playWhenReady = false` → `player.seekTo(index, positionMs)`（`ServicePlaybackStateCoordinator.kt:94-96`）
   - **触发路径 B**：`PlayerController.bootstrapQueue()` → `active.seekTo(index, snapshot.positionMs)`（`PlayerController.kt:613`）
   - **缺口点**：`MicaCompositePlayer.seekTo(mediaItemIndex, …)` 路由到 `onSelectMediaItem` → `startAt` → `start(song, safe, position, queue)`，**未传递** `playWhenReady=false`（`ServicePlaybackEngineCoordinator.kt:242`）；`start()` 默认 `playWhenReady=true` 并写入 `startExoPlayback`（`ServicePlaybackEngineCoordinator.kt:137-180`）。

2. **DFF 等不支持格式：Exo 条目 stale + UI 无错误**
   - **触发路径 A**：`ServicePlaybackEngineCoordinator.start()` → `PlaybackRouteDecision.Unsupported` → `handleFailure` + return，**不调用** `startExoPlayback`（`ServicePlaybackEngineCoordinator.kt:144-159`）
   - **触发路径 B**：用户点播放 → `MicaCompositePlayer.play()` → `playCurrent()` → 读 `player.currentMediaItem`（仍为旧曲）→ `start(oldSong, …)`（`ServicePlaybackEngineCoordinator.kt:33-46`）
   - **缺口点**：`MicaMediaService` 创建 `ServicePlaybackEngineCoordinator` 时**未挂载** `onPlaybackFailure`（`MicaMediaService.kt:213-219`）；客户端 `onPlayerError` 仅在 Exo 已启动后触发，预 Exo 拒绝路径无 UI 回调。

#### 测试覆盖缺口（第三轮）

| 测试 | 缺口 |
|------|------|
| `ServicePlaybackStateCoordinatorTest.restorePreparesCurrentItemWithoutAutoPlay` | 使用 mock `Player`，未经过 `MicaCompositePlayer` 路由，**无法捕获** seek→autoplay 集成回归 |
| `ServicePlaybackEngineCoordinatorTest.dffIsRejectedBeforeExoPlaybackStarts` | 只验证 Exo 未被 `setMediaItems`，**未覆盖**切到 DFF 后 `play()` 的 stale playback |
| （缺失） | `bootstrapQueue` + `MicaCompositePlayer` 端到端：恢复后 `playWhenReady` 应保持 false |
| （缺失） | 不支持格式切歌后 `playCurrent()` 不应继续旧曲；`onPlaybackFailure` 应 surfaced 到 `playbackError` |
| `PlayerControllerBoundaryTest.playSongSwitchPushesAuthoritativeQueueEvenWithoutSessionTimelineClass` | **当前失败且断言已过时。** 测试仍要求客户端直接调用 `MediaController.setMediaItems()`；当前实现把目标队列写入 `PendingPlaybackNavigation`，由 Service 消费后设置 Exo playlist。应替换为覆盖 `PlayerController → PendingPlaybackNavigation → ServicePlaybackEngineCoordinator → Exo playlist` 的跨边界集成测试，而不是把失败直接判为真机队列不同步。 |

#### 2026-06-19 状态澄清

- **权威队列仍然需要**：Exo playlist 位于播放 Service，是出声真相源；`PlayerController.songQueue` 是供 Compose 使用的 UI 镜像。这是 App/UI 与 Service 的边界，不是双音频后端遗留。
- **队列模式不是双写源**：repeat/shuffle 由 UI 通过 `MediaController` 写入同一个 Player；Controller 仅通过 `onRepeatModeChanged` / `onShuffleModeEnabledChanged` 镜像回来，Service 读取同一 Player 状态。原“队列模式双源”疑虑关闭。
- **同步正确性改用集成测试证明**：现有失败测试验证的是旧实现细节，不能证明或否定当前跨 binder 时序下的队列一致性。

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

### exoplayer-only 分支（fe2457a）补充

| 项 | 状态 |
|----|------|
| `CONTEXT.md` Exo-only 管线定义 | ✅ 已与实现同步（移除双后端 / 软件播术语） |
| `ServicePlaybackStateCoordinator` 恢复不自动播放 | ⚠️ 意图正确，但 seek 路由破坏 `playWhenReady=false`（见第三轮 Bugbot #1） |
| `MicaMediaService.onPlaybackFailure` 接线 | ❌ 未挂载；预 Exo 失败无法到 UI |
| 文档与实现：DFF 不支持 | ✅ `PlaybackArchitectureTest` + `ServicePlaybackEngineCoordinatorTest` 覆盖路由 |

### 跳过（工具已覆盖）

`micaCheck` 和 Lint 由现有 Gradle 任务覆盖；项目当前**未配置 JaCoCo/Kover 覆盖率报告或阈值门禁**。
`docs/TESTING.md` 中“播放状态机、队列和数据库分支覆盖至少 80%”是尚未可测量的质量目标，不代表测试数量，
也不能由“测试文件很多”推断已经达到。

---

## Spec（是否实现预期目标）

### 缺失 / 部分实现

| Spec 来源 | 要求 | 状态 |
|-----------|------|------|
| `CONTEXT.md` | 双后端 `MEDIA3` / `SOFTWARE`、`PlaybackRouteDecision` | ⚠️ **exoplayer-only 已改为 Exo-only**；`.dsf` Supported、`.dff` Unsupported |
| `fe2457a` 提交说明 | ExoPlayer-only，移除 FFmpeg 软件播管线 | ✅ 软件播组件已删；`libffmpegJNI.so` 保留为 Media3 decoder 扩展 |
| 历史提交说明 | 「输入缓存」 | ➖ exoplayer-only 已删除 `AudioInputCache`；普通格式由 Exo 直接读取 URI，该项不再是当前实现目标 |
| `TODO.md` L129 | 原生解码优先，FFmpeg 仅兜底 | ✅ Exo + `libffmpegJNI` 扩展解码 ALAC/DSD |
| `TODO.md` L130 | PCM 流式，减少整首落盘 | ✅ 软件播整首 PCM 路径已移除 |
| 提交说明 | 架构迁移收尾 | ✅ exoplayer-only 删除大段 legacy；`CONTEXT.md` / `DSD_EXO_PLAYBACK.md` 已同步 |

### 范围蔓延（spec 未要求）

- 服务侧队列/游标持久化（`ServicePlaybackStateCoordinator` / `Store`）— spec 仅定义 `PlayerController` 的 `PlaybackSession`  
- ALAC 解码器进程级熔断（`recordAlacDecoderFailure`）  
- `AudioQualityMode` + Exo offload 切换（EQ 开关联动）

### 已实现但存疑

- ~~**软件播插播「重排已有曲」**~~：exoplayer-only 已删除软件播与 `alacStreamActive`，该疑虑失效。
- ~~**队列模式双源**~~：已关闭。Controller 侧 `playbackQueueMode` 是同一 Player repeat/shuffle 状态的 UI 镜像，不是独立写入源。

---

## Ponytail（过度工程 / 可删减）

> **方法**：Ponytail review — 只关心 diff 能否变短；不评正确性、安全、性能。  
> **格式**：`path:L#: tag: finding. replacement.`  
> **标签**：`delete` · `stdlib` · `native` · `yagni` · `shrink`

### 分支演进（与 `wip` 对比）

`wip`（`0.1.7-diag5`）是 `exoplayer-only` 的**严格祖先**，无独有提交。`exoplayer-only` 在其上追加 3 个播放相关提交：

| 提交 | 版本 | 要点 |
|------|------|------|
| `b54ee6f` | `0.1.8-hybrid7` | Media3 + 软件播双后端、`ServicePlayback*` 协调层、`AudioInputCache` |
| `d38d8a9` | — | 切歌 / 封面流手势状态同步 |
| `fe2457a` | `0.1.8-hybrid8` | Exo-only，删除软件播管线，新增 `media3-ffmpeg-decoder`、DSF |

结论：架构方向正确（净删 ~3 700 行软件播），但 **hybrid7 协调层**与 **diag5 诊断层**在 Exo-only 落地后尚未收干净。

### Findings 汇总表

| 优先级 | Tag | 位置 | 发现 | 替换 / 动作 | 约省行数 |
|--------|-----|------|------|-------------|----------|
| ✅ 已完成 | delete | `app/src/main/assets/ffmpeg/arm64-v8a/` | 旧 FFmpeg CLI 仍作为 asset 打进 APK（原始 2.31 MB；Perf APK 内压缩后约 1.11 MB） | 已删目录与旧 CLI 构建脚本；保留当前 Media3 FFmpeg decoder 构建链 | 代码 ~15 行 + 资产 |
| ✅ 已完成 | delete | `util/DecodePerformance.kt` | Exo 线不再产生 `decode-input-copy` / `decode-ffmpeg-ready` 打点 | 已删整文件；`TrackSwitchPerformance` 已去掉恒为 `decode=none` 的汇总 | ~120 |
| ✅ 已完成并真机确认 | delete | `AudioOutputCapabilities.kt:L19-27` | `SoftwareAudioRouteState`：`update()` 零调用，`current()` 恒 null | 已删对象；零残余引用；`route()` 直接用 `AudioManager`；删除后 DSF 真机播放与输出规格选择正常 | ~10 |
| ✅ 已完成 | delete | `ServicePlaybackRequestState.kt` | `Switching` 写入后立即被覆盖 | 已移除整套展示型 `PlaybackEngineState` | ~3 |
| ✅ 已完成 | delete | `ServicePlaybackRequestState.kt` | `setUserPlayIntent()` 在 Exo-only 后生产零调用 | 已删方法与过时测试 | ~25 |
| ✅ 已完成 | yagni | `PlaybackArchitecture.kt` | `PlaybackEngineState` 除失败信息外只写不读 | 收为 `activeRequest` + 私有 terminal failure | ~80 |
| ✅ 已完成 | delete | `ServicePlaybackRequestState.kt` | `Playing` / `Paused` 状态无人读 | 保留稳定播放清零失败计数及 `markFailed` | ~20 |
| ✅ 已完成 | delete | `PlaybackArchitecture.kt` `PlaybackRequest` | `generation` 恒等于 `id`；`userPlayIntent`、`qualityMode` 已失去消费者 | `accepts()` 现比对 `id` + `songId` + `sourceRevision` | ~15 |
| P1 | delete | `AlacPcmFormat.kt:L22-49` | `byteOffsetForMs` / `framesForMs` / `fromSong` 无调用方 | 删三个成员；类型可重命名为 `PcmOutputFormat` | ~20 |
| P2 | shrink | `AlacSessionState.kt` | 接口仍名 `AlacSessionCommandHandler` | 重命名为 `PlaybackCommandHandler` | 命名清理 |
| P2 | shrink | `PlaybackCapabilityDiagnostics.kt:L63-76` | 反射调 `FfmpegLibrary.ffmpegHasDecoder` | 用公开 API 或仅显示 `isAvailable()` / `getVersion()` | ~15 |
| P2 | delete | `third_party/media3-ffmpeg-decoder/` | `libffmpegJNI.so` 存两份（`jniLibs/` + `src/main/jniLibs/`） | 留一份；`app/build.gradle.kts` 只扫单路径 | 重复二进制 |
| P2 | shrink | `CoverFlowCarouselHost.kt:L43` | 邻曲预加载 `±3`，`NowPlayingCoverSection` 已是 `±1` | 对齐为 `±1`，避免重复解码 | ~4 |
| P2 | yagni | `data/LibraryTestBoundaries.kt` | `LibraryScanner` / `LibraryStore` 接口 + 生产实现，仅为单测 fake | 接口移到 `src/test` 或 Robolectric 真实现 | ~132 |
| P2 | yagni | `data/PlaybackTestBoundaries.kt` | `MediaControllerConnector` 单层抽象，生产仅一个实现 | 内联 `AndroidMediaControllerConnector` | ~72 |
| P2 | shrink | `media/SongMediaItemCodec.kt` | 整首 `Song`（25+ 字段）塞进 `MediaItem.extras` | 服务侧只存 `mediaId` + URI/MIME，其余曲库查 | ~40–60 |
| P2 | delete | `tools/dsf_probe.py` | 132 行一次性调试脚本，不在 CI / 构建链 | 无 | ~132 |
| P3 | yagni | `util/TrackSwitchPerformance.kt` | 457 行 Choreographer 帧采集 + 切歌时间线 | 若 `PERFORMANCE_INVESTIGATION` 结案则整文件删除 | ~457 |
| P3 | yagni | `AudioEnvironmentDiagnostics.kt` + `BluetoothAudioDiagnostics.kt` | 266 行常驻 `AudioDeviceCallback`，仅写 `DiagnosticLog` | 调查结束后与 TrackPerf 一并删 | ~266 |
| P3 | yagni | `ServicePlaybackStateCoordinator.kt` + `ServicePlaybackStateStore.kt` | 398 行手写 JSON 持久化 + 单线程 executor + 3s 定时刷盘 | 若只需「上次曲 + 进度」，缩为 SharedPreferences 若干 key | ~150–250 |
| P3 | shrink | `app/build.gradle.kts:L17-145` | Jellyfin AAR 回退 + 本地 JNI 双路径 + QA side-by-side 三套解析 | 定一种分发方式，删其余分支 | ~40 |

### Ponytail 单行格式（机器可读）

```
DONE app/src/main/assets/ffmpeg/arm64-v8a/: delete: 旧 FFmpeg CLI asset 已删除；保留 Media3 decoder 构建链.
DONE app/src/main/java/com/mica/music/util/DecodePerformance.kt: delete: 文件与恒为 decode=none 的汇总已删除.
VERIFIED app/src/main/java/com/mica/music/media/AudioOutputCapabilities.kt:L19-27: delete: SoftwareAudioRouteState 已删除且零残余引用；route() 直读 AudioManager；DSF 真机播放已通过.
DONE app/src/main/java/com/mica/music/media/ServicePlaybackRequestState.kt: delete: Switching 与展示型状态已删除.
DONE app/src/main/java/com/mica/music/media/ServicePlaybackRequestState.kt: delete: setUserPlayIntent 已删除.
DONE app/src/main/java/com/mica/music/media/PlaybackArchitecture.kt: yagni: PlaybackEngineState 已收为 activeRequest + terminal failure.
DONE app/src/main/java/com/mica/music/media/ServicePlaybackRequestState.kt: delete: Playing/Paused 写入已删除，稳定播放清零语义保留.
DONE app/src/main/java/com/mica/music/media/PlaybackArchitecture.kt: delete: generation/userPlayIntent/qualityMode 请求字段已删除.
app/src/main/java/com/mica/music/media/AlacPcmFormat.kt:L22-49: delete: byteOffsetForMs/framesForMs/fromSong 无调用. 删成员.
app/src/main/java/com/mica/music/media/AlacSessionState.kt: shrink: AlacSessionCommandHandler 命名过时. PlaybackCommandHandler.
app/src/main/java/com/mica/music/media/PlaybackCapabilityDiagnostics.kt:L63-76: shrink: 反射 ffmpegHasDecoder. 公开 API 或减项.
third_party/media3-ffmpeg-decoder/: delete: libffmpegJNI.so 双份存储. 留一份.
app/src/main/java/com/mica/music/ui/screens/player/view/CoverFlowCarouselHost.kt:L43: shrink: 预加载 ±3 与 NowPlayingCoverSection ±1 冲突. 对齐 ±1.
app/src/main/java/com/mica/music/data/LibraryTestBoundaries.kt: yagni: 测试接缝接口在生产 main 源集. 移到 test 或内联.
app/src/main/java/com/mica/music/data/PlaybackTestBoundaries.kt: yagni: MediaControllerConnector 单层抽象. 内联.
app/src/main/java/com/mica/music/media/SongMediaItemCodec.kt: shrink: 25+ 字段塞进 extras. mediaId + URI/MIME.
tools/dsf_probe.py: delete: 一次性调试脚本. 无.
app/src/main/java/com/mica/music/util/TrackSwitchPerformance.kt: yagni: 457 行诊断（调查未结案则暂留）. 结案后删.
app/src/main/java/com/mica/music/util/AudioEnvironmentDiagnostics.kt: yagni: 常驻设备回调仅写日志. 结案后与 TrackPerf 删.
app/src/main/java/com/mica/music/media/ServicePlaybackStateCoordinator.kt: yagni: 398 行 JSON 持久化. 缩为 SP 若干 key（若产品允许）.
app/build.gradle.kts:L17-145: shrink: 三套 media3-ffmpeg 解析路径. 定一种.
```

### 评分与删减阶段

| 阶段 | 条件 | 约可删减 |
|------|------|----------|
| **已完成（2026-06-20）** | 不依赖性能调查结案 | 旧 CLI asset + `DecodePerformance` + `SoftwareAudioRouteState` 已删除；Perf APK 预计减少约 **1.11 MB 压缩体积** |
| **已完成（2026-06-20）** | 收缩请求状态机 | 删除展示型状态与冗余请求字段；保留请求身份、失败去重及连续失败计数重置 |
| **调查结案后** | `PERFORMANCE_INVESTIGATION.md` 收工 | 再 **~900 行**（TrackPerf + 环境/蓝牙诊断） |
| **需单独设计** | 更换队列恢复或 `Song` 解析方案 | 不计入当前删减；现有 StateStore、测试 seam 与 Song transport 均承载真实行为 |

低风险资产清理与请求状态收缩均已落地；其余数字须按后续独立设计重新计算，不再沿用原 `-1700` 粗估。

若 P0/P1 全部落地后无新膨胀，可记为 **Lean enough to ship**（诊断层按产品节奏保留或删除）。

### Ponytail 结论

- **做对了**：软件播整段删除、Exo 单链路、DSF / `libffmpegJNI` 扩展、封面预烘焙与测试基建保留合理。
- **还没收干净**：软件播时代命名，以及仍待性能调查结案的 diag5 诊断层。
- **建议顺序**：① ~~删旧 CLI asset + `DecodePerformance` + 空路由缓存~~（已完成）→ ② ~~收 `PlaybackEngineState` / `PlaybackRequest` 死字段~~（已完成）→ ③ 调查结案后删诊断层。`ServicePlaybackStateStore` 承担完整队列恢复语义，不再默认列为删减项。

---

## 积极面

- 播放架构从 Controller 内嵌软件播，迁到 **Service 侧协调器**（`ServicePlaybackEngineCoordinator`、`PlaybackRequest` 状态机），边界更清晰  
- 测试体系大幅补强：CI（`android-tests.yml`）、`micaCheck`、大量 JVM / Robolectric / Roborazzi 测试  
- `CONTEXT.md`、`docs/TESTING.md` 等领域文档与分支同步更新  

---

## 总结

| 轴线 | Findings 数 | 最严重问题 |
|------|-------------|------------|
| **Bugbot** | 5（第一轮）/ 6（第二轮）/ **2（第三轮，exoplayer-only）** | 第三轮：**恢复进度时 seek 强制 autoplay**（已关闭，见修订记录） |
| **Standards** | 3 硬性 + 若干判断项（第一、二轮）；exoplayer-only 新增 1 项接线缺失 | exoplayer-only：`onPlaybackFailure` 未挂载 |
| **Spec** | exoplayer-only 核心目标已落地 | 冷启动恢复行为与 `PlaybackSession` 契约仍有缺口 |
| **Ponytail** | **20 项**（第四轮历史清单；8 项已完成） | 旧 CLI、失效诊断、空路由缓存及 hybrid7 请求状态遗留已删除 |

> 第一、二轮中多项 High 随 exoplayer-only 架构变更**已解决**；当前合并阻塞项以**第三轮 2 项 High** 为准。

---

## 合并前建议

### exoplayer-only（fe2457a，当前阻塞）

当前按以下顺序处理：

1. **P0 实机复验 — 大队列手动切歌发热**：代码已改为队列对齐时只验证目标索引并在现有 Exo playlist 内 `seekTo`；不再构造整队列导航载荷、`setMediaItems` 或触发 `mirror-rebuild`。队列刚变更时保留一次重建兜底，完整单测通过。等待原 4500 首设备复验温度与日志。详见 `.scratch/manual-skip-queue-rebuild/issues/01-manual-skip-bypasses-queue-aligned-skip.md`。
2. **P2 — 建立覆盖率门禁**：如仍坚持 80% 分支覆盖目标，配置 JaCoCo 或 Kover，并明确统计包范围；这不阻塞前述运行时修复。
3. **最低优先级 / 机会性 — DFF**：产品明确不支持播放，当前也没有测试资源。保留现有拒绝与防止旧曲复活的保护；只有取得真实文件或出现用户反馈时再复验、修整提示链路。

已关闭：EQ 动态切换已通过实机确认；连续选择 18 首的日志显示 Controller 目标、Service `startAt` 与最终播放曲持续一致，未见旧回调回写，因此不再把额外的全链路集成测试列为当前工作项。该样本切歌间隔约 0.85–2 秒，不等同于亚百毫秒点击风暴；只有后续出现相关反馈时再补压力测试。

已关闭：播放异常不再直接展示 Media3/FFmpeg 的底层长消息。播放页仅保留短原因；只有权限、文件缺失、不支持格式或音频设备等需要用户处理的情况才显示 Snackbar；原始异常完整写入诊断日志。

已关闭：`PLAYLIST_CHANGED` 仅在当前歌曲真的变化时清零进度，同曲队列重排保留进度。`mica-diagnostics (31).txt` 暴露的冷启动 UI 锁在 `0:01` 也已定位并修复：保存位置 `622ms` 的临时钉点会在开始播放或主动 seek 时释放，随后 UI 重新跟随 Exo 实际位置。

建议补测：

- `MicaCompositePlayer` + `ServicePlaybackEngineCoordinator` 集成：`seekTo(index, pos)` 在 `playWhenReady=false` 时不启动播放
- DFF 切歌后 `play()` 不继续旧曲；预 Exo 失败 surfaced 到 UI
- `PlayerController` 最后一次快速选择的队列与 Service Exo playlist 一致

### refactor/playback-architecture（历史，已由 exoplayer-only 替代）

~~1. 播放失败 surfaced 到 UI~~ → Exo `onPlayerError` 已恢复

~~2. `PendingMediaSelection` 失败释放~~ → 仍建议回归验证

文档同步：`PLAYER_PAGE_CONTRACT.md` §架构注意、`PlayerController` 类 KDoc 应与 **Exo-only** 实现对齐（双后端描述应删除）。

### Ponytail 瘦身（第四轮，非阻塞合并）

按 §Ponytail 建议顺序，合并后可开独立 cleanup PR：

1. ~~**P0**：删 `app/src/main/assets/ffmpeg/`、`DecodePerformance.kt`、`SoftwareAudioRouteState`~~（2026-06-20 已完成）
2. ~~**P1**：收 `PlaybackEngineState` / `PlaybackRequest` 死字段与 `setUserPlayIntent`~~（2026-06-20 已完成）
3. **P3（调查结案后）**：删 `TrackSwitchPerformance` + 环境/蓝牙诊断层

下一阶段目标：处理软件播时代命名；性能与蓝牙调查结案后再评估诊断层。

---

## 2026-08-07 复核：状态所有权收拢与数据层边界

本分支继续沿「服务权威、App 镜像」方向收口，重点是把 Controller 内散落的可变状态收成单一 owner，并用显式 generation/request/revision 拒绝旧回调与旧镜像：

| 提交 | 内容 | 覆盖测试 |
|------|------|----------|
| `1e39fe64` | `PlaybackQueueCoordinator` 收口队列模型与镜像调度；陈旧镜像在请求号、本地 revision 或当前连接变化后丢弃 | `PlayerControllerQueueModelTest`、`PlaybackQueueMirrorTest`、`MediaControllerQueueSyncTest` |
| `24d638d7` | `PlaybackTimelineCoordinator` 收口进度、时长、pending seek 与恢复钉点 | `PlaybackTimelineCoordinatorTest`、`PendingSeekClearTest` |
| `ce406e47` | `PlaybackTuningCoordinator` 收口 speed/pitch 的 requested/effective 状态 | `PlaybackTuningCoordinatorTest` |
| `406524ce` | `PlaybackConnectionSession` 用连接 generation 拒绝旧连接回调与播放边界 | `PlayerControllerBoundaryTest` |
| `375ba818` | 陈旧队列镜像结果不可覆盖新本地队列 | 见上镜像测试 |
| `dac84b0c` | 外部队列仅在有可存续 URI 权限时进入恢复快照 | `ExternalAudioOpenTest`、`TransientPlaybackCatalogTest`、`ServicePlaybackStateCoordinatorTest` |
| `e22edeb7` | `AudioPipelineCoordinator` 集中 EQ / 频谱 tap / offload 偏好 / 路由事件 | `AudioPipelineCoordinatorTest` |
| `83e324ba` | `AudioOffloadCircuitBreaker` 确认失速后按 build fingerprint 停用 offload | `AudioOffloadCircuitBreakerTest`、`AudioOffloadPreferencesRobolectricTest` |
| `523bc418` | Song 模型与 UI 解耦：`coverColor` 移到 UI 主题扩展，格式标签经 `metadata`；`LyricLine` / `LyricCue` 收进数据层 | `SongEntityTest`、`SongRowTest` |
| `20075b8e` | 歌单持久化迁到 Room（schema v17）；旧 `mica_playlists` JSON 一次性迁移 | `PlaylistStoreTest`、`RoomMigrationContractTest`、`DatabaseMigrationTest` |

结论与残留风险：

- 播放侧权威边界保持成立：Service 原始 Player 仍是出声真相源，Controller 内只保留派生镜像；本次改动把「旧连接/旧镜像写回」从依赖回调顺序改为显式 generation/request/revision 拒绝。
- 歌单 Room 化不改变曲库 snapshot 的 `scanGeneration` / `storeRevision` 协议；歌单写入与曲库发布是两套 owner。
- offload 熔断按 build fingerprint 记录失败，仍不能替代真机 ROM 矩阵验收。
- 未解决项维持原状：DFF 机会性复验；`TrackSwitchPerformance` / 环境诊断等诊断层等性能调查结案后再评估。

---

## 修订记录

| 日期 | 说明 |
|------|------|
| 2026-06-16 | 初版：三轴审查结果归档 |
| 2026-06-16 | 第二轮 Bugbot 复查：补充 findings + 未覆盖代码路径定位 |
| 2026-06-19 | 第三轮 Bugbot（`exoplayer-only` / `fe2457a`）：2 项 High；标注随 Exo-only 已解决项；更新 Spec / 合并建议 |
| 2026-06-19 | 当前状态复核：冷启动 autoplay 升级为实机确认；关闭队列模式双源疑虑；识别过时队列测试；澄清 80% 分支覆盖尚无工具门禁；重排当前修复优先级 |
| 2026-06-19 | 两项 P0 已实现并通过完整 JVM/Robolectric 单测：恢复保留暂停意图；Unsupported 选择停止旧曲、禁止重播并向 UI 显示提示；等待实机复验后最终关闭 |
| 2026-06-19 | 冷启动 autoplay 已通过实机复验并关闭；DFF 因无测试资源且产品不支持，降为最低优先级机会性事项，不再阻塞发布 |
| 2026-06-19 | EQ enabled 变化已接入 Exo audio processor 管线刷新，完整单测通过；等待实机确认即时生效与跳音表现 |
| 2026-06-19 | EQ 即时生效通过实机确认；冷启动恢复进度不再泄漏到下一首并通过实机确认；realme Android 16 连续切换 18 次正常，常规连续选歌验收关闭 |
| 2026-06-19 | 播放错误展示分层：页面红字使用短原因，需要用户处理时才弹 Snackbar，底层 Media3/FFmpeg 异常仅进入诊断日志；扫描完成提示样式加入 TODO 待讨论 |
| 2026-06-19 | transition 进度语义收口：同曲队列变化保留、换曲归零；冷启动恢复钉点未释放导致进度条锁在 0:01 已修复，并通过完整单测与实机复验 |
| 2026-06-19 | 特定测试人员确认封面流切歌已不卡但大曲库仍发热；定位为手动切歌绕过队列对齐早退、每次重建整条 Exo playlist，列为当前性能 P0 |
| 2026-06-19 | 大队列手动切歌改为 O(1) 目标索引对齐 + 现有 Exo playlist 内 seek；队列变更仍保留整队列兜底，完整单测通过，等待 4500 首实机发热复验 |
| 2026-06-19 | 仓库分支清理：删除 `experiment/coverflow-prebaked-reflection`、`origin/master`、三个 `origin/cursor/*`、`wip`；远程仅余 `main` + `exoplayer-only` |
| 2026-06-19 | **第四轮 Ponytail**（`main..exoplayer-only`）：20 项可删减发现归档；`net: -450` 行（现）/ `-1700` 行（诊断结案 + 持久化瘦身后）；见 §Ponytail |
| 2026-06-20 | 第一组低风险清理完成：删除旧 FFmpeg CLI asset 与构建脚本、`DecodePerformance`、`SoftwareAudioRouteState`；保留 Media3 FFmpeg decoder 构建链、DSD 能力检测及未结案性能诊断。`SoftwareAudioRouteState` 已确认零残余引用，删除后的 DSF 真机播放与输出规格选择正常。重新评估后，不再默认建议删除测试 seam、缩减 `SongMediaItemCodec` 或弱化服务队列恢复。 |
| 2026-06-20 | 播放请求状态收缩：删除 Exo-only 后失效的 `PlaybackEngineState`、`setUserPlayIntent`、`generation`、请求级 `qualityMode` 等；保留当前请求身份、source revision、失败去重、自动跳曲计数及稳定播放后的计数重置。 |
| 2026-08-07 | 第五轮复核：状态所有权收拢、陈旧连接/镜像拒绝、外部队列恢复边界、音频管线协调与 offload 熔断、Song/UI 解耦、歌单 Room 化 |
