# USB Compatibility Adversarial Corpus

> 最后更新：2026-08-14
> 状态：设计已采纳；测试基础设施待实现
> 适用范围：USB Exclusive PCM / DoP / Native DSD 的 descriptor、clock、feedback、capacity、framing、quirk 与 recovery 资格测试

## 1. 目的

Mica 不应只用“正常 DAC 描述符”和当前手头设备做兼容性测试。成熟参考项目已经暴露了大量可接受的 USB 拓扑、恢复策略和设备特殊情况，这些信息可以进一步转化为**边界约束**，再由 Mica 自己生成全新的 synthetic fixtures。

核心方法不是复制参考项目的数据或实现，而是：

```text
参考项目的设计 / 接受条件 / 恢复条件
                ↓
抽取 capability / boundary predicates
                ↓
反向生成“刚好合法、刚好非法、单事实变形”的 synthetic corpus
                ↓
Mica parser / planner / selector / recovery / quirk merge
                ↓
验证 accept / reject reason / fail-closed 行为
```

参考实现因此只是 **candidate generator / coverage oracle**，不是绝对正确性 oracle。

`REFERENCE_ACCEPTED` **不等于** `MICA_MUST_ACCEPT`。如果参考项目愿意在证据不足时尝试，而 Mica 的 exact-only / fail-closed 策略要求拒绝，则 Mica 拒绝仍可能是正确行为。

## 2. 为什么重要

USB Audio 兼容问题高度组合化：同一个 PCM/DSD 目标可以由不同 alternate、service interval、clock topology、feedback endpoint、packet capacity 和 device-specific framing 组成。只用几个真实 DAC 做 happy-path 测试容易漏掉：

- ceil/floor 或 service-interval 算错一帧；
- exact clock 边界差 1 Hz；
- endpoint capacity 刚好少 1 byte；
- 多个 clock / feedback candidate 的歧义；
- descriptor 能证明 RAW_DATA，但不能证明 Native DSD endian/framing；
- static quirk、generic facts、learned runtime facts 相互冲突；
- recovery 策略把一次运行时失败错误固化成设备黑名单。

Adversarial corpus 的目标是让这些情况在不接真实 DAC 的情况下成为稳定回归门禁。

## 3. 证据来源与版权边界

允许的输入来源：

1. USB/UAC 标准和公开协议事实；
2. Mica 自己的真实设备 evidence，经抽象后生成 synthetic fixture；
3. 参考项目公开表现出的设计结构、接受/拒绝条件、测试边界和能力模型；
4. 有明确 provenance 的设备 quirk / controller-family 证据。

禁止把参考项目实现直接复制为 Mica 测试生成器。GPL 项目尤其只允许做**行为/设计观察和独立重实现**。真实设备 descriptor、VID/PID、trace 是否可进入公开 fixture 仍遵守各自 provenance/privacy/license 规则；优先使用虚构 identity 的 synthetic descriptor。

NeriPlayer 自身也区分 synthetic public USB fixtures 与真实设备私有 evidence，这种边界与 Mica 的 corpus 设计兼容。

## 4. Corpus 的基本单位

每个 case 应记录：

```text
caseId
sourceReference
sourceLicenseOrProvenance
predicate / capability rule
synthetic facts
expected Mica classification
expected reject reason（如适用）
confidence / evidence strength
unsafe inference notes
```

Case 不应只写“应该通过”。应尽可能要求**具体状态或拒绝码**，例如：

- `SupportedExact`
- `FramingUnproven`
- `CLOCK_RATE_UNPROVEN`
- `CLOCK_RATE_MISMATCH`
- `ENDPOINT_CAPACITY_INSUFFICIENT`
- `RUNTIME_FRAME_GEOMETRY_MISMATCH`
- `AMBIGUOUS_CLOCK_TOPOLOGY`

实际名称以 Mica 当前 typed result 为准。

## 5. Boundary-derived fixtures

### 5.1 Endpoint / service-interval capacity

对每个目标 carrier 或 PCM frame geometry 自动求出 `requiredBytesPerServiceInterval`，至少生成：

```text
required - 1  → 必须拒绝容量不足
required      → 容量条件刚好成立
required + 1  → 仍成立
```

覆盖至少：

- PCM16；
- packed PCM24；
- PCM24-in-32 / PCM32；
- DoP DSD64 / DSD128 / DSD256；
- Native RAW_DATA DSD64 / DSD128 / DSD256。

当前 SK02 alt4 已提供一个真实雏形：DSD256 Native RAW_DATA 需要 360 B/service，359 应 fail closed，360 容量成立，但若 framing 未证明仍只能是 `FramingUnproven`。

### 5.2 Exact clock boundary

对 exact-only rate 生成：

```text
rate - 1 Hz
rate
rate + 1 Hz
```

并扩展到：

- range 端点刚好包含 / 刚好排除；
- clock source 缺失；
- selector 唯一输入 / 多合法输入；
- GET_CUR 与 RANGE 一致 / 冲突；
- RAW_DATA alt 切换前后 clock authority 不同；
- 44.1k-family 与 48k-family 的 exact rate。

目标是防止“最接近采样率”偷偷进入 bit-perfect 路径。

### 5.3 Feedback boundary

生成合法与边界 malformed feedback：

- 3-byte / 4-byte fixed-point；
- fractional-bit 不同但显式声明的合法 profile；
- short packet；
- reserved / required-zero bit 置位；
- 极小 jitter、突发 jitter；
- 一个 poll 丢失、连续 poll 丢失；
- long scheduling gap 后重新捕获；
- feedback poll cadence 与 data service cadence 不同。

必须分别验证 decoder、normalization、scheduler/recovery，而不是用一个“设备名 quirk”掩盖所有问题。

### 5.4 Descriptor ambiguity

重点生成看起来“都能播”的模糊组合：

- 两个可行 alternate；
- 两个 clock source；
- 多 feedback endpoint；
- RAW_DATA 与 PCM alternate 共存；
- descriptor 声称支持但 endpoint capacity 不足；
- capacity 足够但 clock 不闭合；
- clock + capacity 都足够但 Native framing 未知。

Mica 的目标不是在所有模糊场景都选出一个方案，而是：**有足够唯一证据才接受，否则以明确原因 fail closed。**

## 6. Metamorphic testing

一个合法 fixture `F` 应自动派生只改变一个事实的 sibling cases，例如：

```text
F.capacity -= 1
F.clock += 1 Hz
F.channels += 1
F.feedbackPayloadBytes -= 1
F.clockSelector += one equally-valid input
F.nativeFramingEvidence = absent
```

每个变形都必须有可解释的 classification 变化。这样可以定位某个结论究竟依赖哪个事实，并防止多个条件互相“补错”。

建议 deterministic seed 固定，确保生成 corpus 可重复；随机/属性测试可以作为补充，但不能替代可审计的边界 case。

## 7. Reference-derived predicates

当前参考项目提供的方向至少包括：

### NeriPlayer

- descriptor-first exact format/rate；
- UAC2 clock topology；
- explicit feedback resolution；
- long-gap feedback reacquisition；
- backpressure / startup recovery；
- synthetic fixture 与真实设备 evidence 分离。

NeriPlayer（GPLv3）只作设计/边界观察，不复制实现。

### sylvakru

- 通用 descriptor 路径之外存在静态 VID:PID / vendor quirk；
- quirk 可补 DoP 支持上限、Native DSD framing、clock settle、validation 等 descriptor 无法可靠表达的事实；
- DSD pause/source-gap 使用 carrier idle 且保持 marker/session continuity。

### RawS

- Native DSD 主要由 RAW_DATA/clock/capacity 事实驱动；
- 多种 recovery outcome 倾向记录为 learned profile facts，而不是全部写死成 VID/PID quirk；
- runtime success/failure 与静态设备能力应分层。

这些参考项目彼此不同正是 corpus 的价值：它们暴露不同的 capability 假设，可以转化成更多 adversarial predicates。

## 8. Quirk 与 learned-fact 的 corpus

未来 Mica 如果加入兼容层，测试必须覆盖至少三层输入：

```text
generic USB facts
+ evidence-backed static quirk
+ learned session/profile facts
```

静态 quirk 只允许补协议无法表达、且已有可靠设备证据的事实，例如某 VID:PID 的 Native DSD framing。运行时 timeout、feedback failure、成功 reopen 等不应自动升级成永久静态 quirk。

需要生成的冲突 case：

- generic facts 不足，精确 quirk 刚好补齐；
- quirk 只补一半，仍 fail closed；
- exact VID:PID quirk 与 vendor-level quirk 冲突；
- quirk 与 descriptor 明确事实冲突；
- learned fact 与 static quirk 冲突；
- stale/旧版本 quirk provenance 不再匹配固件或设备 identity。

建议 precedence 最终由正式 quirk 设计冻结，但 corpus 应先要求冲突**不能静默成功**。

### 8.1 设备身份碰撞与 claim-scoped matching

2026-08-14 的 Linux Hardware / LsUSB 社区 probe 审计证明：USB DAC 的产品身份不能简单由 `VID/PID`，甚至不能稳定地由 `VID/PID + bcdDevice + 非字符串 descriptor topology` 唯一确定。

已知真实碰撞：Fosi Audio SK02 与 Douk Audio K5 都可出现 `262a:0001`、`bcdDevice=0x0004`，且 normalized non-string UAC2 topology 基本相同；Fosi Audio DS2 也复用 `262a:0001`，但 revision 为 `0x0003`。两份独立 SK02 probe（`6072a51f4e`、`6fa7d9e6df`）可以交叉验证 Mica 保存的 generic alt4 descriptor facts，但不能证明 Native DSD framing/endian、exact clock programming 成功或 runtime feedback 语义。

因此不要设计一个默认全局唯一的 `DeviceEvidenceKey`。VID/PID、bcdDevice、product/manufacturer string、serial、descriptor/topology fingerprint、interface/alt/endpoints、transport family 都只能是某条 evidence claim 的可能 scope 元素。已知 collision 的意义是**收窄 claim authority**，不是匹配失败后猜一个更像的设备。

Mica 采用 **claim-scoped evidence matching**：每条 `FeatureEvidenceClaim` 自己声明 feature、required identity facts、required generic facts、provenance 和 qualification。若 source 明确证明某条 Linux rule 对 `VID/PID + bcdDevice + alt` 生效，就按该 scope；若 SK02-specific evidence 的现有 identity scope 已知与 K5 collision，则必须进一步收窄到实际已验证的 product/provenance 字段，否则 fail closed。

### 8.2 参考项目的身份模型局限

- **sylvakru**：核心 static quirk 是 `vid:pid` exact → `vid:*` vendor → default；label 不参与核心匹配。因此在 `262a:0001` 这类 PID 复用 family 上可能把 product-specific quirk 作用到别的产品。优点是 quirk 本身 feature-scoped。
- **RawS**：learned profile 可表达 VID/PID/serial，并按 PCM/DoP/Native/DSD rate 分 transport namespace，但当前生产 `learnedPolicyKey()` 实际传入 `serial=null`，所以同 VID/PID 产品仍可能共享 learned namespace。其安全阀是 last-good 只用于 ranking/recovery，不能替代 generic eligibility proof。
- **NeriPlayer**：设备选择使用 `VID/PID + stable label`，UAC2 candidate 还带 `bcdDevice + descriptorFingerprint + configuration/interface/alternate`，不确定关系会进入 `QuirkRequired` / fail-closed。它最接近 Mica 的 evidence 模型，但 label 可能缺失/漂移，而本次 SK02/K5 已证明 non-string topology fingerprint 也不是全局唯一产品身份。

### 8.3 Collision-aware adversarial cases

Phase D 至少覆盖：同 VID/PID 不同 product；同 VID/PID+revision+topology 但不同 product（SK02/K5）；revision drift（SK02/DS2）；不同 PID 但同 vendor/topology family；product string 或 serial 缺失；descriptor fingerprint collision；learned profile identity ambiguity。上述情况都不能制造 Native framing、capacity、clock、feedback 或 hardware-volume proof，也不能把 product-specific last-good/quirk 跨碰撞产品传播。

社区 evidence 应优先保存最小事实与稳定 probe/provenance ID，避免把外部 raw logs 大量 vendor 进仓库。P5 的 `community-usb-probes-v1.tsv` 及后续 collision policy corpus 是该规则的机器可读输入。

## 9. Worker 分工建议

在当前并行开发模型下：

- **P5**：从参考项目、标准和设备 evidence 中抽取 capability predicates、证据强度和 unsafe inference；
- **P4**：把 predicate 转成 replayable synthetic fixtures、boundary generator、metamorphic cases 和 deterministic host/JVM gate；
- **P3 / production owner**：让 Mica parser/planner/transport/quirk merge 消费 corpus；生产合同仍由 P3/协调者决定。

这使测试输入和生产实现保持独立，降低“实现者自己定义自己的正确答案”的风险。

## 10. 分阶段落地

### Phase A — 手写 golden boundary corpus

先覆盖已经证明过的数学/合同边界：

- service capacity `N-1/N/N+1`；
- exact clock `rate-1/rate/rate+1`；
- feedback payload/profile malformed；
- RAW_DATA `FramingUnproven`；
- multiple-candidate ambiguity。

### Phase B — Reference predicate extraction

把 Neri/sylvakru/RawS 的兼容设计整理成结构化 rule inventory，记录 source、license、confidence、required facts 和 unsafe inference。

### Phase C — Deterministic generator

从 rule + parameter domain 生成 hundreds/thousands 的 deterministic cases，并输出稳定 case id / expected classification。

### Phase D — Quirk/learned-fact merge tests

在 Mica 正式引入 quirk 层前先冻结 precedence 与冲突 corpus，再实现 production matching。

### Phase E — Physical spot-check

Corpus 只能证明软件合同。选取少量高风险 case 回到真实 DAC 做物理验证，不能把 synthetic PASS 宣称为通用硬件兼容证明。

## 11. 验收原则

1. Reference project 只提供 boundary/capability 候选，不提供 Mica 的最终正确答案。
2. 每个 synthetic case 必须可审计、可重复，并明确 provenance。
3. 优先测试“刚好过 / 差一点不过”，而不是堆大量普通 happy-path。
4. 一个事实的变化应尽可能只改变一个 classification 原因。
5. 不允许通过放宽 exact-only、猜 clock、猜 framing 或 Native 自造 filler 来让 corpus 变绿。
6. Synthetic PASS 不是 real-device qualification；真实设备 evidence 仍独立记录。

## 12. 预期价值

如果这套 corpus 成型，它会同时成为：

- generic UAC parser 回归集；
- exact transport planner 回归集；
- DSD DoP/RAW_DATA qualification 回归集；
- feedback/scheduler/recovery 边界集；
- future quirk/learned-policy 冲突集；
- 新 DAC 兼容修改的防回归门禁。

其目标不是让 Mica“猜更多 DAC”，而是让 Mica在面对复杂 DAC 时**知道自己为什么能播，也知道什么时候必须拒绝猜测**。
