# Instrumentation media fixtures

`contract-silence-alac.m4a` is one second of generated stereo silence. It contains no source
recording and exists only to verify the real Media3 FFmpeg ALAC path.

Regenerate with FFmpeg:

```powershell
ffmpeg -f lavfi -i "anullsrc=r=44100:cl=stereo" -t 1 -c:a alac -movflags +faststart contract-silence-alac.m4a
```

The DSF contract fixture is generated at runtime by `RealAudioDecodeContractTest` because its
minimal container is straightforward and avoids another binary asset.
