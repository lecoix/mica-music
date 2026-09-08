param(
    [ValidateSet("Reset", "Evaluate")]
    [string]$Mode = "Evaluate",
    [string]$Serial,
    [string]$AdbPath
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

    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" })
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

    $found = $candidates | Select-Object -First 1
    if (-not $found) {
        throw "adb not found. Pass -AdbPath or install Android SDK platform-tools."
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

function Read-Field([string]$line, [string]$name) {
    $escaped = [regex]::Escape($name)
    if ($line -match "(?:^|\s)$escaped=([^\s]+)") {
        return $Matches[1]
    }
    return $null
}

$adb = Resolve-AdbPath
$device = Resolve-DeviceSerial $adb

if ($Mode -eq "Reset") {
    # Playback diagnostics can be extremely noisy and may evict the projection comparison from
    # the default logcat ring before Evaluate runs. Grow the main buffer for the gate when the
    # device allows it; this is test-only and failure is non-fatal.
    & $adb -s $device logcat -G 16M 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Could not enlarge logcat buffer; gate evidence may be evicted on noisy playback builds."
    }

    & $adb -s $device logcat -c
    if ($LASTEXITCODE -ne 0) {
        throw "adb logcat -c failed with exit code $LASTEXITCODE"
    }
    Write-Host "S3 DEVICE Shadow Gate log window reset for $device." -ForegroundColor Cyan
    Write-Host "Now run the isolated scenario, allow Shadow AUTO to observe it, then run a manual Full Scan."
    Write-Host "After the Full Scan completes, run this script again with -Mode Evaluate."
    exit 0
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$artifactDir = Join-Path $root ".scratch\library-auto-sync-s3-device\$stamp"
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null

$logPath = Join-Path $artifactDir "logcat.txt"
$summaryPath = Join-Path $artifactDir "summary.json"

$lines = @(& $adb -s $device logcat -d -v time "MICA_DIAGNOSTICS:D" "*:S")
if ($LASTEXITCODE -ne 0) {
    throw "adb logcat dump failed with exit code $LASTEXITCODE"
}
$lines | Set-Content -LiteralPath $logPath -Encoding utf8

$projectionLines = @(
    $lines | Where-Object {
        $_ -match "LibraryAutoSync: device shadow projection-compare "
    }
)
$baselineCount = @(
    $lines | Where-Object {
        $_ -match "LibraryAutoSync: device shadow projection-baseline "
    }
).Count
$contextResetCount = @(
    $lines | Where-Object {
        $_ -match "LibraryAutoSync: device shadow projection-context-reset "
    }
).Count
$candidateRejectCount = @(
    $lines | Where-Object {
        $_ -match "LibraryAutoSync: device shadow candidate-reject "
    }
).Count
$cursorHeldCount = @(
    $lines | Where-Object {
        $_ -match "LibraryAutoSync: device shadow cursor-held "
    }
).Count

$comparisons = @(
    foreach ($line in $projectionLines) {
        $diff = Read-Field $line "diff"
        $unresolved = Read-Field $line "unresolvedObjects"
        $quarantined = Read-Field $line "quarantined"
        $normalizedPending = Read-Field $line "normalizedPendingKeep"
        $equivalent = Read-Field $line "fullyEquivalent"
        [pscustomobject]@{
            raw = $line
            diff = $diff
            unresolvedObjects = $unresolved
            quarantined = $quarantined
            normalizedPendingKeep = $normalizedPending
            fullyEquivalent = $equivalent
            passed = (
                $diff -eq "0" -and
                $unresolved -eq "0" -and
                $quarantined -eq "0" -and
                $equivalent -eq "true"
            )
        }
    }
)

$failedComparisons = @($comparisons | Where-Object { -not $_.passed })
$passed = $comparisons.Count -gt 0 -and $failedComparisons.Count -eq 0
$status = if ($comparisons.Count -eq 0) {
    "NOT_READY"
} elseif ($passed) {
    "PASS"
} else {
    "FAIL"
}

$summary = [pscustomobject]@{
    status = $status
    passed = $passed
    serial = $device
    capturedAt = (Get-Date).ToString("o")
    projectionCompareCount = $comparisons.Count
    failedProjectionCompareCount = $failedComparisons.Count
    projectionBaselineCount = $baselineCount
    projectionContextResetCount = $contextResetCount
    candidateRejectCount = $candidateRejectCount
    cursorHeldCount = $cursorHeldCount
    comparisons = $comparisons
    logPath = $logPath
    artifactDirectory = $artifactDir
}

$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding utf8

switch ($status) {
    "PASS" {
        Write-Host "PASS: all captured DEVICE Shadow projection comparisons are fully equivalent." -ForegroundColor Green
        Write-Host "Summary: $summaryPath"
        exit 0
    }
    "NOT_READY" {
        Write-Host "NOT READY: no DEVICE Shadow projection comparison was captured." -ForegroundColor Yellow
        Write-Host "Expected sequence: Reset -> Full baseline -> mutate library -> Shadow pass -> manual Full oracle -> Evaluate."
        Write-Host "Summary: $summaryPath"
        exit 2
    }
    default {
        Write-Host "FAIL: at least one DEVICE Shadow projection comparison was not equivalent." -ForegroundColor Red
        Write-Host "Summary: $summaryPath"
        exit 1
    }
}
