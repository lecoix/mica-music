$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "usb-sk02-media3-soak-contract.ps1")

function Assert-SequenceEqual {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][int[]]$Actual,
        [Parameter(Mandatory)][AllowEmptyCollection()][int[]]$Expected,
        [Parameter(Mandatory)][string]$Label
    )

    if ($Actual.Count -ne $Expected.Count) {
        throw "$Label count mismatch: actual=$($Actual -join ',') expected=$($Expected -join ',')"
    }
    for ($index = 0; $index -lt $Expected.Count; $index++) {
        if ($Actual[$index] -ne $Expected[$index]) {
            throw "$Label mismatch at index ${index}: actual=$($Actual[$index]) expected=$($Expected[$index])"
        }
    }
}

$diagnostics = @'
MicaUsbPrototype: opened sr=44100 bits=24
MicaUsbPrototype: opened selection=generic-descriptor sr=48000 bits=24
MicaUsbPrototype: opened selection=generic-descriptor sr=96000 bits=24
MicaUsbPrototype: queue health sr=192000 transportErrorCode=0
MicaUsbPrototype: selected sr=384000
MicaUsbPrototype: opened selection=legacy-fallback sr=88200
MicaUsbPrototype: reopened sr=176400
'@

Assert-SequenceEqual `
    -Actual @(Get-UsbOpenedSampleRates -Diagnostics $diagnostics) `
    -Expected @(44100, 48000, 96000) `
    -Label "legacy+generic collector"

Assert-SequenceEqual `
    -Actual @(Get-UsbOpenedSampleRates -Diagnostics "") `
    -Expected @() `
    -Label "empty diagnostics"

Write-Output "usb-soak sample-rate collector contract: PASS"
