#!/usr/bin/env bash
# Output: app/src/main/assets/ffmpeg/arm64-v8a/ffmpeg
# Build inside /tmp (native Linux FS). Do NOT compile on Windows bind mount.
set -eu

ROOT="${ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}"
OUT_DIR="$ROOT/app/src/main/assets/ffmpeg/arm64-v8a"
HOST_CACHE="$ROOT/ffmpeg/build"
BUILD_DIR="/tmp/mica-ffmpeg-build"
FFMPEG_VERSION="6.1.1"
NDK_VERSION="r26d"
API=26
TARGET=aarch64-linux-android

mkdir -p "$OUT_DIR" "$BUILD_DIR"
cd "$BUILD_DIR"

if [ -d "$HOST_CACHE/android-ndk-${NDK_VERSION}" ] && [ ! -d "android-ndk-${NDK_VERSION}" ]; then
  echo ">> Copy cached NDK into container /tmp..."
  cp -a "$HOST_CACHE/android-ndk-${NDK_VERSION}" .
fi
if [ -f "$HOST_CACHE/ffmpeg-${FFMPEG_VERSION}.tar.xz" ] && [ ! -d "ffmpeg-${FFMPEG_VERSION}" ]; then
  echo ">> Copy cached FFmpeg tarball..."
  cp -a "$HOST_CACHE/ffmpeg-${FFMPEG_VERSION}.tar.xz" .
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
TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"

export PATH="$TOOLCHAIN/bin:$PATH"
export PKG_CONFIG=/bin/false
export AR=llvm-ar
export CC="${TARGET}${API}-clang"
export CXX="${TARGET}${API}-clang++"
export LD="${TARGET}${API}-clang"
export AS="${CC}"
export RANLIB=llvm-ranlib
export STRIP=llvm-strip

export CFLAGS="--sysroot=$SYSROOT -O2 -fPIC -DANDROID"
export CXXFLAGS="--sysroot=$SYSROOT -O2 -fPIC -DANDROID"
# 16KB 页设备（Android 15+ / 部分模拟器）需要 ELF 对齐
export LDFLAGS="--sysroot=$SYSROOT -Wl,--gc-sections -Wl,-z,max-page-size=16384"

echo ">> Test compiler..."
echo 'int main(){return 0;}' | "$CC" -x c - -o /tmp/mica-cc-test && rm -f /tmp/mica-cc-test \
  || { echo "Compiler smoke test failed. Check NDK path."; exit 1; }

if [ ! -d "ffmpeg-${FFMPEG_VERSION}" ]; then
  echo ">> Download FFmpeg ${FFMPEG_VERSION}..."
  if [ ! -f "ffmpeg-${FFMPEG_VERSION}.tar.xz" ]; then
    curl -fL "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" -o "ffmpeg-${FFMPEG_VERSION}.tar.xz"
    cp -a "ffmpeg-${FFMPEG_VERSION}.tar.xz" "$HOST_CACHE/" 2>/dev/null || true
  fi
  tar xf "ffmpeg-${FFMPEG_VERSION}.tar.xz"
fi

cd "ffmpeg-${FFMPEG_VERSION}"
make distclean 2>/dev/null || true

echo ">> configure (arm64 static ffmpeg, common audio decoders)..."
./configure \
  --prefix="$BUILD_DIR/prefix-cli" \
  --enable-cross-compile \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --cc="$CC" \
  --cxx="$CXX" \
  --ar="$AR" \
  --ranlib="$RANLIB" \
  --strip="$STRIP" \
  --sysroot="$SYSROOT" \
  --disable-all \
  --enable-ffmpeg \
  --enable-avcodec \
  --enable-avformat \
  --enable-avutil \
  --enable-swresample \
  --enable-avfilter \
  --enable-filter=aformat,anull,aresample,asetpts,atrim,format,null,abuffer,abuffersink \
  --enable-static \
  --disable-shared \
  --disable-doc \
  --disable-debug \
  --enable-protocol=file \
  --enable-demuxer=mov,mp3,flac,ogg,wav,matroska,ape,wv,caf,aiff,asf,avi,mpc,mpc8,m4v \
  --enable-muxer=pcm_s16le \
  --enable-muxer=pcm_s24le \
  --enable-muxer=pcm_s32le \
  --enable-muxer=flac \
  --enable-decoder=alac,aac,aac_latm,flac,mp3,opus,vorbis,ape,wavpack,mpc7,mpc8,tta,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le \
  --enable-encoder=pcm_s16le,pcm_s24le,pcm_s32le,flac \
  --enable-parser=aac,alac,flac,mpegaudio,opus,vorbis,ac3 \
  --disable-ffprobe \
  --disable-ffplay

echo ">> make ffmpeg..."
make -j"$(nproc)" ffmpeg
"$STRIP" ffmpeg

cp -f ffmpeg "$OUT_DIR/ffmpeg"
chmod +x "$OUT_DIR/ffmpeg"

# 交叉编译产物是 Android ELF，在 x86 Docker 里跑 ./ffmpeg -muxers 会误报失败；改查 config.mak。
echo ">> Verify PCM muxers (config.mak)..."
MISSING=""
for m in PCM_S16LE PCM_S24LE PCM_S32LE; do
  if ! grep -q "CONFIG_${m}_MUXER=yes" ffbuild/config.mak; then
    MISSING="${MISSING} ${m}"
  fi
done
if [ -n "$MISSING" ]; then
  echo "ERROR: missing muxer(s):$MISSING"
  echo "       configure 须用 --enable-muxer=pcm_s16le（不是 s16le）；CLI 输出仍用 -f s16le"
  exit 1
fi
echo ">> PCM muxers OK (pcm_s16le / pcm_s24le / pcm_s32le → -f s16le / s24le / s32le)"

SIZE="$(du -h "$OUT_DIR/ffmpeg" | cut -f1)"
echo ">> Done: $OUT_DIR/ffmpeg ($SIZE)"
echo ">> Enabled demuxers: mov(m4a/mp4) flac mp3 ogg wav matroska ape wv aiff caf asf avi mpc ..."
echo ">> Rebuild and reinstall APK after this step."
