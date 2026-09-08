param(
    [ValidateRange(10, 3600)]
    [int]$DurationSeconds = 120,
    [ValidateRange(500, 10000)]
    [int]$IntervalMs = 1000,
    [string]$Serial,
    [string]$AdbPath,
    [string]$PackageName = "com.mica.music",
    [string]$ScenarioLabel = "s3-device-perf",
    [switch]$ResetLogcat
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Resolve-AdbPath {
    if ($AdbPath) {
        if (-not (Test-Path -LiteralPath $AdbPath)) {
            throw "adb not found at explicit path: $AdbPath"
        }
        return (Resolve-Path -LiteralPath $AdbPath).Path
    }

    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    $localProperties = Join-Path $root "local.properties"
    $sdkFromGradle = $null
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match "^\s*sdk\.dir\s*=" } |
            Select-Object -First 1
        if ($sdkLine) {
            $sdkFromGradle = ($sdkLine -replace "^\s*sdk\.dir\s*=\s*", "") -replace "\\:", ":"
            $sdkFromGradle = $sdkFromGradle -replace "\\\\", "\"
        }
    }

    $candidates = @(
        $(if ($sdkFromGradle) { Join-Path $sdkFromGradle "platform-tools\adb.exe" }),
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" }),
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" })
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

    $found = $candidates | Select-Object -First 1
    if (-not $found) {
        throw "adb not found. Pass -AdbPath or configure Android SDK platform-tools."
    }
    return (Resolve-Path -LiteralPath $found).Path
}

function Resolve-DeviceSerial([string]$adb) {
    $deviceLines = & $adb devices |
        Select-Object -Skip 1 |
        Where-Object { $_ -match "^([^\s]+)\s+device(?:\s|$)" }

    $devices = @(
        $deviceLines | ForEach-Object {
            if ($_ -match "^([^\s]+)\s+device(?:\s|$)") { $Matches[1] }
        }
    )

    if ($Serial) {
        if ($Serial -notin $devices) {
            throw "Requested device '$Serial' is not online. Online devices: $($devices -join ', ')"
        }
        return $Serial
    }

    if ($devices.Count -eq 0) {
        throw "No online adb device. Connect/authorize a device and retry."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple adb devices are online. Pass -Serial. Online devices: $($devices -join ', ')"
    }
    return $devices[0]
}

function Read-MemInfoSample(
    [string]$adb,
    [string]$device,
    [string]$packageName
) {
    $capturedAt = Get-Date
    $pidText = ((& $adb -s $device shell pidof $packageName) | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($pidText)) {
        return [pscustomobject]@{
            capturedAt = $capturedAt.ToString("o")
            pid = $null
            totalPssKb = $null
            totalRssKb = $null
            processRunning = $false
            rawTotalLine = $null
        }
    }

    $processId = ($pidText -split "\s+")[0]
    $memInfo = @(& $adb -s $device shell dumpsys meminfo $packageName)
    $totalLine = $memInfo |
        Where-Object { $_ -match "TOTAL PSS:\s*\d+" } |
        Select-Object -Last 1

    $pss = $null
    $rss = $null
    if ($totalLine -and $totalLine -match "TOTAL PSS:\s*(\d+)") {
        $pss = [long]$Matches[1]
    }
    if ($totalLine -and $totalLine -match "TOTAL RSS:\s*(\d+)") {
        $rss = [long]$Matches[1]
    }

    [pscustomobject]@{
        capturedAt = $capturedAt.ToString("o")
        pid = $processId
        totalPssKb = $pss
        totalRssKb = $rss
        processRunning = $true
        rawTotalLine = $totalLine
    }
}

function Percentile95([long[]]$values) {
    if (-not $values -or $values.Count -eq 0) {
        return $null
    }
    $sorted = @($values | Sort-Object)
    $index = [Math]::Ceiling($sorted.Count * 0.95) - 1
    $index = [Math]::Max(0, [Math]::Min($sorted.Count - 1, $index))
    return [long]$sorted[$index]
}

$adb = Resolve-AdbPath
$device = Resolve-DeviceSerial $adb

$initialPid = ((& $adb -s $device shell pidof $PackageName) | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($initialPid)) {
    throw "Package '$PackageName' is not running. Launch Mica before starting capture."
}

if ($ResetLogcat) {
    & $adb -s $device logcat -c
    if ($LASTEXITCODE -ne 0) {
        throw "adb logcat -c failed with exit code $LASTEXITCODE"
    }
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$safeScenario = ($ScenarioLabel -replace "[^A-Za-z0-9._-]", "_")
$artifactDir = Join-Path $root ".scratch\library-auto-sync-s3-device-perf\$stamp-$safeScenario"
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null

Write-Host "S3 DEVICE performance evidence capture started." -ForegroundColor Cyan
Write-Host "Device: $device"
Write-Host "Package: $PackageName"
Write-Host ("Duration: " + $DurationSeconds + "s, interval: " + $IntervalMs + "ms")
Write-Host "Interact with the phone during capture: keep playback active and trigger the intended library mutation."
Write-Host "Artifacts: $artifactDir"

$samples = New-Object System.Collections.Generic.List[object]
$deadline = (Get-Date).AddSeconds($DurationSeconds)
while ((Get-Date) -lt $deadline) {
    $samples.Add((Read-MemInfoSample $adb $device $PackageName))
    Start-Sleep -Milliseconds $IntervalMs
}

$samplesPath = Join-Path $artifactDir "memory-samples.json"
$samples | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $samplesPath -Encoding utf8

$diagnosticLogPath = Join-Path $artifactDir "diagnostics-logcat.txt"
$diagnosticLines = @(
    & $adb -s $device logcat -d -v time `
        "MICA_DIAGNOSTICS:D" `
        "AndroidRuntime:E" `
        "ActivityManager:I" `
        "*:S"
)
$diagnosticLines | Set-Content -LiteralPath $diagnosticLogPath -Encoding utf8

$pssValues = @(
    $samples |
        Where-Object { $null -ne $_.totalPssKb } |
        ForEach-Object { [long]$_.totalPssKb }
)
$rssValues = @(
    $samples |
        Where-Object { $null -ne $_.totalRssKb } |
        ForEach-Object { [long]$_.totalRssKb }
)
$pids = @(
    $samples |
        Where-Object { $_.processRunning -and $_.pid } |
        ForEach-Object { [string]$_.pid } |
        Select-Object -Unique
)
$notRunningCount = @($samples | Where-Object { -not $_.processRunning }).Count
$fatalCount = @($diagnosticLines | Where-Object { $_ -match "FATAL EXCEPTION" }).Count
$escapedPackage = [regex]::Escape($PackageName)
$anrCount = @(
    $diagnosticLines | Where-Object {
        $_ -match ("ANR in\s+" + $escapedPackage + "(?:\s|$)") -or
        $_ -match ("ANR.*" + $escapedPackage)
    }
).Count

$summary = [pscustomobject]@{
    status = "EVIDENCE_CAPTURED"
    serial = $device
    packageName = $PackageName
    scenarioLabel = $ScenarioLabel
    capturedAt = (Get-Date).ToString("o")
    durationSeconds = $DurationSeconds
    intervalMs = $IntervalMs
    sampleCount = $samples.Count
    processNotRunningSampleCount = $notRunningCount
    distinctPids = $pids
    processRestartObserved = $pids.Count -gt 1 -or $notRunningCount -gt 0
    peakPssKb = if ($pssValues.Count) { ($pssValues | Measure-Object -Maximum).Maximum } else { $null }
    p95PssKb = Percentile95 $pssValues
    peakRssKb = if ($rssValues.Count) { ($rssValues | Measure-Object -Maximum).Maximum } else { $null }
    p95RssKb = Percentile95 $rssValues
    fatalExceptionLineCount = $fatalCount
    anrLineCount = $anrCount
    memorySamplesPath = $samplesPath
    diagnosticsLogPath = $diagnosticLogPath
    artifactDirectory = $artifactDir
    operatorChecksStillRequired = @(
        "Whether playback had audible dropouts, seek/progress stalls, or unexpected track changes",
        "Whether USB exclusive output glitched or renegotiated unexpectedly",
        "Whether the intended mutation workload actually completed",
        "For 1000-copy runs, whether MediaStore ingestion itself stayed within an acceptable wall-clock envelope"
    )
}

$summaryPath = Join-Path $artifactDir "summary.json"
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding utf8

Write-Host ""
Write-Host "Capture complete." -ForegroundColor Green
Write-Host "Peak PSS KB: $($summary.peakPssKb)"
Write-Host "Peak RSS KB: $($summary.peakRssKb)"
Write-Host "Process restart observed: $($summary.processRestartObserved)"
Write-Host "FATAL EXCEPTION lines: $fatalCount"
Write-Host "ANR lines: $anrCount"
Write-Host "Summary: $summaryPath"
