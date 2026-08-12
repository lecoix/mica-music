param(
    [string]$Serial
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$probeScript = Join-Path $PSScriptRoot "run-usb-sk02-descriptor-prototype.ps1"
$adb = Join-Path $root ".codex-android-sdk\platform-tools\adb.exe"
$output = Join-Path $root "app\src\test\resources\usb\sk02\raw-descriptors.hex"
$tag = "MicaUsbPrototype"

$probeArgs = @{}
if ($Serial) { $probeArgs.Serial = $Serial }
& $probeScript @probeArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (-not $Serial) {
    $connected = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
    if ($connected.Count -ne 1) {
        throw "Probe finished, but fixture capture cannot resolve a unique ADB device. Pass -Serial <ip:port>."
    }
    $Serial = ($connected -split "\s+")[0]
}

$logs = & $adb -s $Serial logcat -d -s "${tag}:I" "*:S"
$matches = [regex]::Matches(($logs -join "`n"), 'rawDescriptorHex=([0-9a-fA-F]+)')
if ($matches.Count -eq 0) {
    throw "No rawDescriptorHex evidence was produced. Attach the proven SK02 and rerun; refusing to fabricate P3.0 fixture data."
}

$hex = $matches[$matches.Count - 1].Groups[1].Value.ToLowerInvariant()
if (($hex.Length % 2) -ne 0 -or $hex.Length -lt 18) {
    throw "Captured rawDescriptorHex is malformed (hex chars=$($hex.Length))."
}

$directory = Split-Path -Parent $output
New-Item -ItemType Directory -Force -Path $directory | Out-Null
Set-Content -LiteralPath $output -Value $hex -Encoding ascii -NoNewline
Write-Host ">> Saved SK02 raw descriptor fixture: $output ($($hex.Length / 2) bytes)" -ForegroundColor Green
