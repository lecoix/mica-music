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

| 模式 | 实现 | 行为 |
|------|------|------|
| **封面流**（平行 / 复古） | [`CoverFlowCarouselView`](../app/src/main/java/com/mica/music/ui/screens/player/view/CoverFlowCarouselView.kt) | 拖动跟手；松手超阈值 → `onPlayQueueIndex` / `onNext` / `onPrevious`；点击侧槽 → `onPlayQueueIndex` |
| **标准主题** | [`CoverGestureCoordinator.kt`](../app/src/main/java/com/mica/music/ui/screens/player/CoverGestureCoordinator.kt) | 横向轻扫 → `onPrevious` / `onNext` |

不新增 Controller API。封面流切歌动画由 View 监听 `currentIndex`（经 `CoverFlowCarouselHost.update` → `updateCurrentIndex`）驱动，详见 [`COVER_FLOW_IMPLEMENTATION.md`](COVER_FLOW_IMPLEMENTATION.md) §4。

## 布局

- `PlayerPageLayoutEngine.computeFrame()` — 单帧原子布局
- `PlayerPageFrame` — 封面区 + 下半屏 chrome 的全部几何与 alpha
