## 异步共享状态与副作用一致性（硬性）

凡是会把异步工作的结果写入共享状态、持久化存储、文件缓存、媒体快照或发布给观察者的流程，都必须把“代际/请求有效性”和“写入串行化”作为同一个协议处理。曲库 snapshot 还必须遵循 `CONTEXT.md` 与 `docs/adr/0002-library-snapshot-publication.md` 中的 `scanGeneration`、`storeRevision`、`storeSyncMutex` 约束。

- 每次全量替换、清空、释放、重新加载或新请求都必须产生新的 generation、requestId 或等价 revision。取消旧任务只是优化，不能作为正确性条件。
- 每个不可取消的 `await`、IO、解析、文件操作或回调之后，在任何实际副作用之前都必须重新校验 token；只在入口校验不算保护。一个流程有多个副作用阶段时（例如建 keep-set 后删除、提取后落盘、Room 写入后内存发布），每个阶段都要重新校验。
- 所有会影响同一份共享状态的持久化、删除、清空和内存发布，都必须经过 owner 提供的统一同步 seam（例如 `storeSyncMutex`）；不得在 caller、`fire-and-forget` 回调或后台 IO 中绕过它。
- 旧任务的局部批次不是全量 snapshot。不得让歌词批次、预热结果或其他局部结果替换、清空或污染更新后的 snapshot；清库和释放必须使旧批次无法再写回。
- 新增或修改这类流程时，必须先补一个确定性的交错测试：让旧操作停在副作用边界，完成更新后的新操作，再释放旧操作，并断言最终状态仍属于新操作。测试必须覆盖实际副作用（持久化、删除、缓存落盘、内存发布等），不能只测试取消标志、任务计数或入口 generation。
- 代码评审必须逐项列出 generation/requestId owner、每个取消或不可取消等待点、每个实际副作用、共享同步 seam 和对应的交错测试。无法证明这些不变量时，必须标注风险，不得声称已修复。

## Agent skills

### Issue tracker

Issues 和 PRDs 以 markdown 文件存放在 `.scratch/<feature-slug>/` 下。详见 `docs/agents/issue-tracker.md`。

### Triage labels

五个 canonical triage roles 使用默认 label 字符串。详见 `docs/agents/triage-labels.md`。

### Domain docs

Single-context 布局：根目录 `CONTEXT.md` + `docs/adr/`（尚未创建，skills 会在需要时懒创建）。详见 `docs/agents/domain.md`。

### UI 设计文档前置阅读（硬性）

任何涉及 UI 的设计、视觉或交互改动，开始实现前必须先阅读 `docs/DOC_INDEX.md`，再按任务阅读 `DESIGN_SPEC.md` 及对应专题文档（例如 `MOTION.md`、`PLAYER_PAGE_CONTRACT.md`、`COVER_FLOW_IMPLEMENTATION.md`）。实现必须遵循文档中的形状、间距、动效、可访问性与性能约束；如果用户明确提出覆盖文档的要求，须在改动中保持该要求可追溯。

### 音质改动（硬性）

任何可能**降音质**的改动须**事先向用户明确说明影响范围**，并**得到明确允许**后才能实现或默认启用。详见 `CONTEXT.md`（**Audio quality consent**）与 `.cursor/rules/audio-quality-consent.mdc`。

### 超大曲库容量基线（硬性）

任何涉及曲库的设计或改动，必须以“**10,000 首歌曲、每首均有完整逐字歌词、8 GB 内存 Android 手机**”作为容量基线，评估启动、扫描、加载、排序、保存、同步和缓存时的内存峰值与稳定性。不得非必要地全量解析、编码、复制或常驻歌词；优先使用懒加载、有界缓存、分批或流式处理。完成前须用测试、测量或可审查的内存上界说明该设备能够承受；无法确认时必须明确标注风险，不得宣称安全。

## Cursor Cloud specific instructions

面向已运行过 update script 的后续 cloud agent。工具链已预装进 VM 快照，无需再装。

### Toolchain（已预装，勿重复安装）

- 默认 `java` 已通过 `update-alternatives` 指向 **JDK 17**（`/usr/lib/jvm/java-17-openjdk-amd64`）；AGP 8.7 / Gradle 8.9 需要 17，不要改回 21。
- Android SDK 位于 `~/Android/sdk`（`platform-tools` + `platforms;android-35` + `build-tools;35.0.0`，许可证已接受）。`JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT` 已写入 `~/.bashrc`。
- `local.properties`（含 `sdk.dir`）被 gitignore；update script 会在每次启动时重建它。Gradle 也能直接读 `ANDROID_HOME`。

### 构建 / 测试 / 运行（命令见 `README.md`、`docs/TESTING.md`）

- 文档里的命令是 Windows/PowerShell 风格（`.\gradlew`）；本 Linux VM 用 `./gradlew`。所有 Gradle 命令都带 `--no-configuration-cache`（与 CI 一致）。
- 可在本 VM 跑通的门禁：`./gradlew :app:assembleDebug`（产出 `app/build/outputs/apk/debug/app-debug.apk`）、`./gradlew :app:testDebugUnitTest`（JVM/Robolectric）、`./gradlew :app:micaScreenshotFull`（Roborazzi 渲染 Compose UI 并比对 `app/src/test/snapshots` 基线）。这些对应 CI 里通过的 `jvm-tests`、`screenshots` job，无需真机。

### 重要 gotcha

- **`:app:micaCheck` 目前会在 `lintDebug` 阶段失败**，原因是既有代码里的 lint error：`app/src/main/java/com/mica/music/ui/theme/AnimatedTheme.kt:225`（`ProduceStateDoesNotAssignValue`）。这不是环境问题——同一 commit 在 GitHub CI 的 `Android tests / compile-lint` job 上也同样失败。因为 `micaCheck` 在 lint 处即中止，想验证测试/截图请分别单跑 `:app:testDebugUnitTest` 和 `:app:micaScreenshotFull`。修掉该 lint error 前，别把 `micaCheck` 失败当成环境坏了。
- `:app:micaRecordScreenshotFull` 会**覆盖** `app/src/test/snapshots/*.png` 基线；仅在有意更新基线时用，验证请用 `verifyRoborazziDebug` / `micaScreenshotFull`。
- **无设备/模拟器**：本 app 仅 `arm64-v8a` ABI，云 VM 没有 arm64 设备。`connectedDebugAndroidTest`、真实播放/解码（ALAC/DSF/APE）、SAF 等设备契约测试无法在此运行——它们属于设备验收清单（见 `docs/TESTING.md`）。
- DSD FFmpeg 原生库（`libffmpegJNI.so`）为 debug 可选、release/perf 必需，需 Docker + NDK r26d 单独构建（见 `ffmpeg/docker/`）；debug 构建缺它会回退 Maven 版且照常成功。
