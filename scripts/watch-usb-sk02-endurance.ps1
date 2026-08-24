param(
    [Parameter(Mandatory = $true)]
    [string]$SummaryPath,
    [ValidateRange(0, 2147483647)]
    [int]$RunnerProcessId = 0,
    [ValidateRange(2, 300)]
    [int]$PollSeconds = 15,
    [ValidateRange(1, 48)]
    [int]$TimeoutHours = 12
)

# THROWAWAY PROTOTYPE monitor: no phone access; watches one endurance summary and notifies Windows.
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$resolvedParent = Split-Path -Parent $SummaryPath
if (-not (Test-Path -LiteralPath $resolvedParent)) {
    throw "Summary directory does not exist: $resolvedParent"
}
$markerPath = "$SummaryPath.notified"
$deadline = (Get-Date).AddHours($TimeoutHours)
$summary = $null
$runnerExitedWithoutSummary = $false

while ((Get-Date) -lt $deadline) {
    if (Test-Path -LiteralPath $SummaryPath) {
        try {
            $candidate = Get-Content -Raw -LiteralPath $SummaryPath | ConvertFrom-Json
            if ($null -ne $candidate.passed) {
                $summary = $candidate
                break
            }
        } catch {
            # The writer may still be replacing the JSON; retry the next poll.
        }
    }
    if ($RunnerProcessId -gt 0 -and
        $null -eq (Get-Process -Id $RunnerProcessId -ErrorAction SilentlyContinue)) {
        # The runner writes summary.json before exiting. Allow a short filesystem flush grace,
        # then report a harness failure instead of waiting for the full monitor timeout.
        Start-Sleep -Seconds 2
        try {
            $candidate = Get-Content -Raw -LiteralPath $SummaryPath -ErrorAction Stop |
                ConvertFrom-Json
            if ($null -ne $candidate.passed) {
                $summary = $candidate
                break
            }
        } catch {
            # Missing or incomplete summary after the runner exited is a harness failure.
        }
        if ($null -eq $summary) {
            $runnerExitedWithoutSummary = $true
            break
        }
    }
    Start-Sleep -Seconds $PollSeconds
}

if ($runnerExitedWithoutSummary) {
    $passed = $false
    $title = "SK02 soak runner exited"
    $message = "The runner exited without producing a summary.`nPID=$RunnerProcessId`n$SummaryPath"
} elseif ($null -eq $summary) {
    $passed = $false
    $title = "SK02 endurance monitor timed out"
    $message = "No complete summary was produced within $TimeoutHours hours.`n$SummaryPath"
} elseif ($summary.passed) {
    $passed = $true
    $modeProperty = $summary.PSObject.Properties["mode"]
    $singleMode = if ($null -ne $modeProperty) { "$($modeProperty.Value)" } else { $null }
    if ($singleMode) {
        $title = "SK02 $singleMode soak passed"
        $message = "$singleMode completed.`n$SummaryPath"
    } else {
        $title = "SK02 endurance passed"
        $message = "Continuous and Lifecycle both completed.`n$SummaryPath"
    }
} else {
    $passed = $false
    $modeProperty = $summary.PSObject.Properties["mode"]
    $singleMode = if ($null -ne $modeProperty) { "$($modeProperty.Value)" } else { $null }
    $title = if ($singleMode) { "SK02 $singleMode soak stopped" } else { "SK02 endurance stopped" }
    $reason = if ($summary.failure) { "$($summary.failure)" } else { "No failure reason was recorded." }
    $message = "The test failed or hit a safety threshold.`n$reason`n$SummaryPath"
}

[pscustomobject]@{
    notifiedAt = (Get-Date).ToString("o")
    passed = $passed
    title = $title
    message = $message
} | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding utf8

try {
    Add-Type -AssemblyName System.Windows.Forms
    [System.Media.SystemSounds]::Asterisk.Play()
    $icon = if ($passed) {
        [System.Windows.Forms.MessageBoxIcon]::Information
    } else {
        [System.Windows.Forms.MessageBoxIcon]::Warning
    }
    [void][System.Windows.Forms.MessageBox]::Show(
        $message,
        $title,
        [System.Windows.Forms.MessageBoxButtons]::OK,
        $icon
    )
} catch {
    # The marker remains authoritative if the interactive desktop cannot show a dialog.
    Add-Content -LiteralPath $markerPath -Value "`nPopupError=$($_.Exception.Message)" -Encoding utf8
}
