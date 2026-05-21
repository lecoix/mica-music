# Mica 界面动效规范（Motion Rules）

> **单一实现入口**：`app/src/main/java/com/mica/music/ui/motion/MicaMotion.kt`  
> **本文件为动效规则的权威说明**；`DESIGN_SPEC.md` §九 中的时长表为早期草案，若与本文或 `MicaMotion` 常量不一致，以本文为准。

---

## 一、设计原则

1. **克制、短促**：动效服务于空间与层级，不抢内容（与 `DESIGN_SPEC` 哲学一致）。
2. **方向一致**：「前进」= 新内容从右入、旧内容向左出；「返回」相反。用户应能凭滑动方向判断是深入还是退回。
3. **单一曲线**：界面过渡统一 `FastOutSlowInEasing`（`MicaMotion.Easing`），避免每页各用一套弹性曲线。
4. **尊重系统**：Android「减少动态效果 / 动画缩放为 0」时，**瞬时切换**（0ms），不做装饰性动画。
5. **先接工具类**：新增 `AnimatedContent` / `animate*AsState` 时，优先 `rememberMicaMotionEnabled()` + `MicaMotion.tween*`，禁止硬编码 `tween(300)` 除非有书面例外。

---

## 二、时长 Token

| Token | 毫秒 | 典型用途 |
|-------|------|----------|
| `DurationShortMs` | **200** | 顶栏局部切换（标题 ↔ 搜索框）、统计栏收起、轻量 fade |
| `DurationMediumMs` | **320** | 主页分区、浏览详情、NavHost 路由、侧栏、主题色、切歌擦除 |
| `DurationLongMs` | **400** | 播放页沉浸（封面 lerp、底栏 chrome 显隐）、需要更「沉」的过渡 |

**选用规则**

- 同一屏内**局部**状态（顶栏、一行控件）：Short  
- **整页 / 分区 / 路由**级：Medium  
- **播放页大块布局**重组：Long  
- 循环装饰（如播放指示竖条）**不**使用上述 token，单独注明周期（见 §六）

---

## 三、无障碍与全局开关

```kotlin
val motionEnabled = rememberMicaMotionEnabled()
```

- `rememberMicaMotionEnabled()` = 未开启系统减少动效 **且** `MicaMotion.LocalEnabled == true`。
- `MainActivity` 通过 `CompositionLocalProvider(MicaMotion.LocalEnabled provides !reduceMotion)` 与系统设置联动。
- `motionEnabled == false` 时：所有 `MicaMotion.tween*` 变为 `tween(0)`，过渡 API 应走 fade 0ms 分支。

**禁止**：在业务代码里绕过 `motionEnabled` 单独 `tween(320)`。

---

## 四、方向语义（Depth 模型）

`directionalPaneTransition` 根据 **depth 比较** 决定动画：

| 关系 | 动画 |
|------|------|
| `depth(target) > depth(initial)` | **前进**：新页右入，旧页左出 + fade |
| `depth(target) < depth(initial)` | **返回**：新页左入，旧页右出 + fade |
| `depth` 相等 | **同级**：仅交叉 fade |

`SizeTransform(clip = false)`：允许新旧内容尺寸不同（列表 ↔ 分析页等）。

### 4.1 主页 `HomePaneKey`（`homePaneDepth`）

定义于 `HomeScreen.kt`：

| Depth | Pane |
|-------|------|
| 0 | `Songs`、`Search` |
| 1 | `Analysis`、`Playlist`、`Browse(Root)` |
| 2 | `Browse(Artist/Album 详情)` |

- 侧栏切换「歌曲 / 歌手 / 专辑 / 最近 / 歌单 / 分析」→ `AnimatedContent` + `homePaneWithSearchTransition`。
- **搜索**：进入/退出 Search 时**强制**走与「前进/返回」相同的全宽横向推入（不依赖 depth 差），见 `homePaneWithSearchTransition`。

### 4.2 浏览 `BrowseDestination`（`browseDestinationDepth`）

定义于 `HomeBrowseContent.kt`：

| Depth | 目的地 |
|-------|--------|
| 0 | `Root`（歌手列表 / 专辑列表） |
| 1 | `Artist` / `Album` 详情 |

歌手、专辑各自 `AnimatedContent` 包裹 Root ↔ 详情。

---

## 五、预设 API 一览

| API | 用途 |
|-----|------|
| `directionalPaneTransition(enabled, depth)` | 通用分区栈；前进/返回/同级 |
| `homePaneWithSearchTransition(enabled, depth, isSearch)` | 主页：搜索进出场 + 其余走 directional |
| `topBarSearchTransition(enabled)` | 顶栏：标题+搜索图标 ↔ 搜索框（轻量 1/4 宽滑动） |
| `verticalScreenTransition(enabled)` | 仅 fade（无横向滑动） |
| `paneTransition(enabled)` | `depth` 恒为 0 的 directional（仅 fade） |
| `tweenFloat` / `tweenDp` / `tweenColor` / `tweenIntOffset` / `tweenIntSize` | `animate*AsState`、`AnimatedVisibility` 等 |

---

## 六、场景 → 实现映射

| 场景 | 状态 | 实现位置 | 过渡 / Spec |
|------|------|----------|-------------|
| 主页分区切换 | ✅ | `HomeScreen` `AnimatedContent` | `homePaneWithSearchTransition` + `homePaneDepth` |
| 搜索内容区 | ✅ | 同上 | 进入 Search：全宽右推；退出：左滑回 |
| 搜索顶栏 | ✅ | `HomeTopBar` | `topBarSearchTransition`；leading 菜单↔返回 fade Short |
| 搜索统计栏 | ✅ | `HomeScreen` `AnimatedVisibility` | fade + expand/shrink Short |
| 歌手/专辑 Root↔详情 | ✅ | `HomeBrowseContent` | `directionalPaneTransition` + `browseDestinationDepth` |
| 侧栏抽屉 | ✅ | `HomeDrawerPanel` + `HomeScreen` | 左栏滑入 + 主内容整体右移 50% 宽（不缩窄，`drawerPushSpec` Medium）；迷你播放栏不位移；设置项 `bottomInset` 上抬 |
| NavHost 子页（设置/播放/详情/EQ） | ✅ | `AppNavigation` | 进入：fade + **上滑**；返回：fade + **下滑**；Medium |
| 深浅色 / 云母背景 | ✅ | `Theme.kt`、`AnimatedTheme.kt` | 颜色 crossfade Medium |
| 播放页沉浸（下半屏） | ✅ | `NowPlayingScreen` | chrome fade/size Long |
| 播放页封面 lerp | ✅ | `NowPlayingScreen` | `tweenFloat` Long |
| 切歌擦除 | ✅ | `NowPlayingTrackWipe` | `tweenFloat` Medium |
| 列表→播放共享元素 | ⏳ | — | 待做；目标：进入上滑 + 共享元素，返回下滑 |
| BottomSheet / 对话框 | ⏳ | Material 默认 | 待对齐 Medium + 统一 expand |
| 迷你栏展开全屏 | ⏳ | — | 待做 |
| 歌词行切换 / 双语 | ⏳ | — | 规范建议 Long fade（见 §八） |
| 播放指示竖条 | ✅ | `PlayingIndicator` | **独立** 600ms 循环，不走 MicaMotion |
| EQ 拖动 | — | `EqualizerScreen` | 跟手 + 松手回稳（设计稿 200ms，实现待统一） |

图例：✅ 已接 `MicaMotion`；⏳ TODO（见 `docs/TODO.md` §全局·界面动效）。

---

## 七、开发约束（新增 / 修改 UI 时）

### 7.1 必须使用

```kotlin
val motionEnabled = rememberMicaMotionEnabled()
```

### 7.2 `AnimatedContent` 分区栈

1. 为 `targetState` 定义 **depth 函数**（或复用现有 `homePaneDepth` / `browseDestinationDepth`）。
2. `transitionSpec = MicaMotion.directionalPaneTransition(motionEnabled, ::yourDepth)`。
3. 若状态含「搜索 / 全屏 overlay」等**与 depth 无关的进出场**，扩展 `homePaneWithSearchTransition` 模式，或新增命名预设到 `MicaMotion.kt`，**不要**在 Composable 内复制粘贴 slide 公式。

### 7.3 `AnimatedVisibility` / `animate*AsState`

- 使用 `MicaMotion.tweenFloat` / `tweenIntSize` 等，并传入 `motionEnabled`。
- 顶栏、一行控件级：优先 **Short**；整块区域：Medium。

### 7.4 键盘与动效

- 需要「等顶栏动画再聚焦」时：`delay(MicaMotion.DurationShortMs)` 后再 `FocusRequester.requestFocus()`（见 `HomeTopBar` 搜索）。

### 7.5 禁止

- 硬编码 `tween(250)`、`spring()` 作为默认页面过渡（除非 EQ 等专业控件且文档化）。
- 前进用左滑、返回用右滑（与全局方向相反）。
- 在 `motionEnabled == false` 时仍播放 300ms+ 动画。
- 同一导航栈混用 NavHost 默认动画与自定义 slide 且无说明。

---

## 八、与 DESIGN_SPEC §九 的对照（迁移用）

| DESIGN_SPEC（旧） | 现行实现 |
|-------------------|----------|
| 页面切换 200ms | 主页/浏览 **320ms**（Medium） |
| 进入播放页 300ms | Nav 上滑 **320ms**；共享元素未做 |
| 歌词行 400ms | 播放页 chrome **400ms**（Long）；歌词行切换未做 |
| Tab 下划线 250ms | 若实现，建议改为 **Short 200** 或 **Medium 320** 二选一 |
| 列表波形 600ms | 保持独立循环，不入 `MicaMotion` token |

后续更新 `DESIGN_SPEC.md` §九 时，建议改为「见 `docs/MOTION.md`」并删除重复表格。

---

## 九、文件清单

| 文件 | 职责 |
|------|------|
| `ui/motion/MicaMotion.kt` | 常量、预设过渡、tween 工厂 |
| `ui/motion/MicaMotion.kt`（底部） | `rememberReduceMotion` / `rememberMicaMotionEnabled` |
| `MainActivity.kt` | `LocalEnabled` |
| `docs/MOTION.md` | 本规范 |
| `docs/TODO.md` | 动效待办勾选 |

---

## 十、修订记录

| 日期 | 说明 |
|------|------|
| 2026-05 | 初稿：对齐 `MicaMotion`、主页/搜索/浏览/Nav/主题/播放页现状；标注未完成项 |
