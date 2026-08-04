# 当前功能状态

> 最后更新：2026-08-04。本文记录近期已经接入代码的功能，以及“代码完成”和“真机验收完成”之间的边界。实现细节仍以当前源码和测试为准。

## 已接入

| 功能 | 当前行为 | 验证与边界 |
| --- | --- | --- |
| 自定义壁纸 | 设置页选图后先生成 app 私有候选文件并自动打开全屏裁切窗口；只有点“应用”才同时提交图片、裁切参数和持久化路径，取消会删除候选并保留原壁纸。支持统一遮罩和 0–32dp 模糊；主界面与设置页使用同一壁纸，播放页/歌词页明确排除。极简 HiFi 迷你栏通过 Activity 级共享视口取得同坐标切片。新导入、替换与清除由 generation + 统一写入 seam 串行，旧请求不能删除或发布到新结果之上；失效路径和图片加载失败回退云母背景。 | `AppUiSettingsWallpaperRobolectricTest` 覆盖“准备→裁切→应用”和取消替换；`AppWallpaperStoreTest` 覆盖旧导入晚完成、清除与旧导入交错的实际文件/偏好发布；裁切窗口显示、私有图片解码、偏好、裁切手势及全视口切片几何有 JVM/Robolectric 测试。遮罩、模糊接缝、系统栏、横竖屏、手势触感及 2160px 图片 GPU/内存仍需真机验收。裁切编辑暂未提供 TalkBack 专用调整动作。 |
| 外部音频打开 | 消费文件管理器发送的 `ACTION_VIEW`、`content://` 和 `ClipData` 音频请求；冷启动走 `onCreate`，warm 启动走 `onNewIntent()`。已知曲目复用曲库记录，未知曲目进入进程内 `TransientPlaybackCatalog` 并替换当前队列，不写入曲库。 | `ExternalAudioOpenTest`、MediaSession/可信媒体项契约测试覆盖；Android Auto/OEM controller 仍需按发布前设备矩阵验收。 |
| 文件夹扁平浏览 | `FolderBrowseMode.MUSIC_FOLDERS` 持久化“扁平浏览”选择，只列出直接包含歌曲的完整目录路径；仅包含子目录的父目录不单独列出。分组缓存按曲库 revision 失效，分组对象不保留歌曲或歌词 payload。 | `LibraryBrowseTest`、`LibraryScaleTest` 和偏好恢复测试覆盖；10,000 首歌曲的代码级边界已有测试，但尚未完成 8 GB 真机峰值测量。 |
| 播放页横屏 | 播放页、队列侧栏及相关全局窗口布局已接入；`CUSTOM_STANDARD`、`PAUSE_FOLD`、`RETRO_3D` 等模式有独立契约或回退规则。 | JVM 布局/策略测试覆盖；屏幕比例、旋转生命周期、音频连续性和视觉效果仍需真机验收。 |
| Letter / 信笺歌词 | `LETTER` 已是可选歌词页主题，播放页挂载信笺渲染，设置中支持自定义朱印图片、大小、浓度和旋转。 | 当前实现类仍标注为 prototype，因此应视为已接入的实验性主题，不应描述为已经完成最终视觉验收。 |
| 睡眠定时 | 使用墙钟倒计时，通过当前 ExoPlayer/MediaController 音量接口在结束前渐弱并暂停；切歌、换歌单或手动暂停不会自动取消，最近使用时长会保存。 | 控制器与 UI 测试覆盖；渐弱、锁屏、后台和系统时间变化仍属于设备验收场景。 |
| Hi‑Res 标志 | 支持默认、黄底镂空、自定义图片三种 `HiResBadgeStyle`；自定义图片失效时回退默认样式。 | 样式持久化和解析测试覆盖；列表行仍不显示 Hi‑Res 标志。 |
| 曲库身份与 Room v15 | 当前 Room schema 为 v15，`14→15` 重建 album browse group 以纳入专辑艺术家；首次访问曲库时还会把旧 URI hash ID 迁移到新的稳定文档 ID，并同步播放列表、播放历史、播放会话等引用。 | `DatabaseMigrationTest`、`SongIdentityMigrationTest` 和相关曲库测试覆盖；升级后的真实旧库恢复仍需发布前设备检查。 |

## 仍然开放的事项

- 粒子封面的 GLES 播放路径已是现网主路径，但 `ThreeParticleCoverHost` 和 `assets/particle_cover` WebView fallback 尚未删除；WebView 退役、parity 和性能验收仍在 [`PARTICLE_COVER_OPENGL_MIGRATION.md`](PARTICLE_COVER_OPENGL_MIGRATION.md) 的范围内。
- Android Auto、OEM controller/车机和 MediaSession 重连属于实现边界之外的真机验收，不应因为 JVM 契约测试通过而标记为完成。
- “多文件夹读取”仍是未实现的扫描能力；它不等于已经实现的曲库内“扁平浏览”。
- 10,000 首歌曲、每首完整逐字歌词、8 GB Android 手机的启动/扫描/排序/缓存峰值仍需真实设备测量；现有容量测试不能替代该验收。

## 相关源码入口

- `ExternalAudioOpen.kt`、`TransientPlaybackCatalog.kt`、`MainActivity.kt`
- `AppWallpaperStore.kt`、`AppWallpaperImporter.kt`、`AnimatedTheme.kt`、`CustomWallpaperCropDialog.kt`
- `LibraryBrowse.kt`、`LibraryBrowseSettings.kt`、`FolderBrowseModeSheet.kt`
- `LandscapePlayerPolicy.kt`、`NowPlayingScreen.kt`
- `LyricsPageSettings.kt`、`LetterLyricsPrototype.kt`、`SleepTimerController.kt`
- `HiResBadgeStyle.kt`、`MicaDatabase.kt`、`MIGRATION_14_15`、`SongIdentityMigration.kt`
