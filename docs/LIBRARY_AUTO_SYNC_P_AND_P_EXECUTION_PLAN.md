
# Mica 曲库自动同步（PixelPlayer + Poweramp）完整执行计划

> 日期：2026-09-06；实施状态更新至 2026-09-09
> 状态：**S0–S5 已实施；DEVICE 与 SAF/FOLDER ordinary scheduler real-auto 已启用并通过对应真机 Gate，进入最终验收/兼容性收尾**
> 目标基线：Mica 当前主工作树；Room schema 审阅时为 v26  
> 架构权威：`docs/adr/0002-library-snapshot-publication.md`  
> 现有扫描事实：`docs/LIBRARY_SCAN.md`  
> 异步共享状态硬规则：`AGENTS.md` / `.cursor/rules/async-shared-state-consistency.mdc`  
> 容量基线：**10,000 首歌曲、每首完整逐字歌词、8 GB 内存 Android 手机**  
> 参考思想来源：PixelPlayer 的 MediaStore 事件/增量同步；Poweramp 的自建曲库权威、SAF Fast Scan、不完整不删。参考项目只用于行为与架构对照，不作为 Mica 的代码权威。

---

## 0. 文档用途

本文不是功能愿望清单，而是 Mica 曲库自动同步的**实施规范与阶段门禁**。

实施者不得在未完成前置 Gate 时提前接 observer、开启真实 AUTO、引入第二个曲库 writer，或绕过 `MusicLibraryBacking` 的 generation / storeRevision / storeSyncMutex 协议。

本文的核心取舍是：

> **发现层吸收 PixelPlayer + Poweramp 的增量思想；发布层继续严格服从 Mica ADR-0002 的单一 snapshot 权威。**

第一版明确**不**追求：

1. Poweramp 式“目录 mtime 相同就跳过整个 subtree”；
2. 有变化时做到 O(changed) 的 Room / browse publication；
3. SAF 任意 DocumentsProvider 的实时、零延迟变化通知；
4. 依靠 MediaStore.Files 扩大系统授权范围；
5. 为 AUTO 新建一套独立数据库 writer 或后台曲库 owner。

本文要求先完成 ADR 与 S0/S1，再接事件；S0/S1 未通过前，任何 observer 都不得调用现有 `launchRescan()` / `launchScanDeviceWide()` / `launchScanLibraryFolder()`。

---

# 1. 当前问题与目标

## 1.1 当前行为

Mica 当前曲库主链：

~~~text
MusicLibrary
  → MusicLibraryBacking
      → LibraryScanOrchestrator
          → AndroidLibraryScanner
              ├─ MediaStoreScanner
              └─ FolderScanner
          → LibraryStore / RoomLibraryStore
              → LibraryRepository
          → LibraryCatalogPublisher
~~~

当前扫描已经具备：

- 完整 snapshot authority；
- `scanGeneration`；
- `storeRevision + storeSyncMutex`；
- 扫描中歌词 batch 的 generation 防护；
- MediaStore / SAF 两种真正独立的数据源；
- `reusableCachedSong()` 的文件级复用；
- size / mtime / externalLyricsSignature 等指纹；
- 10k 歌词批次有界的测试基础。

当前缺少：

- 自动 dirty signal；
- 可靠的 AUTO 调度协议；
- DEVICE generation delta；
- SAF 持续前台补偿；
- source/config 切换时统一 invalidation；
- 不完整 discovery 的负信息保护；
- AUTO 删除后的队列/歌单/当前播放契约；
- checkpoint/retry/outbox 的持久化恢复。

## 1.2 目标体验

正常情况下用户不再需要手动“重新扫描”才能看到：

- 新增歌曲；
- 删除歌曲；
- 标签更新；
- 外挂 LRC / TTML 变化；
- SAF 目录中的文件变化；
- 受影响的本地 MV / 视频封面关系变化。

手动 Full Scan 仍作为：

- 首次建库；
- source 切换确认；
- recovery / reconciliation；
- parser / metadata / artwork maintenance；
- 配置变化后的完整重建；
- MediaStore / provider 异常后的恢复入口。

---

# 2. 第一版能得到什么，不能承诺什么

## 2.1 能得到

| 能力 | 第一版实现 |
|---|---|
| PixelPlayer 式事件合并 | ContentObserver / system signal → scheduler dirtySequence |
| PixelPlayer 式前台补偿 | foreground catch-up；冷却期内事件延后而非丢弃 |
| DEVICE 增量发现 | Android 11+ 优先 version + generation；旧版时间戳 + 周期 inventory |
| Poweramp 式自建曲库权威 | 完整保留 Mica Room snapshot authority |
| Poweramp 式 SAF Fast 思路 | 全树 metadata inventory，只有 changed/new 才 probe |
| 不完整不删 | partition-aware completeness gate |
| 无变化短路 | 不 prepare、不 commit snapshot、不 rebuild browse、不碰 queue |
| 失败重试 | RetryLedger 按对象/版本去重、退避、可恢复 |
| 自动删除安全 | removal reason/evidence + queue reconcile + durable playlist follow-up |

## 2.2 第一版不能承诺

- SAF metadata walk 是 O(N)，10k 目录树是否足够快必须实测；
- 有变化时仍会合成完整 snapshot，并走现有 browse group 重建；
- MediaStore `_ID` 的多 volume 身份假设需设备验证；
- SAF provider 不提供可靠 size/mtime 时，内容原地替换无法靠轻量 fingerprint 立即可靠识别；
- 选择性 queue reconcile 是否完全零可感知卡顿需真机验证。

因此性能表述必须准确：

> **第一版最大的收益是“无变化短路”和“少 probe”；有变化时 publication 成本仍与当前成功扫描同阶。**

---

# 3. 不可破坏的 8 条总原则

1. **单一曲库权威**  
   只有 `MusicLibraryBacking → LibraryScanOrchestrator → LibraryStore` 可以提交完整 library snapshot。

2. **取消不是正确性条件**  
   旧操作必须靠 generation / request token / source identity + activationEpoch / object revision 在每个真实副作用边界失效。

3. **不完整 discovery 不能产生破坏性负结论**  
   不仅禁止成员删除，还禁止把 Unknown 写成“没有”、禁止不完整匹配组重算唯一关系。

4. **AUTO 默认静默且无 maintenance 副作用**  
   不触发教程、不显示手动扫描进度、不推进全局 parser maintenance、不跑 album-art prune / bulk video poster prefetch。

5. **无内容变化不发布完整 snapshot**  
   dirty signal 但最终没有成员/元数据/关联变化时，只允许受保护地更新 checkpoint/retry。

6. **用户显式 mutation 优先于旧 AUTO 结果**  
   用户移除、切源、清库、配置变更等不能被扫描开始时的旧 snapshot 复活。

7. **持久副作用必须可靠恢复**  
   歌单清理等跨 owner 持久副作用不能只靠内存 ChangeSet；必须使用同库事务中的 durable outbox 或等价机制。

8. **AUTO 不能通过播放器整体重建掩盖曲库变化**  
   自动删除/更新必须按当前 queue/session 重算选择性 reconcile；禁止默认 `setQueue(整个曲库)`。

---

# 4. 术语与数据模型

## 4.1 OperationMode

~~~kotlin
enum class LibraryOperationMode {
    FULL,
    AUTO_SYNC,
    TARGETED_REFRESH,
    ARTWORK_REPAIR,
}
~~~

说明：

- `FULL`：首次扫描、用户主动重扫、source 首次绑定、完整 reconciliation。
- `AUTO_SYNC`：事件/前台补偿/SAF verify 引发的轻量同步。
- `TARGETED_REFRESH`：沿用现有标签编辑后单曲刷新路径，不创建第二条 targeted scanner。
- `ARTWORK_REPAIR`：归 FULL 族维护任务；用户不可见，低于 TARGETED，高于普通 AUTO follow-up。

## 4.2 OperationCause

至少：

~~~kotlin
enum class LibraryOperationCause {
    INITIAL_SCAN,
    USER_RESCAN,
    SOURCE_SWITCH,
    MEDIASTORE_AUDIO_DIRTY,
    MEDIASTORE_FILES_DIRTY,
    MEDIA_SCANNER_FINISHED,
    STORAGE_CHANGED,
    FOREGROUND_CATCH_UP,
    SAF_PERIODIC_VERIFY,
    TAG_EDITOR_RETURN,
    ARTWORK_REPAIR,
}
~~~

cause 是诊断/策略输入，不直接等于每个成员变化的 removal reason。

## 4.3 OperationToken

~~~kotlin
data class LibraryOperationToken(
    val libraryGeneration: Long,
    val requestSequence: Long,
    val dirtySequenceAtStart: Long,
    val sourceIdentity: SourceIdentityKey,
    val activationEpoch: Long,
    val configFingerprint: String,
    val catalogRevisionAtStart: Long,
)
~~~

普通 dirty **只增加 dirtySequence，不使当前 AUTO token 失效**。

真正使 token 失效的事件：

- 用户 Full Scan 抢占；
- clear；
- release；
- active source activation/identity 改变；
- pending source transition 被替换/取消；
- 会改变候选语义的扫描配置变化；
- owner 明确 bump library generation。

## 4.4 ObjectObservationStamp

OperationToken 证明“这轮操作仍合法”；ObjectObservationStamp 证明“这次 probe 结果仍属于枚举时看到的同一版对象”。

~~~kotlin
data class ObjectObservationStamp(
    val sourceIdentity: SourceIdentityKey,
    val activationEpoch: Long,
    val stableObjectKey: String,
    val fingerprint: String?,
    val providerGeneration: Long? = null,
)
~~~

可靠 fingerprint 对象必须：

~~~text
enumerate f1
→ probe
→ re-stat / re-query
→ 仍是 f1
→ 才能提交 probe 结果
~~~

若变化：

~~~text
丢弃 probe result
→ RetryLedger
~~~

注意：

- “probe 前后 size/mtime 相同”只是**可信 provider 下的实用 fingerprint 判断**，不是文件系统字节级快照证明；
- provider 可能原地改写但保留时间戳，或 size 不变；
- 只有被 S3/S4 capability profile 证明可靠的 fingerprint 才可用于 cheap reuse；
- 不可靠/UNKNOWN provider 必须走 §19.4 的预算 deep verify；
- 若需要字节级强证明，只能使用内容 hash/等价强校验，但这属于高成本 verify，不作为所有 AUTO 对象默认路径。

歌词、封面、视频派生结果同样遵守。

---

# 5. 生命周期必须拆成三个正交轴

## 5.1 LibraryIntentState

~~~text
UNINITIALIZED
ACTIVE
CLEARED_BY_USER
~~~

含义：

- `UNINITIALIZED`：从未建立 authoritative library；AUTO 不负责建立第一份曲库。
- `ACTIVE`：存在明确的 active source identity + activation；允许按访问状态运行 AUTO。
- `CLEARED_BY_USER`：用户明确清库；AUTO suspended，直到用户主动建立/扫描来源。

## 5.2 AccessState

~~~text
AVAILABLE
TEMP_UNAVAILABLE
PERMISSION_REQUIRED
~~~

权限暂失、SD 卡离线、provider 临时错误都不等于用户主动清库。

当前 `updatePermission(false) → clearLibrary()` 的语义必须在 S0/S1 拆开。

## 5.3 Source identity 与 activation 必须分层

冻结后不再使用含义模糊的单一 `bindingId` 同时承担“长期来源身份”和“本次激活代次”。

~~~kotlin
data class SourceIdentityKey(
    val source: ScanSource,
    val stableIdentity: String,
)

data class SourceActivation(
    val sourceIdentity: SourceIdentityKey,
    val activationEpoch: Long,
)

data class LibrarySourceState(
    val active: SourceActivation?,
    val pendingTransition: SourceActivation?,
)
~~~

语义冻结为：

- `SourceIdentityKey` 是**长期来源身份**：
  - DEVICE：同一设备媒体来源在权限撤销/重新授权、进程重启、重新激活后保持同一 identity；扫描配置不进入 identity。
  - FOLDER：以稳定 tree/document identity 为准；同一 SAF tree 重新授权、切走再切回仍是同一 identity。
- `activationEpoch` 是**本次激活代次**：每次 active source 被重新建立/替换时递增；OperationToken 绑定它。
- `configFingerprint` 独立于 source identity：过滤、最短时长、deep probe 等改变只使 checkpoint/operation 失效，不改变用户 exclusion 的长期 scope。
- tombstone / user exclusion 绑定 `SourceIdentityKey + stableObjectKey`。
- checkpoint 绑定 `SourceIdentityKey + configFingerprint + provider/version state`，不得因重新授权同一来源自动绕过 exclusion。
- Retry/Outbox 必须记录长期 source identity；需要判断“是否仍是同一次激活”的操作另带 activationEpoch。

因此：

~~~text
在 A 排除歌曲
→ 切到 B
→ 再切回 A
→ exclusion 仍有效
~~~

重新授权、应用重启或配置变化同样不得解除该 exclusion。

---

# 6. 切源采用两阶段提交，禁止隐式 fallback

场景：active A → 用户选择 B。

~~~text
1. active 仍为 A
2. 建立 pendingTransition = B
3. invalidate 旧 operation
4. 对 B 执行首次 FULL
5a. B 成功：
      同一 publication transaction 提交 B snapshot + active binding=B
      pending=null
5b. B 失败：
      丢弃 pending B
      active 继续为 A
      A 的 AUTO 可恢复
~~~

禁止：

- B 扫描失败后把 A/B delta 混合；
- UI 提前宣称 active=B，但内存仍是 A snapshot；
- B 不可用时偷偷 fallback 到 A 并当作 B 成功；
- 继续依赖现有 `rescan()` 的 fallback 来决定自动 source。

权限丢失：

~~~text
ACTIVE + DEVICE
→ permission lost
→ AccessState.PERMISSION_REQUIRED
→ 保留 A snapshot
→ AUTO suspend
→ 不变成 CLEARED_BY_USER
~~~

权限恢复后根据 checkpoint 是否仍可信决定 catch-up 或 Full reconcile。

## 6.1 Pending source 的扫描资源必须隔离到激活事务

两阶段切源不仅隔离 song snapshot，也必须隔离**扫描过程中提前产生的歌词/派生资源**。

当前仓库已有 `song_lyrics_pending` staging 思路，但现有表属于旧版 schema，且不能直接表达 tri-state / activation / 多 revision；实施时可以复用“staging → promote”的模式，不得直接复用其旧语义。

冻结规则：

~~~text
active = A
pending = B
→ B 扫描过程中产生的 lyrics / resource batch
→ 只能写 operation-scoped staging
→ active A 的 song_lyrics/resource head 完全不可见这些结果
~~~

staging key 至少包含：

~~~text
operation/request id
SourceIdentityKey
activationEpoch
stableObjectKey/songId
slot/resource kind
resource revision
patch state
~~~

B 成功时，**在提交 B snapshot + active source=B 的同一个最终 Room transaction 中**：

~~~text
promote B staged resources
→ 更新 B resource heads
→ 提交 B snapshot/meta/checkpoint
→ 切 active source=B
→ 删除已 promote staging
~~~

B 失败、取消、被新 source transition 取代时：

~~~text
active A 不变
→ B staging 标记 abandoned / 删除
~~~

进程退出后，启动恢复只允许清理“没有任何已提交 activation 对应”的 abandoned staging；不得误删已激活 snapshot 仍引用的资源。

为了避免同一类问题在普通 FULL/AUTO 中再次出现，**所有扫描产生、且会在最终 publication 前提前持久化的歌词/资源 batch 都应写 staging，而不是直接覆盖 active resource head**。这样既保持歌词批次内存有界，又不会让最终失败的扫描留下半提交资源。

---

# 7. DiscoveryCompleteness 必须 partition-aware

不要只保留一个全局 Boolean。

~~~kotlin
enum class Completeness {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE,
}

data class SourcePartition(
    val sourceIdentity: SourceIdentityKey,
    val partitionKey: String,
)

data class DiscoveryReport(
    val partitions: Map<SourcePartition, Completeness>,
)
~~~

第一版删除策略可以保守：

> 任一相关 partition 非 COMPLETE，则该 coverage 范围不做 deletion。

但模型必须允许未来：

~~~text
internal volume COMPLETE
SD card UNAVAILABLE
~~~

时只冻结 SD 卡 deletion，而不必重写数据结构。

---

# 8. “安全 upsert”必须是 tri-state Patch，不是缺字段 Song

## 8.1 三态观察

~~~kotlin
sealed interface Observed<out T> {
    data object Unknown : Observed<Nothing>
    data class Present<T>(val value: T) : Observed<T>
    data object AbsentConfirmed : Observed<Nothing>
}
~~~

含义：

| 状态 | 发布行为 |
|---|---|
| Unknown | 保持当前值 |
| Present(new) | 写入新值 |
| AbsentConfirmed | 确认清空 |

## 8.2 负信息规则

“没看到”只有在负责该字段/关系的 discovery channel 完整时，才能升级成 `AbsentConfirmed`。

例如：

~~~text
音频 query COMPLETE
lyrics query PARTIAL
→ song metadata 可 Present
→ external lyrics 必须 Unknown
→ 不能清掉旧歌词
~~~

MV：

~~~text
某 folder/baseName 匹配组 candidates 不完整
→ relation = Unknown
→ 保留旧关系
→ 禁止把“只看到一个候选”当成唯一
~~~

视频封面同理。

## 8.3 Lyrics slot patch + staging

当前 `applyLyricsBatch()` 会先删该 song 全部槽，再插入本批结果；S1 必须改成 slot-level patch，并且扫描中批次先进入 operation-scoped staging，不直接覆盖 active resource。

示例：

~~~text
EMBEDDED       Present(newDoc)
EXTERNAL_LRC   Unknown
EXTERNAL_TTML  AbsentConfirmed
~~~

只允许：

- 更新 embedded；
- 保留旧 LRC；
- 明确删除 TTML。

持久化语义：

~~~text
Present(newDoc)
→ 写入新的 versioned resource 到 staging
→ 最终 publication 时把对应 slot head 指向新 revision

Unknown
→ staging 不产生 destructive patch
→ 最终 publication 保留 current slot head

AbsentConfirmed
→ staging 记录“确认清空”patch
→ 只有最终 publication 成功时才移除 current slot head
~~~

因此任何 FULL/AUTO/TARGETED 扫描即使后续失败，也不能仅凭已完成的歌词 batch 改变当前 active snapshot 的歌词可见版本。

这条同时约束 FULL、AUTO、TARGETED 和扫描中提前落盘的 lyrics batch。

---

# 9. Membership removal 必须带 reason + evidence

Scanner/Orchestrator 不再只传 `removedIds`。

建议：

~~~kotlin
enum class MembershipRemovalReason {
    CONFIRMED_MISSING,
    FILTERED_OUT,
    TRASHED,
    SOURCE_REPLACED,
    USER_EXCLUDED,
    UNAVAILABLE,
}

data class MembershipChange(
    val stableObjectKey: String,
    val songId: String?,
    val reason: MembershipRemovalReason,
    val evidenceRevision: String,
    val sourceIdentity: SourceIdentityKey,
)
~~~

## 9.1 语义表

| reason | 从当前曲库隐藏/移除 | 永久清理歌单 | 自动恢复 |
|---|---:|---:|---:|
| CONFIRMED_MISSING | 是 | 是 | 新对象再次出现时按新身份处理 |
| FILTERED_OUT | 是 | 否 | 过滤条件恢复后自动回来 |
| TRASHED | 是 | 否 | 从回收站恢复后自动回来 |
| SOURCE_REPLACED | 是 | 否 | 切回来源后重新解析 |
| USER_EXCLUDED | 是 | **是，保持现有手动删除语义** | 只有用户明确恢复 |
| UNAVAILABLE | **否** | 否 | 来源恢复后核对 |

`PENDING` 或无法判断的对象归“不作负结论”，不得冒充 CONFIRMED_MISSING。

## 9.2 CONFIRMED_MISSING 的最低证据

必须同时满足：

- 相关 partition discovery COMPLETE；
- active source identity / activation 未变；
- access AVAILABLE；
- Presence coverage 对负责该 object 的相关 partition COMPLETE；
- Eligibility reason 已能区分 ELIGIBLE / FILTERED_OUT / TRASHED / PENDING 等状态；
- MediaStore version/coverage 没有在本轮矛盾；
- 不是 trashed/pending；
- 不是单纯 filtered-out；
- 不存在 provider/query failure。

## 9.3 AUTO 必须有 mass-deletion guard；“成功空结果”不等于安全空库

手动 FULL 中“query 成功且 0 首”可以作为用户显式重建的结果；AUTO 不能继承这个默认。

风险场景包括：

~~~text
MEDIA_MOUNTED / 权限刚恢复 / MediaScanner 尚未稳定
→ query 本身成功
→ Presence inventory 暂时为 0
→ 若直接视为 COMPLETE + ABSENT
→ 整库被错误 CONFIRMED_MISSING
~~~

因此 AUTO deletion 在 §9.2 之外还必须经过 **MassDeletionGuard**。

冻结规则：

1. **active library 之前非空，而 AUTO 仅凭一次全局/partition Presence inventory 突然得到 0：**
   - 该“inventory collapse”本身不得产生整库 `CONFIRMED_MISSING`；
   - 不立即 publish empty snapshot；
   - 进入 mass-deletion quarantine，并安排独立 verify；
   - 保留现有 authoritative snapshot，直到 destructive evidence 足够。

2. **对象级缺失证据可以正常删到 0 首。**  
   如果某个既有 object 有明确 dirty/删除线索，并经过独立、source/version 稳定的 targeted presence recheck 确认为 absent，则该 object 可以 `CONFIRMED_MISSING`；即使它恰好是曲库最后一首，也允许 AUTO 把曲库变成合法空库。

3. **AUTO 单轮出现异常大的 removal batch：**
   - 若 removals 主要来自“一次 inventory 没看到”，进入 quarantine；
   - 若每个 object 都拥有独立、可复验的 missing evidence，允许按 evidence 提交，但仍必须经过 batch guard，避免 provider/systemic failure 被误当成大量独立删除；
   - 具体比例阈值与“独立 evidence 数量/覆盖率”在 S1 Gate 前冻结并测试。

4. 以下信号**只能触发重新核对，不能提高 deletion 可信度**：
   - `MEDIA_SCANNER_FINISHED`；
   - `MEDIA_MOUNTED`；
   - permission restored；
   - foreground catch-up。

5. quarantine 不能永久静默：
   - 后续独立 verify 成功 → 提交真实 removals；
   - 长期无法确认 → 进入 `NEEDS_FULL_RECONCILE`；
   - Settings/曲库状态提供非 snackbar 的“检测到大规模曲库变化，建议重新扫描”提示与用户 FULL 入口。

MassDeletionGuard 的产品语义因此是：

> **阻止“单次全空/大幅 inventory collapse”直接清库，而不是禁止最后一首歌被可靠自动删除。**

必须有确定性测试：

~~~text
previous=10k
→ AUTO query success + Presence=0
→ Room/memory 仍保持 10k
→ 无 playlist cleanup / queue removal
→ source 进入 reconcile-needed
~~~

以及：

~~~text
previous=10k
→ AUTO 暂时只看到极少数对象
→ 第一轮不得大规模删除
→ 后续 inventory 恢复完整
→ library 从未经历中间“几乎空库”publication
~~~

---

# 10. 用户主动移除必须有持久 tombstone

当前“无法删除文件 → 已从曲库移除”的行为在 AUTO 上线后会被重新扫描复活，因此必须新增持久 exclusion。

建议表：

~~~text
library_user_exclusions
  sourceIdentityKey
  stableObjectKey
  createdAt
  reason
  revision
~~~

tombstone 绑定 **长期 source identity + stable object identity**，不是 activationEpoch，也不是内容 fingerprint。

规则：

- 同一个 MediaStore object id / SAF document identity，rename/move 后仍 excluded；
- 同一 identity 内容 f1→f2 仍 excluded；
- 新 identity 即使内容 hash 一样，也视为新对象；
- Full Scan 和 AUTO 都尊重 exclusion；
- fingerprint 变化绝不能自动解除 exclusion。

用户恢复入口第一版至少提供：

> 恢复已从曲库移除的歌曲

可以先是清除全部 user exclusions，不要求第一版提供复杂逐项管理，但**恢复动作不能只删 tombstone**。

冻结语义：

~~~text
restore exclusion
→ 同一受保护事务：
     bump exclusion revision / mark restored
     supersede 旧 PLAYLIST_REMOVE_USER_EXCLUDED outbox
→ 提交成功后
→ 调度该 SourceIdentityKey 的 rediscovery
~~~

rediscovery 规则：

- 如果能从 tombstone/stableObjectKey 恢复到可 targeted lookup 的对象身份，则优先 TARGETED_REFRESH / targeted presence+probe；
- 若 provider/MediaStore 无法按旧 stable identity 可靠定位，则调度该 source 的 reconciliation/FULL；
- 不能依赖 DEVICE generation delta 自己再次吐出该对象，因为 exclusion 期间 checkpoint 可能已经越过它。

在 `CLEARED_BY_USER` 状态下：

- “恢复已移除歌曲”**只解除 exclusion，不自动重建曲库**；
- UI 提示“已恢复排除记录；重新扫描曲库后生效”；
- 用户显式 Full Scan / 重新建立 active source 后，这些对象才重新进入 authoritative library。

必须测试：

~~~text
A 被 USER_EXCLUDED
→ DEVICE checkpoint 继续推进
→ 用户 restore A
→ 即使 A 的 MediaStore generation 没变化
→ targeted/reconcile 仍能重新发现 A
~~~

以及：

~~~text
CLEARED_BY_USER
→ restore exclusions
→ library 仍保持 cleared
→ 不因恢复操作自动建库
~~~

tombstone 必须和曲库 membership mutation 在同一受保护事务内写入，禁止“先内存隐藏，稍后异步写 tombstone”。

---

# 11. Checkpoint / Retry / Outbox 都属于受保护局部写

它们不是完整 snapshot，但必须列入 ADR-0002 允许的局部持久化。

共同约束：

- 不自行 bump library generation；
- 必须经过 owner 的统一 store seam；
- 写入前后复验 token/revision；
- clear 必须在同一 Room transaction 清理对应 source state；
- release 后旧任务不可写回；
- 不能成为第二个 authority。

建议新增逻辑表（最终命名实施时冻结）：

~~~text
library_sync_state
library_retry_items
library_followup_outbox
library_user_exclusions
~~~

当前 Room schema 审阅基线为 v26；实施时按实际最新 schema 顺延，本文不冻结具体 migration number。

---

# 12. Checkpoint 协议

## 12.1 DEVICE Android 11+

按实际 volume 保存：

~~~text
volumeName
MediaStore version
generation
configFingerprint
sourceIdentityKey
lastSuccessfulAutoSyncAtMs
~~~

核心规则：

- 保存扫描覆盖上界，不保存“扫描结束时间”作为唯一事实；
- version 改变 → checkpoint 作废；
- volume 集合变化 → 对受影响 source/partition 作废；
- permission / source / config 改变 → checkpoint 作废；
- 本轮发生 object probe failure **可以**推进 generation，但前提是：**覆盖该失败对象的 checkpoint 推进，与该对象 RetryLedger 的建立/更新必须在同一个 Room transaction 中提交**；
- 同一事务中如果 retry 写入失败，则 checkpoint 不得推进；
- 无变化 AUTO 也必须遵守同一规则：checkpoint / retry / discovery backoff 是一个不可拆分的持久化提交单元；
- generation 本身不承担 deletion，删除仍靠完整存在性 inventory。

## 12.2 Android 10 及以下

时间戳只是性能提示：

~~~text
DATE_ADDED / DATE_MODIFIED overlap
+
周期性 full Presence + Eligibility inventory
~~~

不能把“开始时间减 2 秒”当正确性证明。

## 12.3 lastFullScanAt 与 lastAutoSyncAt 分离

AUTO 不应篡改用户“上次完整扫描”的语义。

建议：

- 现有 `lastScanAtMs` 在迁移后语义冻结为 Full/authoritative rebuild 时间；
- AUTO checkpoint 记录 `lastSuccessfulAutoSyncAtMs`；
- 无变化 AUTO 和有变化 AUTO 都不伪装成用户 Full Scan。

---

# 13. RetryLedger 协议

Retry 至少分两类：

~~~text
OBJECT_PROBE
DISCOVERY_PARTITION
~~~

对象记录至少：

~~~text
sourceIdentityKey
stableObjectKey
observedFingerprint
failureKind
attemptCount
nextRetryAt
~~~

规则：

### existing song probe 失败

~~~text
保留旧 Song
+ retry
~~~

### new song 首次 probe 失败

~~~text
暂不加入 authoritative catalog
+ retry
~~~

### 对象消失

删除 retry。

### fingerprint 改变

旧 revision retry 作废，新 revision 重新排。

### 调度

即使没有新的 dirty event，前台 scheduler 也要在最早 `nextRetryAt` 到期时醒来。

### 上界与生命周期

- 按 object key + fingerprint 去重；
- 分页/批量读取；
- 不让 10k 条 retry 变成 10k 个常驻大对象；
- provider 长期失败有 partition-level backoff，不能每轮高频打 provider；
- **未成功、未被新 fingerprint supersede、且未被完整 discovery 确认消失的 retry 不得仅因时间到期而删除**；
- 长期失败项可以降低频率、转入 `NEEDS_FULL_RECONCILE` / 等价状态，或等待用户 Full Scan，但不能静默遗忘；
- 只有“成功处理”“对象被完整 coverage 确认不存在”“新 revision 明确替代旧 revision”“用户/clear 明确重置对应 source state”才能终止 retry；
- retry 清理与 checkpoint 变更必须保持因果一致，不能留下“checkpoint 已越过、retry 已消失”的永久漏扫状态。

---

# 14. LibraryChangeSet 与 durable Outbox 必须分工

## 14.1 LibraryChangeSet：进程内事实通知

完整 publication 成功后可以产生：

~~~kotlin
data class LibraryChangeSet(
    val libraryRevision: Long,
    val cause: LibraryOperationCause,
    val addedIds: Set<String>,
    val updatedIds: Set<String>,
    val membershipChanges: List<MembershipChange>,
)
~~~

用途：

- UI/诊断；
- 当前进程 queue reconcile；
- 通知 PlaylistStore 尝试消费 durable follow-up。

它**不是可靠投递日志**。

## 14.2 Durable outbox：跨 owner 的持久副作用

因为曲库和歌单实际同属 `MicaDatabase`，曲库提交事务可以同时：

~~~text
commit snapshot / membership
+
commit checkpoint
+
insert follow-up outbox
~~~

outbox 记录至少绑定：

~~~text
eventId
libraryRevision
action
sourceIdentityKey
activationEpoch（仅 activation-scoped action 需要）
stableObjectKey
objectRevision/evidence
removalReason
~~~

第一版重点 action：

~~~text
PLAYLIST_REMOVE_CONFIRMED_MISSING
PLAYLIST_REMOVE_USER_EXCLUDED
RESOURCE_GC_CANDIDATE
~~~

## 14.3 Outbox 消费必须幂等、原子且晚到安全

仅在 Playlist mutation mutex 下“先查、再删、再 ack”仍有竞态，因为曲库 publication 可以在复核后、真正删除前重新加入对象。

冻结后的锁顺序：

~~~text
Publication gate
  → Playlist mutation mutex
      → MicaDatabase transaction
~~~

任何同时需要曲库有效性判断和歌单持久化的 outbox consumer 都必须遵守这个顺序；其他 playlist 普通 mutation 不得反向持有 playlist mutex 后再请求 Publication gate。

消费协议：

~~~text
acquire Publication gate
→ acquire Playlist mutation mutex
→ BEGIN Room transaction
    read pending event
    re-read current durable library membership / exclusion / removal evidence
    verify event is still the latest valid fact
    mutate playlist_songs
    ack / obsolete event
  COMMIT
→ publish PlaylistStore memory state while playlist revision is still current
→ release locks
~~~

因此“有效性复核 → playlist 删除 → ack”在数据库层是一个事务，且 publication gate 保证其间不会发生新的 library re-add publication。

如果对象已经重新加入、exclusion 已恢复、removal evidence 被新 revision supersede：

~~~text
旧 cleanup 不执行
→ transaction 内 mark obsolete/ack
~~~

内存 PlaylistStore 发布必须带自己的 mutation/revision 校验；不得在事务完成后无条件用旧内存快照覆盖后来 playlist mutation。

### 14.3.1 Outbox action 的失效条件必须按动作定义

不能因为 clear、source switch 或 activationEpoch 改变就一律删除/作废所有 outbox。

冻结语义：

- `PLAYLIST_REMOVE_USER_EXCLUDED`
  - 绑定长期 `SourceIdentityKey + stableObjectKey + exclusionRevision`；
  - **切源、重新授权、clear 都不会自动使它失效**；
  - 只有用户明确恢复该 exclusion，或更新 revision 明确 supersede，才能 obsolete。
- `PLAYLIST_REMOVE_CONFIRMED_MISSING`
  - 绑定长期 source identity + missing evidence revision；
  - source switch 本身不使已经确认的 missing 事实失效；
  - 若同一 object 后续重新出现/恢复并产生更高 revision，则旧 cleanup obsolete。
- `RESOURCE_GC_CANDIDATE`
  - 不因 source switch/clear 自动丢弃；
  - 是否执行取决于当前 resource head/reference 与 playback lease。
- 纯 activation-scoped、尚未承诺外部持久副作用的内部事件，才可以在 activation 失效时直接 obsolete。

进程在 Room commit 后立即退出也不会丢 follow-up；启动后继续消费。

队列不使用 durable outbox，避免把旧位置命令重放到新的 playback session。

### 14.3.2 Playlist mutation 必须迁移为可挂起执行；主线程禁止阻塞等锁

当前 `PlaylistStore` 仍有 `runBlocking { mutationMutex.withLock { ... } }` 和 `runBlocking(Dispatchers.IO)` 写库入口。S1 引入 Publication gate → Playlist mutex → Room transaction 后，不能继续把这套同步 API 原样套进去。

冻结规则：

- outbox consumer 与其复用的 playlist repository mutation 必须提供 `suspend` 入口；
- 主线程不得 `runBlocking` 等待 Publication gate、Playlist mutex、Resource lease mutex 或 Room transaction；
- UI 的同步按钮/动作通过 owner scope 启动 suspend mutation，并按既有 UI 需要返回成功/失败状态；
- 持有 Publication gate / Playlist mutex 时禁止切回 Main dispatcher 等待 UI；
- Room transaction 完成后记录 durable playlist revision，释放数据库 transaction/必要锁；
- Compose/内存 playlists publication 可以在 Main dispatcher 上按 `expectedPlaylistRevision` 发布；如果 revision 已有更新，则丢弃旧内存 publication，不能覆盖后来 mutation；
- 旧的同步 `mutate()/writeStorage()` 若暂时保留给不相关调用，也不得从 outbox/publication path 调用。

S1 必须包含线程/取消测试：

~~~text
Main thread 发起普通 playlist mutation
→ 后台 outbox consumer 正持有/等待 playlist mutex
→ Main 不 runBlocking
→ 无死锁/ANR
→ 两个 mutation 按 revision 顺序收敛
~~~

以及：

~~~text
outbox transaction commit
→ memory publish 前又有新 playlist mutation
→ old expectedPlaylistRevision 不再覆盖新内存状态
~~~

---

# 15. Scanner 字段 ownership matrix

最终 rebase 不允许简单“新 Song 覆盖旧 Song”。

| 字段域 | Owner | rebase 规则 |
|---|---|---|
| 文件 identity / tag metadata / source URI | Scanner | ObjectObservationStamp 有效时采用 |
| external lyrics / video relation | Scanner + completeness | Unknown 保留；Present 更新；AbsentConfirmed 清空 |
| playCount / listenSeconds / lastPlayed | Runtime stats owner | 永远取 publication 时 current 最新值 |
| dateAddedMs | Library membership owner | **首次入库时间**；existing object 的 AUTO/TARGETED reprobe 必须保留旧值，不得因 tag/mtime 更新重置“添加时间”排序 |
| user exclusion/tombstone | User/library membership owner | Scanner 永远不能覆盖 |
| loudnessAnalysis | Version-bound derived | source fingerprint 仍匹配才继承；AUTO 不得用空值覆盖正在进行/刚完成的响度分析 |
| replayGain tags | Scanner metadata + playback-instance freeze | catalog 可在有效 probe 后更新；当前 playback instance 不热替换已捕获的 ReplayGain |
| AppliedReplayGain | Playback session owner | 当前 playback instance 冻结；只在下一次重新建立该 item 的 playback instance 时采用新 ReplayGain/loudness |
| coverColor | Version-bound derived | artwork/source revision 仍匹配才继承 |
| embedded lyrics probe | Version-bound derived | 文件 revision 仍匹配才继承 |
| presentation order / sort | Catalog/user settings | 按当前设置重新 prepare |
| playback-session source version | Playback session | 当前 item 不被 AUTO hot-swap |

---

# 16. 最终 publication 必须是 optimistic rebase + 线性化提交协议

## 16.1 S0 是真实 publication seam 重构，不是只搭“骨架”

当前 `scanExecutionMutex` 长时间覆盖整轮扫描，不适合高频 AUTO，也无法完整串行用户 membership mutation。

S0 必须拆职责：

~~~text
LibrarySyncScheduler
→ 保证 library operations 的执行次序 / 优先级 / pending work

Publication gate
→ 串行 authority replacement、最终 rebase、durable commit、memory publication
~~~

Discovery / metadata walk / probe 在 publication gate 外执行。

### 16.1.1 gate 外 preparation 必须严格无副作用

当前 `LibraryCatalogPublisher.prepareLibrarySongs()` **并非纯计算**：CUSTOM sort 时会调用 `LibraryBrowseSettings.setCustomSongOrderIds()` 写 preferences。

S0 必须拆成：

~~~text
pure prepare
→ PreparedLibrarySongs + ProposedPresentationSideEffects
~~~

pure prepare 允许：

- 读取输入 snapshot；
- 读取捕获的 settings/revision；
- 计算 play stats 合并、sort、browse/index；
- 生成“建议持久化的 custom order”等 proposed effect。

pure prepare **禁止**：

- 写 SharedPreferences；
- 写 Room；
- 写 cache/file；
- 发布 Compose state；
- bump revision；
- 改歌词/封面/视频资源 head。

任何 derived presentation 持久化只能在最终 publication 成功以后、且仍持有/复验对应 presentation revision 时执行。旧 prepared 被丢弃时必须做到 **零持久副作用**。

CUSTOM order 特别规则：

- 用户显式拖动/排序是 user-owned presentation mutation，拥有独立 `presentationRevision`；
- scan preparation 只能计算“缺失 ID 如何并入当前 order”的 proposed order；
- final publication 若发现 `presentationRevision` 已变化，必须丢弃 prepared 并重算；
- 成功 publication 后才允许 revision-guarded 持久化 proposed order；
- 该 derived preference 写失败不得用旧 prepared 覆盖用户新顺序；下次可从已提交 Room order/current presentation 重建并重试。

确定性交错测试：

~~~text
old prepare 读 custom order A
→ 卡在 pure preparation
→ 用户拖动得到 order B 并持久化
→ old prepare 完成
→ final validation 发现 presentationRevision 改变
→ 丢弃 old prepared
→ preferences / memory / Room 最终均保持 B（或基于 B 重算后的新 order）
~~~

### 16.1.2 现有 scanExecutionMutex 调用者必须逐项迁移

移走长时间扫描锁之前，S0 必须列清并迁移现有调用者，禁止留下半套锁模型。

| 当前职责/调用者 | S0 后归属 |
|---|---|
| scan 串行执行 | LibrarySyncScheduler |
| cache hydrate / clear / release / source authority replacement | Publication gate + generation |
| `withCurrentCatalogPublication` 类 catalog publication | Publication gate |
| `storeWriteIfCurrentCatalog/Generation` 的持久副作用 | 统一锁顺序：Publication gate（需要 catalog authority 时）→ store revision/Room transaction |
| 用户 sort / custom-order mutation | presentationRevision + Publication gate；prepare 外写入不得旁路 |
| 扫描中 lyrics batch | operation-scoped staging；不得直接写 active resource head |
| browse/fast-scroll derived publication | pure prepare + final publication |
| album-art prune/cache maintenance | Scheduler 中的 maintenance work；对 destructive cache side effect 使用 expected catalog/resource revision，不能依赖“scan mutex 恰好空闲” |
| artwork repair scan | Scheduler 的 ARTWORK_REPAIR request，不再独立 cancel-all |
| catalog-dependent async cover/loudness 等局部 mutation | 明确 owner revision；需要与 snapshot 竞争时进入 Publication gate 或使用能证明等价的 current-catalog seam |

### 16.1.3 LoudnessScanManager 与 AUTO 的冲突规则

`LoudnessScanManager` 是真实的第二条 catalog mutation 路径，AUTO 上线前必须把它纳入 version/rebase 规则；这不是新增阶段，而是 §15 ownership 的落地。**本节不扩大 S0：S0 只需提供统一 publication/object-revision seam；AUTO-specific 的同文件 IO 仲裁与播放共存门槛在 S3/S4 实施和验收。**

冻结规则：

- loudness decode 得到的结果绑定 `stableObjectKey + source fingerprint/resource revision`；
- `applyLoudnessAnalysis` 必须进入 Publication gate，或使用等价的 object-revision compare-and-set seam；
- AUTO final rebase 对 fingerprint 仍相同的歌曲必须保留 publication 时最新 `loudnessAnalysis`；
- AUTO 不得用 scanner 默认空 `LoudnessAnalysis` 覆盖正在运行或刚提交的用户响度扫描结果；
- 若 AUTO 发现对象 fingerprint 已变化，旧 loudness 只能按 version-bound 规则失效，不能错误继承；
- LoudnessScan 与 AUTO probe 必须共享“同一物理对象重 IO”协调：同一文件正在做 loudness decode 时，AUTO 不应同时再开重型 metadata/decode probe；AUTO 对该 object 延后或进入 RetryLedger。

必须有交错测试：

~~~text
loudness scan 对 A 计算中
→ AUTO 对其他歌曲 publish
→ loudness A 完成
→ 最终 catalog 保留 A 最新结果
~~~

以及：

~~~text
AUTO prepare 读到 A.loudness = empty
→ loudness scan 提交 A=l1
→ AUTO final rebase
→ A 仍为 l1，不被 empty 覆盖
~~~

S0 的手动 Full Scan、cache hydrate、sort、clear、artwork maintenance 回归必须在这次锁迁移后仍然通过，不能等 S1/AUTO 才发现基础扫描被破坏。

## 16.2 Publication 流程与 durable linearization point

~~~text
A. operation 扫描完成，得到 ScanDelta / patches
B. 短暂进入 Publication gate
C. 读取 current catalog + source activation + tombstone/membership/presentation revision
D. rebase delta 到 current catalog
E. 捕获 base revisions
F. 离开 gate
G. 执行严格 pure 的 prepareLibrarySongs / browse preparation
H. 再进入 Publication gate
I. 最终复验：
     library generation
     activationEpoch / source identity
     config fingerprint
     catalog/membership revision
     tombstone revision
     presentation revision
J. 若任一变化：
     离开 gate
     丢弃 prepared（必须零副作用）
     bounded rebase retry
K. 若仍 current：
     **继续持有 Publication gate，不再释放**
L. BEGIN 单一 Room transaction
     promote 本 operation staged resources
     apply snapshot / membership / meta
     apply source activation transition（若有）
     apply checkpoint + RetryLedger（不可拆）
     insert/supersede outbox
   COMMIT
M. Room COMMIT 是本次 publication 的 durable linearization point
N. 在仍持有 Publication gate 的情况下：
     memory adopt prepared snapshot
     adopt active source/lifecycle state
     bump catalog/library change revision
     publish transient LibraryChangeSet
O. 执行仍需同步的、revision-guarded 轻量 derived presentation effect
P. release Publication gate
~~~

### 16.2.1 最终 commit 阶段禁止业务 invalidation，也必须显式处理协程取消

一旦步骤 I 最终复验通过并进入 K：

> clear / source switch / user Full / generation bump 必须等待 Publication gate；不能在 Room commit 与 memory adopt 之间使当前 token 失效。

同时，**父 Job cancel / timeout / release() 取消 scan scope 也不能把已经开始提交的 publication 切成“Room 已提交、memory 未 adopt”的半状态。**

冻结取消策略：

~~~text
discovery / probe / pure prepare
→ 正常可取消

final validation 之前
→ 正常可取消

final validation 通过后
→ 进入一个严格有界的 commit critical section
→ NonCancellable（或语义等价的 cancellation shield）
→ Publication gate
→ Room transaction
→ memory adopt / revision publish
→ release gate
→ 离开 shield
→ 重新观察 cancellation
~~~

要求：

- shield **只覆盖最终提交区间**，绝不包整个扫描；
- serialization、parser、provider/file IO、browse 重计算必须已经在 shield 之外完成；
- final Room transaction 只做 prepared rows/resource refs/head/checkpoint/retry/outbox 的持久化，不在里面重新扫描/解析；
- `release()` 可以先标记 released/cancel owner，但若已有 publication 进入 shield，则等待该短提交区间完成，再清理/关闭 owner；
- commit critical section 必须有耗时指标和上界 Gate，不能用 NonCancellable 掩盖长事务。

因此不再采用：

~~~text
Room 已成功
→ 再发现 token 失效/Job cancelled
→ 只拒绝 memory adopt
~~~

这种半提交模型。

如果 invalidation/cancellation 在 H/I **之前**到达，旧 operation 在最终复验时被拒绝，不能写 Room。

如果 invalidation/cancellation 在 K **之后**到达，它必须排在本次完整 publication 之后：

~~~text
old publication: Room commit → memory adopt
→ release gate / leave commit shield
→ cancellation/new invalidation 生效
~~~

必须增加真实取消测试，不只测 generation：

~~~text
operation 进入 final commit shield
→ parent Job.cancel()
→ Room commit 完成
→ memory adopt 完成
→ 再响应 cancellation
→ Room/memory/restart snapshot 同版本
~~~

以及：

~~~text
Job.cancel() 发生在 final validation 前
→ 不进入 Room commit
→ previous snapshot 保持
~~~

这样 DB 与内存在每个可观察线性化点属于同一版本。

### 16.2.2 进程死亡恢复

Room commit 之后、memory adopt 之前如果发生**进程死亡**，Room 已提交版本是重启后的事实来源；cache hydrate 必须恢复同一个 snapshot + active source/lifecycle state。

memory adopt 阶段必须设计为：

- 无 IO；
- 不执行可失败的外部持久化；
- 仅做可控内存状态替换/revision 发布。

若同进程内出现意外 memory-adopt 异常，不得继续以旧内存 snapshot 提供服务；应将 library 标为需要 rehydrate/recovery，在再次操作前从已提交 Room 恢复。

### 16.2.3 S0 必测线性化交错

必须分别证明：

1. **invalidate-before-final-gate**  
   旧 operation 在最终 gate 前失效 → Room/active binding/memory 均无旧提交。

2. **invalidate-after-final-validation**  
   旧 operation 已通过最终验证并持有 gate → source switch/clear 在另一协程请求 → 新操作必须等待旧 operation 完成 Room + memory publication，然后再执行。

3. **crash-after-Room-before-memory**  
   模拟 Room 已提交但 memory 尚未 adopt → 进程重建/cache hydrate 后，snapshot、active source、checkpoint/retry/outbox 与 Room committed version 完全一致。

测试通过条件必须检查 **Room、内存、active source identity/activation、重启恢复结果**，不能只断言“旧内存没有 adopt”。

不能把扫描开始时 snapshot 当 merge 基线。

## 16.3 用户 membership mutation

`removeSongFromLibrary()` 等必须进入同一 publication gate：

~~~text
publication gate
→ Room 写 tombstone + membership
→ Room 成功
→ 内存 publish
~~~

不能再依赖：

~~~text
先改 scannedSongs
→ persistSongsAsync()
~~~

来保护显式用户删除。

---

# 17. Scheduler：替换现有 cancel-all，不叠加第二套调度器

当前所有 `launch*` 都是 `scanJob.cancel()` 后新开 job；S0 必须让 `LibrarySyncScheduler` 替换这套入口语义。

## 17.1 优先级

推荐：

~~~text
USER FULL
  >
TARGETED_REFRESH（合并 ID）
  >
ARTWORK_REPAIR
  >
AUTO follow-up / retry / periodic verify
~~~

唯一允许主动 bump generation 并取消当前 AUTO 的是：

> **用户 Full Scan / source transition 等明确 authority replacement。**

TARGETED / ARTWORK 不取消正在运行的 AUTO；排队等当前 pass 完成。

## 17.2 AUTO dirty

收到普通 dirty：

~~~text
dirtySequence++
pendingDirty = true
~~~

不得 invalidate 当前 AUTO token。

AUTO pass 开始记录：

~~~text
dirtySequenceAtStart
~~~

结束：

~~~text
dirtySequence > dirtySequenceAtStart
→ 立即补跑
~~~

这个 follow-up 不等待普通 cooldown。

## 17.3 Cooldown

cooldown 中的新 dirty：

~~~text
pendingDirty = true
schedule wake at cooldown end
~~~

不是 ignore。

## 17.4 Debounce 防饥饿

需要同时有：

- trailing debounce；
- max debounce deadline。

持续不断的通知不能无限推迟第一次 AUTO。

具体数值由 S2 shadow 数据决定，S0 只冻结机制。

## 17.5 连续 AUTO budget

不能：

~~~text
AUTO → dirty → AUTO → dirty → AUTO ...
~~~

永久饿死 TARGETED/ARTWORK。

达到连续 pass budget 后：

~~~text
让出给已排队 TARGETED / ARTWORK
→ 之后继续处理 dirty
~~~

具体 budget 由测试调整。

## 17.6 Pending targeted

必须：

~~~text
Set<songId>
~~~

合并，不允许单个 `pendingManual` 让后来的 ID 覆盖前面的。

## 17.7 后台与进程恢复

不要求持久化每个 noisy dirty signal。

恢复正确性依赖：

- checkpoint；
- RetryLedger；
- outbox；
- source lifecycle；
- tombstone；
- foreground catch-up。

进入后台可停止 foreground-only observer/periodic verify；回前台立即判断 catch-up。

---

# 18. DEVICE Auto Delta

## 18.1 事件只是加速信号

监听候选：

~~~text
MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
MediaStore.Files.getContentUri(...)
MEDIA_SCANNER_FINISHED
MEDIA_MOUNTED / storage events
foreground catch-up
~~~

MediaStore.Files 不扩大权限，不能据此宣称所有 LRC/TTML/APE/DSD 均可见。

## 18.2 Android 11+ generation delta

优先 projection：

- volume name；
- generation modified/added（以实际可用列/API为准）；
- 当前 TrackDraft 所需轻字段。

按 volume checkpoint 选择：

~~~text
last generation < row generation <= passUpperBound
~~~

## 18.3 Multi-volume identity Gate

第一版不顺手改变 `ms_<id>` 身份。

S3 在开启真实 AUTO 前必须验证：

- aggregate external collection 下 ID 是否满足现有稳定身份假设；
- 多 volume 时是否存在冲突；
- volume move / reindex 对 ID 的实际行为。

发现冲突 → 单独开启 identity migration 设计，不混入 AUTO delta。

## 18.4 DEVICE discovery channels

至少拆：

1. Audio candidate channel；
2. Files fallback（APE/DSD）；
3. external lyrics sidecar inventory；
4. Presence + Eligibility inventory；
5. volume/version coverage state。

当前 `loadExtendedAudioFileDrafts().getOrDefault(emptyList())` 必须先删除“失败伪装成空集”的语义。

任何 channel 不完整：

> 允许其证明的 Present 更新；禁止依赖该 channel 的 AbsentConfirmed / deletion。

## 18.5 DEVICE inventory 必须拆成“存在性”与“入库资格”

不能用经过 min-duration / excluded-directory / music-filter 处理后的“可入库候选集合”单独证明物理对象已经消失，也不能只 SELECT `_ID` 后做裸差集。

第一版必须逻辑上拆成两层：

### A. Presence inventory：对象是否仍存在/可观察

目标是回答：

> “这个长期 stable object 在当前 source coverage 下还能不能被看到？”

Presence inventory **不应用 Mica 的可见曲库过滤规则**，而是尽可能保留用于判断状态的事实：

- stable object id / volume；
- path/relative path 或等价 identity；
- extension/mime；
- trashed/pending 状态；
- size/modified 等轻量字段；
- source access/volume coverage。

APE/DSD 等必须包含 Files fallback 的存在性观察；相关 Files partition 查询不完整时，不得确认 missing。

### B. Eligibility inventory：对象是否应进入当前曲库

在 Presence 事实之上应用：

- Audio music/mime 规则；
- min duration；
- excluded directories；
- supported format；
- dedupe；
- 其他 LibraryScanSettings / ScanOptions 规则。

两层合成 removal reason：

~~~text
Presence = PRESENT + Eligibility = ELIGIBLE
→ 保持/加入曲库

Presence = PRESENT + 被 Mica 过滤
→ FILTERED_OUT

Presence = PRESENT + TRASHED
→ TRASHED

Presence = PRESENT + PENDING/暂不可发布
→ 不做 CONFIRMED_MISSING；按等待/不可用语义处理

Presence coverage = PARTIAL/UNAVAILABLE
→ UNAVAILABLE / Unknown；保留旧 membership

Presence = ABSENT 且负责该 object 的所有相关 partition COMPLETE
→ 才有资格 CONFIRMED_MISSING
~~~

因此 **Full Scan 的可见候选筛选可以复用 Eligibility 规则，但 deletion correctness 必须额外有 Presence coverage**。

SAF 也遵循同一原则：tree walk 先提供“对象存在事实”，再叠加格式/排除目录/时长等 eligibility；子树 walk 失败时不能把该子树旧对象解释为 missing。

### 18.5.1 DEVICE 必须真实投影 pending / trashed 状态

§9 中的 `PENDING` / `TRASHED` 不是抽象枚举；S3 必须落实到 MediaStore query/projection。

在 API 支持时，Presence inventory 至少读取并保留：

- `IS_PENDING`；
- `IS_TRASHED`；
- volume/source identity；
- generation/version coverage。

冻结规则：

- `IS_PENDING=1` 的 row 不进入 authoritative upsert，也不能让旧 object 被确认 missing；
- copy/import 尚未完成时只记录 pending/dirty，待后续稳定 pass；
- `IS_TRASHED=1` 映射为 `TRASHED` membership reason，不映射为 physical missing；
- API/collection 无法提供这些状态时必须显式降低对应 Presence certainty，不能假装已确认“不 pending / 不 trashed”；
- `MEDIA_SCANNER_FINISHED` 之后仍必须重新读取实际 row 状态；广播本身不是“所有 pending 都结束”的证明。

必须覆盖“复制大文件过程中多次 observer 回调”的测试，期间不得出现：

~~~text
半成品 upsert
→ 删除
→ 完整文件重新加入
~~~

的 membership 抖动。

### 18.5.2 Delta upsert 必须保持 Full Scanner 的跨通道 dedupe

当前完整 `loadDrafts()` 会在 Audio drafts 与 Files fallback 之间使用 `mediaStoreDuplicateKey` 去重；纯 delta 不能只因为出现新的 row id 就直接 upsert。

S3 的 Audio delta / Files delta 合并前必须：

- 使用与 Full Scanner 等价的 duplicate identity/key 规则；
- 将本轮 delta 与**当前 authoritative local catalog / Presence facts** 一起参与 dedupe；
- 同一个物理文件同时从 Audio 与 Files 通道可见时只产生一个 authoritative object；
- Files fallback later-arrival 不得把已有 APE/DSD 再插成第二首；
- 如果 dedupe identity 本身不可靠，进入 reconciliation/retry，而不是猜一个新 Song id。

Shadow comparison 必须专门包含：

~~~text
Audio 与 Files 对同一文件先后/同时返回
→ Delta snapshot 成员数与 Full Scanner 一致
~~~

### 18.5.3 Projection 能力与“查询包含哪些行”必须分开验证

把 `IS_PENDING / IS_TRASHED` 加进 projection，只能证明“返回的 row 带这些列”，不能证明当前 query/permission/API 会返回所有 pending/trashed object。

S3 必须为每个 MediaStore channel 记录 capability：

~~~text
columnsAvailable
rowInclusionSemanticsKnown
permissionScope
volumeScope
apiLevel
~~~

只有“列可读 + 行包含语义已验证 + coverage complete”时，才能用其否定 pending/trashed 或下 destructive absence 结论。

无法证明 query 会包含某类 row：

- Presence certainty 降级；
- 不把“query 没返回”解释为“不存在”；
- 进入 targeted verify / full reconcile / platform capability fallback。

### 18.5.4 DEVICE stable object identity 只在已证明的 identity domain 内成立

设备测试不能证明 MediaStore `_ID` 永远不重用。

第一版冻结：

- `stableObjectKey` 不得是脱离 volume/source epoch 的裸 `_ID`；
- MediaStore version/database rebuild/reindex 触发 identity-domain invalidation；
- 旧 checkpoint 失效；
- 旧 USER_EXCLUDED / playlist evidence **不得直接套到一个仅仅复用了相同 _ID 的新 row**。

发生 identity-domain reset 后：

1. 尝试用唯一的 secondary identity evidence 做保守迁移，例如 volume + canonical/relative path + displayName + 其他已存事实；
2. 只有唯一匹配时迁移旧 object key/exclusion；
3. ambiguous 时不自动把新 row 排除，也不把旧 tombstone 静默丢弃；
4. 标记 `IDENTITY_RECONCILE_REQUIRED`，由 Full/reconciliation 或未来用户管理入口处理。

这意味着“切回同一来源排除仍有效”成立于**同一可证明 object identity lineage**，而不是“任何未来碰巧拿到相同整数 ID 的文件”。

## 18.6 External lyrics

即使 audio row 未变化，LRC/TTML 也可能变化。

AUTO 保留轻量 sidecar inventory：

~~~text
URI
relative path
display name
size
modified
~~~

生成每首歌对应的 external lyrics patch。

删除 sidecar 只有在 lyrics channel COMPLETE 时才允许 `AbsentConfirmed`。

---

# 19. SAF / FOLDER Auto Fast Verify

## 19.1 正确性不能依赖 MediaStore 通知

MediaStore/system signal 对 SAF 只是加速。

持续前台还必须有：

~~~text
SAF_PERIODIC_VERIFY
~~~

第一版可以以 5 分钟作为初始测试常量，但产品承诺只能写：

> “按约定周期发起核对。”

不能承诺“5 分钟内一定完成更新”，因为还包含排队、walk、probe、retry。

## 19.2 第一版仍是 O(N) metadata walk

~~~text
DocumentsContract 全树 metadata inventory
→ audio / lyrics / mp4 inventory
→ fingerprint compare
→ changed/new-only probe
~~~

不做“目录 mtime 相同就跳 subtree”。

只有 S4 profiler 证明 traversal 本身成为瓶颈，后续阶段才允许引入持久 FolderNode/subtree fingerprint。

## 19.3 DocumentsProvider fallback 完整性

当前主 query 失败后会走 DocumentFile fallback；其中 `listFiles() ?: return` 不能再等价于“空目录”。

每个 subtree 必须能报告：

~~~text
COMPLETE / PARTIAL / UNAVAILABLE
~~~

部分子树失败时：

- 成功子树的 Present 可用；
- 失败子树的旧成员保持；
- 不做全树 deletion；
- 不把该目录的 lyrics/video candidates 判空。

## 19.4 FingerprintReliability

推广现有 `hasStableEmbeddedLyricsFingerprint()`，不要建立平行规则。

~~~text
RELIABLE
PARTIAL
UNKNOWN
~~~

只有可靠 identity + size + modified 等满足要求时，才能用最便宜 reuse。

UNKNOWN：

- 不能因为“上次 0，这次也 0”就判内容没变；
- 可以识别增删/rename；
- **第一版选择“有预算的周期 deep verify”，不把 UNKNOWN 原地修改完全推给手动 Full Scan。**

第一版路径：

~~~text
fingerprint reliability = UNKNOWN
→ 持久记录 nextDeepVerifyDue / lastDeepVerifyResult
→ SAF_PERIODIC_VERIFY 时把到期对象加入同一 scheduler 的 deferred work
→ 按数量/时间预算分批 deep verify
→ 遵守 PlaybackIoGuard
→ 成功后更新 observed/resource state
→ 未完成对象保留 due，不静默丢弃
~~~

要求：

- 不新建第二个 coordinator；复用 LibrarySyncScheduler + 持久 deferred/retry work state；
- exact verify interval、每轮 object budget、wall-time budget 在 S4 Gate 前根据 10k/provider profiler 冻结；
- provider/metadata query 本身如果会与 playback 串行争用，也受 PlaybackIoGuard，不只 heavy probe；
- 长期 UNKNOWN 不能因为“从未失败过”而永远不进入 AutoProbeDecision；
- S4 的 `AutoProbeDecision` 因此增加第四个入口：`unknownFingerprintVerifyDue`；
- 用户 Full Scan 仍可提前偿还全部/更多 UNKNOWN verification debt。

这属于正确性换性能，UNKNOWN 的 probe 数不应与 reliable fingerprint 路径直接比较。

---

# 20. 视频封面与本地 MV

## 20.1 仅 FOLDER source 处理本地 MP4 关系

DEVICE scanner 当前会清 `videoCoverUri / musicVideoUri`；DEVICE AUTO 不宣称处理 SAF/FOLDER 的本地视频关系。

## 20.2 MV 第一版按受影响目录重算，不提前做精细依赖图

`MusicVideoMatcher` 不只有原始 `folderPath + baseName` 精确匹配，还存在 normalized-name 匹配与唯一性判断。只记录“旧 basename/new basename 两个组”容易漏掉归一化碰撞。

第一版冻结为：

~~~text
audio/video add/delete/rename/content-revision change
→ 标记 affected directory
→ 重新读取该目录当前完整 audio + mp4 candidates
→ 用现有 matcher 完整重算该目录关系
~~~

这样天然覆盖：

- 旧 basename；
- 新 basename；
- normalized basename；
- 多候选唯一性变化；
- rename 前后碰撞。

如果 affected directory inventory 不完整：

~~~text
relation = Unknown
→ 保留该目录旧关系
→ 进入 retry/verify
~~~

只有 profiler 证明“按目录重算”成为真实瓶颈后，后续版本才允许把它细化成 affected-group dependency graph。

## 20.3 videoCoverRevision

当前 poster cache 仅以 URI 作为磁盘 key，同 URI MP4 内容替换会命中旧图。

S1 增加：

~~~text
videoCoverRevision = uri | size | lastModified
~~~

并贯穿：

- Song；
- Room；
- SongMediaItemCodec；
- poster key；
- 相关 Song.copy / comparison。

poster cache key 改为：

~~~text
uri + revision
~~~

AUTO 不做 bulk poster prefetch；真正显示/播放时 lazy 生成。

---

# 21. Queue reconcile 与当前播放 orphan

## 21.1 不再用“存在已删除 ID → SetQueue(整库)”

`LibraryQueueSyncPolicy` 必须接受 change cause / membership evidence，并产生选择性 plan。

推荐计划类型：

~~~text
RefreshPresentationMetadata
RemoveMissingItems
KeepCurrentOrphanAndRemoveOthers
BootstrapForInitialRestore
ReplaceForExplicitSourceSwitch
NoOp
~~~

其中 `BootstrapForInitialRestore` **只能**用于：

- 冷启动时明确恢复已有 playback/library session；
- 首次 authoritative FULL 建库后的显式 bootstrap；
- 用户显式 source-switch FULL 成功后的恢复策略。

AUTO_SYNC 的任何 membership change 都不得因为：

~~~text
currentQueueIds.isEmpty()
~~~

而把整个 library 填入播放器。

因此以下状态均保持空队列：

- 用户主动清空 queue；
- 播放自然 ended 后 queue 已空；
- 当前没有可恢复 playback session；
- AUTO 新增一首或多首歌曲时 queue 原本为空。

AUTO 的 queue reconcile 在空队列下默认必须是 `NoOp`，除非存在明确的、与 AUTO 无关的 session bootstrap intent。

## 21.2 Queue 按当前事实重算，不消费 durable command

输入：

~~~text
current library snapshot
current playback queue
current session/current item
latest LibraryChangeSet（仅作为原因提示）
~~~

每次重算。

过期删除事件不得直接重放到新 queue。

## 21.3 当前播放项被删除

当前 item 成为 playback-session orphan。

以下状态都**不结束** orphan：

- pause；
- buffering；
- repeat-one；
- queue 只剩它。

结束条件：

- 真正切到另一首；
- 用户显式从 queue 移除当前 orphan；
- stop/reset；
- terminal playback failure。

如果底层文件仍可读，允许继续播到自然切换；如果不可读，走播放器已有 error/skip，不由 library 强制全队列重建。

## 21.4 歌词资源需要不可变 revision，但普通 sidecar 更新允许当前播放实时切换

自动同步正确性要求“同一个 resourceRevision 永远表示同一份资源内容”，但**不要求当前播放把旧歌词冻结到下一曲**。

产品取舍冻结为：

> **音频源、ReplayGain/loudness 等会影响实际播放管线的状态按 playback instance 冻结；歌词属于展示/同步资源，AUTO 发布新歌词后，当前播放允许实时切换到新版本。**

这样保留资源/缓存正确性，又避免为了普通歌词修正长期 lease 旧版本。

当前实现仍需要修：

- `song_lyrics` 主键只有 `(songId, slot)`，同槽覆盖无法表达 immutable resource；
- `lyricsById(id, revision)` 虽然接收 revision，DAO 当前并未真正按 revision 读取；
- songId-only cache 无法区分新旧解析结果。

### 21.4.1 Observation revision 与 resource revision 必须分开

~~~text
observedRevision
= “扫描时看到的输入对象版本”
= source/file/provider fingerprint + parser input context

resourceRevision
= “解析后实际歌词资源内容版本”
= canonical content identity
~~~

`observedRevision` 可以使用可信 provider 下的 URI/identity + size + mtime + generation 等，用于判断是否需要重 probe；它**不能**直接充当 immutable resourceRevision。

`resourceRevision` 冻结生成规则：

~~~text
resourceRevision =
  hash(
    resourceSchemaVersion
    + slot/source-kind
    + canonical LyricsDocument payload
    + 影响解析语义但不会完整体现在 payload 中的必要 parser semantic version
  )
~~~

要求：

- 同一个 `resourceRevision` 必须永远对应同一份 canonical 内容；
- parser 升级/修复若产生不同内容 → revision 必须变化；
- parser 升级但 canonical 结果完全相同 → 复用原 revision，不无限新增副本；
- retry 得到相同结果 → 复用；
- resource row immutable，禁止原地改写同 revision 内容；
- hash/serialization 发生 schema 演进时 bump `resourceSchemaVersion`。

目标模型：

~~~text
song_lyrics_resource
  songId
  slot
  resourceRevision
  canonicalLyricsJson
  ...

song_lyrics_head
  sourceIdentityKey
  songId
  slot
  currentResourceRevision
  observedRevision
~~~

具体表名可在 migration 中调整。

### 21.4.2 Lyrics cache key 必须包含“资源集合 + 选择策略”

“`songId + resourceRevision`”只是简写，不能作为完整 cache key 规范。

解析/选择后的歌词 cache key 至少必须能区分：

~~~text
songId
slot/resource revisions
resource-set revision
lyrics selection priority/policy revision
会影响最终选中/组合结果的必要设置版本
~~~

例如 provider/slot 优先级变化时，即使底层 resourceRevision 没变，也必须得到新的 resolved cache identity。

AUTO promote r2：

- active head 原子切到 r2；
- 当前播放、通知歌词、桌面歌词如果正在跟随这首歌，可以收到 lyrics resource revision 变化并**实时重新 hydrate 到 r2**；
- 不通过 `setQueue()` 或重建 Media3 timeline 来实现歌词刷新；
- r1 若不再被 active head、pending transaction、orphan-retention 引用，可按 GC 规则回收。

### 21.4.3 resource lease 只保留真正需要的旧资源

第一版不再为“当前歌曲正常歌词更新”长期 lease r1。

lease 只用于：

- 当前播放项已经从 library membership 移除、成为 orphan，但仍需旧歌词/封面等资源完成当前 session；
- 正在执行中的读取/恢复需要防 GC；
- 其他明确的短生命周期 resource retention。

因此普通：

~~~text
current song lyrics r1
→ AUTO publish r2
~~~

可以立即切到 r2，不要求等下一首。

删除 membership 时仍产生：

~~~text
RESOURCE_GC_CANDIDATE(oldRevision)
~~~

但若当前 orphan lease 引用 oldRevision，则延后 GC。

### 21.4.4 lease 获取与 GC 的原子边界

lease/GC 的原子协议仍保留，但范围缩小到真正 retained resources；不与 10k 曲库或每次歌词更新线性增长。

### 21.4.5 lease 获取与 GC 必须有原子边界

必须新增明确 owner（例如 `PlaybackResourceLeaseCoordinator`，最终命名可调整）。

GC 与 lease 的锁顺序冻结为：

~~~text
Publication gate（若同时涉及 catalog/resource head）
  → Resource lease mutex
      → Room transaction
~~~

播放实例建立 lease：

~~~text
acquire Resource lease mutex
→ transaction:
     verify resource revision still exists
     register/refresh lease for playbackSessionId + resourceRevision
→ release
~~~

GC consumer：

~~~text
acquire Resource lease mutex
→ transaction:
     re-read resource heads
     re-read active leases
     if revision is neither current head nor leased:
         delete resource revision
         ack GC outbox
     else:
         keep/defer GC event
→ release
~~~

因此不能出现：

~~~text
GC 检查“无 lease”
→ 新 playback lease 建立
→ GC 删除旧资源
~~~

这种竞态。

### 21.4.6 启动恢复顺序

进程启动时：

~~~text
load durable library/resource heads
→ restore PlaybackRuntime session/MediaItem
→ reconcile/register playback resource leases
→ mark playback restoration complete
→ 才允许 RESOURCE_GC consumer 启动
~~~

如果没有可恢复 playback session，则清理上一进程留下的 stale lease，再开放 GC。

必须保证：

- “先 GC、后恢复 session”禁止发生；
- stale lease 有 session/process generation，可安全回收，不造成永久资源泄漏；
- 同一时刻常驻 lease 仅覆盖实际 active/preserved playback instances，不与 10k 曲库规模线性增长。

## 21.5 当前歌曲被修改

AUTO 不 hot-swap 当前播放 source version。

可以即时更新：

- title；
- artist；
- album；
- 纯 presentation 字段。

当前 playback instance 固定：

- mediaUri / playbackUri；
- duration/codec；
- musicVideo URI/revision；
- ReplayGain track/album gain + peak tags；
- `loudnessAnalysis`；
- 当前已计算并应用到音频管线的 `AppliedReplayGain` / 等价 gain state；
- 其他 source-version-bound 资源。

新版本从下一次重新播放该 item 开始使用。

注意：这里冻结的是“Mica 选择哪一版资源/MediaItem 元数据”，不是承诺底层文件在同一路径被外部原地覆盖时 Android 文件系统仍提供旧字节快照。

因此 `refreshQueueMetadata()` 需要拆出：

~~~text
presentation-only refresh
→ 不 setQueue / 不重建 Media3 timeline

next-instance source refresh
→ 更新未来重新播放时使用的 Song/MediaItem source version
→ 当前 instance 保持原 resource set
~~~

当前实现 `refreshQueueMetadata() → setQueue(refreshed)` 在 S1 必须拆开。

---

# 22. Playlist cleanup 契约

AUTO physical removal 与现有手动删除必须区分 evidence。

### CONFIRMED_MISSING

允许 durable outbox 清理 playlist reference。

### USER_EXCLUDED

保持当前手动“从曲库移除”行为：从所有歌单删除。

### FILTERED_OUT / TRASHED / SOURCE_REPLACED

**不得永久删除 playlist reference。**

因为未来对象可恢复。

现有 `songsForPlaylist()` 的 `mapNotNull` 可以让暂时不可见对象不显示，但持久 ID 仍保留。

回收站恢复、过滤恢复、切回 source 后，对象重新可解析时 playlist reference 自动重新生效。

---

# 23. AUTO Publication Policy

不能继续让 AUTO 走现有所有后处理。

建议：

~~~kotlin
data class LibraryPublicationPolicy(
    val userVisible: Boolean,
    val allowLyricsMaintenance: Boolean,
    val allowArtworkMaintenance: Boolean,
    val allowVideoPosterPrefetch: Boolean,
    val allowFullFolderCasingReconcile: Boolean,
)
~~~

### FULL

按现有完整语义。

### AUTO

~~~text
userVisible = false
lyrics maintenance = false
album-art maintenance = false
bulk poster prefetch = false
full casing reconcile = false
manual scan summary/error publication = false
scan-start transient-cache clearing = false
maintenance-driven full reprobe = false
~~~

AUTO 可以对新增目录做受影响目录的局部 physical identity reconciliation，不能把大小写问题留给下次手动扫描。

## 23.1 AUTO 不得写手动扫描结果 UI 字段

现有 `publishSongs()` / failure path 会更新：

- `lastScanSyncSummary`；
- `lastScanError`。

这些字段已经是用户可见契约：首页会把 `lastScanSyncSummary` 弹 snackbar，也会展示 `lastScanError`。

冻结规则：

- AUTO 成功**不得写** `lastScanSyncSummary`；
- AUTO 失败**不得写**面向用户的 `lastScanError`；
- AUTO 不得清掉用户上一轮手动 FULL 留下的 `lastScanError` / summary 状态；
- provider 抖动、storage 暂不可用应更新 `AccessState` / scheduler diagnostic / RetryLedger，而不是伪装成一次用户扫描失败；
- “无法访问所选文件夹，请重新选择”这类 action-oriented 文案只属于用户显式 FULL / folder binding flow。

AUTO 的成功/失败只进入受限频率的诊断日志和内部状态；除非问题需要用户采取明确动作，否则不弹 snackbar。

## 23.2 AUTO 禁止调用手动扫描的 scan-start clearTransientCache

当前 scan-start 清场会：

~~~text
VideoCoverPosterPrefetcher.cancel()
ScanCacheManager.clearTransientScanCache(...)
~~~

AUTO 每次 dirty 都执行会取消正在使用/生成的 poster，并清 `lyrics_probe` / `lyrics_meta` 等 transient cache。

冻结规则：

- AUTO_SYNC 不调用 `clearTransientCache()`；
- AUTO 不取消与本轮 changed object 无关的 poster prefetch；
- AUTO 只允许对**受影响 object/revision** 做精确 cache invalidation；
- FULL / 明确 maintenance 仍可保留全局 scan-start 清场语义；
- TARGETED 也不得无理由执行全局 clear。

## 23.3 AUTO probe eligibility 必须与 maintenance reuse miss 分离

当前 `reusableCachedSong()` 的 miss 原因混合了两类事实：

### 内容可能真的变了

- stable identity/fingerprint 改变；
- reliable size/mtime/revision 改变；
- external lyrics sidecar signature 改变；
- object revision / source version 改变。

这些可以让 AUTO probe。

### 只是维护状态不理想

- artwork cache 缺失/不可读；
- coverColor 缺失；
- `metadataScanVersion` 过期；
- parser version upgrade；
- global lyrics retry；
- DSD/特殊 metadata maintenance 状态；
- 其他“手动 Full 希望顺便修”的 cache-health 条件。

这些**不得**让一条普通 AUTO dirty 把整库变成 changed/probe candidates。

因此 S3/S4 必须引入 AUTO 专用的 probe-decision：

~~~text
AutoProbeDecision =
  identity/fingerprint change
  OR relevant sidecar change
  OR explicit object RetryLedger item
~~~

maintenance-only miss 必须路由到：

~~~text
FULL
ARTWORK_REPAIR
明确 maintenance operation
~~~

而不是 AUTO delta。

## 23.4 AUTO 仅作用于 local ScanSource

本计划的 AUTO source 边界冻结为：

~~~text
DEVICE
FOLDER/SAF
~~~

不扫描、不删除、不重建：

- `RemoteTrackEntity`；
- Navidrome/SMB 等 remote catalog；
- remote queue entries。

混合队列 reconcile 必须保留 remote/external entries；本地 AUTO 的 membership reason 不能传播成 remote deletion。

---

# 24. isScanning 与 UI 契约

现有 `isScanning` 已被：

- UsageTutorialScanInvitation；
- 设置页；
- 首页统计；
- 空态；
- artwork repair

等读取。

新增：

~~~text
isOperationRunning
isUserVisibleScanning
~~~

或等价语义。

要求：

- AUTO 运行时设置页“扫描曲库”仍可点击；
- 用户 Full 可以抢占 AUTO；
- AUTO 不弹首次扫描教程；
- AUTO 不闪现 Full Scan 进度；
- artwork repair 不因 AUTO 结束而偷偷触发一次重型 Full；
- 用户可见扫描状态只由用户 FULL / 明确需要展示的操作驱动。

## 24.1 AUTO publication 后 UI 临时选择状态必须自愈

AUTO 运行时用户仍可能正在：

- 多选歌曲；
- 编辑歌单；
- 浏览当前文件夹；
- 对 selection 执行批量加入/删除操作。

catalog publication 后，所有 UI 临时 selection 必须按当前可见/可操作 ID 做交集：

~~~text
selection = selection ∩ currentValidIds
~~~

规则：

- 已不存在的 ID 从 selection 静默移除；
- 批量操作执行前再次过滤 missing IDs，不能对失效 ID 继续调用删除/加入歌单；
- 当前文件夹因 AUTO 变化为空时正常显示空态，不把 stale selected item 保留成幽灵条目；
- 不要求为 selection 建 durable transaction；它是 UI transient state，但必须在 catalog revision 变化时自愈；
- remote/external selection 不受 local AUTO membership removal 影响。

S1 至少测试：

~~~text
用户多选 A/B/C
→ AUTO 删除 B
→ selection 变 A/C
→ 用户点“加入歌单”
→ 只操作 A/C，不对 B 产生 stale mutation
~~~

---

# 25. 全局 Lyrics Maintenance 隔离

当前 `performScan()` 可能因为本轮无歌词失败就：

- persist parser version；
- clear `lyricsRetryRequired`。

AUTO/TARGETED 不能这样宣告“全库 maintenance 完成”。

规则：

- parser version upgrade 只由 FULL/明确 maintenance pass 完成；
- AUTO 局部歌词成功不清除全局 retry；
- TARGETED 单曲刷新不清除全局 retry；
- **AUTO / TARGETED 不得因为全局 parser upgrade 或 `lyricsRetryRequired` 把 `forceRefreshLyrics=true` 扩散到本轮全部歌曲**；
- 全局 parser upgrade / global lyrics retry 只允许由 FULL / 明确 lyrics-maintenance operation 执行全库 re-probe；
- AUTO 只 probe：
  - 本轮 fingerprint/sidecar 真正变化的歌曲；
  - RetryLedger 中明确列出的对象；
- TARGETED 只 probe 指定 song IDs；
- 局部失败进入 RetryLedger 或维持已有 global retry 状态；
- slot patch 不得先删除未知槽。

必须有 10k 测试：

~~~text
global lyricsRetryRequired = true
→ 收到一条单曲 MediaStore dirty
→ AUTO 只处理该 changed/retry object
→ probe count 不得退化为全库 10k
→ global retry flag 仍保持，等待 FULL/maintenance
~~~

---

# 26. 无变化短路

AUTO scanner/rebase 最终得到：

~~~text
membership changes = none
field patches = none
derived relation changes = none
~~~

则禁止：

- `prepareLibrarySongs`；
- `commitScan(full snapshot)`；
- browse rebuild；
- catalog adopt；
- songIds/queue revision；
- album art maintenance；
- video poster prefetch。

只允许：

~~~text
同一个受保护 Room transaction：
  checkpoint
  + RetryLedger / discovery backoff
  + 必要的 outbox supersede
~~~

scheduler 的纯内存状态可以在事务后更新，但不能反过来成为持久正确性的唯一依据。

如果本轮 checkpoint 会越过任何 probe/discovery failure，则对应 retry/backoff 记录必须与 checkpoint 同事务存在；事务失败时两者一起回滚。

这是 10k 曲库 AUTO 的第一性能目标。

---

# 27. 有变化时仍合成完整 snapshot

第一版保持：

~~~text
current authoritative snapshot
- confirmed membership removals / visibility changes
+ validated upserts / patches
+ affected relation recomputation
+ current user/runtime-owned fields rebase
= next complete snapshot
~~~

然后：

~~~text
prepareLibrarySongs
→ commitScan
→ adoptPrepared
~~~

不在第一版引入增量 browse authority。

如果 S3/S4 profiling 证明 browse rebuild 是主要瓶颈，再另立计划。

---

# 28. Shadow Mode 必须无真实副作用

S2/S3/S4 对照不可直接调用会写生产状态的 Full Scanner 链路。

Shadow 规则：

- 不 `applyLyricsBatch`；
- 不写 library Room catalog；
- 不写 artwork/video cache；
- 不 prefetch；
- 不写 checkpoint；
- 不写 retry/outbox；
- 不发布 Compose state。

优先使用冻结 fixture / fake provider / 隔离 DB 生成 canonical expected snapshot。

生产 Full Scanner 可作为第二对照，但：

> **Full Scanner 不是不可质疑的真理。**

发现 delta 与 full mismatch 时，要判断哪一边是 bug。

---

# 29. Canonical Shadow Equality

不能直接比较完整 `Song == Song`。

Shadow canonical fingerprint 至少包含：

- membership stable identity；
- media URI / document identity；
- file fingerprint；
- folder identity；
- 核心 tag metadata；
- external lyrics signatures / slot presence facts；
- video cover relation/revision；
- music video relation/revision；
- filter state；
- source identity + activation facts。

明确排除：

- playCount；
- totalListenSeconds；
- lastPlayedAt；
- 动态 coverColor（除非被测试为 version-bound scanner 事实）；
- lazy-loaded lyrics payload；
- runtime queue/session state。

---

# 30. Schema / Migration 计划

实施前按最新 Room schema 确认 migration 起点。

预期新增/调整的持久状态：

1. library lifecycle / SourceIdentityKey / activationEpoch state；
2. sync checkpoint；
3. retry items；
4. durable follow-up outbox；
5. user exclusions/tombstones；
6. operation-scoped scan resource staging；
7. versioned lyrics resources + active slot heads；
8. playback resource lease / lease generation（或语义等价的持久协调状态）；
9. videoCoverRevision；
10. 必要的 object/source/presentation/resource revision 字段。

迁移规则：

### 旧库有 library_meta

即便 song rows = 0，也迁移为：

~~~text
LibraryIntentState.ACTIVE
~~~

从 meta 恢复 source。

### 无 library_meta

~~~text
UNINITIALIZED
~~~

### 旧 user-removed song

当前没有可靠 tombstone 历史，无法回溯恢复；迁移只保护升级后的显式移除。

### clear

冻结语义：**“清空曲库”清除扫描得到的 active library，但不撤销用户显式 exclusion。**  
恢复 exclusion 只能通过“恢复已从曲库移除的歌曲”或未来明确的逐项恢复入口。

`LibraryStore.clear()` / clear authority transaction 必须：

- 删除当前 active songs / browse / current resource heads / scan-derived meta；
- 清理对应 active source/config 的 sync checkpoint；
- 清理或终止可由 clear 明确重置的 retry；
- 把 library intent 持久化为 `CLEARED_BY_USER`；
- invalidate 当前 activation / pending operations；
- 清理未激活、已 abandoned 的 scan staging。

**不得一律删除：**

- `library_user_exclusions`：保留，直到用户明确恢复；
- durable outbox：按 §14.3.1 的 action 失效条件处理；
- 已被 active playback resource lease 引用的 versioned resource；
- 已承诺但尚未完成的 `PLAYLIST_REMOVE_USER_EXCLUDED` 等 follow-up。

clear 事务可以对 outbox 做“supersede/obsolete”判定，但不能用 `DELETE ALL outbox` 代替 action-specific 语义。

“清库后 AUTO 不复活”“清库后 exclusion 仍有效”“清库前已承诺的用户删除歌单清理不会丢失”必须有 migration/启动测试。


## 30.1 复杂度与性能成本必须有容量边界

新增 staging / immutable resource / outbox / retry 的成本不能只靠“以后 GC”解释。各阶段开启真实行为前必须量化。

### 30.1.1 Staging 不复制整份歌词 payload

第一版采用：

~~~text
immutable resource blob/row（按 resourceRevision 去重）
+
operation staging 只保存 resourceRevision/reference + patch metadata
~~~

禁止：

~~~text
active lyrics JSON
+ staging 再复制一份相同 JSON
+ promote 再复制第三份
~~~

canonical serialization/hash、parser、provider/file IO 都在 Publication gate 外完成。

可以在最终 gate 前预写 immutable resource blob，因为它尚未成为 active head；失败/取消后只形成可回收 orphan blob，不改变 authoritative library。

final transaction 只做：

- staging/resource reference promote；
- active heads；
- prepared snapshot/browse rows；
- checkpoint/retry/outbox；
- lifecycle/source metadata。

空间不足时：

- immutable blob/staging 写入失败 → operation 失败；
- 不进入 final authority commit；
- 旧 snapshot/head 保持可用；
- abandoned staging/orphan blob 后续分批清理；
- 不允许“为了腾空间先删旧 active resource，再尝试写新资源”。

### 30.1.2 磁盘峰值必须测

S1/S3/S4 Gate 至少记录：

~~~text
DB file
WAL/SHM
active resource payload
new immutable resource payload
staging refs
retained orphan resources
retry/outbox rows
~~~

以完整歌词总编码载荷 `L` 为基线，测试 parser/歌词全量更新时旧 + 新资源并存的峰值；Gate 前冻结可接受的设备剩余空间条件和失败行为。

### 30.1.3 Final Publication gate 必须短

测量：

- waitPublicationGateMs；
- holdPublicationGateMs；
- Room transaction ms；
- memory adopt ms；
- blocked clear/sort/user Full wait ms。

禁止在 gate 内做：

- provider query；
- file open/probe；
- parser；
- JSON canonical serialization/hash；
- 大型 browse 计算；
- poster/cover extraction。

如果 prepared Room rows 本身序列化成本明显，必须在 gate 外提前构造；gate 内只提交已准备好的值。

### 30.1.4 bounded rebase retry 达上限后不能丢工作

行为冻结：

- AUTO/TARGETED：保留 dirty/retry/deferred work，scheduler 退避后重新排队；不得立即无限 loop，也不得当成功清掉 dirty；
- ARTWORK_REPAIR：退避重排或保留 repair-needed；
- 用户 FULL：保持旧 authoritative snapshot，结束本次请求并显示明确的“曲库正在变化，请重试”/等价错误；不偷偷无限重算。

具体连续 rebase attempt 数和 backoff 在 S0 Gate 前作为测试常量冻结。

### 30.1.5 长期增长必须有 batch GC 和预算

必须有：

- abandoned staging 分批回收；
- 无 head/lease/reference 的 immutable old resource 分批 GC；
- ack/obsolete outbox 有 retention 后分批删除；
- stale session lease 清理；
- Retry/deferred work 去重、分页；
- 每轮 GC 的 row/time budget，不能在一次 AUTO 尾部做全库 vacuum 式清理。

### 30.1.6 性能 Gate 必须冻结数值，而不是只写“无感”

S3/S4 开真实 AUTO 前，各自记录 manual/no-AUTO baseline 与 AUTO 对照，并冻结数值阈值：

- 手动切歌延迟；
- playback underrun/xrun；
- 主线程最长阻塞与 jank；
- Publication gate wait/hold；
- Room transaction；
- RSS peak；
- DB/WAL/disk peak；
- provider query/probe wall time；
- 10k no-op / small-delta pass wall time。

本文不现在拍具体毫秒/MB 数字；**但对应 Gate 没冻结并通过这些指标之前，不允许启用真实 AUTO。**


---

# 31. 分阶段实施

---

## S0 — Operation / Scheduler / Lifecycle / Publication Protocol

### 目标

完成**真实的 operation/scheduler/publication owner 重构**，让现有手动扫描、cache hydrate、clear、sort、maintenance 已经运行在新一致性协议上；**不接任何 observer，不做 DEVICE delta，不做 SAF fast**。

S0 不是空壳接口阶段。尤其 `scanExecutionMutex` 职责迁移、pure preparation、最终 publication linearization 必须在 S0 真正落地并由现有手动流程验证。

### 必须完成

1. `LibraryOperationMode / Cause / Token`；
2. `LibrarySyncScheduler` 替换现有 cancel-all 入口；
3. dirtySequence / pendingDirty / cooldown wake / max debounce 机制；
4. targeted ID set 合并；
5. source intent/access/identity/activation 三轴；
6. source two-phase transition；
7. configFingerprint invalidation；
8. `scanExecutionMutex` 现有调用者按 §16.1.2 完整迁移；
9. `prepareLibrarySongs` / browse preparation 纯化，建立 presentationRevision；
10. publication gate + optimistic rebase + durable linearization point 真正落地；
11. pending-source scan resource staging：B 未激活前不得写 active lyrics/resource head；
12. checkpoint + RetryLedger 同事务 seam；
13. checkpoint/retry/outbox/tombstone 的 owner/store 接口与 clear 语义；
14. transient `LibraryChangeSet` 事实模型；
15. `isUserVisibleScanning` 或等价 UI 语义；
16. clear/release/source switch 对所有旧 pending request 的失效；
17. 手动 Full/cache hydrate/sort/clear/artwork maintenance 在新 seam 上回归；
18. ADR 落盘并接受。

### S0 禁止

- register ContentObserver；
- periodic SAF verify；
- generation delta；
- 改 scanner discovery 算法；
- 自动删除；
- 真实 outbox playlist cleanup；
- video schema migration（可留到 S1）；
- 调性能参数。

### S0 确定性交错测试

至少：

1. AUTO-like operation 卡在 discovery 结束 → user Full → 旧结果不能 publish；
2. 旧操作在最终 gate 前失效 → Room / memory / active source 均无旧提交；
3. 旧操作已通过 final validation 并持有 Publication gate → source switch/clear 必须等待其 Room + memory publication 完成，再开始新 authority replacement；
4. 模拟 Room commit 后、memory adopt 前进程死亡 → 重启/cache hydrate 后 snapshot、active source、checkpoint/retry/outbox 与 committed Room 一致；
5. checkpoint 将越过失败对象 → retry write 失败 → 整个事务回滚，checkpoint 不推进；
6. checkpoint + retry 已同事务提交 → 立即进程退出 → 重启后失败对象仍可重试；
7. source A → pending B → B 歌词 staging 已写 → B 失败 → A snapshot 与 A active lyrics/resource heads 完全不变；
8. source A → pending B → B 成功 → staged resources + B snapshot + active=B 原子激活，不出现 A/B 混合；
9. pure prepare 读取 custom order A 时暂停 → 用户改成 B → old prepared 被丢弃，preferences/Room/memory 不被 A 回写；
10. config 改变 → 旧 operation/checkpoint 失效，但同一 SourceIdentityKey 的 USER_EXCLUDED 仍有效；
11. AUTO running 收 dirty → token 不失效，pass 后 pending follow-up；
12. cooldown 内 dirty → cooldown end 必醒；
13. 两个 targeted ID 先后进入 → 不覆盖；
14. clear → exclusions 保留；action-specific durable outbox 不因 clear 被一刀切删除。

### S0 Gate

只有在：

~~~text
NO_OBSERVER_REGISTERED
SINGLE_SCHEDULER_OWNER
SCAN_EXECUTION_MUTEX_MIGRATION_COMPLETE
PURE_PREPARATION_ZERO_SIDE_EFFECT
SOURCE_IDENTITY_ACTIVATION_SPLIT_FROZEN
SOURCE_TRANSITION_RESOURCE_ISOLATION_GREEN
CHECKPOINT_RETRY_ATOMIC
PUBLICATION_LINEARIZATION_GREEN
ROOM_MEMORY_RESTART_CONSISTENT
MANUAL_SCAN_BASELINE_GREEN
~~~

后才能进入 S1。

### S0 implementation checkpoint — 2026-09-06

S0 已按上述 Gate 完成并冻结，尚未启用任何真实 AUTO discovery：

- `NO_OBSERVER_REGISTERED`：生产代码无 `ContentObserver/registerContentObserver`；
- `SINGLE_SCHEDULER_OWNER`：FULL / TARGETED / ARTWORK / protocol-only AUTO 统一经 `LibrarySyncScheduler`；
- `SCAN_EXECUTION_MUTEX_MIGRATION_COMPLETE`：旧 `scanExecutionMutex` 已无调用/字段残留；
- `PURE_PREPARATION_ZERO_SIDE_EFFECT`：presentation preparation 只返回 proposed state，最终写入统一进入 publication/store seam；
- `SOURCE_IDENTITY_ACTIVATION_SPLIT_FROZEN`：source identity / activationEpoch / pending transition 已分离；
- `SOURCE_TRANSITION_RESOURCE_ISOLATION_GREEN`：pending-source lyrics 使用 operation staging，成功随 authority commit promote，失败/取消完整 discard；
- `CHECKPOINT_RETRY_ATOMIC`：`library_sync_state + library_retry_items` 由同一 Room transaction 更新；强制 retry insert 失败测试确认 checkpoint 同步回滚；
- `PUBLICATION_LINEARIZATION_GREEN`：final gate 同时复验 token / catalogRevision / presentationRevision，Room commit 后在同一短 non-cancellable section adopt memory；
- `ROOM_MEMORY_RESTART_CONSISTENT`：v27 schema、library lifecycle state、clear/cold-cache 路径与 migration 测试通过；
- `MANUAL_SCAN_BASELINE_GREEN`：`MusicLibraryTest + data.library.* + LibraryRepositoryTest + DatabaseMigrationTest + LyricsScanBatchTest` 全绿；
- UI 已切到 `isUserVisibleScanning`，protocol-only AUTO 不触发教程、扫描空态、统计栏“扫描中”或禁用用户手动扫描；
- `LibraryChangeSet` 在 S0 仅发布 transient added/updated/cause/revision；removal reason/evidence 保留到 S1，禁止在没有 discovery evidence 时伪造删除事实；
- `library_followup_outbox` / `library_user_exclusions` 已建立 owner/store/schema seam；S0 不消费 outbox、不执行自动删除，clear 不做 `DELETE ALL`。

全量 `:app:testDebugUnitTest` 当前仍有一条与本 S0 无关的 `SettingsSearchIndexTest.conditionalMetadataIncludesMergedCarBluetoothOutput` 基线差异：同 HEAD 的主工作树依赖未提交 Settings 搜索改动可通过，隔离 worktree 的 HEAD 版本单跑失败。未将这些不相关未提交改动混入 S0。

---

## S1 — Destructive Safety / Patch / Queue / Playlist / Resource Contract

### 目标

拆掉 AUTO 上线后会被放大的现存风险。

### 必须完成

1. tri-state `Observed<T>` / patch；
2. partition-aware completeness；
3. Presence inventory 与 Eligibility inventory 分离；
4. removal reason/evidence；
5. AUTO mass-deletion guard；
6. Files fallback failure 不再伪装 empty；
7. SAF DocumentFile fallback 失败不再伪装 empty subtree；
8. user exclusion/tombstone；
9. slot-level lyrics patch + operation staging promote；
10. versioned lyrics resource + active slot head；
11. revision-aware lyrics memory/session cache；
12. playback resource set / resource lease / startup-before-GC 顺序；
13. global lyrics maintenance 隔离，并禁止 AUTO/TARGETED 全库 `forceRefreshLyrics`；
14. LibraryChangeSet + durable outbox；
15. outbox “复核+playlist mutation+ack” 原子事务与锁顺序；
16. action-specific outbox invalidation/clear 语义；
17. playlist cleanup 幂等消费；
18. queue selective reconcile，AUTO 空队列默认 NoOp；
19. current orphan + versioned resource GC；
20. current item presentation vs source-version refresh 分离；
21. 当前 playback ReplayGain/loudness/AppliedReplayGain freeze；
22. loudness mutation 与 AUTO rebase 的 revision 冲突规则；
23. videoCoverRevision；
24. affected MV group patch 语义；
25. AUTO publication policy；
26. AUTO 不写手动 scan summary/error、不调用全局 `clearTransientCache`；
27. UI transient selection 在 catalog revision 后自愈；
28. local-only ScanSource 边界；
29. permission loss 不再冒充 CLEARED_BY_USER；
30. membership mutation 与 scanner final publish 使用同一 publication gate。

### S1 关键测试

- active library=10k，AUTO query 成功但 Presence=0 → 不 publish empty、不删歌单、不改 queue，进入 reconcile-needed；
- 大比例 removal 第一轮触发 quarantine，不立即 destructive publish；
- AUTO membership 新增时 current queue 为空 → queue 保持空，不 `BootstrapOrSetQueue(整库)`；
- AUTO 新增/更新一首 → `lastScanSyncSummary` 不变、不弹“扫描完成” snackbar；
- AUTO provider/storage 抖动 → 不覆盖/清理用户手动 `lastScanError`；
- AUTO/TARGETED 在 global parser upgrade / lyricsRetryRequired 下不打开全库 `forceRefreshLyrics`；
- AUTO 不调用全局 `clearTransientCache()`，不取消无关 poster prefetch；
- lyrics channel PARTIAL 时旧 LRC/TTML slot head 不被清；
- Present 新歌词只新增 versioned resource，final publication 前 active head 不改变；
- pending B 已产生歌词 staging 后失败 → A 的歌词 head/资源选择完全不变；
- 当前播放歌词 r1 → AUTO 发布 r2 → library/current playback/通知歌词/桌面歌词均可实时切到 r2；不得通过 setQueue/重建 Media3 timeline 实现；
- GC 检查与 playback lease 建立交错 → 旧资源不能在 lease 建立竞态中被删；
- 启动恢复 session/lease 完成前 GC consumer 不运行；
- 当前 playback ReplayGain/loudness=r1 → catalog 发布 r2 → 当前 AppliedReplayGain 不跳变，下一次播放才采用 r2；
- loudness scan 与 AUTO rebase 交错 → fingerprint 相同的最新 loudness 不被空值覆盖；
- video group PARTIAL 时旧 MV relation 不被“唯一化”；
- Files fallback query 失败时 APE/DSD 不被删除；
- SAF 子树失败时旧成员不删；
- Presence=PRESENT 但 Eligibility=false → FILTERED_OUT，不得 CONFIRMED_MISSING；
- FILTERED_OUT 不清歌单；
- TRASHED 不清歌单，恢复后引用回来；
- CONFIRMED_MISSING 生成 durable playlist cleanup；
- outbox consumer 复核后准备删除时并发 re-add → publication gate/同事务保证不会误删新引用；
- Room commit 后模拟进程退出 → 启动后 outbox 完成；
- 旧 outbox 晚到但 song 已 re-add → 不删新 playlist reference；
- clear/source switch 后 `PLAYLIST_REMOVE_USER_EXCLUDED` 仍按 action 语义完成，不被一刀切丢弃；
- user remove + 文件删除失败 → AUTO/Full 都不能复活；
- clear → foreground catch-up → 曲库不复活，且 exclusion 仍保留；
- 当前曲 AUTO missing → 不 setQueue 整库、不立即停止；
- 当前 orphan 仍可按 leased revision 读歌词资源；
- 当前文件 tag 改变 → presentation 可更新但播放 source 不 hot-swap；
- 用户多选 A/B/C，AUTO 删除 B → selection 自动变 A/C，后续批量操作不碰 stale B；
- local AUTO publication 不删除/重建 remote catalog/queue entries；
- parser version 不被 AUTO/TARGETED 误推进；
- AUTO 不触发教程/重型 maintenance。

### S1 Gate

~~~text
NEGATIVE_INFORMATION_GATED
PRESENCE_ELIGIBILITY_SPLIT_GREEN
MASS_DELETION_GUARD_GREEN
REMOVAL_EVIDENCE_FROZEN
USER_EXCLUSION_PERSISTENT
VERSIONED_LYRICS_RESOURCE_GREEN
REVISION_AWARE_LYRICS_CACHE_GREEN
PLAYBACK_LEASE_GC_ATOMIC
DURABLE_PLAYLIST_FOLLOWUP_GREEN
OUTBOX_ACTION_INVALIDATION_FROZEN
AUTO_EMPTY_QUEUE_NO_BOOTSTRAP
AUTO_MANUAL_UI_FIELDS_ISOLATED
AUTO_GLOBAL_CACHE_CLEAR_DISABLED
AUTO_GLOBAL_LYRICS_REPROBE_DISABLED
LOUDNESS_REBASE_CONTRACT_GREEN
CURRENT_ORPHAN_CONTRACT_GREEN
TRANSIENT_SELECTION_SELF_HEALS
LOCAL_SOURCE_BOUNDARY_GREEN
AUTO_SIDE_EFFECT_POLICY_GREEN
~~~

S1 完成前仍不得接 observer。

---

## S2 — Detector Shadow Mode

> 实施状态（2026-09-06）：**自动化 Shadow Gate 已通过；真实设备批量事件观察待执行。**
>
> 已接入前台 Audio/Files observer、media-scanner/storage broadcast、foreground catch-up、
> FOLDER 前台 5 分钟 SAF periodic verify。所有信号只进入 scheduler；当前
> `executeScheduled(AUTO_SYNC)` 仍为零 scanner / 零 store / 零 publication 的 protocol-only 分支。
> Shadow diagnostics 每个实际 pass 只记录一行汇总：dirtySequence、coalesced event count、
> cause counts 与 wake reason（trailing debounce / max debounce / cooldown / in-pass follow-up）。

### 目标

接真实 dirty signal，但**绝不扫描/提交曲库**。

### 接入

- MediaStore Audio observer；
- MediaStore Files observer；
- media scanner/storage signal；
- Process foreground/background；
- SAF periodic verify scheduler（只记录 would-run）。

### 记录

- reason；
- dirtySequence；
- debounce/cooldown；
- coalesced event count；
- scheduled/actual pass；
- pending follow-up；
- starvation/max-debounce；
- foreground catch-up。

### 测试/实机观察

- 复制 1 / 100 / 1000 首；
- 连续 tag 编辑；
- 单独改 LRC；
- 删除/恢复；
- 持续前台修改 SAF；
- 后台修改后回来；
- cooldown 内重复事件；
- “pass 模拟运行中”继续收到 dirty；
- provider 高频噪音。

### S2 Gate

证明：

- 事件会合并；
- 不会永久 debounce；
- cooldown 不丢事件；
- in-pass dirty 会补跑；
- observer 生命周期不泄露；
- 没有任何真实 library side effect。

---

## S3 — DEVICE Delta

### 目标

DEVICE source 完成真正 generation delta，并在开启真实 AUTO 前通过 shadow + 10k + 播放共存。

### 实现

1. per-volume version/generation checkpoint；
2. aggregate identity 验证；
3. Audio delta；
4. Files fallback APE/DSD delta；
5. sidecar inventory；
6. Presence + Eligibility inventory；
7. `IS_PENDING / IS_TRASHED` 实际 projection 与 eligibility/removal 映射；
8. Audio/Files delta 与 current catalog 的跨通道 dedupe；
9. removal reason/evidence + MassDeletionGuard；
10. object pre/post fingerprint validation；
11. AUTO 专用 `AutoProbeDecision`，maintenance-only reuse miss 不触发全库 probe；
12. RetryLedger + playback-current-object defer；
13. PlaybackIoGuard + playback-active heavy probe parallelism 上限；
14. 局部 folder identity/casing reconciliation；
15. no-op short circuit；
16. source/version coverage contradiction detection。

### Shadow Gate

Delta canonical snapshot 与隔离 Full/canonical expected 对照：

- members；
- identity；
- metadata；
- lyrics signatures；
- filter reasons；
- removals。

### 性能 / Playback IO Gate（开启真实 AUTO 前）

基线：

> 10,000 songs + 每首完整逐字歌词 + 8 GB 设备/等价可审查内存测试。

#### PlaybackIoGuard

真实 AUTO 开启前必须有 playback-aware probe policy。

当存在 active playback instance 时：

- AUTO 可以做轻量 MediaStore/SAF metadata Presence query；
- **AUTO 不得重新打开/重 probe 当前正在播放的 audio URI/stable object**；
- 当前 object 的 tag/embedded-lyrics/deep metadata refresh 延后，进入 RetryLedger / `DEFER_UNTIL_PLAYBACK_RELEASE` 等价状态；
- 与当前媒体共享同一物理对象/fd 的 sidecar/resource 不做后台重读；
- 对 serialized SAF/provider、USB/高敏感 IO source，如果即使探测其他对象也会抢占当前播放通道，则该 source 的 heavy probe 整体延后；
- 当前 item 结束/切走后 scheduler 主动唤醒 deferred objects。

第一版真实 AUTO 的保守默认：

~~~text
playback active:
  AUTO heavy probe parallelism = 1
~~~

不得直接沿用 Full Scan 的 `PROBE_PARALLELISM = 8`。

idle 时可以使用经 S3 profiler 验证的更高并发，但不得超过当前已验证上界。S5 可以基于证据调整；S3 不能把“播放感知并发”第一次留到 S5。

LoudnessScanManager 与 AUTO 同样受 §16.1.3 的 object-IO coordination 约束。

必须确认：

- lyrics payload 仍有界；
- retry/patch/inventory 不把歌词全量常驻；
- no-op AUTO 不进入 full publication；
- 少量 changed 不全量 probe；
- 当前播放 object 收到 dirty 时不发生第二次 audio open/deep probe；
- 播放中 heavy-probe 并发符合上限；
- 播放中运行 delta 无可感知切歌/进度/USB 独占卡顿；
- 1000 首批量复制 scheduler/IO 不失控。

通过后才允许：

> DEVICE source 启用真实 AUTO。

---

## S4 — SAF Fast Verify

### 目标

保留 SAF 真 source，完成 metadata full walk + changed-only probe + independent compensation。

### 实现

1. tree metadata Presence inventory；
2. subtree completeness；
3. Eligibility 与 fingerprint reliability；
4. AUTO 专用 `AutoProbeDecision`：
   - reliable fingerprint changed；
   - sidecar changed；
   - explicit RetryLedger item；
   - `unknownFingerprintVerifyDue`；
5. changed/new-only probe；
6. current playback object / serialized provider 的 PlaybackIoGuard；必要时连 metadata walk/query 也限速/延后；
7. LRC/TTML patch；
8. video cover / **affected-directory** MV rematch；
9. periodic verify + UNKNOWN deep-verify budget；
10. provider discovery backoff；
11. no-op short circuit；
12. object post-probe validation（provider 能支持时）。

### Shadow Gate

FAST 与隔离 Full/canonical fixture 比较：

- membership；
- folder identity；
- metadata；
- sidecars；
- video/MV relations；
- removal reasons。

### 性能 / Playback IO Gate（开启真实 AUTO 前）

同样执行 10k + 完整逐字歌词 + 播放共存，并**完整继承 S3 的 PlaybackIoGuard**：

- active playback 时不重开当前 media/document object；
- serialized DocumentsProvider 若无法与播放安全并行，heavy probe 整体延后；
- playback active 时 AUTO heavy probe parallelism 初始上限仍为 1；
- current item 结束后自动唤醒 deferred SAF objects；
- 不允许等到 S5 才第一次决定 playback-aware parallelism。

特别记录：

- metadata walk wall time；
- provider query count；
- unchanged probe count；
- UNKNOWN fingerprint probe/verify 成本；
- playback IO latency；
- deferred-current-object 数量与平均延迟；
- periodic verify 对功耗/前台体验的影响。

通过后才允许：

> FOLDER/SAF source 启用真实 AUTO。

---

## S5 — 性能调优与 Poweramp 风格二阶段优化

S5 不是第一次验证“能不能用”，只做调优。

可调：

- debounce；
- max debounce；
- cooldown；
- consecutive AUTO budget；
- probe parallelism；
- retry backoff；
- SAF verify 周期；
- inventory batching。

只有 profiler 明确证明 SAF traversal 成为主要瓶颈，才允许研究：

~~~text
FolderNode / directory fingerprint
→ reliable provider subset
→ subtree skip
~~~

必须另写正确性证明：

- 哪些 provider 的目录时间可信；
- 内容修改是否会传播；
- fallback/UNKNOWN 如何降级；
- deletion 如何保持完整性。

### S5 第一轮 tuning freeze（2026-09-08）

S3/S4 profiler + r5 real-auto Gate 之后，第一轮 S5 **不调整任何已冻结数值参数**。当前决策：

| 可调项 | 当前值 / policy | S5 决策 |
|---|---|---|
| debounce | 1.5 s | keep |
| max debounce | 5 s | keep |
| cooldown | 60 s | keep |
| consecutive AUTO budget | 最多 1 次 immediate `IN_PASS_FOLLOW_UP`，持续 dirty 随后重新进入 debounce/max-debounce | keep |
| AUTO heavy probe parallelism | DEVICE=1，SAF=1 | keep；没有播放共存证据支持提高 |
| retry backoff | provider 30 s→5 min；SAF object retry 30 s→30 min | keep 数值；补 scheduler-owned deadline wake |
| SAF verify | foreground periodic=5 min；slow COMPLETE provider cadence=15 min；UNKNOWN strong verify=24 h | keep |
| inventory batching | 不新增 provider/subtree batching override | keep；provider traversal 语义优先 |

第一轮唯一有证据的实现调整是 **scheduler-owned delayed retry wake**：

- provider failure breaker / persisted SAF RetryLedger 产生 deadline 后，不再只能等待下一次 observer callback 或 5 分钟 periodic verify；
- deadline timer 独立于普通 dirty cooldown，不阻塞 deadline 前的新文件/新 dirty；
- timer 绑定 `SourceIdentityKey + activationEpoch`，`cancelAll()` / source switch / release 会取消旧 wake；
- deadline 落在后台时不启动 provider/audio IO；durable retry debt 保留，下一次 `FOREGROUND_CATCH_UP` 重新核对；
- 前台到期注入内部 `SAF_RETRY_DUE`，planner 仍按当前 wall-clock、source/config/activation 和 RetryLedger 重新判断，timer 本身不直接消费债务；
- provider breaker 的下一档仍由已有 30 s→5 min 指数退避决定，没有新热循环。

真机 Gate：QA APK replace 后 Termux provider 进入已知 cold-state，request=1 为 `FOREGROUND_CATCH_UP / cannot-read-tree / backoffMs=30000`；期间不注入任何外部 signal，30 s 后 request=2 自动出现 `wake=RETRY_DUE / causes={SAF_RETRY_DUE=1}`，仍不可读时继续 fail-closed 并进入 `backoffMs=60000`。随后停止 app、经 DocumentsUI 重新选择同一隔离 tree，恢复 `entries=2 / COMPLETE`；65→70 s ordinary scheduler payload Gate 再次得到 `changed=1 / probeResolved=1 / publicationCommitted=true`，scheduler authority update 约 1.839 s，cold cache 与 Full oracle 都为 70 s，fixture SHA-256 恢复到基线。证据在 `.scratch/library-auto-sync-s5/20260908-delayed-retry/`。

**通用 directory-mtime subtree skip 本轮明确拒绝。** 当前 SAF contract 没有“目录 `lastModified` 必须聚合传播任意后代内容修改”的可依赖语义；本机最小实证也显示，仅覆盖 `root/nested/child.txt` 内容后 child mtime 前进，而 `nested` 和 `root` 两级目录 mtime 完全不变。因此若按目录 mtime 跳过 subtree，会漏掉“不增删目录项、只改 audio tag / embedded lyrics / payload”的变化。除非未来对某个 provider subset 另有可复验的后代传播正确性证明，否则不得实现 subtree skip。证据在 `.scratch/library-auto-sync-s5/20260908-directory-mtime/evidence.txt`。

---

# 32. 验收矩阵

| 场景 | 必须结果 |
|---|---|
| 单首 DEVICE 新增 | 自动加入 |
| DEVICE 1000 首批量新增 | 事件合并，完整加入，不重复全库 probe |
| active=10k，AUTO query success 但 Presence=0 | 不 publish empty、不删歌单/queue；进入 reconcile-needed |
| AUTO 单轮异常大 removal | 第一轮 quarantine，不直接 destructive publish |
| MEDIA_MOUNTED / scanner finished 后暂时空 inventory | 信号只触发核对，不提高 deletion 可信度 |
| 单首 tag 修改 | 受影响 metadata 更新；existing dateAddedMs 保持首次入库时间 |
| global lyrics retry + 单首 dirty | 不全库 forceRefreshLyrics，只处理 changed/retry object |
| 音频不变、LRC 修改 | 对应歌词发布新 revision |
| 当前播放歌词 r1，library promote r2 | library/current playback/通知/桌面歌词实时切到 r2；不重建播放队列/timeline |
| LRC channel 失败 | 旧歌词 head 保留 |
| LRC 确认删除 | 对应 active slot head 清空，leased old revision 不立即 GC |
| APE/DSD Files query 失败 | 不误删 |
| Audio + Files 同时/先后看到同一文件 | delta dedupe 后只保留一个 authoritative object |
| IS_PENDING=1 / 文件复制中 | 不 upsert 半成品、不确认 missing；稳定后再处理 |
| IS_TRASHED=1 | TRASHED，不冒充 CONFIRMED_MISSING |
| MediaStore version 重建 | 旧 checkpoint 作废 |
| 多 volume 变化 | partition coverage 正确 |
| SD 卡临时离线 | 旧歌保留，不清歌单 |
| TRASHED | 曲库按产品规则隐藏/移除，歌单引用保留 |
| 恢复 TRASHED | playlist reference 恢复可解析 |
| FILTERED_OUT | 曲库隐藏，playlist 不删 |
| user remove 但文件仍在 | 不被 AUTO/Full 扫回 |
| clear library | foreground/observer 不复活 |
| 权限暂失 | snapshot 保留，非 CLEARED_BY_USER |
| source A→B，B 失败 | A 保持 active |
| source A→B，B 成功 | 原子切换，无 A/B 混合 |
| scan 中 source switch | 旧 source 无晚到副作用 |
| scan 中用户 remove | AUTO rebase 不复活 |
| Room 成功后进程退出 | outbox 启动后继续 |
| outbox 晚到但 object re-add | 不删新引用 |
| AUTO 新增歌曲时 queue 为空 | queue 保持空，不 bootstrap 整库 |
| 当前曲被物理删除 | 不 setQueue 整库；session orphan 契约生效 |
| 当前 orphan 暂停/repeat-one | 不自动结束 |
| orphan 切歌 | lease 释放，可 GC |
| 当前曲 tag 更新 | presentation 可更新，source 不 hot-swap |
| 当前曲 ReplayGain/loudness 更新 | 当前 AppliedReplayGain 不跳变；下一次 playback instance 才采用新值 |
| 当前 playback URI 收到 dirty | 只做轻量 presence；不重开当前 audio 做 heavy probe |
| playback active AUTO heavy probe | 并发上限符合 gate，第一版为 1 |
| artwork cache/coverColor/metadata version maintenance miss | 普通 AUTO dirty 不因此把对象/全库变成 probe candidate |
| AUTO pass 开始 | 不调用全局 clearTransientCache，不取消无关 poster prefetch |
| AUTO 成功更新一首 | 不写 lastScanSyncSummary、不弹“扫描完成” |
| AUTO provider/storage failure | 不覆盖/清除手动 lastScanError；走内部 AccessState/diagnostic |
| 用户多选 A/B/C，AUTO 删除 B | selection 自愈为 A/C，批量操作不碰 stale B |
| local AUTO + mixed remote queue | remote catalog/queue item 原样保留 |
| 当前 MP4 同 URI 替换 | poster revision miss，不用旧 poster |
| 新同名 audio 导致 MV 歧义 | 原唯一关系失效 |
| MV group inventory PARTIAL | 保留旧关系，不下唯一结论 |
| dirty 但无实际变化 | 不 commit snapshot、不 rebuild browse、不 queue revision；diagnostic 聚合为 no-op summary |
| cooldown 中 dirty | cooldown 后必跑 |
| AUTO 中 dirty | pass 后立即 follow-up |
| 持续通知 | max debounce 后仍会执行 |
| retry 到期无事件 | scheduler 主动醒 |
| provider 长期失败 | discovery backoff + 日志限频，不高频轰炸 |
| 10k 全逐字歌词 | 内存/批次有界 |
| 10k noisy no-op AUTO | diagnostics 不退化为逐歌曲/逐 callback verbose log |
| 10k 播放中 DEVICE AUTO | 不 probe 当前播放对象，无可感知播放回归 |
| 10k 播放中 SAF AUTO | 遵守 provider/PlaybackIoGuard，无可感知播放回归 |

---

# 33. 观测与诊断

新增日志建议统一前缀：

~~~text
LibraryAutoSync
LibraryScheduler
LibraryDiscovery
LibraryPublication
LibraryOutbox
LibraryRetry
LibraryMembership
~~~

AUTO diagnostics 不能继续按“一次用户手动扫描 = 一组完整 verbose log”输出，否则 noisy observer 会制造大量重复日志。

记录策略：

### no-op AUTO

默认只打一条聚合 summary：

~~~text
cause / coalescedEventCount / source / completeness /
candidateCount / changed=0 / retryCount /
discoveryMs / nextWakeReason
~~~

不逐歌曲、不逐 query 打正常路径日志。

### 有变化 / 有异常

可以追加：

- requestSequence；
- generation；
- sourceIdentityKey；
- activationEpoch；
- cause；
- dirty start/end；
- discovery partitions + completeness；
- candidates/changed/retry/removal counts；
- probe counts；
- mass-deletion guard 是否触发；
- deferred-current-playback-object counts；
- rebase retry 次数；
- Room commit 耗时；
- publication 耗时；
- queue reconcile plan；
- outbox insert/consume/obsolete 数；
- 下一次 wake 原因/时间。

### 限频/聚合

- 同 source + 同 failureKind 的 discovery/provider error 必须 rate-limit / aggregate；
- observer burst 不允许每个 callback 写一条完整 scan log；
- repeated no-op 在诊断窗口内可聚合为 count；
- 单 object retry 只在状态/退避级别变化时记录，不每轮重复刷屏；
- 10k 验收时日志量必须是 O(pass/change summary)，不能退化成 O(all songs) 正常日志。

禁止日志保存整段歌词、隐私路径内容或无限增长 payload。

---

# 34. 文件/模块预计落点

具体类名可在 ADR 后微调，但 owner 不应变化。

| 领域 | 预计位置 |
|---|---|
| operation/scheduler | `data/library/`，归 `MusicLibraryBacking` 所有 |
| publication gate/rebase | `MusicLibraryBacking / LibraryScanOrchestrator / LibraryCatalogPublisher` |
| lifecycle/source identity + activation | `LibraryFolderBinding` 重构为更通用 library source owner，或新增同层 coordinator |
| checkpoint/retry/outbox/tombstone | `data/local` Room + `LibraryStore` 边界 |
| scan resource staging | `data/local` staging table/DAO + `LibraryStore`；复用现有 pending 思路但不沿用旧语义 |
| versioned lyrics/head | `LibraryRepository / SongLyricsDao / SongEntity migrations` |
| playback resource lease/GC | `playback` owner + `data/local` lease/resource DAO |
| DEVICE delta | `data/scanner/MediaStoreScanner` 或拆出的 DEVICE discovery helper |
| SAF fast | `data/scanner/FolderScanner` 或拆出的 inventory helper |
| lyrics slot patch | `LibraryRepository / SongLyricsDao` |
| queue policy | `LibraryQueueSyncPolicy / LibraryPlaybackQueueCoordinator` |
| playback source/presentation split | `PlaybackRuntime` |
| playlist outbox consumer | `PlaylistStore / PlaylistRepository/Dao` |
| video revision | `Song / SongEntity / codec / poster store / matcher` |
| observer lifecycle | MainViewModel/MusicLibrary 生命周期附近；不得创建第二个 MusicLibrary |

---

# 35. 实施前 ADR 必须冻结的条目

真正开始 S0 前，新增 ADR 应至少明确：

1. 8 条总原则；
2. OperationMode / Cause / Token；
3. dirtySequence 不 invalidate AUTO；
4. in-pass dirty 立即 follow-up，不等普通 cooldown；
5. TARGETED/ARTWORK 不 cancel 当前 AUTO；
6. LibraryIntent / Access / SourceIdentityKey / activationEpoch 分层；
7. source switch 两阶段提交；
8. pending source 与所有 pre-publication scan resources 必须 operation-scoped staging；
9. checkpoint/retry/outbox/tombstone 属于受保护局部写；
10. **checkpoint 越过失败对象时必须与 RetryLedger 同事务**；
11. clear 的 action-specific 语义：保留 USER_EXCLUDED，不一刀切删除 durable outbox/leased resource；
12. partition completeness；
13. Presence inventory 与 Eligibility inventory 分离；
14. tri-state negative-information patch；
15. removal reason/evidence；
16. USER_EXCLUDED tombstone 绑定长期 source identity，不绑定 activation/config；
17. gate 外 preparation 严格纯化，presentationRevision 冲突规则；
18. scanExecutionMutex 现有调用者迁移清单与统一锁顺序；
19. optimistic final rebase；
20. **final validation 后持有 Publication gate 直至 Room commit + memory adopt；Room commit 为 durable linearization point**；
21. crash-after-Room-before-memory 的重启恢复语义；
22. scanner/runtime/user/version-bound 字段 ownership；
23. LibraryChangeSet 只做 transient notification；
24. durable outbox 的“复核+副作用+ack”原子事务与锁顺序；
25. outbox action-specific invalidation / supersede；
26. queue 每次基于 current state 重算；
27. versioned lyrics resource + active slot head；
28. playback resource set / resource lease / GC 原子边界与启动顺序；
29. current orphan 契约；
30. presentation metadata 与 playback source version 分离；
31. lastFullScanAt 与 lastAutoSyncAt 分离；
32. Retry existing/new publication 与不可静默过期规则；
33. Shadow 无真实副作用；
34. S3/S4 开启真实 AUTO 前各自必须过 10k + playback 性能门。

ADR 未接受，不开始 S0。

---

# 36. 实施禁止项

在对应 Gate 前禁止：

- 用 WorkManager 直接写 library DB；
- Application 中新建第二个 MusicLibrary；
- observer 直接调用 scanner；
- observer 直接调用 `launchRescan()`；
- 依靠 Job.cancel 防旧任务副作用；
- gate 外 `prepareLibrarySongs` / browse preparation 写 preferences、Room、cache 或 Compose state；
- query failure → emptyList；
- PARTIAL discovery 下做 deletion/field clear；
- AUTO 在 previous active 非空时把一次成功空 Presence 直接发布为空库；
- AUTO 对异常大 removal batch 不经过 MassDeletionGuard/quarantine 就 destructive publish；
- 把 `MEDIA_SCANNER_FINISHED / MEDIA_MOUNTED` 当成 deletion 完整性的证明；
- AUTO/TARGETED 因 global parser/retry 打开全库 `forceRefreshLyrics`；
- AUTO 调用全局 `clearTransientCache()` 或取消无关 poster prefetch；
- AUTO 写/清 `lastScanSyncSummary / lastScanError` 这类手动扫描 UI 状态；
- AUTO 因 current queue 为空就 bootstrap/setQueue 整个 library；
- AUTO 因 artwork cache/coverColor/metadataScanVersion 等 maintenance-only miss 把对象判成 changed；
- AUTO heavy probe 重新打开当前正在播放的 media object；
- playback active 时原样使用 Full Scan 的 `PROBE_PARALLELISM = 8`；
- 未投影/无法确认 `IS_PENDING / IS_TRASHED` 时仍下 destructive missing 结论；
- Audio/Files delta 不经过与 Full 等价的跨通道 dedupe 就直接 upsert；
- 用 songId-only lyrics cache 破坏 playback resource revision lease；
- AUTO metadata refresh 热替换当前 playback 的 ReplayGain/loudness/AppliedReplayGain；
- AUTO rebase 用空 loudness 覆盖 fingerprint 相同的最新用户响度分析；
- local AUTO 删除/重建 RemoteTrackEntity 或 remote queue entries；
- noisy AUTO 每个 observer callback / 每首 unchanged song 都输出 verbose scan log；

- 用经过 Eligibility 过滤的 candidate set 单独证明 CONFIRMED_MISSING；
- checkpoint 推进但 retry/backoff 另一个事务稍后再写；
- 未解决 retry 仅因 TTL 到期静默删除；
- pending source/最终未提交 operation 的歌词 batch 直接写 active resource head；
- source switch/clear 一律 DELETE ALL outbox；
- 用裸 removedIds 清 playlist；
- outbox 先复核、事务外删 playlist、最后再 ack；
- 用内存 ChangeSet 作为唯一可靠消费；
- AUTO 删除后 `setQueue(整个曲库)`；
- 用 `(songId, slot)` 覆盖式歌词表声称能冻结当前 playback 的旧歌词 revision；
- GC 在 playback restoration/lease 建立完成前运行；
- AUTO 推进全局 lyrics parser version；
- AUTO 触发 album-art prune / bulk poster prefetch；
- 用 scan-start snapshot 作为 final merge 基线；
- Room commit 后允许 token/source invalidation 插进 memory adopt 之前；
- fingerprint 变化自动解除 USER_EXCLUDED；
- 重新授权/切回同一 source 时换 identity 绕过 USER_EXCLUDED；
- 直接用生产 Full Scanner 做有副作用的 Shadow 对照；
- S5 才第一次验证 10k/播放共存；
- 未证明 provider 语义就做 subtree mtime skip。

---

# 37. 完成定义

此功能只有在以下全部成立后才可称为“自动曲库同步完成”：

### 一致性

- clear/source switch/config change 后无旧任务复活；
- partial/unavailable 不误删、不误清字段；
- user exclusion 不被 AUTO/Full 复活；
- checkpoint/retry/outbox 均可进程恢复；
- AUTO 与 user mutation 冲突有确定赢家。

### 功能

- DEVICE 新增/删除/修改/sidecar 可自动同步；
- SAF 在无 MediaStore signal 时仍有周期补偿；
- 删除/过滤/回收站/source replace 有不同 membership 语义；
- playlist/queue/current orphan 行为符合契约；
- MV/video cover 关联不会因 partial inventory 被误重算。

### 性能

- no-op AUTO 不走 full publication；
- 10k + 完整逐字歌词内存有界；
- S3 DEVICE 与 S4 SAF 各自在启用真实 AUTO 前通过播放共存测试；
- Retry/outbox 持久工作队列有界且可分页；DEVICE/SAF source authority inventory 为 pass-scoped。SAF 按 §2.2/§19.2 保持必要的 O(N) COMPLETE metadata walk，10k memory Gate 已通过；围绕 authority inventory 的 retry/probe/post-validation/cleanup working set 必须按固定 budget 有界，不允许跨 pass 常驻额外全量副本；
- 未出现因 AUTO 引入的手动切歌缓冲/进度延迟回归。

### 可恢复

- 用户 Full Scan 始终能作为明确 recovery；
- AUTO 可通过 feature gate/source gate 单独关闭而不破坏手动扫描；
- 未通过的 source 不自动启用真实 AUTO。

---

# 38. 推荐提交/审查节奏

建议每个阶段至少独立提交，并在阶段结束做 exact-diff/测试审查：

~~~text
DOC/ADR
→ S0 scheduler + lifecycle + publication protocol
→ S0 tests
→ S1 patch/completeness/removal/tombstone
→ S1 queue/playlist/orphan/video
→ S1 tests
→ S2 detector shadow
→ S3 DEVICE shadow
→ S3 DEVICE real-auto gate
→ S4 SAF shadow
→ S4 SAF real-auto gate
→ S5 tuning
~~~

不要把 S0～S4 合成一次大改。

每个会影响 shared state 的提交都必须附：

- token owner；
- await/IO 点；
- real side effects；
- synchronization seam；
- stale-operation deterministic test。

---

# 39. 当前下一步

实施进度（更新至 2026-09-09；以下早期阶段条目保留过程证据，后续条目为当前 superseding 状态）：

1. 文档/ADR：已完成并作为当前执行契约；
2. **S0：已实施，publication/scheduler/lifecycle 交错测试通过；**
3. **S1：安全基础已实施，核心回归通过；**
   - completeness / Presence / Eligibility；
   - mass-deletion quarantine；
   - manual exclusion/tombstone；
   - reason-aware queue / durable playlist followup；
   - AUTO publication / checkpoint / retry / outbox 原子边界；
   - playback orphan / video revision 等契约；
4. **S2：自动化 Shadow Gate 已通过；真实设备事件观察已由后续 S3 DEVICE / S4 SAF 真机 Gate 补齐；**
5. **S3 DEVICE 已完成并启用 real-auto；以下保留 shadow→readiness→production Gate 的过程证据：**
   - S3 1～16 的基础 seam 已落：per-volume generation/version、Audio/Files/sidecar delta、Presence/Eligibility、pending/trashed capability、跨通道 dedupe、removal/MassDeletionGuard、object pre/post observation、AUTO probe decision、RetryLedger、PlaybackIoGuard、folder physical identity/casing、no-op short circuit、source/version/capability contradiction；
   - generation 完全未变化时已在 delta/sidecar/Presence 查询前短路；Presence destructive absence 只有在列可读、hidden-row inclusion 语义已知、permission/volume scope 完整且 partition coverage complete 时才成立，能力不足时 shadow cursor 不前进；
   - canonical coverage tracker 之外，side-effect-free canonical projector + 跨多轮 projection tracker 已接入真实 DEVICE Shadow 路径；生产 composition 注入 Android exact-draft/revision reader 与 read-only object probe runtime，probe 前后都做 object revision validation，且 probe 前再次采样 playback lease；
   - Shadow object probe 已与 Full Scanner 的 quick/deep 选择、APE/DSD Files fallback、external/embedded lyrics、ReplayGain、DEVICE video/MV 清空语义对齐；任何 pre/post revision race、歌词读取失败、partial sidecar inventory 或 playback race 都保持 unresolved 并 hold shadow cursor，不写 production checkpoint；
   - filter/removal Gate 已新增独立 membership audit：区分 ELIGIBLE / PENDING_KEEP / PENDING_NEW_SUPPRESSED / FILTERED_OUT / TRASHED / CONFIRMED_MISSING / UNKNOWN_KEEP，并校验 removal reason + evidence revision + source identity + songId；普通 Full 暂时隐藏既有 pending row 时，仅对 PENDING_KEEP 做 canonical expected normalization，不污染下一轮 Full baseline；
   - 自动化 mixed canonical Gate 已通过：同轮 metadata heavy probe、新增歌曲、LRC 修改、FILTERED、TRASHED、CONFIRMED_MISSING、PENDING_KEEP、unchanged 混合场景最终 projected-vs-Full `fullyEquivalent=true`，unresolved/quarantine 清零；
   - playback defer 闭环单测已通过：当前播放对象 dirty → 本轮 defer 且 cursor 不前进 → playback lease release 触发 `PLAYBACK_IO_RELEASE` scheduler wake → 下一轮 probe READY 后 cursor 才 accept；heavy parallelism 继续保守为 1；
   - unit-scale 10k gate 已通过：10,000 首 catalog 仅 3 首 dirty 时只产生 3 个 object work，active playback 当前对象 defer、其余 2 个 ready；
   - lyrics memory-shape Gate 已通过：`LYRICS_SCAN_BATCH_SIZE=6`；10,000 首逐字 TTML fixture（每首 20 行 × 8 tokens × 3 slots）只允许单批最多 6 首 payload 在途，retained 10,000 首 catalog 的 `lyricsDocument.lines` 总数为 0；另有 6 首 × 400 行 × 12 tokens × 3 slots 的 full-length 单批 fixture。该证据证明 payload lifetime/retained shape 有界，但不等价于 8 GB 真机 RSS/GC 峰值测量；
   - 1000-copy 离线规模 Gate 已通过：1,000 个 dirty signal 合并为单一 AUTO pass；10,000 首既有 catalog 一次加入 1,000 个新对象时仅生成 1,000 个 object work，heavy parallelism=1，canonical projection 一次扩展到 11,000 首且 unresolved/quarantine 为空；
   - 1000-copy **真实 MediaStore/存储 IO Gate 已通过**（Android 12 / API 31，约 12 GB RAM 设备）：最初实测暴露 scheduler immediate-follow-up 热链，修复前 1,000-copy 产生 143 个 Shadow pass，其中 142 个为 `IN_PASS_FOLLOW_UP`；scheduler 现限制每个普通 AUTO pass 最多 1 次 immediate follow-up，follow-up 期间新 dirty 回到 debounce/max-debounce。修复后持续输入被压成有界批次；
   - 同一真实 1000-copy 又暴露 MediaStore provider finalize seam：新 Audio row 会先以 `IS_PENDING=1 / duration=NULL / is_music=NULL` 出现，旧逻辑会在 pending 阶段越过 shadow cursor，导致 1,000 个最终 eligible 对象仅 780 个被 probe。现新增 `DeviceEligibilityAuthority`，Audio / Files fallback / sidecar 的 pending row 一律标 `PROVIDER_PENDING_TRANSIENT`，provider type 尚未完成但扩展名明确是受支持音频时标 `PROVIDER_METADATA_TRANSIENT`；任何 transient row 存在时整轮在 heavy probe 前 hold cursor，不做 probe、不 accept cursor；
   - pending-aware 真机复测：550 首 Full baseline 后复制 1,000 个 65 秒 WAV；pending 阶段 21 次 Shadow pass 全部 `reason=provider-state-transient` 且 `probeExecuted=0`，pending 从 992 等值逐步下降到 10 后清零；最终 request=25 一次性得到 `audioRows=1000 / audioCandidates=1000 / probeReady=1000 / probeExecuted=1000 / probeIssues=0 / probeDeferred=0 / heavy parallelism=1`，request=26 为 0-row no-op；随后 isolated Full 得到 1,550 首，canonical coverage `changed=1000 / covered=1000 / uncovered=0 / fullyCovered=true`，projection compare `diff=0 / unresolvedObjects=0 / quarantined=0 / fullyEquivalent=true`，Gate 脚本正式 PASS；
   - 已新增 `scripts/run-library-auto-sync-s3-shadow-gate.ps1`：真机先 `Reset` 清空 Android logcat window，完成 Full baseline → mutation → Shadow → isolated Full 后再 `Evaluate`，自动保存原始 `MICA_DIAGNOSTICS` 与 JSON summary，并以 diff/unresolved/quarantine/fullyEquivalent 严格判 Gate；真机 playback 日志曾把 compare 从默认 logcat ring 挤掉，`Reset` 现会 best-effort 先把 logcat buffer 扩到 16 MiB，再清空窗口，失败只告警不阻断 Gate；
   - 已新增 `scripts/capture-library-auto-sync-s3-device-perf.ps1`：真机 playback/10k/1000-copy 场景下按间隔采 `dumpsys meminfo`，记录 PSS/RSS 峰值与 p95、PID restart、FATAL/ANR 线索和 `MICA_DIAGNOSTICS` 原始日志；真机首次运行发现 PowerShell `$PID` 只读变量与脚本局部 `$pid` 大小写不敏感冲突，已改为 `$processId` 并通过 parser smoke。脚本只输出 evidence，不擅自把“无可感知播放卡顿/USB 独占异常”自动判 PASS；
   - **真实 DEVICE isolated Full oracle 已完成首轮 Gate**（Android 12 / API 31，`device|mediastore:external`）：550 首 Full baseline 后已验证 eligible 新增歌曲、LRC sidecar-only、confirmed-missing removal、IS_PENDING、IS_TRASHED、APE Audio/Files 双通道 dedupe、current-object revision + ENDED lease release；对应 `Evaluate` 均得到 `diff=0 / unresolved=0 / quarantine=0 / fullyEquivalent=true`。新增 eligible 音频命中 `probeReady=1 / probeExecuted=1`；sidecar-only 命中 `lyricsSignatureChanges=1 / probeExecuted=0`；confirmed-missing 命中 `membershipChanges=1 / removalMissing=1`；pending 命中 `membershipPendingKeep=1` 且 Full 侧 `normalizedPendingKeep=1`；trashed 命中 `removalTrashed=1`；27.8 MB APE 在该 provider 同时出现在 Audio + Files，Shadow 为 `audioRows=1 / fileRows=1` 但只生成 1 个 candidate/1 次 probe，Full oracle 等价；
   - playback coexistence 真机功能链已验证：current object PLAYING 时 revision dirty 会 `probeDeferred=1 / probeExecuted=0 / cursor-held`；同一对象手动 PAUSED（MediaSession `state=2`）仍保持 defer；自然 ENDED 后 `PLAYBACK_IO_RELEASE` 唤醒同一 held delta，下一轮 `probeReady=1 / probeExecuted=1`。35 秒尾段性能采样覆盖播放中→ENDED→release→probe，峰值 PSS 327160 KB、RSS 406628 KB，未观察到 PID restart、FATAL EXCEPTION 或 ANR；1000-copy pending-aware 复测全过程保持 MediaSession `state=3`，5 分钟采样峰值 PSS 402349 KB、RSS 517268 KB，同样无 PID restart、FATAL EXCEPTION 或 ANR；
   - Full 扫描封面取色内存缺陷已收口：`CoverColorExtractor.fromUri()` 改为两次 reopen 的流式 bounds/sample decode，不再 `readBytes()` 整体物化 URI；`resolveCoverColor()` 同时移除把 audio `mediaUri` 当图片 fallback 的无效路径，只接受 embedded/store artwork。`CoverColorExtractorTest.streamedDecodeDoesNotReadWholeUriPayload` 覆盖大尾部 payload 不被整体读取；
   - 真 10k + 完整逐字 TTML 压力先后暴露两类问题。第一类是 `LibraryShadowObservationStamp.catalogRevision` 过宽：长 heavy-probe pass 中自然切歌发布 playCount/totalListenSeconds/lastPlayedAt，旧逻辑会把数分钟 probe 整轮 `stale-drop-post-analysis`；现已新增独立 `shadowAuthorityRevision`，只有成员、Media identity、文件指纹、tag、歌词签名等 canonical publication 才推进，play stats、动态封面色、loudness 不推进，相关 focused regression 已通过。样本完整性审计又发现生成期恰有一个 0 字节 TTML 与一个 44 字节 WAV；坏原件已留证后用同 SHA 模板覆盖修复（未删除文件）。修复发生在 request=9 已开始后，因此 request=9 按契约以 `PRE_OBSERVATION_CHANGED=1` fail-closed；request=10 随后在稳定 revision 上完成 9,339/9,339 probe、`probeIssues=0`。第一次 isolated Full oracle 又暴露第二类问题：旧 `readUpToCompat(10 MiB + 1)` 会为只有约 8 KiB 的 TTML 直接预分配整个 10 MiB buffer，在 256 MiB Java heap 下最终 fatal OOM。现已将其改为 8 KiB chunk 流式增长并保持相同 read-limit 语义，`ExternalLyricsReaderTest`、`BinaryParserGoldenTest`、`BinaryParserFuzzTest` 均通过；手机端复用原安装 APK 中未修改的 native .so 构建，临时 jniLibs 输入随后整体移入 Termux 回收目录，并用与原安装包证书完全一致的桌面 debug keystore 原地升级，保留应用数据。新 PID 3441 重新 Full anchor 后 request=2 得到 10,550 首、`technicalFailed=0`、`full-anchor accepted`、canonical/projection baseline 均 10,550、`performScan end error=false`。随后再次 touch 10,000 WAV，并在 MediaSession `state=3` 持续播放下执行 Shadow：request=4 为 561/561、request=5 为 5,610/5,610、request=6 为 3,829/3,829，三批合计恰好 10,000 个 audio candidate，全部 `probeIssues=0 / quarantine=none / presence=COMPLETE / probeParallelism=1`，期间跨多次自然切歌仍无 stale-drop；request=7 仅消费 4 个尾部 Files dirty，最终 0-row / 0-probe 干净收敛。暂停播放后 isolated Full request=8 得到 `scannerResult=10550 / technicalFailed=0`，canonical coverage `changed=10000 / covered=10000 / uncovered=0 / fullyCovered=true`，projection compare `diff=0 / unresolvedObjects=0 / quarantined=0 / fullyEquivalent=true`，`performScan end error=false`，因此 **10k + 完整逐字 TTML + playback coexistence Shadow Gate 正式 PASS**。35 个一分钟采样覆盖播放与最终 oracle，峰值 PSS 457,771 KB、RSS 459,344 KB，新 PID 未观察到 FATAL/ANR；原始证据保存在 `.scratch/library-auto-sync-s3-device/20260907-oomfix-gate/`。Full 期间另发现旧 resolveCoverColor fallback 会把 audio mediaUri 交给 CoverColorExtractor.fromUri 后整流 readBytes，触发 78～157 MB 单次整音频读取及被捕获 OOM/重 GC。现已将 CoverColorExtractor.fromUri 改为两次 reopen 的 BitmapFactory.decodeStream：第一次仅读 bounds，第二次按 inSampleSize 流式采样，不再把整个 URI payload 读入 ByteArray；新增 `CoverColorExtractorTest.streamedDecodeDoesNotReadWholeUriPayload`，并与 ExternalLyricsReader/BinaryParser focused tests 一起重跑通过。同一台 10,550 首设备在 16:24:43～16:30:03 用新 PID 31844 完整 Full 真机复验：`scannerResult durMs=291845 songs=10550 technicalFailed=0`、`full-anchor accepted`、`performScan end durMs=319499 error=false`，该 PID 全窗口 OutOfMemoryError=0、FATAL=0、ANR=0、blocking GC Alloc=0，结束时 PSS 351,182 KB / RSS 355,232 KB；原始证据保存在 `.scratch/library-auto-sync-s3-device/20260907-coverstream-full-verify/`；
   - 1000-copy PASS 后把测试目录整体移入 `.MicaRecycle` 并让 MediaStore 旧路径失效；恢复阶段 1,000 个 removal 被 MassDeletionGuard 全部 quarantine（Full oracle 回到 550 首时 Shadow `quarantined=1000 / MEMBERSHIP unresolved`），未发生 destructive cursor accept，符合大比例删除第一轮必须隔离的契约；
   - **S3 DEVICE 10k / playback coexistence Shadow Gate 已通过**：这台 provider 对 APE/DSF/DFF 都同时暴露 Audio + Files，因此仍缺一个可物理构造的真实 Files-only provider/格式样本；1000 首真实 MediaStore/存储 IO 与 10,000 首完整逐字 TTML + playback coexistence 已在 Android 12 / API 31、约 12 GB RAM 设备 `22081212C` 上通过 projected-vs-Full oracle。**8 GB 真机 RSS/GC 条件已由用户在 2026-09-07 明确豁免（user-waived），后续不再作为 S3 阻塞 Gate；该项不是 PASS，也不得把 12 GB 结果表述为 8 GB 等价验证。** Files-only 样本缺失现在保留为兼容性证据缺口，而不是通过假定 provider 行为来换取 destructive authority：production `DeviceMediaStorePresenceCapability` 仍要求可证明的列语义、hidden-row inclusion、permission/volume scope 与 partition coverage；能力不足或矛盾时整轮 hold cursor / fail-closed，不把 UNKNOWN 当作 missing，也不推进越过该不确定窗口的 production generation cursor；
   - **S3 DEVICE real-auto readiness / enablement 已闭合（2026-09-09）**：DEVICE validated delta 现在与 SAF 共用最终 publication authority seam，但仍由 `DeviceAutoSyncPublicationPlanner` 单独形成 song/lyrics/membership/RetryLedger/checkpoint proposal。enablement 前补齐了以下交错 Gate：probe 前冻结 `backing.songs.toList()`，避免长 probe 的 scan-start 基线被后续可变 catalog 引用改写；probe 中 catalog mutation 通过 shadow authority revision stale-drop，不能复活局部删除；mass-deletion quarantine 在 shadow/readiness/ordinary scheduler 均禁止 cursor accept；full/external lyrics 双 staging 在 stale/cancel 下都清理 pending row；playback-active object 保持 defer，`PLAYBACK_IO_RELEASE` 后 ordinary scheduler 才允许真实 Room authority + memory adopt + cursor accept；exact retry re-observation 的 `Missing/Unavailable` 不再形成固定 30 s 热循环，而是沿同一 durable RetryLedger 做 30 s→60 s→120 s…最高 30 min 的有界指数退避，且 `Missing` 本身仍不等价于删除证据。最终 `executeScheduled(AUTO_SYNC)` 已把 `publishDeviceAuthority` 翻为 `true`，debug diagnostics 入口继续显式 `publishDeviceAuthority=false` 保持纯 shadow；
   - **DEVICE enablement 后 focused regression 全绿**：开关后的 baseline/no-anchor、validated Delta authority+checkpoint、mass-deletion quarantine、stale/cancel staging、playback defer/release、RetryLedger backoff、Files-only capability fail-closed 等定向 Gate 通过；随后 `Device*Test / Device scanner tests / LibraryScanOrchestratorTest / LibrarySyncSchedulerTest / MusicLibraryTest / LibraryMembershipDecisionPolicyTest / LibraryFollowupProtocolTest` 共 **258 tests / 0 failures**，并同时通过 `:app:compileDebugKotlin`、`:app:compileDebugUnitTestKotlin` 与 `git diff --check`；
   - **DEVICE ordinary scheduler 真机 real-authority Gate 已正式 PASS（2026-09-09 01:17）**：在同一台 Android 12 / API 31、Xiaomi `22081212C` 上，用 app-owned 隔离 MediaStore row `Music/MicaDeviceAutoSyncGate/device-authority.wav` 做可恢复 payload mutation；测试本身不删除该 row，已有内容先备份，结束后恢复原 payload，临时备份移动到 app cache `.MicaRecycle`。baseline 冷 DEVICE Full 得到 **10,551 songs / technicalFailed=0 / full-anchor accepted / error=false**，目标为 `65 s / 1,040,044 bytes / sha256=648e58a…`。随后仅通过真实 MediaStore pending→write→finalize 触发 ContentObserver，ordinary scheduler request=1 经 `MEDIASTORE_FILES_DIRTY` 在 **5,962 ms** 内完成 `added=0 / updated=1 / removed=0 / checkpoint=true / cursorAccepted=true / quarantine=none`，目标 authority 变为 `70 s / 1,120,044 bytes / sha256=d0566c…`；新建 `MusicLibrary` cold cache reload 仍读到同一 70 s / 1,120,044 bytes。紧接 isolated DEVICE Full oracle 再得 **10,551 songs / technicalFailed=0**，canonical coverage `changed=1 / covered=1 / uncovered=0 / fullyCovered=true`，projection compare `diff=0 / unresolvedObjects=0 / quarantined=0 / fullyEquivalent=true`，目标仍为 70 s / 1,120,044 bytes。finally 恢复原 payload 后又由 ordinary scheduler request=3 提交 `updated=1 / checkpoint=true / cursorAccepted=true / quarantine=none`，最终 row 回到 `1,040,044 bytes / sha256=648e58a…`。QA 原先用长 `BroadcastReceiver.goAsync()` 会被 MIUI `MiuiMemoryService(cch-empty)` 回收，因此该长 Gate 已固定走现有 debug foreground service `mode=DEVICE_AUTHORITY`；service 运行期 `isForeground=true`，本轮 exit-info 无新增异常退出。原始日志保存在 `.scratch/library-auto-sync-s3-device/20260909-real-authority-gate/`；
6. **S3 DEVICE real-auto enablement Gate 已满足并已启用**：projected-vs-Full `fullyEquivalent=true`、unresolved/quarantine 清零、真实设备 10k / playback coexistence、final publication/retry/cancellation Gate 均已闭合；未能物理构造的 Files-only provider 继续由 capability-scoped fail-closed 处理，不把缺失证据伪报为 PASS；
   - **S4 SAF Fast Verify shadow 已完成真实 DocumentsProvider correctness Gate 首轮（2026-09-07 20:20）**：FOLDER AutoSync 仍保持 shadow-only，§31 S4 实现清单 1～12 已在代码路径覆盖。metadata full walk 继续以 stable document identity / mediaUri / path / size / lastModified / 外部歌词签名建立 `SafTreeMetadataSnapshot`；DocumentsContract 直查为 COMPLETE，DocumentFile fallback 为 PARTIAL 且永不具 deletion authority，root 不可读为 UNAVAILABLE。可靠 fingerprint 只 probe added/changed；sidecar/path 可观察变化即使 size/lastModified 为 UNKNOWN 也立即进入 changed；RetryLedger due item 可强制把 otherwise-unchanged object 入队；当前播放对象在 planner 与 probe 前均重采样 lease，heavy probe parallelism 继续固定为 1；
   - UNKNOWN fingerprint 已从临时内存轮转改为**持久 debt 模型的 shadow 计划**：新增 `LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY`，记录 weak observation、`nextRetryAtMs` 与最近 strong verify 结果；observation 改变会立即重新到期，future `OBJECT_PROBE` backoff 会阻止 UNKNOWN 绕过退避。S4 10k UNKNOWN Gate 后已冻结第一版成功 deep verify cadence=24 小时、object budget=32/pass、heavy-probe parallelism=1、between-object wall budget=30 s；wall budget 只阻止开始下一个 UNKNOWN object，不强杀已经进入 provider I/O 的单对象。只有 `SAF_PERIODIC_VERIFY / PLAYBACK_IO_RELEASE` 允许偿还 UNKNOWN debt，普通 foreground catch-up 不做全量 deep verify。该 debt 目前仍是 `unknownDebtShadowOnly=true`，不写 Room；
   - selected UNKNOWN 已接**流式 SHA-256 strong resource verify**：probe 前后分别 hash 主音频 + 当前外部歌词 sidecar，任一资源不可读或两次 digest 不同都 fail-closed；成功 strong verify 后才允许音频 canonical probe 结果参与 projection。由于现有 canonical `Song` 不携带实际歌词 payload，UNKNOWN 即使 strong audio/resource verify 成功也仍保留 `EXTERNAL_LYRICS / EMBEDDED_LYRICS_PROBE` capability unresolved，不能仅凭 URI/size/mtime 宣称 payload 完全等价；
   - changed/new/selected-UNKNOWN/due-retry 继续复用真实 read-only `AudioMetadataProbe.quickSong/probeTrack`，probe 前 PlaybackIoGuard、probe 后第二次 metadata walk + `SafShadowPostProbeValidator`；pre/post revision race、歌词读取失败、draft 缺失均 fail-closed。video cover / MV 复用 Full Scanner 的 matcher，只对音频成员变化、MP4 inventory 变化或既有 video relation 涉及的目录做 affected-directory rematch；弱 MP4 revision（size/lastModified 不可靠）即使 relation matcher 命中，也保持 `VIDEO_COVER / MUSIC_VIDEO` unresolved；
   - SAF canonical Gate 本轮真机又修正两处 false negative：① 成功 audio probe 在 COMPLETE inventory 中无相关 MP4、无既有 relation 时不再无条件制造 `VIDEO_COVER / MUSIC_VIDEO` unresolved，relation unresolved 只由真实 relation work/unresolved key 驱动；② `FolderScanner` 的 SAF `dateAddedMs` 是扫描 wall clock 合成值，而 DocumentsProvider 没有 canonical DATE_ADDED，因此 SAF canonical diff 不再比较该字段，membership 仅由 stable-object presence/absence 表示；DEVICE/MediaStore 的真实 DATE_ADDED canonical 比较保持不变；
   - **真实 debug DocumentsProvider 六场景已固化 evidence**：通过 phone-local Termux/AgentDock-phone 在 Android 12 / API 31 上原地升级 `com.mica.music.qa`，使用 debug-only `TestDocumentsProvider + LibraryAutoSyncQaReceiver`，每场执行 `BASELINE Full -> provider mutation -> SAF Shadow -> same-scenario isolated Full oracle`。`CHANGED / LYRICS_CHANGED / ADDED / REMOVED` 均得到 `diff=0 / unresolvedObjects=0 / fullyEquivalent=true`；其中 ADDED 为 `added=1 / probeResolved=1`，REMOVED 为 `COMPLETE / removed=1 / removalSuppressed=0`。`UNKNOWN_CHANGED` 得到 `unknownDue=1 / selected=1 / strongVerified=1 / diff=0`，按设计只保留 lyric 两项 unresolved；`WEAK_VIDEO` 得到 `videoInventoryChangedFolders=1 / relationResolved=1 / diff=0`，按设计只保留 video/MV 两项 unresolved。原始 QA/diagnostics 日志与摘要在 `.scratch/library-auto-sync-s4/20260907-real-provider-gate/`；测试中的 REMOVED 只从 provider cursor 隐藏对象，未删除 backing fixture 文件；
   - 该小型 real-provider evidence 中，每次 metadata walk 为 2 次 direct provider query；changed/added/lyrics/UNKNOWN/weak-video 的 initial+post walk 合计 4 queries，REMOVED 单 walk 为 2 queries。固化复跑中 metadata walk 约 19～25 ms、post walk 约 17～27 ms；UNKNOWN strong SHA verify 约 409 ms，低于暂定 30 s wall budget。**这些数字只代表 app-owned debug DocumentsProvider correctness smoke，不冻结生产性能阈值，也不能替代 10k / 用户选择或 serialized vendor provider / playback coexistence Gate。**；
   - **app-owned DocumentsProvider 10k metadata-walk scale smoke 已通过（2026-09-07 20:40）**：新增 debug-only `TEN_K_METADATA` provider fixture 与 `LibraryAutoSyncQaProfileService`，直接调用 production `FolderScanner.observeMetadata()`，只测 10,000-row O(N) SAF metadata walk/cursor/materialization，不打开 10,000 个音频 payload。20 次连续 walk 全部 `entries=10000 / completeness=COMPLETE / providerQueries=2 / directQueries=2 / fallbackListings=0`；首轮 556 ms，20 次 median=241 ms、p95=291 ms、max=556 ms，warm(2～20) median=237 ms/p95=290 ms。进程内逐轮采样峰值 PSS=144,226 KB、RSS=222,968 KB、Java used heap=27,123 KB、native allocated=14,413 KB，窗口内无 OOM/FATAL/ANR。证据在 `.scratch/library-auto-sync-s4/20260907-10k-inprocess-memory/`。最初 BroadcastReceiver profile 被约 48 s broadcast 生命周期污染；随后确认该 MIUI ROM 的 `am instrument` 对有效/无效 runner 都静默 no-op，因此最终用 debug foreground service 避免 harness 误判。**该结果只通过 app-owned direct DocumentsContract 10k metadata scale smoke，不替代用户选择/vendor/serialized provider、playback coexistence、10k changed-object heavy probe 或生产阈值冻结。**；
   - **app-owned SAF current-object playback defer + `PLAYBACK_IO_RELEASE` Gate 已通过（2026-09-07 20:52）**：debug Gate 先用 BASELINE 做真实 FOLDER Full 建立 active source/canonical anchor，再以前台 QA Activity 启动同一 `contract.wav`，playback snapshot 与 production `MainViewModel` 使用同一 active-instance 语义。CHANGED shadow 在 current object active 时两次均得到 `changed=1 / probeReady=0 / probeDeferred=1 / probeAttempted=0`；随后在 MediaController application/main thread 清空 QA queue 并等待 ownership release，`PLAYBACK_IO_RELEASE` 对同一 held delta 两次均转为 `probeReady=1 / probeAttempted=1 / probeResolved=1 / probeIssues=0`，最终 isolated Full oracle 两次均 `diff=0 / unresolvedObjects=0 / fullyEquivalent=true`。首轮 defer shadow 14 ms、release shadow 75 ms；最终证据窗口无 `MSessionService` foreground-service error。早先后台 broadcast 直接拉播放曾被 Android 12 正确以 `ForegroundServiceStartNotAllowedException` 拒绝，测试改为真实前台用户启动条件，未放宽 production FGS policy。证据在 `.scratch/library-auto-sync-s4/20260907-playback-release-gate/`。**该 Gate 证明 app-owned provider 的 active current-object 不与 AUTO heavy probe 抢 IO，并可在 release 后恢复；仍不替代用户/vendor/serialized provider 的 playback/功耗/延迟证据。**；
   - **app-owned serialized/slow provider + production discovery backoff Gate 已通过（2026-09-07 21:11）**：debug provider 仅在 debug source 增加 child-query 串行 delay/failure injection 与真实 query counter，production `FolderScanner.observeMetadata()` / `SafProviderDiscoveryBackoff` 未加测试特例。250 ms/child-query 的 CHANGED shadow 用 4 次 provider child query 完成，整轮 1,159 ms，initial/post metadata walk 分别 520/515 ms，仍 `COMPLETE / probeResolved=1`。持续 query failure 时该轮 27 ms、2 次真实 provider query（direct + fallback attempt），降为 `PARTIAL / removalSuppressed=1 / probeAttempted=0`，production breaker 记录 `failures=1 / backoffMs=30000`；紧接着一轮 6 ms 且 provider query=0，日志明确 `discovery-backoff remainingMs=29983`。不修改 orchestrator backoff 状态、只恢复 provider 后，等待 production 默认 30 s + 1 s，recovery shadow 200 ms/4 queries 成功，随后立即再跑 157 ms/query count 从 4 增至 8，证明 COMPLETE success 已 reset breaker；最终 Full oracle `diff=0 / unresolvedObjects=0 / fullyEquivalent=true`。证据在 `.scratch/library-auto-sync-s4/20260907-provider-backoff-gate/`。**该结果通过 app-owned serialized/slow-provider 与 30 s circuit-breaker 生命周期 smoke，但仍不替代真实第三方/vendor provider 或最终功耗/阈值冻结。**；
   - **10k changed-object heavy-probe hard-budget Gate 已通过（2026-09-07 22:42）**：debug provider 建立 10,000 个独立 SAF document identity，并复用同一份合法 65 秒 WAV backing，避免夹具自身制造约 10 GB 数据。baseline Full 得到 `songs=10000 / technicalFailed=0`；Shadow 固定 `heavyProbeBudget=32 / probeParallelism=1`，前 312 轮各偿还 32 个对象，第 313 轮偿还最后 16 个，第 314 轮为 0-probe convergence pass，累计 314 轮、`shadowElapsedMs=1091622`，没有重复 probe 或 `probeIssues`。峰值 `PSS=127596 KB / RSS=185788 KB`；isolated Full oracle 为 `scannerResultMs=165003 / songs=10000 / technicalFailed=0 / diff=0 / unresolvedObjects=0 / fullyEquivalent=true`，oracle wall 184,758 ms，Gate 窗口未出现 Mica PID restart、FATAL、ANR 或 OOM。原始证据在 `.scratch/library-auto-sync-s4/20260907-ten-k-heavy-gate/`，凝练的逐 pass/profile 证据在 `.scratch/library-auto-sync-s4/20260907-10k-heavy-gate/`。据此只冻结**可靠 changed/new heavy probe** 的第一版 hard cap 为 32 objects/pass、parallelism=1；该夹具无完整逐字歌词，且不等价于 UNKNOWN 双 SHA 成本，因此不用于冻结 UNKNOWN interval/object/wall budget；
   - **用户选择的真实 provider 大树观测已补，但外部性能 Gate 未通过（2026-09-08 00:04）**：系统 `ExternalStorageProvider` 对真实 `Music` tree 三次只读 metadata walk 均为 `entries=10272 / COMPLETE / directQueries=11`，wall 分别 29,902 / 25,797 / 14,360 ms；第三方 Termux DocumentsProvider 对隔离 1-song tree 为 `entries=1 / COMPLETE / directQueries=2 / wallMs=35`，对隔离 10,000 个零长度 `.wav` metadata-only tree 的 Picker 前台与 persisted-grant 后台复跑分别为 `entries=10000 / COMPLETE / directQueries=1 / wallMs=22716` 与 19,516 ms。后台复跑 Mica PID 始终为 32411，15 次 2 秒采样峰值 `PSS=132965 KB / RSS=139384 KB`，Mica 未出现 FATAL/OOM/restart。随后以 app-owned 65 秒 `contract.wav` 保持 MediaSession 播放，同时第三次读取同一 Termux 10k tree：walk 为 `COMPLETE / directQueries=1 / wallMs=21253`，查询覆盖期间采样始终 `state=3`，已发布 position 单调不退（23,919→35,951 ms，查询完成后继续到 47,996 ms），Mica PID=32411、Termux PID=27454 均未变化；首轮播放/查询混合采样峰值 `PSS=258970 KB / RSS=302708 KB`。测试后 QA 播放已停止于 `state=0 / position=65007`。不过媒体音量为 0，且没有 underrun/xrun 或 audible latency 指标，该复跑只能证明状态链和进程稳定，不能判“无可感知卡顿”。更重要的是 events 在 23:51:13 明确记录 `com.termux: ContentProvider not responding` ANR，发生在 10k tree 的系统 Picker/provider 交互窗口；完整 ANR trace 已固化到同一 evidence 目录。trace 显示 `com.termux` main thread 当时在 Looper idle，阻塞点位于 provider Binder thread 的 `java.io.File.canWrite -> TermuxDocumentsProvider.includeFile -> queryChildDocuments`，因此这是 Termux provider 对每项做权限检查时的 O(N) provider-side 阻塞，不是 Mica main-thread ANR。该轮证据暴露时 production walker 还是无 `CancellationSignal` 的同步 `ContentResolver.query(...)`；之后已把 AUTO metadata query 放到专用 cancellable worker，并给 `ContentResolver.query(..., CancellationSignal)` 传入真实 signal，父 coroutine 取消时同时 cancel signal + worker future。**这只是建立 cancellation seam，不等价于证明所有 provider 都会及时响应 cancellation**；真实 provider capability 仍需单独观测。期间出现过一个未 checkpoint 的 5 s per-query timeout 实验，但因合法 COMPLETE walk 本身已观测约 14～30 秒，已明确撤回：不能用任意 timeout 伪修复，否则既可能截断合法来源，也不能保证已进入 provider Binder 的工作真正结束。原 failure breaker 只处理异常/incomplete 的缺口已在后续 slow-success cadence Gate 中补齐。这里只证明 production metadata walker 能完整消费真实外部 provider 大树，**不能单凭本轮判 provider 性能 PASS，也不能冻结 provider wall/jank/power 阈值**。零长度夹具不属于音频 probe/canonical correctness Gate。证据在 `.scratch/library-auto-sync-s4/20260907-external-provider-gate/`；
   - **真实系统 provider 的 slow-success cadence Gate 已通过（2026-09-08 02:44）**：`SafProviderDiscoveryBackoff` 现把“失败 breaker”和“成功但昂贵的 cadence guard”拆成两套状态，scope 继续由 source identity + activation epoch + config fingerprint 隔离。普通 COMPLETE walk 仍允许完整结束，不加 provider timeout；当 COMPLETE metadata wall 达到暂定 `10,000 ms` 阈值时，后续 ordinary AUTO 进入暂定 `15 min` cadence，failure/incomplete 仍走独立指数退避；`PLAYBACK_IO_RELEASE` 只允许绕过 slow-success cadence，**不能绕过真实 failure breaker**。同一台 Android 12 真机对 persisted `ExternalStorageProvider / primary:Music` 再跑 production metadata walker，得到 `entries=10272 / COMPLETE / directQueries=11 / metadataWallMs=30508 / externalWallMs=30512`；把该实测 wall 喂入同一 production policy 后 `slowSuccess=true / immediatePermit=SLOW_SUCCESS_CADENCE / playbackReleasePermit=ALLOWED`，Gate 只发起 1 次 metadata walk，没有立即第二次 hammer provider。focused `SafProviderDiscoveryBackoffTest + LibraryScanOrchestratorTest` 同轮 `BUILD SUCCESSFUL`，其中覆盖 fast post-walk 不擦掉 initial slow cadence、scope reset、failure breaker 独立以及 playback-release bypass。证据在 `.scratch/library-auto-sync-s4/20260908-slow-success-cadence-gate/`。该轮当时的 **10 s / 15 min 仍是 provisional policy 值**；后续真实播放性能/UID CPU 配对 Gate 已对这两个值完成最终冻结。该 Gate 本身关闭 slow-but-COMPLETE 热循环缺口，但不单独替代 cancellation responsiveness、jank/功耗或第三方 payload correctness；
   - **AUTO SAF process-wide query lane + busy fail-fast Gate 已通过（2026-09-08 03:08）**：为避免第三方 `DocumentsProvider` 在 caller cancellation 后继续执行时被新的 AUTO walk 并发 hammer，`FolderScanner.observeMetadata()` 的自动 child query 统一走进程级单 worker、**无任务队列**的 executor（`SynchronousQueue`）；每个 query 仍传真实 `CancellationSignal`，Manual/Full SAF 扫描则保留原同步路径。若旧 provider call 仍占 lane，新 AUTO query 不排队而是立即以明确的 `AUTO SAF provider query lane busy` 异常降为 PARTIAL；该 observation 永无 destructive authority，orchestrator 把 incomplete discovery 交给既有 exponential failure breaker。真机 debug Gate 故意关闭 provider 自身 serialization、每 query 延迟 1,500 ms 且忽略 thread interrupt：第一轮进入 provider 后 `cancelAndJoin=2 ms`；立即第二轮仅 `busyWalkMs=7` 就 PARTIAL，provider query count 仍为 1；旧 query 退出后 recovery walk `3021 ms / COMPLETE / entries=1`，总 query=3、`maxConcurrent=1`。focused orchestrator 另增 `s4SafShadowBusyPartialStartsFailureBreakerAndSuppressesImmediateRetry`，证明 busy PARTIAL 后立即 AUTO retry 不会再次调用 scanner。故 provider 是否及时响应 cancellation 已不再是 correctness/scheduler-liveness 前提；永久 hung provider 会退化为 fail-fast + breaker，而不是并发 hammer 或无限排队。证据在 `.scratch/library-auto-sync-s4/20260908-auto-query-lane-gate/`。**该 Gate 冻结 AUTO provider 并发上限=1 与 busy fail-closed 语义，但不宣称第三方 provider 自身可被 Mica 强制终止，也不替代 jank/功耗 Gate。**；
   - provider discovery backoff 与 Retry compensation 继续保持：initial/post metadata walk 的非 Cancellation 异常按 sourceIdentity + activationEpoch + configFingerprint 作用域指数退避，PARTIAL/UNAVAILABLE 永无 destructive authority；`PROBE_FAILED / DRAFT_UNAVAILABLE / POST_OBSERVATION_CHANGED` 计划 RetryLedger upsert，成功验证计划删除旧 retry，`PLAYBACK_DEFERRED` 交给 playback wake，UNKNOWN 交给独立 debt。全部 compensation 当前仍为 shadow plan，不调用 `applyAutoSyncState`；
   - focused S4 Gate 在上述真机修复后再次 `BUILD SUCCESSFUL`：`SafFastVerifyPlannerTest / SafAutoProbePlannerTest / SafShadowObjectProbeExecutorTest / SafShadowRelationRematcherTest / SafShadowVideoInventoryTrackerTest / SafShadowCanonicalProjectionTest / SafShadowRetryPlannerTest / SafUnknownFingerprintDebtPlannerTest / SafProviderDiscoveryBackoffTest / LibraryScanOrchestratorTest`，`git diff --check` 干净；debug QA APK 亦通过 Termux native-reuse 路径 `assembleDebug` 并以 PackageInstaller stdin session 原地升级。全 app Robolectric/Room/UI 套件在当前 Termux Linux aarch64 环境仍受 Robolectric native runtime 不支持阻塞，不能伪报全绿；
   - **真实第三方 Termux/ZeroTermux payload correctness + playback coexistence Gate 已通过（2026-09-08 04:24）**：先审计两个真实第三方 provider。J2ME Loader 的 `isChildDocument(parent, child)` 实际只接受 `child.parent == parent`，导致 picker 临时 grant 下可工作、仅 persisted tree grant 时 root/nested descendant 语义失效，因此作为真实 capability-negative 样本留证，不给 production 增加 provider 特判；随后改用 `com.termux.documents / ZeroTermux`，其 `isChildDocument` 为 descendant-prefix 语义且实现 create/open/query。该 provider 不支持新版 DocumentsUI 的 `findDocumentPath()`，故 debug Gate 从 provider `root://` 进入，再用 debug-only one-shot Accessibility state machine 严格限定 `MicaSafVendorGate -> Music -> 系统确认`。真实 picker 回调得到 `flags=3`；force-stop QA 后只保留 persisted grant 的 cold probe 仍为 `root readable / entries=2 / completeness=COMPLETE`，证明不是 picker 临时权限假绿。隔离树只含 `alpha.wav / beta.wav / alpha.lrc`，测试全程不删除 backing 文件；
   - 同一 Termux tree 的 **payload correctness**：baseline 两首 65 s WAV 后把 `alpha.wav` 经真实 SAF 原地替换为 70 s fixture，provider 可见 fingerprint 从 `size=1,040,044` 变为 `1,120,044` 且 mtime 改变；Shadow 得到 `changed=1 / probeReady=1 / probeAttempted=1 / probeResolved=1 / probeIssues=0 / completeness=COMPLETE`，随后 Full oracle `songs=2 / error=false / target durationSec=70`。该 Full oracle 的 changed-object scannerResult 实测约 `120,521 ms`，属于需保留的性能证据，但 correctness 最终成立；finally 通过 SHA-256 校验把 `alpha.wav` 恢复到基线 `648e58a0...`；
   - 同一真实 provider 的 **playback coexistence**：播放 `alpha.wav` 时原地替换非当前对象 `beta.wav`。活跃 playback pass 为 `changed=1 / probeReady=0 / probeDeferred=1 / probeAttempted=0`，Gate `65 ms` 完成且 `active=true`、position `19 -> 19 ms` 无回退；清空 queue 并等待 active instance release 后，`PLAYBACK_IO_RELEASE` 对同一 held delta 转为 `probeReady=1 / probeAttempted=1 / probeResolved=1 / probeIssues=0`，`189 ms` 完成；Full oracle `songs=2 / targetDurationSec=70`，随后 `beta.wav` SHA-256 恢复到基线。该后台 broadcast 驱动曾记录 Android 12 `ForegroundServiceStartNotAllowedException`，但 MediaController 仍进入 PLAYING 并完成 defer/release；因此本轮只关闭第三方 payload correctness / playback-IO coexistence，不把它包装成“用户可感知播放性能 PASS”。证据在 `.scratch/library-auto-sync-s4/20260908-termux-payload-gate/`；同轮 `Saf* + LibraryScanOrchestratorTest + LibrarySyncSchedulerTest + MusicLibraryTest` focused suite `BUILD SUCCESSFUL`，`git diff --check` 干净；
   - **10k UNKNOWN 双 SHA Gate 已完成并冻结第一版 UNKNOWN 阈值（2026-09-08 05:07）**：debug provider 暴露 10,000 个独立 UNKNOWN identity（`size=0 / mtime=0`），共用一份合法 65 秒 WAV backing；Gate 直接走 production `AndroidSafShadowProbeRuntime`，每个对象执行 URI open + SHA-before + audio probe + SHA-after。完整 run 为 313 passes、`resolved=10000 / attempted=10000 / budgetDeferredIssues=0`，series wall `762,718 ms`、UNKNOWN verify wall `753,788 ms`、均值 `75.378 ms/object`；32-object pass median=`2,405 ms`、p95=`2,653 ms`、max=`2,907 ms`，逻辑双 SHA 读量 `20,800,880,000 bytes`，峰值 `PSS=144183 KB / RSS=138668 KB`。另一个 320-object 独立样本为 `320/320`、`20,648 ms` verify wall、p95 pass `2,401 ms`，同样无 issue。基于这组成本，S4 冻结 UNKNOWN object cap=32/pass、parallelism=1、between-object wall budget=30 s；旧 6 小时成功 cadence 在全 UNKNOWN 10k 病理来源上意味着每天 4 次完整 fallback sweep，即便这批约 1 MiB 夹具也至少约 83.2 GB/day 逻辑双 SHA 和约 50 分钟/day verify wall，因此成功 cadence 改冻为 24 小时，保留 day-scale 自动发现并把病理 fallback 降到最多一轮/日；Manual Full Scan 仍可提前偿还。证据在 `.scratch/library-auto-sync-s4/20260908-10k-unknown-gate/`；
   - **外部 provider 播放性能/功耗 + cadence 数值 Gate 已通过并冻结第一版 policy（2026-09-08 13:06）**：同一台 Android 12 真机、同一 app-owned 65 秒 WAV、`MainActivity` 前台，从 position=0 开始做三组 no-provider baseline vs persisted `ExternalStorageProvider / primary:Music` production metadata walk 配对。真实 tree 为 10,272 entries，三次 query walk 均 `COMPLETE / directQueries=11`，metadata wall=`12,531 / 23,147 / 14,161 ms`（median 14,161 ms）。六个播放窗口全程 MediaSession `state=3`、position 0 次回退，published-position 最大更新间隔 baseline/query 分别 `3752/3752`、`3722/3725`、`3752/3757 ms`；gfx jank baseline→query 为 `1.08→0.70%`、`1.48→0.96%`、`1.89→1.46%`，query p95 均 11 ms，未出现 query 导致的 UI jank 恶化。新接入 `PipelineAudioRendererEventListener` diagnostics 后六窗均 `AudioPipeline underrun=0 / sink-error=0 / FATAL=0 / Mica/provider ANR=0`。功耗不再用充电电流硬判：以 kernel `/proc/uid_cputime/show_uid_stat` 累计 CPU 作为可复验代理，provider 每次 walk 相对 paired baseline 额外 CPU=`3.942 / 7.054 / 4.448 s`（median 4.448 s），Mica 额外=`0.429 / 1.869 / 1.060 s`（median 1.060 s），合计 extra UID CPU median=`5.508 s`、max=`8.923 s/walk`。据此冻结 COMPLETE slow-success threshold=`10,000 ms` 与 cadence=`15 min`：10 s 能清晰区分 app-owned fast/serialized smoke（<1.2 s）与反复观测到的真实大树（本轮 ≥12.5 s，历史亦约 14～32 s），且它只是成功 COMPLETE walk 后的分类器、不是 provider timeout；15 min 把 expensive AUTO re-walk 限制为最多 4 次/小时，同时 `PLAYBACK_IO_RELEASE` 仍只绕过 slow-success cadence、不绕过 failure breaker。最新 `SafProviderDiscoveryBackoffTest + LibraryScanOrchestratorTest + SafAutoProbePlannerTest + SafUnknownFingerprintDebtPlannerTest` focused suite 在阈值改名/冻结后 `BUILD SUCCESSFUL in 1m 35s`，`git diff --check` 干净；证据在 `.scratch/library-auto-sync-s4/20260908-provider-performance-gate/`。8 GB 真机条件继续按用户 2026-09-07 指令 user-waived/跳过，**不是 PASS，也不得把其他内存规格结果表述为 8 GB 等价验证**；
   - **S4 real-auto readiness r1–r3 已闭合 shadow→production correctness seam（2026-09-08）**：新增 `SafAutoSyncPublicationPlanner` 把 validated song/lyrics、membership、RetryLedger、UNKNOWN debt、checkpoint 收敛到同一 authority proposal；PARTIAL/UNAVAILABLE、relation unresolved、mass-removal quarantine、playback-deferred、budget-deferred 都不能错误推进 destructive publication/checkpoint。真实 AUTO publication 仍只通过 `MusicLibraryBacking` 的 publication gate；source identity + activationEpoch + live config fingerprint + operation token + catalog/presentation revision 在 final validation 前统一复验。checkpoint-only mutation 已升级到与 visible publication 相同的 final-gate/NonCancellable 语义；Room commit 后 memory adopt 不再因 parent cancellation 或 `release()` 留下 split-brain；
   - **AUTO canonical-row delta authority + Room atomicity Gate 已通过（2026-09-08）**：AUTO visible publication 不再在最终短提交区间重写完整 10k snapshot，而只对本轮 validated `added/updated/removed` canonical rows 做 Room mutation，同时在同一 `db.withTransaction` 中完成 staged lyrics promotion、checkpoint/RetryLedger/UNKNOWN debt、followup outbox、persisted source state；queue order / fast-scroll / browse group 等派生 presentation validity 在同事务内失效，后续按当前 settings 重建。FULL/用户 authority replacement 继续走原 full-snapshot seam。真机 `ROOM_ATOMICITY` Gate 的 success path 证明 delta row + lyrics + checkpoint + retry 同时生效；故意让 song INSERT abort 后，row/lyrics/checkpoint/retry/outbox 全部回滚，pending staging 保留，presentation/browse validity 也没有半提交。该 Gate 关闭了“为缩短 publication hold 而拆散 durable authority”的风险；
   - **10k small-delta publication 数值 Gate 已冻结并变成硬断言（2026-09-08 16:53）**：性能重构前同一 10k/32-song Gate 的 final publication hold median 约 17.259 s；canonical-row delta authority 把 hold 降到约几十毫秒，但最初 bridge 仍被 title sort + fast-scroll normalization 拖到约 7.8 s。最终 `AlphabeticalText` 采用纯 ASCII ICU bypass + 同步 access-order、有界 32,768-entry normalized-text LRU；容量来自 10k synthetic worst-case 的约 30k distinct title+artist+album working set，不是无界进程 cache。fresh-process 10k 唯一中文 title/artist/album Gate 冻结硬阈值：cold pure/stats preparation `<=5,000 ms`、warm 10k/32-object AUTO bridge wall `<=500 ms/pass`、final non-cancellable publication hold `<=100 ms/pass`、final Room authority transaction `<=100 ms/pass`；最新 fresh-process 实测 `baselinePrepare=4,782 ms`，三轮 AUTO wall=`167/148/180 ms`，hold max=`51.521 ms`，Room store max=`28.366 ms`，全部由 debug QA `require` 硬断言 PASS。ASCII synthetic 同路径 baseline presentation 曾为 112 ms、AUTO wall median 99 ms。证据在 `.scratch/library-auto-sync-s4/20260908-publication-gate-final-thresholds/`；cold Unicode 首次全量 normalization 明确保留为约 3.6～4.8 s 的 cold bound，不伪装成 warm AUTO 性能；
   - **delta publication 引出的 object-derived cover-color 丢写竞态已修复（2026-09-08）**：旧封面取色路径是 memory-first、异步 Room write 且绑定 `scanGeneration`；在“memory 已 adopt 新 coverColor → 无关 AUTO bump generation → 旧 writer 才拿到 publication gate”时，旧 full-snapshot publication 还能顺带把整库 coverColor 带入 Room，而新的 delta authority 不会，因此可能造成重启后颜色回退。现在该 writer 改为 object-state-owned seam：仍遵守 `publicationMutex -> storeSyncMutex`，但不因无关 scan generation bump 失效，只在 final gate 下复验当前 song 的 artwork/argb predicate；真正对象/封面替换、clear、release 仍 fail-closed。新增 `objectDerivedStoreWriteSurvivesUnrelatedAutoGenerationBump` 交错 regression，同时原 cover-color persistence、AUTO cancel-after-store、release-after-store regression 均通过。继续审计其他 generation-bound writer 后未发现第二个同类 memory-first 持久化裂缝：loudness 为 Room-first；presentation 的 user-owned sort/custom order 有 preferences authority 且 delta commit 会失效 Room derived presentation；exclusion/outbox 本就应绑定 operation/source authority；
   - **S4 real-auto readiness r4 至此闭合，但真实 FOLDER/SAF AUTO 仍保持关闭**：r1–r4 已完成 correctness、rollback/fencing、publication/adopt cancellation、delta authority、10k numeric publication gate 与 object-derived writer ownership 审计。最终 phone-local focused suite 覆盖 `SafFastVerifyPlannerTest / SafAutoProbePlannerTest / SafShadowObjectProbeExecutorTest / SafShadowRelationRematcherTest / SafShadowVideoInventoryTrackerTest / SafShadowCanonicalProjectionTest / SafShadowRetryPlannerTest / SafUnknownFingerprintDebtPlannerTest / SafProviderDiscoveryBackoffTest / SafAutoSyncPublicationPlannerTest / LibraryScanOrchestratorTest / LibrarySyncSchedulerTest / MusicLibraryTest / SongSorterTest`，共 **192 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESSFUL**，且最终 `git diff --check` 干净。另一次把 `LibraryRepositoryTest` 也加入同一命令时得到 216 tests / 24 failed；逐条 XML 核对后 24/24 均为同一个环境错误 `The Robolectric native runtime is not supported on Linux (aarch64)`，不是业务 assertion failure，因此不得伪报这 24 条为 green。Room durable atomicity/rollback 继续由前述 Android 真机 `ROOM_ATOMICITY` Gate 直接验证。下一步进入 r5，只做“是否允许 FOLDER real-auto”的 enablement decision 与受控回写验证；r5 review PASS 前不得把 readiness bridge 接到普通 scheduler，也不得把当前状态表述为 real-auto 已开启。8 GB 真机条件继续按 2026-09-07 用户指令 user-waived/跳过，**不是 PASS，也不作为等价内存验证**；
   - **S4 real-auto readiness r5 review 已 PASS，FOLDER/SAF ordinary scheduler real-auto 已正式启用（2026-09-08 20:35）**：r5 先把误接到 ordinary scheduler 的 authority write 拆回 shadow-only，完成受控回写后再通过 task final review，确认 visible delta/checkpoint/RetryLedger/UNKNOWN debt 同事务、source/config/activation/token/revision final fencing、Room commit→memory adopt cancellation safety，以及 PARTIAL/UNAVAILABLE/unresolved/quarantine/playback-deferred/budget-deferred fail-closed 全部满足，随后才把 `executeScheduled(AUTO_SYNC)` 的 FOLDER publication authority 翻为开启。`publishSafAuthority` 只在 active source=FOLDER 分支生效；当时新增 regression 证明 scheduled DEVICE 仍为 shadow-only，这一历史状态已被 2026-09-09 的 S3 DEVICE enablement checkpoint supersede；debug diagnostics 仍继续保留 `publishSafAuthority=false` 的纯 shadow 入口；
   - **真实第三方 SAF 受控回写 Gate PASS**：在 `com.termux.documents` 隔离 `MicaSafVendorGate/Music` tree 中把 `alpha.wav` 从 65 s 原地替换为 70 s fixture，readiness authority pass 得到 `changed=1 / probeReady=1 / probeAttempted=1 / probeResolved=1 / publicationPlanCheckpoint=true / publicationCommitted=true / completeness=COMPLETE`；随后新建 `MusicLibrary` cold load 得到 `targetDurationSec=70`，Full oracle 同为 70，最后恢复基线 SHA-256 `648e58a02132292fd046e10788dba2471bf58012f1a714d083bf92314914a3e6`。QA harness 原先成功恢复后会 `backup.delete()`，现已改为移动到 app cache `.MicaRecycle`；测试期间没有永久删除本地文件；
   - **ordinary scheduler 真机 Gate 进一步 PASS**：最新 QA 包重新通过真实 DocumentsUI 取得 persisted read/write tree grant 后，只发一次 payload mutation；`library.onForegroundChanged(true)` 产生真实 `FOREGROUND_CATCH_UP` dirty signal，scheduler 经 `TRAILING_DEBOUNCE` 在 request=1 执行 FOLDER AUTO，得到 `changed=1 / probeResolved=1 / publicationCommitted=true`，从 dirty signal 到可见 authority 更新约 `1,904 ms`。同轮 provider/content signal 仅触发 scheduler 已有的一次 `IN_PASS_FOLLOW_UP`，request=2 为 `changed=0 / metadataNoOp=true / probeNoOp=true` checkpoint pass，没有热链；cold cache reload 与 Full oracle 均为 70 s，fixture 再次恢复原 SHA。证据在 `.scratch/library-auto-sync-s4/20260908-r5-enable-gate/`；最终 focused suite 为 **193 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESSFUL**，`git diff --check` 干净；
   - **第三方 provider availability 兼容项已补成有界、fail-closed recovery（2026-09-09）**：当前 MIUI/Termux 组合在 QA APK `pm install -r` 后仍会出现 persisted tree grant 保留 `read/write=true`、但第三方 DocumentsProvider 的 `acquireUnstableContentProviderClient(treeUri)` 返回 `null`；同机系统 `ExternalStorageProvider` 可正常 acquire，说明问题集中在 OEM/第三方 provider cold-state，而不是 Mica 丢 grant 或 SAF tree identity 错误。production 现在只在普通 `canReadTree()` 已失败后，把 persisted grant 当作 recovery diagnostic，并尝试一次 provider-client reacquire；reacquire 成功仍必须重新通过普通 SAF readability/discovery，绝不因 grant 存在就提升 completeness。真实隔离 Termux `MicaSafVendorGate/Music` Gate 先建立 `entries=2 / songs=2 / intent=ACTIVE / access=AVAILABLE` baseline，checkpoint=`1788918545691`；随后 `install -r` 保留 `persisted=0x3`，ordinary scheduler request=1 得到 `provider-client-unavailable / failures=1 / backoff=30s`，30 s 后 request=2 由 `RETRY_DUE` 自唤醒并进入 `failures=2 / backoff=60s`，60 s 后 request=3 再由 `RETRY_DUE` 运行并在 `failures=3` 转 `TEMP_UNAVAILABLE / recovery=user-reselect-or-resume / retryWake=false`。整个坏态期间 committed songs 始终为 2，SAF checkpoint 前后同为 `1788918545691`，没有 publication/checkpoint 前进，也没有获得 deletion authority。用户经真实 DocumentsUI 重新选择同一 tree 后立即恢复 `entries=2 / COMPLETE`；FOLDER access refresh 会清掉仅内存态 provider breaker 并 re-arm `AVAILABLE`，ordinary `FOREGROUND_CATCH_UP` AUTO 在约 `1,689 ms` 完成 COMPLETE no-op publication/checkpoint，checkpoint 前进到 `1788922042935`，没有残留旧 120 s backoff。该 OEM/provider 行为仍可能要求用户重选目录，但现在不会无限热重试，也不会把 PARTIAL 伪装成 deletion-authoritative；兼容性风险从“无恢复提示”降为“最多两轮自动重试后显式要求重选同一目录”。
   - **S5 第一轮 tuning 已完成（2026-09-08）**：debounce=1.5 s、max debounce=5 s、cooldown=60 s、最多一次 immediate follow-up、DEVICE/SAF heavy probe parallelism=1、provider/object retry backoff、foreground SAF verify=5 min、slow-provider cadence=15 min、UNKNOWN=24 h 等冻结值均保持不动；唯一代码调整是补齐 scheduler-owned delayed retry wake。真实 QA APK replace 复现 Termux provider `cannot-read-tree` 后，request=1 记录 `backoffMs=30000`，无外部 signal 时 30 s 后 request=2 自动以 `wake=RETRY_DUE / SAF_RETRY_DUE` 运行，仍失败则进入 `backoffMs=60000`，证明 retry 到期不再依赖 5 分钟 periodic/外部事件；后台 deadline 不启动 IO、`cancelAll()` 清 timer、source/activation fencing 均有 unit regression。重新 DocumentsUI 授权后 ordinary scheduler 65→70 s Gate 再次 `publicationCommitted=true`，authority update 约 1.839 s，cold cache / Full oracle 均 70 s，fixture SHA 恢复。通用 directory-mtime subtree skip 因目录 mtime 不传播 child content overwrite 而明确拒绝。focused suite 现为 **197 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESSFUL**；证据在 `.scratch/library-auto-sync-s5/20260908-delayed-retry/` 与 `.scratch/library-auto-sync-s5/20260908-directory-mtime/`；
   - **§37 recovery kill-switch 验收缺口已闭合（2026-09-09）**：final closeout audit 发现 ordinary scheduler 在 enablement 后把 DEVICE / FOLDER authority 固定开启，却还没有满足“AUTO 可通过 feature gate/source gate 单独关闭而不破坏手动扫描”的显式运行时控制。现新增内部持久 `LibraryAutoSyncPreferences`，提供 global + DEVICE + FOLDER 三层 gate，三者默认均为 ON，不新增设置 UI。production `AutoSync` 在 `publicationMutex` 内按最终 active token source 检查 gate，禁用时在 discovery 前直接返回且不推进 `scanGeneration`；production AUTO token 同时记录 `autoSyncGateEnforced=true`，因此长 probe 运行中若 gate 被关闭，后续所有既有 `isCurrentOperationToken` / final publication / checkpoint fencing 会把旧 token stale-drop，不能在关闭后迟到提交。Manual `Rescan / ScanDeviceWide / ScanLibraryFolder / TargetedRefresh` 完全不经过该 gate；debug shadow/readiness authority seam 显式 `enforceAutoSyncGate=false`，仍可做受控 QA。focused regression 已证明 DEVICE/FOLDER gate 关闭时 scheduled AUTO 为零 discovery/零 generation bump，而对应 Manual Full 仍能发布；另覆盖 global gate、两个 source gate 独立性、默认开启、mid-pass close stale-drop 与 diagnostics bypass。最终把该 Gate 纳入 DEVICE/scanner/orchestrator/scheduler/MusicLibrary/membership/followup 相关回归后，XML 机械计数为 **265 tests / 0 failures / 0 errors / 0 skipped**，并通过 `:app:compileDebugKotlin`、`:app:compileDebugUnitTestKotlin`、side-by-side `:app:assembleDebug` 与 `git diff --check`。该机制是 operational/recovery kill switch，不放宽任何 capability/completeness/destructive-safety 判据；
   - **10k durable-state boundedness closeout 已完成（2026-09-09）**：普通 DEVICE/SAF AUTO 不再为了规划一次 pass 而 materialize 整个 source RetryLedger。Retry DAO 现提供 keyset page=`128`、due(kind/activation/time) work budget=`64`、stable-object-key lookup batch=`128`，retry delete 以 `400` keys 分批；SAF UNKNOWN 规划对 10,000 个 candidate 仅做 79 个 `<=128` key lookup batch，并只保留 `32` 个 verify work/history，cleanup 也在固定 budget 命中后停止继续读。follow-up outbox 改为 `(createdAtMs,eventId)` keyset page=`64`，单个 bounded batch 最多 `8` 页/`512` 项；consumer 维护 resume cursor，production `drainToTail()` 在批间 `yield()` 后继续直到调用时可见 tail，因此 10k backlog 不需要第二个 library event 才能排空，同时未知/未 ack 的前缀不会永久饿死尾部。Room schema 升到 29，并为 retry due/next-retry 与 outbox keyset 顺序增加索引。曾尝试的伪 `InventoryPaging` 抽象已撤销，没有用“把 List 换个名字”冒充真实分页；相关两个未跟踪试验文件已通过系统回收站处理，未永久删除。10k regressions 覆盖 UNKNOWN lookup/retained working set、outbox 10k drain-to-tail、固定 page limit、resume starvation 与既有 retry/backoff/UNKNOWN cleanup/outbox ack 语义；`git diff --check` 与 `:app:compileDebugKotlin` PASS，`LibraryFollowupConsumerTest + LibraryScanOrchestratorTest + SafShadowRetryPlannerTest + SafRetryPlanningLoaderTest` focused run `BUILD SUCCESSFUL`。将 `LibraryRepositoryTest` 混入同一手机端 run 时为 139 tests / 24 failed；逐条 XML 复核 24/24 均是 `The Robolectric native runtime is not supported on Linux (aarch64)`，因此明确记为 Termux 环境限制而不伪报 PASS；
   - **§37 final acceptance inventory-retention audit 已闭合（2026-09-09）**：最终审计确认不能把 source authority inventory 与 durable/work queue 混为一谈。SAF 第一版根据 §2.2/§19.2 必须完整 O(N) walk 才能获得 COMPLETE deletion authority；DEVICE Presence inventory 同样承担 authoritative absence/eligibility 证据，因此不以伪分页削弱 correctness。已有真实 10k SAF metadata walk、10k changed/UNKNOWN 与 DEVICE scale/playback Gate 证明这种 pass-scoped authority inventory 在当前已验证规模可接受；本轮只收掉围绕它的额外 O(N) 临时副本：DEVICE Presence 由 `List -> Map` 双驻留改为直接构建最终 keyed inventory，transient/logging 不再重复 filter 全量 rows；SAF Fast Verify 生产态复用 `MusicLibraryBacking` 既有 song-id index；post-probe validator 只保留本轮 provisional keys（冻结 heavy-probe budget 下通常 `<=32`），不再为 initial/post 10k snapshot 各建一份完整 Map；UNKNOWN loader 去掉 10k pre-sort，debt planner 只保留 selected verify keys + `<=32` cleanup keys，并对 oversized caller input 自身防御性限界；object retry planner 只为 retryable issue keys 保留 observed map。authority snapshot 仍是 pass-scoped O(N)，但 retry/probe/post-validation/cleanup retained working set 已与 source cardinality 解耦。最终 broad Gate 覆盖 DEVICE/SAF/library/scheduler/publication/retry/outbox，机械解析 **42 个 XML / 371 tests / 0 failures / 0 errors / 0 skipped**，并通过 `:app:compileDebugKotlin` 与 `git diff --check`。`LibraryRepositoryTest` 的 Linux(aarch64) Robolectric native-runtime 限制仍单独保留，不计入上述 green 数字；8 GB 条件继续按用户指令 user-waived/跳过、**不是 PASS**；DEVICE Files-only provider 物理样本缺失继续归类为 non-blocking compatibility evidence/item；第三方 persisted-grant replace 后的 provider cold-state 已有 30s/60s 有界自动重试、第三次 `TEMP_UNAVAILABLE` 停止热链与同-tree 重选 re-arm recovery，仍可能要求用户重选目录，但现有 capability/PARTIAL fail-closed 语义不放宽；
7. **DEVICE 与 SAF/FOLDER ordinary scheduler real-auto 均已开启；S5 第一轮调优、recovery kill-switch、10k durable-state boundedness 与 §37 final acceptance inventory-retention audit 均已完成。** 两条 source path 继续分别保持自己的 discovery/capability Gate；任何 PARTIAL/UNAVAILABLE/transient provider state、quarantine、playback-deferred 或 stale token 都必须 fail-closed，Manual Full Scan 仍保留为明确 recovery authority。剩余项均为已明确记录的 compatibility evidence/item 或 user-waived 条件，不构成当前 destructive-safety blocker。

---

## 附录 A：目标终态数据流

~~~text
Dirty Signals / Periodic Verify
           │
           ↓
LibrarySyncScheduler
  dirtySequence / debounce / cooldown / retry wake
           │
           ↓
LibraryOperationToken
           │
      ┌────┴────┐
      │         │
   DEVICE      SAF
 generation   metadata inventory
 delta         full walk
      │         │
      └────┬────┘
           ↓
DiscoveryReport
partition completeness
           ↓
Tri-state patches
+ membership evidence
+ retry candidates
           ↓
ObjectObservation validation
           ↓
Publication gate
current-catalog rebase
           ↓
        no-op?
      ┌────┴────┐
     yes       no
      │         │
checkpoint   prepare snapshot
 only           │
                 ↓
      revalidate publication base
                 ↓
         single Room transaction
     snapshot + checkpoint + outbox
                 ↓
         memory catalog publication
                 ↓
          LibraryChangeSet
          /              \
 queue current-state      durable outbox
 reconcile                owner consumption
~~~

## 附录 B：P&P 借鉴边界

### PixelPlayer 借鉴

- foreground-only dirty observation；
- debounce/coalesce；
- foreground catch-up；
- MediaStore delta cursor 思想；
- local incremental sync 与重 maintenance 分离。

不照搬：

- MediaStore 作为唯一曲库权威；
- custom folders 仅作为 MediaStore path filter。

### Poweramp 借鉴

- 自建曲库 DB 权威；
- SAF 真扫描；
- dirty signal 只表示“可能变化”；
- fast scan；
- missing only after successful coverage；
- storage unavailable 时跳过 deletion。

第一版暂不照搬：

- 目录级 mtime subtree skip；
- Poweramp 的权限/文件系统访问模型；
- 任何 MANAGE_EXTERNAL_STORAGE 需求；
- 其内部 DB/schema/线程实现。

---

## 附录 C：核心判断

最终实现目标不是“让 Mica 每次变动都重新扫描得更快”，而是：

> **让 Mica 有资格只处理真正变化的对象，同时在任何不确定、并发、权限、source 切换、进程退出和播放会话状态下，都不会把“不知道”误写成“没有”。**

只有先做到这一点，自动同步才可以成为默认行为。
