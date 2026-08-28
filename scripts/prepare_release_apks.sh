#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR="${1:?source APK directory is required}"
RELEASE_DIR="${2:?release APK directory is required}"
VERSION_NAME="${3:?version name is required}"
BUILD_TYPE="${4:-release}"

mkdir -p "$RELEASE_DIR"

declare -A SOURCE_APKS=(
  [arm64-v8a]="$SOURCE_DIR/app-arm64-v8a-${BUILD_TYPE}.apk"
  [armeabi-v7a]="$SOURCE_DIR/app-armeabi-v7a-${BUILD_TYPE}.apk"
  [universal]="$SOURCE_DIR/app-universal-${BUILD_TYPE}.apk"
)
declare -A RELEASE_APKS=(
  [arm64-v8a]="$RELEASE_DIR/Mica_${VERSION_NAME}_64位.apk"
  [armeabi-v7a]="$RELEASE_DIR/Mica_${VERSION_NAME}_32位.apk"
  [universal]="$RELEASE_DIR/Mica_${VERSION_NAME}_通用.apk"
)

for key in arm64-v8a armeabi-v7a universal; do
  source_apk="${SOURCE_APKS[$key]}"
  if [[ ! -f "$source_apk" ]]; then
    echo "Release APK not found: $source_apk" >&2
    find "$SOURCE_DIR" -maxdepth 1 -type f -name '*.apk' -print >&2
    exit 1
  fi
  cp "$source_apk" "${RELEASE_APKS[$key]}"
done

list_abis() {
  unzip -Z1 "$1" \
    | sed -n 's#^lib/\([^/]*\)/.*\.so$#\1#p' \
    | sort -u
}

list_libs() {
  local apk="$1"
  local abi="$2"
  unzip -Z1 "$apk" \
    | awk -F/ -v abi="$abi" \
        '$1 == "lib" && $2 == abi && $3 ~ /^[^/]*\.so$/ { print $3 }' \
    | sort -u
}

assert_abis() {
  local apk="$1"
  local expected="$2"
  local actual
  actual="$(list_abis "$apk")"
  if [[ "$actual" != "$expected" ]]; then
    echo "Unexpected ABI set in $apk" >&2
    echo "Expected:" >&2
    printf '%s\n' "$expected" >&2
    echo "Actual:" >&2
    printf '%s\n' "$actual" >&2
    exit 1
  fi
}

APK_64="${RELEASE_APKS[arm64-v8a]}"
APK_32="${RELEASE_APKS[armeabi-v7a]}"
APK_UNIVERSAL="${RELEASE_APKS[universal]}"
assert_abis "$APK_64" "arm64-v8a"
assert_abis "$APK_32" "armeabi-v7a"
assert_abis "$APK_UNIVERSAL" $'arm64-v8a\narmeabi-v7a'

diff -u \
  <(list_libs "$APK_64" arm64-v8a) \
  <(list_libs "$APK_32" armeabi-v7a)
diff -u \
  <(list_libs "$APK_64" arm64-v8a) \
  <(list_libs "$APK_UNIVERSAL" arm64-v8a)
diff -u \
  <(list_libs "$APK_32" armeabi-v7a) \
  <(list_libs "$APK_UNIVERSAL" armeabi-v7a)

for required_lib in \
  libc++_shared.so \
  libffmpegJNI.so \
  libsylvakru_usb_exclusive.so \
  libtaglib.so \
  libusb-1.0.so; do
  if ! list_libs "$APK_32" armeabi-v7a | grep -Fxq "$required_lib"; then
    echo "Required ARMv7 library missing: $required_lib" >&2
    exit 1
  fi
done

ls -lh "$RELEASE_DIR"/*.apk
