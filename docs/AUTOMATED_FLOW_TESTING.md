# Mica 自动化流程测试设计

> 状态：2026-09-04 起采用。目标不是把所有真机验收硬塞进 CI，而是补上“真实用户动作穿过 Activity / Compose / Android 框架后是否仍然成立”这一层。

## 1. 为什么需要单独的流程层

Mica 现有 JVM / Robolectric 测试对解析器、队列、状态机、数据库、播放协调器和 UI 组件覆盖已经很厚；`androidTest` 也有 Media3、Room、SAF、真实解码等组件契约。

旧缺口在于：大多数测试从某个内部 seam 开始，无法证明用户真正从 `MainActivity` 发起动作后，导航、框架回调、Service、持久化和 UI 会按同一条生产链路工作。

因此新的流程测试只解决这一类问题：

```text
用户动作
→ 真实 Activity / Compose
→ 导航或 Android framework boundary
→ 领域协调器 / Service
→ 可观察结果
```

它不替代纯逻辑单测，也不冒充蓝牙、USB DAC、OEM 后台策略或音质验收。

## 2. 四层门禁

### Gate A — `micaCheck`：确定性代码门

```powershell
.\gradlew :app:micaCheck --no-configuration-cache
```

负责：

- Debug 生产代码编译；
- `androidTest` 源码编译，防止设备测试长期腐烂；
- Lint；
- JVM / Robolectric；
- Roborazzi 基线。

要求：PR 和本地提交前保持全绿。这里可以用 fake 外部边界，但不能用 fake 证明 Android/OEM 会产生某个事件。

### Gate B — Android Device Flow：真实 App 流程

```powershell
.\scripts\run-android-flow-tests.ps1
# 多设备时：
.\scripts\run-android-flow-tests.ps1 -Serial <adb-serial>
```

规则：

- 只运行 `app/src/androidTest/.../flow/**`；
- 必须使用 `-Pmica.qaSideBySide=true`，目标应用固定为 `com.mica.music.qa`；
- 脚本拒绝无设备、多设备未指定 serial、以及非 ARM ABI；
- 不清除普通 `com.mica.music` 数据，不读取用户真实音乐作为 fixture；
- 只允许运行时生成的最小媒体、QA 私有状态或显式测试 provider。

这是“用户从 UI 做了一件事”的首个自动化证据层。

### Gate C — Android Component Contract：真实组件契约

现有 `androidTest` 继续覆盖：

- MediaSession / MediaController / ExoPlayer；
- Service 重建；
- Room migration；
- SAF / ContentResolver；
- TagLib 与真实容器；
- ALAC / DSF / APE 解码；
- 离线响度解码。

它证明 Android/Media3 组件会产生业务依赖的真实事件，但不要求一定从 UI 起手。

流程测试与组件契约不能互相替代：一个检查“入口到结果”，另一个检查“关键 framework boundary 的真实语义”。

### Gate D — Hardware / OEM Acceptance：硬件与厂商行为

仍由 AgentDock + ADB/人工验收承担：

- 蓝牙 / 车机 AVRCP；
- 音频焦点与耳机拔出；
- USB Exact PCM / DoP / Native DSD；
- OEM 通知、锁屏、后台限制；
- 实际音质、断音、时延、温控。

这些场景必须保留物理设备证据，不为了“看起来全自动”而写虚假的 emulator 测试。

## 3. Device Flow 的测试写法

### 3.1 必须从生产入口起手

优先使用：

- `MainActivity`；
- 真实 Compose semantics；
- `ACTION_VIEW` Intent；
- 真实 `MediaSession` / Service；
- 真实 SAF test provider。

不要在流程测试中直接调用内部 `navigateToX()` 后声称“用户导航流程已通过”。

### 3.2 每个流程至少三个 checkpoint

每个测试至少断言：

1. **触发已被 UI 接受**：例如进入设置、搜索词实际进入 TextField；
2. **跨边界后的中间状态正确**：例如进入正确 category / Service 当前 mediaId 正确；
3. **最终用户结果正确**：例如返回路径、播放状态、持久化或恢复结果正确。

涉及播放写操作时继续遵循 `EXTERNAL_EVENT_CONTRACT_TESTING.md`：同时审计 timeline / transition / discontinuity / observer 副作用。

### 3.3 禁止脆弱同步

禁止固定 `sleep(1000)` 作为成功条件。

使用：

- Compose idling；
- `ContractTestSupport.await`；
- 明确状态条件；
- 有上限的超时。

失败必须指出停在哪一个 checkpoint，而不是只报“最后一个文本没出现”。

### 3.4 测试数据隔离

流程测试默认运行 QA application id。测试代码不得：

- 清空普通 Mica 数据；
- 扫描或修改用户真实音乐；
- 删除设备文件；
- 依赖本机某一首歌已经存在；
- 依赖公网服务。

需要媒体时生成最小 WAV / DSF / 容器 fixture，或使用 `androidTest/assets/media` 内已提交的最小契约样本。

## 4. 首批流程矩阵

| 优先级 | 流程 | 自动化层 | 状态 |
|---|---|---|---|
| P0 | Home → 侧栏 → 设置 → 搜索 ReplayGain → 音频与设备 → 返回 → 搜索教程 → 打开/关闭教程 | Device Flow | **已接入** `MainActivitySettingsFlowTest` |
| P0 | 首次教程 → 扫描邀请 → 权限批准/拒绝 → 扫描启动 | Device Flow + SAF contract | 待补 |
| P0 | `ACTION_VIEW content://` 冷启动 → 临时单曲 → warm `onNewIntent()` → 返回曲库 | Device Flow + provider contract | 待补 |
| P0 | 生成 WAV → 点歌 → 播放/暂停/seek/下一首 → 后台 → Controller 重连 | Device Flow + Media3 contract | 待补 |
| P0 | 通知歌词开/关 → metadata 更新不得误计播放 → 系统媒体控制仍可用 | Device Flow + Media3 contract | 待补 |
| P1 | SAF 选目录 → 扫描 → Activity 重建 → 授权仍在 | Device Flow + SAF contract | 待补 |
| P1 | 设置修改 → Activity 重建 / 进程级 owner 重连 → UI 与真实配置一致 | Device Flow | 待补 |
| P1 | 远端来源配置 UI → 错误状态 → 修正后恢复 | Device Flow + 本地 fake server | 待设计 |
| 物理 | 蓝牙/车机上一首下一首、通知歌词兼容 | Hardware/OEM | 不进普通 Device Flow |
| 物理 | USB 独占模式切换、拔插、授权、DSD | Hardware/OEM | 不进普通 Device Flow |

新增流程按“用户损失 × 框架边界数量 × 历史回归频率”排序，而不是追求测试数量。

## 5. CI 策略

### 当前阶段

GitHub Hosted runner 继续执行 Gate A，并显式编译 `androidTest`。Mica 当前 native ABI 为 `arm64-v8a` / `armeabi-v7a`，因此不把 x86_64 GitHub emulator 假装成可执行的 Device Flow runner。

Device Flow 由连接的 ARM QA 设备一条命令执行，已经具备之后迁入 self-hosted runner 的固定入口。

### 有稳定 ARM self-hosted runner 后

按以下顺序接入：

1. Nightly 先运行 `scripts/run-android-flow-tests.ps1`；
2. 连续稳定后，P0 Device Flow 升为 main/release 必须通过；
3. P1 仍 nightly；
4. USB / 蓝牙 / OEM 继续独立物理矩阵，不和普通 Device Flow 混在一个 job。

禁止在没有可用 ARM runner 时创建永久排队的“假 CI gate”。

## 6. 失败归因

流程失败按下面顺序分类：

- **ENTRY**：点击/Intent/权限事件没有到生产入口；
- **BRIDGE**：Activity → framework / Session / provider 传输失败；
- **DOMAIN**：生产协调器接受事件后状态错误；
- **OUTPUT**：持久化、通知、播放器或 UI 没有反映正确结果；
- **ENVIRONMENT**：设备、权限、ABI、系统服务不满足测试前提。

只有 `ENVIRONMENT` 可以跳过；业务层失败不得用重试变绿。

## 7. 晋级规则

一个历史线上 bug 若属于 Activity、Android framework、Service、Binder、ContentProvider 或生命周期边界：

1. 先保留最低层回归测试；
2. 若旧测试曾经全绿而用户仍可复现，必须补至少一个 Component Contract 或 Device Flow；
3. 第二次出现同类型边界问题时，相关流程升级为 P0；
4. P0 流程不得依赖公网、用户音乐或人工点击。

这样让自动化增长在“曾经漏过的问题”上，而不是把已有状态机测试再复制一遍。
