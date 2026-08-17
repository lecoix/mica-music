#!/usr/bin/env bash
set -euo pipefail

VERSION="v0.7.6"
STATE_DIR="/workspaces/.mica-cloud"
INSTALL_DIR="$HOME/.local/share/mica-cloud-agentdock/$VERSION"
BIN="$INSTALL_DIR/bin/agentdock"
LOG="$STATE_DIR/agentdock.log"
PID_FILE="$STATE_DIR/agentdock.pid"

mkdir -p "$STATE_DIR" "$INSTALL_DIR"
chmod 700 "$STATE_DIR"

if curl -fsS --max-time 2 http://127.0.0.1:8765/healthz >/dev/null 2>&1; then
  exit 0
fi

if [[ -z "${MICA_CLOUD_TOKEN:-}" ]]; then
  echo "mica cloud agentdock: MICA_CLOUD_TOKEN Codespaces secret is missing" >&2
  exit 2
fi

if [[ ! -x "$BIN" ]]; then
  case "$(uname -m)" in
    x86_64|amd64) arch="amd64" ;;
    aarch64|arm64) arch="arm64" ;;
    *) echo "mica cloud agentdock: unsupported architecture: $(uname -m)" >&2; exit 3 ;;
  esac
  base="https://github.com/uvwt/agentdock/releases/download/$VERSION"
  file="agentdock_linux_${arch}.tar.gz"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL "$base/$file" -o "$tmp/$file"
  curl -fsSL "$base/$file.sha256" -o "$tmp/$file.sha256"
  (cd "$tmp" && sha256sum -c "$file.sha256")
  tar -xzf "$tmp/$file" -C "$tmp"
  mkdir -p "$INSTALL_DIR/bin"
  if [[ -x "$tmp/bin/agentdock" ]]; then
    install -m 755 "$tmp/bin/agentdock" "$BIN"
  elif [[ -x "$tmp/agentdock" ]]; then
    install -m 755 "$tmp/agentdock" "$BIN"
  else
    echo "mica cloud agentdock: release archive has no agentdock binary" >&2
    exit 4
  fi
fi

mkdir -p "$HOME/.agentdock" "$HOME/AgentDock"
nohup env \
  HOME="$HOME" \
  AGENTDOCK_HOST=0.0.0.0 \
  AGENTDOCK_PORT=8765 \
  AGENTDOCK_AUTH_TOKEN="$MICA_CLOUD_TOKEN" \
  "$BIN" >"$LOG" 2>&1 < /dev/null &
echo $! > "$PID_FILE"

for _ in $(seq 1 30); do
  if curl -fsS --max-time 2 http://127.0.0.1:8765/healthz >/dev/null 2>&1; then
    echo "mica cloud agentdock: ready"
    exit 0
  fi
  sleep 1
done

echo "mica cloud agentdock: failed to become healthy; see $LOG" >&2
exit 5
