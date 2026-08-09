param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [ValidateRange(1, 1440)]
    [int]$ContinuousMinutes = 120,
    [ValidateRange(1, 1440)]
    [int]$LifecycleMinutes = 120,
    [ValidateRange(0, 100)]
    [int]$CrashCycleEvery = 3,
    [switch]$NoNotification
)

# THROWAWAY PROTOTYPE orchestrator: runs both single-SK02 endurance modes in sequence.
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runner = Join-Path $PSScriptRoot "run-usb-sk02-media3-soak.ps1"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$artifactDir = Join-Path $root ".scratch\usb-sk02-endurance\$stamp"
$summaryPath = Join-Path $artifactDir "summary.json"
$monitorProcessId = $null
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null

if (-not $NoNotification) {
    try {
        $monitor = Join-Path $PSScriptRoot "watch-usb-sk02-endurance.ps1"
        $windowsPowerShell = (Get-Command powershell.exe -ErrorAction Stop).Source
        $monitorProcess = Start-Process -FilePath $windowsPowerShell -ArgumentList @(
            "-NoProfile",
            "-STA",
            "-ExecutionPolicy", "Bypass",
            "-File", $monitor,
            "-SummaryPath", $summaryPath
        ) -WindowStyle Hidden -PassThru
        $monitorProcessId = $monitorProcess.Id
        Write-Host "Completion monitor started pid=$monitorProcessId"
    } catch {
        Write-Warning "Could not start completion monitor: $($_.Exception.Message)"
    }
}

function Get-LatestSoakSummary {
    $soakRoot = Join-Path $root ".scratch\usb-sk02-soak"
    $latest = Get-ChildItem -LiteralPath $soakRoot -Directory |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $latest) { throw "No soak artifact directory was produced." }
    $path = Join-Path $latest.FullName "summary.json"
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing soak summary: $path" }
    return Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
}

$startedAt = Get-Date
$continuous = $null
$lifecycle = $null
$failure = $null
try {
    Write-Host "Starting Continuous endurance for $ContinuousMinutes minutes"
    & $runner -Serial $Serial -Mode Continuous -DurationMinutes $ContinuousMinutes `
        -SampleIntervalSeconds 60 -EvidenceEverySamples 30
    $continuousExitCode = $LASTEXITCODE
    $continuous = Get-LatestSoakSummary
    if ($continuousExitCode -ne 0) {
        throw "Continuous endurance failed: $($continuous.failure)"
    }

    Write-Host "Starting Lifecycle endurance for $LifecycleMinutes minutes"
    & $runner -Serial $Serial -Mode Lifecycle -DurationMinutes $LifecycleMinutes `
        -CrashCycleEvery $CrashCycleEvery -EvidenceEverySamples 10 -SkipBuild -SkipInstall
    $lifecycleExitCode = $LASTEXITCODE
    $lifecycle = Get-LatestSoakSummary
    if ($lifecycleExitCode -ne 0) {
        throw "Lifecycle endurance failed: $($lifecycle.failure)"
    }
} catch {
    $failure = $_.Exception.ToString()
} finally {
    [pscustomobject]@{
        passed = ($null -eq $failure -and $continuous.passed -and $lifecycle.passed)
        failure = $failure
        serial = $Serial
        startedAt = $startedAt.ToString("o")
        finishedAt = (Get-Date).ToString("o")
        continuous = $continuous
        lifecycle = $lifecycle
        monitorProcessId = $monitorProcessId
        artifactDirectory = $artifactDir
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding utf8
}

if ($failure -or $null -eq $continuous -or $null -eq $lifecycle -or
    -not $continuous.passed -or -not $lifecycle.passed) {
    Write-Host "FAIL. Summary: $summaryPath" -ForegroundColor Yellow
    exit 1
}
Write-Host "PASS. Summary: $summaryPath" -ForegroundColor Green
