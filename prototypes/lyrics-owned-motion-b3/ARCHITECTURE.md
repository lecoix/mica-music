# B3 自有歌词位置层：几何与生命周期契约

- **布局权威**：`LazyColumn.layoutInfo.visibleItemsInfo` 的 `(key, offset, size)` 是唯一真实布局端点。offset 位于 LazyList 自己的 placement/viewport 坐标系，**不是** root 位置；大 top contentPadding 属于这套坐标空间，禁止拼接 rootY 或重新使用 firstVisibleItemScrollOffset 作为旧行离场终点。
- **所有权拆分**：LazyColumn 独占 scroll/measure/virtualization；B3 只拥有每项 `graphicsLayer.translationY` 的结构性 **残差**。逐行滞后原本的 `staggerOffsetY` 仍单独保留，且只属于滚动的视觉滞后。合成显示位置 = layout offset + structural residual + stagger lag；三者只加一次。
- **提交边界**：由 `snapshotFlow(layoutInfo.visibleItemsInfo)` 观察一次完整 layout 结果；与上一布局的同一 key 比较 `layoutDelta = new.offset - old.offset - measuredScrollTravel`。scrollTravel 从 nested-scroll post-consumed 实测（屏幕像素符号），绝不从动画目标反推。如果未确认滚动来源，必须保守地禁用位移补偿，不能把滚动误认为 layout move。
- **FLIP 位移**：在 geometry commit 时把旧的视觉位置衔接到新的真实布局点：`nextTranslation = oldTranslation - layoutDelta`；然后由 Mica 自有 `TargetBasedAnimation` 动画到 0。后续纯滚动不二次开弹簧；如果高度再次变化，取消旧 job，从当前 translation 接续新目标。
- **身份**：key 使用显示模型稳定 key；size 来自真实 measured item 而非字体行高估计。离开 visibleItems 的 key 不会经 Foundation moving-away 復活：controller 取消 animation 并释放 state；重入作为新的可见行初始化 translation=0。它不绘制从上方离场的幽灵项。
- **seek / document / 布局边界**：切歌、显式 scrollToItem snap、视口变化与禁用动效清空 controller 状态；位置调节继续沿用现有真实 geometry scrollDelta 和 fixed padding。频繁 seek 不允许旧弹簧穿过取消边界。
- **特殊时序**：间奏插入仍以已有 +插入行高的 animateScrollBy 保持下方歌词相对位置；上方歌词随滚动上移。结构性残差不得抵消已经发生的 scroll。淡入淡出保留原歌词层的 alpha 动画并为退出间奏提供局部过渡窗口（不依赖 animateItem）。
- **可证伪点**：要测 scroll-only 的 0px residual、+30px 行高变更连续性、scroll+height 混合 -527/-35 的反向事故、连续切句中断、回收重入、间奏上下分离、seek reset 和编译。此文档先于实现落盘。
