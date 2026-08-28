---
status: accepted
---

# 本地音乐 MV 使用音乐权威的单 ExoPlayer 时间线

文件夹曲库允许将同目录、同基本文件名的 MP4 作为歌曲的 `musicVideoUri`。音乐文件始终是唯一音频来源；`MusicVideoMediaSourceFactory` 将原歌曲过滤为 audio-only、MP4 过滤为 video-only，再以 `MergingMediaSource(adjustPeriodTimeOffsets = true, clipDurations = false)` 合并。歌曲 `MediaItem`、mediaId、队列、时长、通知、统计和音频输出路径保持权威，不允许双播放器同步或播放 MP4 内嵌音轨。

## 配对与持久化

- 文件夹扫描只记录 MP4 的 URI、目录、基本文件名、大小和修改时间，不打开、解析或解码视频。
- `MusicVideoMatcher` 先做原始基本文件名精确匹配，再做 NFKC、空白折叠、`Locale.ROOT` 大小写归一化；每个目录/名称组必须恰好一首音乐和一个 MP4。
- 多音频格式、多 MP4、跨目录和带 `MV` / `Official Video` 等后缀均不猜测。
- Room v21 保存 nullable URI 与由 URI、大小、修改时间组成的 revision；MediaStore 和临时外部歌曲不携带 MV。
- 匹配发生在完整扫描结果返回前，最终 Room/内存发布继续受 `scanGeneration`、`storeRevision`、`storeSyncMutex` 约束。

## 开关与 Surface 协议

- `music_video_enabled` 独立、默认关闭，只在标准封面设置中显示。
- Service 中的 `MusicVideoPreferenceOwner` 是请求值和每个队列项有效值的 owner。开关变化不替换当前项，只重写非当前项；切到不同 mediaId 后再更新旧项。同步循环在每次 `replaceMediaItem` 前复验 generation。
- Exo 默认禁用 video track。播放页处于 RESUMED、标准封面可见且未进入歌词页时，`MusicVideoOutputCoordinator` 才签发绑定 TextureView、mediaId、Controller identity 的 lease，设置 Surface 并启用 video track。
- detach、切歌、Controller 断开和 Service 播放栈重建都校验 lease/identity；旧 detach、旧首帧和旧尺寸事件不得影响新输出。播放栈重建通过 session event 让同一有效 lease 重新绑定。
- UI 维持静态封面底层；当前 lease 的首帧到达后才淡入。视频在纯黑容器内按像素宽高比 Fit，禁止裁剪和拉伸。MV 有效时优先级为 `MV > 视频专辑封面 > 静态封面`。

## 错误与音质边界

- `MusicVideoFailureRegistry` 以 `songId + musicVideoRevision` 做会话级熔断。首次合并源错误保存队列、索引、位置、播放意图、循环/随机和 playback parameters，将当前项标为纯音频并原位重建；同 revision 不再尝试 MV。
- 纯音频重试再次失败时进入既有普通播放错误路径，不无限恢复。
- MV 不修改采样率、位深、音频 renderer、DSP、ReplayGain 或 USB 模式。构建/JVM 测试不能证明实际设备的 PCM/DSD 输出不变；Shared PCM、蓝牙、USB Exact PCM、DoP、Native DSD 必须分别留存真机日志后才可宣称通过。

## 并发审查清单

- 扫描 owner：`scanGeneration`；不可取消等待点与 Room/内存副作用沿用 ADR-0002 的复验和 `storeSyncMutex`。
- 开关 owner：`MusicVideoPreferenceOwner.generation`；无 IO/await；每个队列替换副作用前复验 generation；交错测试覆盖旧 generation 晚到。
- Surface owner：单个 `MusicVideoOutputCoordinator` lease；副作用是 set/clear Surface 与 video track enable/disable；交错测试覆盖旧 detach、旧首帧、Controller identity 和播放栈重建。
- 播放错误 owner：`ServicePlaybackRequestState.requestId` + source revision + MV revision registry；副作用是当前队列的纯音频重建；测试覆盖一次恢复和同 revision 熔断。

## 验收边界

JVM/Robolectric 覆盖配对、10k+10k 容量代理、Room 20→21、codec、开关 generation、Surface lease 和错误恢复。真实 H.264 素材的首帧、暂停/seek/0.5×–2×、短/长视频、30 分钟漂移及所有音频输出路径仍属于真机验收，未取得证据前不得标记完成。
