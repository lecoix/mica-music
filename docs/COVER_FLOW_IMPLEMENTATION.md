# 播放页封面行为手册（产品 + 实现）

> **状态**：2026-07 现网热路径  
> **读者**：改播放页封面交互、间距、切歌动画前必读。  
> **范围**：**平行 / 复古封面流**（七轨 `CoverFlowRails`）、**拍立得回忆**（`PhotoStack` 叠放转场）。粒子封面见 [`PARTICLE_COVER_OPENGL_MIGRATION.md`](PARTICLE_COVER_OPENGL_MIGRATION.md)。  
> 播放页边界见 [`PLAYER_PAGE_CONTRACT.md`](PLAYER_PAGE_CONTRACT.md)；动效 token 见 [`MOTION.md`](MOTION.md)。

---

## 0. 产品设计

### 0.1 定位

设置 → **播放页封面行为**（`PlayerCoverFlowMode`）除「标准大封面」外，有三类 **View 岛**特殊主题：

| 主题 | 枚举 | 实现族 |
|------|------|--------|
| 平行封面带 | `PAUSE_FOLD` | **封面流七轨**（§1–§12） |
| 复古立体封面 | `RETRO_3D` | **封面流七轨**（§1–§12） |
| 拍立得回忆 | `PHOTO_STACK` | **拍立得叠放栈**（§13） |

共性：

- 播放 / 暂停：不改变封面区布局形态。
- 歌词聚焦：临时退出特殊主题层，回到普通播放页布局过渡（拍立得 **`normalLayerVisible=false`**，且**不支持**下半屏沉浸）。
- 特殊主题下播放页强制**裁切填充**，忽略设置里「原样比例」。

封面流（平行 / 复古）：启用后封面区常驻**横向七轨**队列带。  
拍立得：启用后封面区为**三张叠放**的竖版卡片（当前 + 后两首），切歌时整组转场，**不是** `railOffset` 模型。

### 0.2 设置（已实现）

设置 → **播放页封面行为**（`PlayerCoverFlowMode` / `AppUiSettings.playerCoverFlowMode`）：

| 选项 | 枚举 | 说明 |
|------|------|------|
| 标准 | `STANDARD` | 普通大封面；Compose 横向轻扫切歌 |
| 粒子封面 | `PARTICLE_COVER` | 边缘粒子化 + 切歌分解（**GLES** View 岛，`ParticleCoverHost`）；**不在本文** — 见 [`PARTICLE_COVER_OPENGL_MIGRATION.md`](PARTICLE_COVER_OPENGL_MIGRATION.md) §0 |
| 平行封面带 | `PAUSE_FOLD` | 同尺寸并排，暂停不折叠 |
| 复古立体封面 | `RETRO_3D` | 两侧透视倾斜 + 倒影 |
| 拍立得回忆 | `PHOTO_STACK` | 三张拍立得叠放 + 切歌抽换动画 |

与「播放页背景」（主题色 / 封面渐变 / 封面模糊等）**并列、可任意组合**。

### 0.3 交互

**标准封面**（Compose `CoverGestureCoordinator`）：

- 横向轻扫：超过约 **13%** 屏宽切歌（提交阈值 `0.13125`）。

**封面流**（平行 / 复古 · `CoverFlowCarouselView`）：

- 点击侧槽封面 → 切到对应曲目。
- 横向拖动 → 跟手；松手超过约 **26%** 的单轨步进切歌，否则回弹（提交阈值 `0.2625`；见 §4.1）。
- 特殊主题遵循「封面底边进度」开关：关闭时保留下方普通进度条与普通频谱条；开启时将进度条与已启用的频谱条移到中心专辑图底边，并隐藏普通进度区域。底边覆盖层由 Compose 按中心槽缩放对齐，封面/倒影/切歌动画仍由 View 绘制。

**拍立得回忆**（`PhotoStackTransitionView` · 见 §13）：

- 横向轻扫最前一张：跟手旋转/平移；松手 **\|fraction\| > 11%**（`SwipeCommitFraction`）切上一首 / 下一首，否则回弹（视觉钳制 ±15%）。
- 点击最前一张卡片**底部波形/进度带** → 拖动 seek（`TouchMode.Seek`）；进度嵌在前卡底部，非下半屏 chrome。
- 长按最前一张 → `onCoverLongPress`。
- 转场动画进行中 **禁用**触摸（`activeTransitionCards` 非空）。
- **不支持**下半屏沉浸（`supportsImmersiveLower = false`）。

**共用**：

- 播放 / 暂停、特殊主题内布局：**不**随播放状态切换。
- 系统「减少动态效果」→ `0ms` 瞬切（`MicaMotion` / `setMotionEnabled(false)`）。

### 0.4 动效（`MicaMotion`）

| 变化 | Token |
|------|-------|
| 进入 / 退出封面流布局 | Long `400ms` |
| 切歌换位（平行） | Medium `320ms` |
| 复古立体切歌 | Long `400ms`；沿连续中心索引，避免角度阶跃 |
| 拍立得切歌转场 | **`620ms`**（`PhotoStackPullAwayDurationMs`）；`DecelerateInterpolator(1.65f)` |
| 减少动态效果 | `0ms` |

播放页通用约束：**先冻结布局 → 再算目标 → 单一 progress 驱动 lerp**（见 [`MOTION.md`](MOTION.md) §8.5）。

### 0.5 视觉参数

下表为**设计意图**；像素级间距 / 缩放以 [`CoverFlowRails.kt`](../app/src/main/java/com/mica/music/ui/screens/player/CoverFlowRails.kt) 与本文 §7 为准。

**平行封面带**

| 元素 | 设计参考 |
|------|----------|
| 当前封面 | 约 `0.76` 缩放，直角、不旋转 |
| 左右封面 | 同尺寸并排；外侧可降低 alpha |
| 倒影 | 位图底部约 **28%** 条带翻转渐隐；非整屏玻璃舞台 |
| 背景 | 复用现有播放页背景 |

**复古立体**

| 元素 | 设计参考 |
|------|----------|
| 当前封面 | 正面居中，较强主视觉 |
| 两侧 | 透视 `rotationY`；位移/缩放见 `CoverFlowRails` |
| 倒影 | 与封面同一变换栈（§5.3） |

底边进度与频谱启用时，其轻量倒影与中心专辑图共用 `ReflectionHeightFraction`、`ReflectionAlpha` 和倒影间隙，仅向下绘制矩形/柱条渐隐，不创建位图、模糊或额外离屏图层。

**拍立得回忆**

| 元素 | 设计参考 / 现网 |
|------|----------------|
| 卡片比例 | 屏宽 × **80%**（`PhotoStackScreenFraction`），高宽比 **0.78** |
| 稳态栈 | 前 / 中 / 后：`queue[i]`、`queue[i+1]`、`queue[i+2]` |
| 稳态位姿 | 前卡 `rotationZ ≈ -1.4°`；中卡 offset `(14,10)dp` + `3.2°` + scale `0.95`；后卡 `(28,22)dp` + `6.4°` + scale `0.90` |
| 纸框 | 米白渐变 + 1dp 描边；封面 inset 约 **5.5%** 顶 / **3.8%** 左右 |
| 阴影 | 预烘焙 `BlurMaskFilter` 圆角阴影（全局直角规则的**有意例外**） |
| 前卡底带 | 86 条波形 + 进度（`waveformHeight = 24dp`） |
| 垂直节奏 | 顶/底留白约屏高 **10%**（`PhotoStackEdgeFraction`） |

### 0.6 范围

**已做**：播放页封面区；平行 / 复古七轨 ±3 + 拖动 + 按钮切歌；拍立得三卡栈 + 轻扫/seek；各主题 × 各播放页背景。

**未做**：专辑浏览页 Cover Flow；强拟物舞台；拍立得 JVM 单测（封面流有 `CoverFlowRailsTest`）。

---

## 1. 封面流一句话结论（平行 / 复古）

> **以下 §1–§12 仅适用于 `PAUSE_FOLD` / `RETRO_3D`。** 拍立得见 §13。

**七张图、七条固定轨道（lane ∈ [-3,3]），唯一动画量是 `stripFraction`，所有视觉量只查 `railOffset = laneIndex - stripFraction`。**

切歌末帧提交时：`logicalCenter` 换歌、`stripFraction` 归零、各 lane 歌曲索引平移——**每张图在屏幕上的 `railOffset` 必须连续不变**。违反这条就会出现拖了两周的跳变、闪帧、缩放突变。

---

## 2. 封面流架构分层（平行 / 复古）

| 层 | 文件 | 职责 |
|----|------|------|
| Compose 外壳 | [`NowPlayingCoverSection.kt`](../app/src/main/java/com/mica/music/ui/screens/NowPlayingCoverSection.kt) | 布局高度、`AndroidView` 挂载、倒影溢出绘制、歌词遮罩点击 |
| View 宿主 | [`CoverFlowCarouselHost.kt`](../app/src/main/java/com/mica/music/ui/screens/player/view/CoverFlowCarouselHost.kt) | `AndroidView` factory/update、预载位图、把 `currentIndex` 交给 View |
| 绘制核心 | [`CoverFlowCarouselView.kt`](../app/src/main/java/com/mica/music/ui/screens/player/view/CoverFlowCarouselView.kt) | 手势、`ValueAnimator`、`onDraw` 单遍绘制 |
| **轨道数学（唯一真相源）** | [`CoverFlowRails.kt`](../app/src/main/java/com/mica/music/ui/screens/player/CoverFlowRails.kt) | `railOffset` → 位移 / 缩放 / 旋转 / 透明度 / 枢轴 / z 序 |
| 共享公式 | [`CoverFlowMath.kt`](../app/src/main/java/com/mica/music/ui/screens/player/CoverFlowMath.kt) | 中心缩放、基础 slot 公式（Rails 复用） |
| 位图 | [`CoverFlowBitmaps.kt`](../app/src/main/java/com/mica/music/ui/screens/player/view/CoverFlowBitmaps.kt) | 从 Coil 内存缓存取 `Bitmap`，避免 Compose 每槽一张图 |
| 布局引擎 | [`PlayerPageLayoutEngine.kt`](../app/src/main/java/com/mica/music/ui/screens/player/PlayerPageLayoutEngine.kt) | `cover.blockHeight`、`zoneStop`；**不含倒影额外高度** |
| 标准封面轻扫 | [`CoverGestureCoordinator.kt`](../app/src/main/java/com/mica/music/ui/screens/player/CoverGestureCoordinator.kt) | 仅标准主题横向轻扫；封面流手势在 View 内 |
| 单测 | [`CoverFlowRailsTest.kt`](../app/src/test/java/com/mica/music/ui/screens/player/CoverFlowRailsTest.kt) | 末帧 `railOffset` 连续性、切歌起点钳制 |

### 为何从 Compose 迁出（简史）

早期用 Compose `CoverFlowStage`（每槽 `SongCover` + 离屏倒影）+ Lane 环形池 + `CoverGestureCoordinator` 动画。问题在于：动画帧触发整树重组、`key(song.id)` 切歌重建、位移/缩放双轨不同步——表现为闪帧与跳变。现网已 **删除** 上述 Compose 热路径，改为单 View 绘制；旧方案文档已清理，**勿再引入第二套轨道状态机**。

通用判据与决策清单见 [`MOTION.md`](MOTION.md) **§七**（Compose 与 AndroidView 岛分工）。

---

## 3. 核心不变式（背下来）

### 3.1 变量

```text
laneIndex      ∈ {-3, -2, -1, 0, 1, 2, 3}   // 固定槽位，不随切歌改 key
logicalCenter  : Int                         // 中心歌曲在 queue 中的下标
stripFraction  : Float                       // 唯一动画量；0=居中，+1=刚切完下一首的视觉终点
railOffset     = laneIndex - stripFraction   // 轨道坐标（浮点）
virtualCenter  = logicalCenter + stripFraction
```

### 3.2 视觉量（全部只依赖 `railOffset`）

```kotlin
tx          = CoverFlowRails.translationPx(railOffset, coverWidthPx, mode)
drawScale   = CoverFlowRails.drawScale(railOffset, mode, foldProgress)
rotationY   = CoverFlowRails.rotationY(railOffset, mode)
alpha       = CoverFlowRails.alpha(railOffset, foldProgress, mode)
scalePivotX = CoverFlowRails.pivotX(railOffset, slotWidthPx, mode)   // 复古：平滑插值，禁止按整数 lane 硬切
zIndex      = CoverFlowRails.zIndex(railOffset, mode)
song        = queue[logicalCenter + laneIndex]
```

**禁止**：位移用 `stripFraction`、缩放用整数 `laneIndex`、旋转用 `transformOrigin` 阶跃——这就是跳变根源。

### 3.3 相邻切歌末帧提交

动画终点 `stripFraction = ±1` 时，执行 `commitTrackIndex`：

```text
logicalCenter ← newIndex
stripFraction ← 0
// 各 lane 歌曲：queue[logicalCenter + laneIndex] 等价于提交前 queue[(logicalCenter-Δ) + (laneIndex-Δ)]
```

**断言**（单测已覆盖）：对任意 `lane`，提交前后 `CoverFlowRails.railOffset(lane, strip)` 与 `CoverFlowRails.railOffset(lane-Δ, 0)` 相等。

---

## 4. 动画时序

### 4.1 拖动（View 内完成，不经 Compose）

1. `ACTION_DOWN` → `cancelAnimators()`，跟手改 `stripFraction`
2. 步进：`stripFraction -= deltaPx / (coverWidthPx * laneStepFraction())`
3. `ACTION_UP`：`stripFraction > 0.2625` → `onPlayQueueIndex`；`< -0.2625` → 上一首；否则 `animateStripTo(0)`

`laneStepFraction()`：平行用 `PauseFoldStep`，复古用 `RetroFirstStep`（与位移首格一致）。

### 4.2 外部切歌（播放器改 `currentIndex`）

入口：[`CoverFlowCarouselHost`](../app/src/main/java/com/mica/music/ui/screens/player/view/CoverFlowCarouselHost.kt) `update` → `view.updateCurrentIndex(index)`。

1. 若 `trackAnimator != null`：**直接 return**（动画中不插队）
2. `|delta| != 1` 或无动画 → `commitTrackIndex` 瞬切
3. 相邻切歌：`ValueAnimator` 动画 `virtualCenter` 从 `clampTrackChangeStartVisual(...)` 到 `endVisual`
4. 每帧：`stripFraction = animatedVisual - fromCenter`；`invalidate()` only
5. `onAnimationEnd`：先设 `stripFraction = endVisual - fromCenter`，再 `commitTrackIndex`

`CoverFlowRails.clampTrackChangeStartVisual`：拖动已滑到 0.6 再点下一首时，起点钳在 `[fromCenter, endVisual]`，避免先弹回 0 再动画。

### 4.3 与 Compose 的关系

- 动画帧 **不触发** Compose 重组；只有 `invalidate()` → `onDraw`
- `AndroidView` 的 `update` 在父级重组时跑（`foldProgress`、尺寸等），**不是**每动画帧
- 改间距 / 倒影 / 轨道公式时，先跑 `CoverFlowRailsTest`，再真机拖+点切歌

---

## 5. 绘制管线

### 5.1 单遍绘制顺序

[`CoverFlowCarouselView.onDraw`](../app/src/main/java/com/mica/music/ui/screens/player/view/CoverFlowCarouselView.kt)：

1. 对 `lane ∈ [-3,3]` 构建 `LaneDrawState`（`railOffset` 超 `MaxViewDistance` 或 alpha≈0 则跳过）
2. 按 `zIndex` 排序（远 → 近）
3. 每槽 `drawLane`：同一 `canvas.save/restore` 栈内画封面 + 倒影

### 5.2 复古 3D

- `Camera` + `Matrix` 施加 `rotationY`（非 Compose `graphicsLayer`）
- `pivotX` 随 `|railOffset|` 在中心与外缘之间 **线性插值**，禁止在整数边界突变

### 5.3 倒影（平行 / 复古均有）

- 取 centerCrop 后位图 **底部 28%** 条带，垂直翻转；**不要**把整张图压进倒影区
- 与封面 **同一变换栈**（含复古 `rotationY`），保证倾斜封面与倒影衔接
- 渐变：`saveLayer` + `PorterDuff.Mode.DST_IN`（每可见槽一次，性能热点）
- 常量：`ReflectionHeightFraction = 0.28f`，`ReflectionAlpha = 0.24f`

### 5.4 坐标系

- 步进基准：**封面宽 `coverWidthPx`**（`layoutWidthPx()`），不用屏宽（否则间距被拉大、与 Compose 封面区错位）
- 中心 X：`coverStartPaddingPx + coverWidthPx * 0.5f`（与 `PlayerPageLayoutEngine` 对齐）
- 有倒影时封面 **顶对齐**：`contentCenterY = slotH * 0.5f`，下方留给倒影区

---

## 6. 布局与下半区（踩坑高发）

### 6.1 封面区占位

- **布局高度** = `cover.blockHeight`（= `coverHeight + topPadding`），由 [`PlayerPageLayoutEngine`](../app/src/main/java/com/mica/music/ui/screens/player/PlayerPageLayoutEngine.kt) 计算
- **倒影不占布局**：`CoverFlowCarouselView` 画布可高于 `blockHeight`（`cover.height + reflectionExtra`），通过 `clip = false` + `zIndex(1f)` 画进封面与标题之间的 `afterCover` 空隙
- **禁止** `blockHeight + reflectionExtra` 作为 Column 子项高度——会把下半区 `weight(1f)` 挤矮（「下半区上界被压低」）

### 6.2 手势

- 封面流拖动 / 点击在 **View `onTouchEvent`** 内处理
- 歌词展开时：仅叠 **无 `combinedClickable` 的透明 Box**（`NowPlayingCoverSection`），避免挡住 `AndroidView` 手势
- **禁止** 在铺满的 Compose `Box` 上挂 `combinedClickable` 盖住 `AndroidView`

### 6.3 背景 `zoneStop`

- 仍按 **无倒影** 的 `coverBlockHeight / screenHeight` 计算
- 倒影视觉上落在 `zoneStop` 以下、标题区以上的间隙

---

## 7. 间距与缩放参数（调参只改 `CoverFlowRails`）

| 常量 | 当前值 | 含义 |
|------|--------|------|
| `PauseFoldStep` | `0.80` | 平行：相邻槽间距 × 封面宽 |
| `RetroFirstStep` | `1.10` | 复古：\|railOffset\|=1 的累计位移系数 |
| `RetroOuterStep` | `1.20` | 复古：\|railOffset\|=2 的累计位移系数 |
| `NearSideScale` | `0.85` | 复古 ±1 额外缩放（相对基础 slotScale） |
| `OuterSideScale` | `0.90` | 复古 ±2、±3 再缩 10% 相对近邻 |

复古位移在 \|offset\|∈(1,2] 线性插值，\|offset\|>2 按 `thirdStep` 外推（±3 不与 ±2 叠位）。

平行与复古 **分开调系数**；用户反馈「平行偏宽、复古偏近」时勿共用一个 `LaneStepFraction`。

---

## 8. 两周踩坑清单（禁止重演）

| 现象 | 根因 | 正确做法 |
|------|------|----------|
| 切歌闪到屏外 / 末帧跳变 | 平行模式「父层 `carouselShift` + 子槽整数 `laneOffset`」双轨位移；提交时与 `stripFraction` 不同步 | 只保留 `translationPx(railOffset)` 单轨 |
| 滑到位缩放才突变 | 缩放 / 枢轴绑整数 `laneIndex`，位移绑 `railOffset` | 全部 `CoverFlowRails.*(railOffset)` |
| 下一张开头闪一下 | 末帧先改 `logicalCenter` 再归零 `stripFraction`，或动画起点未 `clamp` | 末帧先对齐 `stripFraction`，再 `commitTrackIndex` |
| 倒影不可见 | View 高度不足被裁切 | 画布加高 + 父级 `clip=false` |
| 倒影像顶行像素拉伸 | 整图压进倒影区 | 只取底部 28% 条带翻转 |
| 复古倒影与倾斜封面脱节 | 倒影第二遍绘制未跟封面变换栈 | 同一 `drawLane` 栈内先封面后倒影 |
| 倒影盖住中心图 | 倒影单独全量第二遍绘制 | 按 z 序逐槽：封面+倒影 |
| 滑动切歌失效 | Compose 透明层 `combinedClickable` 拦截触摸 | 手势交给 View；遮罩仅歌词展开 |
| 下半区变矮 | 倒影高度计入 Column 布局 | 布局 `blockHeight`，倒影溢出绘制 |
| 外侧 ±2 看不见 | 复古步进过大 | 调 `RetroFirstStep` / `RetroOuterStep`（平行不动除非明确要求） |

---

## 9. 性能（相对旧 Compose Stage）

| 维度 | 旧 `CoverFlowStage` | 现 `CoverFlowCarouselView` |
|------|---------------------|----------------------------|
| 动画帧 | 每帧 Compose 重组 + 7 槽 `graphicsLayer` | `ValueAnimator` + `invalidate()`，无重组 |
| 图片 | 每槽 2× `SongCover`（封面+倒影） | Coil 缓存 `Bitmap` + `drawBitmap` |
| 倒影 | 每槽 `CompositingStrategy.Offscreen` | 每槽 `canvas.saveLayer`（仍贵，但单 View 内） |
| 固定成本 | 纯 Compose | `AndroidView` 桥接 + `update` 回调 |

**结论**：动画热路径通常 **不比原来差、往往更好**；瓶颈仍在最多 7 槽倒影的离屏绘制。未在真机跑 Systrace 前，不要为「性能」再拆回 Compose 双轨。

---

## 10. 改代码前检查清单

- [ ] 位移 / 缩放 / 旋转 / alpha / pivot / z 序是否 **全部** 只依赖 `railOffset`？
- [ ] 相邻切歌末帧是否满足 `railOffset` 连续？（跑 `CoverFlowRailsTest`）
- [ ] 调间距是否只改 `CoverFlowRails`，且平行/复古分开？
- [ ] 倒影是否仍在同一变换栈、底部条带翻转？
- [ ] 布局高度是否仍为 `cover.blockHeight`（倒影不撑 Column）？
- [ ] 是否在 View 热路径上误接 Compose 槽位动画或第二套 `stripFraction` 状态？
- [ ] 真机：拖动切歌 + 按钮切歌 + 平行/复古各测一遍

---

## 11. 封面流数据流简图（平行 / 复古）

```mermaid
flowchart TB
    subgraph compose [Compose 壳]
        NPC[NowPlayingCoverSection]
        Host[CoverFlowCarouselHost AndroidView]
        PLE[PlayerPageLayoutEngine cover.blockHeight]
    end

    subgraph view [View 热路径]
        CFV[CoverFlowCarouselView]
        Rails[CoverFlowRails]
        VA[ValueAnimator stripFraction]
        Draw[onDraw 7 lanes]
    end

    PLE --> NPC
    NPC --> Host
    Host --> CFV
    Player[currentIndex] --> Host
    Host -->|updateCurrentIndex| CFV
    VA -->|stripFraction| CFV
    CFV --> Rails
    Rails --> Draw
    Touch[onTouchEvent] --> CFV
```

---

## 13. 拍立得回忆（`PHOTO_STACK`）

### 13.1 一句话结论

**三张固定角色卡片（前 / 中 / 后），稳态用 `PhotoStackPose` 叠放；切歌时用 `PhotoStackTransitionSlot` + 单一 `transitionProgress` 做抽换，与封面流 `railOffset` 无关。**

末帧：`commitTrackIndex` 更新 `logicalCenter`，清空 `activeTransitionCards`，`transitionProgress = 1` 回到稳态栈。

### 13.2 架构分层

| 层 | 文件 | 职责 |
|----|------|------|
| Compose 入口 | [`PhotoStackTheme.kt`](../app/src/main/java/com/mica/music/ui/screens/PhotoStackTheme.kt) | 薄包装 → `PhotoStackTransitionHost` |
| Compose 挂载 | [`NowPlayingCoverSection.kt`](../app/src/main/java/com/mica/music/ui/screens/NowPlayingCoverSection.kt) | `photoStack.normalLayerVisible` 时挂载；与标准/粒子/封面流互斥 |
| View 宿主 | [`PhotoStackTransitionHost.kt`](../app/src/main/java/com/mica/music/ui/screens/player/view/PhotoStackTransitionHost.kt) | `AndroidView` factory/update、帧像素、`PhotoStackCarouselNavigationBridge` |
| 绘制 + 手势 | [`PhotoStackTransitionView.kt`](../app/src/main/java/com/mica/music/ui/screens/player/view/PhotoStackTransitionView.kt) | 稳态/转场绘制、轻扫、前卡 seek、频谱波形 |
| 转场规划 | [`PhotoStackTransition.kt`](../app/src/main/java/com/mica/music/ui/screens/player/PhotoStackTransition.kt) | `PhotoStackTransitionPlan`、slot → card 列表 |
| 布局帧 | [`PlayerPageLayoutEngine.kt`](../app/src/main/java/com/mica/music/ui/screens/player/PlayerPageLayoutEngine.kt) | `PhotoStackFrame`、卡片尺寸、垂直留白 |
| 类型 | [`PlayerPageTypes.kt`](../app/src/main/java/com/mica/music/ui/screens/player/PlayerPageTypes.kt) | `PhotoStackFrame` 字段 |
| 导航桥 | [`PhotoStackCarouselNavigationBridge.kt`](../app/src/main/java/com/mica/music/ui/screens/player/view/PhotoStackCarouselNavigationBridge.kt) | 外部 `updateCurrentIndex` → View |
| 阴影调参 | [`PhotoStackShadowTuning.kt`](../app/src/main/java/com/mica/music/ui/screens/player/view/PhotoStackShadowTuning.kt) | 预览页可调；现网默认构造 |
| 预览 | [`PhotoStackShadowPreviewScreen.kt`](../app/src/main/java/com/mica/music/ui/screens/PhotoStackShadowPreviewScreen.kt) | 设置 → 高级 → 阴影预览路由 |

### 13.3 稳态 vs 转场槽位

**稳态**（`photoStackSteadyCards`）：

| Slot | 队列语义 | 进度/频谱 |
|------|----------|-----------|
| `SteadyFront` | `queue[currentIndex]` | `showProgress = true` |
| `SteadyMiddle` | `queue[currentIndex + 1]` | 否 |
| `SteadyBack` | `queue[currentIndex + 2]` | 否 |

**切下一首**（`TrackSkipDirection.TO_NEXT`）：`NextEmergingBack` / `NextStackMiddle` / `NextStackFront` / `NextLeavingFront`（离场的旧前卡带进度）。

**切上一首**（`TO_PREVIOUS`）：`PreviousFadingBack` / `PreviousStackBack` / `PreviousStackMiddle` / `PreviousIncomingFront`。

各 slot 的 `PhotoStackPose` 由 `poseFor(slot, transitionProgress)` **lerp**；禁止在转场中途改 Compose 槽位 key。

### 13.4 动画时序

1. **外部切歌**：`PhotoStackTransitionHost` update → `navigationBridge.view?.applyHostUpdate` / `updateCurrentIndex`
2. `|delta| != 1` 或无动画 → `resetToIndex` 瞬切
3. 相邻切歌：构建 `activeTransitionCards`，`ValueAnimator` **0→1**，时长 **`620ms`**
4. 每帧：`transitionProgress` + `invalidate()` only（**不**重组 Compose）
5. `onAnimationEnd`：`commitTrackIndex` → 清空转场卡 → `flushPendingPlayQueueIndex`

**跟手轻扫**（稳态、无转场卡）：

- `dragFraction` 累加 `deltaX / width`，钳制 ±`SwipeVisualLimitFraction`（0.15）
- 松手：`> 0.11` 上一首，`< -0.11` 下一首；否则 `animateDragFractionToZero`
- 前卡 idle 位姿：`translationX = dragFraction × slotWidth × 0.35`，`rotationZ = -1.4° + dragFraction × 6°`

**Host 索引守卫**：binder 延迟时 `pendingHostIndex` / `hostIndexGuardUntilMs`（1500ms）避免 UI 索引被旧回调打回。

### 13.5 布局（`PlayerPageLayoutEngine`）

| 常量 | 值 | 含义 |
|------|-----|------|
| `PhotoStackScreenFraction` | `0.80` | 卡片宽度 / 屏宽 |
| `PhotoStackAspectRatio` | `0.78` | 高 / 宽 |
| `PhotoStackEdgeFraction` | `0.10` | 期望顶/底留白 / 屏高 |

`PhotoStackFrame` 输出 `slotWidth/Height`、`cardWidth/Height`、`artworkInset*`、`waveformHeight`（24dp）供 Host 转 px。

`normalLayerVisible = photoStackMode && !lyricsExpanded && !immersiveLower && lyricsFocus≈0`。

歌词聚焦时拍立得层隐藏，封面区走与普通模式相同的 shrink lerp。

### 13.6 绘制管线

单遍 `onDraw`（稳态或转场卡列表）：

1. 按 slot 算 `PhotoStackPose`（含 alpha）
2. `canvas.save` → translate / rotate / scale
3. `drawShadowHalo`：预烘焙 Bitmap（三层 `BlurMaskFilter` + **`drawRoundRect` 阴影**）
4. 纸框 **`drawRect`**（米白渐变 + 描边）——卡片本体直角，圆角仅阴影层
5. 封面 `centerCrop` 位图（Coil → `bitmapByKey` 缓存）
6. 若 `showProgress`：`drawProgressStrip`（波形 + 进度条）

频谱：`MicaSpectrumAnalyzer` → 86 条 `spectrumDisplayLevels`；仅绑定前卡 `song.id`。

### 13.7 与封面流七轨的差异

| 维度 | 封面流 §1–§12 | 拍立得 §13 |
|------|----------------|------------|
| 可见队列深度 | ±3 lane | 稳态 3 张 |
| 动画变量 | `stripFraction` / `railOffset` | `transitionProgress` + `dragFraction` |
| 数学真相源 | `CoverFlowRails.kt` | `PhotoStackTransition.kt` + View 内 `poseFor` |
| 倒影 | 28% 条带翻转 | 无；纸框 + 阴影 |
| 进度 UI | 下半屏 chrome 或封面底边 | **前卡底带**内嵌 seek |
| 点击侧槽切歌 | 有 | 无（轻扫 / 按钮 / 队列） |
| 单测 | `CoverFlowRailsTest` | 暂无 |

**勿**把拍立得硬套 `CoverFlowRails` 或 Compose 七槽 `AnimatedContent`。

### 13.8 踩坑与调参

| 现象 | 根因 | 做法 |
|------|------|------|
| 切歌后仍播旧曲 | Host index 与 View `logicalCenter` 竞态 | 查 `pendingHostIndex` / `playQueueIndexAfterVisualCommit` |
| 转场中误触 seek | 未禁用手势 | `activeTransitionCards.isNotEmpty()` 时 `onTouchEvent` return false |
| 阴影变更不刷新 | Bitmap 缓存 key 未变 | `setShadowTuning` → `clearShadowBitmapCache` |
| 沉浸模式叠拍立得 | `supportsImmersiveLower=false` | 设置项应禁用；`normalLayerVisible` 已 guard |

阴影预览：设置 → 高级 → **拍立得阴影预览**（`PhotoStackShadowPreviewScreen`）。

### 13.9 数据流简图

```mermaid
flowchart TB
    subgraph compose [Compose 壳]
        NPC[NowPlayingCoverSection]
        PSH[PhotoStackThemeHost]
        Host[PhotoStackTransitionHost AndroidView]
        PLE[PlayerPageLayoutEngine PhotoStackFrame]
    end

    subgraph view [View 热路径]
        PST[PhotoStackTransitionView]
        Plan[PhotoStackTransition.kt slots]
        VA[ValueAnimator transitionProgress]
        Draw[onDraw 1-4 cards]
    end

    PLE --> NPC
    NPC --> PSH --> Host --> PST
    Player[currentIndex] --> Host
    Host -->|updateCurrentIndex| PST
    Plan --> PST
    VA --> PST
    Touch[onTouchEvent swipe/seek] --> PST
```

---

## 12. 文档维护

- 改产品交互 / 设置项 → 更新本文 §0
- 改封面流热路径 → 更新本文 §2、§4
- 改拍立得热路径 → 更新本文 §13
- 改间距常量 → 更新 §7 表格 + `CoverFlowRails.kt` 注释
- 新踩坑 → 封面流追加 §8；拍立得追加 §13.8
