# B3 v1 · 物理锚点、D4 重排与独立 QA 阶段验证（2026-10-12）

状态：**源码定向测试 GREEN / 真机验证未完成 / 不可合并**。

## 变更范围与行为契约

- 独立工作树：`D:\AI\3\mica-lyrics-b3-rewrite-20261012`，分支 `experiment/lyrics-b3-v1-rewrite-20261012`。
- B-01：修正 LazyColumn 的 `viewportStartOffset` / `viewportEndOffset` 与物理视口之间的几何坐标混用。首次 snap 先将当前行变为真实可测量条目，再按**当前行中心**修正到用户指定的物理锚点；不把巨大 content padding 擅自替换成 Spacer。
- D4：`BROWSING_HOLD` 期间字号、翻译字号、行距、锚点、对齐或 viewport 尺寸重排时，按真实测量、稳定歌词行 key 和旧的可见位置收据修正列表滚动；复用原 gesture generation，不重启 4000ms 浏览恢复计时。Seek / 新手势使旧收据失效。
- D3：生产交互的 Pause、Resume、暂停 Seek 使用真实 Compose 按钮输入验证；D5 经典样式与 D6 解析时间轴保留。
- B-02 / C03：继续以独立手势和间奏集成测试校验间奏插入时的整列位移、间奏后首行不发生额外漂移。55秒段单独排除 Seek 进入事务的初始定位过程，持续段从 56.5 秒开始断言，另新增 Seek 事务稳定性测试。
- 调试收据新增滚动模式、物理 viewport、padding 和真实根坐标，确保断言不是把 Lazy 虚拟坐标误认为屏幕物理坐标。

## 运行记录（Windows / Robolectric 实际执行）

1. `ExpandedLyricsReflowBrowseContractTest` 原始单测：**1/1 PASS**，Gradle 2m19s（21:07–21:10 AEDT）；首句物理60%锚点、手动浏览、字号34→48及当前行锚点60%→25%、原4000ms计时恢复。
2. B3 九个相关测试类组合回归：**52/52 PASS，0 failures**，Gradle 1m02s（21:10–21:11 AEDT）；含初始 snap、C03、D1、D2、D3、17/55秒间奏、D4、经典位置动画等。
3. 额外新增真实 viewport 变化场景，再跑 `ExpandedLyricsReflowBrowseContractTest`：**2/2 PASS**，Gradle 1m14s（21:16–21:17 AEDT）。窗口高度650dp→500dp时同一歌词行 `line-8` 从 Y=657px 到 Y=657px，滚动模式保持 `BROWSING_HOLD`；原恢复定时结束后重新按60%锚点跟随。
4. `git diff --check` 无输出；未修改其它工作树、未推主分支。

以上 52 项与新增单测合起来覆盖 **53 个不同测试**；不是声称 172 个业务场景全部完成，也不是全应用测试已通过。

## 独立真机 QA 交付边界

- `:app:assembleDebug --project-prop=mica.qaSideBySide=true --project-prop=mica.qaApplicationId=com.mica.music.b3v1qa`：**BUILD SUCCESSFUL**，3m32s，21:23 AEDT。
- `aapt dump badging` 确认 APK 的 `applicationId=com.mica.music.b3v1qa`，`versionName=0.4.3-qa`，大小 **40,686,354 bytes**。
- Windows APK 与 Android12 / 22081212C 测试机 Termux 下载副本 SHA-256 均为 `76de16ed022e2b2c0d9708d99c09fd67eba33bee7812f64e04519d27455a83d0`。
- PC 和手机均无 ADB 已连接设备；通过局域网 HTTP 一次性传输，传输后已停止临时 HTTP 服务；Android 安装 Intent 已触发（命令返回0），**包管理器尚未报告 b3v1qa 已安装**。需要系统安装界面确认后才能验收，不能声称真机通过。
- 原正式包 `com.mica.music`、B2 `com.mica.music.b2qa`、原 B3 `com.mica.music.b3qa`、修补版 `com.mica.music.b3fixqa` 未覆盖、未卸载。

## 尚需门禁

- 确认独立 QA 包安装后，生产 ExpandedLyricsPanel 真机逐阶段测 D1–D4：中途间奏、正反向手势、4秒恢复、反复 Seek、暂停相位、字体/锚点/窗口变化；与原 B3 修补包同机录屏对比。
- 继续按 `SCENARIO_LEDGER.csv` 推进尚未覆盖的 P0 和其余场景，并检查旋转、页面重建、极端字体及歌词数据源。
- 现阶段 **禁止合并、推送主分支或替换正式 APK**；历史 RED XML 保留在工作树中，不删除。
