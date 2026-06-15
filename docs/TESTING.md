# Mica 测试指南

## 单命令质量门

```powershell
.\gradlew :app:micaCheck --no-configuration-cache
```

该任务包含 Debug 编译、Lint、JVM/Robolectric 测试和 Roborazzi 截图比对，不需要模拟器、真机、网络或真实媒体库。

完整截图矩阵：

```powershell
.\gradlew :app:micaScreenshotFull --no-configuration-cache
```

夜间强化测试（包含 10,000 次固定种子解析模糊测试）：

```powershell
.\gradlew :app:micaNightlyCheck --no-configuration-cache
```

更新截图前先人工检查差异，再运行：

```powershell
.\gradlew :app:micaRecordScreenshotFull --no-configuration-cache
```

基线位于 `app/src/test/snapshots`。测试固定 API、尺寸、字体缩放和静态背景，禁止把动态动画或网络图片带入基线。

## 测试分层

- 纯 JVM：队列策略、播放时钟、音频算法、歌词和二进制解析。
- Robolectric：Room、迁移、偏好损坏恢复、Activity 多 API 创建。
- Roborazzi：主界面边缘覆盖和关键尺寸布局。
- 设备验收：真实解码、AudioTrack、蓝牙、音频焦点、系统通知及 SAF。

测试样本只能使用自行生成的最小合法片段、损坏片段或固定种子随机字节，不提交完整歌曲。

## 覆盖目标

- 纯算法和解析器：行覆盖至少 90%。
- 播放状态机、队列和数据库：分支覆盖至少 80%。
- Compose UI 不用覆盖率数字代替场景审查，以截图矩阵完整性为准。

这些是新增和修改风险模块的合并要求。遗留模块在触及时补齐，不能通过降低阈值掩盖缺口。

## 发布前设备验收

- Android 8/8.1 与 Android 14+ 各至少一台真机。
- MP3、FLAC、ALAC、DSF、DFF 实际播放，长曲 seek 与连续切歌。
- 蓝牙、耳机拔出、音频焦点、锁屏控制和后台播放。
- 通知权限拒绝、划掉任务、进程恢复和重启。
- SAF 文件夹授权及重启后的持久权限。
- 删除、分享、睡眠定时和 EQ。
- 手势导航、三键导航、横屏、浅色和深色模式。
- 手势提示线周围必须由应用背景完整覆盖。

设备验收失败时，即使 JVM 测试全部通过也不得发布。

## 混合播放架构验收

每次播放测试都应导出诊断日志，并确认 `route backend`、`request`、`source` 与
`AudioQuality` 字段符合预期。

- 常见格式必须显示 `backend=MEDIA3`，不得出现 `decode-input-copy`。
- ALAC 首次必须先走 Media3；只有 extractor/decoder/decode 错误允许一次 SOFTWARE 回退。
- DSD 必须显示 SOFTWARE；蓝牙实际输出候选不得高于 48kHz。
- SOFTWARE 日志必须显示 `mode=stdout` 和 `transport=stdout`，`alac_stream` 不得生成 `.pcm` 文件。
- SOFTWARE seek 必须启动带目标 `-ss` 的新 FFmpeg 管道，并终止旧进程。
- 连续快速切歌 20 次后只能播放最后一次请求，FFmpeg 进程不得叠加。
- 快速交替 Media3 与 SOFTWARE 曲目时，旧任务不得覆盖最终曲目的 PCM、输入缓存 lease 或播放状态。
- HIFI 必须显示 `dsp=false offload=true`；DSP 必须显示 `dsp=true offload=false`。
- 耳机/蓝牙断开必须暂停，禁止切到扬声器继续播放。
- 划掉 Activity 后，Media3 与 SOFTWARE 播放都应继续并可从通知控制。
- 暂停后划掉任务并重新打开应用，MediaController 必须重新连接 Service。
- Service 重启后必须按歌曲 ID 恢复队列中的当前曲和位置；恢复状态必须为暂停，即使持久化时 `playWhenReady=true`。
- repeat/shuffle 必须从 MediaSession 恢复并反映到 UI，Activity/ViewModel 不得用默认模式覆盖。
- 旧 request 的 prepared、position、playing、ended 和 error 回调不得改变当前 Service request。
- 同一 source revision 的 Media3 → Software fallback 最多一次；权限、文件缺失和输出错误不得 fallback。
- 软件播放期间执行插入、移动、删除队列项后，通知 timeline、当前索引和自然下一首顺序必须一致。
- SOFTWARE 申请音频焦点被拒绝时必须保持暂停，不得与其他音源叠播。

自动回归已覆盖 active cache lease、Controller 断连重连，以及 limiter 单调性和 `-1 dBFS` 峰值门槛。

性能门槛：

- 常见格式热切歌 p95 ≤ 250ms，冷切歌 p95 ≤ 500ms。
- ALAC Media3 p95 ≤ 500ms；首次软件回退 p95 ≤ 1,200ms。
- Media3 seek p95 ≤ 350ms；软件 seek p95 ≤ 900ms。
- 普通 FLAC 平均 CPU 相比旧全 FFmpeg 基线至少下降 50%。
- 输入缓存 ≤ 512MB；PCM 临时文件占用必须为 0。

### 并行真机 QA 包

设备上已有不同签名的 `com.mica.music` 时，可构建并行安装包，不需要卸载或清除现有数据：

```powershell
.\gradlew :app:assemblePerf "-Pmica.qaSideBySide=true" --no-configuration-cache
adb install -r app/build/outputs/apk/perf/app-perf.apk
```

并行包名为 `com.mica.music.qa`，版本名追加 `-qa`。该参数还会仅对 QA Perf 包启用调试，
便于使用 `run-as` 读取 `files/diagnostics/current-session.log`；普通 Perf/Release 仍不可调试。

覆盖安装后必须重新确认运行时权限和 AppOps。部分 MIUI 版本会把
`READ_EXTERNAL_STORAGE` 恢复为拒绝，即使安装命令成功。
