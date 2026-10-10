# B3 / B2 视觉对照验收清单

B3 **只生成独立 QA 包，不安装、不改变手机当前包或音乐数据**。待 B2 验收完成后人工决定是否安装 B3，对照同一首有长间奏、带歌词换行和当前行加粗的曲目。

B3 计划使用独立 application ID `com.mica.music.lyricsb3qa`，不得覆盖正式版 `com.mica.music` 或 B2 QA 包。

1. 正常从上一句到下一句：顶部旧行只能朝上离开；不可反向从中部落下。
2. 间奏进入：旧/上方歌词向上升，下方下一句固定；退出间奏淡出后当前行仍按设置锚定。
3. 让当前行从单行变双行（粗体/换行）：不允许单帧跳位；上下文平稳随行高变化。
4. 快速连续 seek、正反往返：旧弹簧立即取消，当前句在目标锚点，不重用上一次 key 的离场目标。
5. 调整当前行位置百分比、高字体、双语歌词、横竖屏进出；已有颜色、字词高亮、滞后和渐隐不回退。
6. 下滑超出可见区后重新进入旧行：不得遗留 translation 或 alpha 影响。
7. 独立 APK 仅供后续对照。未进行真机拍摄/视觉验收时不可写“已验收”。

## 构建方式（待验证）

```powershell
.\gradlew.bat :app:assembleDebug '-Pmica.qaSideBySide=true' '-Pmica.qaApplicationId=com.mica.music.lyricsb3qa' --no-daemon --console=plain
```
