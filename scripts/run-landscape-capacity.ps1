param(
    [string]$Serial = "736abe6c",
    [string]$Package = "com.mica.music",
    [int]$Steps = 200,
    [int]$RotationCycles = 12,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $repo ".codex-android-sdk\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) {
    $adb = "adb"
}

if (-not $SkipInstall) {
    & (Join-Path $repo "gradlew.bat") :app:installPerf --no-configuration-cache
    if ($LASTEXITCODE -ne 0) { throw "Perf install failed." }
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$output = Join-Path $repo ".scratch\landscape-player\measurements\$stamp"
New-Item -ItemType Directory -Path $output -Force | Out-Null
$deviceDir = "/sdcard/Android/data/$Package/files/capacity"
$component = "$Package/com.mica.music.perf.LandscapeCapacityActivity"

function Invoke-Adb([string[]]$Arguments) {
    & $adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
}

function Wait-CapacityDone([string]$Mode, [int]$TimeoutSeconds = 90) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $donePath = "$deviceDir/capacity-$Mode.done"
        $result = & $adb -s $Serial shell "if [ -f '$donePath' ]; then echo ready; fi"
        if ("$result" -match "ready") { return }
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for capacity mode '$Mode'."
}

function Start-CapacityMode([string]$Mode, [int]$ModeSteps) {
    Invoke-Adb @(
        "shell", "am", "force-stop", $Package
    )
    Invoke-Adb @("shell", "cmd", "window", "user-rotation", "lock", "1")
    Invoke-Adb @(
        "shell", "am", "start", "-W", "-n", $component,
        "--es", "mode", $Mode,
        "--ei", "steps", "$ModeSteps",
        "--el", "intervalMs", "120",
        "--ei", "queueSize", "10000",
        "--ei", "startIndex", "5000"
    )
}

try {
    Invoke-Adb @("shell", "mkdir", "-p", $deviceDir)

    foreach ($mode in @("queue", "commit")) {
        Start-CapacityMode $mode 1
        Wait-CapacityDone $mode
        Invoke-Adb @("pull", "$deviceDir/capacity-$mode.jsonl", (Join-Path $output "$mode.jsonl"))
        (& $adb -s $Serial shell dumpsys meminfo $Package) |
            Set-Content -LiteralPath (Join-Path $output "$mode-meminfo.txt") -Encoding utf8
    }

    foreach ($mode in @("coverflow", "retro", "photo")) {
        Start-CapacityMode $mode $Steps
        for ($cycle = 0; $cycle -lt $RotationCycles; $cycle++) {
            $rotation = if ($cycle % 2 -eq 0) { "1" } else { "0" }
            Invoke-Adb @("shell", "cmd", "window", "user-rotation", "lock", $rotation)
            Start-Sleep -Milliseconds 900
        }
        Wait-CapacityDone $mode 180
        Invoke-Adb @("pull", "$deviceDir/capacity-$mode.jsonl", (Join-Path $output "$mode.jsonl"))
        (& $adb -s $Serial shell dumpsys meminfo $Package) |
            Set-Content -LiteralPath (Join-Path $output "$mode-meminfo.txt") -Encoding utf8
    }

    (& $adb -s $Serial logcat -d -v threadtime -s "MicaCapacity:I" "TrackPerf:I" "AudioTrack:E" "ExoPlayerImplInternal:E" "*:S") |
        Set-Content -LiteralPath (Join-Path $output "capacity-logcat.txt") -Encoding utf8

    $summary = foreach ($mode in @("queue", "commit", "coverflow", "retro", "photo")) {
        $rows = Get-Content -LiteralPath (Join-Path $output "$mode.jsonl") -Encoding utf8 |
            ForEach-Object { $_ | ConvertFrom-Json }
        $settled = $rows | Where-Object { $_.phase -eq "browse-settled" } | Select-Object -Last 1
        $last = @($rows)[-1]
        $retainedLimit = if ($mode -in @("coverflow", "retro")) { 7 } elseif ($mode -eq "photo") { 5 } else { -1 }
        $structuralPass = @($rows | Where-Object { $_.queueSize -ne 10000 }).Count -eq 0
        if ($mode -eq "queue") {
            $structuralPass = $structuralPass -and $last.viewportWidthPx -gt $last.viewportHeightPx
        }
        elseif ($mode -eq "commit") {
            $structuralPass = $structuralPass -and $last.firstSongId -eq "capacity-song-9999"
        }
        else {
            $structuralPass = $structuralPass -and
                $settled.retainedBitmapCount -le $retainedLimit -and
                $settled.pendingLoadCount -eq 0 -and
                ($rows | Measure-Object reflectionCacheBytes -Maximum).Maximum -le (16 * 1024 * 1024) -and
                $last.phase -eq "released-after-gc"
        }
        [pscustomobject]@{
            Mode = $mode
            Samples = @($rows).Count
            MaxTotalPssKb = ($rows | Measure-Object totalPssKb -Maximum).Maximum
            MaxJavaUsedBytes = ($rows | Measure-Object javaUsedBytes -Maximum).Maximum
            MaxRetainedBitmaps = ($rows | Measure-Object retainedBitmapCount -Maximum).Maximum
            MaxPendingLoads = ($rows | Measure-Object pendingLoadCount -Maximum).Maximum
            MaxReflectionBytes = ($rows | Measure-Object reflectionCacheBytes -Maximum).Maximum
            SettledRetained = if ($null -ne $settled) { $settled.retainedBitmapCount } else { -1 }
            SettledPending = if ($null -ne $settled) { $settled.pendingLoadCount } else { -1 }
            RotationCallbacks = @($rows | Where-Object { $_.phase -eq "viewport-changed" }).Count
            FinalPhase = $last.phase
            FinalTotalPssKb = $last.totalPssKb
            StructuralPass = $structuralPass
        }
    }
    $summary | Format-Table -AutoSize | Out-String |
        Set-Content -LiteralPath (Join-Path $output "summary.txt") -Encoding utf8
    $summary | ConvertTo-Json |
        Set-Content -LiteralPath (Join-Path $output "summary.json") -Encoding utf8
    Write-Host "Capacity report: $output"
    Get-Content -LiteralPath (Join-Path $output "summary.txt")
}
finally {
    & $adb -s $Serial shell cmd window user-rotation free | Out-Null
    & $adb -s $Serial shell settings put system accelerometer_rotation 1 | Out-Null
}
