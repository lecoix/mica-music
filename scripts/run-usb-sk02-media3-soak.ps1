param(
    [string]$Serial,
    [ValidateSet("Continuous", "Lifecycle", "CrashRecovery", "ResumeStress", "RebuildResumeStress", "BoundaryResumeStress")]
    [string]$Mode = "Lifecycle",
    [ValidateRange(1, 1440)]
    [int]$DurationMinutes = 10,
    [ValidateRange(1, 3600)]
    [int]$HoldSeconds = 8,
    [ValidateRange(0, 100)]
    [int]$CrashCycleEvery = 3,
    [ValidateRange(0, 10000)]
    [int]$FirstMediaIndex = 0,
    [ValidateRange(0, 10000)]
    [int]$SecondMediaIndex = 1,
    [ValidateRange(10, 60)]
    [int]$SampleIntervalSeconds = 60,
    [ValidateRange(1, 1000)]
    [int]$EvidenceEverySamples = 30,
    [ValidateRange(5, 100)]
    [int]$MinimumBatteryLevel = 20,
    [ValidateRange(30, 60)]
    [double]$MaximumBatteryTempC = 45,
    [string]$RunStamp,
    [switch]$SkipBuild,
    [switch]$SkipInstall
)

# THROWAWAY PROTOTYPE runner: single known Fosi SK02 + side-by-side Mica QA package only.
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = Join-Path $root ".codex-android-sdk\platform-tools\adb.exe"
$gradle = Join-Path $root "gradlew.bat"
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$metadataPath = Join-Path $root "app\build\outputs\apk\debug\output-metadata.json"
$packageName = "com.mica.music.qa"
$activity = "$packageName/com.mica.music.MainActivity"
$receiver = "$packageName/com.mica.music.media.usbprototype.UsbSk02DescriptorPrototypeReceiver"
$tag = "MicaUsbPrototype"
if ($RunStamp -and $RunStamp -notmatch '^\d{8}-\d{6}$') {
    throw "RunStamp must use yyyyMMdd-HHmmss: $RunStamp"
}
$runStamp = if ($RunStamp) { $RunStamp } else { Get-Date -Format "yyyyMMdd-HHmmss" }
$artifactDir = Join-Path $root ".scratch\usb-sk02-soak\$runStamp"
$eventLog = Join-Path $artifactDir "events.log"
$summaryPath = Join-Path $artifactDir "summary.json"
$metricsPath = Join-Path $artifactDir "metrics.csv"
$observedRates = [System.Collections.Generic.HashSet[int]]::new()
$startedAt = Get-Date
$testStartedAt = $null
$deadline = $startedAt
$cycle = 0
$failure = $null
$cleanupDriversBound = $false
$sample = 0
$minPssKb = $null
$maxPssKb = $null
$minFdCount = $null
$maxFdCount = $null
$maxBatteryTempC = $null

New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null

function Write-Event {
    param([string]$Message)
    $line = "$(Get-Date -Format o) $Message"
    Write-Host $line
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        try {
            Add-Content -LiteralPath $eventLog -Value $line -Encoding utf8
            return
        } catch [System.IO.IOException] {
            if ($attempt -eq 4) { throw }
            Start-Sleep -Milliseconds 50
        }
    }
}

function Invoke-Adb {
    param(
        [string[]]$Arguments,
        [switch]$AllowFailure
    )
    # ADB emits normal daemon startup messages on stderr. Under
    # ErrorActionPreference=Stop PowerShell turns those into terminating errors,
    # so judge the native command by its exit code instead.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $adb -s $Serial @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "adb failed ($exitCode): $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return ,$output
}

function Send-PrototypeAction {
    param(
        [string]$Suffix,
        [string[]]$Extras = @()
    )
    $arguments = @(
        "shell", "am", "broadcast", "--include-stopped-packages",
        "-n", $receiver,
        "-a", "$packageName.debug.$Suffix",
        "-p", $packageName
    ) + $Extras
    Invoke-Adb -Arguments $arguments | Out-Null
}

function Get-PrototypeLog {
    (Invoke-Adb -Arguments @("logcat", "-d", "-s", "${tag}:I", "*:S") -AllowFailure) -join "`n"
}

function Save-Evidence {
    param([string]$Label)
    $safe = $Label -replace '[^a-zA-Z0-9_-]', '_'
    (Invoke-Adb -Arguments @("logcat", "-d", "-v", "threadtime") -AllowFailure) |
        Set-Content -LiteralPath (Join-Path $artifactDir "$safe-logcat.txt") -Encoding utf8
    (Invoke-Adb -Arguments @(
        "shell", "run-as", $packageName, "cat", "files/diagnostics/current-session.log"
    ) -AllowFailure) | Set-Content -LiteralPath `
        (Join-Path $artifactDir "$safe-diagnostics.txt") -Encoding utf8
    (Invoke-Adb -Arguments @("shell", "dumpsys", "media_session") -AllowFailure) |
        Set-Content -LiteralPath (Join-Path $artifactDir "$safe-media-session.txt") -Encoding utf8
}

function Get-Diagnostics {
    (Invoke-Adb -Arguments @(
        "shell", "run-as", $packageName, "cat", "files/diagnostics/current-session.log"
    ) -AllowFailure) -join "`n"
}

function Get-QaPlaybackState {
    $dump = (Invoke-Adb -Arguments @("shell", "dumpsys", "media_session")) -join "`n"
    $escaped = [regex]::Escape($packageName)
    $match = [regex]::Match(
        $dump,
        "androidx\.media3\.session\.id\.\s+$escaped(?s:.*?)" +
            "state=PlaybackState \{state=(?<state>\d+), position=(?<position>\d+)(?<rest>[^}]*)\}"
    )
    if (-not $match.Success) {
        throw "QA Media3 session was not found. Scan/select music once in the QA app first."
    }
    [pscustomobject]@{
        State = [int]$match.Groups["state"].Value
        PositionMs = [long]$match.Groups["position"].Value
        HasError = $match.Groups["rest"].Value -notmatch "error=null"
    }
}

function Write-MetricSample {
    param([string]$Phase)
    $state = Get-QaPlaybackState
    if ($state.State -ne 3 -or $state.HasError) {
        throw "Metric sample found non-playing state: $($state | ConvertTo-Json -Compress)"
    }

    $pidText = ((Invoke-Adb -Arguments @("shell", "pidof", $packageName)) -join "").Trim()
    if (-not $pidText) { throw "QA process disappeared during metric sample." }
    $processId = ($pidText -split '\s+')[0]

    $meminfo = (Invoke-Adb -Arguments @("shell", "dumpsys", "meminfo", $packageName)) -join "`n"
    $pssMatch = [regex]::Match($meminfo, 'TOTAL PSS:\s*(?<value>[\d,]+)')
    if (-not $pssMatch.Success) {
        $pssMatch = [regex]::Match($meminfo, '(?m)^\s*TOTAL\s+(?<value>[\d,]+)\s+')
    }
    if (-not $pssMatch.Success) { throw "Could not parse TOTAL PSS from dumpsys meminfo." }
    $pssKb = [long]($pssMatch.Groups["value"].Value -replace ',', '')

    $fds = Invoke-Adb -Arguments @("shell", "run-as", $packageName, "ls", "/proc/$processId/fd")
    $fdCount = @($fds | Where-Object { "$_".Trim() }).Count
    if ($fdCount -le 0) { throw "Could not count QA file descriptors." }

    $cpuinfo = (Invoke-Adb -Arguments @("shell", "dumpsys", "cpuinfo")) -join "`n"
    $escapedPackage = [regex]::Escape($packageName)
    $cpuMatch = [regex]::Match(
        $cpuinfo,
        "(?m)^\s*(?<value>[\d.]+)%\s+\d+/${escapedPackage}:"
    )
    $cpuPercent = if ($cpuMatch.Success) { [double]$cpuMatch.Groups["value"].Value } else { 0.0 }

    $battery = (Invoke-Adb -Arguments @("shell", "dumpsys", "battery")) -join "`n"
    $levelMatch = [regex]::Match($battery, '(?m)^\s*level:\s*(?<value>\d+)')
    $tempMatch = [regex]::Match($battery, '(?m)^\s*temperature:\s*(?<value>-?\d+)')
    if (-not $levelMatch.Success -or -not $tempMatch.Success) {
        throw "Could not parse battery level/temperature."
    }
    $batteryLevel = [int]$levelMatch.Groups["value"].Value
    $batteryTempC = [double]$tempMatch.Groups["value"].Value / 10.0
    if ($batteryLevel -le $MinimumBatteryLevel) {
        throw "Battery safety threshold reached: $batteryLevel% <= $MinimumBatteryLevel%."
    }
    if ($batteryTempC -ge $MaximumBatteryTempC) {
        throw "Battery temperature safety threshold reached: ${batteryTempC}C >= ${MaximumBatteryTempC}C."
    }

    $script:sample++
    if ($null -eq $script:minPssKb -or $pssKb -lt $script:minPssKb) { $script:minPssKb = $pssKb }
    if ($null -eq $script:maxPssKb -or $pssKb -gt $script:maxPssKb) { $script:maxPssKb = $pssKb }
    if ($null -eq $script:minFdCount -or $fdCount -lt $script:minFdCount) { $script:minFdCount = $fdCount }
    if ($null -eq $script:maxFdCount -or $fdCount -gt $script:maxFdCount) { $script:maxFdCount = $fdCount }
    if ($null -eq $script:maxBatteryTempC -or $batteryTempC -gt $script:maxBatteryTempC) {
        $script:maxBatteryTempC = $batteryTempC
    }

    [pscustomobject]@{
        timestamp = (Get-Date).ToString("o")
        sample = $script:sample
        mode = $Mode
        phase = $Phase
        cycle = $cycle
        pid = $processId
        positionMs = $state.PositionMs
        pssKb = $pssKb
        fdCount = $fdCount
        cpuPercent = $cpuPercent
        batteryLevel = $batteryLevel
        batteryTempC = $batteryTempC
    } | Export-Csv -LiteralPath $metricsPath -NoTypeInformation -Append -Encoding utf8

    Write-Event (
        "Metric sample=$script:sample phase=$Phase positionMs=$($state.PositionMs) " +
            "pssKb=$pssKb fdCount=$fdCount cpu=$cpuPercent% " +
            "battery=$batteryLevel% tempC=$batteryTempC"
    )
}

function Wait-PrototypeResult {
    param(
        [string]$Pattern,
        [int]$TimeoutSeconds = 20
    )
    $limit = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 500
        $logs = Get-PrototypeLog
        if ($logs -match $Pattern) { return $logs }
    } while ((Get-Date) -lt $limit)
    throw "Timed out waiting for prototype log pattern: $Pattern"
}

function Invoke-Control {
    param(
        [string]$Suffix,
        [string]$LogName,
        [string[]]$Extras = @()
    )
    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    Send-PrototypeAction -Suffix $Suffix -Extras $Extras
    $logs = Wait-PrototypeResult -Pattern "media3Control=$LogName complete=(true|false)"
    Add-Content -LiteralPath $eventLog -Value $logs -Encoding utf8
    if ($logs -match "media3Control=$LogName complete=false") {
        throw "Media3 control failed: $LogName"
    }
}

function Start-Qa {
    Invoke-Adb -Arguments @("shell", "am", "start", "-n", $activity) | Out-Null
    Start-Sleep -Seconds 4
}

function Click-PermissionButtonIfPresent {
    Invoke-Adb -Arguments @("shell", "uiautomator", "dump", "/sdcard/mica-soak-window.xml") `
        -AllowFailure | Out-Null
    $raw = (Invoke-Adb -Arguments @("shell", "cat", "/sdcard/mica-soak-window.xml") `
        -AllowFailure) -join ""
    if (-not $raw) { return $false }
    try { [xml]$document = $raw } catch { return $false }
    $positive = $document.SelectNodes("//node[@clickable='true']") | Where-Object {
        $_.text -match '^(仅在使用中允许|始终允许|允许|确定|Allow|While using the app|OK)$'
    } | Select-Object -Last 1
    if ($null -eq $positive -or $positive.bounds -notmatch `
        '^\[(?<x1>\d+),(?<y1>\d+)\]\[(?<x2>\d+),(?<y2>\d+)\]$') {
        return $false
    }
    $x = ([int]$Matches.x1 + [int]$Matches.x2) / 2
    $y = ([int]$Matches.y1 + [int]$Matches.y2) / 2
    Write-Event "Approving QA-only Android permission button '$($positive.text)' at $x,$y"
    Invoke-Adb -Arguments @("shell", "input", "tap", "$x", "$y") | Out-Null
    return $true
}

function Ensure-UsbPermission {
    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    Send-PrototypeAction -Suffix "USB_SK02_PROBE"
    $limit = (Get-Date).AddSeconds(45)
    do {
        Start-Sleep -Seconds 1
        $logs = Get-PrototypeLog
        if ($logs -match "probe=complete") { return }
        if ($logs -match "permission_denied|target_not_found|open_failed") {
            throw "USB permission/probe failed.`n$logs"
        }
        if ($logs -match "permission=requested") {
            [void](Click-PermissionButtonIfPresent)
        }
    } while ((Get-Date) -lt $limit)
    throw "Timed out waiting for SK02 USB permission."
}

function Assert-NoPlaybackFailure {
    param([string]$Label)
    $diagnostics = Get-Diagnostics
    foreach ($match in [regex]::Matches($diagnostics, 'opened sr=(\d+)')) {
        [void]$observedRates.Add([int]$match.Groups[1].Value)
    }
    $logcat = (Invoke-Adb -Arguments @("logcat", "-d", "-v", "brief") -AllowFailure) -join "`n"
    $bad = @(
        "PlaybackException",
        "WriteException",
        "exactPcm32Rejected",
        "FATAL EXCEPTION",
        "UsbExclusivePrototype: underrunBytes="
    )
    foreach ($pattern in $bad) {
        if ($diagnostics -match [regex]::Escape($pattern) -or
            $logcat -match [regex]::Escape($pattern)) {
            Save-Evidence -Label "failure-$Label"
            throw "Playback failure pattern '$pattern' detected during $Label."
        }
    }
}

function Assert-PlayingAdvances {
    param([string]$Label)
    $positions = [System.Collections.Generic.List[long]]::new()
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $state = Get-QaPlaybackState
        if ($state.State -ne 3 -or $state.HasError) {
            throw "$Label did not remain in clean PLAYING state: $($state | ConvertTo-Json -Compress)"
        }
        $positions.Add($state.PositionMs)
        if ($positions.Count -ge 2) {
            $delta = $positions[$positions.Count - 1] - $positions[$positions.Count - 2]
            # dumpsys exposes a periodically refreshed media-session snapshot, not a
            # sample-accurate clock. Any clear forward movement proves liveness; a
            # negative jump proves that repeat/track transition crossed a boundary.
            if ($delta -ge 750 -or $delta -le -1000) { return }
        }
        if ($attempt -lt 3) { Start-Sleep -Seconds 5 }
    }
    throw "$Label showed no progress across multiple samples: $($positions -join ', ')"
}

function Assert-ExclusiveDrivers {
    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    Send-PrototypeAction -Suffix "USB_SK02_NATIVE_FD_PROBE"
    $logs = Wait-PrototypeResult -Pattern "nativeFdProbe=complete"
    if ($logs -notmatch "control=\{driver=usbfs" -or
        $logs -notmatch "streaming=\{driver=usbfs") {
        throw "SK02 was not exclusively owned by usbfs while playing.`n$logs"
    }
}

function Recover-KernelDrivers {
    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    Send-PrototypeAction -Suffix "USB_SK02_RECONNECT"
    [void](Wait-PrototypeResult -Pattern "reconnect=complete")
    Start-Sleep -Seconds 2
    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    Send-PrototypeAction -Suffix "USB_SK02_NATIVE_FD_PROBE"
    $logs = Wait-PrototypeResult -Pattern "nativeFdProbe=complete"
    $bound = $logs -match "control=\{driver=snd-usb-audio" -and
        $logs -match "streaming=\{driver=snd-usb-audio"
    if (-not $bound) { throw "Kernel USB audio drivers were not restored.`n$logs" }
    return $true
}

function Restart-ExclusiveAfterCrash {
    Write-Event "Intentional QA process kill while exclusive"
    Invoke-Adb -Arguments @("shell", "am", "force-stop", $packageName) | Out-Null
    Start-Sleep -Seconds 2
    # A cold restart that intends to remain exclusive must fresh-open the DAC
    # directly. Rebinding snd-usb-audio only to detach it again creates an
    # unnecessary kernel-driver transition and a second race window.
    Start-Qa
    Invoke-Control -Suffix "USB_SK02_MEDIA3_PLAY" -LogName "play"
    Start-Sleep -Seconds $HoldSeconds
    Assert-ExclusiveDrivers
    Assert-PlayingAdvances -Label "post-crash-restart"
}

function Save-PeriodicEvidence {
    param([string]$Label)
    if ($script:sample -eq 1 -or ($script:sample % $EvidenceEverySamples) -eq 0) {
        Save-Evidence -Label $Label
    }
}

function Run-ContinuousSoak {
    Write-Event "Continuous mode: repeat index $FirstMediaIndex without lifecycle mutations"
    Invoke-Control -Suffix "USB_SK02_MEDIA3_REPEAT_ONE" -LogName "repeat_one"
    Invoke-Control -Suffix "USB_SK02_MEDIA3_SELECT_INDEX" -LogName "select_index" `
        -Extras @("--ei", "mediaIndex", "$FirstMediaIndex")
    Start-Sleep -Seconds $HoldSeconds
    Assert-PlayingAdvances -Label "continuous-start"
    Assert-NoPlaybackFailure -Label "continuous-start"
    Assert-ExclusiveDrivers
    Write-MetricSample -Phase "steady"
    Save-PeriodicEvidence -Label "sample-$script:sample"

    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds $SampleIntervalSeconds
        Assert-PlayingAdvances -Label "continuous-sample-$($script:sample + 1)"
        Assert-NoPlaybackFailure -Label "continuous-sample-$($script:sample + 1)"
        if (($script:sample % 5) -eq 0) { Assert-ExclusiveDrivers }
        Write-MetricSample -Phase "steady"
        Save-PeriodicEvidence -Label "sample-$script:sample"
    }
}

function Run-LifecycleSoak {
    Invoke-Control -Suffix "USB_SK02_MEDIA3_REPEAT_OFF" -LogName "repeat_off"
    do {
        $script:cycle++
        Write-Event "Cycle ${cycle}: select index $FirstMediaIndex"
        Invoke-Control -Suffix "USB_SK02_MEDIA3_SELECT_INDEX" -LogName "select_index" `
            -Extras @("--ei", "mediaIndex", "$FirstMediaIndex")
        Start-Sleep -Seconds $HoldSeconds
        Assert-PlayingAdvances -Label "cycle-$cycle-first"
        Assert-ExclusiveDrivers

        Write-Event "Cycle ${cycle}: pause/resume"
        Invoke-Control -Suffix "USB_SK02_MEDIA3_PAUSE" -LogName "pause"
        Start-Sleep -Seconds 3
        $paused = Get-QaPlaybackState
        if ($paused.State -ne 2 -or $paused.HasError) {
            throw "Pause check failed: $($paused | ConvertTo-Json -Compress)"
        }
        Invoke-Control -Suffix "USB_SK02_MEDIA3_PLAY" -LogName "play"
        Assert-PlayingAdvances -Label "cycle-$cycle-resume"

        Write-Event "Cycle ${cycle}: seek near end and cross track boundary"
        Invoke-Control -Suffix "USB_SK02_MEDIA3_SEEK_NEAR_END" -LogName "seek_near_end"
        Start-Sleep -Seconds ($HoldSeconds + 5)
        Assert-PlayingAdvances -Label "cycle-$cycle-boundary"

        Write-Event "Cycle ${cycle}: select index $SecondMediaIndex"
        Invoke-Control -Suffix "USB_SK02_MEDIA3_SELECT_INDEX" -LogName "select_index" `
            -Extras @("--ei", "mediaIndex", "$SecondMediaIndex")
        Start-Sleep -Seconds $HoldSeconds
        Assert-PlayingAdvances -Label "cycle-$cycle-second"
        Assert-NoPlaybackFailure -Label "cycle-$cycle"
        Write-MetricSample -Phase "cycle-complete"
        Save-PeriodicEvidence -Label "sample-$script:sample-cycle-$cycle"

        if ($CrashCycleEvery -gt 0 -and ($cycle % $CrashCycleEvery) -eq 0) {
            Restart-ExclusiveAfterCrash
        }
    } while ((Get-Date) -lt $deadline)
}

function Run-CrashRecoverySoak {
    Invoke-Control -Suffix "USB_SK02_MEDIA3_REPEAT_ONE" -LogName "repeat_one"
    do {
        $script:cycle++
        Write-Event "CrashRecovery cycle ${cycle}: select index $FirstMediaIndex"
        Invoke-Control -Suffix "USB_SK02_MEDIA3_SELECT_INDEX" -LogName "select_index" `
            -Extras @("--ei", "mediaIndex", "$FirstMediaIndex")
        Start-Sleep -Seconds $HoldSeconds
        Assert-PlayingAdvances -Label "crash-cycle-$cycle-before-kill"
        Assert-ExclusiveDrivers
        Assert-NoPlaybackFailure -Label "crash-cycle-$cycle-before-kill"

        Restart-ExclusiveAfterCrash
        Assert-NoPlaybackFailure -Label "crash-cycle-$cycle-after-restart"
        Write-MetricSample -Phase "crash-recovery-complete"
        Save-PeriodicEvidence -Label "sample-$script:sample-crash-cycle-$cycle"
    } while ((Get-Date) -lt $deadline)
}

function Run-ResumeStressSoak {
    Invoke-Control -Suffix "USB_SK02_MEDIA3_REPEAT_ONE" -LogName "repeat_one"
    Invoke-Control -Suffix "USB_SK02_MEDIA3_SELECT_INDEX" -LogName "select_index" `
        -Extras @("--ei", "mediaIndex", "$FirstMediaIndex")
    Start-Sleep -Seconds $HoldSeconds
    Assert-PlayingAdvances -Label "resume-initial"
    Assert-ExclusiveDrivers
    do {
        $script:cycle++
        Write-Event "ResumeStress cycle ${cycle}: pause/resume"
        Invoke-Control -Suffix "USB_SK02_MEDIA3_PAUSE" -LogName "pause"
        Start-Sleep -Seconds 1
        $paused = Get-QaPlaybackState
        if ($paused.State -ne 2 -or $paused.HasError) {
            throw "Pause check failed: $($paused | ConvertTo-Json -Compress)"
        }
        Invoke-Control -Suffix "USB_SK02_MEDIA3_PLAY" -LogName "play"
        Assert-PlayingAdvances -Label "resume-cycle-$cycle"
        Assert-NoPlaybackFailure -Label "resume-cycle-$cycle"
        Assert-ExclusiveDrivers
        Write-MetricSample -Phase "resume-complete"
        Save-PeriodicEvidence -Label "sample-$script:sample-resume-cycle-$cycle"
    } while ((Get-Date) -lt $deadline)
}

function Run-RebuildResumeStressSoak {
    Invoke-Control -Suffix "USB_SK02_MEDIA3_REPEAT_OFF" -LogName "repeat_off"
    do {
        $script:cycle++
        $mediaIndex = if (($cycle % 2) -eq 1) { $FirstMediaIndex } else { $SecondMediaIndex }
        Write-Event "RebuildResumeStress cycle ${cycle}: select index $mediaIndex then pause/resume"
        Invoke-Control -Suffix "USB_SK02_MEDIA3_SELECT_INDEX" -LogName "select_index" `
            -Extras @("--ei", "mediaIndex", "$mediaIndex")
        Start-Sleep -Seconds $HoldSeconds
        Assert-PlayingAdvances -Label "rebuild-cycle-$cycle-before-pause"
        Assert-ExclusiveDrivers

        Invoke-Control -Suffix "USB_SK02_MEDIA3_PAUSE" -LogName "pause"
        Start-Sleep -Seconds 1
        $paused = Get-QaPlaybackState
        if ($paused.State -ne 2 -or $paused.HasError) {
            throw "Pause check failed: $($paused | ConvertTo-Json -Compress)"
        }
        Invoke-Control -Suffix "USB_SK02_MEDIA3_PLAY" -LogName "play"
        Assert-PlayingAdvances -Label "rebuild-cycle-$cycle-resume"
        Assert-NoPlaybackFailure -Label "rebuild-cycle-$cycle"
        Assert-ExclusiveDrivers
        Write-MetricSample -Phase "rebuild-resume-complete"
        Save-PeriodicEvidence -Label "sample-$script:sample-rebuild-cycle-$cycle"
    } while ((Get-Date) -lt $deadline)
}

function Run-BoundaryResumeStressSoak {
    Invoke-Control -Suffix "USB_SK02_MEDIA3_REPEAT_OFF" -LogName "repeat_off"
    do {
        $script:cycle++
        Write-Event "BoundaryResumeStress cycle ${cycle}: select, seek across boundary, then pause/resume"
        Invoke-Control -Suffix "USB_SK02_MEDIA3_SELECT_INDEX" -LogName "select_index" `
            -Extras @("--ei", "mediaIndex", "$FirstMediaIndex")
        Start-Sleep -Seconds $HoldSeconds
        Assert-PlayingAdvances -Label "boundary-cycle-$cycle-before-seek"
        Assert-ExclusiveDrivers

        Invoke-Control -Suffix "USB_SK02_MEDIA3_SEEK_NEAR_END" -LogName "seek_near_end"
        Start-Sleep -Seconds ($HoldSeconds + 5)
        Assert-PlayingAdvances -Label "boundary-cycle-$cycle-after-boundary"

        Invoke-Control -Suffix "USB_SK02_MEDIA3_PAUSE" -LogName "pause"
        Start-Sleep -Seconds 1
        $paused = Get-QaPlaybackState
        if ($paused.State -ne 2 -or $paused.HasError) {
            throw "Pause check failed: $($paused | ConvertTo-Json -Compress)"
        }
        Invoke-Control -Suffix "USB_SK02_MEDIA3_PLAY" -LogName "play"
        Assert-PlayingAdvances -Label "boundary-cycle-$cycle-resume"
        Assert-NoPlaybackFailure -Label "boundary-cycle-$cycle"
        Assert-ExclusiveDrivers
        Write-MetricSample -Phase "boundary-resume-complete"
        Save-PeriodicEvidence -Label "sample-$script:sample-boundary-cycle-$cycle"
    } while ((Get-Date) -lt $deadline)
}

if (-not (Test-Path -LiteralPath $adb)) { throw "ADB not found: $adb" }
if (-not $Serial) {
    $connected = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
    if (@($connected).Count -ne 1) {
        throw "Expected exactly one ADB device; pass -Serial <ip:port>."
    }
    $Serial = (($connected | Select-Object -First 1) -split "\s+")[0]
}

try {
    Write-Event (
        "Starting SK02 Media3 soak serial=$Serial mode=$Mode " +
            "durationMinutes=$DurationMinutes sampleIntervalSeconds=$SampleIntervalSeconds"
    )
    if (-not $SkipBuild) {
        Write-Event "Building side-by-side QA APK"
        # PowerShell/Gradle argument parsing is unreliable for this dotted -P
        # property on this host. Gradle's environment mapping is unambiguous.
        $qaPropertyName = "ORG_GRADLE_PROJECT_mica.qaSideBySide"
        $previousQaProperty = [Environment]::GetEnvironmentVariable($qaPropertyName, "Process")
        try {
            [Environment]::SetEnvironmentVariable($qaPropertyName, "true", "Process")
            & $gradle :app:assembleDebug --no-configuration-cache
            if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
        }
        finally {
            [Environment]::SetEnvironmentVariable($qaPropertyName, $previousQaProperty, "Process")
        }
    }
    if (-not $SkipInstall) {
        $metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
        if ($metadata.applicationId -ne $packageName) {
            throw "Refusing unexpected applicationId: $($metadata.applicationId)"
        }
        Write-Event "Installing side-by-side QA APK"
        Invoke-Adb -Arguments @("install", "-r", $apk) | Out-Host
    }

    Invoke-Adb -Arguments @("shell", "am", "force-stop", $packageName) | Out-Null
    Start-Qa
    Ensure-UsbPermission
    Send-PrototypeAction -Suffix "USB_SK02_MEDIA3_ENABLE"
    $enableLogs = Wait-PrototypeResult -Pattern "media3Prototype=(enabled|enable_rejected)"
    if ($enableLogs -match "enable_rejected") { throw "USB prototype enable was rejected.`n$enableLogs" }
    Invoke-Adb -Arguments @("shell", "am", "force-stop", $packageName) | Out-Null
    Start-Qa

    $testStartedAt = Get-Date
    $deadline = $testStartedAt.AddMinutes($DurationMinutes)
    switch ($Mode) {
        "Continuous" { Run-ContinuousSoak }
        "Lifecycle" { Run-LifecycleSoak }
        "CrashRecovery" { Run-CrashRecoverySoak }
        "ResumeStress" { Run-ResumeStressSoak }
        "RebuildResumeStress" { Run-RebuildResumeStressSoak }
        "BoundaryResumeStress" { Run-BoundaryResumeStressSoak }
    }

    if ($Mode -eq "Lifecycle" -and
        (-not $observedRates.Contains(48000) -or -not $observedRates.Contains(96000))) {
        throw "Expected to observe both 48000 and 96000 Hz; observed: $($observedRates -join ', ')"
    }
    if ($Mode -eq "Continuous" -and $observedRates.Count -eq 0) {
        throw "Continuous mode did not observe an opened USB sample rate."
    }
    if ($Mode -eq "CrashRecovery" -and $observedRates.Count -eq 0) {
        throw "CrashRecovery mode did not observe an opened USB sample rate."
    }
    if ($Mode -eq "ResumeStress" -and $observedRates.Count -eq 0) {
        throw "ResumeStress mode did not observe an opened USB sample rate."
    }
    if ($Mode -eq "RebuildResumeStress" -and
        (-not $observedRates.Contains(48000) -or -not $observedRates.Contains(96000))) {
        throw "Expected rebuild stress to observe both 48000 and 96000 Hz; observed: $($observedRates -join ', ')"
    }
    if ($Mode -eq "BoundaryResumeStress" -and $observedRates.Count -eq 0) {
        throw "BoundaryResumeStress mode did not observe an opened USB sample rate."
    }
    Write-Event "Soak assertions passed; observed sample rates: $($observedRates -join ', ')"
} catch {
    $failure = $_.Exception.ToString()
    Write-Event "FAILED: $failure"
    Save-Evidence -Label "final-failure"
} finally {
    Write-Event "Cleanup: disable prototype, stop QA, reconnect kernel drivers"
    try { Invoke-Control -Suffix "USB_SK02_MEDIA3_REPEAT_OFF" -LogName "repeat_off" } catch {}
    try { Send-PrototypeAction -Suffix "USB_SK02_MEDIA3_DISABLE" } catch {}
    try { Invoke-Adb -Arguments @("shell", "am", "force-stop", $packageName) | Out-Null } catch {}
    try {
        Start-Qa
        $cleanupDriversBound = Recover-KernelDrivers
    } catch {
        Write-Event "Cleanup driver verification failed: $($_.Exception.Message)"
    }
    try { Save-Evidence -Label "final-cleanup" } catch {}
    try { Invoke-Adb -Arguments @("shell", "am", "force-stop", $packageName) | Out-Null } catch {}

    [pscustomobject]@{
        passed = ($null -eq $failure -and $cleanupDriversBound)
        failure = $failure
        serial = $Serial
        startedAt = $startedAt.ToString("o")
        testStartedAt = if ($null -ne $testStartedAt) { $testStartedAt.ToString("o") } else { $null }
        finishedAt = (Get-Date).ToString("o")
        requestedDurationMinutes = $DurationMinutes
        mode = $Mode
        completedCycles = $cycle
        metricSamples = $sample
        observedSampleRates = @($observedRates)
        pssKb = @{ min = $minPssKb; max = $maxPssKb; delta = if ($null -ne $minPssKb) { $maxPssKb - $minPssKb } else { $null } }
        fdCount = @{ min = $minFdCount; max = $maxFdCount; delta = if ($null -ne $minFdCount) { $maxFdCount - $minFdCount } else { $null } }
        maxBatteryTempC = $maxBatteryTempC
        safetyThresholds = @{ minimumBatteryLevel = $MinimumBatteryLevel; maximumBatteryTempC = $MaximumBatteryTempC }
        cleanupDriversBound = $cleanupDriversBound
        artifactDirectory = $artifactDir
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $summaryPath -Encoding utf8
}

if ($failure -or -not $cleanupDriversBound) {
    Write-Host "Artifacts: $artifactDir" -ForegroundColor Yellow
    exit 1
}
Write-Host "PASS. Artifacts: $artifactDir" -ForegroundColor Green
