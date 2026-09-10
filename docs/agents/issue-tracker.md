# Issue tracker: Local Markdown

> 2026-09-11 复核：`.scratch/<feature-slug>/` issue/PRD 工作流继续有效；scratch 是临时协调/证据层，不应被 DOC_INDEX 当成发布文档，也不得用临时 issue 状态覆盖主仓库 living docs。

这个 repo 的 issues 和 PRDs 作为 markdown 文件存放在 `.scratch/` 中。

## Conventions

- 每个 feature 一个目录：`.scratch/<feature-slug>/`
- PRD 是 `.scratch/<feature-slug>/PRD.md`
- Implementation issues 是 `.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 开始编号
- Triage state 记录为每个 issue file 顶部附近的 `Status:` 行（role 字符串见 `triage-labels.md`）
- Comments 和 conversation history 追加到文件底部的 `## Comments` heading 下

## When a skill says "publish to the issue tracker"

在 `.scratch/<feature-slug>/` 下创建新文件（必要时创建目录）。

## When a skill says "fetch the relevant ticket"

读取引用路径处的文件。用户通常会直接传入路径或 issue number。
