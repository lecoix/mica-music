# 文件夹独立发现与扫描成本验证

2026-09-13。对应“先验证 FOLDER → 分阶段测速 → 按瓶颈优化 → 决定混合来源”的实施顺序。

## 本次范围

- 保留现有 FolderScanner、标签/歌词解析、Room 与 ADR-0002/0006 发布协议。
- 补真实 Android provider → 空目录 FULL → 新增未索引音频 → FULL → Room → owner 重建回归。
- 增加 AUTO 组件分阶段 profiler；不注入生产并发/调度参数，不以 readiness 调用冒充普通自动调度。
- 将用户入口“扫描全部音乐”明确为“扫描系统音乐库”；旧名称仍可搜索。空结果提示引导选择真实音乐目录。
- 工作树已有大量其他扫描/性能改动；它们不是本次新增修改，不把它们的性能收益归于本次工作。

## 独立发现

`SafUnindexedDiscoveryContractTest` 使用 QA 私有缓存内的 WAV 与真实 DocumentsProvider，没有向 MediaStore 插入对象，也没有发送媒体扫描广播。断言空目录 COMPLETE、首次空库成功、随后新增歌曲成功、真实 Room 行、持久授权、重新创建 owner 后恢复及再次扫描身份稳定。

另经真实 MainActivity 设置入口 → Google DocumentsUI → 系统 externalstorage provider 授权独立目录：

`Music/MicaScannerGate-20260912`（含 `.nomedia`，不使用用户歌曲）。

先扫描空目录，再通过 ADB 写入生成的 65 秒 PCM WAV。MediaStore Audio 精确名称查询返回 `No result found`，FOLDER 扫描后的主页显示该曲。强停 QA 进程再启动仍显示该曲，来源与 tree grant 恢复。这是实际 picker/callback/扫描/持久化/冷启动证据；不外推所有 OEM/provider。

## 初始设备数据

设备 Xiaomi 22081212C，Android 12，约 12 GB RAM；QA 包 `com.mica.music.qa`，0.4.1-qa/code55，初始测试安装时间 2026-09-12 20:25:47。这些是该安装快照数据，当前工作树后续变化需重新验证。

### QA provider 的组件计时

`SafAutoStageProfileTest` 包装现有 scanner/probe seam，读取现有 publication timing。排除通知、scheduler 等待与 UI 延迟。WAV 测试数据无内嵌封面；100 首组件场景未给每首附带歌词，不能当成完整万曲库负载。

| 场景 | 总墙钟 | 枚举 | 后核验 | 探测 | 实际探测数 |
|---|---:|---:|---:|---:|---:|
| 无变化 1/2/3 | 152/65/53 ms | 28/11/12 ms | 0 | 0 | 0 |
| 仅改歌词 | 208 ms | 9 ms | 12 ms | 88 ms | 1 |
| 新增 1 首 | 187 ms | 13 ms | 8 ms | 62 ms | 1 |
| 新增 100 首 | 6042 ms | 92 ms | 79 ms | 5249 ms | 100，分两轮 |

其余时间含计划、歌词暂存、关联处理、发布等，不能把未测部分一概称为数据库耗时。`lastPublication` 只代表最后一轮，不能加总成两轮发布耗时。

`SafTenKMetadataProfileTest` 的 10,000 条轻量目录记录三次墙钟为 478/304/234 ms，均为 COMPLETE、两次直接 query。它没有读取一万首完整逐字歌词，不是完整扫描或 8 GB 容量 PASS。

### 系统 SAF 的普通 AUTO

真实目录写入，不调用 readiness、不发送手动刷新、不主动补 MediaStore 音频索引：

- 新增一首：写入结束设备时钟 `1789227577578`；AUTO 发布日志 `01:39:40.347`，约 2.77 秒。音频集合仍查不到此文件。
- 新增 100 首 WAV + 100 份短逐字 LRC：开始 `1789227609918`，推送完成 `1789227612278`；AUTO 分 64/36 两轮发布，最终 `01:40:22.820`。从推送完成约 10.54 秒，从开始约 12.90 秒。
- 两轮前后目录核验分别 202+188、212+237 ms，合计 839 ms；不能据此认定全部剩余时间都是探测。
- 主页确认 103 首（3 首基线 + 100 首新增）。执行前点击了 QA 播放，但没有录制声学输出或连续 underrun 证据，不能宣称音频无断音验收通过。
- 一次进程采样 PSS 186842 KiB / RSS 297240 KiB，不是峰值测量，也不是 8 GB 设备结果。

## 决策

1. FOLDER 已有独立发现能力，本次通过真实回归把它锁住，并使入口语义准确。
2. 当前观测没有支持新增 native 枚举器的充分收益：100 首组件场景探测约占 87%，系统场景枚举/核验合计不足一秒。继续复用现有 provider 路线。
3. 不据这些单次数据新增未经 A/B 验证的性能修改；其他任务已有的探测/定向核验改动仍需各自负责回归与同设备 A/B。
4. 暂不把 DEVICE 与 FOLDER 合并成一个来源。需要先设计跨 URI 身份、来源覆盖/删除证明、歌单/统计迁移和来源切换回滚；简单 union 会违反当前 snapshot authority 语义。需要目录完整性时使用 FOLDER，DEVICE 继续承担系统索引入口。

## 容量与一致性边界

本次生产修改仅为静态文案，额外空间为 O(1)，没有增加歌曲/歌词常驻副本。测试 provider 的 100 WAV 场景只在 debug 使用。10,000 首完整逐字歌词、8 GB 设备上的全流程峰值、热量和后台表现仍未验收，不宣称安全或提速。

未新增生产异步副作用。既有 generation/request owner、IO 后复验、Room/歌词/缓存实际写入与 `storeSyncMutex`/publication seam 均保持原实现；本次回归不替代它们已有的交错测试。

## 复跑与证据

当前工作树复验曾遭遇 MIUI 冻结：`/proc/<qa-pid>/wchan` 为 `do_freezer_trap`，即使屏幕 Awake 也会冻结没有前台 Activity 的 instrumentation。该轮已中断，计时无效，保留 `scanner-freezer-evidence.txt` 与 `scanner-device-tests-freezer-interrupted.log`。新增 debug-only `ScannerContractHostActivity` 与 `ScannerDeviceHostRule`，让三组组件测试保持前台；宿主不创建 MusicLibrary，不改变扫描调度/并发。它只解决测试宿主冻结，不代表产品后台扫描已获得豁免。

最终前台宿主复验：**3 个设备测试全部通过，9.049 秒**，设置搜索 **5 个 JVM 测试通过**，QA APK 与 androidTest 构建通过。最终 APK SHA-256：`EB4B93F7D1C1D9B864368CF5C44B1031AC4A466DF1C046025B6A095E50ED205C`。证据目录 `.scratch/scanner-discovery-20260913-015029/`。真机设置页新名称与引导文案无截断，截图 `.scratch/scanner-settings-final.png`。

最终组件样本：无变化 27/13/16 ms、仅改歌词 58 ms、新增一首 61 ms、新增 100 首 3417 ms（探测 2599 ms、枚举/后核验 30/26 ms）；10k 元数据 261/149/155 ms。测试宿主、缓存与工作树均有变化，**不把初始与最终样本作为受控 A/B，也不宣称本次实现了相应提速**。

清空本次专用 QA 包的数据后，真机首次进入页也已检查，目录扫描与系统音乐库两个入口及说明无截断，截图 `.scratch/scanner-initial-final.png`。QA 的临时授权、诊断配置和私有测试数据已重置；外部 `MicaScannerGate-20260912` 目录按已知生成文件逐项清理，确认空目录后移除。未操作正式包数据或用户歌曲。

推荐复跑入口（自动校验包名/目标、保留证据、拒绝只有进程退出而无测试成功的结果）：

```powershell
.\scripts\run-scanner-discovery-tests.ps1 -Serial <serial>
```

构建必须使用 `-Pmica.qaSideBySide=true`。安装后先用 `adb shell pm list instrumentation` 验证目标；本仓当前 test applicationId 为 `com.mica.music.test`，target 为 `com.mica.music.qa`，不要猜成 `.qa.test`。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest '-Pmica.qaSideBySide=true' --no-daemon --no-configuration-cache --no-parallel
# 安装两个生成 APK 后，针对确认过的设备：
adb -s <serial> shell am instrument -w -r -e class com.mica.music.data.SafUnindexedDiscoveryContractTest,com.mica.music.data.SafAutoStageProfileTest,com.mica.music.data.SafTenKMetadataProfileTest com.mica.music.test/androidx.test.runner.AndroidJUnitRunner
```

本地原始证据（`.scratch` 不随 Git 分发）：`scanner-device-contract.log`、`scanner-stage-profile.log`、`scanner-stage-timings.log`、`scanner-system-found.xml`、`scanner-system-restored.xml`、`scanner-system-mediastore-empty.txt`、`scanner-system-complete.log`、`scanner-burst-start.txt`、`scanner-burst-push-complete.txt`、`scanner-burst-meminfo.txt`。真实 UI 操作辅助脚本为 `scanner-ui-tools.ps1`，拒绝错误包和旧坐标。
