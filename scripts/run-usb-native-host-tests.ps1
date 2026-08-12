param(
    [string]$Compiler = "g++",
    [switch]$KeepBinaries
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$sourceDir = Join-Path $repoRoot "prototypes\usb-sk02-native\src\main\cpp"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDir = Join-Path $repoRoot ".scratch\usb-native-host-tests\p4-a1\$timestamp"
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

$compilerCommand = Get-Command $Compiler -ErrorAction Stop
$commonArgs = @(
    "-std=c++17",
    "-Wall",
    "-Wextra",
    "-Werror",
    "-pedantic",
    "-I$sourceDir"
)

# The underrun interleaving test uses std::thread and remains in the Android/NDK CMake gate.
# The workstation currently carries MinGW.org GCC 6.3 without a usable std::thread runtime, so the
# portable host lane is intentionally limited to pure deterministic scheduler/feedback/math tests.
$tests = @(
    "sk02_feedback_rate_filter_test",
    "sk02_stream_metrics_test",
    "sk02_iso_ahead_window_test",
    "usb_feedback_decoder_test",
    "usb_iso_scheduler_projection_test",
    "usb_iso_scheduler_stress_test"
)

$results = New-Object System.Collections.Generic.List[object]
foreach ($test in $tests) {
    $source = Join-Path $sourceDir "$test.cpp"
    if (-not (Test-Path $source)) {
        throw "Missing host test source: $source"
    }
    $binary = Join-Path $outputDir "$test.exe"
    $compileStdout = Join-Path $outputDir "$test.compile.stdout.log"
    $compileStderr = Join-Path $outputDir "$test.compile.stderr.log"
    $runLog = Join-Path $outputDir "$test.run.log"

    $compile = Start-Process `
        -FilePath $compilerCommand.Source `
        -ArgumentList ($commonArgs + @($source, "-o", $binary)) `
        -NoNewWindow `
        -Wait `
        -PassThru `
        -RedirectStandardOutput $compileStdout `
        -RedirectStandardError $compileStderr
    Get-Content $compileStdout -ErrorAction SilentlyContinue
    Get-Content $compileStderr -ErrorAction SilentlyContinue
    if ($compile.ExitCode -ne 0) {
        throw "Host compile failed: $test (exit=$($compile.ExitCode))"
    }

    & $binary 2>&1 | Tee-Object -FilePath $runLog
    $runExitCode = $LASTEXITCODE
    $results.Add([pscustomobject]@{
        Test = $test
        ExitCode = $runExitCode
        Result = if ($runExitCode -eq 0) { "PASS" } else { "FAIL" }
    })
    if ($runExitCode -ne 0) {
        throw "Host test failed: $test (exit=$runExitCode)"
    }
}

$summaryPath = Join-Path $outputDir "summary.csv"
$results | Export-Csv -Path $summaryPath -NoTypeInformation -Encoding UTF8
$results | Format-Table -AutoSize
Write-Output "P4-A1 native host tests passed: $($tests.Count)"
Write-Output "Evidence: $outputDir"

if (-not $KeepBinaries) {
    Get-ChildItem $outputDir -Filter "*.exe" | Remove-Item -Force
}
