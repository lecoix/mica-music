# 当前功能状态

> 最后更新：**2026-11-25**。已提交代码基线 `exoplayer-only` HEAD **`90bdd12a`**（发布 tag **`v0.4.5`**）；未提交工作树改动不视为实现事实。`app/build.gradle.kts` 为 **0.4.5 / versionCode 60**、Room **v35**，**minSdk 26 / targetSdk 36 / compileSdk 36**，Release 构建已启用 R8 压缩（`isMinifyEnabled = true`）。
> 代码/JVM/单机 smoke 不能外推为所有 OEM、DAC、ABI、16 KB 页面设备、正式签名包或音质通过。

## 2026-10-12—11-25 已合入增量（v0.4.4—v0.4.5）

- **Android 16 / targetSdk 36 迁移与 16 KB ELF 对齐**（`408e1c77`）：`targetSdk` 升至 36，NDK 固定 `r28c (28.2.13676358)`，`graphics-path` 升至 `1.1.0`，Robolectric 升至 `4.16.1`（API 36 JVM 测试需 JDK 21，TagLib 保持 JDK 17）。Debug/Release APK 已通过静态 16 KB LOAD/zipalign 结构检查；真实 16 KB 页面设备运行时仍未验收。详见 [`TARGET_SDK_36_MIGRATION.md`](TARGET_SDK_36_MIGRATION.md)。
- **R8 Release 压缩与 MBassador 规则修复**（`0b28e223`、`edb3d3bb`、`73727779`）：Release 启用 `isMinifyEnabled = true` 并调整 CI R8 堆上限；`app/proguard-rules.pro` 保留 Compose、Media3 FFmpeg JNI 解码器、Jaudiotagger ID3 frame body、SMBJ/MBassador `@Handler` 及 `ReflectiveHandlerInvocation(SubscriptionContext)` 构造函数，防止 R8 全模式下 SMB 监听注册崩溃。
- **Emby 远程曲库 MVP 及按需音频元数据/内嵌封面/歌词**（`976c056e`、`0585bc0c`、`81eca7d5`、`6e614a32`）：支持 Emby API Key（Keystore `BearerToken` + `X-Emby-Token` 头，不落入 URL/稳定 ID/日志）、分页发现音频条目（单源上限 10,000 首）、静态直出播放与 Range seek、音频元数据展示、封面按需拉取（曲目无主图时回退专辑主图，并支持经 256 KiB read-ahead 字节范围提取内嵌封面）及按需歌词（`EmbyLyricsLoader` 消费 Emby `MediaSources` 字幕/Extradata，同步期不拉歌词）。真机/真实 Emby 服务器矩阵仍需独立验收。
- **歌词逐字注音（Ruby）、源词尾时间、斜杠纯展示契约与外显定制**（`2918d360`、`d9924958`..`38b88a67`、`6357fd43`、`6616e940`、`8ce53841`、`1f069b54`、`a2bb1491`、`7b10a12d`、`93d3e288`）：
  - 经典歌词页间奏入场闪烁与收起回弹已修复（`6357fd43`）；
  - 源提供的逐字词尾时间跨经典/窄栏/桌面渲染器保留（`6616e940`，见 [`testing/LYRICS_WORD_END_ACCEPTANCE.md`](testing/LYRICS_WORD_END_ACCEPTANCE.md)）；
  - 普通斜杠视觉分行启发式与语言内容选择（`ALL / ORIGINAL / TRANSLATION`）完全解耦，裸斜杠不再误拆为译文（`d9be0a58` 等，见 [`LYRICS_SLASH_ROLE_CONTRACT.md`](LYRICS_SLASH_ROLE_CONTRACT.md)）；
  - 逐字注音严格按源提供范围对齐，不猜分，并已接入桌面/状态栏悬浮窗（明确选「仅原文」时同时隐藏注音与整行读音）；外部歌词新增未填充颜色与透明度调节（`2918d360`、`1f069b54`、`8ce53841`、`7b10a12d`，见 [`LYRICS_RUBY_ALIGNMENT.md`](LYRICS_RUBY_ALIGNMENT.md)）。
- **大队列防 ANR 与关闭态交互保护**（`018191c1`、`b9a6e9cf`）：队列元数据对比与同步避免重复全队列工作，并针对大队列收拢媒体通知控制器命令以降低大队列会话 ANR 风险；播放队列在关闭过渡期间禁用点击、滚动、拖拽与删除交互。
- **扫描器可信专辑标签优先、歌单与横屏频谱修复**（`7991239d`、`207bd966`、`97fd135d`）：扫描器读到可信专辑标签结果（含验证为空）时优先于 MediaStore 目录默认回退；修复歌单命名新建原子落库、重命名标签可读性与歌单总览底部留白；修复横屏切换歌词页后的频谱资格恢复。

## 2026-10-07—09 已合入增量（已包含于 v0.4.4 / v0.4.5）

- **播放队列有效顺序**（`57f3b989`）：服务接受完整排列并持有 effective/source order、逻辑 shuffle 标志、游标与请求 token；插播、拖动、删除、追加、自然切歌、失败跳曲及冷恢复走统一顺序。成员变化仍对 Media3 物理索引增量操作；旧 seed-only 快照无法无损重建编辑史。详见 [ADR-0008](adr/0008-playback-effective-order.md)。
- **SMB 显式文件夹导入**（`b332de4a`）：当前目录浏览只列名字；仅显式选择范围才完整发现并先发布可播放条目，再最多四路 TagLib probe、一次补齐元数据。probe read-ahead 为 **64 KiB**，与音频播放窗口不同；取消/退出保留已提交数据，同范围刷新可重试，不完整发现不提交。详见 [ADR-0007](adr/0007-smb-browse-and-selected-tracks.md)。
- **远端封面队列镜像**（`82f5374a`）：按稳定歌曲 ID 更新远端 artwork，不以过期下标错配。
- **正方形粒子封面**（`77386225`）：修正随 songId 奇偶变化的单轴散射；新增两种 parity × 四个播放进度的 GLES 像素回归，在 Xiaomi Android 12 / Adreno 730 获得定向验证，不外推全部 GPU。粒子封面 WebView 旧管线（`ThreeParticleCoverHost` / `assets/particle_cover`）已于 `d0d5d56e` 彻底移除。
- **9 月已提交功能**：紧凑比例布局、拍立得 seek 热区、歌词读音/翻译定时轨、应用图标预设与壁纸/迷你栏/信息行设置子页均进入代码。发布与多设备验收仍独立。
- **验证限定**：历史提交说明记录的定向 JVM / 单机测试（如 `57f3b989` 的 270 项 JVM + 9 项设备测试）仅针对当时改动，不代表全量质量门或正式 Release 验收通过。

## 已接入的主链

- 播放统一由前台 `MicaMediaService` 管理 Media3/ExoPlayer 单链路；普通格式与 ALAC/DSF/APE 扩展解码进入同一 Service/MediaSession。最近的队列修复避免列表选歌与非当前 metadata refresh 无意义重建整条 timeline；同曲单曲循环由 Service 确认回卷时同步把 UI 进度重置到 0。
- `PlayerController` 是 UI facade；`PlaybackRuntime` 与 queue/timeline/tuning/statistics/connection coordinator 持有长期状态。队列列表写路径（`setQueue` / `appendSongs` / `removeSongById` 等）约定主线程；基于 live 队列的读改写走 owner API，禁止跨 await 快照后再整表 `setQueue`。
- 本地曲库以 `MusicLibraryBacking -> LibraryOperationExecutor -> LibraryStore` 为扫描/AUTO/封面修复执行入口（`LibraryScanOrchestrator` 已退役）。Full Scan、cache hydrate、clear 与 AUTO publication 共享 generation/revision/publication fencing；Room **v35** 持有本地曲库、歌词 staging、自动同步 checkpoint/retry/outbox/exclusion、browse 派生状态、歌单及远端 catalog。
- DEVICE 与 SAF/FOLDER **durable automatic sync 已进入 ordinary scheduler real-auto**。dirty signal 只触发调度；任何 PARTIAL/UNAVAILABLE、provider transient、mass-deletion quarantine、playback defer 或 stale token 都 fail-closed。SAF 大量删除开闸需跨批**累计**对象级 missing 证明盖住隔离全集（cursor 只是 resume）。global / DEVICE / FOLDER 内部 kill switch 可停 AUTO 而不影响 Manual Full Scan；SAF provider 不可用有 30s/60s 有界重试，之后要求用户重选来源而不热循环。
- AUTO `CONFIRMED_MISSING` 的歌单清理经 followup outbox 异步消费；手动 `deleteSongEverywhere` 即时清歌单并报告 `playlistCleanupSucceeded`，**不**走该 outbox 补偿。
- 播放统计由进程级 `PlaybackStatisticsRepository` 经 FIFO `Channel` 单消费者写入 `PlayHistoryStore`。
- 远程曲库已合入四种来源：Navidrome/OpenSubsonic、WebDAV、SMB2/SMB3 与 **Emby**；支持来源隔离、原始音频播放/JIT 解析、文件型 Range/random-access、自动 catalog sync、独立排序、全局搜索、Artists/Albums/Recent 联合浏览、安全多选、歌单/封面解析、当前曲定位、JIT 封面与歌词。同一来源 catalog sync 在 `RemoteSourceManager` 单飞串行。SMB1 不启用，凭据不进入稳定 media id/歌单导出。SMB 新工作流是**当前目录浏览 + 显式 selected-folder scope**；旧版 catalog 的递归同步路径不可再描述为普通 SMB 默认行为。Emby 走 API Key（`X-Emby-Token`）分页索引（上限 10,000 首）、静态直出流与按需内嵌封面/歌词。

## UI / 歌词 / 系统集成

- 播放页有六种封面行为：标准、自定义标准、粒子封面、平行封面带、复古立体、拍立得回忆。自定义标准已扩展歌词/标题/进度条/控制组件编辑、隐藏后重排、封面滑动与阴影等低层配置。
- 播放页背景当前有七种枚举：主题色、封面渐变、封面模糊、动态烟云（设置 UI 暂隐藏）、流光溢彩、**星图**、**雨幕玻璃**。星图已取代实验 aurora/固定 constellation，为连续球面星图与太阳系天体；雨幕玻璃使用静态街景 + Mica 自有雨滴、湿痕、折射与模糊着色器，Mali/HyperOS 等跨 GPU 兼容性仍单独验收。
- 标准主题横屏封面已改为向背景交接处渐隐；歌曲列表可选 `HQ / SQ / HR` 副行音质标志；迷你播放器可关闭左右滑切歌；可选“启动时自动播放”默认关闭。
- 搜索结果已有数量统计与多选加入歌单，进入歌曲会收起键盘。桌面 Glance 小组件已有 **自适应 / 四宫格 / 大封面** 三类。
- 歌词支持 LRC/逐字/TTML/内嵌与多角色结构；LDDC 行尾翻译已兼容；支持源提供逐字注音（Ruby）、保留源词尾时间，且普通斜杠视觉分行与语言选择独立。桌面/状态栏歌词同步延迟已修，支持注音渲染与未填充颜色/透明度自定义；Lyricon provider 可选接入。车机歌词不再创建第二个 active MediaSession，而通过单 Session 的 `MicaSessionPresentationPlayer` 做 presentation，避免上一曲/下一曲控制冲突；仍需更多 OEM/车机矩阵。
- AAC/ADTS seeking 已修：CBR seek workaround 只用于 ADTS，不扩大到普通 MP4/M4A AAC。

## USB / DSD / 音频边界

- 默认输出语义是 **“关闭独占” = Shared PCM**。USB Exclusive Hybrid 已有 `UsbOutputCoordinator`、权限/插拔/重连、Exact PCM、DoP 与显式 Native DSD 路径；选择/格式/ownership 都按 capability fail-closed。
- Exact PCM 只接受可证明的整数 PCM 与无损宽度扩展；会改变信号的 SRC/DSP/ReplayGain/Sonic/软件音量等不能伪装成 signal-exact。DoP/Native 不互相静默 fallback。
- SK02 的 DSD64/128 与若干插拔/授权/恢复已经有实机证据，但不能外推任意 DAC/ROM。DSD256 曾出现白噪/不稳定，仍不作为普适稳定能力；Native `signalExact` 必须按设备/路径物理资格确认。
- `.dsf` 在 Shared PCM 路径仍可经 Media3 FFmpeg + DSD processor 播放；`.dff` / DSDIFF 仍拒绝播放。
- EQ、ReplayGain、频谱 tap、offload 偏好/熔断、音效实验室均走当前 `AudioPipelineCoordinator` 约束。任何可能降低音质的改动继续受 `CONTEXT.md` 的 Audio quality consent 规则约束。

## 已合入 v0.4.5 代码但仍缺多端真机验收的边界

- **Spectrum stall（媒体时钟频谱）**：`SpectrumClockAudioSink` + `SpectrumPcmTimeline` + `SpectrumAnalysisEngine` 已合入 `v0.4.5` 代码；JVM/受控 AudioOutput 测试通过，但多 OEM AudioTrack、蓝牙输出与视觉时钟对齐真机验收尚未闭环，详见 [`SPECTRUM_STALL_BUG.md`](SPECTRUM_STALL_BUG.md)。
- **Managed artwork recovery / ANR**：启动/health/scan-reuse/provider-open 仅做 metadata-level 检查、强 SHA 仅在加载失败后由 `ManagedArtworkRecovery` 懒重建的改动已合入 `v0.4.5`；仍需 8 GB / 10k 大库真机冷启动与异常恢复验收。
- **队列/统计/远端/删除/SAF 正确性收敛**（队列 owner RMW、统计 FIFO `Channel`、远端 sync 单飞、删除歌单成败文案、SAF 累计 missing 证明）均已进入 `v0.4.5`；`PersistentSortKeyCache` 并发落盘串行与字节上界、`RemoteSourceOwner` 同配置并发刷新代际仍属后续工程加固项。
- **Emby / Target SDK 36 / R8 Release / 外部歌词注音与透明度**：代码已进入 `v0.4.5`，但真实 Emby 服务器全场景、Android 16 16 KB 页面设备运行时、正式签名 R8 Release 包（含 SMBJ/MBassador 与原生库）及桌面/状态栏悬浮窗真机渲染均待独立真机验收。

## 2026-09-11 发布前设备证据

- Android 12 / Xiaomi 22081212C 上从空库 Full Scan 到 **10,665 首 / 37.0 GB**，`technicalFailed=0`，扫描约 342s；冷启动 cache load 约 1.6s。
- 同机实播 ALAC、FLAC、DSF；暂停态 seek/恢复、上/下一首、搜索单曲队列、四种播放模式、单曲循环回卷、标准横屏、后台/熄屏继续播放与冷启动暂停恢复均取得证据。
- 扫描后单点 TOTAL PSS 约 367 MB，杀进程前约 351 MB；这是约 12 GB 档设备的单点读数，不是 8 GB/10k 完整逐字歌词峰值证明。
- `dumpsys activity lastanr` 当轮未报告 Mica ANR，crash buffer 无 Mica FATAL；但 `/data/anr` 在扫描结束附近存在不可读 trace，不能据此宣称“零 ANR”。

## 尚未关闭的发布验收

- 目前**不能宣称发布前完整流程已通过**：现有真机证据主要集中在单台 Android 12 / 12 GB 设备（Xiaomi 22081212C + SK02 DAC）的 Debug/工作树包，不能等价替代正式签名 R8 Release 包。
- 仍缺 Android 8/8.1 与 Android 14+/16（含真实 16 KB 页面设备）多端矩阵、正式签名 R8 Release 双 ABI（含真实 32 位进程）覆盖升级、真实 MP3 点播、DFF 拒绝样本、蓝牙/耳机拔出、通知控制/划掉 Activity，以及六种封面行为 × 歌词主题 × 浅深色 × 1:1 折叠屏/方屏布局的完整 UI 组合。
- 频谱时钟对齐（`SpectrumPcmTimeline`）、音效实验室 USB 旁路、视频封面 H.264 长播与耗电、Emby 真实服务端矩阵、桌面/状态栏逐字注音与未填充透明度真机悬浮窗仍需补证。
- Android Auto/不同 OEM 车机、锁屏、后台限制、Glance launcher 差异、分屏/小窗触摸、USB 多 DAC/扩展坞与 DSD256 仍需独立证据。
- 8 GB 设备条件在自动同步 S3/S4 阶段曾被用户明确跳过；这不是 PASS。项目仍以 **10,000 首 + 完整逐字歌词 + 8 GB** 为设计容量基线，发布结论不得用 12 GB 设备结果等价替代。

## 相关权威文档

- 领域词汇/owner：[`../CONTEXT.md`](../CONTEXT.md)
- 文档索引：[`DOC_INDEX.md`](DOC_INDEX.md)
- 功能清单：[`TODO.md`](TODO.md)
- Target SDK 36 迁移：[`TARGET_SDK_36_MIGRATION.md`](TARGET_SDK_36_MIGRATION.md)
- 歌词注音与斜杠契约：[`LYRICS_RUBY_ALIGNMENT.md`](LYRICS_RUBY_ALIGNMENT.md)、[`LYRICS_SLASH_ROLE_CONTRACT.md`](LYRICS_SLASH_ROLE_CONTRACT.md)
- 曲库 Full/AUTO：[`LIBRARY_SCAN.md`](LIBRARY_SCAN.md)、[`LIBRARY_AUTO_SYNC_P_AND_P_EXECUTION_PLAN.md`](LIBRARY_AUTO_SYNC_P_AND_P_EXECUTION_PLAN.md)、[`adr/0006-library-auto-sync-publication-and-discovery.md`](adr/0006-library-auto-sync-publication-and-discovery.md)
- 远端曲库：[`REMOTE_MUSIC_SOURCE_RESEARCH.md`](REMOTE_MUSIC_SOURCE_RESEARCH.md)
- USB Hybrid：[`USB_EXCLUSIVE_HYBRID_STATUS.md`](USB_EXCLUSIVE_HYBRID_STATUS.md)、[`adr/0004-usb-exclusive-hybrid.md`](adr/0004-usb-exclusive-hybrid.md)
- 测试：[`TESTING.md`](TESTING.md)、[`AUTOMATED_FLOW_TESTING.md`](AUTOMATED_FLOW_TESTING.md)
