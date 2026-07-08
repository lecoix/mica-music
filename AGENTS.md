## Agent skills

### Issue tracker

Issues 和 PRDs 以 markdown 文件存放在 `.scratch/<feature-slug>/` 下。详见 `docs/agents/issue-tracker.md`。

### Triage labels

五个 canonical triage roles 使用默认 label 字符串。详见 `docs/agents/triage-labels.md`。

### Domain docs

Single-context 布局：根目录 `CONTEXT.md` + `docs/adr/`（尚未创建，skills 会在需要时懒创建）。详见 `docs/agents/domain.md`。

### 音质改动（硬性）

任何可能**降音质**的改动须**事先向用户明确说明影响范围**，并**得到明确允许**后才能实现或默认启用。详见 `CONTEXT.md`（**Audio quality consent**）与 `.cursor/rules/audio-quality-consent.mdc`。
