#!/usr/bin/env bash
# arm64-v8a-only variant of androidx/media decoder_ffmpeg/jni/build_ffmpeg.sh
set -eu

FFMPEG_MODULE_PATH="$1"
NDK_PATH="$2"
HOST_PLATFORM="$3"
ANDROID_ABI="$4"
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
ANDROID_ABI_64BIT="$ANDROID_ABI"
if [[ "$ANDROID_ABI_64BIT" -lt 21 ]]; then
  ANDROID_ABI_64BIT=21
fi

cd "${FFMPEG_MODULE_PATH}/jni/ffmpeg"
./configure \
  --libdir=android-libs/arm64-v8a \
  --arch=aarch64 \
  --cpu=armv8-a \
  --cross-prefix="${TOOLCHAIN_PREFIX}/aarch64-linux-android${ANDROID_ABI_64BIT}-" \
  --nm="${TOOLCHAIN_PREFIX}/llvm-nm" \
  --ar="${TOOLCHAIN_PREFIX}/llvm-ar" \
  --ranlib="${TOOLCHAIN_PREFIX}/llvm-ranlib" \
  --strip="${TOOLCHAIN_PREFIX}/llvm-strip" \
  ${COMMON_OPTIONS}
make -j"$JOBS"
make install-libs
