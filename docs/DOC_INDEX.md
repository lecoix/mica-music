# Mica 项目文档索引

> 最后整理：2026-07  
> 范围：本仓库 **Mica Android** 文档（不含 `.icey-ref/`、`.codex-push-*` 副本）。

---

## 阅读顺序

1. [`README.md`](../README.md) — 环境、功能概览  
2. [`CONTEXT.md`](../CONTEXT.md) — 领域词汇（播放队列、封面行为、持久化等）  
3. [`DESIGN_SPEC.md`](../DESIGN_SPEC.md) — 设计语言（§十五 规范与现网对照）  
4. [`TODO.md`](TODO.md) — 已实现 / 待办  
5. [`MOTION.md`](MOTION.md) — 动效规范 + **§七 Compose/View 岛**  
6. 按任务：[`PLAYER_PAGE_CONTRACT.md`](PLAYER_PAGE_CONTRACT.md)、[`COVER_FLOW_IMPLEMENTATION.md`](COVER_FLOW_IMPLEMENTATION.md)、[`PARTICLE_COVER_OPENGL_MIGRATION.md`](PARTICLE_COVER_OPENGL_MIGRATION.md) §0、[`SHARED_ELEMENT_ANIMATION_NOTES.md`](SHARED_ELEMENT_ANIMATION_NOTES.md)
7. 参考拆解：[`APPLE_MUSIC_DYNAMIC_BACKGROUND_RE.md`](APPLE_MUSIC_DYNAMIC_BACKGROUND_RE.md)

---

## 必须保留

| 文档 | 角色 |
|------|------|
| [`README.md`](../README.md) | 项目入口 |
| [`DESIGN_SPEC.md`](../DESIGN_SPEC.md) | 设计规范 v1.3（含 §十五 规范与现网对照） |
| [`TODO.md`](TODO.md) | 功能 living list |
| [`MOTION.md`](MOTION.md) | 动效权威 + View 岛分工 |
| [`COVER_FLOW_IMPLEMENTATION.md`](COVER_FLOW_IMPLEMENTATION.md) | 播放页封面行为 **产品 §0 + 封面流 §1–12 + 拍立得 §13** |
| [`PLAYER_PAGE_CONTRACT.md`](PLAYER_PAGE_CONTRACT.md) | 播放页契约、模块地图、回归清单 |
| [`OPEN_SOURCE_NOTICES.md`](OPEN_SOURCE_NOTICES.md) | 开源合规（含 BlurView） |
| [`DOC_INDEX.md`](DOC_INDEX.md) | 本索引 |

---

## 仍有价值（专项）

| 文档 | 用途 |
|------|------|
| [`SHARED_ELEMENT_ANIMATION_NOTES.md`](SHARED_ELEMENT_ANIMATION_NOTES.md) | 迷你栏↔播放页共享封面状态机与必测场景 |
| [`reviews/REFACTOR_PLAYBACK_ARCHITECTURE.md`](reviews/REFACTOR_PLAYBACK_ARCHITECTURE.md) | 播放架构审查（`refactor/playback-architecture` → `exoplayer-only`；含 Bugbot 第三轮 + Ponytail 第四轮） |
| [`DSD_EXO_PLAYBACK.md`](DSD_EXO_PLAYBACK.md) | DSD `.dsf` 的 Exo 扩展实现、降采样链路与系统音效说明 |
| [`AUDIO_PIPELINE_REFACTOR.md`](AUDIO_PIPELINE_REFACTOR.md) | Exo PCM **改造计划**（**§0 摘要、§18 Gate/验收**；P1 过渡态；P2–P6） |
| [`AUDIO_PIPELINE_DISCUSSION.md`](AUDIO_PIPELINE_DISCUSSION.md) | Exo PCM 链路**讨论记录**（背景与机制分析；结论已并入 REFACTOR） |
| [`PERFORMANCE_INVESTIGATION.md`](PERFORMANCE_INVESTIGATION.md) | 切歌卡顿/发热主线调查（hybrid4-hybrid8） |
| [`PERFORMANCE_INVESTIGATION_02.md`](PERFORMANCE_INVESTIGATION_02.md) | 调查 **#02**：大队列复验、mirror-index-sync、按钮 visual-first、cover-load 发热 |
| [`PARTICLE_COVER_OPENGL_MIGRATION.md`](PARTICLE_COVER_OPENGL_MIGRATION.md) | 粒子封面 **§0 产品** + WebView 退役 / GLES parity 施工单 |
| [`APPLE_MUSIC_DYNAMIC_BACKGROUND_RE.md`](APPLE_MUSIC_DYNAMIC_BACKGROUND_RE.md) | Apple Music 动态背景逆向（`DYNAMIC_ARTWORK` 参考） |
| [`REASONIX.md`](../REASONIX.md) | AI/工具速览（须与 `libs.versions.toml` 对齐） |
| [`CONTEXT.md`](../CONTEXT.md) | 领域词汇权威来源 |

**历史快照（勿作现行 bug 清单）**：[`reviews/REFACTOR_PLAYBACK_ARCHITECTURE.md`](reviews/REFACTOR_PLAYBACK_ARCHITECTURE.md)、[`PERFORMANCE_INVESTIGATION.md`](PERFORMANCE_INVESTIGATION.md) 及 `_02` — 文首已说明 hybrid 调查背景。

---

## 已合并 / 已删除（勿再引用）

| 原文档 | 处置 |
|--------|------|
| `docs/COVER_FLOW.md` | **已合并** → `COVER_FLOW_IMPLEMENTATION.md` **§0** |
| `docs/REVIEW_NOTES.md` | **已合并** → `PLAYER_PAGE_CONTRACT.md`（模块地图、回归、架构注意） |
| `DESIGN_SPEC.md` §九 重复时长表 | **已删减** → 指向 `MOTION.md` |
| `.codex-push-main/`、`.codex-push-origin-main/` 内 `*.md` | 过期副本，不维护 |
| `.icey-ref/**/*.md` | Flutter Icey 参考仓，非 Mica 文档 |

---

## 文档层级

```text
入口     README · DOC_INDEX · CONTEXT
设计     DESIGN_SPEC
进度     TODO
动效     MOTION（含 §七 岛分工）
播放页   PLAYER_PAGE_CONTRACT
         COVER_FLOW_IMPLEMENTATION（§0 + §1–12 封面流 + §13 拍立得）
         PARTICLE_COVER_OPENGL_MIGRATION（§0 粒子产品 + GLES parity）
         SHARED_ELEMENT_ANIMATION_NOTES
合规     OPEN_SOURCE_NOTICES
工具     REASONIX
```

---

## 可执行规约（非 Markdown）

| 位置 | 说明 |
|------|------|
| `.cursor/rules/audio-quality-consent.mdc` | **音质改动须事先说明并获明确允许**（Agent 始终生效） |
| `CONTEXT.md` → **Audio quality consent** | 同上，领域词汇 |
| `app/src/test/.../CoverFlowRailsTest.kt` | 封面流 `railOffset` 连续性 |
| `app/src/test/.../PlayerPageLayoutEngineTest.kt` | 播放页布局 |
| `gradle/libs.versions.toml` | 依赖版本真相源 |

---

## 修订记录

| 日期 | 说明 |
|------|------|
| 2026-06 | 初版三类归档 |
| 2026-06 | 执行清单：合并 COVER_FLOW/REVIEW_NOTES；修 README/DESIGN_SPEC/REASONIX/OPEN_SOURCE/MOTION |
| 2026-06-16 | 新增 `reviews/REFACTOR_PLAYBACK_ARCHITECTURE.md` 播放架构重构分支审查 |
| 2026-06-18 | 新增 `DSD_EXO_PLAYBACK.md`（Exo DSD 扩展与音效说明） |
| 2026-06-19 | 更新 `reviews/REFACTOR_PLAYBACK_ARCHITECTURE.md`：exoplayer-only / fe2457a Bugbot 第三轮 |
| 2026-06-20 | 新增 `PERFORMANCE_INVESTIGATION_02.md`；更新 `PERFORMANCE_INVESTIGATION.md` 封面流发热结论 |
| 2026-06-27 | 新增 `APPLE_MUSIC_DYNAMIC_BACKGROUND_RE.md` |
| 2026-07-04 | 文档与现网对齐：`CONTEXT`、`TODO`、`REASONIX`、`DESIGN_SPEC` v1.3、`COVER_FLOW` §13 拍立得、`PLAYER_PAGE_CONTRACT` 粒子/拍立得、粒子 §0、`README` |
| 2026-07-07 | 新增 `AUDIO_PIPELINE_DISCUSSION.md`（变速/频谱/Sonic/Hi-Res 探测/USB 前瞻讨论记录） |
| 2026-07-07 | 新增 `AUDIO_PIPELINE_REFACTOR.md`（可执行改造计划：Sink Profile、P0–P6、验收与测试） |
| 2026-07-08 | 更新 `AUDIO_PIPELINE_REFACTOR.md`：§0 一页摘要、§18 终态/Gate/验收、P1 过渡态与 §7.3 勘误 |
| 2026-07-07 | 新增 **Audio quality consent**：`.cursor/rules/audio-quality-consent.mdc`、`CONTEXT.md` |
