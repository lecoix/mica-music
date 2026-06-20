# 切歌卡顿 / 发热 — 测试问题与改进记录（#02）

> **前序文档**：[PERFORMANCE_INVESTIGATION.md](PERFORMANCE_INVESTIGATION.md)（hybrid4–hybrid8 主线调查；队列对齐轻量切歌见 §当前结论）
>
> **当前分支 / 版本**：`exoplayer-only` · `0.1.8-Exo-only`（`versionCode 15`）
>
> **整理日期**：2026-06-20
>
> **文档性质**：承接 #01 中「大队列手动切歌发热」修复后的第二轮实机复验；记录 **已验证生效**、**尚未解决** 与 **已规划未落地** 项。

---

## 0. 本轮测试背景

| 项 | 内容 |
|----|------|
| 主要设备 | 小米 `25102RKBEC`，Android 16（SDK 36） |
| 曲库规模 | 约 **4518** 首（大队列） |
| UI 组合 | `RETRO_3D` 封面流 + `COVER_GLOW` 背景 + **频谱开启** |
| 音频 | `HIFI`，`dsp=false`，`spectrum=true`，`offload=false`（软解 + 频谱） |
| 测试动作 | 快速按钮连切、部分滑动切歌；单次会话约 **8 分钟** / **82 次** `manual next` |
| 主观结论 | **按钮切歌卡顿明显改善**；**发热体感改善有限** |

### 0.1 日志样本索引

| 文件 | 场景 | 用途 |
|------|------|------|
| `mica-diagnostics(1).txt` | 4518 首、~34 分钟（修复前或混合版本） | 定位 `mirror-rebuild` 每次 seek、按钮 `animAvg` 80–140ms |
| `mica-diagnostics(3).txt` | 256 首、~1 分钟 | 验证 `mirror-index-sync` + 按钮 visual-first 在小队列生效 |
| `mica-diagnostics(1)(1).txt` | 4518 首、~8 分钟、82 次按钮切歌 | **修复后大队列复验**：卡顿好、发热仍在 |
| `mica-diagnostics(2)(1).txt` | 4518 首、~38 分钟（22:20–22:58）；蓝牙 **漫步者 Comfo Clip** | **测试反馈复验**：播放列表调序失败、43°C 发热、换背景后切歌 BT 断连 |

导出方式：关于页 → 导出诊断日志。分析时优先搜 `QueueSync`、`TrackPerf`、`manual next`、`cover-load`、`reflection-bake`、`AudioRoute`、`controller-setMediaItems`、`targetMismatch`。

---

## 1. 问题清单（本轮）

### 1.1 已验证解决

| # | 问题 | 根因（简述） | 改动 | 日志判据 |
|---|------|--------------|------|----------|
| A | 手动切歌每次 `exo-setMediaItems` + 多次 `mirror-rebuild` | 队列已对齐仍走整队列重建 | #01 已述：`controller-sync-skipped` + `exo-seek-existing` | `(1)(1)`：81× seek，无切歌路径 `exo-setMediaItems switching` |
| B | seek 仍触发全量 `mirror-rebuild`（与 A 独立） | `onTimelineChanged` 不区分 playlist 变更 vs 索引 seek | `PlayerController`：`PLAYLIST_CHANGED` 或镜像未对齐才 `syncQueueMirrorFromPlayer`，否则 `syncQueueIndexFromPlayer`（`mirror-index-sync`） | `(1)(1)`：**5** 次 `mirror-rebuild`（冷启动），**151** 次 `mirror-index-sync`（~0.03–0.10ms） |
| C | 按钮切歌比滑动更卡 | 按钮立刻 `playSong`，动画与队列/解码并行 | 按钮走 `manualNextTarget()` → `CoverFlowCarouselNavigationBridge.skipToIndex()` → `playQueueIndexAfterVisualCommit`（与滑动 commit 同路径） | `(1)(1)`：`manual next` 先于 `cover-animation-*`，`playSong` / `audio-start` 在 `cover-animation-end` 之后；`animAvg` **~8ms**（改前 80–140ms） |

详见 [.scratch/manual-skip-queue-rebuild/issues/01-manual-skip-bypasses-queue-aligned-skip.md](../.scratch/manual-skip-queue-rebuild/issues/01-manual-skip-bypasses-queue-aligned-skip.md)。

### 1.2 尚未解决（当前 P0 / P1）

| # | 问题 | 证据 | 分析 | 优先级 |
|---|------|------|------|--------|
| D | **`cover-load` 全尺寸解码慢、几乎不命中缓存** | `(1)(1)`：~150 次 `cover-load-end`；连点窗口 `coverLoad=1/1 hit=0 max=200–415ms`；尺寸 800²–2416² | `MicaImageLoaders.buildCoverRequest` 无 `.size()`；预载绑在 `currentIndex` 变更，晚于按钮意图 ~400ms | **P0**（发热 + 掉帧） |
| E | **`reflection-bake` 倒影烘焙** | 每动画窗 ~7 次；首次进 CoverFlow `11/11 hit=0 avg=179ms max=397ms`；`lastInvalidate=reflection-bake` 伴随 132–223ms 帧尖峰 | 7-lane 轨窗对新封面重复 bake；依赖已解码全尺寸 bitmap | **P1**（尖峰；缓存热后减轻） |
| F | **大队列连切仍发热** | 主观：卡顿好、温度仍高；日志：上述 D+E 每 0.5–1s 重复 + 底噪 | 按钮优化去掉的是 **队列镜像 + 动画抢帧**；未去掉 **图像 CPU** 与 **播放栈常驻负载** | 现象；根因见 §3 |
| G | **`manualNextTarget` 未做意图预载** | `host-preload` 在 index 变更后命中（<0.1ms），但 `cover-load` 仍在动画末才开始 | 最早意图信号未用于抢跑解码 | **P1**（规划见 §5.2） |
| H | **播放列表拖拽调序失败（大队列）** | `(2)(1)` **22:50:06–22:50:44**：连续 `controller-setMediaItems`（4518 首，单次 170–470ms）+ `mirror-rebuild`；`startIndex` 在 389↔393 间跳变，多次 `targetMismatch=true` | `moveInQueue` 每次拖拽全量 `setMediaItems`；`PlaybackQueueSheet` 的 `LaunchedEffect(queue)` 在 mirror 回写时覆盖本地 `items`，与拖拽乐观更新打架 | **P1**（功能 bug；见 §3.3） |
| I | **换背景后快速切歌蓝牙断连** | `(2)(1)` **22:42:37** `bg=THEME`（自 `ARTWORK_GRADIENT`）；**22:42:48–50** 封面流连跳 631→634；**22:42:50.243** `BT_SCO` 移除 → **22:42:50.909** `BT_A2DP` 移除，与 `playSong` index=634 + `exo-seek-existing` 同时；约 7s 后重连。另 **22:50:50** 调序风暴期间再次断连 | 日志无 `BECOMING_NOISY` / 焦点丢失；更像路由剧变 + 主线程重负载（切歌 seek、多封面 decode）触发系统或耳机断 A2DP；**时间线强相关，App 单点未定责** | **P2**（需加 AudioFocus / 设备移除日志复现；见 §3.4） |

### 1.3 播放底噪（非本轮回归，但影响发热基线）

| 项 | 日志 | 说明 |
|----|------|------|
| HIFI 软解 | `offload=false` | FLAC 等由 CPU 解码，切歌与否都耗电 |
| 频谱 | `spectrum=true` | 播放时持续 PCM 分析 + UI 刷新 |
| COVER_GLOW | 每次切歌 `blur-bg-request` / `compose-song` | 模糊背景独立 ImageLoader；单次通常 <100ms，但连切仍叠加 |
| 系统音效 | `Dolby` / `MiSound` / `EqualizerBundle` 已注册 | ROM 行为；记录环境，不单独判为 App bug |

**对照实验建议**（区分 F 的子因）：同一设备上临时 `spectrum=false` 或 `ARTWORK_GRADIENT`，再跑 82 次连切对比温度与日志。

---

## 2. 术语澄清（避免与封面特效混淆）

| 日志 / 代码名 | 含义 | 不是 |
|---------------|------|------|
| `mirror-rebuild` | `PlayerController` 从 Exo 全量扫描 `mediaItemCount` 重建 `songQueue` | 封面镜面动画 |
| `mirror-index-sync` | 仅 `syncIndexFromPlayer`，不扫全队列 | — |
| `reflection-bake` | `CoverFlowReflectionBake` 预烘焙倒影位图 | 同上 |
| `cover-load` | `CoverFlowCarouselView.bitmapFor` → Coil 解码进内存 | Compose `AsyncImage` 列表封面 |

---

## 3. 根因分析：为何「不卡了还热」

```text
[已消除] 每次切歌 O(N) mirror-rebuild + 按钮与动画并行 playSong
              ↓
[仍在] 每切 ~1 次 cover-load（200–400ms 全图 JPEG 解码）
     + 每窗 ~7 次 reflection-bake（新封面时 15–150ms）
     + 连切时 track-animator ~50 次/400ms（animAvg≈8ms，可接受但持续唤醒 CPU）
     + 常驻：HIFI 软解 + 频谱 + COVER_GLOW 重组
              ↓
        发热体感改善有限
```

### 3.1 `(1)(1)` 会话关键计数（4518 首，~8 分钟）

| 指标 | 次数 / 数值 |
|------|-------------|
| `manual next` | 82 |
| `exo-seek-existing` | 81 |
| `mirror-rebuild` | 5（冷启动 / playlist 真变） |
| `mirror-index-sync` | 151 |
| `cover-load-end` | ~150 |
| 切歌窗 `estimatedMissed=0` | 18 / 94 窗 |
| 连切窗 `estimatedMissed` | 多数 6–17 |
| `animAvg`（连点） | ~8ms |
| `cover-load max` | 200–415ms |
| `avgDraw` | ~0.1–0.3ms（绘制不是瓶颈） |

### 3.2 典型连切时间线（修复后，仍见 cover-load 晚到）

```text
22:21:06.704  Player: manual next target=7
22:21:06.704  cover-animation-start
22:21:07.111  cover-load-start          ← 动画将结束才开始
22:21:07.111  cover-animation-end
22:21:07.112  playSong / audio-start
```

队列侧已轻量；**视觉管线仍在动画窗口内赶解码**。

### 3.3 播放列表调序：为何「拖了又回到原位」（`(2)(1)`）

测试反馈：**播放列表无法调整顺序**。日志在 **22:50:06** 起出现与打开队列 sheet 拖拽时段吻合的同步风暴（此前 **22:49:44** 为正常 seek，**22:50:06** 起无切歌 `playSong`，仅有 `QueueSync`）。

```text
22:50:06.080  controller-setMediaItems durMs=190  items=4518 startIndex=389 preserve=true targetMismatch=true
22:50:06.402  controller-setMediaItems durMs=195  startIndex=390 preserve=true targetMismatch=true
22:50:08.657  controller-setMediaItems durMs=432  startIndex=392 preserve=true targetMismatch=true
…（至 22:50:44，约 30 次 setMediaItems + 等量 mirror-rebuild）
22:50:44.649  mirror-index-sync index=392          ← 风暴结束，索引仍回到 392 附近
```

**机制链**（与 §1.2 H 对应）：

1. `PlaybackQueueSheet` 拖拽 → `onMove` → `PlayerController.moveInQueue(from, to)`。
2. `moveInQueue` 更新内存 `songQueue` 后调用 `syncQueueToService(..., preserveCurrentPlayback=true)`。
3. 大队列且顺序未对齐时，**每次**走 `c.setMediaItems(4518)`（日志 `controller-setMediaItems`），耗时数百 ms。
4. Exo `onTimelineChanged` → `mirror-rebuild` 写回 `songQueue` → Compose `queue` 变更 → `LaunchedEffect(queue)` **重置** sheet 内 `items`，冲掉用户刚拖动的本地顺序。
5. `targetMismatch=true` 表示 UI 目标索引处的 `songId` 与 Exo 该槽位 `mediaId` 不一致，拖拽进行中反复出现。

**与切歌优化的对比**：§4 的 `mirror-index-sync` 分流只减轻 **seek 切歌** 路径；**`moveInQueue` 仍会触发整队列 `PLAYLIST_CHANGED`**，4518 首下不可接受。

**建议修复方向**（待实现）：

| 方向 | 说明 |
|------|------|
| Exo 增量移动 | 对齐时用 `Player.moveMediaItem(from, to)`（或 Media3 等价 API），避免 `setMediaItems(全量)` |
| 防抖 / 合并 | 连续拖拽合并为一次服务同步，或拖拽结束再提交 |
| Sheet 状态 | `LaunchedEffect(queue)` 在本地 `isDragging` 或调序 pending 期间勿覆盖 `items` |
| 大队列 UX | 调序时显示「同步中」或限制单次可移动距离（兜底） |

### 3.4 换背景 + 切歌：蓝牙断连时间线（`(2)(1)`）

设备：**漫步者 Comfo Clip**（`BT_A2DP` + `BT_SCO`）。日志**无温度字段**，43°C 仅能靠 §3.1 同类负载间接解释，此处只记 BT 断连。

**第一次（与「换背景后切歌」反馈吻合）**

| 时间 | 事件 |
|------|------|
| 22:42:15 | `bg=ARTWORK_GRADIENT` |
| 22:42:37 | 切至 `bg=THEME`（`cover-mode` invalidate） |
| 22:42:48–50 | 封面流快速连跳：631 → 632 → 633 → 634（`coverflow-drag` + `queue-select`） |
| 22:42:50.243 | `AudioRoute: devices-removed: BT_SCO` |
| 22:42:50.312 | `playSong` index=634（FLAC）+ `exo-seek-existing` |
| 22:42:50.909 | `devices-removed: BT_A2DP` |
| 22:42:57 | `devices-added: BT_SCO` / `BT_A2DP`（约 7s 后重连） |

同毫秒还有 3 路 `cover-load-start`（800²–998²）。`THEME` 下 `compose-song background=false`，无 `blur-bg-request`，但 **封面解码 + seek** 仍在主线程窗口内。

**第二次（与 §3.3 调序风暴重叠）**

| 时间 | 事件 |
|------|------|
| 22:50:06–44 | `controller-setMediaItems` ×4518 调序风暴 |
| 22:50:50.389 | `devices-removed: BT_SCO` |
| 22:50:51.186 | `devices-removed: BT_A2DP` |
| 22:51:05 | 蓝牙重连 |

**当前判断**：App 日志未见音频焦点丢失或 `becoming noisy`；更像是 **蓝牙栈 / 耳机固件** 在短时间多次路由变化 + CPU 尖峰下的断连。要定责 App 需补充：`AudioManager.OnAudioFocusChangeListener`、`onAudioDevicesRemoved` 原因码、是否在 `setMediaItems` / `moveInQueue` 期间重复 `AudioTrack` 重建。

**对照实验**：换背景后 **单次** 按钮切歌 vs **封面流连跳**；关 CoverFlow；有线耳机；记录是否仅 A2DP 设备复现。

---

## 4. 本轮已做改动（代码）

### 4.1 `mirror-rebuild` 分流

**文件**：`app/src/main/java/com/mica/music/data/PlayerController.kt`

- `onTimelineChanged`：`TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED` 或 `!isQueueMirrorAligned()` → `syncQueueMirrorFromPlayer`（日志 `mirror-rebuild`）
- 否则 → `syncQueueIndexFromPlayer`（日志 `mirror-index-sync`）

### 4.2 按钮切歌对齐滑动

| 文件 | 改动 |
|------|------|
| `PlayerController.kt` | `manualNextTarget()` / `manualPreviousTarget()`：只算目标、打 trigger，不立刻 `playSong`；`next()`/`previous()` 仍立即播放（非 CoverFlow fallback） |
| `CoverFlowCarouselNavigationBridge.kt` | **新建**：`skipToIndex` → `CoverFlowCarouselView.skipToIndexVisualFirst` |
| `CoverFlowCarouselView.kt` | `skipToIndexVisualFirst` → `playQueueIndexAfterVisualCommit` |
| `CoverFlowCarouselHost.kt` | 挂载 `navigationBridge` |
| `NowPlayingScreen.kt` | CoverFlow 阶段底部按钮走 `manual*Target` + bridge；否则 `actions.next/previous` |
| `NowPlayingCoverSection.kt` | 传入 `coverFlowNavigation` |
| `PlayerSheetHost.kt` | `NowPlayingContent` 传入 `playerController` |

**未做**：`manualNextTarget` 内封面预载；Coil 按槽位尺寸解码（见 §5）。

---

## 5. 建议的下一步

### 5.1 CoverFlow 封面加载（P0，发热主因）

1. `MicaImageLoaders.buildCoverRequest` 增加 **display-sized** `.size(w, h)`（槽位 ×2），`memoryCacheKey` 含尺寸。
2. `CoverFlowCarouselView.bitmapFor` 与预载共用同一 request 规格。
3. 扫描期可选 **thumb 档**（512px WebP）供列表 / CoverFlow 使用（中长期）。

预期：`(1)(1)` 中 `cover-load max` 从 200–400ms 降至数十 ms 量级；`estimatedMissed` 下降；发热有可感知改善。

### 5.2 `manualNextTarget` 意图预载（P1，与 5.1 叠加）

**分工**（避免 `PlayerController` 依赖 UI 层）：

| 层 | 职责 |
|----|------|
| `PlayerController` | 在 `manualNextTarget` / `manualPreviousTarget` 确认目标后，`scope.launch(IO)` 对 **目标 URI + 跳过方向邻曲** 调用 `MicaImageLoaders.preloadCover`；generation 令牌防连点堆积 |
| UI（`NowPlayingScreen` 或 `PlaybackArtworkPrefetch`） | 在 `manualNextTarget()` 返回后，用 **槽位 px** 做 sized warm；`COVER_GLOW` 时额外 `preloadBackground` |

不在 Controller 内阻塞 `ensureCoverCached`；尺寸与 blur 不进 `manualNextTarget` 方法签名。

### 5.3 倒影与特效（P1）

- 仅对 **中心轨** bake，或对小图 bake。
- 邻轨命中已有 `reflection-bake` cache 时跳过（日志已有 `hit=6/7`，新封面仍贵）。

### 5.4 验收与对照

| 场景 | 通过标准 |
|------|----------|
| 4518 首 + 原 UI 组合 + 连切 20+ | `mirror-rebuild` 仅冷启动/改队列；切歌均为 `mirror-index-sync` + `exo-seek-existing` |
| 同上 | `animAvg` < 16ms；`estimatedMissed` 连切窗中位数明显下降 |
| 同上 | `cover-load` 多数 `hit=1` 或 `max` < 50ms（需 5.1 后复验） |
| 发热 | 同操作前后设备温度或主观对比；必要时关 spectrum 做 A/B |
| 播放列表调序 | 4518 首 sheet 内拖拽 5+ 次：无 `setMediaItems` 风暴；顺序持久；或单次 `moveMediaItem` 日志 |
| 蓝牙 | 换 `THEME` / `COVER_GLOW` 后连跳切歌 10 次：无意外 `BT_A2DP` removed；若断连则导出含 `AudioRoute` 新日志 |

### 5.5 播放列表调序（P1，功能）

见 §3.3。优先 `moveMediaItem` + sheet 拖拽期间不覆盖本地列表；与 §5.1 封面优化独立，但同属大队列体验。

### 5.6 蓝牙断连（P2，待观测）

见 §3.4。先加诊断日志再改播放栈；避免在已知 `setMediaItems(4518)` 风暴路径上叠加 AudioTrack 不必要重建。

---

## 6. 与 #01 文档的状态同步

建议在 [PERFORMANCE_INVESTIGATION.md](PERFORMANCE_INVESTIGATION.md)「当前结论」表中更新：

| 原表述 | #02 更新 |
|--------|----------|
| 封面流切歌「发热修复待大曲库复验」 | 队列 + 按钮时序 **已复验通过**；**发热仍待 cover-load / 视觉管线优化** |
| — | 新增子文档：**本文 #02** |

---

## 7. 相关文件索引

| 路径 | 作用 |
|------|------|
| `data/PlayerController.kt` | `mirror-*` 分流、`manualNextTarget`、轻量 seek 切歌 |
| `imaging/MicaImageLoaders.kt` | 封面/背景 Coil；待加 sized preload |
| `ui/screens/player/view/CoverFlowCarouselView.kt` | `bitmapFor` / `cover-load`、`preloadWindow` |
| `ui/screens/player/view/CoverFlowReflectionBake.kt` | `reflection-bake` |
| `ui/screens/NowPlayingScreen.kt` | 按钮 visual-first 接线 |
| `ui/components/PlaybackQueueSheet.kt` | 队列 sheet 拖拽；`LaunchedEffect(queue)` 与 `moveInQueue` 交互 |
| `util/TrackSwitchPerformance.kt` | `TrackPerf` / `coverflow-diag` 导出 |
| `util/BluetoothAudioDiagnostics.kt` | `AudioRoute` 设备增删日志；待扩展 focus / 断连原因 |

---

## 8. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-06-20 | 初版：整合 `(1)` / `(3)` / `(1)(1)` 三轮日志、本轮代码改动与未决项 |
| 2026-06-20 | 增补 `(2)(1)`：§1.2 H/I 播放列表调序失败、换背景切歌蓝牙断连；§3.3–3.4、§5.5–5.6 |
