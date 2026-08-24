# 远程音乐源调研记录

> 状态：调研中  
> 最近更新：2026-08-12  
> 当前范围：Navidrome / WebDAV / SMB  
> 本文记录候选开源项目、可复用范围、风险和 Mica 的预期边界；不是已批准的实施方案。USB 输出与 DSD 的交叉约束同步参考 [`USB_EXCLUSIVE_AUDIO_STATUS.md`](USB_EXCLUSIVE_AUDIO_STATUS.md)。

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
