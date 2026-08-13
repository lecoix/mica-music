param(
    [string]$Serial,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = Join-Path $root ".codex-android-sdk\platform-tools\adb.exe"
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$metadataPath = Join-Path $root "app\build\outputs\apk\debug\output-metadata.json"
$packageName = "com.mica.music.qa"
$receiver = "$packageName/com.mica.music.media.usbprototype.UsbDoPContentProbeReceiver"
$action = "$packageName.debug.USB_DOP_CONTENT_PROBE"
$tag = "MicaUsbDoPContent"
$checkpoint = (& git -C $root rev-parse HEAD).Trim()
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$artifact = Join-Path $root ".scratch\usb-dop-content\$stamp"
New-Item -ItemType Directory -Force -Path $artifact | Out-Null

if (-not $Serial) {
    $connected = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" })
    if ($connected.Count -ne 1) { throw "Expected exactly one ADB device; pass -Serial <serial>." }
    $Serial = ($connected[0] -split "\s+")[0]
}

if (-not $SkipBuild) {
    & (Join-Path $root "gradlew.bat") :app:assembleDebug --no-daemon --no-configuration-cache "-Pmica.qaSideBySide=true"
    if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed" }
}
$metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
if ($metadata.applicationId -ne $packageName) {
    throw "Refusing unexpected applicationId: $($metadata.applicationId)"
}

& $adb -s $Serial install -r $apk | Tee-Object -FilePath (Join-Path $artifact "install.txt") | Out-Host
if ($LASTEXITCODE -ne 0) { throw "APK install failed" }
& $adb -s $Serial shell am start -n "$packageName/com.mica.music.MainActivity" | Out-File -FilePath (Join-Path $artifact "launch.txt")
Start-Sleep -Seconds 1
& $adb -s $Serial logcat -c

$header = @(
    "checkpoint=$checkpoint",
    "serial=$Serial",
    "started=$(Get-Date -Format o)",
    "action=$action"
)
$header | Set-Content -LiteralPath (Join-Path $artifact "RUN.txt") -Encoding UTF8

& $adb -s $Serial shell am broadcast --include-stopped-packages -n $receiver -a $action -p $packageName |
    Tee-Object -FilePath (Join-Path $artifact "broadcast.txt") | Out-Host

$deadline = (Get-Date).AddSeconds(30)
$status = $null
$logs = @()
do {
    Start-Sleep -Milliseconds 300
    $logs = @(& $adb -s $Serial logcat -d -s "${tag}:I" "${tag}:E" "*:S")
    if ($logs -match "dopContentProbe=result status=USER_ACTION_REQUIRED") {
        $status = "USER_ACTION_REQUIRED"
        break
    }
    if ($logs -match "dopContentProbe=result status=PASS") {
        $status = "PASS"
        break
    }
    if ($logs -match "dopContentProbe=result status=SOURCE_UNAVAILABLE") {
        $status = "SOURCE_UNAVAILABLE"
        break
    }
    if ($logs -match "dopContentProbe=result status=FAIL") {
        $status = "FAIL"
        break
    }
} while ((Get-Date) -lt $deadline)
if (-not $status) { $status = "TIMEOUT" }

$logs | Set-Content -LiteralPath (Join-Path $artifact "dop-content-logcat.txt") -Encoding UTF8
$logs | Where-Object {
    $_ -match "dopContentProbe=(sourceDiscovery|sourceCandidate|sourceSelected|readerInfo|device|selection|prefill|armUnderThreshold|beforeArm|arm|sample|sourceEof|sourcePump|accounting|feeder|transportFinal|cleanup|sourceClose|result)"
} | Set-Content -LiteralPath (Join-Path $artifact "probe-facts.txt") -Encoding UTF8
& $adb -s $Serial logcat -d | Set-Content -LiteralPath (Join-Path $artifact "logcat-full.txt") -Encoding UTF8
& $adb -s $Serial shell dumpsys usb | Set-Content -LiteralPath (Join-Path $artifact "dumpsys-usb.txt") -Encoding UTF8

$previousErrorActionPreference = $ErrorActionPreference
$optionalAlsaExit = 0
$optionalAlsaOutput = @()
try {
    $ErrorActionPreference = "Continue"
    $optionalAlsaOutput = @(& $adb -s $Serial shell cat /proc/asound/cards 2>&1)
    $optionalAlsaExit = $LASTEXITCODE
} catch {
    $optionalAlsaOutput = @($_.Exception.Message)
    $optionalAlsaExit = 1
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
$optionalAlsaOutput | Set-Content -LiteralPath (Join-Path $artifact "proc-asound-cards.txt") -Encoding UTF8
$optionalAlsaEvidence = if ($optionalAlsaExit -eq 0) { "available" } else { "unavailable exitCode=$optionalAlsaExit" }

$summary = @(
    "status=$status",
    "checkpoint=$checkpoint",
    "serial=$Serial",
    "artifact=$artifact",
    "optionalAlsaEvidence=$optionalAlsaEvidence",
    "finished=$(Get-Date -Format o)"
)
$summary | Set-Content -LiteralPath (Join-Path $artifact "RESULT.txt") -Encoding UTF8
$summary | Out-Host
$logs | Out-Host

switch ($status) {
    "PASS" { exit 0 }
    "USER_ACTION_REQUIRED" { exit 2 }
    "SOURCE_UNAVAILABLE" { exit 3 }
    default { exit 1 }
}
