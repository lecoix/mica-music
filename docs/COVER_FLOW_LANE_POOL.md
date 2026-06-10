# 封面流 Lane 池治本方案

> 状态：**已实现**（Lane 池 + 滑动手势；见 [`CoverGestureCoordinator.kt`](../app/src/main/java/com/mica/music/ui/screens/player/CoverGestureCoordinator.kt)）  
> 最后更新：2026-06  
> 关联：[封面流产品设计](COVER_FLOW.md) · [播放页契约](PLAYER_PAGE_CONTRACT.md) · [切歌闪帧记录](TODO.md#封面带立体封面切歌闪帧--治本当前为缓解) · 实现入口 [`NowPlayingCoverSection.kt`](../app/src/main/java/com/mica/music/ui/screens/NowPlayingCoverSection.kt)

---

## 1. 背景与目标

### 1.1 问题

平行封面带 / 复古立体封面在切歌时，Compose 会按 `key(song.id)` **销毁并重建**边缘 slot。重建当帧 `AsyncImage` 可能尚未绘出位图，在**封面模糊 / 封面渐变**背景下表现为闪上一张或露底色。

### 1.2 已完成（与本方案的关系）

| 层级 | 内容 | 作用 |
|------|------|------|
| **缓解** | `centerAnchorIndex` 锚定、`alpha=0` 隐远端、`memoryCacheKey`、`decodedCoverUris` | 滑动中途不成批重建；重建当帧尽量命中缓存 |
| **L1 基础设施** | 双 `ImageLoader`、背景跟 `displayedCoverSong`、`ensureCoverCached` / `ensureBackgroundCached` | 减少缓存争用与背景抢先换图 |
| **标准主题治本** | `StandardDualSlotCover`（A/B 固定 key） | **仅标准主题**：可见槽展示期间不换 URI |

### 1.3 本方案目标（L2 治本）

**封面流**仍使用 `CoverFlowStage`，将 `key(song.id)` 改为 **Lane 固定 key**：

- 切歌时 **0 次** 因 `song.id` 变化导致的 slot dispose/compose（滑动动画帧同理）。
- 可见 slot 在居中展示期间 **URI 不变**；新曲写入 **当前不可见** lane 后再翻转变换 / 透明度。
- 与标准 A/B 同一思路，扩展到 **7 lane ×（主图 + 倒影）**。

**不在本方案范围**：标准主题（已由 A/B 覆盖）、播放出声、队列 API、MediaService。

---

## 2. 现状架构（实现前必读）

### 2.1 渲染窗口

```text
windowRadius = 3
start = centerAnchorIndex - 3
end   = centerAnchorIndex + 3
→ 最多 7 个队列下标
```

- `centerAnchorIndex` = `displayedCoverIndex`（真正切歌后的稳定整数，非动画浮点中心）。
- `virtualCenterIndex` = 滑动动画中的浮点中心，只驱动 `graphicsLayer`（位移 / 缩放 / 旋转 / alpha）。
- 远端 slot：`alpha = 0`，**不** `continue` 剔除（保持 keyed 子项序列长度稳定——缓解方案，Lane 池将替换 key 策略）。

### 2.2 每个 slot 的子树

```text
key(song.id) {                    ← 待改为 key("cover_lane_$laneOffset")
  Box(graphicsLayer…) {
    ParallelCoverWithReflection
      ├── SongCover（主图）
      └── SongCover（倒影，scaleY = -1）
  }
}
```

复古立体模式额外有 `rotationY`、`zIndex`、`maxDistance` 差异（见 `coverFlowSlot*` 系列函数）。

### 2.3 与 `StableCoverState` 的衔接

- 封面流模式：`waitForArtwork = false`，`displayedSong` 随 `activeSong` **立即**更新（无标准主题式 pending）。
- 标准主题：`waitForArtwork = true` + `StableCoverPreloader` + A/B 双槽。
- Lane 池实现时，封面流 **可保留** `waitForArtwork = false`；隐藏 lane 换 URI 前应用 `MicaImageLoaders.ensureCoverCached` 预载（与 A/B 一致）。

---

## 3. 方案选型

### 3.1 推荐：Lane 池（固定 key + 数据绑定）

| 概念 | 说明 |
|------|------|
| **Lane** | 相对中心的固定偏移 `laneOffset ∈ {-3,-2,-1,0,1,2,3}` |
| **Compose key** | `key("cover_lane_$laneOffset")`，与 `song.id` 解耦 |
| **队列下标** | `queueIndex = centerAnchorIndex + laneOffset` |
| **绑定歌曲** | `song = queue.getOrNull(queueIndex)` |
| **换歌** | 仅更新 **不可见** lane 的 `song` 绑定；可见 lane 在展示期间 URI 不变 |

### 3.2 备选：movableContentOf

| | Lane 池 | movableContent |
|--|---------|----------------|
| 实现复杂度 | 中 | 中高 |
| 调试 | 直观 | 难 |
| 跳多首 | 隐藏 lane 连续换数据 + 预载 | 子树可「搬家」，状态保留更好 |
| 复古 3D + graphicsLayer | 兼容好 | 需真机验证 move 后 layer 状态 |
| 内存 | 固定 7 lane | 略高（movable 元数据） |

**结论**：优先 Lane 池；仅当 Lane + 预载仍有个别机型闪帧时，再评估 movableContent。

---

## 4. 详细设计

### 4.1 状态模型

```kotlin
// 概念结构（非最终实现名）
data class CoverLaneState(
    val laneOffset: Int,           // -3 .. 3，终身不变
    val boundQueueIndex: Int?,     // 当前绑定的队列下标；越界为 null
    val boundSong: Song?,          // queue.getOrNull(boundQueueIndex)
)
```

**生命周期**：

- `CoverFlowStage` 首次进入（`stageActive == true`）时初始化 7 个 lane 的首次绑定。
- 播放页封面流活跃期间，**lane 的 Compose key 不销毁**。
- 退出封面流（歌词聚焦 / 下半屏沉浸 / `coverFlowProgress → 0`）时整段 `CoverFlowStage` 子树销毁——与现网一致，Lane 不跨模式持久化。

### 4.2 切歌时 lane 更新规则

设 `centerAnchorIndex` 从 `oldCenter` 变为 `newCenter`，`delta = newCenter - oldCenter`。

| delta | 行为 |
|-------|------|
| `+1` / `-1` | 窗口平移 1：仅 **进入窗口的一侧** 远端 lane 需要绑定新歌；从窗口离开的一侧 lane 可保留旧绑定（`alpha=0`）或惰性换绑 |
| `>1` 或 `<-1` | 跳曲：多个 lane 需换绑；**先**对将变为可见的 lane 做 `ensureCoverCached`，**再**更新 `centerAnchorIndex` 与绑定 |

**核心约束**（与标准 A/B 相同）：

> **禁止在 `alpha > 0` 的 lane 上修改 `albumArtUri`。**

推荐流程：

```text
1. 计算 newCenter 下各 lane 的目标 queueIndex / song
2. 对「将变为可见」或「即将翻到中心」的 lane，若 URI 变化 → ensureCoverCached(uri)
3. 仅对 alpha≈0 的 lane 写入新 song
4. **动画结束后再**更新 `centerAnchorIndex`（见 §4.2.1；滑动/按钮切歌均适用）
5. `virtualCenterIndex` 动画期间只动 `graphicsLayer`

#### 4.2.1 滑动切歌跳变：根因与正确时序（2026-06 已修）

播放页重写后，平行封面带 / 复古立体封面在**松手切歌**或**刚开始滑**时曾出现「先跳一段再移动」。与 [TODO 闪帧条目](TODO.md#封面带立体封面切歌闪帧--治本当前为缓解) 的 Compose 重建问题不同，本次是 **位移状态机 + 变换枢轴** 的连续性问题。

**位移模型（实现约定）**

```text
virtualCenterIndex = centerAnchorIndex + laneFraction
offset(queueIndex)   = queueIndex - virtualCenterIndex
translationX         = CoverFlowMath.slotTranslation(offset, …)
```

- `centerAnchorIndex`：Lane 窗口绑定的整数中心（`queueIndex = centerAnchor + laneOffset`）。
- `laneFraction`：相对锚点的浮点偏移；拖动、切歌补间都只改这一项（勿拆成 `dragFraction` + `animOffset` 分步清零）。
- 拖动增量除以 `screenWidth × 0.92`，与 `slotTranslation` 单格步进对齐，保证跟手比例一致。

**根因 1 — 动画中途更新 `centerAnchor`（松手跳变的主因）**

错误做法： `currentIndex` 一变就立刻 `centerAnchor = currentIndex`，再用负的 `laneFraction` 补偿 `virtualCenter`。

Lane 槽位按 `laneOffset` 固定 key（`cover_lane_0` 等），**中心槽**是用户主视觉。提前换锚点时，**同一帧**内中心槽会发生：

| 时刻 | lane 0 绑定的歌 | offset | 视觉效果 |
|------|-----------------|--------|----------|
| 松手后、换锚点前 | 当前曲 N | ≈ -0.4（略偏左） | 正常 |
| 换锚点后 | 下一曲 N+1 | ≈ +0.6（偏右） | **中心槽内容 + 位移同时突变** |

虽然 N+1 在全局坐标上位置连续，但 **lane 0  composable 换歌且 translation 跳变**，表现为「往滑来/滑去方向先跳一段」。

**正确做法**：`currentIndex` 变化后，**整段补间动画期间保持 `centerAnchor` 为旧值**；只把 `laneFraction` 从拖动值动画到 `signedDelta`（下一首 `+1`，上一首 `-1`）。当 `virtualCenter` 已到达整数目标后，再在同一帧执行 `centerAnchor = currentIndex; laneFraction = 0`（此时各曲目的 `offset` 与换锚点前一致，无视觉跳变）。

```text
拖动中:     anchor=N, laneFraction=0.4  → virtualCenter=N+0.4
松手切歌:   anchor=N, laneFraction: 0.4 → 1.0  （lane 绑定不变，只动 graphicsLayer）
动画结束:   anchor=N+1, laneFraction=0   → virtualCenter=N+1
```

**根因 2 — `transformOrigin` 随 `offset` 阈值跳变（刚开始滑时闪一下）**

若用 `offset` 是否越过 ±0.01 切换 `TransformOrigin`（`Center` ↔ 左/右边缘），中心封面在**第一下拖动**跨阈值时旋转枢轴突变，Retro 3D / Pause Fold 都会闪。

**正确做法**：按 **固定 `laneOffset`** 定枢轴——`laneOffset < 0` → 右缘，`> 0` → 左缘，`== 0` → `Center`；与拖动产生的瞬时 `offset` 解耦。

**根因 3 — 拖动方向与 `virtualCenter` 符号反了**

`detectHorizontalDragGestures` 左滑时 `dragAmount` 为负。应使用 `laneFraction -= delta`（`delta = dragAmount / (screenWidth × 0.92)`），使左滑增大 `virtualCenter`、封面跟手向左。

**根因 4 — `pointerInput` 在切歌时重建手势**

勿把 `gestureState.handlers` 放进 `pointerInput` 的 key；`currentIndex` 变化会重建 detector，松手帧可能多触发事件。key 仅用 `gesturesEnabled`；索引用 `rememberUpdatedState` 读最新值。

**勿再引入的写法**

- 动画开始前 `laneFraction = 0` 再另起 `animOffset`（中间帧 `virtualCenter` 断裂）。
- 动画未结束就 `centerAnchor = to`（中心槽换歌跳变）。
- 用 `offset` 阈值切换 `transformOrigin`（拖动起始闪动）。

实现与单测：[`CoverGestureCoordinator.kt`](../app/src/main/java/com/mica/music/ui/screens/player/CoverGestureCoordinator.kt)、[`CoverGestureCoordinatorTest.kt`](../app/src/test/java/com/mica/music/ui/screens/player/CoverGestureCoordinatorTest.kt)。

### 4.3 渲染循环（伪代码）

```kotlin
val windowRadius = 3
for (laneOffset in -windowRadius..windowRadius) {
    val queueIndex = centerAnchorIndex + laneOffset
    val song = queue.getOrNull(queueIndex)
    key("cover_lane_$laneOffset") {
        val offset = queueIndex - virtualCenterIndex
        val distance = abs(offset)
        val withinView = distance <= maxDistance
        val slotAlpha = if (withinView) coverFlowSlotAlpha(...) else 0f

        if (song == null) {
            // 队列边界：见 §4.5
        } else {
            CoverLaneSlot(
                song = song,
                queueIndex = queueIndex,
                graphicsLayer = { alpha = slotAlpha; translationX = …; rotationY = … },
                onClick = { onPlayQueueIndex(queueIndex) },
            )
        }
    }
}
```

`CoverLaneSlot` 内部保持现有 `ParallelCoverWithReflection` 结构；**不要**在 slot 内再 `key(song.id)`。

### 4.4 与 `ParallelCoverWithReflection` 的配合

- 主图 / 倒影各一个 `SongCover`；`stableMemoryCacheKey` 默认 `albumArtUri`（已实现）。
- `publishHoldoverOnSuccess`：仅 `song.id == activeSongId`（中心曲）为 true。
- `onDisplayedCoverDrawn`：仅中心 lane（`laneOffset == 0` 且 `distance < 0.08`）回调。
- 隐藏 lane：`publishHoldoverOnSuccess = false`，避免污染全局 holdover。

### 4.5 队列边界

| 场景 | 建议 |
|------|------|
| `queueIndex < 0` 或 `≥ queue.size` | lane 保留 Composable，**不绑歌**；`alpha=0`；可选显示透明占位 |
| 队列仅 1 首 | 仅 `laneOffset=0` 有歌；两侧 lane 空占位 |
| 播放中删歌 / 重排 | `LaunchedEffect(queue, centerAnchorIndex)` 重算绑定；**不**改 lane key |

### 4.6 预载策略

与 [`MicaImageLoaders`](../app/src/main/java/com/mica/music/imaging/MicaImageLoaders.kt) 对齐：

```kotlin
// centerAnchorIndex 变化前或同时
for (laneOffset in -3..3) {
    val uri = queue.getOrNull(centerAnchorIndex + laneOffset)?.albumArtUri ?: continue
    MicaImageLoaders.preloadCover(context, uri)
}
// 若 lowerBackground == COVER_GLOW
MicaImageLoaders.preloadBackground(context, uri)
```

跳曲时优先预载 **新中心 ±1**，再预载远端 lane。

封面流 **不** 使用 `StableCoverPreloader`（仅 `coverSwitching` 时激活）；预载放在 `CoverFlowStage` 或 `LaunchedEffect(centerAnchorIndex, queue)` 内。

---

## 5. 性能与资源

### 5.1 开销评估（相对现网）

| 维度 | 现网（已缓解） | Lane 池 |
|------|----------------|---------|
| 切歌 CPU | 边缘 1× dispose + compose（跳曲更多） | **0** 次 slot 拆建 |
| 滑动帧 | 每帧 `graphicsLayer` 重算 | 相同 + 少量普通 recomposition |
| 内存（封面流活跃） | ≈7×2 路 AsyncImage | **固定** 7×2 路，略增 lane 状态 |
| GPU | 7 层 graphicsLayer（含 alpha=0） | 相同；alpha=0 合成成本可忽略 |
| 解码 | 重建当帧易 miss | 隐藏 lane 换 URI + 预载，miss 率更低 |

**结论**：性能开销 **可控**；切歌路径通常 **更优**，内存 **持平或略增**，不是从 1 张封面扩到 7 张（窗口本就 7 slot）。

### 5.2 不影响的部分

- 音频播放、seek、队列 CRUD API  
- 迷你播放器、列表 `SongCover`（仍走 cover `ImageLoader`）  
- 标准主题 `StandardDualSlotCover`  
- 主题色 / 封面渐变 / 封面模糊 的背景管线（已隔离缓存）

---

## 6. 影响面矩阵

| 维度 | 平行封面带 | 复古立体 | 标准主题 | 主题色 | 封面渐变 | 封面模糊 |
|------|:----------:|:--------:|:--------:|:------:|:--------:|:--------:|
| Lane 池 | ● 直接改 | ● 直接改 | — A/B 已覆盖 | ○ | ○ | ◎ 主要受益 |
| 点侧封面切歌 | 需按 `queueIndex` 更新点击 | 同左 | — | — | — | — |
| 随机跳曲 | 多 lane 换绑 + 预载 | 同左 | — | — | — | — |
| 歌词聚焦退出 | 子树销毁，与现网同 | 同左 | — | — | — | — |

图例：● 必改 ◎ 强受益 ○ 间接受益 — 无关

---

## 7. 实现步骤（建议 PR 顺序）

### Phase 0：诊断基线（可选但推荐）

- [ ] 恢复或新增 `CoverFlowDiag` 日志（`laneOffset`、`song.id`、`disposed`/`composed` 时间戳）。
- [ ] 在 **未改 Lane** 的分支上录一条「连切 20 首」基线 log（若仍保留闪帧）。

### Phase 1：Lane key 替换

- [ ] `CoverFlowStage`：`key(song.id)` → `key("cover_lane_$laneOffset")`。
- [ ] 循环变量从「绝对 `index`」改为「`laneOffset` + 计算 `queueIndex`」。
- [ ] 点击 / 长按仍传 `queueIndex`。
- [ ] **暂不改**换绑策略，先验证 key 稳定后 dispose 是否消失。

### Phase 2：隐藏 lane 换绑 + 预载

- [ ] 引入 `CoverLaneBindings` 状态（或等价 `remember` 结构）。
- [ ] `centerAnchorIndex` 变化时：仅更新不可见 lane 的 `song`；可见 lane 延迟换绑到下一次该 lane 隐藏后。
- [ ] 换绑前 `ensureCoverCached`（`COVER_GLOW` 时加 `ensureBackgroundCached`）。

### Phase 3：边界与压力场景

- [ ] 队列 1 首、2 首、删当前曲、拖拽排序。
- [ ] `indexDelta > 1` 随机模式连跳。
- [ ] 减少动态效果 `0ms` 直切。

### Phase 4：清理与文档

- [ ] 验收通过后评估是否删减冗余缓解代码（**保留**双 ImageLoader、背景同步、A/B；`decodedCoverUris` 可降级为兜底）。
- [ ] 更新 [`TODO.md`](TODO.md) 封面流治本条目为已完成。
- [ ] 移除 `CoverFlowDiag` 或改为 `debug` 开关。

---

## 8. 验收标准

### 8.1 日志

- 任意 **平行 / 复古** × **三种背景**，连续上一首 / 下一首 / 点侧封面切歌：
  - logcat **无**同毫秒成批 `SLOT disposed` + `SLOT composed`（lane key 不应因切歌触发）。
- 允许：进入 / 退出封面流模式、进出播放页时整段 `CoverFlowStage` 挂载 / 卸载。

### 8.2 视觉

- 封面模糊 / 封面渐变：切歌与滑动全程 **无** 上一张封面闪入、**无** 黑底 / 色块闪一下。
- 主题色：无回归。
- 复古 3D：透视 / 倒影 / z 序无跳变。

### 8.3 交互

- 侧封面点击切歌正确；中心长按菜单正常。
- 歌词聚焦、下半屏沉浸进出封面流动画正常。
- 与标准 A/B 切换封面主题（设置）无崩溃、无封面卡死。

### 8.4 性能（真机抽检）

- 中低端机：封面流连续切歌 30 首，无明显卡顿、无 OOM。
- 内存：封面流活跃时与改前同量级（±1 张封面解码误差可接受）。

---

## 9. 风险与对策

| 风险 | 对策 |
|------|------|
| 可见 lane 误换 URI | Code review 强制检查：换绑函数内断言 `slotAlpha < 0.01f` |
| 跳曲预载跟不上 | `indexDelta > 1` 时批量 `ensureCoverCached`；动画 `snapTo(1f)` 前完成 |
| 队列重排后索引错乱 | 单一入口 `rebindLanes(queue, centerAnchorIndex)`；单测或日志断言 |
| 倒影 lane 双图换 URI | 主图与倒影同 URI，同时换；隐藏 lane 一起换 |
| 与 A/B 逻辑重复 | 抽取 `CoverUriGate`（预载 + 可见性断言）到 `imaging` 或 `ui/components` |

---

## 10. 文件 touch 清单

| 文件 | 改动 |
|------|------|
| [`NowPlayingCoverSection.kt`](../app/src/main/java/com/mica/music/ui/screens/NowPlayingCoverSection.kt) | `CoverFlowStage`、可选 `CoverLaneSlot` / `CoverLaneBindings` |
| [`SongCover.kt`](../app/src/main/java/com/mica/music/ui/components/SongCover.kt) | 通常 **不改**；已支持 `lastPaintedUri`、默认 `memoryCacheKey` |
| [`MicaImageLoaders.kt`](../app/src/main/java/com/mica/music/imaging/MicaImageLoaders.kt) | 可选：`preloadCoverFlowWindow(queue, center, radius)` 辅助函数 |
| [`TODO.md`](TODO.md) | 完成后勾选治本项，链到本文档 |
| [`COVER_FLOW.md`](COVER_FLOW.md) | 可选：「实现说明」一节加链接 |

**不应修改**：`StableCoverState` 标准路径、`StandardDualSlotCover`、`PlayerController`、队列存储。

---

## 11. 与标准 A/B 的对照

| | 标准 `StandardDualSlotCover` | 封面流 Lane 池 |
|--|------------------------------|----------------|
| Slot 数 | 2 | 7 |
| Key | `standard_cover_slot_a/b` | `cover_lane_-3` … `cover_lane_3` |
| 可见性 | `frontIsA` 翻转 | `graphicsLayer.alpha` + 动画位移 |
| 换 URI 时机 | 隐藏槽 | 隐藏 lane（`alpha≈0`） |
| 预载 gate | `StableCoverPreloader` | `CoverFlowStage` 内 `LaunchedEffect` |
| waitForArtwork | `true` | `false`（保持现网） |

二者可共用 **`ensureCoverCached` / `ensureBackgroundCached`**，不必共用 Composable 实现。

---

## 12. 参考资料

- Compose `key()` 与 slot reuse：[Compose 稳定性](https://developer.android.com/develop/ui/compose/performance/stability)
- 项目内复盘：[共享元素动画笔记](SHARED_ELEMENT_ANIMATION_NOTES.md)（状态模型「冻结 / 可见 / 隐藏」可类比 lane）
- 动效时长：[`MOTION.md`](MOTION.md) 中封面流切歌 `Medium 320ms` / 复古 `Long 400ms`

---

## 13. 开放问题（实现时决定）

1. **空 lane 占位**：完全透明 vs 保留上一首残影（建议透明，避免误触）。
2. **lane 绑定状态放哪**：`remember` 在 `CoverFlowStage` 内 vs 提升到 `StableCoverState`（建议前者，仅封面流存活）。
3. **是否在 Phase 1 后即删 `key(song.id)` 相关缓解注释**：保留至 Phase 4 验收通过。
4. **movableContent 触发条件**：Lane + 预载验收仍闪的机型列表；无则不做。

---

*实现完成后，在本文件顶部将状态改为 **已实现**，并补充「实际改动摘要」与「与设计的差异」三节。*
