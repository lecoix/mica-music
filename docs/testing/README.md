# Test taxonomy

Mica classifies each tracked Kotlin `@Test` into **three active roles** plus an `OBSOLETE` retirement category. The role answers **what is allowed to make the test change**, not which framework runs it.

## Categories

### `BEHAVIOR_CONTRACT`

Stable user/data semantics. These tests protect user-visible behavior, data safety, persistence semantics, security boundaries, and concurrency/race guarantees that must survive an internal refactor.

A normal implementation refactor should make the implementation satisfy these tests rather than rewriting the expectation. Changing one requires an explicit product/behavior decision.

### `ARCHITECTURE_CONTRACT`

Stable architecture decisions: dependency direction, authority/ownership, publication/commit boundaries, and single-owner lifecycle rules.

These may change only as part of an explicit architecture decision. A local implementation change must not silently edit the expectation to get green.

### `IMPLEMENTATION_VERIFICATION`

Evidence for the current implementation: concrete planners/schedulers, batching/budget choices, cache topology, Media3 callback wiring, Robolectric wiring, UI geometry/screenshot expectations, DSP/pipeline implementation details, and similar replaceable mechanisms.

These tests are valuable, but they may be rewritten or retired when the implementation is intentionally replaced, provided the relevant Behavior and Architecture contracts remain covered.

### `OBSOLETE`

The production implementation or product semantic no longer exists. These tests are not evidence for the current artifact and must not force production code to resurrect a retired implementation.

Obsolete tests are tracked explicitly until they are retired from the suite.

## Manifest

`TEST_CLASSIFICATION.csv` contains one row per tracked Kotlin `@Test` with:

- category
- file and suite
- test method
- classification rationale
- confidence (`high` rule-derived or `reviewed` manual review)

Generate it with:

```powershell
python scripts/generate-test-classification.py
```

Validate that every tracked Kotlin `@Test` is represented exactly once with:

```powershell
.\scripts\check-test-classification.ps1
```

The generator is conservative by default. Manual decisions live in `scripts/test_classification_reviewed.py` so mixed suites can classify individual tests differently without moving or renaming the original tests.

## 三层合同与退役标记（2026-10-09）

`BEHAVIOR_CONTRACT`、`ARCHITECTURE_CONTRACT`、`IMPLEMENTATION_VERIFICATION` 是三种测试变更授权边界；`OBSOLETE` 仅记录待退役，不计为第四种有效验证层。10 月新增的 SMB staged import、service accepted order、GLES geometry 回归须重新生成与审阅 manifest，不能沿用 9 月数量当成当前全集。

## 当前已提交快照（2026-10-09）

以 HEAD `57f3b989` 的 tracked Kotlin 测试内容计算（排除工作树尚未提交的 2 个 `PlayerPageLayoutEngineTest` 新方法）：共 **2,636** 项。分类为 `BEHAVIOR_CONTRACT` **1,299**、`ARCHITECTURE_CONTRACT` **83**、`IMPLEMENTATION_VERIFICATION` **1,245**、`OBSOLETE` **9**。相对 9 月快照新增 60 个已提交方法，剔除 2 个被重命名/替换的旧记录；`scripts/test_classification_reviewed.py` 中 Review pass 9 保存这 60 项的显式判定。

**工作树注意**：默认 generator/validator 读取当前磁盘内容而不是 Git HEAD；当前存在未提交的 UI 测试修改，普通校验会额外找到两项而失败。这并非 HEAD manifest 缺漏。待这些修改决定提交后，必须重新生成 manifest 并复核角色。不要为了让测试变绿而将这些未提交方法当作已发布测试。

## Historical checkpoint (2026-09-29)

The manifest covers all 2,578 tracked Kotlin `@Test` methods:

- `BEHAVIOR_CONTRACT`: 1,276
- `ARCHITECTURE_CONTRACT`: 73
- `IMPLEMENTATION_VERIFICATION`: 1,220
- `OBSOLETE`: 9

The four obsolete test suites are the orphaned `media/usbprototype` tests; their production symbols no longer exist and they currently prevent the Perf unit-test source set from compiling. Classification does not delete or disable them.

The manual review now also covers the latest SMB playlist persistence/commit boundaries and widget-launch wiring. The manifest contains 1,407 manually reviewed rows and 1,171 high-confidence rule-derived rows; no medium-confidence rows remain. The validator rejects missing/stale rows, invalid categories, duplicate rows, and any classification that is still left at medium confidence.
