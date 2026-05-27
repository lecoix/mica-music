# Run from mica-android: .\scripts\build-ffmpeg-arm64.ps1
# Requires Docker Desktop (Linux containers)
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$DockerDir = Join-Path $Root "ffmpeg\docker"
$BuildSh = Join-Path $Root "ffmpeg\docker\build.sh"

# Windows CRLF breaks bash in Linux container (set: pipefail error)
$raw = [System.IO.File]::ReadAllText($BuildSh)
$lf = $raw -replace "`r`n", "`n" -replace "`r", "`n"
[System.IO.File]::WriteAllText($BuildSh, $lf, [System.Text.UTF8Encoding]::new($false))

Write-Host ">> Building custom FFmpeg for arm64-v8a via Docker (PCM + DSD)..." -ForegroundColor Cyan
docker build -t mica-ffmpeg-arm64 $DockerDir
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

docker run --rm `
    -v "${Root}:/work/mica-android" `
    -w /work/mica-android `
    -e ROOT=/work/mica-android `
    mica-ffmpeg-arm64 `
    bash -lc "sed -i 's/\r$//' ffmpeg/docker/build.sh && bash ffmpeg/docker/build.sh"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$bin = Join-Path $Root "app\src\main\assets\ffmpeg\arm64-v8a\ffmpeg"
if (Test-Path $bin) {
    $sizeMb = [math]::Round((Get-Item $bin).Length / 1MB, 2)
    Write-Host ">> OK: $bin (~${sizeMb} MB)" -ForegroundColor Green
    Write-Host ">> DSD support is checked inside Docker: dsf + iff(dff/dsdiff) demuxers and DSD decoders must be enabled." -ForegroundColor Green
} else {
    Write-Host ">> ffmpeg binary not found. Check Docker output above." -ForegroundColor Red
    exit 1
}
