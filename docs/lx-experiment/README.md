# Mica LX — 基于 0.4.4 的独立实验版

本分支基于正式版 0.4.4（`edb3d3bb`），迁移旧 LX 实验分支。支持单个 LX `.js` 导入、QQ/酷我/网易/酷狗在线搜索、明确选择音质后播放/加入队列/收藏到歌单，以及按需封面和歌词。音频地址仍由用户导入的音源解析；不把本地歌曲转换成平台 ID。

发布身份、并发审查、容量评估与本轮验证见 [MIGRATION_044.md](MIGRATION_044.md)；旧版本的设备测试记录保留在 VALIDATION.md，不代表本版本已完成真机验证。

## 试用

应用名 `Mica LX`，独立包名 `com.mica.music.lx`，版本 `0.4.4-lx.1`，versionCode 1。可与 `com.mica.music` 同时安装，数据互不覆盖；不会自动继承旧 `com.mica.music.lxexperiment` 的实验数据。

1. 设置 → 在线音乐 → 导入 LX .js；从主页侧栏进入在线音乐搜索。
2. 阅读脚本执行说明，选择可信 `.js`。导入成功后自动初始化；可停用、重新初始化、删除。最多保存一个脚本，重新导入会替换。
3. 查看脚本实际声明的 `kw / kg / tx / wy / mg` 平台和 `128k / 320k / flac / flac24bit` 品质。
4. 测试播放中填写平台、**明确填写品质**、粘贴完整 LX MusicInfo JSON，再点“替换队列并播放”。脚本需要的 songmid/hash/id 等字段必须来自调用者；Mica 不生成这些字段。
5. 音源状态显示初始化/解析结果；解码或网络播放错误由现有播放器报告。解析成功不等于音频可播、歌曲正确或品质真实。

本目录 `direct-http-source.js` 是 Mica 自编测试夹具，无平台查询。可用自己的合法音频地址试通：

```json
{"source":"wy","name":"My authorized audio","url":"https://your-host/audio.flac"}
```

此处 `wy` 只是协议标签。脚本不下载或转码，只原样返回调用者提供的地址；选择的品质不会改变测试文件本身。该夹具通过不能证明第三方 LX 音源兼容。

## 已实现协议与差异

| 项目 | 实验边界 |
|---|---|
| 元数据 | 首个文件头注释块中的 name/description/author/homepage/version，按 LX 上游长度裁切；接受 UTF-8 BOM；无名称时 Mica 提供默认名称 |
| JS 引擎 | `wang.harlon.quickjs:wrapper-android:3.2.3`，QuickJS 2024-02-14；不使用 WebView 或 React Native |
| lx 信息 | `env=mobile`, `version=2.0.0`, currentScriptInfo 与上游事件名 |
| 初始化 | `lx.send(lx.EVENT_NAMES.inited, {sources})`，筛选 musicUrl 与上述五平台/四品质；无可用组合时报错 |
| 请求 | `lx.on(request, handler)`，传入 `{source, action:'musicUrl', info:{type,musicInfo}}`，Promise 成功值必须是 HTTP(S) URL |
| HTTP | lx.request 回调三参数、GET/POST/其他 OkHttp 支持方法、headers、JSON body、form、标量 formData、binary、返回取消函数；最多 4 个并发请求，响应最多 1 MiB |
| binary | 和上游 React Native JSON 桥一样返回 `{type:'Buffer',data:[...]}`，不是浏览器 ArrayBuffer |
| crypto | MD5；AES 128 CBC PKCS7 与上游 `AES` ECB provider 行为；RSA public-key X.509 NoPadding；随机字节沿用上游 Math.random，不声明密码学随机性 |
| buffer | from / bufToString，hex/base64/UTF-8；显式修正上游对二进制 Base64、非 BMP Unicode 的转换，见 asset NOTICE |
| 定时器 | setTimeout/clearTimeout，每运行时最多 64 个，延迟上限 60 秒；clearTimeout 同时取消宿主定时器 |
| updateAlert | 接收但不自动展示、导航、下载或更新脚本 |
| 脚本协议不支持 | lyric/pic/local、搜索、Node require、DOM、文件访问、zlib、文件 multipart、脚本持久化扩展、音频 URL 额外 headers 协议；在线搜索、封面和歌词由独立内置适配器处理 |
| 播放 | URL 仅交给 `RemoteHttpPlaybackRequest`，不保存 URL；保留现有 HTTP range 行为与“不跟随重定向”策略，需要脚本返回最终音频地址 |
| 品质 | 只请求用户指定且脚本声明的品质，不重试更低品质，不更改 DSP、采样率、位深或 USB 路由 |

## 生命周期与并发审查

`LxSourceOwner` 是脚本存储、运行时、初始化状态与唯一临时测试曲目的 owner。未新增 `RemoteSourceType`：实验没有远端 catalog 或同步能力，不能冒充可同步曲库。服务仅接 `RemoteHttpPlaybackRequestResolver` 与受信任曲目 lookup。

| 等待/副作用 | 协议与测试 |
|---|---|
| 文件读取/导入 | 导入开始增加 generation；有界文件读取后检查 coroutine 和 generation；同一 `gate` 中 AtomicFile 写入成功后发布脚本。延迟旧文件读取 vs 新导入、磁盘删除测试覆盖 |
| 初始化 | runtimeFactory 归 owner；QuickJS 整个 JS turn 返回后才完成初始化；await 后在 gate 下复验 generation，再发布平台/品质。删除/释放 vs 延迟初始化测试覆盖 |
| 更换/禁用/删除/释放 | 同一 gate 增加 generation 和 requestId、清除临时曲目、关闭旧 runtime；存储更新和内存发布经同一 seam。释放不删除已提交的用户脚本 |
| musicUrl | 每次请求增加 requestId，解析返回后验证 generation/requestId/当前 trial 身份，再发布 URL 与状态；停用及新测试曲目 vs 延迟结果测试覆盖 |
| HTTP/计时回调 | runtime 的短 gate + closed 标记约束请求入队、取消和 deferred 完成；响应读取结束后检查 call 身份，在真正排队的 JS 回调内再次检查；关闭清除 calls/timers，JS destroy 在唯一 JS 线程执行 |
| 已打开音频 | 停用/删除使后续解析失效；不强制中断已经打开的 HTTP 音频。播放层在 resolver 成功后使用地址，未新增“撤销已接受播放”的协议 |

JVM 测试验证真实 owner 的持久化/状态副作用，不仅检查取消标记。Android 契约测试另覆盖实际 QuickJS、异步 HTTP、磁盘重新加载、受信任 MediaItem、MediaSession 与播放进度。未运行的 Android 测试不能算 native/设备验收。

## 限制和容量

- 初始化 15 秒、musicUrl 20 秒、HTTP 默认 13 秒且最大 60 秒；单次同步 JS turn 5 秒 watchdog，失败后拒绝该进程继续启动脚本。
- **没有进程隔离，也没有原生执行中断。** Wrapper 3.2.3 的 Java API 不提供 interrupt。Watchdog 能让等待者失败并关闭网络/计时资源，不能终止无限循环的 native JS；这种情况需要彻底结束并重启应用。不能将此实现宣称为恶意脚本沙箱或可靠 crash isolation。
- 单个 QuickJS context 堆限制 32 MiB、栈限制 512 KiB；单个脚本 512 KiB、MusicInfo 32 KiB、唯一 trial、最多 4 个 1 MiB HTTP 响应。二进制响应的 JSON/boxed byte 转换有数倍临时开销。旧 context 销毁和新 context 创建在同一线程排序；挂死后不增加工作线程。
- 本地曲库 snapshot、扫描与音频处理沿用 0.4.4。在线歌曲最多 10,000 条；启动只载入摘要，逐行读取至多 8 KiB 的 MusicInfo 后丢弃原始 JSON，不常驻全部歌词。歌词和封面分别有 16 MiB 缓存预算，二者合计最多 2 个并发加载。详细内存边界与尚未测量的设备风险见 MIGRATION_044.md。
- 临时测试 MusicInfo 不持久化；应用重启后旧测试队列不能重新解析，需重新填写测试播放。脚本存于 noBackupFilesDir 的 AtomicFile，不进入云备份。

## 构建与发布

使用已配置的固定 release keystore 签名；不要用 debug 签名发布。发布标签为 `lx-v0.4.4-lx.1`，仅触发 `android-lx-release.yml`。流水线运行测试、lint、R8 release 构建、三个 ABI APK 验证和签名验证，然后创建 prerelease，显式 `latest=false`。本分支不包含正式版发布工作流，不部署 GitHub Pages。后续 LX 版本需要同时更新 Gradle 版本及 `updates/lx.json`；应用拒绝正式版清单和 Latest 下载链接。

## 验证命令

PowerShell 构建前只对本进程清除可能污染 NDK 的环境变量：

```powershell
$env:ANDROID_HOME = 'C:\Users\leco\AppData\Local\Android\Sdk'
$env:C_INCLUDE_PATH = $null
$env:CPLUS_INCLUDE_PATH = $null
$env:LIBRARY_PATH = $null
.\gradlew.bat :app:testDebugUnitTest --tests 'com.mica.music.data.lx.*' :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-daemon --no-configuration-cache --no-parallel
```

设备测试必须使用独立包名；下面命令会安装独立实验包及测试 APK，执行前确认设备并获得安装授权：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.mica.music.media.LxSourcePlaybackContractTest' --no-daemon --no-configuration-cache --no-parallel
```

## 来源

- LX Mobile [上游](https://github.com/lyswhut/lx-music-mobile/tree/d2956044533150057d65bfeaf2ae0fb5179433f5)：preload、QuickJS Android host、`src/core/init/userApi`、`src/utils/data.ts`；LICENSE 为 Apache-2.0，README 另有项目使用声明。此实验未移植其平台 SDK。
- [QuickJS wrapper 3.2.3](https://github.com/HarlonWang/quickjs-wrapper/tree/cfdc05fa11ac866558297e888fbac5580905e194)，引擎 submodule `c46516996da53eaf2f9aca516264870e25719d76`。资产目录保留两个 Apache 文本、QuickJS MIT 文本和 Mica 修改 NOTICE。
- 独立工作树基线 `4e794210` 自带 `LibraryScanOrchestrator.kt` 扫描提示字符串缺少引号；为构建实验版仅修复该行文案/语法，未改扫描控制流。

本次执行结果见 `VALIDATION.md`；真实第三方脚本样本未提供，兼容率未知。
