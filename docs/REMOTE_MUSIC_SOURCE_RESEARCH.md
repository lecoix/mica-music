# 远程音乐源调研记录

> 状态：调研中  
> 最近更新：2026-08-30
> 当前范围：Navidrome / WebDAV / SMB  
> 本文记录候选开源项目、可复用范围、风险和 Mica 的预期边界；不是已批准的实施方案。USB 输出与 DSD 的交叉约束同步参考 [`USB_EXCLUSIVE_AUDIO_STATUS.md`](USB_EXCLUSIVE_AUDIO_STATUS.md)。

## 2026-08-29 实施状态

- Navidrome / OpenSubsonic MVP 已实现并接入统一远程曲库、JIT 播放解析和凭据边界。
- WebDAV MVP 已实现；真机已覆盖配置、鉴权、PROPFIND、递归同步、播放与非零 Range seek。
- SMB2/SMB3 MVP 已实现到协议 adapter、递归同步、稳定媒体身份、JIT 凭据解析与 Media3 offset-read DataSource；SMB1 明确不启用。
- SMB 单元/路由回归与 QA 构建已通过；真实 Android + SMB2/SMB3 端到端验收已于 2026-08-30 完成，详见下节。
## 2026-08-30 SMB 真机验收

- 真实 Android + Windows SMB2/SMB3 链路已打通：递归同步并原子发布 264 首远程曲目，远程曲库可浏览。
- 真机 SMB 播放已通过；修复了“先 `setQueue` 再 `playSong`”导致旧队列 binder 回调抢回 current item 的竞态，现改为原子“替换队列并播放指定曲目”。
- 真机非零 seek 已验证：M4A 从约 25.8 s 跳到 98.541 s 后，SMBJ 从字节偏移 7,381,641 开始实际读取，随后继续正常播放。
- FLAC 中段 seek/冷启动恢复曾因 extractor 的细碎读取被 1:1 放大为 SMB 网络往返而长时间 buffering。参考 `tsm-player` 的有界预取思路，Mica 保留 SMBJ 精确 `File.read(fileOffset, ...)`，并在每个 `SmbDataSource` 内增加单个 1 MiB 有界 read-ahead window；无后台预取线程，异常仍保持为 I/O 失败而非伪装 EOF。真机 AIZO FLAC 在 63.505 s 冷恢复后的首批非零 SMB 读取从 14,821,727 字节开始，单次请求 1,048,576 字节，2 s 内进入正常播放。
- SMB1 仍明确禁用。2026-08-30 已补齐真机负例：debug-only QA 控制面使用当前 Android Keystore 凭据进行错误密码连接，287 ms 内得到 `AUTH`；连接当前源相邻的不可达端口，26 ms 内得到 `CONNECT`；随后原凭据立即恢复并成功列目录。完整播放链另通过撤销 `adb reverse tcp:1445` 模拟服务器不可达，Mica 进入 Media3 `Source error`，根因为 SMB `ECONNREFUSED`；恢复 1445→445 转发并冷启动后错误清除，原远程曲目从保存位置恢复并继续播放。

### 2026-08-30 文件型远端元数据增量同步

- WebDAV / SMB 不再只用文件名生成浏览元数据。两者现在共用协议无关的只读 `SeekableByteSource`，通过 Android proxy fd 复用现有 TagLib；同步阶段关闭图片读取，只提取标题、艺术家、专辑、专辑艺术家、时长、年份、轨号和碟号，避免把封面 payload 带进全库扫描。
- Room schema 升至 23：`remote_tracks.contentRevision` 保存协议提供的内容修订提示；SMB 使用 file-id + last-write-time，WebDAV 使用 ETag + mtime。`remote_sources.catalogConfigRevision` 记录当前已发布快照属于哪个 source config revision；来源配置一旦变化，旧快照即使仍可显示，也禁止拿来复用元数据，直到新配置成功原子发布新快照。
- SMB 元数据 probe 保持目录遍历单写者和稳定排序，只把文件随机读限制为最多 4 路并发；每个 probe 使用单个 1 MiB read-ahead window。单曲标签损坏或读失败只降级该曲为文件名元数据，不能把整次同步伪装成失败或 EOF；generation 在 probe 期间变化时仍 fail closed，旧 operation 不能覆盖已发布 catalog。
- 2026-08-30 真机对当前 264 首 SMB 曲库强制关闭复用后完整重读：`probed=264 / reused=0`，得到 artist 262、album 260、duration 262，154774 ms 完成；紧接着普通同步为 `probed=0 / reused=264`，3951 ms 完成，标签覆盖保持一致。此前首次 schema-23 冷同步也已得到 264 首全量 probe，证明迁移后旧 catalog 会按设计 fail closed 而不是错误复用。
- WebDAV 同样具备基于 ETag/mtime 的元数据复用和严格 HTTP Range 随机读；定向测试覆盖服务器忽略 Range、修订变化重新 probe、修订未变零 GET 复用。2026-08-30 已补独立 Android 真机验收：旧 smoke WebDAV 在 `/dav/Album/` 同时发布无标签 WAV 与真实 `Last Call.m4a`，PROPFIND 返回稳定 ETag/last-modified，严格 `bytes=start-end` Range 返回精确 206。首次带 revision 的同步为 `tracks=2 / probed=2 / reused=0 / artists=1 / albums=1 / durations=2 / artworks=1 / embeddedArtworks=1`，1,358 ms；紧接着热同步为 `probed=0 / reused=2`，153 ms。WebDAV 的 TagLib metadata 与 revision reuse 已有独立真机证据，不再依赖 SMB 结果代替。
- `RemoteDatabaseMigrationTest`、共享 seekable byte source、SMB/WebDAV 元数据定向测试以及完整 `:app:testDebugUnitTest` 均通过；`git diff --check` 通过。

### 2026-08-30 文件型远端 sidecar 封面

- SMB / WebDAV 同步阶段只利用目录枚举已经返回的图片文件名、大小和内容修订提示建立 sidecar 引用，不读取图片 payload；图片字节仍由现有 `RemoteArtworkContentProvider` 按需加载并进入 16 MiB 有界进程缓存。支持 jpg/jpeg/png/webp，同名图片（如 `Song.jpg` 对 `Song.flac`）具有最高优先级。
- `Folder.jpg` / `cover.*` / `front.*` 不能无条件作为整目录封面。当前 264 首真实 SMB 曲库包含 28 个有歌曲目录和 422 张图片；根目录 103 首歌实际属于 85 个不同专辑，`gundam` 目录 36 首也属于 31 个专辑。若简单套 `Folder.jpg` 会产生大量错误封面。因此目录通用封面只在完整目录只有 1 首歌，或所有已识别歌曲具有同一个非空 album 时发布。主机按目录/标签静态盘点曾估算安全覆盖 45/264；sidecar-only QA 真机同步实际发布 41/264，以 Android 实际 catalog 为准。其余曲目不能猜测 Windows Media Player 的 `AlbumArt_{GUID}` 映射。
- sidecar 的 opaque artwork id 同时携带相对资源路径与图片 content revision；SMB 使用 file-id + last-write-time，WebDAV 使用 ETag/mtime（缺失时退化到 size）。音频 metadata 命中 revision reuse 时，仍以本轮目录枚举重新计算 artwork id，因此只替换 `Folder.jpg` 不会被旧音频标签缓存遮蔽。
- `RemoteArtworkContentProvider` 已扩展为 Navidrome / WebDAV / SMB 共用 JIT 路由。文件型 resolver 只接受规范化且仍位于配置根下的相对路径；provider 在真正读取前还要求 exact artwork id 被当前 config revision 已发布的 catalog 引用，来源编辑后旧 catalog 的 URI 不能被拿去新地址解析。缓存 key 新增 `catalogRevision`，成功发布新 catalog 后即切换缓存代际，避免同 artwork id 换图后继续命中旧字节。
- SMB artwork loader 使用独立短生命周期 SMBJ session/file handle，32 MiB 上限且严格要求完整读取；WebDAV artwork loader 禁用重定向、复用同源 Basic/Digest challenge auth，并同样限制 32 MiB。定向测试覆盖 sidecar 不在扫描期下载、混合专辑目录拒绝通用封面、音频 metadata reuse 时图片 revision 更新、SMB/WebDAV JIT loader、catalog artwork 授权边界和缓存代际。
- debug QA 增加 `artworks=` 同步覆盖计数及独立 `SMB_QA_READ_ARTWORK` JIT 读取动作。2026-08-30 测试机重新上线后，sidecar-only QA 普通同步得到 `tracks=264 / probed=0 / reused=264 / artworks=41`，6056 ms 完成；随后通过真实 `ContentResolver → RemoteArtworkContentProvider → SMBJ` 打开当前 catalog 中一张 sidecar，读取 57,115 bytes，289 ms 完成。sidecar 的 Android 物理链路已闭合。

### 2026-08-30 文件型远端内嵌封面 JIT

- Mica 的 TagLib fork 已把 `FileRef::complexPropertyKeys()` 暴露到合并 probe 结果：同步阶段只记录是否存在 `PICTURE` complex property，不调用 `complexProperties("PICTURE")`，也不把图片字节 materialize 到 Java/Room。需要强调，这一约束是“同步期不提取图片 value”；底层 TagLib 为解析音频标签仍可能读取所在 metadata block，不能把它表述成对所有格式都保证网络层完全跳过图片所在字节。
- Room schema 升至 24，`remote_tracks.metadataProbeRevision` 默认 0；当前文件 metadata profile revision 为 2。schema-23 旧 catalog 或 revision-1 catalog 因 profile revision 不匹配只会进行一次重新 probe，之后即使确认“无内嵌封面”也可以按 audio content revision 复用，避免每次同步重复跑 TagLib。音频内容 revision 改变仍会强制重新 probe。revision 2 专门用于修复 revision 1 期间可能被旧 read-ahead short-read 污染的缓存结果。
- 封面优先级明确为：**同名 sidecar（如 `Song.jpg`）→ 音频内嵌封面 → 经安全判定的 `Folder/cover/front` 通用封面**。这样混合专辑目录不会误套通用图片，而明确与歌曲同名的 sidecar 仍可覆盖内嵌图。
- `embedded-v1` opaque artwork id 只携带音频相对 resource id、audio content revision 和文件大小，不含凭据或可直接播放 URL。provider 仍先验证 exact artwork id 被当前 config revision 的已发布 catalog 引用，再即时解析凭据。SMB 使用独立短生命周期 SMBJ session + random-access file；WebDAV 使用同源认证、禁重定向和严格 HTTP Range；两者通过 Android proxy fd 在真正打开 artwork URI 时才调用 TagLib picture value extraction。
- revision-1 真机验证暴露了一个共用 `ReadAheadSeekableByteSource` bug：当 TagLib 的一次 proxy-fd read 从已缓存的 1 MiB 窗口尾部跨到下一窗口时，旧实现只返回当前窗口剩余字节，制造人为 short read。`Last Call.m4a` 的 A/B 诊断证明同一设备/同一 TagLib 在 remote proxy fd 下丢 `TITLE/ALBUM`，把同一 12,480,719-byte 文件完整拷到 QA 私有 cache 后普通本地 fd 能完整读出；修复 read-ahead 为跨窗口填满请求后，`Sincerely.flac`、`TIT FOR TAT.flac`、`Last Call.m4a` 三个定向 SMB 真机 probe 均恢复完整 title/artist/album/duration。SMB random-access 层也同步改为在已知 EOF 前处理合法 short read，并对 0-progress fail closed。
- probe 抛异常时不再写 `metadataProbeRevision`；失败记录保持旧 revision，下一次同步会自动重试，避免一次瞬时 SMB/WebDAV 读取失败永久缓存 filename fallback。定向测试同时覆盖失败→重试恢复，以及旧 metadata profile revision 在 content revision 未变化时仍必须一次性重新 probe。
- 2026-08-30 最终 SMB 真机 revision-1→revision-2 普通同步：`tracks=264 / probed=264 / reused=0 / artists=264 / albums=262 / durations=264 / artworks=264 / embeddedArtworks=264`，95,387 ms 完成。原文件独立核对确认仅两首曲目的 album 标签本来为空，因此 262/264 是完整结果；此前 revision-1 的 259 album 与 262 embedded-art 覆盖均由 read-ahead short-read 导致。
- 紧接着热同步得到 `probed=0 / reused=264 / artists=264 / albums=262 / durations=264 / artworks=264 / embeddedArtworks=264`，4,128 ms 完成；随后 `SMB_QA_READ_EMBEDDED_ARTWORK` 经真实 `ContentResolver → provider → SMBJ → proxy fd → TagLib` 再次读取 458,398 bytes，600 ms 完成。证明 rev2 metadata 与 embedded artwork id 均可稳定复用，read-ahead 修复没有破坏 JIT 封面链。
- WebDAV 也已用同一 `Last Call.m4a` fixture 完成独立 Android provider 验收：`WEBDAV_QA_READ_EMBEDDED_ARTWORK` 在首次 catalog 上读取 1,971,475 bytes / 1,232 ms；补入稳定 ETag/mtime、重新发布 catalog 并完成 `0 probe / 2 reuse` 后再次读取同样 1,971,475 bytes / 1,046 ms。链路为真实 `ContentResolver → RemoteArtworkContentProvider → WebDAV authenticated Range → proxy fd → TagLib`，证明 current-catalog artwork 授权、revision 更新和 embedded JIT 在 WebDAV 上均成立。
- 定向 JVM/Robolectric 回归覆盖 schema 23→24、embedded opaque id、SMB/WebDAV presence→catalog 映射、metadata profile revision、probe failure retry、跨 read-ahead 窗口完整读取、SMBJ short-read、同名 sidecar 优先及两协议 resolver 根目录/凭据边界；TagLib JNI/C++ 则由 arm64-v8a 与 armeabi-v7a debug CMake 实际构建覆盖。

### 2026-08-30 文件型远端歌词 JIT

- WebDAV / SMB 已接入现有 `RemoteLyricsRepository` 的按需 hydration，曲库同步本身不下载歌词 payload。文件型远端的当前优先级与本地一致为 **同名 TTML → 同名 LRC → 音频内嵌文本歌词**；一旦高优先级 sidecar 成功解析即短路，不继续打开低优先级 sidecar 或音频文件。
- sidecar 继续复用本地 `ExternalLyricsReader` 的编码检测、LRC/TTML parser、sanitizer 和 10 MiB 上限。SMB 在歌词请求发生时才创建短生命周期 SMBJ session，列出音频所在目录并按规范化相对路径读取同名 sidecar；WebDAV 用当前 source 的即时 Basic/Digest 凭据请求同目录 sibling URL，禁用重定向，404 仅表示该候选不存在，认证/HTTP/读取错误仍按失败处理而不是伪装成“无歌词”。
- 没有 sidecar 时，两协议先复用 `SeekableByteSource → ReadAheadSeekableByteSource → Android proxy fd → TagLib` 的随机读链提取文本歌词候选；文本候选为空时，再进入与本地扫描共用的 **bounded binary fallback**。`AudioProbeBytes` 新增协议无关的 random-access seam：MP3/AAC 只取声明的 ID3 标签且最多 4 MiB，FLAC 最多取 4 MiB metadata，APE 只取尾部 2 MiB，MP4/M4A 只扫描 box header 并最多读取 16 MiB `moov`（失败时退化为 2 MiB head + 4 MiB tail），其他格式仅做小型 ID3/head+tail 探测；不会为了内嵌歌词下载整首远端音频。取得的有限二进制窗口仍交给现有 `EmbeddedLyricsReader`，因此 ID3 USLT/ULT/SYLT/TXXX/LYR、FLAC/APE/MP4 等本地 binary parser 路径可以直接复用。远端没有复制本地 `MediaMetadataRetriever` fallback；这一点仍是两条链的差异，但不影响已由 TagLib 文本或 binary parser 覆盖的格式。
- 远端歌词共享缓存的数据版本从 v1 升至 v2，因此此前 WebDAV/SMB 被缓存成空文档的结果不会继续命中。网络、认证或 transport 异常仍不会被缓存成永久“无歌词”，catalog/config revision 变化也会切换缓存代际。
- WebDAV catalog 额外硬排除 `.lrc/.ttml` 扩展，不信任服务端 MIME 来判断它们是否是歌曲。真机 smoke 故意把 `Last Call.lrc` 报成 `audio/wav`，同步仍从旧的 3 条错误 catalog 收敛回 2 首真实音频；对应 server 日志只有 PROPFIND 和音频 metadata 的 Range GET，**同步阶段没有任何 `.lrc` GET**。
- WebDAV sidecar 真机：在 `/dav/Album/Last Call.lrc` 存在时，通过真实 `RemoteLyricsRepository → WebDavLyricsLoader` 得到 `format=LRC / origin=EXTERNAL / lines=2`，353 ms；server 先探测不存在的 TTML，随后只读取 LRC，没有打开 `Last Call.m4a`。把该 fixture 移出 Album（仅移动、未删除）并重新发布 catalog 后，同一 action 得到 `format=LRC / origin=EMBEDDED / lines=2`，1,493 ms；server 日志显示 TTML/LRC 候选均 404 后才对 `Last Call.m4a` 发多个严格 206 Range GET。fixture 验证后已移动回原位。
- binary/SYLT 真机另使用 `.scratch/webdav-smoke` 生成的 4,161-byte `MicaRemoteSylt.mp3`（65-byte ID3v2.3/SYLT 标签 + dummy payload，不进入真实音乐目录）。WebDAV catalog 发布该 fixture 后，指定歌曲的歌词 hydration 得到 `format=SYLT / origin=EMBEDDED / lines=2 / tokens=4`，374 ms；server 记录 sidecar 候选全部 404 后仅对该 MP3 产生一个 206 Range GET，证明真实链路为 `WebDAV Range → ReadAheadSeekableByteSource → bounded ID3 fast probe → EmbeddedLyricsReader SYLT parser`，而不是整文件下载。验收后 fixture 已移动到 `.scratch/webdav-smoke/disabled/`，再次同步把 QA catalog 恢复为原来的 2 首。
- SMB sidecar 真机直接使用现有 264 首共享曲库中的 `LudoWic - Hit The Floor (Live).flac/.lrc`，无需新增测试文件；`SMB_QA_LOAD_LYRICS` 得到 `format=LRC / origin=EXTERNAL / lines=37`，1,156 ms。定向测试另外覆盖 SMB 无 sidecar 后通过 random-access 音频句柄进入 embedded loader，以及 TTML 命中后 LRC/音频都不再打开。
- 定向回归覆盖 WebDAV/SMB sidecar 优先级、WebDAV 404→embedded 严格 Range、SMB random-access fallback、WebDAV 错误 MIME 下歌词文件不进入 catalog，以及 `RemoteLyricsRepository` 的 revision/cache/error 语义。真机验收期间测试机 MIUI 曾把 QA UID 标为 power-restriction `REJECT_ALL`；仅为验收临时加入 `com.mica.music.qa` device-idle whitelist，全部网络验证结束后已明确撤销，未修改生产包或设备音乐文件。

## 已确定的产品范围

- 设置中合并为一个“远程曲库”入口。
- 进入后选择添加 `Navidrome`、`WebDAV` 或 `SMB`。
- 同一种协议允许添加多个来源实例。
- 上层可以聚合浏览，连接、认证、同步状态和故障必须按来源实例隔离。
- Navidrome 按 OpenSubsonic/Subsonic 协议接入，不依赖 Navidrome 私有 API。
- 默认请求并播放原始音频。任何可能降低音质的服务端转码选项，必须说明影响并取得用户明确允许后才能实现或默认启用。

## 复用等级

| 等级 | 含义 |
|---|---|
| A：可直接移植 | 保留许可证和版权声明后，仅需改包名、依赖或少量类型 |
| B：可改造移植 | 协议逻辑可保留，但必须接入 Mica 自己的身份、认证、存储和播放边界 |
| C：只借鉴设计 | 思路有价值，直接复制会引入错误抽象或大量耦合 |
| D：不要采用 | 存在安全、音质、容量或一致性问题 |

## 候选项目总表

| 项目 | 许可证 | 覆盖协议 | 结论 | 对全部三协议的预估节省 |
|---|---|---|---|---:|
| [CraftWorksMC/Chora](https://github.com/CraftWorksMC/Chora) | Apache-2.0 | Navidrome/Subsonic | 端点清单和兼容行为参考；生产代码不宜整体移植 | 很低 |
| [xianyvbang/XyMusic](https://github.com/xianyvbang/XyMusic) | Apache-2.0 | Subsonic/Navidrome，另有 Jellyfin/Emby/Plex | OpenSubsonic 协议层最有价值；数据源和缓存层过重 | 约 10–20% |
| [LYCaikano/Android-WebDav-Music-Player](https://github.com/LYCaikano/Android-WebDav-Music-Player) | **未声明** | WebDAV | 技术参考价值高，但当前不能复制代码 | 约 5–10% 概念节省 |
| [SuperMite233/Simple-Music-Player](https://github.com/SuperMite233/Simple-Music-Player) | MIT | WebDAV；Subsonic 仅有配置 UI | WebDAV Basic/Digest 和 Range 请求可参考，整体架构不宜移植 | 约 5–10% |
| [gbandszxc/tsm-player](https://github.com/gbandszxc/tsm-player) | **未声明** | SMB | SMB 播放链参考价值最高，但当前不能复制代码 | 约 5–10% 概念节省 |
| [nova-video-player/aos-FileCoreLibrary](https://github.com/nova-video-player/aos-FileCoreLibrary) | Apache-2.0 | WebDAV / SMB | 当前最有价值、许可证明确的 Android 传输参考；只应裁剪协议部分，不整体引入 | 约 10–20% |
| [thegrizzlylabs/sardine-android](https://github.com/thegrizzlylabs/sardine-android) | Apache-2.0 | WebDAV | 可作为 PROPFIND、认证和 XML 解析的依赖候选；播放 Range 仍建议由 Media3/OkHttp 负责 | 约 5–10% |
| [hierynomus/smbj](https://github.com/hierynomus/smbj) | Apache-2.0 | SMB2 / SMB3 | 当前首选 SMB 协议库候选；Mica 仍需自行实现 Media3 adapter 和连接生命周期 | 约 10–15% |
| [mzhsy1/MzDKPlayer](https://github.com/mzhsy1/MzDKPlayer) | GPL-3.0 | WebDAV / SMB | 证明 `sardine-android + smbj + Media3` 路线可落地；只能作行为参考 | 很低 |
| [zhanghai/MaterialFiles](https://github.com/zhanghai/MaterialFiles) | GPL-3.0 | WebDAV / SMB | 成熟文件管理器的兼容行为参考；不适合向非 GPL 应用摘抄代码 | 很低 |
| [namidaco/namida](https://github.com/namidaco/namida) | 自定义 EULA / source-available | WebDAV / SMB / Subsonic | 覆盖面广但不是可自由复用的开源代码，只作产品行为参考 | 很低 |

> 上述比例是静态代码审查估算，尚未连接真实服务器，也未在 Mica 中编译或运行。

## XyMusic

### 值得移植

XyMusic 的 `api/.../subsonic` 目录约有 55 个 Kotlin 文件、2592 行，协议覆盖比较完整。

| 部分 | 复用等级 | 预估可保留 | 说明 |
|---|---|---:|---|
| OpenSubsonic/Subsonic DTO | A/B | 70–85% | 需要适配包名、序列化依赖、空值及版本兼容策略 |
| 类型化端点声明 | B | 60–75% | 应裁剪成 Mica MVP 所需端点 |
| token/salt 认证参数注入 | B | 40–60% | 可保留算法，认证材料必须由 Mica 的凭据边界提供 |
| 响应状态和错误映射 | B | 40–60% | 需要改成 Mica 的错误类型和重新登录状态机 |
| 多连接的 `connectionId` 设计 | C | 概念 70–80% | 很适合来源实例隔离，不应复制其完整 Room 模型 |
| Paging/RemoteMediator 缓存 | C | 直接代码 10–25% | 与 Mica snapshot 发布协议不兼容，不能直接接管曲库 |
| 播放、下载及 UI | C/D | 接近 0% | 与 Mica Media3、队列和界面结构耦合 |

优先研究的文件：

- [SubsonicApiClient.kt](https://github.com/xianyvbang/XyMusic/blob/main/api/src/commonMain/kotlin/cn/xybbz/api/client/subsonic/SubsonicApiClient.kt)
- [SubsonicItemApi.kt](https://github.com/xianyvbang/XyMusic/blob/main/api/src/commonMain/kotlin/cn/xybbz/api/client/subsonic/service/SubsonicItemApi.kt)
- [DefaultApiClient.kt](https://github.com/xianyvbang/XyMusic/blob/main/api/src/commonMain/kotlin/cn/xybbz/api/client/DefaultApiClient.kt)
- [HttpLogSanitizer.kt](https://github.com/xianyvbang/XyMusic/blob/main/api/src/commonMain/kotlin/cn/xybbz/api/client/HttpLogSanitizer.kt)
- [ConnectionConfig.kt](https://github.com/xianyvbang/XyMusic/blob/main/localdata/src/commonMain/kotlin/cn/xybbz/localdata/data/connection/ConnectionConfig.kt)

### 建议裁出的 Navidrome MVP

第一阶段只移植：

- `ping`
- `getMusicFolders`
- `getArtists`
- `getAlbumList2`
- `getAlbum`
- `getSong`
- `search3`
- `getCoverArt`
- `stream`
- OpenSubsonic 歌词接口

播放列表写操作、收藏、评分、scrobble、相似歌曲和服务端扫描控制可以后置。

### 不应整体复制

[IDataSourceServer.kt](https://github.com/xianyvbang/XyMusic/blob/main/composeApp/src/commonMain/kotlin/cn/xybbz/api/client/IDataSourceServer.kt) 同时承载认证、数据库、Paging、下载、收藏、歌词、播放地址和上报等职责。整体引入会把 XyMusic 的应用架构带进 Mica。

还需安全加固：当前日志脱敏实现需要核对是否完整覆盖 Subsonic 的 `u`、`t`、`s`、`p` 等查询参数，不能假定已有脱敏足够。

## Chora

### 可用价值

- 可作为 Subsonic 端点覆盖清单。
- 可对照 Navidrome 的响应字段和部分兼容行为。
- 可参考多 Navidrome 曲库的产品行为，但不采用其数据结构。

相关文件：

- [NavidromeDataSource.kt](https://github.com/CraftWorksMC/Chora/blob/master/app/src/main/java/com/craftworks/music/data/datasource/navidrome/NavidromeDataSource.kt)
- [GetNavidromeSongs.kt](https://github.com/CraftWorksMC/Chora/blob/master/app/src/main/java/com/craftworks/music/providers/navidrome/GetNavidromeSongs.kt)

### 明确不要采用

- 信任所有 TLS 证书并关闭 hostname 校验。
- 记录包含认证查询参数的完整请求 URL。
- 将带 `u/t/s` 的播放 URL 持久化到歌曲，或用作 `MediaItem.mediaId`。
- 使用全局 `mutableStateListOf` 常驻整个远程曲库。
- 按网络类型自动降低转码码率。

Chora 的 README 也明确说明其 Kotlin 代码组织不佳，不建议作为学习资源。因此它的生产代码直接复用比例估计低于 10%。

## Android-WebDav-Music-Player

### 有价值的部分

- 使用 OkHttp 发出 `PROPFIND Depth: 1`，通过流式 XML Pull Parser 处理 `207 Multi-Status`。
- 对返回的 `href` 做同源限制，避免向恶意跨域地址转发 Authorization。
- 使用 HTTP Range 获取文件头并嗅探元数据。
- Media3 `OkHttpDataSource` 可以在请求时动态添加认证头。
- 多账号以 `accountId` 隔离歌曲。
- 密码使用 Android Keystore + AES-GCM 加密后存入 Room。
- 网络目录部分失败时跳过删除阶段，避免一次网络抖动误删本地索引。

如果未来仓库补充兼容许可证，`WebDavXmlParser` 可以作为 B 级候选；协议请求和错误分类可以作为 C 级参考。

### 不能直接采用的原因

- 截至 2026-08-10，仓库根目录没有 LICENSE/NOTICE，也没有在源码中找到明确授权。公开可读不等于允许复制、修改或分发。
- 同步过程没有 generation/requestId，也没有统一写入 mutex；并发刷新、账号删除和旧同步回写之间存在竞态。
- 扫描先把全部任务及已有歌曲载入内存，并按目录建立大量 `async`。虽然网络并发限制为 5，但一个超大扁平目录仍可能一次创建大量 Deferred，不符合 Mica 的一万首容量基线。
- DAO 对全曲库暴露 `Flow<List<Song>>`，不能直接作为 Mica 的聚合曲库实现。
- “跳过证书验证”仍通过 trust-all 与关闭 hostname 校验实现；即使是用户选项，也不能直接复制为安全方案。
- 播放认证注册表按 URL 前缀和 host 保存。相同地址下的多个账号可能相互覆盖，host 级 `skipSsl` 也可能影响同主机其他来源。
- 没有发现单元测试目录，也没有 generation 交错测试。

## Simple-Music-Player

### 实际覆盖与 README 差异

README 声称支持 Subsonic/Navidrome 的登录、浏览和播放，但当前源码中的界面文字明确写着“服务器列表管理已完成，API 对接由后续开发接手”。没有找到 Subsonic 客户端或端点实现。因此它不能替代 XyMusic 作为 Navidrome 参考。

### 可参考部分

- 单个约 400 行的 `WebDavClient` 覆盖 `PROPFIND Depth: 1`、Basic/Digest challenge、Range 读取、路径编码和目录解析。
- MIT License 允许在保留版权及许可文本的前提下复制和修改。
- 支持多 WebDAV 服务器配置，产品流程可以作为轻量参考。

### 不应直接采用

- 通过正则表达式解析 WebDAV XML，兼容性和可维护性不如 namespace-aware 的流式 XML parser。
- 忽略证书时使用 trust-all 并接受任意 hostname。
- WebDAV 和 Subsonic 密码以明文写入 JSON 配置；配置导入导出还会扩大泄露范围。
- 存在把完整下载读成 `ByteArray` 的路径，不适用于大无损音频和一万首曲库。
- 客户端以同步阻塞调用为主，没有 Mica 要求的 generation、串行写入 seam 或确定性交错测试。
- 大量功能集中在约 6600 行的 `MainActivity.kt`，不应迁入 Mica。

结论：许可证允许移植，但代码质量决定它主要是 WebDAV Digest 认证和兼容行为参考；预估可保留代码低于 20–30%。

## tsm-player

### 有价值的部分

- 基于 `jcifs-ng`，默认 SMB 2.02–3.11，并提供显式 SMB1 兼容开关。
- `JcifsSmbRepository` 已拆出目录浏览和错误映射。
- 自定义 Media3 `BaseDataSource` 使用 `SmbRandomAccessFile`，能够按 `DataSpec.position` seek。
- 播放读取采用有界预取：1 MiB 块、最多 4 块，具有背压，不会无限增长。
- 包含 SMB 外挂歌词、远程封面、WAV 元数据探测、历史和收藏等真实使用场景。
- 测试数量明显多于另外两个项目，适合作为 SMB 行为与异常清单。

### 当前阻断与风险

- 截至 2026-08-10，仓库根目录没有 LICENSE/NOTICE，也没有找到明确源码授权。当前只能阅读和验证行为，不能复制其实现。
- 密码以明文写入 DataStore/SQLite，并被复制到收藏、历史和最后播放快照；部分流程还通过 Intent extra 传递密码。Mica 必须改成稳定 `sourceInstanceId` + 凭据引用。
- `SmbDataSource` 将预取异常和 10 秒队列超时转换成 EOF，可能把网络故障表现为歌曲正常提前结束；该行为不能照搬。
- 没有发现针对真实 SMB 服务器的 DataSource seek/read 集成测试；现有相关单测主要覆盖路径和错误分类。
- SMB1 必须默认关闭，并带安全风险提示；不能因为兼容旧 NAS 自动开启。
- `jcifs-ng` 本身是 LGPL-2.1，正式采用前需要单独确认 Android 分发和开源声明义务。

如果作者补充兼容许可证，优先评估 `SmbDataSource`、`JcifsSmbRepository`、`SmbContextFactory` 和相关测试；在此之前，应直接基于 `jcifs-ng` 的公开 API 自行实现 Mica adapter。

## NOVA aos-FileCoreLibrary

这是本轮搜索中最能改变结论的项目：它是 NOVA Video Player 长期使用的 Android 文件传输模块，根许可证和源码头均为 Apache-2.0，同时包含 WebDAV、`smbj` 和 `jcifs-ng` 实现。静态检查的 `v6.4` 分支包含约 734 行 WebDAV、998 行 `smbj` 和 1000 行 `jcifs-ng` 相关 Java 源码。

### 可以借用或改造的部分

- WebDAV 以 Sardine/OkHttp 负责认证、列目录和 XML 解析，播放读取通过带 `Range` 的 GET 获取流。
- `smbj` 实现用只读、共享读权限打开远端文件，并包含连接、Session、Share 缓存以及可重试故障分类。
- WebDAV 与 SMB 两套实现都把“列目录”和“打开字节流”分开，适合作为 Mica transport/repository 边界的参考。
- 许可证允许在保留声明后选择性移植；它补上了此前 WebDAV/SMB 优质样本没有明确许可证的缺口。

### 不能照搬的部分

- 模块带有旧式 Java、UI、网络发现、全局静态缓存和不属于音乐播放器的文件管理能力，整体导入会引入大量耦合。
- WebDAV `Range` 读取没有充分验证服务端是否返回 `206 Partial Content` 及正确的 `Content-Range`；Mica 播放 DataSource 必须显式校验。
- SMB 定位通过 `InputStream.skip(from)` 完成。未做真实服务器测量前，不能保证它是协议级随机读而非低效读弃；Mica 应使用可验证的 offset read/seek。
- 凭据数据库用源码内硬编码 key 的 Blowfish 加密，不能移植；Mica 必须使用 Android Keystore 支持的凭据存储。
- 日志拦截器和认证头需要逐项脱敏，不能默认认为安全。
- 未发现覆盖上述网络路径的测试，尤其没有 Mica 所需的 generation/写入串行化交错测试。

结论：NOVA FileCore 是 A/B 级的协议实现素材和兼容性清单，不是可整包接入的组件。

## 底层库候选

### WebDAV：sardine-android

- Apache-2.0，面向 Android，基于 OkHttp，已经被 NOVA 和 MzDKPlayer 实际采用。
- 适合承担 `PROPFIND`、认证、目录资源模型和 namespace-aware XML 解析，避免自行维护脆弱的 WebDAV XML parser。
- 不建议让它独占播放链。Media3 播放继续使用 Mica 控制的 OkHttp DataSource，更容易正确处理 `DataSpec.position`、206/200、`Content-Range`、关闭响应和错误映射。
- 上游活跃度、Android/OkHttp 版本兼容性和特殊服务端兼容仍需原型验证；不能仅凭仓库存在就确定最终依赖版本。

### SMB：smbj

- Apache-2.0 的 Java SMB2/SMB3 客户端，许可证比 `jcifs-ng` 的 LGPL-2.1 更容易与当前应用分发方式兼容。
- NOVA 和 MzDKPlayer 都采用它，说明 Android 接入路线具备现实样本。
- 建议第一版只支持 SMB2/SMB3；不把 SMB1 兼容性带入默认方案。
- Mica 仍须自行实现连接 owner、超时/重连、Media3 `BaseDataSource`、offset read、资源关闭和错误映射；不能把异常伪装成 EOF。

### 只作行为参考的补充项目

- MzDKPlayer：GPL-3.0，同时采用 `smbj`、`sardine-android` 和 Media3；其 SMB seek 也使用 `skip()`，WebDAV 还出现忽略 TLS 证书的路径，不能直接照搬。
- Material Files：GPL-3.0，WebDAV/SMB 文件系统兼容行为成熟，但 GPL 代码不能选择性摘入非 GPL 应用。
- Namida：功能覆盖三条远程路线，但采用自定义 source-available EULA，不作为代码来源。

## 当前样本是否足够

现在无论是“决定架构和依赖方向”，还是“找到许可证明确的底层实现素材”，样本都已经足够，可以停止无目标地继续搜索：

| 协议 | 主要代码参考 | 权威事实来源 | 当前结论 |
|---|---|---|---|
| Navidrome | XyMusic | OpenSubsonic API | 可选择性移植协议 DTO/端点 |
| WebDAV | `sardine-android` + NOVA FileCore；其他播放器作兼容参考 | RFC 4918、HTTP Range/认证规范 | 目录与认证可复用已授权库；播放 DataSource 自行实现 |
| SMB | `smbj` + NOVA FileCore；tsm-player 作行为参考 | `smbj` API 与 SMB2/3 协议行为 | 采用 Apache-2.0 库，自行实现 Media3 adapter |

许可证不再是 WebDAV/SMB 的主要阻断：NOVA FileCore、`sardine-android` 和 `smbj` 都有 Apache-2.0 授权。不过，“可合法复用”不等于“可整段照搬”。推荐边界是：

1. Navidrome：从 XyMusic 选择性移植 OpenSubsonic DTO 和 MVP 端点声明。
2. WebDAV：以 `sardine-android` 承担列目录/认证；以 Mica 自有 Media3 + OkHttp DataSource 承担 Range 播放。
3. SMB：以 `smbj` 承担 SMB2/3 协议；以 Mica 自有 repository、连接 owner 和 Media3 DataSource 承担生命周期与播放。

按静态审查粗估，这些样本和依赖可减少三协议合计约 25–35% 的协议摸索与基础实现工作；同步、一致性、凭据安全、10,000 首容量、缓存和真实服务器兼容测试仍然必须由 Mica 自己完成。这个比例不是工期承诺。

## Mica 应保留的自有边界

候选模型：

```text
RemoteSourceInstance
- id
- type: NAVIDROME | WEBDAV | SMB
- displayName
- endpoint
- credentialRef
- enabled

RemoteTrackRef
- sourceInstanceId
- opaqueTrackId
```

推荐播放解析链：

```text
RemoteTrackRef
    -> RemotePlaybackResolver
    -> 临时 URL / 请求头 / 协议 DataSource
    -> Media3
```

必须满足：

- 稳定歌曲身份不能依赖临时 URL、token、密码或请求头。
- 认证信息不能进入播放队列持久化、播放历史、歌单 JSON 或日志。
- Navidrome 的认证播放 URL 应在即将播放时生成，不长期保存。
- WebDAV/SMB 和 Navidrome 可以共享上层来源、目录、歌曲与错误模型，但底层传输实现保持独立。
- 单个来源失败不能清空或污染其他来源的可用快照。

## 与 USB 独占 / DSD 的架构兼容边界

远端音乐和 USB 独占不应形成 `WebDavUsbProvider`、`SmbUsbProvider`、`NavidromeUsbProvider` 之类的协议×输出组合实现。对于 PCM，网络来源只负责把**真实媒体字节**稳定交给 Media3；USB 独占继续只接收 decoder 后的 PCM。正常路径应保持正交：

```text
Local file ──────────────┐
Navidrome original ──────┤
WebDAV Range ────────────┤ -> Media3 extractor/decoder -> actual PCM facts -> AudioOutput
SMB random-access ───────┘                                      ├─ SharedPcm
                                                               └─ UsbDirectPcm
```

因此 Remote PCM + USB Direct 的主要风险不是“每种网络协议适配每台 DAC”，而是**输入侧和输出侧的时序、格式事实与故障归因不能混淆**。

### 1. catalog metadata 不是 USB 协商事实

远端曲库返回的 codec/sample rate/bit depth 只能作为 catalog/display/expected facts，不能直接驱动 USB clock、alternate setting 或 exactness 判断。Navidrome 可能因服务器配置发生转码，WebDAV/SMB 文件也可能在索引之后被替换；最终必须以实际打开的 stream、extractor/decoder 确认后的格式为准：

```text
CatalogFormatFacts
    ↓ 仅用于浏览/预期
RemotePlaybackResolver
    ↓
actual stream bytes
    ↓
Extractor / Decoder
    ↓
ActualSourceFormatFacts
    ↓
UsbFormatNegotiator
```

至少区分并诊断：

```text
catalogFormat
streamRequested        // ORIGINAL / explicit transcoding profile
streamActual
usbRequested
usbActual
signalExact / signalModified
modificationReason     // e.g. SERVER_TRANSCODE
```

默认仍请求 original。若用户明确允许服务端转码，`signalExact` 必须反映真实链路，不能因为 DAC 最终收到 48 kHz PCM 就把原始 192/24 曲目显示成 bit-perfect USB。

### 2. upstream starvation 与 USB transport failure 必须正交

未来网络抖动时可能出现：

```text
Remote DataSource 暂时无数据
    -> Media3 BUFFERING / decoder 暂停产出
    -> USB source ring 逐渐耗尽
    -> USB worker 按当前格式输出合法 idle/silence
```

这属于 **source starvation**，不能计入 USB recovery/failure budget，也不能因为 SharedPcm fallback 无法修复网络故障而误触发输出切换。反过来，USB feedback/packet/clock/device 故障也不能导致远端连接 owner 无意义地重新登录或重建服务器会话。

故障模型至少保持两个独立维度：

```text
Input / source                    Output / USB
NETWORK_TIMEOUT                   USB_TRANSPORT_ERROR
AUTH_EXPIRED                      USB_CLOCK_ERROR
HTTP_RANGE_INVALID                USB_FEEDBACK_ERROR
SMB_SESSION_LOST                  USB_DEVICE_DETACHED
REMOTE_FILE_GONE                  USB_FORMAT_REJECTED
SOURCE_STARVATION                 USB_STALLED_PROGRESS
```

跨层 health/recovery 只有在存在明确证据时关联；例如 `sourceStarved=true` 时 USB ring short-read 本身不是 DAC degradation 证据。诊断应能直接回答“是服务器不给数据，还是 USB 真的坏了”。

### 3. 网络 buffer 与 USB realtime ring 分层

不要用扩大 USB Native ring 的方式吸收 Wi-Fi/SMB 抖动。两层目的不同：

```text
network / protocol
    ↓
Remote DataSource / Media3 buffering
    // 较大、有界；吸收 RTT、TLS、服务器和无线抖动
    ↓
decoder
    ↓
USB source ring
    // 小、实时、有界；只吸收 Kotlin/Native/URB/feedback 调度抖动
    ↓
USBFS URB
```

网络缓存策略可以按 MiB 或媒体时长设计；USB ring 继续由实时 transport latency、seek/pause/reconfigure 上界驱动。禁止为了远端播放把 USB ring 无界放大，导致 seek、pause、格式切换和 stale PCM 清理恶化。

### 4. seek latency 必须分段归因

远端 seek 会同时叠加协议 random access、网络 RTT、decoder refill 和 USB prefill，因此不能只记录一个 `seek took N ms`：

```text
seekRequested
 -> remoteOpenOrRangeLatencyMs
 -> remoteFirstByteLatencyMs
 -> decoderFirstOutputLatencyMs
 -> usbPrefillLatencyMs
 -> usbResumeLatencyMs
 -> totalSeekLatencyMs
```

WebDAV 必须校验 `206 Partial Content` / `Content-Range`；SMB 必须使用可验证的 offset/random-access read，而不是无法证明成本的线性 `skip()`。只有拆开这些时间，才能避免把 NAS 慢、服务器慢或无线抖动误诊为 USB 96 kHz seek 性能问题。

### 5. 为未来 Remote DSD 预留 `SeekableByteSource`

即使第一版远端音乐完全不支持 DSF/DFF，P5 的 raw DSD reader 也不应把 `File`、`RandomAccessFile`、本地 fd 或路径写成核心输入契约。应预留一个与协议无关、只读、可 seek 的字节源，例如：

```text
SeekableByteSource
- size / knownLength
- readAt(offset, dst) or seek + bounded read
- close
- stable source identity / generation token

├─ LocalFileByteSource
├─ HttpRangeByteSource      // future WebDAV / Navidrome original
└─ SmbRandomAccessByteSource
```

然后 DSD 路径保持：

```text
Local DSF/DFF ───────────┐
Remote DSF/DFF (future) ─┤
                         ↓
                  SeekableByteSource
                         ↓
                  DsdContainerReader
                         ↓
               canonical raw DSD stream
                         ↓
                 DoP / Native encoder
                         ↓
              generic USB payload scheduler
```

`DsdContainerReader` 只理解 DSF/DFF container 和 DSD 字节语义，不理解 HTTP、SMB、认证或 Android 文件路径。Remote DSD 是否最终成为产品功能可以以后决定，但这一 seam 现在保留几乎没有额外运行成本，却能避免未来拆掉 P5 reader。

### 6. Remote DSD starvation 的连续性边界

如果未来启用 Remote DSD，网络 starvation 不能简单停止 USB session。P5 的 session-level DoP marker / Native frame carry / DSD idle continuity 仍由输出层负责：

```text
remote source starved
    ↓
DsdContainerReader 暂无新 payload
    ↓
DSD output session 保持 ACTIVE
    ↓
合法 DSD idle（候选 framing 已证明后）
+ DoP marker phase / Native frame alignment 连续
    ↓
网络恢复后继续 source payload
```

source starvation 仍不得计入 USB device failure budget。是否允许在过长 starvation 后主动结束 DSD session属于产品/资源策略，应由明确 timeout/state machine 决定，而不是由 USB underrun 偶然触发。

### 7. 组合测试与资源基线

远端功能落地后至少分开比较：

```text
Local  + SharedPcm
Local  + UsbDirectPcm
Remote + SharedPcm
Remote + UsbDirectPcm
```

必要时再增加 DSD 分支。记录网络吞吐/重试、Media3 buffering、decoder first-output、USB health、PSS/FD/CPU/电量/温度，避免把 Wi-Fi/SMB/服务器开销全部记到 USB。组合测试重点覆盖网络 starvation/recovery 与 USB detach/recovery 同时发生时的 generation、owner 和错误归因，而不是为每个“协议 × DAC”复制完整测试套件。

### 8. 预留边界，不提前实现未确定产品功能

当前只要求未来实现不能堵死以下四个 seam：

1. `ActualSourceFormatFacts`：catalog metadata 与实际 stream/decoded format 分离；
2. `PlaybackStarvationReason` / source health：上游 starvation 与 USB transport health 分离；
3. `SeekableByteSource`：本地/HTTP Range/SMB 可为未来 raw reader 提供统一随机读；
4. diagnostics：source health、decode/buffering 与 output health 分层记录。

本阶段**不要求实现 Remote DSD、不要求因 USB 独占提前扩大远端音乐 scope**。这些接口只用于防止 P3/P5 和未来 Remote Music 各自写死相互冲突的假设。

## 异步一致性与容量约束

每个来源实例必须拥有自己的同步 owner，以及 generation/requestId、revision 和统一写入同步 seam。

- 每次刷新、删除来源、清空缓存、重新登录或修改连接配置，都要推进 generation。
- 每个网络请求、分页请求、文件枚举、解析和数据库等待之后，在写入或发布前重新校验 generation。
- Room 写入、缓存删除和内存发布必须走同一个 owner 提供的串行化边界。
- 必须有确定性交错测试：旧同步停在实际副作用前，新同步完成，再释放旧同步，最终数据仍属于新同步。
- 取消旧协程只能作为优化，不能作为正确性保证。

容量基线为一万首歌曲、每首具有完整逐字歌词、8 GB 内存 Android 手机：

- 目录与歌曲列表必须分页或分批处理。
- 不在扫描阶段全量下载、解析或常驻歌词。
- 封面按需加载并采用有界磁盘/内存缓存。
- 聚合视图避免复制三份完整歌曲对象；优先轻量索引或数据库分页。

## 当前结论

- Navidrome 部分，以 XyMusic 为主要代码参考，以 Chora 为兼容和反例参考。
- 若选择性移植 XyMusic 的 DTO 与端点声明，预计可减少 Navidrome MVP 约 25–40% 的开发工作。
- WebDAV 以 `sardine-android` + Mica 自有 OkHttp/Media3 DataSource 为首选路线；NOVA FileCore 提供 Apache-2.0 的实现参考。
- SMB 以 Apache-2.0 的 `smbj`、仅支持 SMB2/3 为首选路线；NOVA FileCore 提供连接与故障处理参考。
- Navidrome、WebDAV、SMB 三条路线现在均有许可证明确的实现素材，可以停止广泛搜索。
- 仍不建议复制任何完整模块：所有应用级样本都不满足 Mica 的 generation、统一写入串行化、确定性交错测试、安全凭据和一万首容量要求。
- 下一步应确定最小依赖与 adapter 接口，再做只包含“连接、列目录/查询、按位置读取播放”的协议原型；播放 adapter 同时验证 `ActualSourceFormatFacts`、source-starvation 与 output-health 分层、seek latency 分段，以及 `SeekableByteSource` 不绑定本地文件假设。

## 后续候选项目记录模板

复制一行到总表，并补充以下内容：

```markdown
### 项目名

- 地址：
- 许可证及 NOTICE：
- 活跃程度：
- 覆盖协议：Navidrome / WebDAV / SMB
- 网络与序列化栈：
- 认证信息保存方式：
- 曲目稳定身份：
- 分页与一万首容量表现：
- 播放时 URL/请求头生成方式：
- generation/requestId 与写入串行化：
- 可直接移植：
- 可改造移植：
- 只能借鉴：
- 明确风险：
- 真实服务器验证状态：未验证 / 部分验证 / 已验证
```

## 开源合规提醒

Chora、XyMusic、NOVA FileCore、`sardine-android` 和 `smbj` 根许可证均为 Apache-2.0，Simple-Music-Player 为 MIT。若实际复制代码或引入依赖，需要逐文件确认版权和第三方来源，保留许可证及版权声明、标记修改，并同步更新 Mica 的开源声明。

Android-WebDav-Music-Player 和 tsm-player 当前没有明确源码许可证，因此不能把其公开仓库视为可自由复制的开源代码。MzDKPlayer 和 Material Files 为 GPL-3.0，Namida 使用自定义 EULA，均只作为行为参考。`jcifs-ng` 为 LGPL-2.1，也需要单独处理依赖合规。这里只记录初步兼容性判断，不构成法律意见。
