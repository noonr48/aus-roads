#!/usr/bin/env bash
#
# serve-pack.sh
#
# Serves the built map-pack artifacts in dist/ over plain HTTP so the aus-roads
# in-app downloader can fetch them in a local demo. The app fetches:
#
#   <baseUrl>/latest.json                  -- the manifest (dist/latest.json)
#   <baseUrl>/packs/<packVersion>/pack.zip -- the pack archive (staged by package-pack.sh)
#
# so point the app's pack base URL at http://<this-host>:<port>/ .
#
# Usage:
#   tools/map-pack-builder/scripts/serve-pack.sh [port]
#
# Default port: 8080. Binds 0.0.0.0 so a device/emulator on the LAN can reach it.
#   - Android emulator -> use http://10.0.2.2:<port>/ to reach the host loopback.
#   - Physical device  -> use http://<host-LAN-IP>:<port>/ .
#
# Ctrl-C to stop. This is a dev/demo helper only -- python3 http.server is not a
# production server.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DIST="$ROOT/dist"
PORT="${1:-8080}"

log() { printf '\033[1;34m[serve]\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m[serve]\033[0m %s\n' "$*" >&2; }

if [[ ! -s "$DIST/latest.json" ]]; then
    err "dist/latest.json not found -- run package-pack.sh first."
    exit 1
fi

# The app fetches the pack at the FLAT URL <base>/pack.zip (GitHub Release asset
# names can't contain slashes); package-pack.sh writes dist/pack.zip.
ZIP_REL="pack.zip"
if [[ ! -s "$DIST/$ZIP_REL" ]]; then
    err "dist/$ZIP_REL not found -- run package-pack.sh first."
    exit 1
fi

# Best-effort LAN IP for the convenience hint (does not affect what we bind).
LAN_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')"
[[ -n "${LAN_IP:-}" ]] || LAN_IP="<host-LAN-IP>"

log "serving $DIST on 0.0.0.0:$PORT"
log "manifest : http://${LAN_IP}:${PORT}/latest.json"
log "pack zip : http://${LAN_IP}:${PORT}/${ZIP_REL}"
log "device base URL: http://127.0.0.1:${PORT}/  (run: adb reverse tcp:${PORT} tcp:${PORT})"
log "Ctrl-C to stop."

# Serve from dist/ so /latest.json and /pack.zip resolve directly.
cd "$DIST"
exec python3 -m http.server "$PORT" --bind 0.0.0.0
