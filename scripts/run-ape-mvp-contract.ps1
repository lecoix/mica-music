$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sampleDirectory = Join-Path $root ".scratch\ape-playback-prototype"
$samplePath = Join-Path $sampleDirectory "sh3.ape"
$referenceSamplePath = Join-Path $sampleDirectory "reference\sh3.ape"
$assetPath = Join-Path $root "app\src\androidTest\assets\media\contract-ape-mvp.ape"
$sampleUrl = "https://samples.ffmpeg.org/monkeyaudio/sh3.ape"
$expectedSha256 = "9B8E89B81A87001648D58DC9EF440A5B9B8C214A4DF07BD22776DA1FF6E32004"
$testName = "com.mica.music.media.RealAudioDecodeContractTest#externalApeMvpUsesFfmpegReachesAudioSinkAndSeeks"

New-Item -ItemType Directory -Force -Path $sampleDirectory | Out-Null
if (-not (Test-Path -LiteralPath $samplePath)) {
    if (Test-Path -LiteralPath $referenceSamplePath) {
        Copy-Item -LiteralPath $referenceSamplePath -Destination $samplePath
    } else {
        Write-Host ">> Download temporary FFmpeg APE test fixture..." -ForegroundColor Cyan
        curl.exe -fL $sampleUrl -o $samplePath
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
}

$actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $samplePath).Hash
if ($actualSha256 -ne $expectedSha256) {
    throw "Unexpected APE fixture SHA-256: $actualSha256"
}

try {
    Copy-Item -LiteralPath $samplePath -Destination $assetPath -Force
    Write-Host ">> Run real APE decode + AudioTrack + seek contract..." -ForegroundColor Cyan
    & (Join-Path $root "gradlew.bat") `
        :app:connectedDebugAndroidTest `
        --no-configuration-cache `
        "-Pkotlin.compiler.execution.strategy=in-process" `
        "-Pandroid.testInstrumentationRunnerArguments.class=$testName"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    if (Test-Path -LiteralPath $assetPath) {
        Remove-Item -LiteralPath $assetPath -Force
    }
}
