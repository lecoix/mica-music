# HiFi 本地音乐播放器 · 设计规范 v1.0

> Android Jetpack Compose · 云母氛围 + 极简尖角 · 发烧友定位

---

## 一、设计哲学

**一句话定义**：骨子里精准，外表上克制。尖角是底层规则，留白是表达手段，氛围色是情绪。

| 维度 | 主张 |
|------|------|
| 形状语言 | 全直角（0dp 圆角），无圆形按钮，无胶囊药丸 |
| 视觉密度 | 极简，**用留白和字体层级分组，不用边框** |
| 色彩 | 云母氛围色（mica）作为背景，紫色作为唯一强调色，金色仅用于 Hi-Res 标记 |
| 字体 | 中文为主，等宽字体承载技术参数 |
| 动效 | 克制，短促，不喧宾夺主 |
| 偶像 | Linear、Dieter Rams、专业 DAW（Ableton/Reaper）、Bandcamp |

---

## 二、色彩系统

### 2.1 强调色（唯一）

| 用途 | 颜色 | Hex | 说明 |
|------|------|-----|------|
| Primary 强调 | 柔和紫 | `#8B7AFF` | 激活态、正在播放、品牌色 |
| Primary Glow | 紫色发光 | `#A89BFF` @ 60% | 用于发光效果、EQ 曲线 |

### 2.2 Hi-Res 金色（专用）

| 用途 | 颜色 | Hex | 说明 |
|------|------|-----|------|
| Hi-Res 标记 | 暖金 | `#D4AC4F` | 仅用于 Hi-Res 圆点和金标，不用于其他场景 |

### 2.3 中性色（浅色模式）

| Token | Hex | 用途 |
|-------|-----|------|
| `text.primary` | `#1A1A1A` | 主标题、列表行主文本 |
| `text.secondary` | `#6B6B6B` | 副标题、艺术家、时间戳 |
| `text.tertiary` | `#9B9B9B` | 辅助说明、Tab 未激活、信息条 |
| `divider` | `#000000` @ 8% | 列表行之间、信息区分隔 |
| `surface.glass` | `#FFFFFF` @ 60% + blur 24 | 迷你播放器、底栏 |

### 2.4 中性色（深色模式 / 播放页背景上）

| Token | Hex | 用途 |
|-------|-----|------|
| `text.primary` | `#FFFFFF` | 主文本 |
| `text.secondary` | `#FFFFFF` @ 70% | 副文本 |
| `text.tertiary` | `#FFFFFF` @ 40% | 非当前歌词、辅助信息 |
| `divider` | `#FFFFFF` @ 12% | 列表行之间 |
| `surface.glass.dark` | `#000000` @ 30% + blur 32 | 浅色背景上的深色玻璃叠层 |

### 2.5 云母氛围渐变（背景）

> 这是产品的"灵魂层"——所有页面背景都从这套渐变库中取色，**饱和度故意偏低**避免干扰内容。

| 渐变名 | 起始色 | 结束色 | 适用场景 |
|--------|--------|--------|----------|
| `mica.dawn` 黎明 | `#F7F2E8` | `#E8E0F2` | 主页、文件夹页（默认浅色） |
| `mica.dusk` 黄昏 | `#FFE6CC` | `#FFCCD9` | 个人中心、温暖场景 |
| `mica.midnight` 子夜 | `#0D1B2A` | `#D4823A` | 播放页（深色专辑取色后渲染） |
| `mica.aurora` 极光 | `#1A0B2E` | `#3B2266` | 均衡器、深色模式默认 |
| `mica.fog` 薄雾 | `#F5F5F8` | `#E8EBF0` | 搜索页、设置页（最克制） |

**动态规则**：播放页的氛围色应从专辑封面提取主色调动态生成（Palette API）。其他页面使用固定预设。

### 2.6 语义色（最小化使用）

| Token | Hex | 用途 |
|-------|-----|------|
| `semantic.like` | `#FF6B6B` | 心形已收藏（实心红） |
| `semantic.warning` | `#F5A623` | 错误的文件格式、扫描异常 |
| `semantic.success` | `#52C41A` | 极少使用，扫描完成 |

---

## 三、字体系统

### 3.1 字体族

| 角色 | 字体 | 备用 |
|------|------|------|
| 中文（主） | `HarmonyOS Sans SC` | `Noto Sans SC`, `PingFang SC`, `system-ui` |
| 英文 / 数字 | `Inter` | `SF Pro Display`, `Roboto Flex` |
| 等宽（技术参数） | `JetBrains Mono` | `IBM Plex Mono`, `Roboto Mono` |

### 3.2 字号层级

| Token | 字号 | 字重 | 行高 | 用途 |
|-------|------|------|------|------|
| `display` | 28sp | Bold | 36sp | 页面大标题（"本地音乐"） |
| `title.lg` | 24sp | Bold | 32sp | 播放页歌曲名 |
| `title.md` | 18sp | SemiBold | 26sp | 区块标题 |
| `title.sm` | 16sp | SemiBold | 24sp | 页内小标题 |
| `body.lg` | 16sp | Medium | 24sp | 列表行主文本（歌名） |
| `body.md` | 14sp | Regular | 20sp | 正文段落 |
| `body.sm` | 13sp | Regular | 18sp | 列表行副文本（艺术家·专辑） |
| `caption` | 12sp | Regular | 16sp | 辅助说明 |
| `mono.md` | 12sp | Mono Regular | 16sp | FLAC/MP3 格式标签、时间戳 |
| `mono.sm` | 11sp | Mono Regular | 14sp | 信息条（首歌·GB·扫描时间） |
| `lyric.current` | 22sp | Bold | 32sp | 当前歌词（带发光） |
| `lyric.other` | 16sp | Regular | 24sp | 非当前歌词（@40% 透明） |

---

## 四、间距系统（4dp 基础网格）

| Token | 数值 | 用途 |
|-------|------|------|
| `space.xxs` | 2dp | 紧密元素之间（图标和文字） |
| `space.xs` | 4dp | 小间隙 |
| `space.sm` | 8dp | 中等间隙、卡片内边距 |
| `space.md` | 12dp | 标准间隙 |
| `space.lg` | 16dp | 页面边距、行间距 |
| `space.xl` | 24dp | 区块之间 |
| `space.xxl` | 32dp | 大区块之间 |
| `space.xxxl` | 48dp | 顶部巨大留白 |

---

## 五、尺寸规范

### 5.1 触摸目标

- **最小可点击区域**：`48dp × 48dp`（遵循 Material 无障碍规范）
- **图标按钮**：图标 24dp，外围 padding 12dp，总 48dp

### 5.2 图标尺寸

| Token | 数值 | 用途 |
|-------|------|------|
| `icon.xs` | 12dp | 行内指示（Hi-Res 圆点、激活点） |
| `icon.sm` | 16dp | 列表行末尾、辅助图标 |
| `icon.md` | 20dp | 工具栏标准图标 |
| `icon.lg` | 24dp | 顶部导航、底部导航 |
| `icon.xl` | 32dp | 播放控制（上一首/下一首） |
| `icon.xxl` | 48dp | 主播放按钮（三角形） |

### 5.3 缩略图

| Token | 数值 | 用途 |
|-------|------|------|
| `cover.xs` | 32dp | 迷你播放器 |
| `cover.sm` | 44dp | 列表行 |
| `cover.md` | 80dp | 卡片缩略图 |
| `cover.lg` | 240dp | 歌单详情大封面 |
| `cover.fullwidth` | screen width | 播放页全屏封面（顶部~45%） |

### 5.4 容器宽高

- 顶部 AppBar 高度：`56dp`
- 底部导航高度：`72dp`（带文字）/ `56dp`（仅图标）
- 列表行高度：`64dp`（带缩略图）/ `48dp`（无缩略图）
- 迷你播放器高度：`56dp`

---

## 六、形状（**核心规则**）

| 元素 | 圆角 |
|------|------|
| **所有元素** | **0dp（直角）** |
| 缩略图 | 0dp |
| 按钮 | 0dp（且尽量用文字按钮，避免矩形填充按钮） |
| 卡片 | 0dp，但**优先不要卡片**，用留白分组 |
| 进度条 | 0dp（端点也是直角） |
| 滑块滑头 | 矩形小条（28dp × 3dp） |

---

## 七、边框 / 分隔

| 用途 | 规则 |
|------|------|
| 列表行分隔 | hairline `1dp` 横线，`divider` 颜色 |
| 边框 | **几乎不用**——仅在缩略图占位符或扫描中状态使用 |
| 强调态指示 | 紫色 `2dp` 直线（下划线 / 左侧竖线） |

---

## 八、模糊与质感

### 8.1 云母 / 毛玻璃

```kotlin
// 浅色模式
Modifier
    .background(Color.White.copy(alpha = 0.60f))
    .blur(radius = 24.dp)

// 深色模式
Modifier
    .background(Color.Black.copy(alpha = 0.30f))
    .blur(radius = 32.dp)
```

### 8.2 阴影

- **几乎不用阴影**。一切扁平化。
- 唯一例外：迷你播放器悬浮于内容上方时，顶部加一条 hairline 分隔线（不是阴影）

---

## 九、动效

> **权威说明见 [`docs/MOTION.md`](docs/MOTION.md)**（与 `MicaMotion.kt` 对齐）。下表为早期参考，实现以 Motion 文档为准。

| 场景 | 时长（现行） | 缓动 |
|------|--------------|------|
| 主页/浏览分区切换 | 320ms（Medium） | `FastOutSlowInEasing` |
| 顶栏局部（搜索框等） | 200ms（Short） | `FastOutSlowInEasing` |
| 播放页沉浸 / 封面 lerp | 400ms（Long） | `FastOutSlowInEasing` |
| Nav 子页（设置/播放/详情） | 320ms 淡入 + 纵滑 | `FastOutSlowInEasing` |
| 开关 / 滑块 | 150ms | `LinearOutSlowInEasing`（控件级，待统一） |
| 歌词行切换 | 400ms（规划） | 淡入淡出 + 轻微上移（待做） |
| 进入播放页共享元素 | — | 待做 |
| EQ 曲线变化 | 200ms | 跟随用户拖动，松手后弹簧回稳 |
| 列表波形指示 | 持续循环 600ms | 3 根竖线高度交替变化（独立周期） |

---

## 十、组件模式（设计语言核心）

### 10.1 激活态（统一规则）

> **强调色 + 2dp 直线** 是激活态的唯一表达。不用填充、不用胶囊、不用阴影。

| 场景 | 表达 |
|------|------|
| Tab 激活 | 文字下方 `2dp` 紫色横线，文字加粗 |
| 底部导航激活 | 图标下方 `2dp` 紫色横线 |
| 列表行正在播放 | 左侧 `2dp` 紫色竖线，文字变紫，右侧波形小图标 |
| EQ 预设激活 | 文字下方 `2dp` 紫色横线 |
| Toggle 开 | 文字"开" + 紫色小圆点（`●`） |
| Toggle 关 | 文字"关"灰色 |

### 10.2 Hi-Res 视觉签名

```
●  Hi-Res
```

- 金色小圆点（`#D4AC4F`, 直径 6dp）
- 后接文字 "Hi-Res"（caption 字号）
- **仅用于**：播放页右上、文件浏览中 DSD/24bit 以上的文件、设置中 Hi-Res 直通选项
- 永远不框起来，永远不变色

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
- 滑块/播放头：`2dp × 12dp` 矩形竖条，跟随当前位置
- 两侧时间戳 `mono.md`

### 10.6 快捷操作（主页）

```
[图标]  随机播放
        全部
```

- 仅图标 + 两行文字
- **无背景，无边框**
- 列方向布局，整体左对齐
- 行之间用 `space.xl` (24dp) 分隔

---

## 十一、Jetpack Compose 实现

### 11.1 文件结构建议

```
app/src/main/java/com/yourapp/ui/theme/
├── Color.kt          # 色彩常量 + 语义化封装
├── Type.kt           # 字体定义
├── Spacing.kt        # 间距/尺寸 Token
├── Shapes.kt         # 全 0dp
├── Theme.kt          # HifiTheme 主入口
└── MicaGradient.kt   # 云母渐变工具
```

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

enum class MicaPreset { Dawn, Dusk, Aurora, Fog }

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

### 12.5 Tab 行（带下划线指示）

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

## 十三、依赖建议

```kotlin
// app/build.gradle.kts
dependencies {
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.2")

    // 图片加载（封面）
    implementation("io.coil-kt:coil-compose:2.7.0")

    // 提取专辑封面主色（用于动态 mica）
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Media3 / ExoPlayer（HiFi 解码）
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    // FLAC/DSD 等格式扩展
    implementation("androidx.media3:media3-decoder:1.4.1")
    implementation("androidx.media3:media3-extractor:1.4.1")
    // 第三方 DSD 解码（可选）：例如 jaudiotagger 处理元数据

    // 字体（可选，提升中文显示）
    implementation("androidx.compose.ui:ui-text-google-fonts")
}
```

---

## 十四、待补充

以下页面的规范在迭代时补充：

- [ ] 设置页（音频输出、独占模式、Hi-Res 直通、扫描路径）
- [ ] 歌单管理（创建、智能歌单条件）
- [ ] 专辑/歌手聚合页（九宫格、列表）
- [ ] 首次启动 / 扫描引导
- [ ] 错误状态、空状态、加载状态

---

**版本**：v1.0  
**最后更新**：2026-05-18  
**适用平台**：Android 7.0+ / Jetpack Compose 1.7+
