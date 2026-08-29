# 应用更新清单

Mica 的版本页只检查一个很小的 HTTPS JSON 清单，然后把下载入口交给浏览器；APK 下载和安装不在应用内完成。

## URL 的三个角色

这三个地址不要混用：

- GitHub Pages 清单：`https://lecoix.github.io/mica-music/update.json`，国际版 APK 内置该地址。
- EdgeOne 清单：未来的国内清单镜像地址，例如 `https://<edgeone-public-host>/update.json`。它通过 Gradle 属性 `mica.update.domesticUrl` 注入 APK；为空时应用直接使用 GitHub Pages。
- 123 下载页：清单里的 `domesticUrl` 字段，指向用户实际下载 APK 的 123 页面，而不是 EdgeOne 清单。

清单格式：

下面的版本号仅用于说明字段格式；发布时由 workflow 从 `app/build.gradle.kts` 和 `v` 标签生成当前值。

```json
{
  "versionName": "0.2.5.0",
  "versionCode": 49,
  "changelog": "修复若干问题",
  "domesticUrl": "https://www.123pan.com/s/example",
  "githubUrl": "https://github.com/lecoix/mica-music/releases/tag/v0.2.5.0"
}
```

`versionCode` 必须递增；清单和下载地址必须使用 HTTPS。`domesticUrl` 可以暂时为空，但 `githubUrl` 必须存在。

## GitHub 发布流程

`.github/workflows/android-release.yml` 会在 `v*` 标签推送时自动完成：

1. 校验标签是否与 `app/build.gradle.kts` 的 `versionName` 一致。
2. 使用仓库 Secret 构建并验证签名 APK。
3. 创建或更新 GitHub Release，上传 APK；更新日志优先使用附注 tag 的 `-m` 正文，否则回退 GitHub 自动生成 notes。
4. 生成 `site/update.json`，把版本号、更新日志、123 下载页和 GitHub Release 页写入清单。
5. 将只包含 `update.json` 的 Pages artifact 部署到 GitHub Pages。

Release workflow 通过 `-Pmica.abiSplitApks=true` 生成三个 ABI 产物，再由 `scripts/prepare_release_apks.sh` 检查并重命名为：

- `Mica_<version>_64bit.apk`（`arm64-v8a`）
- `Mica_<version>_32bit.apk`（`armeabi-v7a`）
- `Mica_<version>_universal.apk`（两个 ABI）

三包都会执行 `apksigner verify`；本地 unsigned 构建或单一 debug APK 不能替代该发布检查。

因此，日常发布不需要手动编辑或上传 `update.json`。正常发布只需要：

```powershell
# 先在 app/build.gradle.kts 递增 versionCode/versionName，并提交代码
git tag -a v0.2.5.0 -m @"
- 修复若干问题
- 新增某某功能
"@
git push origin exoplayer-only
git push origin v0.2.5.0
```

附注 tag（`-a`）的 message 会写入 GitHub Release 与 `update.json.changelog`。轻量 tag 或无 message 时，workflow 仍回退 `--generate-notes`。

仓库首次使用前，在 GitHub Settings → Pages 中把 Source 设为 **GitHub Actions**。

### 必需的 Actions Secret

保留现有发布 workflow 使用的四个 Secret：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`ANDROID_KEYSTORE_BASE64` 是发布 keystore 的 Base64 内容，不是文件路径。PowerShell 可这样生成内容后粘贴到 Secret：

```powershell
$bytes = [IO.File]::ReadAllBytes('C:\path\to\release.jks')
[Convert]::ToBase64String($bytes)
```

### 可选的 Actions Variables

- `UPDATE_DOMESTIC_DOWNLOAD_URL`：123 下载页面地址，会写入 `update.json.domesticUrl`。
- `UPDATE_DOMESTIC_MANIFEST_URL`：EdgeOne 上的国内 `update.json` 地址，会编译进之后构建的 APK，作为国内检查更新入口。

前者是下载地址，后者是清单地址。EdgeOne 流程尚未接入此 workflow；在它配置好之前，保持 `UPDATE_DOMESTIC_MANIFEST_URL` 为空即可，应用会回退 GitHub Pages。

也可以从 Actions 页面手动运行 workflow，输入标签，并可选填写本次更新日志和 123 下载页地址；输入值只覆盖本次运行的仓库变量。

## 国内镜像的后续接入

EdgeOne 需要发布与 GitHub Pages 完全相同的 `update.json`。当前 workflow 已完成 GitHub Release + GitHub Pages 主链路，之后只需增加 EdgeOne 的静态文件部署步骤，并把 `UPDATE_DOMESTIC_MANIFEST_URL` 设置为 EdgeOne 的公开 HTTPS 地址。

国内清单请求失败时，应用会回退到国际清单地址。
