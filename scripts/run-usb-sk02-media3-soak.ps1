param(
    [string]$Serial,
    [ValidateSet("SharedPcmBaseline", "Continuous", "Lifecycle", "CrashRecovery", "OemBackground", "ResumeStress", "RebuildResumeStress", "BoundaryResumeStress")]
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
    [ValidateRange(0, 1000)]
    [int]$ReclaimEverySamples = 30,
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
. (Join-Path $PSScriptRoot "usb-sk02-media3-soak-contract.ps1")
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
$initialWakefulness = $null

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
    $power = if ($Mode -eq "OemBackground") {
        Get-PowerSnapshot
    } else {
        [pscustomobject]@{ Wakefulness = "not-sampled"; PlaybackWakeLockHeld = $false }
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
        wakefulness = $power.Wakefulness
        playbackWakeLockHeld = $power.PlaybackWakeLockHeld
    } | Export-Csv -LiteralPath $metricsPath -NoTypeInformation -Append -Encoding utf8

    Write-Event (
        "Metric sample=$script:sample phase=$Phase positionMs=$($state.PositionMs) " +
            "pssKb=$pssKb fdCount=$fdCount cpu=$cpuPercent% " +
            "battery=$batteryLevel% tempC=$batteryTempC " +
            "wakefulness=$($power.Wakefulness) playbackWakeLock=$($power.PlaybackWakeLockHeld)"
    )
}

function Get-PowerSnapshot {
    $snapshot = $null
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $lines = @(Invoke-Adb -Arguments @("shell", "dumpsys", "power") -AllowFailure)
        $snapshot = ConvertFrom-PowerDump -Lines $lines
        if ($null -ne $snapshot) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($null -eq $snapshot) { throw "Could not parse device power snapshot after 3 attempts." }
    return $snapshot
}

function Enter-OemBackgroundWindow {
    Invoke-Adb -Arguments @("shell", "input", "keyevent", "3") | Out-Null
    Start-Sleep -Seconds 1
    $power = Get-PowerSnapshot
    if ($power.Wakefulness -eq "Awake") {
        Invoke-Adb -Arguments @("shell", "input", "keyevent", "26") | Out-Null
        Start-Sleep -Seconds 2
        $power = Get-PowerSnapshot
    }
    if ($power.Wakefulness -eq "Awake") {
        throw "Unable to enter screen-off background window."
    }
    Write-Event "OEM background window entered wakefulness=$($power.Wakefulness)"
}

function Restore-InitialScreenState {
    if ($null -eq $script:initialWakefulness) { return }
    $current = (Get-PowerSnapshot).Wakefulness
    $initialAwake = $script:initialWakefulness -eq "Awake"
    $currentAwake = $current -eq "Awake"
    if ($initialAwake -ne $currentAwake) {
        Invoke-Adb -Arguments @("shell", "input", "keyevent", "26") -AllowFailure | Out-Null
        Start-Sleep -Seconds 1
    }
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
    $limit = (Get-Date).AddSeconds(30)
    do {
        Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
        Send-PrototypeAction -Suffix $Suffix -Extras $Extras
        $attemptLimit = (Get-Date).AddSeconds(5)
        do {
            Start-Sleep -Milliseconds 500
            $logs = Get-PrototypeLog
            $outcome = Get-ControlOutcome -Logs $logs -LogName $LogName
            if ($outcome -eq "Succeeded") {
                Add-Content -LiteralPath $eventLog -Value $logs -Encoding utf8
                return
            }
            if ($outcome -eq "Failed") {
                Add-Content -LiteralPath $eventLog -Value $logs -Encoding utf8
                throw "Media3 control failed: $LogName"
            }
            if ($outcome -eq "Retry") { break }
        } while ((Get-Date) -lt $attemptLimit)
        Start-Qa
    } while ((Get-Date) -lt $limit)
    throw "Timed out waiting for Media3 control readiness: $LogName"
}

function Reset-PersistedUsbGate {
    $limit = (Get-Date).AddSeconds(60)
    $startedQaFallback = $false
    do {
        Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
        Send-PrototypeAction -Suffix "USB_SK02_MEDIA3_DISABLE" -Extras @("--ez", "gateOnly", "true")
        $attemptLimit = (Get-Date).AddSeconds(5)
        do {
            Start-Sleep -Milliseconds 500
            $logs = Get-PrototypeLog
            if ($logs -match "media3Prototype=disabled") { return }
            if ($logs -match "media3Prototype=rebuild_failed") {
                throw "Failed to reset persisted USB gate.`n$logs"
            }
        } while ((Get-Date) -lt $attemptLimit)
        if (-not $startedQaFallback) {
            Write-Event "Gate receiver was inactive; launch QA once, then retry the gate-only reset"
            Start-Qa
            $startedQaFallback = $true
        }
    } while ((Get-Date) -lt $limit)
    throw "Timed out resetting persisted USB gate before service startup."
}

function Start-Qa {
    Invoke-Adb -Arguments @("shell", "am", "start", "-n", $activity) | Out-Null
    Start-Sleep -Seconds 4
}

function Start-PlaybackThroughQaSession {
    # A global media-key event can be consumed by another app's active MediaSession. Address the
    # QA receiver explicitly so the SharedPcm baseline always starts the session under test.
    Invoke-Control -Suffix "USB_SK02_MEDIA3_PLAY" -LogName "play"
    Start-Sleep -Seconds 2
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
    $permissionApproved = $false
    do {
        Start-Sleep -Seconds 1
        $logs = Get-PrototypeLog
        if ($logs -match "probe=complete") { return }
        if ($logs -match "permission_denied|target_not_found|open_failed") {
            throw "USB permission/probe failed.`n$logs"
        }
        if (-not $permissionApproved -and $logs -match "permission=requested") {
            $permissionApproved = Click-PermissionButtonIfPresent
            if ($permissionApproved) {
                $limit = (Get-Date).AddSeconds(15)
            }
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
    $readyLimit = (Get-Date).AddSeconds(10)
    do {
        $state = Get-QaPlaybackState
        $readiness = Get-PlaybackReadiness -State $state -AllowTransientBuffering
        if ($readiness -eq "Ready") { break }
        if ($readiness -eq "Failed") {
            throw "$Label entered a non-retryable playback state: $($state | ConvertTo-Json -Compress)"
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $readyLimit)
    if ($readiness -ne "Ready") {
        throw "$Label did not reach clean PLAYING state: $($state | ConvertTo-Json -Compress)"
    }
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $state = Get-QaPlaybackState
        if ((Get-PlaybackReadiness -State $state) -ne "Ready") {
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

function Assert-KernelDriversBound {
    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    Send-PrototypeAction -Suffix "USB_SK02_NATIVE_FD_PROBE"
    $logs = Wait-PrototypeResult -Pattern "nativeFdProbe=complete"
    if ($logs -notmatch "control=\{driver=snd-usb-audio" -or
        $logs -notmatch "streaming=\{driver=snd-usb-audio") {
        throw "SK02 kernel drivers were not bound in SharedPcm mode.`n$logs"
    }
}

function Assert-KernelDriversBoundWithoutUsbPermission {
    $links = (Invoke-Adb -Arguments @(
        "shell", "ls", "-l", "/sys/bus/usb/drivers/snd-usb-audio"
    )) -join "`n"
    $interfacesByDevice = @{}
    foreach ($match in [regex]::Matches($links, '(?<device>\d+-[\d.]+):1\.(?<interface>[12])')) {
        $device = $match.Groups["device"].Value
        if (-not $interfacesByDevice.ContainsKey($device)) {
            $interfacesByDevice[$device] = [System.Collections.Generic.HashSet[int]]::new()
        }
        [void]$interfacesByDevice[$device].Add([int]$match.Groups["interface"].Value)
    }
    $bound = @($interfacesByDevice.Values | Where-Object {
        $_.Contains(1) -and $_.Contains(2)
    }).Count -gt 0
    if (-not $bound) {
        throw "Could not find one USB audio device with kernel-bound control and streaming interfaces.`n$links"
    }
    return $true
}

function Set-InPlaceUsbPrototype {
    param([bool]$Enabled)
    $suffix = if ($Enabled) { "USB_SK02_MEDIA3_ENABLE" } else { "USB_SK02_MEDIA3_DISABLE" }
    $state = if ($Enabled) { "enabled" } else { "disabled" }
    $target = if ($Enabled) { "UsbDirectPcm" } else { "SharedPcm" }
    $beforeDiagnostics = Get-Diagnostics
    $beforeCount = ([regex]::Matches($beforeDiagnostics, "UsbOutputRebuild: result=")).Count
    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    Send-PrototypeAction -Suffix $suffix
    $limit = (Get-Date).AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 500
        $prototypeLogs = Get-PrototypeLog
        if ($prototypeLogs -match "media3Prototype=(rebuild_failed|enable_rejected)") {
            throw "In-place output rebuild was rejected for target=$state.`n$prototypeLogs"
        }
        $diagnostics = Get-Diagnostics
        $matches = [regex]::Matches(
            $diagnostics,
            "UsbOutputRebuild: result=(?<result>\S+) generation=(?<generation>\d+) " +
                "from=(?<from>\S+) target=(?<target>\S+)"
        )
        if ($matches.Count -gt $beforeCount) {
            $result = $matches[$matches.Count - 1]
            $resultLine = $result.Value
            Add-Content -LiteralPath $eventLog -Value $resultLine -Encoding utf8
            if ($result.Groups["result"].Value -ne "Published" -or
                $result.Groups["target"].Value -ne $target) {
                throw "In-place output rebuild failed for target=$state. $resultLine"
            }
            return
        }
    } while ((Get-Date) -lt $limit)
    throw "Timed out waiting for output rebuild diagnostics target=$target"
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

function Restart-ExclusiveAfterSimulatedReclaim {
    $oldPid = ((Invoke-Adb -Arguments @("shell", "pidof", $packageName)) -join "").Trim()
    if (-not $oldPid) { throw "Cannot simulate reclaim because QA process is absent." }
    Write-Event "Simulated process death: ActivityManager crash pid=$oldPid without force-stop"
    # On this OEM build, run-as uses a SELinux context that cannot resolve even the same UID's
    # app PID (`kill: unknown pid`). ActivityManager owns the cross-context kill and, unlike
    # force-stop, still permits the explicit cold restart that exercises durable recovery.
    Invoke-Adb -Arguments @("shell", "am", "crash", $packageName) | Out-Null
    Start-Sleep -Seconds 3
    $survivingPid = ((Invoke-Adb -Arguments @(
        "shell", "pidof", $packageName
    ) -AllowFailure) -join "").Trim()
    if (($survivingPid -split '\s+') -contains ($oldPid -split '\s+')[0]) {
        throw "Simulated process death did not terminate QA: old=$oldPid surviving=$survivingPid"
    }
    if ($survivingPid) {
        Write-Event "System restarted QA after process death: old=$oldPid replacement=$survivingPid"
    }
    Start-Qa
    Invoke-Control -Suffix "USB_SK02_MEDIA3_PLAY" -LogName "play"
    Start-Sleep -Seconds $HoldSeconds
    $newPid = ((Invoke-Adb -Arguments @("shell", "pidof", $packageName)) -join "").Trim()
    if (-not $newPid -or $newPid -eq $oldPid) {
        throw "Simulated reclaim did not produce a replacement QA process: old=$oldPid new=$newPid"
    }
    Assert-PlayingAdvances -Label "post-simulated-reclaim"
    Assert-NoPlaybackFailure -Label "post-simulated-reclaim"
    Assert-ExclusiveDrivers
    Enter-OemBackgroundWindow
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

function Run-SharedPcmBaseline {
    Write-Event "SharedPcm baseline: repeat index $FirstMediaIndex without USB ownership or lifecycle mutations"
    Invoke-Control -Suffix "USB_SK02_MEDIA3_REPEAT_ONE" -LogName "repeat_one"
    Invoke-Control -Suffix "USB_SK02_MEDIA3_SELECT_INDEX" -LogName "select_index" `
        -Extras @("--ei", "mediaIndex", "$FirstMediaIndex")
    Start-Sleep -Seconds $HoldSeconds
    Assert-PlayingAdvances -Label "shared-baseline-start"
    Assert-NoPlaybackFailure -Label "shared-baseline-start"
    Assert-KernelDriversBoundWithoutUsbPermission | Out-Null
    Write-MetricSample -Phase "shared-steady"
    Save-PeriodicEvidence -Label "sample-$script:sample"

    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds $SampleIntervalSeconds
        Assert-PlayingAdvances -Label "shared-baseline-sample-$($script:sample + 1)"
        Assert-NoPlaybackFailure -Label "shared-baseline-sample-$($script:sample + 1)"
        if (($script:sample % 5) -eq 0) {
            Assert-KernelDriversBoundWithoutUsbPermission | Out-Null
        }
        Write-MetricSample -Phase "shared-steady"
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

function Run-OemBackgroundSoak {
    Write-Event "OEM background mode: screen off, active playback wake lock, periodic simulated process reclaim"
    Invoke-Control -Suffix "USB_SK02_MEDIA3_REPEAT_ONE" -LogName "repeat_one"
    Invoke-Control -Suffix "USB_SK02_MEDIA3_SELECT_INDEX" -LogName "select_index" `
        -Extras @("--ei", "mediaIndex", "$FirstMediaIndex")
    Start-Sleep -Seconds $HoldSeconds
    Assert-PlayingAdvances -Label "oem-background-start"
    Assert-NoPlaybackFailure -Label "oem-background-start"
    Assert-ExclusiveDrivers
    $script:initialWakefulness = (Get-PowerSnapshot).Wakefulness
    Enter-OemBackgroundWindow

    do {
        Start-Sleep -Seconds $SampleIntervalSeconds
        $script:cycle++
        Assert-PlayingAdvances -Label "oem-background-sample-$($script:sample + 1)"
        Assert-NoPlaybackFailure -Label "oem-background-sample-$($script:sample + 1)"
        Assert-ExclusiveDrivers
        $power = Get-PowerSnapshot
        if (-not $power.PlaybackWakeLockHeld) {
            throw "ExoPlayer playback wake lock is absent during background playback."
        }
        Write-MetricSample -Phase "background-screen-off"
        Save-PeriodicEvidence -Label "sample-$script:sample-oem-background"
        if ($ReclaimEverySamples -gt 0 -and
            ($script:sample % $ReclaimEverySamples) -eq 0 -and
            (Get-Date) -lt $deadline) {
            Restart-ExclusiveAfterSimulatedReclaim
        }
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

    # A previous QA run may have persisted the debug USB gate. Reset it before explicitly
    # starting the service so a fresh install never tries to open USB ahead of permission.
    Reset-PersistedUsbGate
    Invoke-Adb -Arguments @("shell", "am", "force-stop", $packageName) | Out-Null
    Start-Qa
    if ((Get-SoakOutputPath -Mode $Mode) -eq "SharedPcm") {
        Write-Event "Baseline setup: keep production SharedPcm and kernel USB audio drivers"
        Set-InPlaceUsbPrototype -Enabled $false
        Invoke-Control -Suffix "USB_SK02_MEDIA3_PLAY" -LogName "play"
        Assert-PlayingAdvances -Label "baseline-shared-before"
        Assert-KernelDriversBoundWithoutUsbPermission | Out-Null
    } else {
        Ensure-UsbPermission

        Write-Event "Full-mode smoke: establish SharedPcm baseline"
        Set-InPlaceUsbPrototype -Enabled $false
        Start-PlaybackThroughQaSession
        Assert-PlayingAdvances -Label "full-mode-shared-before"
        Assert-KernelDriversBound

        Write-Event "Full-mode smoke: SharedPcm -> UsbDirectPcm"
        Set-InPlaceUsbPrototype -Enabled $true
        Assert-PlayingAdvances -Label "full-mode-usb-first"
        Assert-NoPlaybackFailure -Label "full-mode-usb-first"
        Assert-ExclusiveDrivers

        if ($Mode -ne "OemBackground") {
            Write-Event "Full-mode smoke: UsbDirectPcm -> SharedPcm"
            Set-InPlaceUsbPrototype -Enabled $false
            Assert-PlayingAdvances -Label "full-mode-shared-after"
            Assert-NoPlaybackFailure -Label "full-mode-shared-after"
            Assert-KernelDriversBound

            Write-Event "Full-mode smoke: SharedPcm -> UsbDirectPcm for soak"
            Set-InPlaceUsbPrototype -Enabled $true
            Assert-PlayingAdvances -Label "full-mode-usb-final"
            Assert-NoPlaybackFailure -Label "full-mode-usb-final"
            Assert-ExclusiveDrivers
        } else {
            Write-Event "OEM background setup: keep first UsbDirectPcm cutover active for soak"
        }
    }

    $testStartedAt = Get-Date
    $deadline = $testStartedAt.AddMinutes($DurationMinutes)
    switch ($Mode) {
        "SharedPcmBaseline" { Run-SharedPcmBaseline }
        "Continuous" { Run-ContinuousSoak }
        "Lifecycle" { Run-LifecycleSoak }
        "CrashRecovery" { Run-CrashRecoverySoak }
        "OemBackground" { Run-OemBackgroundSoak }
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
    if ($Mode -eq "OemBackground" -and $observedRates.Count -eq 0) {
        throw "OemBackground mode did not observe an opened USB sample rate."
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
    Save-Evidence -Label "final-success"
} catch {
    $failure = $_.Exception.ToString()
    Write-Event "FAILED: $failure"
    Save-Evidence -Label "final-failure"
} finally {
    try { Restore-InitialScreenState } catch {
        Write-Event "Cleanup screen-state restoration failed: $($_.Exception.Message)"
    }
    Write-Event "Cleanup: disable prototype, stop QA, reconnect kernel drivers"
    try { Invoke-Control -Suffix "USB_SK02_MEDIA3_REPEAT_OFF" -LogName "repeat_off" } catch {}
    try { Send-PrototypeAction -Suffix "USB_SK02_MEDIA3_DISABLE" } catch {}
    try { Invoke-Adb -Arguments @("shell", "am", "force-stop", $packageName) | Out-Null } catch {}
    try {
        if ((Get-SoakOutputPath -Mode $Mode) -eq "SharedPcm") {
            $cleanupDriversBound = Assert-KernelDriversBoundWithoutUsbPermission
        } else {
            Start-Qa
            $cleanupDriversBound = Recover-KernelDrivers
        }
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
        outputPath = Get-SoakOutputPath -Mode $Mode
        completedCycles = $cycle
        metricSamples = $sample
        observedSampleRates = @($observedRates)
        pssKb = @{ min = $minPssKb; max = $maxPssKb; delta = if ($null -ne $minPssKb) { $maxPssKb - $minPssKb } else { $null } }
        fdCount = @{ min = $minFdCount; max = $maxFdCount; delta = if ($null -ne $minFdCount) { $maxFdCount - $minFdCount } else { $null } }
        maxBatteryTempC = $maxBatteryTempC
        safetyThresholds = @{ minimumBatteryLevel = $MinimumBatteryLevel; maximumBatteryTempC = $MaximumBatteryTempC }
        reclaimEverySamples = $ReclaimEverySamples
        cleanupDriversBound = $cleanupDriversBound
        artifactDirectory = $artifactDir
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $summaryPath -Encoding utf8
}

if ($failure -or -not $cleanupDriversBound) {
    Write-Host "Artifacts: $artifactDir" -ForegroundColor Yellow
    exit 1
}
Write-Host "PASS. Artifacts: $artifactDir" -ForegroundColor Green
