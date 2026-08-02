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
