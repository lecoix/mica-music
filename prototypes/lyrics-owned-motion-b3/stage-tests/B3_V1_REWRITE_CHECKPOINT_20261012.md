# B3 v1 重新实施 · 阶段检查点

**日期：2026-10-12；状态：IN PROGRESS / NOT MERGEABLE**

## 基线与隔离

- 单一权威行为规范：`prototypes/lyrics-owned-motion-b3/stage-tests/B3_REWRITE_BEHAVIOR_CONTRACT_V1.md`（上游 6dac3fe4，D1–D6 已确认）。
- 重写工作树：`D:\AI\3\mica-lyrics-b3-rewrite-20261012`；分支 `experiment/lyrics-b3-v1-rewrite-20261012`。
- 新源码阶段提交：`f7a5c349149999f15cd58862bda59048bbe7bca7`。
- Mica 主工作树与旧 B2/B3 开发工作树未更改；未合并、未推送、未安装正式包、未删除本地文件。

## 已实施（尚需阶段验收）

- `LyricsScrollAuthority.kt`：FOLLOWING / BROWSING_DRAG / BROWSING_FLING / BROWSING_HOLD / RECOVERING；generation 防过期；D1 手动浏览 4 秒后回当前行；D2 显式 Seek 抢占。
- `NowPlayingLyricsExpanded.kt`：真实 LazyListState 由 UserInput 优先于 Default 程序动画；跟随和间奏补偿改为各自有界的整个 scroll mutation；间奏进入补偿只在有限 ENTERING 事务持有 pin，事务完成释放，不再贯穿整个 12 秒间奏；preInterludeGeometry 按当前可见行限界。
- 视觉风格（D5）和解析与 7 秒判定（D6）不改。D3 点钟相位保持做了初版更改，但**无法视为验收通过**。
- D4 浏览时字体/视口变化保持阅读行和剩余计时仍需专门测试和必要实现完善，不得标记已完成。

## 真实自动化测试结果

第一轮（构建约 12m26s）：
- 原版真 Compose C03 RED->GREEN：原 B3 上方 `-625.7565px`、下方 `0px`，错差 `625.7565px`；重写后上方 `-625.7565px`、下方 `-625.7565px`，错差 **0px**。
- `ExpandedLyricsInterludeIntegrationTest`：9/9 PASS，含 17s/55s 入口、25/60% 锚点、持续稳定及 29/68s 离场。
- `LyricsScrollAuthorityContractTest`：3/3 PASS。
- 合计 13/13 PASS。

第二轮全量定向：**52 运行、50 PASS、2 FAIL**。
- 保留原 45/45 全部 PASS（9+3+9+22+2）；C03 真组件 1/1 PASS；滚动权威 3/3 PASS；D1 真组件 4 秒恢复 1/1 PASS（`origin=461; browsed=11; held=11; recovered=461.9461`）。
- **仅 D3 两项失败**：`LyricsInterludePauseContractTest` 在暂停恢复时相位未推进（`advanced=17832; frozen=17864; resumed=17864`）；暂停时 Seek 17s→22s 返回 17s。
- 额外 2 次单类 D3 重跑均 2 FAIL。加入 Text 和显式 Compose `mutableStateOf` 状态、渲染器采样探针后，`rootRunning=false` / `rootPosition=17000` 显示根 Composable 在后续输入下没有观察到更新。**这说明测试驱动存在尚未解释的重组/时钟边界；不能仅据此判定 D3 生产动画实现正确或错误。**
- 本轮完整 RED、50/52 及重复 D3 结果保留于构建报告；单类 D3 最终 XML 拷贝入本文件夹 `D3_COMPONENT_CLOCK_OPEN_20261012.xml`。

## 三层契约判定

**Behavior Contract**：手势间奏期间整列跟手、间奏自动播放不额外漂移、D1 4 秒恢复等目前有真实组件证据。D3 暂停相位测试尚未建立可靠的通路，D4 浏览时重排、长时间/极端操作及真实音轨仍欠验。

**Architecture Contract**：核心滚动控制权与一次性间奏事务已拆开并初步测试；仍需证明浏览/Seek异步回调不能跨 generation 乱序提交，以及页面重建、容量和真实交互边界。

**Implementation Verification**：曾运行 52 项 50/52，不允许把原有 45 项冒充 75 个 P0 场景；新 172 项台账不能提前标为通过。

## 下一轮不应跳过的执行顺序

1. 建立可靠可重组的 D3 受控测试输入 seam（也可用生产 `InterludeDots` debug QA 活动和真实设备补充），查清恢复/暂停Seek未反应原因，RED→GREEN。
2. 补 D4 真实组件布局重排时**阅读行身份和4秒剩余时长**验证并完成必要实现。
3. 再执行 52 项完整回归及新 P0，检查双向手势、手势和间奏同帧、Seek 中断、页面/窗口生命周期。
4. 只有满足上述门禁，才构建 `com.mica.music.b3v1qa` 独立 debug QA APK，在 Android12 上与旧 `com.mica.music.b3fixqa` 同机对照。**不要更新正式版应用**。
5. 阶段P0真机通过后继续其余172场景矩阵。未通过前不合并或推送。

以上为检查点，不表示重写完成。
