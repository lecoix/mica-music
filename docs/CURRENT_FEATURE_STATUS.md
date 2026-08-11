# 当前功能状态

> 最后更新：2026-08-12。本文记录近期已经接入代码的功能，以及“代码完成”和“真机验收完成”之间的边界。实现细节仍以当前源码和测试为准。

## 已接入

| 功能 | 当前行为 | 验证与边界 |
| --- | --- | --- |
| 自定义壁纸 | 设置页选图后先生成 app 私有候选文件并自动打开全屏裁切窗口；只有点“应用”才同时提交图片、裁切参数和持久化路径，取消会删除候选并保留原壁纸。支持统一遮罩和 0–32dp 模糊；主界面与设置页使用同一壁纸，播放页/歌词页明确排除。极简 HiFi 迷你栏通过 Activity 级共享视口取得同坐标切片。新导入、替换与清除由 generation + 统一写入 seam 串行，旧请求不能删除或发布到新结果之上；失效路径和图片加载失败回退云母背景。 | `AppUiSettingsWallpaperRobolectricTest` 覆盖“准备→裁切→应用”和取消替换；`AppWallpaperStoreTest` 覆盖旧导入晚完成、清除与旧导入交错的实际文件/偏好发布；裁切窗口显示、私有图片解码、偏好、裁切手势及全视口切片几何有 JVM/Robolectric 测试。遮罩、模糊接缝、系统栏、横竖屏、手势触感及 2160px 图片 GPU/内存仍需真机验收。裁切编辑暂未提供 TalkBack 专用调整动作。 |
| 外部音频打开 | 消费文件管理器发送的 `ACTION_VIEW`、`content://` 和 `ClipData` 音频请求；冷启动走 `onCreate`，warm 启动走 `onNewIntent()`。已知曲目复用曲库记录，未知曲目进入进程内 `TransientPlaybackCatalog` 并替换当前队列，不写入曲库。 | `ExternalAudioOpenTest`、MediaSession/可信媒体项契约测试覆盖；Android Auto/OEM controller 仍需按发布前设备矩阵验收。 |
| 文件夹扁平浏览 | `FolderBrowseMode.MUSIC_FOLDERS` 持久化“扁平浏览”选择，只列出直接包含歌曲的完整目录路径；仅包含子目录的父目录不单独列出。分组缓存按曲库 revision 失效，分组对象不保留歌曲或歌词 payload。 | `LibraryBrowseTest`、`LibraryScaleTest` 和偏好恢复测试覆盖；10,000 首歌曲的代码级边界已有测试，但尚未完成 8 GB 真机峰值测量。 |
| 播放页横屏 | 播放页、队列侧栏及相关全局窗口布局已接入；`CUSTOM_STANDARD`、`PAUSE_FOLD`、`RETRO_3D` 等模式有独立契约或回退规则。平行/复古主题在横屏稳定播放态可长按标题进入封面流沉浸，仅保留背景、封面流及能力允许的封面底边进度/频谱，并隐藏系统栏；返回、旋转、切主题或进入歌词页会退出。 | JVM 布局/策略测试覆盖；屏幕比例、旋转生命周期、音频连续性、系统栏恢复和视觉效果仍需真机验收。 |
| 自定义标准播放页自由布局 | `CUSTOM_STANDARD` 已接入播放页内直接布局编辑：竖屏空白区域长按可进入，设置页主题详情也可直接展开播放页进入编辑。六个现有组件支持二维拖动、双指缩放、选择/显隐；编辑过程中只更新局部草稿，保存后才持久化 `PlayerLowerLayoutConfig.freeformEnabled` 与每组件 offset，取消/返回/旋转横屏/切主题丢弃草稿。 | `PlayerLowerLayoutConfigTest`、`PlaybackUiPreferencesRobolectricTest`、`CustomPlayerLowerPanelTest`、设置搜索/导航相关测试覆盖配置与编辑契约；不同屏幕尺寸、手势冲突、TalkBack 与长时间视觉稳定性仍需真机验收。 |
| Letter / 信笺歌词 | `LETTER` 已是可选歌词页主题，播放页挂载信笺渲染，设置中支持自定义朱印图片、大小、浓度和旋转。 | 当前实现类仍标注为 prototype，因此应视为已接入的实验性主题，不应描述为已经完成最终视觉验收。 |
| 睡眠定时 | 使用墙钟倒计时，通过当前 ExoPlayer/MediaController 音量接口在结束前渐弱并暂停；切歌、换歌单或手动暂停不会自动取消，最近使用时长会保存。 | 控制器与 UI 测试覆盖；渐弱、锁屏、后台和系统时间变化仍属于设备验收场景。 |
| Hi‑Res 标志 | 支持默认、黄底镂空、自定义图片三种 `HiResBadgeStyle`；自定义图片失效时回退默认样式。 | 样式持久化和解析测试覆盖；列表行仍不显示 Hi‑Res 标志。 |
| 歌单 Room 持久化 | 歌单列表与歌曲顺序迁入 Room `playlists` / `playlist_songs`；旧 `mica_playlists` JSON 首次启动一次性迁移；所有增删改先写库成功再更新内存，写失败不发布内存变更。 | `PlaylistStoreTest`、`RoomMigrationContractTest`、`DatabaseMigrationTest` 覆盖迁移与增删改；升级用户的真实旧数据恢复仍需发布前设备检查。 |
| 播放状态所有权收拢 | 队列、时间轴、调音三块可变 UI 状态分别由 `PlaybackQueueCoordinator` / `PlaybackTimelineCoordinator` / `PlaybackTuningCoordinator` 收口；连接与镜像结果带 generation/request/revision 校验，旧连接回调与陈旧队列镜像被拒绝。 | `PlaybackTimelineCoordinatorTest`、`PlaybackTuningCoordinatorTest`、`PlayerControllerQueueModelTest`、`PlaybackQueueMirrorTest`、`PlayerControllerBoundaryTest` 覆盖；跨 binder 时序仍需真实组件契约观察。 |
| 音频管线协调与 offload 熔断 | `AudioPipelineCoordinator` 串行处理 EQ、频谱 tap、offload 偏好、熔断与路由事件；`AudioOffloadCircuitBreaker` 检测真实 offload AudioTrack 缓冲但未起播，确认回 PCM 且播放推进后才按 build fingerprint 记录失败并停用 offload。 | `AudioPipelineCoordinatorTest`、`AudioOffloadCircuitBreakerTest`、`AudioOffloadPreferencesRobolectricTest` 覆盖；不同 ROM/固件的 offload 行为仍需设备矩阵验收。 |
| 外部队列恢复边界 | 外部 `ACTION_VIEW` 音频只有 MediaStore authority 或已持久化 grant 才标记可恢复；不可存续的临时队列不写入 `ServicePlaybackStateStore`，避免重启后恢复失效 URI。 | `ExternalAudioOpenTest`、`TransientPlaybackCatalogTest`、`ServicePlaybackStateCoordinatorTest` 覆盖；真实 provider 与进程重启仍需设备验收。 |
| 歌词结构化角色与逐字时间 | `LyricLine` / `LyricCue` 收进数据层；入库不再用细空格把单行文本改写成 `TRANSLATION`，显示拆分仅发生在展示层；歌词云按 token 角色与行结束时间计算逐字进度。 | `LyricDisplayRowsTest`、`LyricsCloudLayoutTest`、`LyricsTimelineEngineTest` 覆盖。 |
| 曲库身份与 Room v17 | 当前 Room schema 为 v17；`14→15` 重建 album browse group 纳入专辑艺术家，`15→16` 新增 `songs.embeddedLyricsProbeRevision`，`16→17` 新建 `playlists` / `playlist_songs`；首次访问曲库时把旧 URI hash ID 迁移到稳定文档 ID，并同步播放列表、播放历史、播放会话等引用。 | `DatabaseMigrationTest`、`RoomMigrationContractTest`、`SongIdentityMigrationTest` 和相关曲库/歌单测试覆盖；升级后的真实旧库恢复仍需发布前设备检查。 |

## 仍然开放的事项

- 粒子封面的 GLES 播放路径已是现网主路径，但 `ThreeParticleCoverHost` 和 `assets/particle_cover` WebView fallback 尚未删除；WebView 退役、parity 和性能验收仍在 [`PARTICLE_COVER_OPENGL_MIGRATION.md`](PARTICLE_COVER_OPENGL_MIGRATION.md) 的范围内。
- Android Auto、OEM controller/车机和 MediaSession 重连属于实现边界之外的真机验收，不应因为 JVM 契约测试通过而标记为完成。
- “多文件夹读取”仍是未实现的扫描能力；它不等于已经实现的曲库内“扁平浏览”。
- offload 熔断的失败记录绑定 build fingerprint；同一设备升级系统/固件后会给一次新尝试，跨 ROM 的行为仍须在设备矩阵复验。
- 10,000 首歌曲、每首完整逐字歌词、8 GB Android 手机的启动/扫描/排序/缓存峰值仍需真实设备测量；现有容量测试不能替代该验收。

## 相关源码入口

- `ExternalAudioOpen.kt`、`TransientPlaybackCatalog.kt`、`MainActivity.kt`
- `AppWallpaperStore.kt`、`AppWallpaperImporter.kt`、`AnimatedTheme.kt`、`CustomWallpaperCropDialog.kt`
- `LibraryBrowse.kt`、`LibraryBrowseSettings.kt`、`FolderBrowseModeSheet.kt`
- `LandscapePlayerPolicy.kt`、`NowPlayingScreen.kt`
- `LyricsPageSettings.kt`、`LetterLyricsPrototype.kt`、`SleepTimerController.kt`
- `HiResBadgeStyle.kt`、`MicaDatabase.kt`、`MIGRATION_14_15/15_16/16_17`、`SongIdentityMigration.kt`
- `PlaylistStore.kt`、`PlaylistRepository.kt`、`PlaylistDao.kt`
- `PlaybackQueueCoordinator.kt`、`PlaybackTimelineCoordinator.kt`、`PlaybackTuningCoordinator.kt`、`PlaybackConnectionSession.kt`
- `AudioPipelineCoordinator.kt`、`AudioOffloadCircuitBreaker.kt`、`AudioOffloadPreferences.kt`
