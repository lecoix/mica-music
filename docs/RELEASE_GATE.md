# Mica 发布门禁矩阵（Release Gate）

> 最后更新：**2026-11-25**（基线 `v0.4.5 / build 60 / targetSdk 36 / Room v35`）。
> 目标：把发版前验证拆分为**可每次 100% 跑完的轻量必过门**与**按变更触发的条件门**，不再用“多机房/全硬件笛卡尔积”导致门禁名存实亡，同时守住「10,000 首 + 完整逐字歌词 + 8 GB 内存」设计基线与真实用户高频踩坑面。

---

## 原则

1. **只用真实 R8 Release 签名包做发版冒烟**：Debug / QA Perf / JVM 全绿不能替代正式 R8 Release（历史教训：`73727779` SMBJ/MBassador 反射构造函数崩溃仅在 R8 正式包触发）。
2. **明确区分“发版阻断”与“设计基线 / 条件抽检”**：
   - 10,000 首 + 完整逐字歌词 + 8 GB 内存始终是**代码设计与架构硬基线**（见 [`AGENTS.md`](../AGENTS.md)）；但真机大库重扫仅在改动扫描、Room、封面或歌词缓存链路时触发，不因改几行 UI 每次重刷 10,000 首。
   - 未实际测试过的系统版本、32 位进程、16 KB 页面设备、多 DAC 或车机，**绝不宣称已验收**，在发布说明/状态文档中按实标注。
3. **已有自动化守卫的旧重构不再人工重复手测**：历史迁移（`RoomMigrationContractTest`）、架构边界（`DataLayerDependencyStructureTest`）、解析/队列/统计状态机（`micaCheck`）由自动化回归承担。

---

## Gate 0：CI 自动门（每次 push / 发版自动，0 人工）

- [ ] `:app:compileDebugKotlin` + `:app:compileDebugAndroidTestKotlin` + `:app:lintDebug`
- [ ] `:app:testDebugUnitTest`（API 34 默认 + 显式 API 36 / 多 API 契约）
- [ ] `:app:assembleRelease`（启用 R8 `isMinifyEnabled = true`，产出 64 位 / 32 位 / universal 三包）+ `apksigner verify`
- [ ] 改 UI / 布局 / 主题时本地或 nightly 补充 `:app:micaCheck`（含 Roborazzi 截图比对）

> CI 入口见 [`.github/workflows/android-tests.yml`](../.github/workflows/android-tests.yml)、[`.github/workflows/android-nightly.yml`](../.github/workflows/android-nightly.yml)、[`.github/workflows/android-release.yml`](../.github/workflows/android-release.yml)。

---

## Gate 1：每次发版真机必测（P0 阻断，约 10–15 分钟）

> **环境**：**1 台主力真机** + CI 产出的 **正式签名 R8 Release APK**（覆盖安装到已有真实数据的上一正式版上）。

1. **覆盖升级与数据保留**
   - 直接覆盖安装新 Release APK（不清数据）；冷启动确认曲库、歌单、排序、主题/扫描设置及上次播放状态完整保留，无崩溃/乱码。
2. **核心格式播放、Seek 与焦点打断**
   - 实播至少 1 首 **MP3**、1 首 **FLAC**、1 首 **ALAC 或 DSF**；验证拖动进度条 seek、上/下一首切歌无卡 0 秒/长缓冲。
   - 触发一次 **拔耳机或断蓝牙**（必须暂停，不得外放）与一次 **来电/闹钟/语音音频焦点打断后恢复**（不闪退、能正常续播）。
3. **冷启动状态与随机模式恢复**
   - 开启 **随机模式** 播放并切换一首 → 强杀应用进程 → 重新打开：确认播放模式图标、队列与当前曲位置恢复正确，未回退为顺序或重置队列；确认默认不自动出声（除非开启启动自动播放）。
4. **后台持续播放、通知栏与桌面小组件**
   - 播放中划掉 Activity（清卡片）：音乐不断，通知栏/锁屏切歌与暂停可用，从通知栏或桌面图标重新进入后状态同步。
   - 点击桌面小组件冷启动/切歌可直达播放页并响应控制。
5. **R8 远端源最小闭环（防混淆/反射炸裂）**
   - 任选 1 个已配置的远端源（优先 **SMB** 或 **Emby/Navidrome**）：浏览/同步一次、播放 1 首并 seek，确认封面与歌词（若有）正常、无反射/混淆崩溃。
6. **浅色可读性与缓存健康度**
   - 切到**浅色模式**看一眼列表页、播放页与队列，确认文字/图标对比度清晰、编辑按钮不被状态栏遮挡。
   - 系统设置看一眼本应用缓存体积，确认无异常膨胀（如明显超出预期或逼近 GB 级）。

> 以上任一项失败均阻断打正式版公告；定位并修复后重跑失败项及关联冒烟。

---

## Gate 2：按变更触发（Conditional，仅触及对应模块时执行）

| 本版本改动范围 | 必须补充的定向验收 |
|---|---|
| **扫描器 / Room / 封面缓存 / 歌词缓存 / 自动同步** | • 外部修改 1 首曲目标签或新增 1 首音频后重扫，确认封面/标签/歌词正确刷新且无错配；<br>• 抽查大库扫描耗时、扫描前后缓存体积与内存峰值（代码层须继续证明符合 10k / 8 GB 上界，见 [`AGENTS.md`](../AGENTS.md)）；<br>• SAF 目录授权重启后仍有效。 |
| **Native C++ / NDK / TagLib / FFmpeg / `targetSdk` / 权限 / FGS** | • 运行静态 16 KB ELF LOAD + `zipalign -c -P 16 4` 检查（见 [`TARGET_SDK_36_MIGRATION.md`](TARGET_SDK_36_MIGRATION.md)）；<br>• 抽测高/低系统版本（如有设备）或 32 位 APK 安装启动。 |
| **USB 独占 / DSD / EQ / 音效实验室 / Offload 熔断** | • 用手头 DAC（如 SK02）验证 Shared PCM ↔ USB 独占切换、插拔重连与 DSD64/128；<br>• 音效实验室开启参数后确认 USB 独占链保持旁路；<br>• 导出诊断日志核对 `route` / `dsp` / `offload` 字段（详见 [`TESTING.md`](TESTING.md)「Exo 单链路播放验收」）。 |
| **播放页封面主题 / 歌词渲染 / 外部悬浮歌词 / 布局** | • 仅针对本次修改的主题与歌词模式测浅/深色 + 横屏；<br>• 涉及桌面/状态栏悬浮窗时实测注音对齐、未填充颜色/透明度、锁定穿透与顶部不裁切；<br>• 涉及视频封面/MV 时实测首帧、切歌、后台释放与一次纯音频回退。 |
| **频谱可视化（`SpectrumPcmTimeline` / `AudioSink`）** | • 连续播放中切后台再回前台 + 蓝牙输出，验证频谱恢复不断流、无明显时钟漂移。 |
| **MediaSession / 车机歌词 / 蓝牙控制** | • 蓝牙设备媒体键切歌/暂停、通知栏歌词开关后元数据恢复正常，且不误增播放次数。 |

---

## Gate 3：大版本 / 社区观察项（非日常小版本阻断）

以下场景硬件与环境依赖重，**不阻断日常 `0.4.x` 小版本发布**，安排在 `0.x.0` 大版本节点或收到社区具体设备报障时专项复现与验证，并在 [`CURRENT_FEATURE_STATUS.md`](CURRENT_FEATURE_STATUS.md) 中如实保留“未验收边界”：

- Android 8 / 8.1 老系统专项回归与真实 16 KB 页面 Android 15/16 设备运行时验证；
- 8 GB 内存真机全量 10,000 首 + 完整逐字歌词峰值内存实测；
- 多品牌 USB DAC / 扩展坞矩阵与 DSD256 稳定性；
- Android Auto DHU 与多 OEM 车机 MediaSession 矩阵；
- 折叠屏 / 平板 / 1:1 方屏下的标准、平行、3D 封面全主题排版；
- 低性能硬路由 + 移动硬盘的 SMB 大库高延迟与内嵌歌词压测；
- 视频封面 H.264 30 分钟长播音画漂移与耗电测量。

---

## 相关文档

- 当前功能状态与未验收边界：[`CURRENT_FEATURE_STATUS.md`](CURRENT_FEATURE_STATUS.md)
- 自动化测试与诊断契约：[`TESTING.md`](TESTING.md)、[`AUTOMATED_FLOW_TESTING.md`](AUTOMATED_FLOW_TESTING.md)
- Target SDK 36 与 16 KB 对齐：[`TARGET_SDK_36_MIGRATION.md`](TARGET_SDK_36_MIGRATION.md)
- 待办清单：[`TODO.md`](TODO.md)
