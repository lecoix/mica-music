#!/usr/bin/env bash
# Build the ARMv7 FFmpeg static libraries used by libffmpegJNI.so.
set -eu

FFMPEG_MODULE_PATH="$1"
NDK_PATH="$2"
HOST_PLATFORM="$3"
ANDROID_API="$4"
ENABLED_DECODERS=("${@:5}")

JOBS="$(nproc 2>/dev/null || echo 4)"
COMMON_OPTIONS="
  --target-os=android
  --enable-static
  --disable-shared
  --disable-doc
  --disable-programs
  --disable-everything
  --disable-avdevice
  --disable-avformat
  --disable-swscale
  --disable-postproc
  --disable-avfilter
  --disable-symver
  --enable-swresample
  --extra-ldexeflags=-pie
  --disable-v4l2-m2m
  --disable-vulkan
"

for decoder in "${ENABLED_DECODERS[@]}"; do
  COMMON_OPTIONS="${COMMON_OPTIONS} --enable-decoder=${decoder}"
done

TOOLCHAIN_PREFIX="${NDK_PATH}/toolchains/llvm/prebuilt/${HOST_PLATFORM}/bin"
ARMV7_PREFIX="${TOOLCHAIN_PREFIX}/armv7a-linux-androideabi${ANDROID_API}-"
if [[ ! -e "${ARMV7_PREFIX}clang" ]]; then
  echo "ERROR: ARMv7 clang not found: ${ARMV7_PREFIX}clang" >&2
  exit 1
fi

cd "${FFMPEG_MODULE_PATH}/jni/ffmpeg"
./configure \
  --libdir=android-libs/armeabi-v7a \
  --arch=arm \
  --cpu=armv7-a \
  --cross-prefix="${ARMV7_PREFIX}" \
  --nm="${TOOLCHAIN_PREFIX}/llvm-nm" \
  --ar="${TOOLCHAIN_PREFIX}/llvm-ar" \
  --ranlib="${TOOLCHAIN_PREFIX}/llvm-ranlib" \
  --strip="${TOOLCHAIN_PREFIX}/llvm-strip" \
  --extra-cflags="-march=armv7-a -mfloat-abi=softfp" \
  --extra-ldflags="-Wl,--fix-cortex-a8" \
  ${COMMON_OPTIONS}
make -j"$JOBS"
make install-libs
