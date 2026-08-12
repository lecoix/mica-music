# USB 独占人工听感验收（SK02 / Perf）

## 验收边界

- 使用 `Perf` QA 包、同一台手机、Fosi Audio SK02 和同一副耳机/音箱。
- 先把 DAC 硬件音量调低，再逐步恢复到舒适音量。
- USB 独占不启用 EQ、频谱、变速或 ReplayGain；格式无法精确支持时应拒绝，不允许静默降位深或重采样。
- 每个步骤只记录是否听到 click、pop、静音缺口、重复片段、左右声道异常或明显音色变化。主观“更好听”不作为通过条件。

## 准备

1. 物理连接 SK02，打开 Mica 的“设置 → 音频与设备 → USB 独占输出”。
2. 确认系统权限弹窗后，状态应显示“USB 独占输出中”及实际格式。
3. 选择三类熟悉素材：安静开头/尾音、强瞬态鼓点、人声或持续音；至少包含 44.1 kHz 与 48/96 kHz 中一种高采样率素材。
4. 每项异常立即记下曲目、位置、动作和听感，不要用重复重试覆盖第一次失败。

## 操作矩阵

| # | 操作 | 次数/时长 | 通过条件 |
|---|---|---:|---|
| 1 | USB 独占稳态播放 | 每类素材 2 分钟 | 无 click、pop、dropout、声道异常 |
| 2 | 暂停 5 秒后恢复 | 5 次 | 暂停/恢复边界无爆音，恢复位置合理 |
| 3 | 随机 seek 到曲中、近尾和近头 | 每类 3 次 | 无旧片段串入、长静音或爆音 |
| 4 | 上一曲/下一曲，含 44.1↔48/96 kHz | 往返 10 次 | 格式切换无明显爆音，曲目/位置正确 |
| 5 | 播放中关闭 USB 独占，再重新开启 | 3 次 | SharedPcm 与 USB 切换均继续播放，无双路叠音 |
| 6 | 播放中物理拔出，3 秒后重插并确认权限 | 3 次 | 拔出后 SharedPcm 接管；授权后恢复原播放意图 |
| 7 | 暂停中物理拔出/重插并确认 | 2 次 | 全程保持暂停，不自行播放 |
| 8 | HOME、锁屏 2 分钟后返回 | 2 次 | 后台无 dropout，返回后控制和位置正确 |
| 9 | 与 SharedPcm 做同段落 A/B | 每类素材 2 轮 | 只判断异常与声道/速度；不要求响度完全相同 |

## 失败判定

任一可重复 click/pop、超过约 200 ms 的非素材静音、错误重复、左右声道异常、速度/音高变化、播放意图错误，均记为失败。若只出现一次，保留时间点和日志后再对同一动作复验两次；不能先清日志或重装覆盖证据。

## 通过条件

- 1–9 全部完成；没有可重复的听感异常。
- 设置页始终报告与实际路径一致的 ACTIVE/SharedPcm/fallback 状态。
- 日志中无 `PlaybackException`、非零 `transportErrorCode`、非零 `underrunBytes` 或非预期 fallback。
- 结束时关闭 USB 独占，确认 SK02 回到系统 `snd-usb-audio` 驱动。

## 2026-08-12 验收结果

- 构建：`0.2.4.4-qa` Perf QA；设备：Xiaomi `22081212C` / Android 12；DAC：Fosi Audio SK02。
- 完成稳态播放、暂停/恢复 5 次、44.1/48/96 kHz seek、超过 30 次跨采样率切换、USB↔SharedPcm 3 轮、播放中物理拔插 3 轮、暂停中物理拔插 2 轮、HOME/锁屏各 2 分钟、48/96 kHz A/B，以及 3 次冷启动首播。
- 人工听感未发现可重复 click、pop、dropout、声道/速度异常或 USB/SharedPcm 双路叠音；48/96 kHz SharedPcm A/B 未听出异常差异。
- 日志中 `underrunBytes=0`、`transportErrorCode=0`，无 data packet error、poll timeout 或 `PlaybackException`。播放中拔插均转 SharedPcm 并在授权后恢复原播放意图；暂停中拔插均保持暂停。
- 验收清理已确认 `AudioOutputPath=SharedPcm`，SK02 interface `2-1:1.1`/`2-1:1.2` 重新绑定 `snd-usb-audio`，应用无残留 usbfs FD。
- 本轮 logcat 与最终状态归档：`.scratch/usb-listening-acceptance/20260812-depth16-final/`。

本轮将 SK02-only P2 记为验收完成，但保留以下非隐藏观察项：

- 96 kHz 冷 seek 最慢约 3.9 秒，预热后约 0.6–1.5 秒；BUFFERING 期间 UI 短暂显示为暂停。播放意图未丢失，USB 重开后首写约 7 ms。
- USB↔SharedPcm 开关恢复约 0.8–2.4 秒，功能正确但仍有产品体验优化空间。
- HOME 后发生一次 `maximumDataCompletionGapUs=16811`，略高于 16 ms ahead window；当时 PCM 缓冲未耗尽，无 underrun 或听感异常。
- 队列命中已知不兼容 DSD/DFF 项时会报 `EXTRACTOR_UNSUPPORTED` 并停止；该现象与 USB 传输或锁屏无关，不在本 P2 修复中掩盖。
