param(
    [string]$Serial
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = Join-Path $root ".codex-android-sdk\platform-tools\adb.exe"
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$metadataPath = Join-Path $root "app\build\outputs\apk\debug\output-metadata.json"
$packageName = "com.mica.music.qa"
$tag = "MicaUsbPrototype"

if (-not $Serial) {
    $connected = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
    if ($connected.Count -ne 1) {
        throw "Expected exactly one ADB device; pass -Serial <ip:port>."
    }
    $Serial = ($connected -split "\s+")[0]
}

Write-Host ">> Build throwaway SK02 descriptor probe..." -ForegroundColor Cyan
& (Join-Path $root "gradlew.bat") :app:assembleDebug --no-configuration-cache `
    "-Pmica.qaSideBySide=true"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
if ($metadata.applicationId -ne $packageName) {
    throw "Refusing to install unexpected applicationId: $($metadata.applicationId)"
}

Write-Host ">> Install side-by-side QA APK on $Serial..." -ForegroundColor Cyan
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# MIUI suppresses an exported receiver until the newly installed package has been launched once.
& $adb -s $Serial shell am start -n "$packageName/com.mica.music.MainActivity" | Out-Host
Start-Sleep -Seconds 1

& $adb -s $Serial logcat -c
Write-Host ">> Request read-only USB permission. Accept the Android dialog if shown." -ForegroundColor Cyan
& $adb -s $Serial shell am broadcast `
    --include-stopped-packages `
    -n "$packageName/com.mica.music.media.usbprototype.UsbSk02DescriptorPrototypeReceiver" `
    -a "$packageName.debug.USB_SK02_PROBE" `
    -p $packageName | Out-Host

$deadline = (Get-Date).AddSeconds(45)
do {
    Start-Sleep -Milliseconds 500
    $logs = & $adb -s $Serial logcat -d -s "${tag}:I" "*:S"
    if ($logs -match "probe=(complete|permission_denied|target_not_found|open_failed)") {
        $logs | Out-Host
        exit 0
    }
} while ((Get-Date) -lt $deadline)

& $adb -s $Serial logcat -d -s "${tag}:I" "*:S" | Out-Host
throw "Timed out waiting for the USB permission result."
