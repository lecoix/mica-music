# ADR-0008：服务持有明确的有效播放顺序

日期：2026-10-09。状态：已接受（播放队列修复方案）。

## 问题

Media3 物理列表与 native ShuffleOrder 可以不同。原实现只发送随机种子，插播/拖动却改物理列表；两次操作分别生成了不同的后继关系。种子加当前曲也无法还原已编辑或已经走到中途的顺序。

## 决定

- `PlaybackOrderState` 生成一次随机顺序；游标移动不重新生成。追加保留已有顺序并加到尾部；插播只移动目标到当前曲之后；拖动的排列同时成为源顺序；删除过滤有效顺序与源顺序。
- `ServicePlaybackOrderOwner` 在服务 main looper 接受明确 permutation；检查完整排列、物理 ID 指纹与请求 token。服务与 App 当前在同一进程（manifest 未设置 `android:process`），共享单调请求 token；新队列意图使旧 posted command 失效，包括 A→B→A。拆进程时必须先替换这项协议，不能直接复用进程内 AtomicLong。
- Media3 保留物理队列。顺序编辑不逐条移动整个物理列表；成员增删使用物理索引增量操作，再提交有效顺序。只有真正应用完成后才确认命令；拒绝后仅当前请求可尝试一次重新提交。
- 服务在 timeline 改变、恢复和输出栈重建时按稳定 ID 投影已接受的顺序。App 查询/确认后按该顺序投影镜像，元数据更新按 ID 找物理索引。服务普通导航、自然切歌和失败跳曲读取同一 native traversal。
- native traversal 可用于非随机的手动顺序；App 的逻辑随机标志与 native shuffle flag 不等价。App 模式显示及保存读取 accepted order 的逻辑标志。
- `ServicePlaybackStateCoordinator` 的 FIFO persistence executor 串行保存成员、完整有效/源顺序、逻辑随机标志和游标。队列 revision 在提交前捕获，不能在后台闭包内再读取可变 revision。清理也经过同一 executor。新格式游标 revision 不一致时，采用队列提交时的当前 ID并从零恢复，避免旧游标错误跳转。
- App 冷恢复在 owner dispatcher 发布；IO 后复验队列意图 revision。过期恢复返回 handled，防止 caller 的 fallback 覆盖用户队列。删空同时取消 pending queue/selection/navigation/seek/restore intent。

旧快照没有完整顺序时保留旧格式恢复兼容；重建结果随后保存为新格式。旧 seed 数据缺少最初锚点及手动编辑历史，无法承诺无损还原。新格式不重播旧 App seed。

## 容量与验证边界

10k 项传输两个 IntArray，原始数组共 80KB；Robolectric Parcel 测得 80,344 bytes。顺序路径只复制 Song/ID 引用、构建有界 O(n) 索引和数组，不遍历、摘要、编码或复制完整逐字歌词。10k 已加载歌词的访问陷阱回归覆盖实际 PlayerController 移动，确保没有整队 MediaItem 重建或物理移动。

该证据限制于新增顺序处理的开销，不证明全应用在任意大小的 10k 完整歌词下均能驻留于 8GB。全歌词常驻、批量删除、长时间播放、所有 USB/蓝牙输出、任意第三方直接设置 native shuffle 的行为仍须分别验收。

命令 token 与指纹检查、App 恢复、延迟镜像、服务持久化清空均有确定性交错测试。真机测试必须覆盖队列编辑后自然遍历、末曲停止、暂停删除当前曲、退出随机、服务重建及实际 UI 拖动/删除。
