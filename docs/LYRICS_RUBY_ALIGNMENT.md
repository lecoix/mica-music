# 歌词逐字注音对应

2026-10-25 实现；已通过下述代码与测试检查，未做真机验收。用户要求：优先使用文件中的对应关系，不能确定时保留整行读音，不猜测。

## 数据与回退

- `LyricTextPart.ruby` 保存原文中 UTF-16 的 `[start, endExclusive)` 和读音。一个范围可以包含一个或多个字，读音长度不限于一个音节；不会按字符数量拆音。
- TTML `tts:ruby="container"` 内一个 `base` / `baseContainer` 与一个 `text` / `textContainer` 建立明确对应。注音不会混入原文。注音分音节时间和 base 自带的逐字时间继续保留。
- 独立的 `x-roman` 或 Apple `transliterations` 仅在原始文件提供的原文/读音起止时间严格相同且唯一时建立对应。缺结束时间、重叠/重复区间、只有整行读音或数量刚好相等，都不足以推断对应；不会模糊匹配、均分读音或生成读音。
- 匹配发生在 parser 推导缺失 token end 之前；保存为范围后 UI 不再用推导时间重新匹配旧数据。旧 JSON 缺 `ruby` 时仍显示整行，重新扫描后取得新信息；可选 JSON 字段向后兼容，无需改 Room schema。local parser 代次 14，remote lyrics cache 代次 4。
- 只有全部读音都被明确覆盖时才合并读音行。非法/越界/重叠范围或文本清理改变了范围依据时放弃对齐，保留原文与读音。
- 读音开关与原文/译文展示偏好继续生效。超过 4,096 UTF-16 单元或包含硬换行的原文保留整行，限制按单元排版的对象数量。

## 显示

经典列表、紧凑歌词和横屏歌词共用 `LyricLineBlock`。`RubyLyricText` 将原字/字词和读音作为不可拆分单元，单元宽度取两者较大值，上下居中；同一视觉行的原文字体基线保持一致。注音为原文字号的一半。正文按现有时间轴填充，音节抬升时整组移动，长行在单元之间换行。提供包含原文及读音的一份无障碍语义。

歌词云使用同一个测量/绘制实现，packing 将注音高度计入。信笺竖排把整段读音画在对应字词右侧，字词组不跨列；读音过长时在组高度内缩放。组长超过一列容量、或旋转 Latin 列不能承载该对应时，保留独立整列读音。

通知、状态栏、桌面外部歌词等独立文本输出继续使用既有整行投影；本次没有向这些输出协议添加 Ruby 排版。

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

## 本次验证

- 最终 `testDebugUnitTest` 定向执行 102 项：101 通过、1 跳过、0 失败。跳过的是需要本地歌曲样本的 `realCoralSeaTtmlFixtureKeepsItsWordTimelineWhenAvailable`，不计作通过。
- `lintDebug` 通过：0 errors / 376 warnings；`compileDebugAndroidTestKotlin` 通过。没有执行设备端 instrumentation 或实际安装。
- `RubyLyricTextTest` 截图已人工检查：`app/build/reports/lyrics-ruby-preview.png`（对齐、窄屏换行、整行回退）及 `lyrics-ruby-themes.png`（信笺与歌词云）。截图复核修正了竖排注音继承正文行高造成的过度缩小。
- 新增 13 个测试方法的分类已补入 manifest 与手工分类规则；本次只核对相关条目，未替其他未提交工作重新生成全仓分类。
- 最终日志：`.scratch/lyrics-ruby-final.log`；容量测试先失败后修复的证据：`.scratch/lyrics-ruby-capacity-red.log`。

## 参考来源

参考本地 Halcyon `0381f67` 的逐字上下排版与 timed furigana 测试，以及 [AMLL TTML Ruby 语义](https://amll.dev/en/guides/lyric/ttml#ruby-annotation)。Mica 使用自有数据范围、严格时间匹配及 Compose 绘制实现，没有引入 AMLL 依赖或复制其渲染代码，也没有移植 Halcyon 的假名锚点/连续汉字猜分算法。
