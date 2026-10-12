# B3 v1 重写 · 晚间会话恢复与测试回退检查点

日期：2026-10-12 晚间（澳大利亚东部时间）。
**状态：IN PROGRESS / P0 BLOCKED；本检查点是诊断与安全回退，并非验收完成。**

## 会话/仓库

- 工作树：`D:\AI\3\mica-lyrics-b3-rewrite-20261012`，分支 `experiment/lyrics-b3-v1-rewrite-20261012`。
- 本轮重新访问时，上一轮 `session-6d9acef94efb37b866fec944` 返回 SESSION_NOT_FOUND，不能声称旧会话仍在运行或已成功。
- 本地既有提交仍为 `963bb7d9`，原 B3 分支 `experiment/lyrics-owned-motion-b3-20261011` 未触碰。
- 重新连接时已有**未提交**的真实测量/坐标修正尝试与测试：`NowPlayingLyricsExpanded.kt`、`ExpandedLyricsMotionTelemetry.kt`、`NowPlayingLyricsExpandedTest.kt`、`LyricsInterludePauseContractTest.kt`、`ExpandedLyricsReflowBrowseContractTest.kt`；时间戳显示来自此前工作窗口，本轮没有覆盖或重置这些修改。
- 本轮未删除本地文件、未修改 Mica 主线/正式 APK、未合并或推送。

## 可核实的通过

- D3 单独修正真实 Compose 按钮事件后的两项测试（此前报告）确实通过：相位 `17832→17880 (pause)→18152`；暂停 Seek 目标 `22000ms` 生效。
- 原早期重写阶段通过了 45/45 旧定向 + C03 手势同列位移 0px + D1 4秒浏览恢复组件测（此前检查点）。这些成绩只对原当时的源码/测试环境有效，**不得自动继承到仍未提交的后续坐标修改上**。

## 当晚证实的阻断问题

1. 晚间首次定位和 D4 `ExpandedLyricsReflowBrowseContractTest` 仍在初始当前歌词行的测量处失败：`Initial playback line must be measured`。日志表明目标 index=8，而 2256px 实际 viewport 的 `visibleItemsInfo` 最终仅列到 index=7；已有程序滚动消费约 `-1202.6001px`，因此还没到后续字体/锚点重排断言。
2. 这是 **B-01 首次歌词定位** 的比 D4 更早的基础门禁，D4 不能判定通过。
3. 本轮实验将巨大 `contentPadding.bottom` 改为尾部 `Spacer`，希望避免 `LazyListLayoutInfo.viewportEndOffset` 缩小；源码编译通过，但定向复测**反而出现五个失败**：
   - D4 初始化歌词行仍未测量；
   - `ExpandedLyricsInterludeIntegrationTest` 9项中3项失败（两项 next lyric 未测量，另一项 55–68s 间奏位移达 484px）；
   - `ExpandedLyricsStage1GestureTest` 缺失上下行同时测量，无法形成有效手势差断言；
   - D3 2/2通过，`NowPlayingLyricsExpandedTest` 22/22通过。
4. 因存在明确回归，当场把刚才增加的 **`Spacer`/尾部 padding 改法** 三处文本修改精确恢复到本轮开始时的状态。**回退后尚未再执行编译/回归，不得把实验失败视作回退版本也失败或已经恢复绿色。** 保留 XML 失败证据，不使用 git clean/reset 或永久删除命令。

## 证据（保留为历史 RED，而非当前源状态）

- `D4_EVENING_FIRST_SNAP_RED_20261012.xml`
- `INTERLUDE_EVENING_SPACER_REJECTED_20261012.xml`
- `GESTURE_EVENING_SPACER_REJECTED_20261012.xml`
- 先前 `B01_INITIAL_PHYSICAL_ANCHOR_RED_20261012.xml`、`D4_PHYSICAL_VIEWPORT_RED_20261012.xml`、`D4_FALSE_ANCHOR_GREEN_20261012.xml` 已存在，未更改。

## 下一窗口应优先做什么

1. **不要先叠加更多 UI 改动。** 读取当前真实布局/时间轴采样、`viewportStartOffset`/`viewportEndOffset`/`viewportSize`、`beforeContentPadding`/`afterContentPadding`、`scrollToItem` 后真实 item 坐标，明确“index8缺席”究竟是 Lazy 虚拟化、滚动修正过头、定位事务乱序，还是首次快照采样时机。
2. 首先单独建立可失败的 **B-01 初次 snap 测得当前行且 anchor 正确** 测试；测试仍 RED 时禁止继续 D4 的阅读保持逻辑。
3. 在保留当前原始 `contentPadding` 的前提下找最小安全修正，之后原 C03 与 17/55s 间奏、D1/D2/D3 一起回归。
4. B-01 通过后再恢复 D4 严格测试（读者正在看的一行、修改字号/锚点、保持约4秒剩余计时）；D4 本身尚无 GREEN 证据。
5. 通过真实组件后才能走 `com.mica.music.b3v1qa` 独立 Android12 真机对照。原172场景 P0 尚远未关闭。

严格区分 Behavior Contract、Architecture Contract 与 Implementation Verification。测试不通过仍阻止合并。
