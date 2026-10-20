# 0.4.4-lx.1 迁移与发布审查

## 身份和更新隔离

- 基线：正式版 0.4.4 / build 59，提交 edb3d3bb。
- 旧实验完整快照：codex/lx-source-20261020 / 0ef04fdd。
- 发布分支：codex/lx-standalone-044；应用 Mica LX，applicationId com.mica.music.lx，versionCode 1。
- namespace 仍为 com.mica.music；Provider authorities 由 applicationId 派生。正式版和旧实验版数据不自动迁入。
- updates/lx.json 为专属更新清单；版本号必须为 x.y.z-lx.n，下载地址必须对应 lx-v 标签。正式版清单和 releases/latest 被拒绝。
- LX 分支仅有 LX 发布工作流，创建 prerelease 且 latest=false，无 Pages 写权限/部署步骤。正式分支工作流不变。
- release 签名复用仓库已有固定 Android 密钥；同证书、不同包名可分别安装和更新。

## 迁移保留与适配

保留 0.4.4 的 Room v35、LibraryOperationExecutor、持久化远端缓存、SMB、R8、音频处理及两个 ARM ABI 的原生库。旧 LibraryScanOrchestrator 的文本修复不迁入。没有音质降级、转码、修改 DSP/位深/采样率或改动 USB 路由。

迁入单脚本运行时、四平台搜索、持久化在线歌曲身份、按需歌词/封面、播放/队列/歌单操作。0.4.4 的异步 PlaylistStore 由其自身 Room/revision 协议写入；LX 在交接前复验搜索结果身份，已经接受的用户歌单操作使用已持久化歌曲身份，源切换不删除这些收藏。

## 异步副作用审查

| 流程 | Owner/token | 等待点、实际副作用与统一同步 seam | 验证 |
|---|---|---|---|
| 脚本导入/初始化/删除/停用 | LxSourceOwner generation/requestId | 文件读取、JS 初始化返回后 gate 内复验；AtomicFile 写/删、平台状态发布均走 gate | LxSourceOwnerTest：晚导入/初始化、失败持久化、删除与关闭 |
| 在线搜索/选择 | generation/searchRequest、结果对象身份 | 网络返回及 IO→Main 交接后复验；SQLite write/read 与 onlineTracks 发布同 gate；歌单交接后由 PlaylistStore mutationMutex/revision 管理 | LxOnlineOwnerTest、LxOnlineStorageTest，覆盖实际 SQLite 与歌单持久化 |
| 在线音频地址/运行时恢复 | generation/scriptId/runtime identity | musicUrl、恢复 mutex、初始化和重试返回后复验，URL 发布走 gate；不持久化短期音频 URL | LxOnlineOwnerTest，延迟旧结果与恢复后新 runtime |
| LX 歌词 | generation/resourceRevision | semaphore、IO、网络/解析后复验；仅 owner gate 内写入独立有界歌词缓存；源变更、记录更新和 close 在同 gate 清缓存 | LxResourcePublicationTest：让旧非取消加载停住，完成新加载后释放旧加载，检查新缓存仍在 |
| LX 封面 | generation/resourceRevision、已接受资源 token | 网络结束后在 gate 内落内存；单 writer 在 poll(POLLOUT) 就绪后写不超过 PIPE_BUF 的片段，写前 gate 内复验；等待后再次复验，15s 输出超时；不落磁盘 | LxResourcePublicationTest：旧加载不能回填、旧资源不能向观察者输出，LRU 淘汰不撤销同代际已接受的输出；Android smoke test 源码编译 |
| 正式远端/本地 snapshot | 原 0.4.4 owner 协议 | 未把 LX 作为 RemoteCatalogRepository 的全量 snapshot writer，原 storeSyncMutex/scanGeneration/storeRevision 路径保留 | 原回归套件 |

封面 poll/write API：Android [Os](https://developer.android.com/reference/android/system/Os)。未连接设备时，不能把编译或 JVM 测试当成实际 native pipe、QuickJS、MediaSession 播放验收。

## 一万首歌曲 / 完整逐字歌词 / 8 GB Android 的容量边界

- 本地曲库维持 0.4.4 的摘要加载和歌词懒加载；LX 不遍历或复制本地逐字歌词。
- 在线收藏上限 10,000；标题/歌手/专辑各不超过 256 字符，MusicInfo 单条不超过 8 KiB。数据库逐行投影摘要，原始 MusicInfo 不进入常驻 onlineTracks；原始 JSON 最坏约 80 MiB 在磁盘，非全量内存副本。
- metadata 字符串、RemoteTrackSummary 与 UI Song/Map 仍按歌曲数量增长；10,000 条标题/歌手/专辑的 UTF-16 字符内容上限约 15 MiB，此外有标识符、albumId、对象及集合开销。不能把字符上界当作总进程内存上界。
- LX 歌词缓存 16 MiB（最多 12 个计费条目）、封面缓存 16 MiB，资源加载并发 2，预取并发 1；歌词响应单份最多 2 MiB，图片最多 8 MiB。解析对象/字符串临时副本额外占用内存；没有启动时批量下载全部歌词。
- QuickJS 堆 32 MiB、栈 512 KiB、脚本 512 KiB、最多 4 个各 1 MiB 的脚本 HTTP 响应；无原生执行中断/独立进程隔离，死循环需重启应用。
- 容量测试以 10,000 条、每条接近 8 KiB MusicInfo 的 SQLite 记录验证摘要不含歌词及第 10,001 条被拒绝。**本轮未测量 8 GB 真机总 RSS、逐字歌词解析峰值或长时稳定性，因此仍有设备容量与运行时风险，不宣称已证明安全。**

## Native 页对齐

旧 wrapper-android 2.4.0 的 ARM64 ELF LOAD 对齐实测为 4096，已在发布前停止首次流水线并升级到上游固定版本 3.2.3（cfdc05fa）。上游 3.2.0 起支持 16 KB 页。发布检查会遍历实际 APK 中每个 ARM64 .so 的 PT_LOAD 对齐，必须至少为 16384；仍需真机验收，静态对齐不等于 native 行为验证。

## 本轮验证

2026-10-20 本地结果：

- Kotlin 主程序与 Android 测试源码编译通过。
- 迁移后完整 JVM 套件 418 个 suite / 2,574 项测试，0 failure / 0 error。最后的封面资源 token / QuickJS 3.2.3 适配后，LX 与更新通道定向回归 7 个 suite / 31 项测试再次全部通过，包含新增 LRU 淘汰测试。
- 10,000 条近 8 KiB 元数据容量测试通过（本机 Robolectric 约 5.4 秒，不代表手机耗时）。
- JS preload 协议 5 项测试通过；LX 版本/更新清单静态检查通过。
- 本机原生构建最初被 C_INCLUDE_PATH=C:\mingw\include 污染；仅对构建进程移除相关变量后重跑，未修改音频源码。
- 最终本地 lint 为 0 error（468 条 warning）；R8 release 构建通过。三个本地未签名 APK 的 applicationId、版本、名称、Provider authorities、原生库完整性及全部 ARM64 ELF 16 KB 对齐检查通过。
- 签名/三个 ABI APK 的最终验证由独立发布流水线执行，未通过不会创建 Release。
- adb 未发现设备；本轮未执行 connectedAndroidTest。旧 VALIDATION.md 只作历史证据。
