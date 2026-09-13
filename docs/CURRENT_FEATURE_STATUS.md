# 当前功能状态

> 最后更新：2026-09-11。本文是“当前实现 + 已取得证据 + 未完成验收”的 living status；代码/JVM/单机 smoke 不能外推为所有 OEM、DAC、ABI、签名包或音质均通过。
> 当前 App 基线：**0.4.0 / versionCode 54 / Room schema v29 / minSdk 26 / targetSdk 34 / compileSdk 36**。

## 已接入的主链

- 播放统一由前台 `MicaMediaService` 管理 Media3/ExoPlayer 单链路；普通格式与 ALAC/DSF/APE 扩展解码进入同一 Service/MediaSession。最近的队列修复避免列表选歌与非当前 metadata refresh 无意义重建整条 timeline；同曲单曲循环由 Service 确认回卷时同步把 UI 进度重置到 0。
- `PlayerController` 是 UI facade；`PlaybackRuntime` 与 queue/timeline/tuning/statistics/connection coordinator 持有长期状态。2026-09-02 的架构收敛已把 catalog store write fencing、media/UI/lyrics seam、browse/lyrics ownership、cover renderer/geometry authority 和 playback-stack lifecycle 进一步集中到 owner 边界。
- 本地曲库以 `MusicLibraryBacking -> LibraryScanOrchestrator -> LibraryStore` 为单一 authority。Full Scan、cache hydrate、clear 与 AUTO publication 共享 generation/revision/publication fencing；Room v30 同时持有本地曲库、歌词 staging、自动同步 checkpoint/retry/outbox/exclusion、browse 派生状态、歌单及远端 catalog。
- DEVICE 与 SAF/FOLDER **durable automatic sync 已进入 ordinary scheduler real-auto**。dirty signal 只触发调度；任何 PARTIAL/UNAVAILABLE、provider transient、mass-deletion quarantine、playback defer 或 stale token 都 fail-closed。global / DEVICE / FOLDER 内部 kill switch 可停 AUTO 而不影响 Manual Full Scan；SAF provider 不可用有 30s/60s 有界重试，之后要求用户重选来源而不热循环。
- 远程曲库 MVP 已合并：Navidrome/OpenSubsonic、WebDAV、SMB2/SMB3；支持来源隔离、原始音频播放/JIT 解析、文件型 Range/random-access、自动 catalog sync、独立排序、全局搜索、Artists/Albums/Recent 联合浏览、安全多选、歌单/封面解析、当前曲定位、JIT 封面与歌词。SMB1 不启用，凭据不进入稳定 media id/歌单导出。

## UI / 歌词 / 系统集成

- 播放页有六种封面行为：标准、自定义标准、粒子封面、平行封面带、复古立体、拍立得回忆。自定义标准已扩展歌词/标题/进度条/控制组件编辑、隐藏后重排、封面滑动与阴影等低层配置。
- 播放页背景当前枚举为主题色、封面渐变、封面模糊、动态烟云（设置 UI 暂隐藏）、流光溢彩、**星图**。星图已取代实验 aurora/固定 constellation，现为连续球面星图、季节天空、星座及太阳/月亮/行星的程序化位置。
- 标准主题横屏封面已改为向背景交接处渐隐；歌曲列表可选 `HQ / SQ / HR` 副行音质标志；迷你播放器可关闭左右滑切歌；可选“启动时自动播放”默认关闭。
- 搜索结果已有数量统计与多选加入歌单，进入歌曲会收起键盘。桌面 Glance 小组件已有 **自适应 / 四宫格 / 大封面** 三类。
- 歌词支持 LRC/逐字/TTML/内嵌与多角色结构；LDDC 行尾翻译已兼容。桌面/状态栏歌词同步延迟已修；Lyricon provider 可选接入。车机歌词不再创建第二个 active MediaSession，而通过单 Session 的 `MicaSessionPresentationPlayer` 做 presentation，避免上一曲/下一曲控制冲突；仍需更多 OEM/车机矩阵。
- AAC/ADTS seeking 已修：CBR seek workaround 只用于 ADTS，不扩大到普通 MP4/M4A AAC。

## USB / DSD / 音频边界

- 默认输出语义是 **“关闭独占” = Shared PCM**。USB Exclusive Hybrid 已有 `UsbOutputCoordinator`、权限/插拔/重连、Exact PCM、DoP 与显式 Native DSD 路径；选择/格式/ownership 都按 capability fail-closed。
- Exact PCM 只接受可证明的整数 PCM 与无损宽度扩展；会改变信号的 SRC/DSP/ReplayGain/Sonic/软件音量等不能伪装成 signal-exact。DoP/Native 不互相静默 fallback。
- SK02 的 DSD64/128 与若干插拔/授权/恢复已经有实机证据，但不能外推任意 DAC/ROM。DSD256 曾出现白噪/不稳定，仍不作为普适稳定能力；Native `signalExact` 必须按设备/路径物理资格确认。
- `.dsf` 在 Shared PCM 路径仍可经 Media3 FFmpeg + DSD processor 播放；`.dff` / DSDIFF 仍拒绝播放。
- EQ、ReplayGain、频谱 tap、offload 偏好/熔断、音效实验室均走当前 `AudioPipelineCoordinator` 约束。任何可能降低音质的改动继续受 `CONTEXT.md` 的 Audio quality consent 规则约束。

## 当前工作树中的 staged 修复（未等同已发布）

- **Spectrum stall**：当前未提交工作树正在把频谱从“processor wall-clock 发布”迁到 sink media clock + `SpectrumPcmTimeline` + `SpectrumAnalysisEngine`；桌面 focused suite 与完整 JVM 曾通过，设备验收仍进行中。它不能写成 0.4.0 干净 HEAD 已发布行为，详见 `SPECTRUM_STALL_BUG.md`。
- **Managed artwork recovery / ANR**：当前未提交工作树把启动/health/scan-reuse/provider-open 的封面健康检查限制为 metadata-level，强 SHA-256 校验只在真实图片加载失败后进入低优先级单线程 lazy recovery。2026-09-11 的 10,665 首冷启动 `repair-check` 约 200ms 且无缺失，但一次 smoke 不能关闭历史主线程 SHA ANR，且改动尚未提交。

## 2026-09-11 发布前设备证据

- Android 12 / Xiaomi 22081212C 上从空库 Full Scan 到 **10,665 首 / 37.0 GB**，`technicalFailed=0`，扫描约 342s；冷启动 cache load 约 1.6s。
- 同机实播 ALAC、FLAC、DSF；暂停态 seek/恢复、上/下一首、搜索单曲队列、四种播放模式、单曲循环回卷、标准横屏、后台/熄屏继续播放与冷启动暂停恢复均取得证据。
- 扫描后单点 TOTAL PSS 约 367 MB，杀进程前约 351 MB；这是约 12 GB 档设备的单点读数，不是 8 GB/10k 完整逐字歌词峰值证明。
- `dumpsys activity lastanr` 当轮未报告 Mica ANR，crash buffer 无 Mica FATAL；但 `/data/anr` 在扫描结束附近存在不可读 trace，不能据此宣称“零 ANR”。

## 尚未关闭的发布验收

- 目前**不能宣称发布前完整流程已通过**：2026-09-11 设备轮次只覆盖一台 Android 12，且机上包包含当前工作树改动，不是正式签名/混淆 Release。
- 仍缺 Android 8/8.1 与 Android 14+ 双端矩阵、真实 MP3 点播、DFF 拒绝样本、蓝牙/耳机拔出、USB 模式矩阵、通知控制/划掉 Activity、正式覆盖升级，以及六种封面行为 × 歌词主题 × 浅深色的完整 UI 组合。
- Android Auto/不同 OEM 车机、锁屏、后台限制、Glance launcher 差异、分屏/小窗触摸、USB 多 DAC/扩展坞、真实 32 位进程与正式签名 ABI split 仍需独立证据。
- 8 GB 设备条件在自动同步 S3/S4 阶段曾被用户明确跳过；这不是 PASS。项目仍以 10,000 首 + 完整逐字歌词 + 8 GB 为设计容量基线，发布结论不得用 12 GB 设备结果等价替代。

## 相关权威文档

- 领域词汇/owner：[`../CONTEXT.md`](../CONTEXT.md)
- 文档索引：[`DOC_INDEX.md`](DOC_INDEX.md)
- 功能清单：[`TODO.md`](TODO.md)
- 曲库 Full/AUTO：[`LIBRARY_SCAN.md`](LIBRARY_SCAN.md)、[`LIBRARY_AUTO_SYNC_P_AND_P_EXECUTION_PLAN.md`](LIBRARY_AUTO_SYNC_P_AND_P_EXECUTION_PLAN.md)、[`adr/0006-library-auto-sync-publication-and-discovery.md`](adr/0006-library-auto-sync-publication-and-discovery.md)
- 远端曲库：[`REMOTE_MUSIC_SOURCE_RESEARCH.md`](REMOTE_MUSIC_SOURCE_RESEARCH.md)
- USB Hybrid：[`USB_EXCLUSIVE_HYBRID_STATUS.md`](USB_EXCLUSIVE_HYBRID_STATUS.md)、[`adr/0004-usb-exclusive-hybrid.md`](adr/0004-usb-exclusive-hybrid.md)
- 测试：[`TESTING.md`](TESTING.md)、[`AUTOMATED_FLOW_TESTING.md`](AUTOMATED_FLOW_TESTING.md)
