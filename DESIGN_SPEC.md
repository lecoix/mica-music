# HiFi 本地音乐播放器 · 设计规范 v1.3

> Android Jetpack Compose · 云母材质（浮岛 blur）+ 氛围渐变 + 极简尖角 · 发烧友定位

---

## 一、设计哲学

**一句话定义**：骨子里精准，外表上克制。尖角是底层规则，留白是表达手段，氛围色是情绪。


| 维度   | 主张                                                      |
| ---- | ------------------------------------------------------- |
| 形状语言 | 全直角（0dp 圆角），无圆形按钮，无胶囊药丸                                 |
| 视觉密度 | 极简，**用留白和字体层级分组，不用边框**                                  |
| 色彩   | 云母氛围色（mica）作为背景；**用户可选强调色**（默认紫韵）；Hi-Res 标记固定暖金，不随强调色变化 |
| 字体   | 中文为主，等宽字体承载技术参数                                         |
| 动效   | 克制，短促，不喧宾夺主                                             |
| 偶像   | Linear、Dieter Rams、专业 DAW（Ableton/Reaper）、Bandcamp      |


---

## 二、色彩系统

### 2.1 强调色（用户可选，`colors.accent`）

> 设置 → **强调色** 切换；全应用激活态、当前曲高亮、EQ 曲线、频谱等跟随所选色。  
> 实现：`AppAccentColor` + `MicaTheme.colors.accent`（见 `AppAccent.kt`）。


| 预设名    | 存储键       | Hex（浅色）            | 说明                                                 |
| ------ | --------- | ------------------ | -------------------------------------------------- |
| 紫韵（默认） | `purple`  | `#8B7AFF`          | 品牌默认                                               |
| 鎏金     | `gold`    | `#D4AC4F`          | 与 Hi-Res 金同色值，**仅作强调色**；Hi-Res 标签仍走 `colors.hiRes` |
| 青釉     | `teal`    | `#5BA8A0`          | —                                                  |
| 珊瑚     | `coral`   | `#E07A7A`          | —                                                  |
| 动态取色   | `dynamic` | 系统 Material You 主色 | Android 12+；低版本回退紫韵                                |
| 自定义     | `custom`  | 用户自选 Hex           | 设置对话框取色；与预设并列                                      |



| Token        | 颜色   | Hex             | 说明                     |
| ------------ | ---- | --------------- | ---------------------- |
| Primary Glow | 紫色发光 | `#A89BFF` @ 60% | 发光效果（实现可按当前 accent 派生） |


### 2.2 Hi-Res 金色（语义专用）


| 用途        | 颜色  | Hex       | 说明                                                     |
| --------- | --- | --------- | ------------------------------------------------------ |
| Hi-Res 标记 | 暖金  | `#D4AC4F` | **仅**用于 Hi-Res 圆点与「Hi-Res」文案（`colors.hiRes`），不随强调色设置改变 |


### 2.3 中性色（浅色模式）


| Token            | Hex                       | 用途               |
| ---------------- | ------------------------- | ---------------- |
| `text.primary`   | `#1A1A1A`                 | 主标题、列表行主文本       |
| `text.secondary` | `#6B6B6B`                 | 副标题、艺术家、时间戳      |
| `text.tertiary`  | `#9B9B9B`                 | 辅助说明、Tab 未激活、信息条 |
| `divider`        | `#000000` @ 8%            | 列表行之间、信息区分隔      |
| `surface.glass`  | `#FFFFFF` @ 60% + blur 24 | 迷你播放器、底栏         |


### 2.4 中性色（深色模式 / 播放页背景上）


| Token                | Hex                       | 用途           |
| -------------------- | ------------------------- | ------------ |
| `text.primary`       | `#FFFFFF`                 | 主文本          |
| `text.secondary`     | `#FFFFFF` @ 70%           | 副文本          |
| `text.tertiary`      | `#FFFFFF` @ 40%           | 非当前歌词、辅助信息   |
| `divider`            | `#FFFFFF` @ 12%           | 列表行之间        |
| `surface.glass.dark` | `#000000` @ 30% + blur 32 | 浅色背景上的深色玻璃叠层 |


### 2.5 云母氛围渐变（全局 App 背景）

> 设置 → **云母背景**（`MicaPreset` / `micaAppBackground()`）。这是**主页、侧栏、设置**等壳层的氛围渐变，**不是**播放页下半屏背景（见 §2.7）。饱和度故意偏低，避免干扰内容。

| 预设（设置标签） | storage | 浅色起始 → 结束 | 深色起始 → 结束 | 说明 |
| --- | --- | --- | --- | --- |
| 晨曦 `dawn` | `dawn` | `#FFF6EE` → `#E3EEF8` | `#081420` → `#8B4E28` | 默认浅色；暖雾→天光蓝 |
| 暮色 `dusk` | `dusk` | `#FFF4EB` → `#FFE8F2` | `#2A1810` → `#6B3A32` | 偏暖 |
| 午夜 `midnight` | `midnight` | `#F7F2E8` → `#E8E0F2` | `#141022` → `#251A3D` | 浅色为奶白→淡紫 |
| 极光 `aurora` | `aurora` | `#E6F5F0` → `#D8E8FF` | `#0A1F1A` → `#1E4A3D` | 青绿→浅蓝 |
| 雾霭 `fog` | `fog` | `#F5F5F8` → `#E8EBF0` | `#12121A` → `#1E1E2A` | 最克制 |
| 自定义 | `custom` | 用户自选起止色 | 同上 | 设置对话框 |

实现：`Color.kt`（`HifiPalette`）+ `MicaGradient.kt`。每预设均有浅色/深色两套端点。

> **v1.1 草案差异**：早期 spec 中「黎明/子夜/极光」的 hex 与语义已迭代（如「子夜」不再指 `#0D1B2A→#D4823A` 播放页专用渐变）。以 **`Color.kt` 现网值**为准；完整对照见 §十五。

### 2.6 语义色（最小化使用）


| Token              | Hex       | 用途           |
| ------------------ | --------- | ------------ |
| `semantic.like`    | `#FF6B6B` | 心形已收藏（`HifiColors.like`） |
| `semantic.warning` | `#F5A623` | 错误的文件格式、扫描异常（**规范色；主题 token 未落地**） |
| `semantic.success` | `#52C41A` | 极少使用，扫描完成（**规范色；主题 token 未落地**） |

### 2.7 播放页背景与封面行为（现网）

与 §2.5 云母背景**并列、可任意组合**；枚举与领域词汇见 `CONTEXT.md`。

**播放页背景**（`PlayerLowerBackgroundMode`，设置 → 播放页背景；默认 `COVER_GLOW`）：

| 设置标签 | 枚举 | 要点 |
| --- | --- | --- |
| 主题色 | `THEME` | 云母渐变，不用专辑取色 |
| 封面渐变 | `ARTWORK_GRADIENT` | Palette 取色，下半屏保持专辑色 |
| 封面模糊 | `COVER_GLOW` | API 31+ 全屏模糊 + 取色；低版本渐变兜底 |
| 动态烟云 | `DYNAMIC_LIGHT` | 低分辨率封面纹理 + GLES；**设置 UI 暂隐藏**，代码保留 |
| 流光溢彩 | `DYNAMIC_ARTWORK` | 多层封面纹理 + shader 切歌 crossfade |

**播放页封面行为**（`PlayerCoverFlowMode`，设置 → 播放页封面行为；默认 `STANDARD`）：

| 设置标签 | 枚举 | 要点 |
| --- | --- | --- |
| 标准 | `STANDARD` | 大封面 + 横向轻扫切歌 |
| 自定义标准 | `CUSTOM_STANDARD` | 标准封面手势 + 六组件自定义布局；竖屏可在播放页进入自由布局编辑，二维拖动/双指缩放/显隐以草稿编辑后保存；不支持下半屏沉浸 |
| 粒子封面 | `PARTICLE_COVER` | 边缘粒子化 + 切歌分解；现网 **GLES**（`ParticleCoverHost` / `ParticleCoverRenderer`）；WebView 回退见 `ThreeParticleCoverHost` |
| 平行封面带 | `PAUSE_FOLD` | 七轨 View 岛封面流；横屏稳定态可长按标题进入封面流沉浸 |
| 复古立体封面 | `RETRO_3D` | 透视封面流 + 倒影；横屏复用平行封面带的沉浸缩放契约 |
| 拍立得回忆 | `PHOTO_STACK` | 拍立得叠放转场（**局部圆角**，见 §十五） |

`PARTICLE_COVER`、`PAUSE_FOLD`、`RETRO_3D`、`PHOTO_STACK` 强制裁切填充；`CUSTOM_STANDARD` 保留标准封面显示策略。`CUSTOM_STANDARD` / `PARTICLE_COVER` / `PHOTO_STACK` 不支持下半屏沉浸。自由布局编辑仅提供竖屏入口，详细交互与保存/取消契约见 `docs/PLAYER_PAGE_CONTRACT.md`。


---

## 三、字体系统

### 3.1 字体族


| 角色       | 规范字体                | 现网实现（`Type.kt`） |
| -------- | ------------------- | ----------------- |
| 中文（主）    | `HarmonyOS Sans SC` | `FontFamily.Default`（系统 sans） |
| 英文 / 数字  | `Inter`             | 同上 |
| 等宽（技术参数） | `JetBrains Mono`    | `FontFamily.Monospace`（系统 mono） |

> **字体缺口**：内置字库与 `ui-text-google-fonts` 尚未接入；见 `docs/TODO.md`「内置第二套字体」。字号 token 数值已与 `HifiTypography` 对齐。


### 3.2 字号层级


| Token           | 字号   | 字重           | 行高   | 用途                |
| --------------- | ---- | ------------ | ---- | ----------------- |
| `display`       | 28sp | Bold         | 36sp | 页面大标题（"本地音乐"）     |
| `title.lg`      | 24sp | Bold         | 32sp | 播放页歌曲名            |
| `title.md`      | 18sp | SemiBold     | 26sp | 区块标题              |
| `title.sm`      | 16sp | SemiBold     | 24sp | 页内小标题             |
| `body.lg`       | 16sp | Medium       | 24sp | 列表行主文本（歌名）        |
| `body.md`       | 14sp | Regular      | 20sp | 正文段落              |
| `body.sm`       | 13sp | Regular      | 18sp | 列表行副文本（艺术家·专辑）    |
| `caption`       | 12sp | Regular      | 16sp | 辅助说明              |
| `mono.md`       | 12sp | Mono Regular | 16sp | FLAC/MP3 格式标签、时间戳 |
| `mono.sm`       | 11sp | Mono Regular | 14sp | 信息条（首歌·GB·扫描时间）   |
| `lyric.current` | 22sp | Bold         | 32sp | 当前歌词（带发光）         |
| `lyric.other`   | 16sp | Regular      | 24sp | 非当前歌词（@40% 透明）    |


---

## 四、间距系统（4dp 基础网格）


| Token        | 数值   | 用途            |
| ------------ | ---- | ------------- |
| `space.xxs`  | 2dp  | 紧密元素之间（图标和文字） |
| `space.xs`   | 4dp  | 小间隙           |
| `space.sm`   | 8dp  | 中等间隙、卡片内边距    |
| `space.md`   | 12dp | 标准间隙          |
| `space.lg`   | 16dp | 页面边距、行间距      |
| `space.xl`   | 24dp | 区块之间          |
| `space.xxl`  | 32dp | 大区块之间         |
| `space.xxxl` | 48dp | 顶部巨大留白        |


---

## 五、尺寸规范

### 5.1 触摸目标

- **最小可点击区域**：`48dp × 48dp`（遵循 Material 无障碍规范）
- **图标按钮**：图标 24dp，外围 padding 12dp，总 48dp

### 5.2 图标尺寸


| Token      | 数值   | 用途                  |
| ---------- | ---- | ------------------- |
| `icon.xs`  | 12dp | 行内指示（Hi-Res 圆点、激活点） |
| `icon.sm`  | 16dp | 列表行末尾、辅助图标          |
| `icon.md`  | 20dp | 工具栏标准图标             |
| `icon.lg`  | 24dp | 顶部导航、底部导航           |
| `icon.xl`  | 32dp | 播放控制（上一首/下一首）       |
| `icon.xxl` | 48dp | 主播放按钮（三角形）          |


### 5.3 缩略图


| Token             | 数值           | 用途              |
| ----------------- | ------------ | --------------- |
| `cover.xs`        | 32dp         | 迷你播放器           |
| `cover.sm`        | 44dp         | 列表行             |
| `cover.md`        | 80dp         | 卡片缩略图           |
| `cover.lg`        | 240dp        | 歌单详情大封面         |
| `cover.fullwidth` | screen width | 播放页全屏封面（顶部~45%） |


### 5.4 容器宽高

- 顶部 AppBar 高度：`56dp`
- 底部导航高度：`72dp`（带文字）/ `56dp`（仅图标）
- 列表行高度：`64dp`（带缩略图）/ `48dp`（无缩略图）
- 迷你播放器高度：`56dp`（规范）；**现网浮岛/极简均为 `64dp`**（见 §十五）

---

## 六、形状（**核心规则**）


| 元素       | 圆角                     |
| -------- | ---------------------- |
| **所有元素** | **0dp（直角）**            |
| 缩略图      | 0dp                    |
| 按钮       | 0dp（且尽量用文字按钮，避免矩形填充按钮） |
| 卡片       | 0dp，但**优先不要卡片**，用留白分组  |
| 进度条      | 0dp（端点也是直角）            |
| 滑块滑头     | 矩形小条（28dp × 3dp）       |


---

## 七、边框 / 分隔


| 用途    | 规则                             |
| ----- | ------------------------------ |
| 列表行分隔 | hairline `1dp` 横线，`divider` 颜色 |
| 边框    | **几乎不用**——仅在缩略图占位符或扫描中状态使用     |
| 强调态指示 | 紫色 `2dp` 直线（下划线 / 左侧竖线）        |


---

## 八、模糊与质感

### 8.1 云母 / 毛玻璃

**设计意图（视觉目标）**

| 模式 | 叠色 | 模糊半径 |
|------|------|----------|
| 浅色 | `surface.glass`：`#FFFFFF` @ 60% | 24dp |
| 深色 | `surface.glass.dark`：`#000000` @ 30% | 32dp |

效果是 **backdrop blur**：先透视并模糊**背后**内容（如滚动的歌曲列表），再叠半透明 tint——同 Windows Fluent Mica / iOS 毛玻璃，**不是**只糊一层色块。

**实现约束（勿用 Compose `Modifier.blur()` 冒充）**

- Compose 的 `Modifier.blur()` / 对单个 `Box` 的 `BlurEffect` 只模糊**该层自己绘制的内容**，采不到背后的列表像素；调半径与 alpha **做不出**真毛玻璃。
- 需要模糊背后内容时 → **View 岛**：`BlurView` 3.x + `BlurTarget` + `surface.glass` tint。栈选型见 [`docs/MOTION.md`](docs/MOTION.md) **§七**。
- Compose `BlurEffect` 仅用于糊**自身**图形（如浮岛柔影 `FloatingIslandShadowHalo`），不用于 backdrop。

**现网（浮岛迷你栏 `FLOATING_ISLAND`）**

- `MicaMaterialBackdrop`（`MicaMaterialCard.kt`）+ `MainActivity` 双 `ComposeView` / `BlurTarget` 兄弟结构。
- 顶 hairline + tint；极简 Hi‑Fi（`AUDIOPHILE`）为不透明通栏，未接 blur。
- **参数与视觉目标的差距**（`MiniPlayer.kt` 有意压低 blur 以保性能/可读性）：

| 项 | 规范（§8.1 视觉目标） | 现网 |
| --- | --- | --- |
| blur 半径 | 24dp / 32dp | **4dp / 5dp** |
| glass alpha | `#FFFFFF` @ 60% | `surfaceGlass.alpha × 0.1375` |
| 卡片高度 | 56dp | **64dp** |
| 封面 | 32dp | **48dp** |
| 柔影 | 几乎不用 | `FloatingIslandShadowHalo`（Compose 自绘，非 elevation） |

### 8.2 阴影

- **几乎不用阴影**。一切扁平化。
- 唯一例外：迷你播放器悬浮于内容上方时，顶部加一条 hairline 分隔线（不是阴影）

---

## 九、动效

> **权威说明见 [`docs/MOTION.md`](docs/MOTION.md)**：时长 token（Short 200 / Medium 320 / Long 400）、场景映射（§六）、开发约束（§八）、**Compose 与 View 岛分工（§七）**。

本规范不重复维护时长表。实现状态速查：

| 场景 | 状态 | 文档 |
|------|------|------|
| 主页 / 浏览 / Nav / 主题 / 沉浸 | 已实现 | `MOTION.md` §六 |
| 迷你栏 → 播放页共享封面 | 第一版已实现 | `SHARED_ELEMENT_ANIMATION_NOTES.md` |
| 封面流（平行 / 复古）切歌 / 拖动 | 已实现（View 岛） | `COVER_FLOW_IMPLEMENTATION.md` |
| 粒子封面 / 拍立得转场 | 已实现（View / GLES 岛） | `CONTEXT.md`、`PARTICLE_COVER_OPENGL_MIGRATION.md` §0、`COVER_FLOW_IMPLEMENTATION.md` §13 |
| 列表项 → 播放共享元素 | 待做 | `TODO.md` |
| 歌词行切换 / 双语动效 | 待做 | `TODO.md` |

---

## 十、组件模式（设计语言核心）

### 10.1 激活态（统一规则）

> **不用填充、不用胶囊、不用阴影。** 分两类表达，均使用当前 `colors.accent`（默认紫韵）。

#### A. 导航级（Tab / 底栏）— 强调色 + `2dp` 直线


| 场景      | 表达                                                |
| ------- | ------------------------------------------------- |
| Tab 激活  | 文字下方 `2dp` 横线（accent），`title.sm` + `text.primary` |
| Tab 未激活 | `body.md` + `text.tertiary`，无下划线                  |
| 底部导航激活  | 图标与文案为 accent，图标下方 `2dp` 横线                       |
| 底部导航未激活 | `text.tertiary`                                   |


#### B. 选项级（设置 / 排序 / EQ 预设）— **纯字色**


| 场景    | 表达                                            |
| ----- | --------------------------------------------- |
| 选项激活  | `title.sm` + **accent 字色**                    |
| 选项未激活 | `body.md` + `text.tertiary`                   |
| 布局    | `FlowRow` 可换行；项间距 `space.sm`；**无下划线、无背景、无边框** |
| 实现    | `AccentTextChoice`                            |


#### C. 其他


| 场景       | 表达                                          |
| -------- | ------------------------------------------- |
| 列表行当前曲   | 左侧 `2dp` accent 竖线，歌名 accent，播放中显示波形        |
| Toggle 开 | 文案 accent + `6dp` accent 方块点                |
| Toggle 关 | `text.tertiary`                             |
| 空状态 CTA  | **紫色文字链接**（主：`title.sm`，次：`body.md`），无按钮底/框 |


### 10.2 Hi-Res 视觉签名

```
●  Hi-Res
```

- 现网提供三种样式：**默认**、**黄底镂空**、**自定义图片**（`HiResBadgeStyle`）。
- 默认与黄底样式仍使用 Hi‑Fi 信息行的紧凑标志；自定义图片使用等比适配并在文件失效时回退默认样式。
- **现网出现位置**：播放页 Hi‑Fi 信息行旁（`HiFiBadgeSection`）；音乐库分析页。仍**未**出现在列表行。
- 样式由设置持久化；本文不把某一种样式描述为唯一视觉规范。

### 10.3 HiFi 信息行（播放页专用）

格式：`FLAC · 24bit/96kHz · 2.4 MB/s`

- 等宽字体 `mono.md`
- 颜色：`text.tertiary`（深色背景上为 `#FFFFFF40`）
- 中点 `·` 分隔（前后各 1 空格）
- 无边框，无背景

### 10.4 列表行（歌曲）

```
┌────────────────────────────────────────────────┐
│ [封面]  歌名加粗                FLAC 24/96  ⋯  │
│ 44dp    艺术家 · 专辑 · 4:05                    │
└────────────────────────────────────────────────┘
                ↓ hairline 1dp
```

- 高度 64dp
- 左侧封面 44×44dp，0dp 圆角
- 右侧格式标签：`mono.sm`，无框
- 末尾三点按钮：48dp 触摸目标
- 行间分隔：hairline @ 8% opacity

### 10.5 进度条

- 总高度 32dp（含上下间距）
- 进度线本身高度 2dp
- 未播放部分：`text.tertiary` @ 30%
- 已播放部分：纯色（深色背景上为白，浅色为黑）
- 滑块/播放头：`2dp × 12dp` 矩形竖条，跟随当前位置（**规范**）
- 两侧时间戳 `mono.md`

> **现网 `HiFiSeekBar`**：2dp（默认 3dp 可配）双色条，**无独立 thumb 竖条**；拖动时整条覆盖区高亮。见 §十五。

#### 10.5.1 横向 100 点滑杆：B / HiFi Track

用于普通连续调节、播放进度或需要快速定位的 `0..100` 参数。内部步进为 `1`，但不在屏幕上绘制 101 个可见刻度。

```text
0..100                                      当前值 63
0 ━━━━━━━━━━━━━━━━━━━■━━━━━━━━━━━━━━━━━━ 100
```

- 可视轨道：`3dp`；未选中部分使用 `text.tertiary` @ 30%，选中部分使用 `colors.accent`。
- 滑块：**`12dp × 12dp` 方形**，`0dp` 圆角、无阴影；这是对 §六通用矩形滑块规则的 B/C 专用特例。
- 触控区：高度至少 `48dp`，轨道位于触控区中心；点击轨道跳转，拖动实时更新，松手提交。
- 数值行独立于轨道，使用 `mono.md`；左侧固定显示限制范围，右侧固定显示当前值；范围端点不挤占轨道宽度。
- `360dp` 屏幕按左右 `16dp` 边距拥有 `328dp` 内容宽度，轨道应使用剩余全宽；不得保留左侧大标题栏。

#### 10.5.2 横向 100 点滑杆：C / Fine Tune

用于需要单点精调的设置。主滑杆仍为 `0..100`、步进 `1`，并提供 `-1` / `+1` 微调动作。

```text
        −1       ━━━━━━━━━━━■━━━━━━━━━━       +1
```

- 滑块：与 B 一致，固定为 **`12dp × 12dp` 方形**、`0dp` 圆角、无阴影。
- 宽屏排列：`48dp` `−1` 触控目标 + `8dp` 间距 + 至少 `200dp` 滑轨 + `8dp` 间距 + `48dp` `+1` 触控目标；`360dp` 屏幕可放入 `328dp` 内容宽度。
- `−1` / `+1` 只显示文字或图标，不绘制额外方形按钮；视觉控件保持项目的直角、无胶囊规则，语义触控区仍为 `48dp`。
- 窄屏断点：当屏幕宽度 `<344dp` 时，把 `−1` / `+1` 移到数值行，滑轨独占下一行；不得压缩到无法进行单点拖动的宽度。
- 拖动期间数值实时更新；点击 `−1` / `+1` 每次改变一个点，长按允许连续改变，并在无障碍语义中暴露当前值与 `0..100` 范围。

### 10.6 快捷操作（主页）

```
[图标]  随机播放
        全部
```

- 仅图标 + 两行文字
- **无背景，无边框**
- 列方向布局，整体左对齐
- 行之间用 `space.xl` (24dp) 分隔

### 10.7 文字选项（`AccentTextChoice`）

用于：`SettingsChoiceRow`、排序 Sheet、EQ 预设条等。

```
  紫韵    鎏金    青釉
  ^accent+SemiBold   tertiary+Regular
```

- 激活：`typography.titleSm` + `colors.accent`
- 未激活：`typography.bodyMd` + `colors.textTertiary`
- 内边距：水平 `space.sm`，垂直 `space.xxs`（紧凑，不预留下划线高度）
- 禁用：`text.tertiary` @ 50%，不可点

### 10.8 空状态（`EmptyState`）

- 结构：图标 → 标题 `title.md` → 副标题 `body.md` → 可选 CTA 链接
- 主 CTA：`title.sm` + accent，上间距 `space.md`
- 次 CTA：`body.md` + accent，上间距 `space.xs`
- 扫描中：仅 `CircularProgressIndicator`，无 CTA

---

## 十一、Jetpack Compose 实现

### 11.1 文件结构（现网）

```
app/src/main/java/com/mica/music/
├── data/AppAccentColor.kt          # 强调色预设（含 CUSTOM）
├── data/PlayerLowerBackgroundMode.kt
├── data/PlayerCoverFlowMode.kt
├── data/MiniPlayerStyle.kt
├── ui/theme/
│   ├── Color.kt                    # HifiPalette + HifiColors
│   ├── AppAccent.kt                # accent 解析（含动态取色）
│   ├── Type.kt                     # HifiTypography
│   ├── Spacing.kt / Shapes.kt
│   ├── Theme.kt                    # MicaTheme（非示例中的 HifiTheme）
│   ├── MicaGradient.kt             # MicaPreset + micaAppBackground()
│   └── MicaMaterialCard.kt         # BlurView 浮岛 backdrop
└── ui/components/
    ├── AccentTextChoice.kt
    ├── HiResIndicator.kt
    ├── EmptyState.kt
    ├── MiniPlayer.kt
    └── …
```

§11.2–11.7 为**结构参考示例**（包名 `com.yourapp` 需读作 `com.mica.music`）；运行时 API 以 **`MicaTheme`** 为准。

### 11.2 Color.kt

```kotlin
package com.yourapp.ui.theme

import androidx.compose.ui.graphics.Color

object HifiPalette {
    val PurplePrimary = Color(0xFF8B7AFF)
    val PurpleGlow = Color(0xFFA89BFF)
    val HiResGold = Color(0xFFD4AC4F)
    val LikeRed = Color(0xFFFF6B6B)

    val NeutralBlack = Color(0xFF1A1A1A)
    val NeutralGray600 = Color(0xFF6B6B6B)
    val NeutralGray400 = Color(0xFF9B9B9B)
    val NeutralWhite = Color(0xFFFFFFFF)

    val MicaDawnStart = Color(0xFFF7F2E8)
    val MicaDawnEnd = Color(0xFFE8E0F2)
    val MicaDuskStart = Color(0xFFFFE6CC)
    val MicaDuskEnd = Color(0xFFFFCCD9)
    val MicaAuroraStart = Color(0xFF1A0B2E)
    val MicaAuroraEnd = Color(0xFF3B2266)
    val MicaFogStart = Color(0xFFF5F5F8)
    val MicaFogEnd = Color(0xFFE8EBF0)
}

data class HifiColors(
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val surfaceGlass: Color,
    val accent: Color = HifiPalette.PurplePrimary,
    val hiRes: Color = HifiPalette.HiResGold,
)

val LightHifiColors = HifiColors(
    textPrimary = HifiPalette.NeutralBlack,
    textSecondary = HifiPalette.NeutralGray600,
    textTertiary = HifiPalette.NeutralGray400,
    divider = HifiPalette.NeutralBlack.copy(alpha = 0.08f),
    surfaceGlass = HifiPalette.NeutralWhite.copy(alpha = 0.60f),
)

val DarkHifiColors = HifiColors(
    textPrimary = HifiPalette.NeutralWhite,
    textSecondary = HifiPalette.NeutralWhite.copy(alpha = 0.70f),
    textTertiary = HifiPalette.NeutralWhite.copy(alpha = 0.40f),
    divider = HifiPalette.NeutralWhite.copy(alpha = 0.12f),
    surfaceGlass = HifiPalette.NeutralBlack.copy(alpha = 0.30f),
)
```

### 11.3 Type.kt

```kotlin
package com.yourapp.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val SansFamily = FontFamily.Default
private val MonoFamily = FontFamily.Monospace

data class HifiTypography(
    val display: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp
    ),
    val titleLg: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp
    ),
    val titleMd: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 26.sp
    ),
    val titleSm: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    val bodyLg: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    val bodyMd: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    val bodySm: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp
    ),
    val caption: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    val monoMd: TextStyle = TextStyle(
        fontFamily = MonoFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    val monoSm: TextStyle = TextStyle(
        fontFamily = MonoFamily, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 14.sp
    ),
    val lyricCurrent: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 32.sp
    ),
    val lyricOther: TextStyle = TextStyle(
        fontFamily = SansFamily, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
)
```

### 11.4 Spacing.kt

```kotlin
package com.yourapp.ui.theme

import androidx.compose.ui.unit.dp

object HifiSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

object HifiSize {
    val iconXs = 12.dp
    val iconSm = 16.dp
    val iconMd = 20.dp
    val iconLg = 24.dp
    val iconXl = 32.dp
    val iconXxl = 48.dp

    val coverXs = 32.dp
    val coverSm = 44.dp
    val coverMd = 80.dp
    val coverLg = 240.dp

    val touchTarget = 48.dp
    val topBarHeight = 56.dp
    val bottomNavHeight = 72.dp
    val miniPlayerHeight = 56.dp
    val listRowHeight = 64.dp

    val dividerHairline = 1.dp
    val accentBarWidth = 2.dp
}
```

### 11.5 Shapes.kt

```kotlin
package com.yourapp.ui.theme

import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.material3.Shapes

val HifiShapes = Shapes(
    extraSmall = RectangleShape,
    small = RectangleShape,
    medium = RectangleShape,
    large = RectangleShape,
    extraLarge = RectangleShape,
)
```

### 11.6 Theme.kt

```kotlin
package com.yourapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*

val LocalHifiColors = staticCompositionLocalOf { LightHifiColors }
val LocalHifiTypography = staticCompositionLocalOf { HifiTypography() }

@Composable
fun HifiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkHifiColors else LightHifiColors
    val typography = HifiTypography()

    CompositionLocalProvider(
        LocalHifiColors provides colors,
        LocalHifiTypography provides typography,
    ) {
        MaterialTheme(
            shapes = HifiShapes,
            content = content,
        )
    }
}

object HifiTheme {
    val colors: HifiColors
        @Composable get() = LocalHifiColors.current
    val typography: HifiTypography
        @Composable get() = LocalHifiTypography.current
}
```

### 11.7 MicaGradient.kt

```kotlin
package com.yourapp.ui.theme

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class MicaPreset { Dawn, Dusk, Midnight, Aurora, Fog, CUSTOM }

fun Modifier.micaBackground(preset: MicaPreset): Modifier {
    val (start, end) = when (preset) {
        MicaPreset.Dawn -> HifiPalette.MicaDawnStart to HifiPalette.MicaDawnEnd
        MicaPreset.Dusk -> HifiPalette.MicaDuskStart to HifiPalette.MicaDuskEnd
        MicaPreset.Aurora -> HifiPalette.MicaAuroraStart to HifiPalette.MicaAuroraEnd
        MicaPreset.Fog -> HifiPalette.MicaFogStart to HifiPalette.MicaFogEnd
    }
    return this.background(Brush.verticalGradient(listOf(start, end)))
}

fun Modifier.micaFromArtwork(dominantColor: Color, vibrantColor: Color): Modifier {
    return this.background(
        Brush.verticalGradient(
            listOf(dominantColor.copy(alpha = 0.95f), vibrantColor.copy(alpha = 0.85f))
        )
    )
}
```

---

## 十二、可复用组件示例

### 12.1 Hi-Res 标签

```kotlin
@Composable
fun HiResIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.xs)
    ) {
        Box(
            Modifier
                .size(HifiSize.iconXs / 2)
                .background(HifiTheme.colors.hiRes)
        )
        Text(
            "Hi-Res",
            style = HifiTheme.typography.caption,
            color = HifiTheme.colors.hiRes
        )
    }
}
```

### 12.2 HiFi 格式信息行

```kotlin
@Composable
fun HiFiInfoRow(
    format: String,   // "FLAC"
    quality: String,  // "24bit/96kHz"
    bitrate: String,  // "2.4 MB/s"
) {
    Text(
        text = "$format · $quality · $bitrate",
        style = HifiTheme.typography.monoMd,
        color = HifiTheme.colors.textTertiary,
    )
}
```

### 12.3 文字开关

```kotlin
@Composable
fun TextToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onLabel: String = "开",
    offLabel: String = "关",
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
        modifier = Modifier
            .clickable { onCheckedChange(!checked) }
            .padding(HifiSpacing.sm),
    ) {
        Text(
            if (checked) onLabel else offLabel,
            style = HifiTheme.typography.bodyMd,
            color = if (checked) HifiTheme.colors.accent else HifiTheme.colors.textTertiary,
        )
        if (checked) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(HifiTheme.colors.accent)
            )
        }
    }
}
```

### 12.4 列表行（歌曲）

```kotlin
@Composable
fun SongRow(
    coverUrl: String?,
    title: String,
    artist: String,
    album: String,
    duration: String,
    format: String,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(HifiSize.listRowHeight)
            .clickable(onClick = onClick)
    ) {
        // 左侧紫色激活竖条
        Box(
            Modifier
                .width(HifiSize.accentBarWidth)
                .fillMaxHeight()
                .background(if (isPlaying) HifiTheme.colors.accent else Color.Transparent)
        )

        Spacer(Modifier.width(HifiSpacing.md))

        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            modifier = Modifier.size(HifiSize.coverSm),
        )

        Spacer(Modifier.width(HifiSpacing.md))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = HifiTheme.typography.bodyLg,
                    color = if (isPlaying) HifiTheme.colors.accent else HifiTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isPlaying) {
                    Spacer(Modifier.width(HifiSpacing.sm))
                    PlayingIndicator()
                }
            }
            Text(
                "$artist · $album · $duration",
                style = HifiTheme.typography.bodySm,
                color = HifiTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            format,
            style = HifiTheme.typography.monoSm,
            color = HifiTheme.colors.textTertiary,
            modifier = Modifier.padding(end = HifiSpacing.md),
        )

        IconButton(onClick = onMoreClick) {
            Icon(
                Icons.Default.MoreHoriz,
                contentDescription = "更多",
                tint = HifiTheme.colors.textTertiary,
            )
        }
    }

    HorizontalDivider(
        thickness = HifiSize.dividerHairline,
        color = HifiTheme.colors.divider,
    )
}
```

### 12.5 文字选项（纯字色激活）

```kotlin
@Composable
fun AccentTextChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = MicaTheme.colors
    val typography = MicaTheme.typography
    Text(
        text = label,
        style = if (selected && enabled) typography.titleSm else typography.bodyMd,
        color = when {
            !enabled -> colors.textTertiary.copy(alpha = 0.5f)
            selected -> colors.accent
            else -> colors.textTertiary
        },
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = HifiSpacing.sm, vertical = HifiSpacing.xxs),
    )
}
```

### 12.6 Tab 行（带下划线指示）

```kotlin
@Composable
fun MinimalTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.xl),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = HifiSpacing.lg)
    ) {
        tabs.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onTabSelected(index) }
            ) {
                Text(
                    label,
                    style = if (active) HifiTheme.typography.titleSm
                            else HifiTheme.typography.bodyMd,
                    color = if (active) HifiTheme.colors.textPrimary
                            else HifiTheme.colors.textTertiary,
                    modifier = Modifier.padding(vertical = HifiSpacing.sm),
                )
                Box(
                    Modifier
                        .width(24.dp)
                        .height(HifiSize.accentBarWidth)
                        .background(
                            if (active) HifiTheme.colors.accent else Color.Transparent
                        )
                )
            }
        }
    }
}
```

---

## 十三、依赖（版本真相源）

> **以 `gradle/libs.versions.toml` + version catalog 为准**；下列为 2026-08 快照，升级后请同步本文。

| 类别 | 坐标 / 插件 | 版本 |
| --- | --- | --- |
| Kotlin | `org.jetbrains.kotlin.android` | 2.2.21 |
| AGP | `com.android.application` | 8.7.0 |
| Gradle | wrapper | 8.9 |
| Compose BOM | `androidx.compose:compose-bom` | 2024.10.00 |
| Activity Compose | `androidx.activity:activity-compose` | 1.9.2 |
| Lifecycle | `lifecycle-runtime-ktx` 等 | 2.8.6 |
| Navigation Compose | `navigation-compose` | 2.8.2 |
| Media3 ExoPlayer / Session / Decoder | `androidx.media3:*` | **1.9.0** |
| Media3 FFmpeg 扩展 | `org.jellyfin.media3:media3-ffmpeg-decoder` | **1.9.0+1**（`third_party/media3-ffmpeg-decoder`） |
| Room | `room-runtime` / KSP | 2.6.1 |
| Coil | `coil-compose` | 2.7.0 |
| Coroutines | `kotlinx-coroutines-android` | 1.8.1 |
| Palette | `palette-ktx` | 1.0.0 |
| BlurView | `com.github.Dimezis:BlurView` (JitPack) | 3.2.0 |
| Reorderable | `sh.calvin.reorderable` | 2.4.3 |
| Mica TagLib fork / jAudiotagger | 元数据 | vendored（基于 Kyant0 1.0.6；TagLib C++ 2.2.1）/ 3.0.1 |
| 测试 | JUnit / Robolectric / MockK / Roborazzi | 4.13.2 / 4.13 / 1.13.13 / 1.34.0 |

**平台**：`minSdk 26`，`targetSdk 34`，`compileSdk 35`，**仅 arm64-v8a**。

**v1.1 草案已移除或未采用的依赖**：直接 `implementation("androidx.media3:media3-exoplayer-hls")`、Compose `ui-text-google-fonts`（字体 spec 仍未落地）。

```kotlin
// app/build.gradle.kts — 声明方式示例（实际用 libs.xxx）
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer.ffmpeg) // Jellyfin 扩展
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.blurview)
    // …见 libs.versions.toml
}
```

---

## 十四、页面规范进度

| 页面 / 模块 | 状态 | 说明 |
| --- | --- | --- |
| 设置 · 外观 | ✅ | `SettingsCategory.APPEARANCE`：主题、强调色、云母背景（含 CUSTOM）、自定义壁纸、状态栏四档隐藏范围、迷你播放栏 |
| 设置 · 播放页 | ✅ | `PLAYBACK`：播放页背景（5 模式，UI 暂藏动态烟云）、封面行为（6 模式）、封面显示、信息行、频谱、能力相关的沉浸/封面底边进度；`CUSTOM_STANDARD` 详情提供“进入播放页布局编辑”入口 |
| 设置 · 歌词 | ✅ | `LYRICS`：歌词主题、对齐/字号/双语/逐字、歌词优先级、通知/信息行等歌词输出 |
| 列表 / 专辑 / 艺术家显示设置 | ✅ | 不再是 `SettingsCategory`；歌曲排序 Sheet、专辑/艺术家浏览 Sheet 等上下文入口各自持久化显示选项 |
| 设置 · 曲库与扫描 | ✅ | `LIBRARY`：曲库文件夹、重扫、排除目录、最短时长、深度分析、艺术家分割 |
| 设置 · 音频与设备 | ✅ | `AUDIO`：ReplayGain、音频焦点等播放/设备相关偏好 |
| 设置 · 诊断与系统 | ✅ | `DIAGNOSTICS`：offload 状态与重试、元数据调试、系统空间音频、系统权限与应用信息 |
| 歌单管理 | ⚠️ | 创建 / 详情 / 删除已有；**无**智能歌单条件 |
| 专辑 / 歌手聚合 | ⚠️ | 列表 + 九宫格已有；视觉规范未单独成文 |
| 首次启动 / 空状态 | ✅ | `EmptyState` + 文字链接 CTA |
| 统一错误状态页 | ❌ | 播放错误 inline + Snackbar；无全局错误 UI 规范 |
| EQ | ✅ | 10 段软件 EQ；见 `EqualizerScreen`，不在本 spec 组件示例中 |

---

## 十五、规范与现网实现对照

> 2026-08-12 对照 `app/src/main/java/com/mica/music/`。  
> **已对齐**：不必改代码即可视为达标。**有意偏离**：产品/性能取舍，规范已更新或标注。**缺口**：规范仍有效但代码未做到。

### 15.1 已对齐

| 领域 | 说明 |
| --- | --- |
| 强调色 hex | 紫/鎏金/青/珊瑚与 `HifiPalette` 一致 |
| 中性色 token | `text.*`、`divider`、`surface.glass` 与 `Light/DarkHifiColors` 一致 |
| Hi‑Res 金色 | `#D4AC4F`，不随 accent |
| 字号层级 | `HifiTypography` 与 §3.2 数值一致 |
| 形状 | `Shapes.kt` 全档 0dp；Compose 主体直角 |
| 间距 token | `HifiSpacing` / `HifiSize` 与 §四、§五一致 |
| 动效时长 | `MicaMotion` 200 / 320 / 400 ms |
| 封面显示 | `CROP_FILL` / `FIT_ORIGINAL` |
| 激活态模式 | `AccentTextChoice` 纯字色；Tab 下划线 |
| 空状态 | `EmptyState` + 扫描 `CircularProgressIndicator` |

### 15.2 有意偏离（规范已修订或标注）

| 领域 | 规范原意 | 现网 | 备注 |
| --- | --- | --- | --- |
| 云母 hex / 语义 | v1.1 黎明/子夜/极光表 | §2.5 现网表 + `Color.kt` | 晨曦/午夜等对调迭代 |
| 播放页氛围 | 封面 Palette 动态 mica | 独立 `PlayerLowerBackgroundMode` 五选一 | §2.7 |
| 全局 vs 播放背景 | 混为一谈 | `MicaPreset` 与播放页背景分离 | 可任意组合 |
| 浮岛 blur 强度 | 24–32dp + 60% glass | 4–5dp blur + 低 alpha 缩放 | §8.1；性能/可读性 |
| 迷你栏尺寸 | 56dp 高 / 32dp 封面 | 64dp / 48dp | `MiniPlayer.kt` |
| Hi‑Res 标记形状 | 圆点 | 6dp 方形 | `HiResIndicator.kt` |
| 进度条 thumb | 2×12dp 竖条 | 无 thumb，双色条 | `HiFiSeekBar.kt` |
| 拍立得封面 | 全直角 | `PhotoStackTransitionView` 圆角 | 该主题专用 |
| 紧凑歌词字号 | `lyric.current` 22sp | 播放页紧凑区约为 token × 2/3 | `LyricsDisplay.kt` |
| 强调色 / 云母 | 四预设 | 各增 **CUSTOM** | 对话框取色 |

### 15.3 规范仍有效、代码未做到（缺口）

| 领域 | 规范要求 | 现网 | 优先级 |
| --- | --- | --- | --- |
| 字体族 | HarmonyOS Sans / Inter / JetBrains Mono | 系统 Default / Monospace | 中（见 TODO 内置字体） |
| 语义 warning/success | §2.6 token | 未进 `HifiColors` | 低 |
| Primary Glow | `#A89BFF` 发光 | 已定义未使用 | 低 |
| Hi‑Res 列表行 | DSD/24bit+ 文件旁标记 | 仅播放页/分析页 | 中 |
| Hi‑Res 直通设置 | 设置项 | 无 | 低（产品未做） |
| 进度条播放头 | 矩形 thumb | 无 | 中（seek 仍可用） |
| 横向 100 点滑杆 C | C 方形 thumb 与窄屏断点 | 尚无独立通用组件；B 已由 `SettingsSliderRow` 实现，限制范围左、当前值右 | 中（C 代码待实现） |
| 歌词切句动效 | §九待做 | 无 `AnimatedContent` | 低 |
| 列表→播放共享元素 | §九待做 | 无 | 中 |

### 15.4 现网超出 v1.1 草案（规范已补录）

| 能力 | 位置 |
| --- | --- |
| 播放页背景 5 模式 | §2.7、`PlayerLowerBackgroundMode.kt` |
| 封面行为 6 模式 | §2.7、`PlayerCoverFlowMode.kt`（含 `CUSTOM_STANDARD`） |
| 设置 6 大类 | `SettingsScreen.kt` → `SettingsCategory` |
| 通知歌词 | 设置 → 歌词 → 歌词输出 |
| 粒子封面 GLES 现网 / WebView 回退 | `ParticleCoverHost`、`ThreeParticleCoverHost`（回退路径待退役） |
| Hi‑Res 标志三种样式 | `HiResBadgeStyle.kt`、播放页设置 |
| 深色云母每预设双端点 | `Color.kt` `*DarkStart/*DarkEnd` |
| `HifiColors.surfaceCard` / `like` / `isDark` | `Color.kt` |

---

**版本**：v1.3  
**最后更新**：2026-08-12
**适用平台**：Android 8.0+（minSdk 26）/ Jetpack Compose BOM 2024.10+
