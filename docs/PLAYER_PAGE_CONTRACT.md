# 播放页 UI 契约

> `PlayerController` / 媒体服务 / 队列语义由数据层定义；本文约定 **播放页 UI 边界**、模块拆分与回归要求。

## 状态输入

播放页 UI 只读以下状态：

- `PlaybackSurfaceState` — 当前曲、播放/缓冲/错误、播放模式
- `PlaybackProgressState` — 进度与时长
- `PlaybackQueueState` — 队列与当前下标
- `AppUiSettings` — 主题、下半背景、封面流、沉浸、频谱等

## 操作输出（`NowPlayingActions`）

| 方法 | 用途 |
|------|------|
| `togglePlay` / `previous` / `next` | 播放控制 |
| `seekToMs` | 进度条与歌词点击 seek |
| `setSeekUiActive` | 拖动时钉住进度 UI |
| `playQueueIndex` / `moveQueueItem` / `removeQueueItem` | 队列 Sheet |
| `cyclePlaybackQueueMode` | 播放模式循环 |
| `toggleImmersiveLower` | 沉浸模式 |
| `insertPlayNext` / `setQueue` | 长按菜单 |

## 封面手势 → Controller

| 模式 | 实现 | 行为 |
|------|------|------|
| **封面流**（平行 / 复古） | [`CoverFlowCarouselView`](../app/src/main/java/com/mica/music/ui/screens/player/view/CoverFlowCarouselView.kt) | 拖动跟手；松手超阈值 → `onPlayQueueIndex` / `onNext` / `onPrevious`；点击侧槽 → `onPlayQueueIndex` |
| **标准主题** | [`CoverGestureCoordinator.kt`](../app/src/main/java/com/mica/music/ui/screens/player/CoverGestureCoordinator.kt) | 横向轻扫 → `onPrevious` / `onNext` |

不新增 Controller API。封面流切歌动画由 View 监听 `currentIndex`（`CoverFlowCarouselHost.update` → `updateCurrentIndex`）驱动，详见 [`COVER_FLOW_IMPLEMENTATION.md`](COVER_FLOW_IMPLEMENTATION.md) §4。

## 布局

- `PlayerPageLayoutEngine.computeFrame()` — 单帧原子布局
- `PlayerPageFrame` — 封面区 + 下半屏 chrome 的全部几何与 alpha

### 组件优先级

播放页视觉状态必须按以下优先级决策，低优先级组件不得自行绕过布局引擎重新判断：

1. **歌词页 / 歌词切换**：保留进度条过渡所需的布局与绘制，但频谱从打开歌词的第一帧起关闭，直到关闭动画完全结束。
2. **沉浸模式 / 沉浸切换**：频谱关闭；进度与控制区按布局引擎输出显示。
3. **切歌保护期**：频谱关闭，避免封面与音频状态切换时短暂显示旧数据。
4. **稳定播放场景**：频谱设置开启时才允许显示；位置由进度布局决定。

特殊封面流的“切歌保护期”从 `ACTION_DOWN` 开始，包含未达到切歌阈值时的回弹动画；仅在回弹或切歌动画结束后恢复频谱。

进度布局规则：

| 状态 | 普通进度区 | 专辑图底边进度 |
|------|------------|----------------|
| 「封面底边进度」关闭 | 显示 | 隐藏 |
| 「封面底边进度」开启 | 隐藏；歌词切换时仅为交叉淡化临时挂载 | 显示 |
| 歌词页 | 显示歌词页进度 | 隐藏 |

频谱不是独立布局模式。它只能附着于当前有效的进度布局，并由 `PlayerPageFrame.spectrumEnabled` 统一控制。组件层不得根据 `lyricsProgress`、`showStandardProgress` 等再次推导频谱资格。

普通进度条上方的频谱使用独立绘制高度，可向上覆盖紧凑歌词区域；该高度不参与进度区测量，不得为了容纳频谱而推挤歌词或播放控制。

## 模块职责（文件地图）

| 文件 | 职责 |
|------|------|
| `NowPlayingScreen.kt` | 播放页壳：背景、封面区、下半屏、队列 Sheet |
| `NowPlayingCoverSection.kt` | 封面尺寸、原样比例、封面流 `AndroidView`、底边进度 overlay |
| `PlayerLowerPanel.kt` | 下半屏组合：元数据、紧凑/展开歌词、chrome |
| `PlayerLowerPanelMetadata.kt` | Hi‑Fi 元数据、标题/副标题 |
| `PlayerLowerPanelChrome.kt` | 进度条、播放控制、频谱条 |
| `NowPlayingCompactLyrics.kt` | 三行歌词、空歌词 |
| `NowPlayingLyricsExpanded.kt` | 全屏歌词列表、自动滚动、行内 seek |
| `player/PlayerPageLayoutEngine.kt` | 单帧布局；`PlayerLowerPanelSpacing` |
| `player/PlayerPageState.kt` | 沉浸/歌词聚焦等动画 progress 与冻结状态 |
| `player/PlayerPageTypes.kt` | `PlayerPageFrame` 等布局数据类型 |
| `player/view/CoverFlowCarousel*.kt` | 封面流 View 岛（`COVER_FLOW_IMPLEMENTATION.md`） |
| `player/CoverGestureCoordinator.kt` | 标准封面横向轻扫 |

**约束**：新逻辑优先落入上表专用文件，**避免**再把 `NowPlayingScreen.kt` 撑大。

## 布局动画约束

顺序不可颠倒（详见 [`MOTION.md`](MOTION.md) §8.5）：

1. **先冻结**：`panelHeight`、`layoutMode`、下半区间距、底栏起止高度等在模式切换时快照。
2. **再全算**：`PlayerPageLayoutEngine.computeFrame()` 等仅用冻结输入。
3. **再动画**：单一 `immersiveProgress` / `lyricsFocus` 等 progress 做 `lerp`；禁止动画途中用正在变的测量值重算目标。

### 封面底边进度 ↔ 歌词页跳变复盘

**现象**

- 歌词页返回播放页时，封面底边进度条先消失，元数据、歌词和控制区随后突然补上空位。
- 播放页进入歌词页时，标准进度条没有正常淡入。
- 曾有一版把进度条和五个播放按钮放进同一个会改变高度的 `Column`，导致按钮也跟随进度条产生形变/位移。

**根因**

1. `coverEdgeOnPlaySurface` 同时承担了“底边进度条是否可见”和“采用哪套底栏布局”两种职责。布尔值越过阈值时，Compose 会在两套测量结果间离散切换。
2. 底栏高度与间距只在 `lyricsFocus` 最后约 5% 区间内插值。数学上连续，但绝大部分位移集中在极短时间内，视觉效果仍接近跳变。
3. 底边进度条、标准进度条和布局分别使用 `lyricsChromeFade`、`lyricsFocus` 及条件挂载，时间线不一致。进度条可能先卸载，其他组件下一帧才补位。
4. 标准进度条的过渡分支受互斥状态限制，切换过程中无法持续挂载，因此淡入动画被跳过。
5. 频谱资格曾只排除沉浸和切歌，没有排除歌词切换；标准进度条为交叉淡化临时挂载时会顺带闪出频谱。下半区还曾额外绘制第二份普通频谱，使生命周期更难推断。

**解决方式与不变量**

- `lyricsFocus` 负责整段页面几何插值；底栏高度、进度区占位和相关间距必须在 `0f..1f` 全区间连续变化，禁止只在末端阈值附近补位。
- 进度条显隐只控制绘制和透明度，不得直接选择另一套底栏测量结构。
- 封面底边进度条与标准进度条共用 `chromeProgressAlpha` 做交叉淡化；切换期间标准进度条保持挂载，动画结束后才允许移除。
- 进度条临时挂载不代表频谱可见；频谱仅在稳定播放场景启用，歌词打开与关闭动画全程禁用。
- 普通进度区只允许 `PlayerProgressBarSection` 绘制一份频谱，禁止在 `PlayerLowerPanel` 叠加第二份。
- 五个播放按钮独立锚定在底栏底部，不能成为进度条 `Column` 的流式子项；进度条出现、消失或高度变化不得改变按钮自身测量位置。
- 不要用条件挂载制造进度条空缺，再依赖相邻组件自动补位。需要移动的页面组件必须由布局引擎给出的连续帧明确驱动。
- JVM 回归测试至少覆盖：打开/关闭歌词页两个方向的中间帧、进度条 alpha 连续性、底栏高度在播放页/中点/歌词页之间单调变化。

相关实现：`PlayerPageLayoutEngine.kt`、`PlayerLowerPanelChrome.kt`、`NowPlayingCoverSection.kt`、`PlayerPageLayoutEngineTest.kt`。

## 架构注意

- **出声路径**：现网 `PlayerController.playSong()` 走 FFmpeg → PCM → `AudioTrack`（见 `TODO.md`「全格式软件播」）。缺 `libmica_ffmpeg.so` 时播放失败；长期待办为原生解码优先、FFmpeg 兜底。
- **共享封面转场**：第一版在 `AppNavigation`；坐标污染与 overlay 顺序见 [`SHARED_ELEMENT_ANIMATION_NOTES.md`](SHARED_ELEMENT_ANIMATION_NOTES.md)。

## 回归清单

改动播放页布局 / 歌词 / 封面流后，至少验证：

- [ ] 打开 / 关闭播放页；切歌；播放 / 暂停 / seek / 队列
- [ ] 紧凑歌词与展开歌词；无歌词空状态；行点击 seek
- [ ] 沉浸模式；歌词聚焦（封面 lerp + 底栏）
- [ ] 封面底边进度模式 ↔ 歌词页返回无跳变
- [ ] 原样比例横/竖封面无两步位移
- [ ] 封面流：平行 / 复古 × 拖动与按钮切歌，无闪帧（`CoverFlowRailsTest`）
- [ ] 手势导航条设备：背景铺满底边，控件避让 `navigationBars`
- [ ] 迷你栏 → 播放页共享封面（含搜索/键盘场景，见共享元素文档）

纯布局/歌词 helper 可补 JVM 单测：`CoverFlowRailsTest`、`PlayerPageLayoutEngineTest` 等。

## 修订记录

| 日期 | 说明 |
|------|------|
| 2026-05 | 初版契约 |
| 2026-06 | 合并原 `REVIEW_NOTES.md` 模块地图与回归清单；删除独立审查笔记 |
| 2026-06 | 记录封面底边进度与歌词页切换跳变的根因、错误修法及布局不变量 |
