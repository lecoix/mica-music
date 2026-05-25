Place the arm64 FFmpeg binary here as:

  ffmpeg

Build it on the project root (requires Docker):

  .\scripts\build-ffmpeg-arm64.ps1

Gradle copies it to jniLibs as libmica_ffmpeg.so. Without this file,
all software-decoded playback will fail.

The build script enables ALAC/AAC/FLAC/MP3/Opus/Vorbis/APE/WavPack/MPC
and common containers (m4a, flac, ogg, wav, matroska, ...).

Playback requires raw PCM muxers (configure: pcm_s16le,pcm_s24le,pcm_s32le; CLI: -f s16le/s24le/s32le).
Rebuild with scripts\build-ffmpeg-arm64.ps1 after build.sh changes.
