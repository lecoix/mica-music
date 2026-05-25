# 解决 Windows 上 app-debug.apk 被占用 / 配置缓存陈旧
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

Write-Host ">> Stop Gradle daemons..." -ForegroundColor Cyan
& .\gradlew --stop 2>$null

$apkDir = Join-Path $Root "app\build\outputs\apk"
if (Test-Path $apkDir) {
    Write-Host ">> Remove APK outputs..." -ForegroundColor Cyan
    Remove-Item $apkDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ">> assembleDebug (no configuration cache)..." -ForegroundColor Cyan
& .\gradlew assembleDebug --no-configuration-cache
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
    $mb = [math]::Round((Get-Item $apk).Length / 1MB, 2)
    Write-Host ">> OK: $apk (~${mb} MB)" -ForegroundColor Green
} else {
    Write-Host ">> APK not found" -ForegroundColor Red
    exit 1
}
