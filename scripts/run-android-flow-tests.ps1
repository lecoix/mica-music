param(
    [string]$Serial
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Resolve-AdbPath {
    $candidates = @()
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }

    $localProperties = Join-Path $root "local.properties"
    if (Test-Path $localProperties) {
        $sdkLine = Get-Content $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($sdkLine) {
            $sdk = $sdkLine.Substring("sdk.dir=".Length).Replace("\\", "\")
            $candidates += (Join-Path $sdk "platform-tools\adb.exe")
        }
    }

    if ($env:LOCALAPPDATA) {
        $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    }

    $resolved = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
    if (-not $resolved) {
        throw "adb.exe not found. Configure ANDROID_SDK_ROOT / ANDROID_HOME or local.properties."
    }
    return $resolved
}

$adb = Resolve-AdbPath
$deviceRows = & $adb devices |
    Select-Object -Skip 1 |
    Where-Object { $_ -match '^\S+\s+device$' }

$devices = @($deviceRows | ForEach-Object { ($_ -split '\s+')[0] })

if ($Serial) {
    if ($devices -notcontains $Serial) {
        throw "Requested device '$Serial' is not connected. Connected devices: $($devices -join ', ')"
    }
} elseif ($devices.Count -eq 1) {
    $Serial = $devices[0]
} elseif ($devices.Count -eq 0) {
    throw "No connected Android device. Device flow tests require a connected ARM device."
} else {
    throw "Multiple devices are connected. Re-run with -Serial <serial>. Connected devices: $($devices -join ', ')"
}

$abi = (& $adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
if ($abi -notmatch '^arm64-v8a$|^armeabi-v7a$') {
    throw "Unsupported device ABI '$abi'. Mica currently packages ARM native libraries only."
}

Write-Host "Mica device flow target: $Serial ($abi)"
Write-Host "Using side-by-side QA application id; ordinary com.mica.music data is not used."

$previousSerial = $env:ANDROID_SERIAL
try {
    $env:ANDROID_SERIAL = $Serial
    $gradleArgs = @(
        ":app:connectedDebugAndroidTest",
        "-Pmica.qaSideBySide=true",
        "-Pandroid.testInstrumentationRunnerArguments.package=com.mica.music.flow",
        "--no-configuration-cache"
    )
    & (Join-Path $root "gradlew.bat") $gradleArgs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    $env:ANDROID_SERIAL = $previousSerial
}
