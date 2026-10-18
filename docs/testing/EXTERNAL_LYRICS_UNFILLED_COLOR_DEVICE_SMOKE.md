# 外部悬浮歌词未填充颜色：实机记录

日期：2026-10-18；设备小米 `22081212C`，Android 12。

截图、样本生成脚本与诊断原件仅保存在测试机器的 `.scratch/external-lyrics-unfilled-color/`，不纳入版本控制；下文文件名用于关联本机证据。

使用当前工作区构建的 `com.mica.music.qa`，0.4.3-qa / code 58；包含工作区其他未提交改动，不能视为正式发布包。未覆盖或清空普通 `com.mica.music`。样本为自行生成的 180 秒静音 WAV（2,880,174 bytes），通过内嵌 ID3 USLT 提供增强 LRC 逐字歌词 `COLOR BEFORE AFTER TEST`；未以用户真实音乐作为测试 fixture。

## 已确认

- 真机可打开取色器，Hex 输入及色相/饱和度/明度预览响应正常：`picker-open.png`。
- 输入红色后点击取消，设置页和 SharedPreferences 仍为之前的白色，没有提交红色草稿。
- 点击应用保存绿色 `#FF00FF00`，偏好值为 `-16711936`；已填充颜色保持白色。
- 强制停止、冷启动后绿色仍保留，并用于真实悬浮窗口，验证未仅依赖当前 Activity 内存。
- 桌面悬浮歌词：系统桌面上可见白色已填充区和绿色未填充区，`desktop-green-home.png`；应用内效果为 `fixture-cold-open.png`。
- 从 UI 切换状态栏模式后共用绿色，系统桌面截图为 `status-bar-green-home.png`。
- 从 UI 关闭自定义后，`external_lyrics_unfilled_color` key 被移除；重新播放样本时未填充区恢复跟随白色，截图 `status-bar-follow-white-home.png`。

## 未解决的发现

在已有 QA Activity 的设置页上，通过 shell 发起 warm `ACTION_VIEW` 打开样本时，出现一次明确的系统 ANR。`last-anr.txt` 记录原因：`Waited 5001ms for FocusEvent(hasFocus=false)`；当时的截图为 `qa-anr.png`。后来后台回前台也出现画面未及时切回的现象，但未取得第二份明确 ANR 结论。

冷启动直接 `ACTION_VIEW` 可成功播放同一样本，完成上述桌面/状态栏颜色验证。没有取得 ANR 主线程栈或同一工作区改动前的对照，**无法确认 ANR 的根因或与本次颜色改动的关系，也不能声称它已经修复**。Android 12 单机 smoke 不等于所有 OEM/Android 版本、前后台切换或长期稳定性通过。

## 清理

已停止 QA 播放和进程；从 UI 将外部歌词输出恢复为关闭、自定义颜色恢复为跟随；状态栏顶部偏移恢复 0 dp。QA 悬浮窗权限恢复原来的 ignore（UID override 回到 default）。已删除本次创建的 MediaStore 样本行 `378476`、其 WAV 文件以及两个设备临时截图/XML 文件。QA 更新包保留安装；没有清空 QA 数据库或用户普通应用数据。

本轮实机测试未修改产品代码。
