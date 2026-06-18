# Build Media3 FFmpeg extension with dsd_lsbf (arm64-v8a) via Docker.
# Requires Docker Desktop (Linux containers).
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$DockerDir = Join-Path $Root "ffmpeg\docker"
$BuildSh = Join-Path $Root "ffmpeg\docker\build-media3-ffmpeg.sh"
$Arm64Sh = Join-Path $Root "third_party\media3-ffmpeg-decoder\scripts\build_ffmpeg_arm64.sh"

foreach ($path in @($BuildSh, $Arm64Sh)) {
    $raw = [System.IO.File]::ReadAllText($path)
    $lf = $raw -replace "`r`n", "`n" -replace "`r", "`n"
    [System.IO.File]::WriteAllText($path, $lf, [System.Text.UTF8Encoding]::new($false))
}

Write-Host ">> Building Media3 libffmpegJNI (dsd_lsbf) for arm64-v8a via Docker..." -ForegroundColor Cyan
docker build -t mica-ffmpeg-arm64 $DockerDir
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

docker run --rm `
    -v "${Root}:/work/mica-android" `
    -w /work/mica-android `
    -e ROOT=/work/mica-android `
    mica-ffmpeg-arm64 `
    bash -lc "apt-get update -qq && apt-get install -y --no-install-recommends cmake git && sed -i 's/\r$//' ffmpeg/docker/build-media3-ffmpeg.sh third_party/media3-ffmpeg-decoder/scripts/build_ffmpeg_arm64.sh && bash ffmpeg/docker/build-media3-ffmpeg.sh"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$soCandidates = @(
    Join-Path $Root "third_party\media3-ffmpeg-decoder\src\main\jniLibs\arm64-v8a\libffmpegJNI.so"
    Join-Path $Root "third_party\media3-ffmpeg-decoder\jniLibs\arm64-v8a\libffmpegJNI.so"
)
$so = $soCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
$aar = Join-Path $Root "app\libs\media3-ffmpeg-decoder-dsd.aar"
if ($so) {
    $sizeMb = [math]::Round((Get-Item $so).Length / 1MB, 2)
    Write-Host ">> OK: $so (~${sizeMb} MB)" -ForegroundColor Green
} else {
    Write-Host ">> libffmpegJNI.so not found. Check Docker output above." -ForegroundColor Red
    exit 1
}
if (Test-Path $aar) {
    Write-Host ">> OK: $aar" -ForegroundColor Green
} else {
    Write-Host ">> AAR not built; Gradle will use :media3-ffmpeg-decoder-dsd module with jniLibs." -ForegroundColor Yellow
}
