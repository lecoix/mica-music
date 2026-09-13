param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$AdbPath = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe",
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$scannerRoot = Split-Path -Parent $PSScriptRoot
$scannerAdb = (Resolve-Path -LiteralPath $AdbPath).Path
Push-Location $scannerRoot
try {
    $scannerDevices = & $scannerAdb devices
    if (-not ($scannerDevices | Where-Object { $_ -match ('^' + [regex]::Escape($Serial) + '\s+device$') })) {
        throw "Requested device is not online: $Serial"
    }
    if (-not $SkipBuild) {
        & ./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest '-Pmica.qaSideBySide=true' --no-daemon --no-configuration-cache --no-parallel
        if ($LASTEXITCODE -ne 0) { throw 'Scanner QA build failed' }
    }
    $scannerAppDir = 'app/build/outputs/apk/debug'
    $scannerTestDir = 'app/build/outputs/apk/androidTest/debug'
    $scannerApp = Get-Content "$scannerAppDir/output-metadata.json" -Raw | ConvertFrom-Json
    $scannerTest = Get-Content "$scannerTestDir/output-metadata.json" -Raw | ConvertFrom-Json
    if ($scannerApp.applicationId -ne 'com.mica.music.qa') { throw 'Refusing non-QA application APK' }
    if ($scannerApp.elements.Count -ne 1 -or $scannerTest.elements.Count -ne 1) { throw 'Expected one APK per artifact' }
    & $scannerAdb -s $Serial install -r (Join-Path $scannerAppDir $scannerApp.elements[0].outputFile)
    if ($LASTEXITCODE -ne 0) { throw 'QA application install failed' }
    & $scannerAdb -s $Serial install -r (Join-Path $scannerTestDir $scannerTest.elements[0].outputFile)
    if ($LASTEXITCODE -ne 0) { throw 'QA test install failed' }
    $scannerInstrumentation = @(& $scannerAdb -s $Serial shell pm list instrumentation) |
        Where-Object { $_ -match ('^instrumentation:' + [regex]::Escape($scannerTest.applicationId) + '/\S+ \(target=com\.mica\.music\.qa\)$') }
    if (@($scannerInstrumentation).Count -ne 1) { throw 'Unable to verify QA instrumentation target' }
    $scannerComponent = ($scannerInstrumentation -replace '^instrumentation:', '') -replace ' \(target=.*$', ''
    $scannerClasses = 'com.mica.music.data.SafUnindexedDiscoveryContractTest,com.mica.music.data.SafAutoStageProfileTest,com.mica.music.data.SafTenKMetadataProfileTest'
    $scannerEvidence = Join-Path $scannerRoot ('.scratch/scanner-discovery-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    $null = New-Item -ItemType Directory -Path $scannerEvidence
    Get-FileHash (Join-Path $scannerAppDir $scannerApp.elements[0].outputFile) |
        Format-List | Out-File "$scannerEvidence/apk-sha256.txt"
    & $scannerAdb -s $Serial shell input keyevent KEYCODE_WAKEUP
    & $scannerAdb -s $Serial shell am instrument -w -r -e class $scannerClasses $scannerComponent |
        Tee-Object -FilePath "$scannerEvidence/instrumentation.log"
    $scannerExitCode = $LASTEXITCODE
    & $scannerAdb -s $Serial logcat -d -v threadtime -s 'MICA_SCANNER_STAGE:I' 'MICA_S4_10K:I' '*:S' > "$scannerEvidence/timings.log"
    if ($scannerExitCode -ne 0 -or -not (Select-String -Path "$scannerEvidence/instrumentation.log" -Pattern '^OK \(3 tests\)$' -Quiet)) {
        throw "Scanner contracts failed or incomplete. Evidence: $scannerEvidence"
    }
    Write-Output "Scanner component contracts passed. Evidence: $scannerEvidence"
    Write-Output 'These tests do not replace the physical DocumentsUI, OEM notification, playback, or 8 GB capacity gates.'
} finally {
    Pop-Location
}
