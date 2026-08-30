# 当前功能状态

> 最后更新：2026-08-30。本文以当前工作树的源代码、导出 schema 和现有自动化测试为准；JVM/构建通过不等于真机、OEM、视觉或音质验收通过。

## 当前已接入

- 播放统一走前台 `MicaMediaService` 管理的 Media3/ExoPlayer 单链路；普通格式、ALAC、DSF、APE 等按当前扩展解码配置进入同一播放服务。
- 播放队列、时间轴、调音和连接状态分别由对应 coordinator / session owner 管理；旧连接、旧请求和旧队列镜像结果按 generation、requestId 或 revision 丢弃。
- 曲库支持 MediaStore 和 SAF 文件夹扫描、增量同步、Room 冷启动恢复、封面缓存修复、内嵌/外挂歌词以及有界歌词缓存。完整曲库替换遵守 `scanGeneration`、`storeRevision`、`storeSyncMutex` 协议。
- 歌单持久化使用 Room `playlists` / `playlist_songs`；旧 `mica_playlists` JSON 只在首次迁移时读取，成功写库后才发布内存变更。
- 当前 Room schema 为 **v21**：`17→18` 增加单曲歌词偏移，`18→19` 增加 comment，`19→20` 增加响度分析字段，`20→21` 增加 `musicVideoUri` / `musicVideoRevision`。
- 播放页包含标准、自定义标准、粒子、平行封面带、复古立体和拍立得回忆六类封面行为；动效和 Compose/View 岛边界以 `docs/MOTION.md`、`docs/COVER_FLOW_IMPLEMENTATION.md` 为准。
- EQ、ReplayGain、声道平衡、响度分析、频谱 tap、offload 偏好与 offload 熔断已接入现有音频协调链；涉及音质的行为仍按设备和输出路径分别验收。
- 音效实验室（立体声宽度、低/高架、混响房间/阻尼/湿比、360° 环绕）默认关闭，opt-in 后走同一 Shared PCM 软件 DSP；USB 独占旁路；不把 `AudioQualityMode` 写成 DSP。听感与 USB 旁路仍需真机验收。未做压缩器/动态 EQ。
- USB Exclusive Hybrid 已有应用层 owner、权限/设备状态、PCM/DoP/实验 Native DSD 路径和诊断 UI；代码级测试与既有 SK02 证据不能外推到所有 DAC、手机、ROM 或发布包。

## 本地音乐 MV

- 文件夹扫描按同目录、同基本文件名匹配 MP4；先原名精确匹配，再做 NFKC、空白折叠和 `Locale.ROOT` 大小写归一化。多音频、多视频、跨目录、未知专辑或带版本后缀的候选不猜测。
- 音乐文件是唯一音频来源。MV 以 video-only source 与音乐的 audio-only source 合并到同一 ExoPlayer 时间线，不播放 MP4 内嵌音轨，也不改变采样率、位深、DSP、ReplayGain 或 USB 输出模式。
- `music_video_enabled` 默认关闭；仅标准封面、Activity 处于 RESUMED 且未进入歌词页时挂载视频 Surface。开关从下一首不同歌曲生效，当前歌曲不因开关变化而重建。
- 输出使用单一 TextureView lease，绑定 Surface、mediaId 和 Controller identity；旧 detach、首帧、尺寸、断连和播放栈重建事件不得影响新 lease。视频在纯黑 1:1 容器中按像素宽高比 Fit，不裁剪、不拉伸。
- MV 首帧成功前保留静态封面；当前 `songId + musicVideoRevision` 首次视频错误后只回退一次纯音频并保留队列、位置、播放意图、循环/随机和 playback parameters，同一 revision 不重复尝试。
- 已有 JVM/Robolectric 覆盖 matcher、10k+10k 容量代理、Room 20→21、实体/MediaItem 往返、generation、Surface lease、播放栈重建和错误熔断。真实 H.264 首帧、codec 兼容、暂停/seek/变速、短长视频、30 分钟漂移及各音频输出路径仍未验收。

## 发布与容量边界

- App 的 ABI 配置包含 `arm64-v8a` 和 `armeabi-v7a`；ABI split release workflow 预期生成 64 位、32 位和通用 APK，并在上传前检查每个自有 native library。未完成真实签名 Actions/Release 流程前，不把本地 unsigned 包称为正式发布包。
- 曲库容量基线是 10,000 首、每首完整逐字歌词、8 GB Android 手机。现有代码和容量代理测试支持有界索引/歌词缓存方向，但启动、扫描、排序、保存、同步和缓存的真实内存峰值仍需设备测量。

## 仍需验收或保留风险

- UI 的触摸、旋转、系统栏、TalkBack、Surface 首帧、耗电和不同屏幕比例需要真机验证。
- Android Auto/OEM controller、后台/锁屏/进程死亡、蓝牙和不同 ROM 的 MediaSession/音频焦点行为需要设备矩阵验证。
- Shared PCM、蓝牙、USB Exact PCM、DoP、Native DSD 的音频路径与音质不能由 MV 的 JVM 测试或 Exo timeline 构造证明。
- 多 DAC、扩展坞供电、USB 总线带宽、真实 32 位进程和发布签名包仍需按发布门禁单独验证。

## 相关权威文档

- 领域词汇与边界：[`CONTEXT.md`](../CONTEXT.md)
- 文档入口与权威文档分工：[`DOC_INDEX.md`](DOC_INDEX.md)
- 功能 living list：[`TODO.md`](TODO.md)
- 播放页契约：[`PLAYER_PAGE_CONTRACT.md`](PLAYER_PAGE_CONTRACT.md)
- 本地 MV 决策：[`adr/0005-local-music-video-playback.md`](adr/0005-local-music-video-playback.md)
- 测试和设备验收：[`TESTING.md`](TESTING.md)
