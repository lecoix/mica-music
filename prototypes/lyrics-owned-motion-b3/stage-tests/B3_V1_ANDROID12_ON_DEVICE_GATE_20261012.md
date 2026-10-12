# B3 V1 · Android 12 真实设备分阶段验收

日期：2026-10-12（AEDT）；状态：**受控页面部分 P0 场景 GREEN，未完成全量 P0，不允许合并**。

## 基线/设备/方法

- 独立分支 `experiment/lyrics-b3-v1-rewrite-20261012`，受测源码提交 `f3504942`；主 Mica 未变更。
- 测试机：Android 12，机型 `22081212C`，屏幕 `1220×2712`，density `480`。
- 真正运行包：`com.mica.music.b3v1qa`，实际前台 `com.mica.music.b3v1qa/com.mica.music.ui.screens.B3LyricsVisualQaActivity`，由设备 `dumpsys activity` 核实。受控页面的文字仍显示通用旧标题 “B3 FIX QA”，**仅 UI 标签旧，不能用标题判别实际应用版本**。
- APK SHA256：`76de16ed022e2b2c0d9708d99c09fd67eba33bee7812f64e04519d27455a83d0`，PC 构建副本和设备副本一致。
- 使用已获授权的测试机 root shell `am start`、`input swipe/tap`、`screencap` 实施**真实用户可见 UI**操作；不是把 Robolectric 或假日志当成真机。使用 FFmpeg 解码截图与 NumPy 按真实 1220×2712 像素计算文本区位置/二值区域 IoU，不使用 OCR。仅操作隔离 QA 包，无数据删除、未修改已安装的正式版、未改系统分辨率。
- 原始截图保留在测试机 Termux `/data/data/com.termux/files/home/` 下，以 `b3v1qa-*.png` 与 `b3-oldqa-*.png` 为前缀；未清理任何文件。

## 已验证

| 真实设备场景 | 输入 / 独立观测 | 判定 |
|---|---|---|
| B01 物理锚点 | 13s 当前行在 25% 与 60% 锚点间移动；真机 QA 实测首屏定位；源码 Compose 测试已检查行中心对应物理视口 | PASS（本次部分可视检查） |
| B02 17s 间奏插入 | PLAY 从 13s 穿过 17s，截图时间 15s、16s、18s、19s；下方首行在屏幕 `x180..1120, y1960..2240` 的二值文本区域 IoU **1.0**，最大匹配平移 **0px** | PASS |
| B02 55s 第二间奏 | 从 50s 播放至截图 53s 与 56s；第二次间奏后首行在 `x120..1120,y1240..1450` 区域二值文本 IoU **1.0**，最佳平移 **0px** | PASS |
| D1 暂停手动浏览、停留和4s回收 | 19s 暂停、60%锚点、手指向上滑。当前亮色歌词 top：`1806→1102→1102（hold）→1805（return）→1806（settle）`；不提前夺走用户控制 | PASS |
| D2 浏览时显式 Seek 抢占 | 手动浏览后直接 Seek 60s 到第二间奏。旧阅读行顶部约1171px，Seek目标行定位到1805px；约4.8秒后仍1805px，无旧计时器乱入 | PASS |
| D2 连续快速 Seek | 真实按钮触发 `12s→36s→15s→69s→21s`；最终页面21s、当前歌词正确；等待约4.5秒目标行位置仅小于3px的动画/渲染收敛，没有被旧命令重定位 | PASS |
| D3 第二间奏暂停/恢复 | 在60s开始播放后暂停，间隔约2秒的两张截图三点区域`x530..690,y1190..1330`像素变化数为**0**；恢复播放后相对暂停画面变化**862像素** | PASS |
| D4 浏览中锚点重排 | 60s第二间奏，暂停手势下滑后点60%→25%。当前亮色歌词 top `2174→2174（reflow）→2174（hold）→1081（return）`；先保持阅读位置，超时才返回新锚点 | PASS |
| C05 间奏播放时用户上滑 | PLAY 状态 21→22s 的三点间奏内滑动；当前亮色歌词 top `1080→681`（**-399px**），下方“间奏之后”整行二值文本最佳匹配 `-399px` 且 IoU **1.0** | PASS |

### 与旧 B3 同机对照（区别与相同点均如实记录）

- 同包并存：旧 `com.mica.music.b3qa` 的同名 debug 受控页面，以独立前台 `com.mica.music.b3qa/com.mica.music.ui.screens.B3LyricsVisualQaActivity` 运行，不影响新包。
- 旧 B3 在同一 **17s间奏静态插入测试** 下，下方首行位移也测得 **0px / IoU 1.0**。因此这一条**不能作为 B3 V1 相比旧 B3 的提升证据**，也不能证明其他边界无漂移。
- 同一19s间奏暂停、60%锚点、上滑并等待超过5秒：
  - **旧 B3** 当前歌词 top：`1721→1041→1041`，等待后仍不自动回到当前行。
  - **B3 V1** 当前歌词 top：`1806→1102→1806`，按明确的4秒协议恢复。
  - 这一次同机对照能够区分两版的 D1 浏览恢复能力。

## 证据位置

测试机 Termux 工作目录：
- `b3v1qa-17gap-pre17/post17/post18/post19.png`，`b3v1qa-55gap-pre/post.png`
- `b3v1qa-d1-base/browse/hold/return/settle.png`
- `b3v1qa-d2-browse/seek/after-deadline.png`
- `b3v1qa-d3-paused-a/paused-b/resumed/paused-c.png`
- `b3v1qa-d4-browse/reflow/hold/return/settle.png`
- `b3v1qa-rapidseek-final/after.png`
- `b3v1qa-c05-playing-base/playing-swipe/paused-after-swipe.png`
- 旧版 `b3-oldqa-17gap-pre/post.png`，`b3-oldqa-d1-base/browse/hold/settle.png`

## 不得过度推断的边界

- 本次是**真实设备+真实生产 ExpandedLyricsPanel 组件，但输入歌词与播放时间由 debug QA 页面控制**；不等于真实音频文件/播放器页/MediaSession 端到端测试。
- 只覆盖 **25%/60% 两个锚点、受控固定字号34sp、短/长句样本**。字号动态变化及窗口650→500dp由另一个真实 Compose 组件测试通过，**本轮 Android 12 QA 页面没有字号/旋转/分屏控制，不能声称这些已在真机通过**。
- 没有走完 `SCENARIO_LEDGER.csv` 的172个场景，尤其是首次打开、旧动效未结束时插入、甩动惯性、双向交错、间奏退出29/68s、后台/页面重建、点击命中区域、大批量歌词。不得把部分 GREEN 冒充整套 P0 GREEN。
- `SCENARIO_LEDGER.csv` 当前含旧 B3 `30fe2855` 失败记录；此报告是**另一个版本的新证据**。旧记录暂不覆盖/删除，待统一跨修订台账时再迁移。
- 原始用户音乐与正式 Mica `com.mica.music` 均未更改。独立包可保留用于后续对照；阶段尚不可合并主分支、不可推正式版本。
