# 歌词逐字注音对应

2026-10-25 实现；已通过下述代码与测试检查，未做真机验收。用户要求：优先使用文件中的对应关系，不能确定时保留整行读音，不猜测。

## 数据与回退

- `LyricTextPart.ruby` 保存原文中 UTF-16 的 `[start, endExclusive)` 和读音。一个范围可以包含一个或多个字，读音长度不限于一个音节；不会按字符数量拆音。
- TTML `tts:ruby="container"` 内一个 `base` / `baseContainer` 与一个 `text` / `textContainer` 建立明确对应。注音不会混入原文。注音分音节时间和 base 自带的逐字时间继续保留。
- 独立的 `x-roman` 或 Apple `transliterations` 仅在原始文件提供的原文/读音起止时间严格相同且唯一时建立对应。缺结束时间、重叠/重复区间、只有整行读音或数量刚好相等，都不足以推断对应；不会模糊匹配、均分读音或生成读音。
- 匹配发生在 parser 推导缺失 token end 之前；保存为范围后 UI 不再用推导时间重新匹配旧数据。旧 JSON 缺 `ruby` 时仍显示整行，重新扫描后取得新信息；可选 JSON 字段向后兼容，无需改 Room schema。local parser 代次 14，remote lyrics cache 代次 4。
- 只有全部读音都被明确覆盖时才合并读音行。非法/越界/重叠范围或文本清理改变了范围依据时放弃对齐，保留原文与读音。
- 读音开关与原文/译文展示偏好继续生效。超过 4,096 UTF-16 单元或包含硬换行的原文保留整行，限制按单元排版的对象数量。
- 2026-10-26 用户明确调整：手动选择“仅原文”时只显示原文字句，同时隐藏 Ruby 注音、整行读音和译文；已知/未知对应及开启/关闭分行均适用。应用内经典/紧凑/横屏、歌词云、信笺及桌面/状态栏共用此语义。“全部”仍遵循读音开关；状态栏逐字模式自动隐藏译文不等于手动选择“仅原文”，仍可显示注音。该调整只改变同步显示过滤，无新增 IO、发布入口或缓存，不增加曲库容量开销。
- “仅原文”调整定向回归：73 项通过、0 跳过、0 失败，涵盖应用内共用分行、信笺、歌词云及外部投影；已有回归方法补充了已知/未知对应、分行开/关与状态栏自动隐藏译文的区别。日志 `.scratch/lyrics-original-only.log`；此后未重跑 Lint 或真机测试。

## 显示

经典列表、紧凑歌词和横屏歌词共用 `LyricLineBlock`。`RubyLyricText` 将原字/字词和读音作为不可拆分单元，单元宽度取两者较大值，上下居中；同一视觉行的原文字体基线保持一致。注音为原文字号的一半。正文按现有时间轴填充，音节抬升时整组移动，长行在单元之间换行。提供包含原文及读音的一份无障碍语义。

歌词云使用同一个测量/绘制实现，packing 将注音高度计入。信笺竖排把整段读音画在对应字词右侧，字词组不跨列；读音过长时在组高度内缩放。组长超过一列容量、或旋转 Latin 列不能承载该对应时，保留独立整列读音。

系统通知、车载等独立文本输出继续使用既有整行投影。桌面与状态栏悬浮窗的后续接入见下节。

## 2026-10-26：桌面与状态栏悬浮窗（工作树实现）

- `buildExternalLyricsLine` 将已经确认的 `LyricRuby` 范围附到当前句 `ExternalLyricsText.ruby`，不解析整库、不重新匹配时间。依据已确立的 SPLIT/语言选择独立契约（`LYRICS_SLASH_ROLE_CONTRACT.md` Phase 3/4），真实结构化 ORIGINAL / TRANSLATION / READING 不随 SPLIT 开关合并；有效 Ruby 仅附于原文，译文换行不影响原文范围。原文被拆分/清理到范围依据不成立或含硬换行时保留原文及整行读音。
- `forExternalDisplay` 在最终展示边界应用全局读音开关和原文/译文选择。只有有效范围与完整读音匹配时才隐藏独立 READING 行；关闭读音同时清除 inline 范围，不需要等待重新加载。无法匹配的读音继续整行展示。
- 状态栏生产分支原先硬编码 `readingEnabled=false`；本次按用户要求改为遵循全局读音开关，与桌面一致。不新增设置 key。
- `ExternalRubyLyricsText` 复用应用内 `measureRubyText` 的分组测量：读音为正文半号，字词与注音一起排版。当前句只有一个 Canvas、一个原生 `basicMarquee`；阴影、发光、未填充颜色/透明度和已填充渐变统一在同一几何上绘制，正文与读音一起填充、一起滚动。现有无范围的普通文字绘制路径保持原状。
- 注音的额外高度以及阴影/发光留白计入 `WRAP_CONTENT` 窗口，避免在 y=0 的状态栏窗口里从顶部裁掉读音。窗口位置、宽度、锁定与穿透交互继续由原设置控制；本次没有新增自动避让刘海或系统图标的算法。
- 布局只在文字/范围、字体或密度变化时重新测量，播放位置与颜色/效果变化只更新绘制。桌面和状态栏共用同一当前句对象；模式切换的短暂退出动画最多额外保留上一句的布局，不随 10,000 首曲库增加对象数量。原文范围路径限 4,096 UTF-16 单元；仍受当前歌词文档及 16 MiB 原有歌词缓存预算约束。没有新增扫描、持久化或全库复制。

### 外部发布协议审计

| owner / 等待点 | 副作用与同步点 | 本次覆盖 |
|---|---|---|
| `NotificationLyricsCoordinator.generation`；歌词加载和 Handler 投递 | 加载回调回到 player looper 后检查 `released`、generation、`pendingSpec`；原 looper 串行发布 `DesktopLyricsOverlayStateStore`。本次只增加同步的范围投影，未新增等待或旁路发布 | `oldNonCancellableLoadCannotOverwriteNewStatusBarRuby` 让旧加载停在不可取消等待点，先切歌并发布新读音，再释放旧加载；断言实际状态栏 snapshot 仍为新读音 |
| `release` 推进 generation / 清理发布 | 原 owner 清空外部当前句；旧回调不能恢复已清空的桌面/状态栏 snapshot | `releasedCoordinatorCannotRepublishLateDesktopRuby` 先完成 IO 并将实际回调排入 Handler，再 release，最后放行 Handler；任务取消无法撤回已排队回调 |
| Compose 当前句局部测量 | `remember` 以当前文本、范围、字体/密度持有一份同步布局；跑马灯共用一个父绘制面，无新增共享异步状态 | 透明度/seek 像素测试、跑马灯的读音/正文位移一致性测试和桌面/状态栏截图 |

为了确定性交错测试，coordinator 增加可注入的歌词 dispatcher，生产默认仍为 IO；generation owner、等待点和发布顺序没有变化。仅 READING 有逐字时间时，沿用现有 100 ms anchor 更新入口，避免其进度在 seek 后丢失锚点；没有新增轮询器。

## 副作用协议核对

本次新增的 parser、codec、对应计算与渲染均为同步计算，无新增 IO/await、写入入口或后台任务。

| 流程 | token owner / 等待点 | 实际副作用与同步 seam | 验证 |
|---|---|---|---|
| 本地扫描歌词 | 现有 `LibraryOperationAuthority.scanGeneration`；探测/解析后与批次提交前继续由 `LibraryScanEngine` 复验 | 原有 `MusicLibraryBacking.storeSyncMutex` 串行 Room batch；随后原有缓存失效，局部结果不发布整库 snapshot | `clearLibraryDoesNotAllowAnInFlightLyricsBatchToRepopulateTheClearedStore` |
| runtime lyrics cache | `LyricsCacheCoordinator` 的每歌 content generation；加载返回后锁内复验 | 同一 `lock` 下的缓存发布/失效；不新增共享 owner | `oldRubyLoadCannotReplaceANewerPublishedReading`：旧加载停住，失效后发布新读音，再释放旧加载，最终仍为新读音 |
| 持久化载荷 | codec 无等待点；原有仓储/扫描 seam 不变 | Ruby 随同一 `song_lyrics` JSON 写入和读取 | `sourceRubySurvivesRoomLyricsBatchAndReload`，包含真实 Room roundtrip |
| UI 测量 | 当前 Composable / 当前歌曲；同步测量，无新等待点 | 可见行的一份布局缓存；cloud 原有预热/缓存协议保持不变 | `RubyLyricTextTest` 的字体缩放、组换行、回退和截图 |

## 10,000 首 / 完整逐字歌词 / 8 GB 基线

- 没有新增整库歌词读取、解析或常驻映射；启动、排序和浏览仍使用原有轻量目录。原有 DEVICE 歌词扫描批次为 6 首，解析仍受单文档 2,000,000 字符、5,000 行和 50,000 cues 上限约束；Ruby 范围也计入 cue 总额。
- 字节预算的缓存仍为 16 MiB、最多 12 首；每个 Ruby 对象及其读音字符串按 `96 + 2 × reading.length` 字节计入保守估算，不会漏计新增载荷。`rubyPayloadCountsTowardTheCacheBudgetAcrossTenThousandSongs` 连续访问一万首，验证大注音载荷被逐出而非累积保留。
- 可见水平行只保留当前几何结果及最多 64 个 TextMeasurer 条目；换行范围不随播放时钟重建。信笺仍只为当前歌曲建页，注音布局与同一页数据共同释放。容量随当前歌曲/有界缓存增长，不乘以一万首。
- 这些是代码层面的上界与回归证据；没有测量 8 GB 真机的进程峰值、滚动帧率或全部主题/字体组合，不能据此宣称全部设备性能已验收。

## 2026-10-25 应用内验证

- 最终 `testDebugUnitTest` 定向执行 102 项：101 通过、1 跳过、0 失败。跳过的是需要本地歌曲样本的 `realCoralSeaTtmlFixtureKeepsItsWordTimelineWhenAvailable`，不计作通过。
- `lintDebug` 通过：0 errors / 376 warnings；`compileDebugAndroidTestKotlin` 通过。没有执行设备端 instrumentation 或实际安装。
- `RubyLyricTextTest` 截图已人工检查：`app/build/reports/lyrics-ruby-preview.png`（对齐、窄屏换行、整行回退）及 `lyrics-ruby-themes.png`（信笺与歌词云）。截图复核修正了竖排注音继承正文行高造成的过度缩小。
- 新增 13 个测试方法的分类已补入 manifest 与手工分类规则；本次只核对相关条目，未替其他未提交工作重新生成全仓分类。
- 最终日志：`.scratch/lyrics-ruby-final.log`；容量测试先失败后修复的证据：`.scratch/lyrics-ruby-capacity-red.log`。

## 2026-10-26 外部歌词验证

- 最终 `testDebugUnitTest` 定向执行 74 项：74 通过、0 跳过、0 失败，包含应用内 Ruby 与歌词缓存回归。
- `lintDebug` 通过：0 errors / 376 warnings；`compileDebugAndroidTestKotlin` 通过。最终质量门为 `.scratch/external-ruby-final-release-callback.log`。
- `ExternalLyricsRubyProjectionTest` 覆盖源范围、读音开关、未知/部分/非法对应整行回退、首尾空白清理后的下标失效、原文硬换行回退、结构化原译文与 Ruby 不受 SPLIT 开关影响（含译文换行）、同文字换读音的快照相等性及只有读音有时间轴时的 position anchor。空白下标用例先复现错误合并，再通过禁止复用已改写原文的范围修复。
- `ExternalLyricsRubyPublicationTest` 使用真实 coordinator、Handler 与状态仓库验证旧加载不可覆盖新读音及 release 后不得复活输出；仅注入播放器事实与可控 IO dispatcher，没有手工替换 generation 判断。
- `ExternalRubyLyricsRenderingTest` 使用原生 Robolectric 图形及项目已有的 Roborazzi 截图通道，断言读音与原文的填充/未填充透明度、seek 回退与跑马灯位移；截图 `app/build/reports/external-ruby-preview.png` 已人工检查小字号、字体放大、居左/居右、渐变、阴影/发光和整行回退。
- 新增 Android 设备测试使用真实 `DesktopLyricsOverlayService` / `WindowManager`，限定 `com.mica.music.qa`，以合成 TTML 验证两种窗口及暂停隐藏，并在 finally 恢复相关 QA 偏好。测试已编译、QA APK 构建成功；**本轮没有实际执行**：连接的 Android 12 设备拒绝首次 QA 安装，返回 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。随后未继续安装、未授予悬浮窗权限；原测试 APK 未被替换，QA 包没有出现在已安装列表中。
- 因此不能宣称真实状态栏顶部/刘海、OEM 窗口管理、实际帧率或 8 GB 真机峰值已验收。主机截图与缓存/当前句对象的代码上界不能替代这些证据。
- 新增 12 个测试方法均已核对 manifest 与手工分类规则：10 个行为契约、2 个实现验证；仅核对本次条目，没有重生成其他未提交工作的全仓分类。
- 日志：`.scratch/external-ruby-red.log`（实现前编译缺失字段）、`.scratch/external-ruby-check.log`（第一轮回归）、`.scratch/external-ruby-visual.log`（像素/跑马灯验证）、`.scratch/external-ruby-offset-red.log`（空白下标回归修复前失败）、`.scratch/external-ruby-final-verified.log`（空白修复后回归）、`.scratch/external-ruby-final-release-callback.log`（加强已排队回调交错用例后的最终质量门）、`.scratch/external-ruby-device-build.log`（隔离 APK 构建）。

## 参考项目

参考本地 Halcyon `0381f67` 的逐字上下排版与 timed furigana 测试，以及 [AMLL TTML Ruby 语义](https://amll.dev/en/guides/lyric/ttml#ruby-annotation)。Mica 使用自有数据范围、严格时间匹配及 Compose 绘制实现，没有引入 AMLL 依赖或复制其渲染代码，也没有移植 Halcyon 的假名锚点/连续汉字猜分算法。
