# 播放页 UI 契约

> 播放页逻辑重写后的边界说明。`PlayerController` / 媒体服务 / 队列语义 **未修改**。

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
| `setSeekUiActive` | 拖动时钉住 ALAC/通知进度 |
| `playQueueIndex` / `moveQueueItem` / `removeQueueItem` | 队列 Sheet |
| `cyclePlaybackQueueMode` | 播放模式循环 |
| `toggleImmersiveLower` | 沉浸模式 |
| `insertPlayNext` / `setQueue` | 长按菜单 |

## 封面手势 → Controller

`rememberCoverGestureState`（[`CoverGestureCoordinator.kt`](../app/src/main/java/com/mica/music/ui/screens/player/CoverGestureCoordinator.kt)）在松手过阈值时调用：

- Cover Flow：`onPlayQueueIndex(target)` 或 `onNext()` / `onPrevious()`
- 标准主题轻扫：`onPrevious()` / `onNext()`

不新增 Controller API；切歌动画监听 `currentIndex` 变化。

### Cover Flow 位移状态

| 状态 | 含义 |
|------|------|
| `centerAnchorIndex` | Lane 池整数锚点；`laneBindings` 的 `queueIndex = anchor + laneOffset` |
| `laneFraction` | 相对锚点的浮点偏移；拖动与切歌补间共用 |
| `virtualCenterIndex` | `anchor + laneFraction`；驱动 `CoverFlowMath.slotTranslation` |

`currentIndex` 变化且相邻切歌（`|Δ| = 1`）时：`centerAnchor` **保持旧值**，`laneFraction` 从当前值动画到 `Δ`；结束后 `anchor ← currentIndex`、`laneFraction ← 0`。详见 [COVER_FLOW_LANE_POOL.md §4.2.1](COVER_FLOW_LANE_POOL.md#421-滑动切歌跳变根因与正确时序2026-06-已修)。

## 布局

- `PlayerPageLayoutEngine.computeFrame()` — 单帧原子布局
- `PlayerPageFrame` — 封面区 + 下半屏 chrome 的全部几何与 alpha
