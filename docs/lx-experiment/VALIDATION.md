 > 历史记录：本文件记录 0.4.1 / lxexperiment 阶段的测试，不能视为 0.4.4-lx.1 已通过的设备证据。本轮验证见 MIGRATION_044.md。

# LX 实验验证记录

2026-09-12 交付复核。工作树：

```text
C:\Users\leco\.codex\worktrees\c585\mica-music-a8fa312e3b45477f922d0dde3ca38e99d203cebc
```

基线 `4e794210`；修改尚未提交、合并或推送，未安装到任何设备。

## 已执行并通过

| 验证 | 结果 / 证据 |
|---|---|
| JVM / Robolectric | 10 tests、0 failures、0 errors：LxProtocolTest 4 项，LxSourceOwnerTest 6 项。报告 `app/build/test-results/testDebugUnitTest/` |
| Node preload | `node --test tools/test-lx-preload.cjs`：5 tests passed。仅 JS 协议证据，native bridge 由测试适配器提供 |
| 应用 APK | `:app:assembleDebug` 成功；独立 applicationId `com.mica.music.lxexperiment`，0.4.1/code55，minSdk 26 |
| Android 测试 APK | `:app:assembleDebugAndroidTest` 成功；独立 applicationId `com.mica.music.lxexperiment.test` |
| lint | `:app:lintDebug` 成功，0 errors、447 warnings；没有将 warning 数量表述为全清。报告 `app/build/reports/lint-results-debug.html` |
| 签名 | 两个 APK 的 `apksigner verify --verbose` 均通过，APK Signature Scheme v2 |
| 打包检查 | ARM64 / ARMv7 均含 QuickJS、FFmpeg、TagLib、USB native 库；LX preload、NOTICE、LX/QuickJS-wrapper Apache 文本与 QuickJS MIT 文本在 APK assets 中 |
| diff | `git diff --check` 通过 |

最终构建日志：`.scratch/lx-delivery-build.log`，`BUILD SUCCESSFUL in 2m 37s`。该轮包含 targeted JVM tests、应用 APK、Android 测试 APK 与 lint。

JVM 交错测试覆盖：旧初始化 vs 删除、旧导入读取 vs 新导入、延迟 URL vs 停用、旧初始化 vs 释放、旧 URL/错误 vs 新 trial、存储写入失败后的能力退役。另有实际 AtomicFile 删除 vs 延迟文件读取及重新读取测试。

## 产物

应用：`app/build/outputs/apk/debug/app-debug.apk`

```text
SHA-256 E3FE79A2B04A81396BEC1E08939E087A3FC393A3D6FA62E0E19F6DFE8A055A9B
```

测试：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

```text
SHA-256 56441891BF7743A3C1DB2E637D619BAC4FFC6A2D5A8F031BB7F4E61017EFC969
```

## 未验证，不能算通过

- ADB `devices -l` 为空，没有已配置 AVD。未执行 Android instrumentation，未执行 QuickJS Android 真运行、HTTP 到 MediaSession 的真实播放链、文件选择器/UI 手动试用或视觉验收。
- `LxSourcePlaybackContractTest` 已编译打包，计划执行真实 QuickJS 的 MD5、二进制 Base64、Unicode、定时器、HTTP 回调、磁盘重新加载、MediaSession 及播放进度；它使用自编夹具和生成的静音 WAV，不代表第三方音源样本通过。
- 未提供真实第三方 LX 脚本，兼容率未知。未验证 AES/RSA 在目标设备 provider 上的实际行为、CDN/重定向/Range/大文件或长时间播放。
- 未验证 8 GB 手机总 RSS / 峰值、后台生命周期、CPU 死循环恢复或 native crash isolation。空间上界和无法终止同步 JS 的限制见 README。

已处理的构建前置问题：仅对构建进程清除了 MinGW `C_INCLUDE_PATH` / `LIBRARY_PATH` 污染；按仓库固定 gitlink 补齐 utfcpp；修复基线扫描提示缺少引号的一行语法错误。未把这些问题解释成 LX 协议失败，也未修改扫描逻辑。

## 2026-09-12 GitHub 真实音源补充验证

本轮补齐此前“未提供真实第三方 LX 脚本”的缺口，验证工作树仍为本文件开头记录的 c585 LX 实验工作树。产品代码未因本轮验证发生兼容修复；新增内容仅为 androidTest 真源样本/测试与本记录。

真实样本：

- `cdyUuu/lx-music-xinghai-source` / `xinghai-music-source.js`，脚本头版本 `v3.2.12`。现代 async 写法，覆盖 `globalThis.lx`、HTTP、定时器、五平台能力发布。
- `Macrohard0001/lx-ikun-music-sources` / `v260211/3.全平台mp3_320kbps/废材公社_内部源.js`，脚本头版本 `v1.1.2`。Webpack bundle，覆盖 `currentScriptInfo.rawScript`、buffer、MD5/AES/RSA、form/GET 请求等旧式复杂写法。

执行结果：

| 验证 | 结果 |
|---|---|
| 两份 GitHub 原始脚本 `node --check` | PASS |
| `LxRealGitHubSourceCompatibilityTest` 真机 Android QuickJS | PASS，2/2；两份脚本均实际执行初始化并发布 `wy/tx/kg/kw/mg` 的可播放能力 |
| `LxSourcePlaybackContractTest#importRestartResolveAndPlayThroughService` | PASS，1/1；真机完成导入、持久化重启恢复、QuickJS HTTP 解析、MediaSession 交接与实际播放进度前进 |
| `node --test tools/test-lx-preload.cjs` | PASS，5/5 |
| `LxProtocolTest` + `LxSourceOwnerTest` JVM/Robolectric | PASS，Gradle `:app:testDebugUnitTest` 成功 |
| 隔离应用/测试 APK 构建 | PASS；`com.mica.music.lxexperiment` / `com.mica.music.lxexperiment.test` |

测试设备：`172.17.57.16:37587`，22081212C，Android 12 / API 31。MIUI 对新包 ADB 安装弹确认，本轮使用设备已有 Magisk root 通过 `pm install` 安装隔离实验包。

结论：当前 Mica LX 宿主已对两类真实公共 LX 脚本证明“可载入、可执行、可完成初始化/能力协商”，且宿主自己的 URL 解析到 MediaSession 播放链已在同机闭环。未将第三方上游 API 的当前可用性计入兼容 PASS：真实脚本的外部解析服务、密钥、地区/CDN、会员状态等会独立变化，因此本轮不宣称任意第三方脚本此刻都能成功返回并播放真实歌曲 URL。

## 2026-09-14 真实 `musicUrl` / 真歌曲播放验证

本轮在同一 c585 LX 实验工作树继续补齐“真实第三方脚本是否能返回真实歌曲 URL，并通过 Mica MediaSession 实际播放”的缺口。测试始终使用隔离 applicationId `com.mica.music.lxexperiment` / `com.mica.music.lxexperiment.test`；未清除或卸载正式 `com.mica.music` 数据。

新增第三份真实样本 `Free listen v1.1.1`，并将三份真实脚本的初始化覆盖扩展为 3/3。三份脚本均在 Android QuickJS 中实际执行并发布平台能力。

真实播放结果：

| 音源 | 分支 / 曲目 | 结果 / 证据 |
|---|---|---|
| 星海 `v3.2.12` | `wy/128k` / 《起风了》 | PASS；第三方 `musicUrl` → Mica resolver → MediaSession → `STATE_READY`，播放进度到 253 ms |
| 废材公社 `v1.1.2` | `tx/128k` / 《卡农 (经典治愈版)》 | PASS；QQ 分支真实返回 URL，MediaSession 播放进度到 268 ms |
| Free listen `v1.1.1` | `tx/128k` / 《卡农 (经典治愈版)》 | PASS；单独复跑 `OK (1 test)`，`LX_QQ_SMOKE` 记录播放进度 257 ms |

对应 instrumentation：

- `LxRealGitHubSourceCompatibilityTest`：三份真实脚本初始化 / 能力发布。
- `LxRealGitHubPlaybackSmokeTest`：真实脚本 `musicUrl` 到 Mica 播放链 smoke。
- `LxLegacySourceQqPlaybackSmokeTest`：废材公社与 Free listen 的 QQ `tx/128k` 真歌曲播放 smoke。

失败分支归因也做了宿主外对照，避免把第三方 API 老化误判为 Mica 宿主不兼容：

- 废材公社与 Free listen 的旧网易 EAPI 路径在 Mica 外按脚本相同 MD5 + AES-EAPI 算法请求多首歌曲，均返回 `code=404`、`url=null`；因此两者 `wy/128k` 当前失败不能作为宿主兼容失败证据。
- 两份旧脚本使用的酷狗 `play/getdata` 路径在宿主外请求也返回 `status=0`、`err_code=30020`。
- QQ `musicu.fcg / vkey.GetVkeyServer` 仍可为匿名可播条目返回非空 `purl`；同一真实条目随后在废材公社与 Free listen 两份脚本中均由 Mica 成功解析并播放。

因此本轮结论从“宿主可载入真实 LX 脚本”提升为：**Mica LX 宿主已对三类真实公共脚本完成 Android QuickJS 初始化验证，并至少对三条真实第三方 `musicUrl` 路径完成实际播放闭环。旧音源中的单个平台分支可能因第三方上游接口、匿名权限、地区或服务状态而失效，这类失败应与宿主协议兼容性分开判定。**
