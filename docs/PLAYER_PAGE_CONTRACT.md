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
| `playQueueIndex` / `moveQueueItem` / `removeQueueItem` | 队列场景 / 横屏侧栏 |
| `cyclePlaybackQueueMode` | 播放模式循环 |
| `toggleImmersiveLower` | 沉浸模式 |
| `insertPlayNext` / `setQueue` | 长按菜单 |
| `attachMusicVideoOutput` / `detachMusicVideoOutput` | 仅标准主题当前歌曲的 TextureView 输出；由 `PlaybackRuntime` 校验 lease、mediaId 与 Controller identity |

## 封面手势 → Controller

| 模式 | 实现 | 行为 |
|------|------|------|
| **标准**（`STANDARD`） | [`CoverGestureCoordinator.kt`](../app/src/main/java/com/mica/music/ui/screens/player/CoverGestureCoordinator.kt) | 横向轻扫 → `onPrevious` / `onNext` |
| **自定义标准**（`CUSTOM_STANDARD`） | `CustomPlayerPagePanel` 统一六组件布局 | 标准封面手势保持；封面、信息行、标题、歌词、进度和控制按 `PlayerLowerLayoutConfig` 拖拽排序、缩放、显隐和调间距 |
| **封面流**（平行 / 复古） | [`CoverFlowCarouselView`](../app/src/main/java/com/mica/music/ui/screens/player/view/CoverFlowCarouselView.kt) | 拖动跟手；松手超阈值 → `onPlayQueueIndex` / `onNext` / `onPrevious`；点击侧槽 → `onPlayQueueIndex` |
| **拍立得**（`PHOTO_STACK`） | [`PhotoStackTransitionView`](../app/src/main/java/com/mica/music/ui/screens/player/view/PhotoStackTransitionView.kt) + [`PhotoStackLyricsTransition.kt`](../app/src/main/java/com/mica/music/ui/screens/PhotoStackLyricsTransition.kt) | 卡片轻扫切歌、前卡底带 seek；播放页空白区域左右滑进出歌词页并跟手；卡片/seek/控制/歌词列表继续各自消费手势。详见 [`COVER_FLOW_IMPLEMENTATION.md`](COVER_FLOW_IMPLEMENTATION.md) §13 |
| **粒子封面**（`PARTICLE_COVER`） | [`ParticleCoverPlayerLayer`](../app/src/main/java/com/mica/music/ui/screens/player/ParticleCoverPlayerLayer.kt) + [`ParticleCoverHost`](../app/src/main/java/com/mica/music/ui/screens/player/view/ParticleCoverHost.kt) | 全屏 GLES 层；切歌分解动画；**不**走标准轻扫/封面流。详见 [`PARTICLE_COVER_OPENGL_MIGRATION.md`](PARTICLE_COVER_OPENGL_MIGRATION.md) §0 |

不新增 Controller API。封面流切歌动画由 View 监听 `currentIndex`（`CoverFlowCarouselHost.update` → `updateCurrentIndex`）驱动；拍立得经 `PhotoStackCarouselNavigationBridge`；粒子封面切歌由 `ParticleCoverHost` 内部阶段动画 + 播放器 `currentIndex` 同步。

音乐 MV 是此规则的窄例外：不向 UI 暴露可提交 URI 的通用 Controller API，只在 `NowPlayingActions` 暴露 attach/detach 当前播放视频输出。`PlaybackRuntime` 的单一 Surface lease 校验 TextureView、mediaId 和 Controller identity；仅 `STANDARD`、Activity RESUMED、歌词页与竖屏队列均关闭时挂载。静态封面始终在下层，首帧后淡入；黑色 1:1 Fit，不裁剪。切歌转场用既有静态封面 wipe 承接，只允许一个真实视频 Surface。

**互斥**：同一时刻仅一种封面行为层挂载（`NowPlayingCoverSection` 分支）。`CUSTOM_STANDARD` / `PARTICLE_COVER` / `PHOTO_STACK` **不支持**下半屏沉浸（`supportsImmersiveLower = false`）。

横屏平行 / 复古另有页面局部状态 `landscapeCoverFlowImmersive`：稳定播放态长按标题进入，仅保留背景与封面流区域并隐藏全部系统栏；以平行封面带中心封面本体高度铺满屏幕为基准计算外层缩放，复古立体复用相同缩放数字。中心封面本体上下居中，倒影不参与尺寸或居中计算。返回优先退出，旋转、切主题或进入歌词页也退出。该状态不写入 `AppUiSettings`，不新增 Controller API，也不复用竖屏 `immersiveLower`。封面底边进度/频谱属于封面流区域，继续由现有设置和 `PlayerPageFrame` 决定。

## 布局

- `PlayerPageLayoutEngine.computeFrame()` — 单帧原子布局
- `PlayerPageFrame` — 封面区 + 下半屏 chrome 的全部几何与 alpha

`CUSTOM_STANDARD` 仅维护稳定播放态，不消费 `lyricsProgress` / `immersiveProgress` 做布局变形。其六组件顺序、`50%..200%` 大小、显隐、统一间距、顶部/底部留白、自由布局偏移、文字横向对齐及播放控制五键的逐个显隐只来自规范化后的 `PlayerLowerLayoutConfig`；总配置高度超过屏幕安全区域时统一收敛到屏内。

文字对齐以 `PlayerLowerTextTarget`（歌名 / 副标题 / 紧凑歌词）为单位，歌名与副标题相互独立，取值为 `PlayerLowerTextAlign` 的靠左 / 居中 / 靠右，默认居中且默认值不写入持久化。标题滚动时两侧边缘渐隐保持不变；静止时只渐隐文字未停靠的那一侧，避免靠左/靠右的首尾字被吃掉。播放控制五键由 `PlayerControlButton` 逐个显隐，隐藏的按钮让位给同尺寸占位，五个槽位几何不变，因此播放键在任何显隐组合下都停在正中。默认全部可见，两项配置都只作用于 `CUSTOM_STANDARD`；标准、粒子、平行、复古、拍立得主题继续使用居中文字与完整五键。封面作为普通布局项参与排序和缩放，但继续复用标准封面的点击、长按与滑动切歌链路。选中封面时元素栏下方出现“点击封面暂停/播放”与“专辑图阴影”两行开关，均默认关闭；阴影复用浮岛迷你栏 `FloatingIslandShadowHalo`，只作用于竖屏自定义标准封面。这两项与文字对齐、五键显隐一样写入布局草稿，保存后才持久化。进度组件是唯一普通进度来源，因此该主题不启用封面底边进度；其 32dp 触控区、轨道厚度、时间数字与频谱高度都随该组件 `50%..200%` 缩放，小于 100% 时轨道变短并居中，大于 100% 时长度顶满、只加粗，seek 仍按可见轨道整宽映射。进入歌词云或经典歌词页均复用粒子封面歌词云的横向整页滑动：目标页从右侧滑入、播放页向左退出，两页在过渡中始终相邻，返回时反向滑回。信笺主题不挂载经典歌词列表页，只做自身叠层过渡。标准封面在该转场中只改变绘制几何；图片请求尺寸和缓存键固定为正常播放态封面几何，禁止随歌词转场的逐帧尺寸变化重新发起请求。

竖屏播放页可在组件之外的空白区域长按进入自由布局编辑；设置 → 播放与界面 → 自定义标准主题中的“进入播放页布局编辑”是唯一设置入口，设置页不再提供布局预览、边界、间距、顺序、大小或显隐控制；有当前歌曲时入口直接展开播放页并发出一次性编辑请求。编辑以封面、信息行、标题、歌词、进度和控制六个现有组件为最小单位；拖动改变二维位置，双指改变大小，元素栏负责选择与显隐，并可在本次编辑会话内拖动（位置不落盘；换选中项时保持当前位置，不回到顶部），编辑工具栏、元素选框及标签统一使用 0dp 直角。选中封面时元素栏下方出现点击暂停/播放与专辑图阴影两行开关，选中标题时出现歌名与副标题两行对齐三选，选中紧凑歌词时出现一行对齐三选，选中播放控制时出现五键的逐个显隐开关；这些上下文控件与拖动、缩放共用同一份页面局部草稿。拖动的原始增量必须跨指针事件连续累计；只有显示和保存时，距离原始横轴或纵轴不超过画布对应尺寸 5‰ 的轴才吸附为零并显示辅助线，组件中心必须保持在可编辑画布内。编辑态必须覆盖并消费组件手势，禁止触发切歌、seek、歌词页、播放按钮或队列；所有连续手势只更新页面局部草稿，点击“保存”后才通过 `AppUiSettings.updateCustomPlayerLowerLayout` 持久化。取消、系统返回、旋转为横屏或切离 `CUSTOM_STANDARD` 都丢弃草稿；“恢复”只恢复草稿，仍需保存。横屏不提供自由布局编辑入口。

### 组件优先级

播放页视觉状态必须按以下优先级决策，低优先级组件不得自行绕过布局引擎重新判断：

1. **队列页 / 队列切换**：竖屏嵌入播放页；封面与歌词聚焦共用 `headerFocus`；底栏进度与五键收起；点选播放不关闭。自定义标准从当前封面槽插值到顶栏，不切标准布局。粒子封面保持 GLES，按封面槽缩到顶栏，不切静态图、不铺满歌词背景。拍立得 Polaroid 原位淡出，顶栏小专辑图与文字一并淡入，不转正、不共享元素到顶栏。横屏仍用侧栏。
2. **歌词页 / 歌词切换**：保留进度条过渡所需的布局与绘制，但频谱从打开歌词的第一帧起关闭，直到关闭动画完全结束。
3. **沉浸模式 / 沉浸切换**：频谱关闭；进度与控制区按布局引擎输出显示。
4. **切歌保护期**：频谱关闭，避免封面与音频状态切换时短暂显示旧数据。
5. **稳定播放场景**：频谱设置开启时才允许显示；位置由进度布局决定。

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
| `NowPlayingScreen.kt` | 播放页壳：背景、封面区、下半屏、竖屏队列场景 / 横屏队列侧栏 |
| `NowPlayingCoverSection.kt` | 封面尺寸、原样比例；封面流 / 拍立得 / 粒子 / 标准 分支挂载；底边进度 overlay |
| `PhotoStackTheme.kt` | 拍立得 Compose 入口 → `PhotoStackTransitionHost` |
| `player/ParticleCoverPlayerLayer.kt` | 粒子封面全屏 GLES 层（现网 `UseNativeParticleCoverInPlayer = true`） |
| `player/ParticleCoverPageLayout.kt` | 粒子模式布局帧与歌词区 alpha |
| `player/view/PhotoStackTransition*.kt` | 拍立得 View 岛 |
| `player/view/ParticleCoverHost.kt` | 粒子封面 GLES 宿主（现网热路径） |
| `player/view/ThreeParticleCoverHost.kt` | 粒子 WebView 回退（`UseNativeParticleCoverInPlayer = false` 时） |
| `PlayerLowerPanel.kt` | 下半屏组合：元数据、紧凑/展开歌词、chrome |
| `CustomPlayerLowerPanel.kt` | `CUSTOM_STANDARD` 的受约束六组件整页布局 |
| `HorizontalClassicLyricsPage.kt` | 自定义标准主题横滑后的经典歌词目标页 |
| `PlayerLowerPanelMetadata.kt` | Hi‑Fi 元数据、标题/副标题 |
| `PlayerLowerPanelChrome.kt` | 进度条、播放控制、频谱条 |
| `NowPlayingCompactLyrics.kt` | 三行歌词、空歌词 |
| `NowPlayingLyricsExpanded.kt` | 全屏歌词列表、自动滚动、行内 seek |
| `player/PlayerPageLayoutEngine.kt` | 单帧布局；`PlayerLowerPanelSpacing` |
| `player/PlayerPageState.kt` | 沉浸/歌词聚焦等动画 progress 与冻结状态 |
| `player/PlayerPageTypes.kt` | `PlayerPageFrame` 等布局数据类型 |
| `data/PlayerLowerLayoutConfig.kt` | 自定义播放页顺序、大小、显隐、间距、自由布局偏移与规范化 |
| `player/view/CoverFlowCarousel*.kt` | 封面流 View 岛（[`COVER_FLOW_IMPLEMENTATION.md`](COVER_FLOW_IMPLEMENTATION.md) §1–§12） |
| `player/CoverGestureCoordinator.kt` | 标准封面横向轻扫 |
| `player/ParticleCoverThemePolicy.kt` | 粒子 vs 封面流 stage 互斥、强制裁切填充 |

**约束**：新逻辑优先落入上表专用文件，**避免**再把 `NowPlayingScreen.kt` 撑大。

## 布局动画约束

顺序不可颠倒（详见 [`MOTION.md`](MOTION.md) §8.5）：

1. **先冻结**：`panelHeight`、`layoutMode`、下半区间距、底栏起止高度等在模式切换时快照。
2. **再全算**：`PlayerPageLayoutEngine.computeFrame()` 等仅用冻结输入。
3. **再动画**：单一 `immersiveProgress` / `headerFocus`（`max(lyricsProgress, queueProgress)`）等 progress 做 `lerp`；禁止动画途中用正在变的测量值重算目标。

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

- **出声路径**：已收敛为 **Exo 单链路**（`libffmpegJNI.so` + `MicaAudioProcessorChain` → `AudioTrack`）。无 `libmica_ffmpeg.so` 软件播兜底；`.dff` 播放时拒绝。
- **共享封面转场**：第一版在 `AppNavigation`；坐标污染与 overlay 顺序见 [`SHARED_ELEMENT_ANIMATION_NOTES.md`](SHARED_ELEMENT_ANIMATION_NOTES.md)。

## 回归清单

改动播放页布局 / 歌词 / 封面流后，至少验证：

- [ ] 打开 / 关闭播放页；切歌；播放 / 暂停 / seek / 队列
- [ ] 紧凑歌词与展开歌词；无歌词空状态；行点击 seek
- [ ] 沉浸模式；歌词聚焦（封面 lerp + 底栏）
- [ ] 竖屏队列：封面缩至歌词顶栏、列表嵌入腾出空间、底栏进度与五键收起、点选不关闭、返回关闭；自定义标准从当前槽飞顶栏；粒子保持 GLES 缩槽；拍立得 Polaroid 原位淡出；横屏仍侧栏
- [ ] 封面底边进度模式 ↔ 歌词页返回无跳变
- [ ] 原样比例横/竖封面无两步位移
- [ ] 封面流：平行 / 复古 × 拖动与按钮切歌，无闪帧（`CoverFlowRailsTest`）
- [ ] 横屏封面流沉浸：平行 / 复古长按标题进入；平行中心封面全屏高、复古复用同一缩放数字，封面本体上下居中；只留背景、封面流及已启用的封面底边进度/频谱；状态栏和导航栏隐藏；返回、旋转、切主题、进入歌词均退出
- [ ] 拍立得：轻扫切歌、前卡 seek、转场中不可 seek；进入/退出歌词页的中间帧保持双页挂载且布局不跳；× 各播放页背景
- [ ] 粒子封面：切歌分解/重组、歌词聚焦几何时 `ParticleCoverPlayerLayer` 与布局一致；预览页调参后播放页一致
- [ ] 粒子 / 拍立得：确认沉浸模式入口不可用或无效
- [ ] 手势导航条设备：背景铺满底边，控件避让 `navigationBars`
- [ ] 迷你栏 → 播放页共享封面（含搜索/键盘场景，见共享元素文档）
- [ ] 自定义标准竖屏：空白长按进入编辑；六组件拖动/缩放/显隐；选中封面可开关点击播放与专辑图阴影；中心吸附线；编辑时所有播放手势无效；保存后重进仍保持；取消/返回/旋转/切主题不落盘

纯布局/歌词 helper 可补 JVM 单测：`CoverFlowRailsTest`、`PlayerPageLayoutEngineTest`、`PhotoStackLyricsTransitionTest` 等。

## 修订记录

| 日期 | 说明 |
|------|------|
| 2026-05 | 初版契约 |
| 2026-06 | 合并原 `REVIEW_NOTES.md` 模块地图与回归清单；删除独立审查笔记 |
| 2026-06 | 记录封面底边进度与歌词页切换跳变的根因、错误修法及布局不变量 |
| 2026-07 | 补充拍立得 / 粒子封面手势、模块地图与回归项；粒子现网 GLES 路径 |
| 2026-09 | 竖屏队列改为播放页 `Queue` 场景：封面与歌词聚焦共用 `headerFocus`；横屏仍侧栏 |
| 2026-09 | 拍立得竖屏队列：Polaroid 原位淡出，不切 `SongCover`、不飞顶栏 |
