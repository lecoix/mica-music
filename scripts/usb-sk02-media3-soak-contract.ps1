Set-StrictMode -Version Latest

function Get-SoakOutputPath {
    param([Parameter(Mandatory)][string]$Mode)

    if ($Mode -eq "SharedPcmBaseline") { return "SharedPcm" }
    return "UsbDirectPcm"
}

function Get-PlaybackReadiness {
    param(
        [Parameter(Mandatory)]$State,
        [switch]$AllowTransientBuffering
    )

    if ($State.HasError) { return "Failed" }
    if ($State.State -eq 3) { return "Ready" }
    if ($AllowTransientBuffering -and $State.State -eq 6) { return "Retry" }
    return "Failed"
}

function Get-ControlOutcome {
    param(
        [Parameter(Mandatory)][string]$Logs,
        [Parameter(Mandatory)][string]$LogName
    )

    if ($Logs -match "media3Control=$([regex]::Escape($LogName)) complete=true") {
        return "Succeeded"
    }
    if ($Logs -match "media3Control=$([regex]::Escape($LogName)) complete=false error=IllegalStateException:service_not_active") {
        return "Retry"
    }
    if ($Logs -match "media3Control=$([regex]::Escape($LogName)) complete=false") {
        return "Failed"
    }
    return "Pending"
}

function ConvertFrom-PowerDump {
    param([Parameter(Mandatory)][object[]]$Lines)

    # Invoke-Adb deliberately returns its native output as one array object. Flatten it
    # before joining; otherwise PowerShell renders the dump as the literal "System.Object[]".
    $flatLines = @($Lines | ForEach-Object { $_ })
    $text = $flatLines -join "`n"
    $wakefulnessMatch = [regex]::Match(
        $text,
        '(?m)^\s*mWakefulness\s*=\s*(?<value>[A-Za-z]+)\s*$'
    )
    if (-not $wakefulnessMatch.Success) { return $null }

    $wakeListStart = -1
    for ($index = 0; $index -lt $flatLines.Count; $index++) {
        if ("$($flatLines[$index])" -match '^Wake Locks: size=') {
            $wakeListStart = $index
            break
        }
    }
    if ($wakeListStart -lt 0) { return $null }
    $wakeList = @($flatLines | Select-Object -Skip $wakeListStart -First 12) -join "`n"
    [pscustomobject]@{
        Wakefulness = $wakefulnessMatch.Groups["value"].Value
        PlaybackWakeLockHeld = $wakeList -match "ExoPlayer:WakeLockManager"
    }
}
