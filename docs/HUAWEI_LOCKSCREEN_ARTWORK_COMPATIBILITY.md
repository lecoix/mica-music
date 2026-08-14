# Huawei MediaSession 封面触发锁屏兼容方案

> 状态：已在问题真机验证有效  
> 验证日期：2026-08-15  
> 已验证设备：HUAWEI OXF-AN10、Android API 31（HarmonyOS 系统）

## 1. 问题与结论

OXF-AN10 在 Mica 快速连续切歌时会意外进入锁屏。问题只在 Mica 将自己管理的封面
`content://com.mica.music.artwork/...` 发布到 MediaSession 的 `MediaMetadata.artworkUri`
后出现。

真机对照结果：

| 实验 | MediaSession 封面 | 系统封面/控制 | 意外锁屏 |
|---|---|---|---|
| test3 | Mica managed `content://` | 正常 | 会发生 |
| test4 | `artworkUri = null` | 无封面，控制正常 | 不发生 |
| test5 | 同一缓存 JPG 的 `file://` | 封面、歌名、控制正常 | 不发生 |
| RC1 | 完整正式功能 + 本文兼容边界 | 用户反馈正常 | 未再发生 |

Provider 观察还确认：封面 Provider 的访问全部来自 Mica 自身，没有 SystemUI 直接打开
Provider、恢复缓存、慢 I/O 或异常的证据。因此当前工程结论是：

> OXF-AN10 的系统媒体/锁屏链路会被 MediaSession 中的 app-owned managed
> `content://` artwork URI 触发异常行为。

这是基于黑盒 A/B 和用户验收得到的兼容性结论；没有华为 SystemUI 源码，不能声称已经定位
到 OEM 代码中的具体错误行。

## 2. 当前修法

Mica 内部继续以 managed `content://` 作为封面的 canonical reference，Provider、封面缓存、
曲库和 App UI 行为均不改变。只在向 MediaSession 发布元数据时建立兼容边界：

```text
Song / 曲库 / Mica UI
    └─ canonical content://com.mica.music.artwork/...

MediaSession 出口
    ├─ HUAWEI + OXF-AN10 + API 31
    │   ├─ backing JPG 存在且非空 -> file:// backing JPG
    │   └─ backing JPG 缺失或为空 -> artworkUri = null
    └─ 其他设备 -> 保持原 content:// URI
```

关键约束：

- 兼容条件必须同时匹配 manufacturer、model 和 API；不能把未经验证的 workaround 扩散到
  所有华为或所有 Android 12 设备。
- 目标设备缺少 backing file 时，宁可让系统媒体界面暂时没有封面，也不能回退到已知会触发
  锁屏的 managed `content://`。
- `SongMediaItemCodec` 的 extras 仍保存 canonical URI，所以 MediaItem decode 后的内部 `Song`
  不会被 `file://` 污染。
- 内部队列发布和外部 controller 重建 MediaItem 两条生产路径必须使用同一个兼容边界，防止
  Android Auto 或其他 controller 把问题 URI 重新送入 Session。
- 普通 `http://`、`https://`、非 Mica URI 和非目标设备行为保持原样。

代码位置：

- `media/SystemMediaArtworkResolver.kt`：设备判定、managed URI 到 backing file 的映射和
  fail-closed 规则。
- `media/SongMediaItemCodec.kt`：Mica 内部队列的 session-facing metadata。
- `media/ExternalMediaItemCodec.kt`：外部 controller 路径的 session-facing metadata。
- `data/PlayerController.kt`、`data/MediaControllerQueueSync.kt`：确保运行时队列构建走带
  `Context` 的 session codec；保留无 Context 的纯函数入口供非运行时用途和既有测试使用。

## 3. 验证与回归边界

自动化测试覆盖：

- 目标设备且 backing file 存在时发布相同文件的 `file://`。
- 目标设备且 backing file 不存在时返回 `null`，不会回退到 managed `content://`。
- 非目标设备继续发布原 managed `content://`。
- session metadata 使用兼容 URI 时，codec decode 仍恢复 canonical URI。
- 外部 controller 编码路径遵守相同边界。

RC1 使用完整正式功能基线构建，而不是 no-session/minimal-session 诊断版本。问题用户已确认
修复有效。以后修改 Media3、MediaSession、封面缓存或 MediaItem codec 时，仍应在 OXF-AN10
回归快速连续切歌、通知/锁屏封面、上下曲、暂停恢复和后台播放。

## 4. 已知限制

- `file://` 是针对已验证 OEM 行为的窄范围 workaround，不是 Android 通用媒体封面的推荐
  协议。不能据此把全平台封面都改成 `file://`。
- SystemUI 消费 backing file 期间如果文件恰好被缓存驱逐，封面可能显示失败；该失败应保持
  为无封面，不能恢复问题 URI。
- 当前只证明 OXF-AN10/API 31 有效。其他华为型号、HarmonyOS 版本或 Android 版本既不能
  自动视为受影响，也不能自动视为已修复。
- 用户反馈证明了症状消失和常用媒体控制正常，不等于证明所有第三方 controller 都能读取
  私有 backing file；外部车机/手表场景需要各自验证。

## 5. 未来可选方案

### 5.1 有界 `artworkData`（优先研究）

只给 MediaSession 生成缩小、压缩且有严格字节上限的封面数据，同时令 `artworkUri = null`。
它不依赖 SystemUI 解析任何 URI，理论上比 `file://` 更独立。

实施前必须确定并测量：

- 最大像素尺寸、压缩格式和最大字节数；禁止直接塞入原始大图。
- Binder transaction 上限以及 Media3/framework 是否还会复制或重编码数据。
- 快速切歌时的解码、缩放、压缩、Parcel 复制和 GC 峰值。
- 10,000 首曲库下只能按当前/邻近曲目有界生成，不能全库常驻 `artworkData`。
- 异步生成或缓存若会发布共享结果，必须有 request/generation 校验、统一串行化 seam 和确定性
  交错测试，旧曲目的结果不得覆盖新曲目。

只有在 OXF-AN10 上重新完成“封面显示 + 控制正常 + 快速切歌不锁屏”的真机 A/B 后，才可
替换当前方案。

### 5.2 升级 Media3 后重新验证

Media3 或 Android framework 未来可能改变 artwork metadata 的转换和传递方式。升级后先保留
workaround，分别测试 managed `content://`、有界 `artworkData` 和当前 `file://`；不能仅凭
依赖升级推断 OEM 问题已经消失。

### 5.3 专用导出 Provider / session artwork cache

可以研究独立、只读、有界、专供系统媒体界面读取的 artwork Provider 或 session cache，避免
暴露曲库 canonical URI。不过当前证据指向 `content://` 被发布本身就是触发条件之一，因此
换 authority 或 Provider 实现不构成修复证据，必须先做目标机 A/B。

### 5.4 无封面兜底

如果未来发现 `file://` 在新系统或第三方 controller 上不可用，最安全的目标机兜底仍是
`artworkUri = null`。它已经由 test4 证明不会触发锁屏，只牺牲系统媒体界面的封面，不影响
播放控制。

## 6. 扩大或移除兼容条件的门槛

- 新增机型：必须有同样的 URI A/B 证据，按可审查的设备/系统范围加入，不能使用宽泛的
  `manufacturer == HUAWEI`。
- 移除 workaround：必须在原问题设备上证明 managed `content://` 不再触发锁屏，并完成
  快速切歌和后台媒体控制回归。
- 替换实现：必须至少达到当前 RC1 的系统封面、媒体控制和不锁屏结果，并给出内存、Binder
  和快速切歌成本的可审查上界。
