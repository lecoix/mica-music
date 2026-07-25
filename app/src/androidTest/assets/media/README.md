# Instrumentation media fixtures

`contract-silence-alac.m4a` is one second of generated stereo silence. It contains no source
recording and exists only to verify the real Media3 FFmpeg ALAC path.

Regenerate with FFmpeg:

```powershell
ffmpeg -f lavfi -i "anullsrc=r=44100:cl=stereo" -t 1 -c:a alac -movflags +faststart contract-silence-alac.m4a
```

`contract-silence-flac-96k-24bit.flac` is two seconds of generated stereo silence used to cover
the production hi-res FLAC renderer and float-sink path. It contains no source recording.

Regenerate with Python SoundFile:

```python
import numpy as np
import soundfile as sf

sf.write("contract-silence-flac-96k-24bit.flac", np.zeros((96_000 * 2, 2)), 96_000, subtype="PCM_24")
```

The DSF contract fixture is generated at runtime by `RealAudioDecodeContractTest` because its
minimal container is straightforward and avoids another binary asset.
