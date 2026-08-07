# Mica 功能清单

> 最后整理：2026-08-07。以当前代码为准。领域词汇见 `[CONTEXT.md](../CONTEXT.md)`；近期功能状态见 `[CURRENT_FEATURE_STATUS.md](CURRENT_FEATURE_STATUS.md)`；文档索引见 `[DOC_INDEX.md](DOC_INDEX.md)`。

---

## 已实现

### 曲库与扫描

- [x] 通过系统目录（SAF）或 **MediaStore** 扫描本地音频
- [x] 扫描选项：最短时长（默认 ≥1 分，含 ≥2 分）、纳入非 IS_MUSIC 音频、深度元数据探测
- [x] 深度扫描：格式/采样率/码率、内嵌封面提取与缓存、**封面取色**（Palette，偏封面下方区域）
- [x] 扫描进度与错误展示；扫描前清理 `lyrics_probe` 等临时缓存
- [x] 歌曲列表 **排序**（字段 + 升/降序；`SharedPreferences` 持久化，统计栏显示「标题 · 升序」等）
- [x] 播放次数记录（`PlayHistoryStore`，供「最近播放」等）
- [x] **播放会话恢复**：`ServicePlaybackStateStore` 持久化完整队列与进度；冷启动 `PlayerController.bootstrapQueue()` 恢复当前曲与位置（不自动续播）。`PlaybackSessionStore` 仍写入 shuffle 等 App 偏好
- [x] 文件夹浏览模式：层级浏览与**扁平浏览**均已持久化；扁平模式只列出直接包含歌曲的目录，不把仅包含子目录的父目录重复列出
- [x] **歌单持久化迁移到 Room**：`playlists` / `playlist_songs`（schema v17）；旧 `mica_playlists` JSON 首次启动一次性迁移，写库成功后才更新内存



### 歌词

- [x] 内嵌歌词读取（ID3 / FLAC / APE / FFmpeg 元数据等）
- [x] LRC 时间轴解析；播放页 **三行歌词**（上句 / 当前 / 下句）
- [x] **毫秒级**进度同步（约 50ms 轮询）
- [x] **歌词聚焦**：播放页内点击歌词区，封面动画缩至左上角，展开大字歌词；无独立页面；底部控制条不变
- [x] **歌词结构化角色与逐字时间保留**：入库不再把显示分隔符改写为 `TRANSLATION`；TTML/SPL/逐字 token 角色与行结束时间原样保留，文本分隔仅用于展示层



### 播放

- [x] Media3 **ExoPlayer** + 前台 `MicaMediaService`（`MediaSessionService`；播放出声统一走 Exo 单链路）
- [x] **ExoPlayer 单链路播放**：普通格式、ALAC/DSF 等均由 ExoPlayer / Media3 扩展链路处理；不再走整首 `.pcm` 落盘 + `AudioTrack` 自建播放管线
- [x] 播放队列、上一首 / 下一首、拖动进度条 seek、缓冲与错误提示
- [x] 外部文件管理器打开音频：消费 `ACTION_VIEW` 的 `content://` 音频 URI，支持冷启动与 warm `onNewIntent()`；已知曲目复用曲库记录，否则使用不持久化的临时单曲替换当前队列
- [x] **播放状态所有权收拢**：队列 / 时间轴 / 调音状态分别由 `PlaybackQueueCoordinator` / `PlaybackTimelineCoordinator` / `PlaybackTuningCoordinator` 集中；`PlaybackConnectionSession` 拒绝旧连接回调，陈旧队列镜像结果按请求号 / revision / 当前连接丢弃
- [x] **外部队列恢复边界**：只有 URI 权限可跨重启存续的外部歌曲进入 `ServicePlaybackStateStore` 快照；临时不可恢复队列不落盘
- [x] **累计播放时长**统计（按曲 / 全库；切歌与暂停时落盘；音乐库分析或统计栏展示）
- [x] **播放模式**：顺序 / 列表循环 / 单曲循环 / 随机（单按钮切换）
- [x] 播放页 **播放列表** 底部弹层（查看队列、点击切歌）
- [x] Hi‑Res 标志样式：默认、黄底镂空、自定义图片；自定义图片失效时回退默认样式
- [x] 曲库 **Room 持久化**（扫描结果写入 DB，冷启动自动恢复；**增量同步**新增/更新/移除）
- [x] 播放列表：**拖拽排序**、**从队列删除**
- [x] **音乐库分析**（格式 / 采样率 / 码率分布，侧栏入口）
- [x] **通知歌词**（设置开关；锁屏/通知栏随进度显示当前歌词行，关闭后恢复普通元数据）
- [x] 底部 **迷你播放器**



### 播放页 UI

- [x] 封面、歌名/歌手/专辑、Hi‑Fi 信息行、Hi‑Res 标记
- [x] 五种**播放页背景**（设置 → 播放页背景；与封面行为并列可选）：
  - **主题色**（`THEME`）：云母背景渐变
  - **封面渐变**（`ARTWORK_GRADIENT`）：取色 + 下半屏保持专辑色
  - **封面模糊**（`COVER_GLOW`）：Android 12+ 全屏模糊封面 + 取色叠层；低版本取色渐变兜底；下半屏控件 **白色**（Hi‑Fi 标签仍用主题色）
  - **动态烟云**（`DYNAMIC_LIGHT`）：低分辨率封面纹理 + GLES 动态渲染
  - **流光溢彩**（`DYNAMIC_ARTWORK`）：多层封面纹理、blur shader 与切歌 crossfade
- [x] 六种**播放页封面行为**（设置 → 播放页封面行为；见 `[COVER_FLOW_IMPLEMENTATION.md](COVER_FLOW_IMPLEMENTATION.md)` §0 与 `[CONTEXT.md](../CONTEXT.md)`）：
  - **标准**（`STANDARD`）：大封面 + 横向轻扫切歌
  - **自定义标准**（`CUSTOM_STANDARD`）：标准封面布局的可配置歌词页/横屏契约
  - **粒子封面**（`PARTICLE_COVER`）：边缘粒子化与切歌分解；现网 **GLES**（`ParticleCoverPlayerLayer` → `ParticleCoverHost` / `ParticleCoverRenderer`）；WebView 回退仅 `UseNativeParticleCoverInPlayer = false`。详见 `[PARTICLE_COVER_OPENGL_MIGRATION.md](PARTICLE_COVER_OPENGL_MIGRATION.md)` §0
  - **平行封面带**（`PAUSE_FOLD`）、**复古立体封面**（`RETRO_3D`）：七轨 View 岛封面流
  - **拍立得回忆**（`PHOTO_STACK`）：拍立得叠放转场
- [x] 根据背景自动/固定调整文字对比度
- [x] **封面底边进度**（设置开关；仅「主题色」「封面模糊」：封面下缘屏宽细进度条，隐藏常规进度条与时间）
- [x] **下半屏沉浸**（设置或长按歌名/歌手：下半屏仅居中歌名+歌手，点击播放/暂停，长按退出）
- [x] **歌曲详情**（标题/艺术家/专辑/专辑艺术家/媒体来源/播放统计/音质/路径/版权/时间等；列表菜单「歌曲信息」或播放页 ℹ 进入）
- [x] **歌曲长按菜单**（封面信息头、下一首播放、分享、标签编辑、删除等；添加到歌单、删除前确认）
- [x] **歌单**：侧栏列表/新建、歌单详情与播放、删除歌单（确认）；侧栏歌单样式可切换、歌单总览长按管理、导入/导出歌单（JSON）、歌曲封面/自定义封面（导出不含自定义封面）



### 主界面与设置

- [x] 侧栏导航：**歌曲 / 歌手 / 专辑 / 最近 / 歌单 / 音乐库分析 / 设置**（元数据调试在设置内）
- [x] **搜索**：标题 / 艺术家 / 专辑 / 文件名
- [x] **播放次数**与**最近播放**（`PlayHistoryStore`）；副标题显示播放次数
- [x] ~~收藏 / 我喜欢~~（已有歌单，已移除）
- [x] **歌曲**列表完整流程（权限、选文件夹、扫描、空状态）
- [x] 设置：主题（跟随系统/浅/深）、强调色、云母背景、封面显示、隐藏状态栏、播放页背景、扫描相关、**元数据调试**（设置 → 扫描）、ALAC 播放方式、权限入口等
- [x] 歌词页主题：经典列表、歌词云、**信笺**；信笺支持自定义朱印图片、大小、浓度和旋转
- [x] 睡眠定时：墙钟倒计时，通过当前 ExoPlayer/MediaController 音量接口结束前渐弱并暂停；保留最近使用时长
- [x] 云母风格 Compose 设计系统（直角、渐变背景、状态栏处理）

---



## 部分实现（有 UI、逻辑未完成）


| 功能            | 现状                                                   |
| ------------- | ---------------------------------------------------- |
| 分享            | 长按菜单入口在，实际分享未做                                       |
| 歌手/专辑详情歌曲列表滚动 | 已用 `rememberSaveable` 按歌手/专辑 key 保留；返回列表与返回同一详情均不丢位置 |


---



## 尚未实现



### 曲库 / 数据

- [ ] **完整曲库 snapshot 发布保护回归复查（2026-08-03）**：已发生过“旧 generation 保护修好后，后续新增的挂起/文件副作用又插入保护区间并绕过校验”的情况：`bb0802da` 建立 cache/scan/clear 的 generation 协议后，`b9407a1d` 在 `publishSongs()` 的 generation 检查与 `adoptPrepared()` 之间加入 `pruneAlbumArtCache()`，重新留下过期 snapshot 与过期封面清理风险。修复后必须继续审查所有 `generation` 检查之间的 `withContext`、IO、文件删除和异步发布；为每类交错补回归测试，并把完整 snapshot 发布收敛到不可绕过的统一 seam，防止新增功能再次绕过保护。
- [ ] **大曲库启动后续优化（低优先级）**：当前 `cachedOrder=true` 后测试反馈不卡；仅当实机再次报告冷启动/回后台恢复慢，或日志出现明确瓶颈时再推进。
  - 单一 `LibraryUiSnapshot` / sealed library state 一次发布，减少 `songs`、`songIds`、fast-scroll 数据、scan flags 等多个 Compose state 连续更新。
  - 持久化多字段 `sortKey` / `section`（标题、歌手、专辑、文件夹），用于非当前排序切换时避免现场 `Collator` 排序。
  - 拆分列表行模型与完整歌曲详情模型；如果超大曲库日志显示 `toSong`、lyrics decode 或 payload map 成为瓶颈，再按需读取歌词/复杂 metadata。
  - 首屏 Ready 之后再延后非首帧任务，如 album-art prune、player connect/bootstrap、歌手/专辑/文件夹分组。
  - 缓存或延迟歌手、专辑、文件夹派生分组；只在大曲库导航日志显示 group/sort 成本明显时启动。
- [ ] **扫描完成提示样式待讨论**：当前使用首页 Snackbar 展示新增/更新/移除摘要；后续确认是否保留、改为页面内状态，或采用更符合整体视觉的非打断式样式。
- [x] 外挂 `.lrc` 文件（与音频同目录、同名 `.lrc`；扫描时与内嵌歌词取优）
- [ ] 外挂歌词：**指定目录** / 多候选命名（artist-title.lrc 等）
- [ ] 自定义歌词优先级：TTML、LRC;外挂、内嵌


### 主界面 · 迷你播放器底栏

- [x] **底栏封面被圆角裁切**：`MiniPlayer` 增加 `safeDrawing` 左右/底内边距，封面落在安全区（圆角屏 + 手势条需真机验收）
- [x] **迷你播放栏样式**：设置 → 外观「迷你播放栏」可在 **浮岛卡片（B）** / **极简 Hi‑Fi（D）** 间切换；浮岛为底边主题色卡片 + 底描边，极简为通栏底条 + 左播放 + 右剩余时间



### 设置 / 字体

- [ ] **内置第二套字体**（设置项：系统默认 / 内置字体；默认仍走系统）
  - **授权**：随 APK **再分发**须合法——**开源/OFL（如 SIL）/Apache 2.0** 最省事；亦可用明确允许嵌入的商用免费字体，但须在 `NOTICE`/`licenses` 保留版权声明，**不可**仅桌面授权、不可擅自打包 HarmonyOS Sans 等未授权字库。
  - **范围**：中文无衬线 + 等宽（Hi‑Fi 信息行、时间戳）；与 `[DESIGN_SPEC.md](../DESIGN_SPEC.md)` 字号层级一致；**子集化**（常用汉字 + Latin）控制体积。
  - **实现**：`res/font/*.ttf` → `FontFamily`（`[Type.kt](../app/src/main/java/com/mica/music/ui/theme/Type.kt)` 现用 `Default`/`Monospace` 回退，需接入选项并全局 `MicaTheme.typography`）。
  - **候选（待选型）**：Noto Sans SC、思源黑体 Source Han Sans 子集、JetBrains Mono、霞鹜文楷 LXGW WenKai 等——以 OFL/Apache 可嵌入为准。



### 设置 / 封面显示

- [x] **不规则封面**：设置 → 外观「封面显示」—「裁切填充」居中裁切；「原样比例」列表/歌词/迷你栏等保持正方形容器内完整显示，仅播放页未进歌词聚焦时大图可按比例排版


### 全局 · 界面动效

- [x] **动效规范文档**（`[docs/MOTION.md](MOTION.md)`：时长 token、depth、场景映射、开发约束）
- [x] **动效基础**（`MicaMotion`：320/400ms + `FastOutSlowIn`；系统「减少动态效果」时瞬时切换）
- [x] **搜索动效**（主页内容区推入/顶栏 `topBarSearchTransition`/统计栏收起）
- [x] **浅色/深色与云母背景**：`AnimatedMicaAppBackground` + `rememberAnimatedHifiColors` 交叉淡入
- [x] **主导航内容区**：侧栏切换歌曲/歌手/专辑/最近/歌单/分析时 `AnimatedContent` 淡入+轻滑；歌手/专辑 Root↔详情同理
- [x] **路由**：设置/播放页/详情等 NavHost 淡入+纵滑；侧栏抽屉与遮罩统一曲线
- [x] **播放页下半屏沉浸**：元数据区与底栏控制区 alpha 交叉淡入（400ms）
- [x] **歌词聚焦**：曲线并入 `MicaMotion`（封面 lerp / 底栏下沉保持）
- [x] **播放页打开/关闭共享封面第一版**：迷你播放器封面→播放页封面；返回时反向收回；特殊封面流主题回退普通转场
- [ ] **共享封面转场架构整理**：从 `AppNavigation` 拆出独立 `SharedCoverTransitionHost/Coordinator`，导航层只挂载入口；坐标稳定、源/目标隐藏、图片预热、IME/路由转场防污染等状态集中管理并配套测试清单
- [x] **播放页浮层返回状态机整理**：将 `PlayerSheetHost` 的 `expanded`、predictive back、关闭动画拆成显式 `Expanded / PredictiveClosing / Closing / Collapsed` 状态，避免外部展开状态与本地动画值各自作为真值源
- [ ] **列表→播放共享元素**：列表项封面作为进入来源，来源不可见时回退迷你播放器或普通转场
- [ ] **浮层**：BottomSheet / 对话框自定义 expand（Material 默认动画；待与 `MicaMotion` 对齐）
- [ ] **列表与迷你播放器**：迷你栏展开为全屏的衔接动画；列表排序/删除占位（低优先级）
- [ ] 按压状态层重新设计


### 主题与播放页样式

- [x] **主题色**：设置 → 外观「强调色」（紫韵 / 鎏金 / 青釉 / 珊瑚 / 动态取色）与「云母背景」（晨曦 / 暮色 / 午夜 / 极光 / 雾霭）；晨曦浅色独立暖雾→天光蓝、深色海军→琥珀；`MicaTheme` + `micaAppBackground()` 全应用生效
- [x] **浅色/深色切换动画**（见「全局 · 界面动效」）
- [x] **流光溢彩**播放页样式：`PlayerLowerBackgroundMode.DYNAMIC_ARTWORK`（见上「五种播放页背景」）
- [ ] **信笺歌词物理平行光带（可能还有救，低优先级）**
  - 独立 Three.js 原型已经能用 `DirectionalLight` 与两块真实遮挡平面形成边界平行的光带；不要再退回手绘灰影或艺术阴影蒙版。
  - 当前从正式主题撤下：固定烘焙图无法同时适配 19.5:9、20:9 等长屏裁剪、圆角安全区、歌词列位置与朱印构图，光带还会抢过文字成为视觉主体。
  - 若以后重启，先在真实手机比例下解决“光带随可见纸面重排”的构图规则，再比较多套静态比例资源与轻量运行时着色方案；没有真机矩阵与耗电/GPU 测量前，不把完整 Three.js 运行时带进播放器。
- [ ] **动态烟云打磨**
- [ ] **粒子封面 WebView 退役**：播放页已切 GLES；删除 `ThreeParticleCoverHost` / `assets/particle_cover`、完成 parity 与性能验收见 `[PARTICLE_COVER_OPENGL_MIGRATION.md](PARTICLE_COVER_OPENGL_MIGRATION.md)`
- [ ] 播放页 **歌词切换动效**（平滑过渡 / 滚动居中）
- [x] 歌词 **双语**（分隔显示或原文/译文切换）



### 播放页 · 频谱可视化

- [x] **频谱条**（设置开关；默认关）
  - **首选（标准进度条模式）**：`LyricsChromeProgressBlock` 内，紧贴 `HiFiSeekBar` 上/下方，高度 **12–20dp**；与 2dp 进度条同色，使用 `PlayerContentColors.primary`，按频段递减 alpha；对称竖条（柱宽 **2–3dp**、间距 **2dp**），无底色块、不用圆形 EQ 旋钮风。
  - **封面底边进度模式**：全宽封面 `SongCover` 底缘、`CoverEdgeProgressBar` **上方** **16–24dp** 律动竖条（向上生长），与底边细进度条同一视觉语言；与标准模式二选一展示或分模式开关，避免两处同时闪。
  - **组件**：单一 `PlayerSpectrumStrip`，按 `useCoverEdgeProgress` / 锚点切换位置；颜色走现有播放页对比度（封面模糊下半屏用白 @ alpha）。
  - **显隐**：**歌词聚焦**时随 `lyricsFocus` 淡出或关闭；**下半屏沉浸**关闭；不遮挡五按钮触控区、不铺满封面、不嵌入歌词列表。
  - **数据**：跟随当前 ExoPlayer 播放链路取样 / 分析，避免额外录音权限。
  - **次选（仅弱装饰）**：歌词区与底栏间 `beforePlaybackChrome` 留白极低透明度氛围带——非主方案。



### 音频 · 播放架构（长期）

- [x] **ExoPlayer 单链路优先**：普通格式与扩展格式统一走 ExoPlayer / Media3 解码播放，避免旧的多播放管线分叉
- [x] **内存流式解码**：已移除整首 `.pcm` 落盘播放路径；seek 与进度状态跟随 ExoPlayer 当前播放状态
- [x] **MediaSession 外部控制边界**：已完成连接能力与命令限制的基础边界；保留系统媒体控件、蓝牙和车机所需的标准播放命令，不做品牌包名白名单。Android Auto / OEM 真机验收仍见下方 P2 项
- [ ] **P2：Android Auto / 车机 MediaSession 验收**：补充真实车机或 Android Auto DHU 的连接、元数据、播放控制与重连验证。
- [ ] **P2：OEM 车机兼容性验收**：覆盖不同厂商车机/系统控制器的媒体按键、元数据、封面与进程重启行为。
- [x] **ReplayGain 实际应用状态**：已按 [`REPLAYGAIN_SIGNAL_STATE_PLAN.md`](REPLAYGAIN_SIGNAL_STATE_PLAN.md) 建立最终线性系数的事实来源和 owner module；保持现有算法、音量乘法与音频链行为不变
- [ ] **USB Host 真独占（远期）**：按 [`ADR-0001`](adr/0001-usb-host-exclusive-output.md) 新增独立 output adapter/session；当前不得解除 P6 fail-fast 或把最小 `DefaultAudioSink` 称为 USB 独占



### 音频与其它

- [x] **EQ** 均衡器（10 段软件 EQ、系统/自定义预设、保存配置；界面已重做为横向推子布局）
- [x] **均衡器全局增益（Preamp / Master Gain）**：EQ 页提供可调全局输出增益并持久化；与现有 EQ 限幅协调，避免削波
- [x] **音频管线协调与 offload 熔断**：EQ / 频谱 tap / offload 偏好 / 路由事件统一由 `AudioPipelineCoordinator` 串行处理；offload 失速经 `AudioOffloadCircuitBreaker` 确认后按 build fingerprint 记录失败并回 PCM，设置可手动重试
- [x] **横屏**播放页基础实现：播放页、队列侧栏及全局窗口横屏布局已接入；视觉、生命周期、音频连续性仍需真机验收
- [ ] 车机模式
- [ ] 多文件夹读取（扫描授权仍是单一根目录；这不是已实现的“扁平浏览”模式）


### 远期 · 低优先级

- [ ] **自定义排序长期计划**：如果歌曲列表自定义顺序、艺术家自定义顺序、专辑自定义顺序都需要统一持久化，再把当前 SharedPreferences 里的歌曲自定义顺序迁移到 Room；不要复用 `songs.queueOrder`，它只表示缓存的当前可见列表顺序。建议新增 `library_meta.customSongOrderJson/customSongOrderLocked` 或独立排序表，再按需要扩展 artist/album group key 的自定义顺序。
- [x] **标准播放页视频封面**：仅限 `PlayerCoverFlowMode.STANDARD` 的播放页大封面；开关默认关闭。文件夹扫描收集与歌曲同目录、文件名等于专辑名的 `.mp4`，原名精确匹配优先，之后按 NFKC、首尾/连续空白和 `Locale.ROOT` 大小写归一化匹配；不忽略标点或版本后缀，空/未知专辑和多候选歧义不匹配。
  - **范围**：不影响封面流、照片堆、粒子封面、迷你播放器、列表缩略图和动态背景；这些路径继续使用 `albumArtUri` 静态图。
  - **播放**：独立短生命周期 Media3/ExoPlayer，禁用音轨并由系统 `MediaCodec` 解码视频；随音乐暂停，循环播放，仅播放页处于前台且标准主题激活时挂载，切歌/离开页面/后台/锁屏及时释放。
  - **显示**：沿用当前静态封面矩形，比例不符只在渲染层居中裁切；静态图保留到视频首帧成功，不转码、不改源文件。
  - **回退与验收**：视频不存在、无法打开、解码失败或设备不支持时回退静态封面，同 URI 在当前播放页会话不重试；匹配索引为 O(歌曲数 + MP4 数)，10,000 首曲库不解析视频。代码与 JVM 测试已完成；耗电、切歌释放、后台/锁屏切换和常见 H.264 MP4 兼容性仍需真机验收。
- [ ] **瘦身**（APK / 运行时占用）
  - **播放缓存**：复查 ExoPlayer / 扩展解码缓存与临时文件上限，避免大曲库长期占用膨胀
  - **依赖与资源**：ProGuard/R8、未用资源与 so；设置项说明占用
- [x] **平行封面带** / **复古立体封面**（见上「六种播放页封面行为」；`[COVER_FLOW_IMPLEMENTATION.md](COVER_FLOW_IMPLEMENTATION.md)` §0–§12）
  - **观感**：启用后播放页常驻平行/立体封面带；播放 / 暂停不再触发布局放大缩小。
  - **实现**：View + Canvas 七轨；启用时强制裁切填充；与任意播放页背景组合。
- [x] **封面流切歌闪帧 / 位移跳变（已治本）**
  - **治本**：View + Canvas 七轨（`[COVER_FLOW_IMPLEMENTATION.md](COVER_FLOW_IMPLEMENTATION.md)`）——无 Compose 槽位重建、Coil 缓存位图直绘、`railOffset` 单轨末帧连续。
  - **仍保留的通用优化**：模糊背景 `.size(384)` 降采样；`SongCover` `stableMemoryCacheKey`；`[MicaImageLoaders](../app/src/main/java/com/mica/music/imaging/MicaImageLoaders.kt)` 预载。
  - **验收**：平行 / 复古 × 各播放页背景下连续切歌与拖动；无闪帧、无松手跳变。
- [x] **歌单功能正式化**：歌单已有侧栏列表 / 新建 / 详情 / 播放 / 删除、重命名、导入导出 JSON、歌曲封面与自定义封面管理；2026-08-07 起持久化迁至 Room（schema v17）。尚未实现智能歌单条件
- [ ] **歌曲年份信息**
  - **月日信息**
---



## 已废弃 / 实验归档

- ~~首版 CSDN mesh + 离线 Bitmap + Ken Burns~~：观感不佳，已由 **封面模糊 / 动态烟云 / 流光溢彩** 等背景方案替代
- ~~底边黑带 / 双帧流光叠化~~ 等临时实验
