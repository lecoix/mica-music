#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

if [[ -n "$(git status --porcelain=v1)" ]]; then
  echo "USB cloud gate requires a clean checkout before runner-local fixture handling." >&2
  git status --short >&2
  exit 2
fi

echo "USB_CLOUD_SHA=$(git rev-parse HEAD)"
echo "USB_CLOUD_BRANCH=${GITHUB_REF_NAME:-$(git branch --show-current)}"

# This package is an established test-collection debt in the current USB baseline.
# Keep the workaround runner-local and outside the checkout so the gate cannot
# accidentally certify a source-tree mutation.
USBPROTOTYPE="app/src/test/java/com/mica/music/media/usbprototype"
HOLD_ROOT="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/mica-usbprototype-hold-$$"
restore_fixture() {
  if [[ -d "$HOLD_ROOT/usbprototype" && ! -e "$USBPROTOTYPE" ]]; then
    mkdir -p "$(dirname "$USBPROTOTYPE")"
    mv "$HOLD_ROOT/usbprototype" "$USBPROTOTYPE"
  fi
  rm -rf "$HOLD_ROOT"
}
trap restore_fixture EXIT

if [[ -d "$USBPROTOTYPE" ]]; then
  mkdir -p "$HOLD_ROOT"
  mv "$USBPROTOTYPE" "$HOLD_ROOT/usbprototype"
fi

# Source integrity before any build/test action.
git diff --check

bash ./gradlew \
  :app:compileDebugKotlin \
  :app:compilePerfKotlin \
  -Pksp.incremental=false \
  --no-daemon \
  --no-configuration-cache

COMMON_TESTS=(
  'com.mica.music.media.usb.protocol.UsbPcmPhysicalRetirementProofTest'
  'com.mica.music.media.usb.protocol.UsbExclusivePlaybackProtocolTest'
  'com.mica.music.media.usb.shadow.UsbExclusiveShadowCoordinatorTest'
  'com.mica.music.media.dsd.PcmPhysicalRetirementStructureTest'
)

TEST_ARGS=()
for pattern in "${COMMON_TESTS[@]}"; do
  TEST_ARGS+=(--tests "$pattern")
done

EXTRA_FILE=".github/usb-cloud-extra-tests.txt"
if [[ -f "$EXTRA_FILE" ]]; then
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    line="${raw%%#*}"
    line="$(printf '%s' "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    [[ -z "$line" ]] && continue
    TEST_ARGS+=(--tests "$line")
  done < "$EXTRA_FILE"
fi

bash ./gradlew \
  :app:testPerfUnitTest \
  "${TEST_ARGS[@]}" \
  --no-daemon \
  --no-configuration-cache

restore_fixture
trap - EXIT

# Runner-local fixture handling must leave the checkout byte-for-byte clean.
if [[ -n "$(git status --porcelain=v1)" ]]; then
  echo "USB cloud gate left the checkout dirty." >&2
  git status --short >&2
  exit 3
fi

git diff --check
echo 'USB_CLOUD_GATE=GREEN'
