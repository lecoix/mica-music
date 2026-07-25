---
status: accepted
---

# 完整曲库 Snapshot 替换须走统一 generation / store 协议

凡是能替换「完整曲库 snapshot」的操作，必须进入同一套 `libraryGeneration`（现 `scanGeneration`）与 `storeRevision` + `storeSyncMutex` 协议：先成功写入 Room（若需要），再发布内存中的歌曲列表与扫描元数据。选择这条路线是因为 cache hydrate、scan commit 与 clear 曾可并行改同一份真相，导致旧 snapshot 覆盖新结果、clear 后曲库复活，或 commit 失败后元数据与歌曲列表撕裂。

## Consequences

- **完整替换**：cache hydrate、scan commit、clear library、`release` 作废。局部更新（`applyPlayStats`、`removeSong`、排序 presentation、扫描中歌词 batch）不得冒充整库替换，也不得擅自 bump 整库 generation。
- **顺序**：`Room 成功 → 再发布内存（songs + hasScanned + lastScanAt/source/size）`。禁止先改内存元数据再 `commitScan`。
- **失败扫描**：不改变旧 snapshot，只设置 `lastScanError`（不因此把 `hasScanned` 置 true，不改歌曲与扫描元数据）。
- **clear**：开始时 bump generation、cancel 进行中的 scan，经 `storeRevision`/`storeSyncMutex` 清库后再发布空内存；不得 fire-and-forget 旁路写 Room。
- **cache load**：开始时 bump generation；Room 返回后、`adoptPrepared` 前必须 `isActiveGeneration`，否则丢弃。

## Deferred

- 将 `scanGeneration` 字段重命名为 `libraryGeneration`（语义已扩大，改名可随后续清理）。
- 扫描取消后的 lyrics orphan 低成本清理（P2）。
