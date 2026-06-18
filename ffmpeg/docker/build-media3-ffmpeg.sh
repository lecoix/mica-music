#!/usr/bin/env bash
# Build libffmpegJNI.so (arm64-v8a) with dsd_lsbf for Media3 ExoPlayer extension.
# Output: third_party/media3-ffmpeg-decoder/jniLibs/arm64-v8a/libffmpegJNI.so
#         app/libs/media3-ffmpeg-decoder-dsd.aar (optional)
#
# Run inside Linux / Docker (/tmp build). Do NOT compile on Windows bind mount.
set -eu

ROOT="${ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}"
OUT_SO_DIR="$ROOT/third_party/media3-ffmpeg-decoder/src/main/jniLibs/arm64-v8a"
OUT_SO_DIR_LEGACY="$ROOT/third_party/media3-ffmpeg-decoder/jniLibs/arm64-v8a"
OUT_AAR_DIR="$ROOT/app/libs"
HOST_CACHE="$ROOT/ffmpeg/build"
BUILD_DIR="/tmp/mica-media3-ffmpeg-build"
FFMPEG_BRANCH="release/6.0"
NDK_VERSION="r26d"
API=26
TARGET_ABI="arm64-v8a"
ENABLED_DECODERS=(flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd dsd_lsbf dsd_lsbf_planar)

VENDORED_JNI="$ROOT/third_party/media3-ffmpeg-decoder/src/main/jni"
# All compile work stays under /tmp — avoid symlinks on Windows bind mounts.
WORK_MOD="$BUILD_DIR/decoder_ffmpeg/src/main"
WORK_JNI="$WORK_MOD/jni"

mkdir -p "$OUT_SO_DIR" "$OUT_SO_DIR_LEGACY" "$OUT_AAR_DIR" "$BUILD_DIR" "$WORK_JNI"

if [ ! -f "$VENDORED_JNI/CMakeLists.txt" ] || [ ! -f "$VENDORED_JNI/ffmpeg_jni.cc" ]; then
  echo "ERROR: missing vendored JNI sources under $VENDORED_JNI" >&2
  exit 1
fi

cp -f "$VENDORED_JNI/CMakeLists.txt" "$VENDORED_JNI/ffmpeg_jni.cc" "$WORK_JNI/"

cd "$BUILD_DIR"

if ! command -v cmake >/dev/null 2>&1; then
  echo ">> Installing cmake..."
  apt-get update -qq && apt-get install -y --no-install-recommends cmake git
fi

if [ -d "$HOST_CACHE/android-ndk-${NDK_VERSION}" ] && [ ! -d "android-ndk-${NDK_VERSION}" ]; then
  echo ">> Copy cached NDK into container /tmp..."
  cp -a "$HOST_CACHE/android-ndk-${NDK_VERSION}" .
fi

if [ ! -d "android-ndk-${NDK_VERSION}" ]; then
  echo ">> Download Android NDK ${NDK_VERSION}..."
  curl -fL "https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip" -o ndk.zip
  unzip -qo ndk.zip
  rm -f ndk.zip
  mkdir -p "$HOST_CACHE"
  cp -a "android-ndk-${NDK_VERSION}" "$HOST_CACHE/" 2>/dev/null || true
fi

export ANDROID_NDK="$BUILD_DIR/android-ndk-${NDK_VERSION}"
HOST_PLATFORM="linux-x86_64"

if [ ! -d "ffmpeg" ]; then
  echo ">> Clone FFmpeg ${FFMPEG_BRANCH}..."
  git clone --depth 1 --branch "${FFMPEG_BRANCH}" https://github.com/FFmpeg/FFmpeg.git ffmpeg \
    || git clone --depth 1 --branch "n6.0" https://github.com/FFmpeg/FFmpeg.git ffmpeg
fi

rm -f "$WORK_JNI/ffmpeg"
ln -sfn "$BUILD_DIR/ffmpeg" "$WORK_JNI/ffmpeg"

echo ">> Build FFmpeg static libs (${TARGET_ABI}, decoders: ${ENABLED_DECODERS[*]})..."
bash "$ROOT/third_party/media3-ffmpeg-decoder/scripts/build_ffmpeg_arm64.sh" \
  "$WORK_MOD" "$ANDROID_NDK" "$HOST_PLATFORM" "$API" "${ENABLED_DECODERS[@]}"

echo ">> Verify DSF DSD decoders in FFmpeg config..."
CONFIG_MAK="$BUILD_DIR/ffmpeg/ffbuild/config.mak"
if [ -f "$CONFIG_MAK" ]; then
  for decoder in DSD_LSBF DSD_LSBF_PLANAR; do
    grep -q "CONFIG_${decoder}_DECODER=yes" "$CONFIG_MAK" || {
      echo "ERROR: ${decoder} decoder missing from FFmpeg build" >&2
      exit 1
    }
  done
else
  echo "WARN: config.mak not found at $CONFIG_MAK" >&2
fi

echo ">> Build libffmpegJNI.so with CMake..."
CMAKE_BUILD="$BUILD_DIR/cmake-${TARGET_ABI}"
rm -rf "$CMAKE_BUILD"
cmake -S "$WORK_JNI" -B "$CMAKE_BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$TARGET_ABI" \
  -DANDROID_PLATFORM="android-${API}" \
  -DCMAKE_BUILD_TYPE=Release
cmake --build "$CMAKE_BUILD" -j"$(nproc)"

cp -f "$CMAKE_BUILD/libffmpegJNI.so" "$OUT_SO_DIR/libffmpegJNI.so"
cp -f "$OUT_SO_DIR/libffmpegJNI.so" "$OUT_SO_DIR_LEGACY/libffmpegJNI.so"
chmod 644 "$OUT_SO_DIR/libffmpegJNI.so" "$OUT_SO_DIR_LEGACY/libffmpegJNI.so"

echo ">> Verify ffmpegHasDecoder(dsd_lsbf) symbol in libffmpegJNI.so..."
if command -v nm >/dev/null 2>&1; then
  nm -D "$OUT_SO_DIR/libffmpegJNI.so" | grep -q "ffmpegHasDecoder" || {
    echo "WARN: ffmpegHasDecoder symbol not found (nm); continuing." >&2
  }
fi

SIZE="$(du -h "$OUT_SO_DIR/libffmpegJNI.so" | cut -f1)"
echo ">> Done: $OUT_SO_DIR/libffmpegJNI.so ($SIZE)"

if [ -f "$ROOT/gradlew" ] && [ -d "$ROOT/third_party/media3-ffmpeg-decoder" ]; then
  echo ">> Assemble local media3-ffmpeg-decoder-dsd AAR..."
  cd "$ROOT"
  if ./gradlew :media3-ffmpeg-decoder-dsd:assembleRelease; then
    AAR_SRC="$ROOT/third_party/media3-ffmpeg-decoder/build/outputs/aar/media3-ffmpeg-decoder-dsd-release.aar"
    if [ -f "$AAR_SRC" ]; then
      cp -f "$AAR_SRC" "$OUT_AAR_DIR/media3-ffmpeg-decoder-dsd.aar"
      mkdir -p "$ROOT/app/build/generated/media3-ffmpeg"
      cp -f "$AAR_SRC" "$ROOT/app/build/generated/media3-ffmpeg/media3-ffmpeg-decoder-dsd.aar"
      echo ">> AAR: $OUT_AAR_DIR/media3-ffmpeg-decoder-dsd.aar"
    fi
  else
    echo ">> AAR assemble skipped/failed; libffmpegJNI.so is enough for Gradle module." >&2
  fi
fi

echo ">> Rebuild and reinstall APK after this step."
