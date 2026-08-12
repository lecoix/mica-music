$contract = Join-Path $PSScriptRoot "..\usb-sk02-media3-soak-contract.ps1"
. (Resolve-Path $contract)

Describe "SK02 Media3 soak runner contracts" {
    It "keeps baseline runs on SharedPcm and all recovery modes on UsbDirectPcm" {
        Get-SoakOutputPath "SharedPcmBaseline" | Should Be "SharedPcm"
        Get-SoakOutputPath "Lifecycle" | Should Be "UsbDirectPcm"
    }

    It "accepts clean PLAYING as ready" {
        Get-PlaybackReadiness ([pscustomobject]@{ State = 3; HasError = $false }) | Should Be "Ready"
    }

    It "retries transient BUFFERING instead of failing the playback check" {
        Get-PlaybackReadiness ([pscustomobject]@{ State = 6; HasError = $false }) -AllowTransientBuffering |
            Should Be "Retry"
    }

    It "fails a playback state carrying a Media3 error" {
        Get-PlaybackReadiness ([pscustomobject]@{ State = 6; HasError = $true }) -AllowTransientBuffering |
            Should Be "Failed"
    }

    It "treats service_not_active as retryable control startup" {
        Get-ControlOutcome "media3Control=play complete=false error=IllegalStateException:service_not_active" "play" |
            Should Be "Retry"
    }

    It "keeps other control failures terminal" {
        Get-ControlOutcome "media3Control=play complete=false error=IllegalArgumentException:bad_index" "play" |
            Should Be "Failed"
    }

    It "does not treat an unrelated control log as complete" {
        Get-ControlOutcome "media3Control=pause complete=true" "play" | Should Be "Pending"
    }

    It "flattens wrapped adb output before parsing OEM power state" {
        $wrapped = ,@(
            "POWER MANAGER (dumpsys power)",
            "  mWakefulness=Dozing",
            "Wake Locks: size=1",
            "  PARTIAL_WAKE_LOCK 'ExoPlayer:WakeLockManager'"
        )

        $snapshot = ConvertFrom-PowerDump -Lines $wrapped

        $snapshot.Wakefulness | Should Be "Dozing"
        $snapshot.PlaybackWakeLockHeld | Should Be $true
    }

    It "rejects a power dump without both canonical state sections" {
        ConvertFrom-PowerDump -Lines @("mWakefulness=Awake") | Should Be $null
        ConvertFrom-PowerDump -Lines @("Wake Locks: size=0") | Should Be $null
    }

    It "uses ActivityManager for simulated process death instead of an ignored run-as kill" {
        $runner = Get-Content -Raw (Join-Path $PSScriptRoot "..\run-usb-sk02-media3-soak.ps1")

        $runner | Should Match '"shell", "am", "crash", \$packageName'
        $runner | Should Not Match '"run-as", \$packageName, "kill"'
        $runner | Should Match '\$survivingPid -split.*-contains.*\$oldPid -split'
    }
}
