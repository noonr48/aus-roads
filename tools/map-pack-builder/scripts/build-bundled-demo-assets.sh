#!/usr/bin/env bash
# Canonically rebuild and install the matching demo tiles + search assets.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PROJECT_ROOT="$(cd "$ROOT/../.." && pwd)"
DIST="${BUNDLED_DEMO_DIST:-$ROOT/dist}"
ASSET_DIR="${BUNDLED_DEMO_ASSET_DIR:-$PROJECT_ROOT/android/app/src/main/assets/maptile}"
BUILD_SCRIPT="${BUNDLED_DEMO_BUILD_SCRIPT:-$HERE/build-adelaide-test-pack.sh}"

# shellcheck source=bundled-demo-contract.sh
source "$HERE/bundled-demo-contract.sh"

PACK_BBOX="$BUNDLED_DEMO_BBOX" BUNDLED_DEMO_DIST="$DIST" bash "$BUILD_SCRIPT"

BUILT_TILES="$DIST/$BUNDLED_DEMO_TILES"
BUILT_SEARCH="$DIST/search.db"
[[ -s "$BUILT_TILES" ]] || { echo "missing built demo tiles: $BUILT_TILES" >&2; exit 1; }
[[ -s "$BUILT_SEARCH" ]] || { echo "missing built demo search database: $BUILT_SEARCH" >&2; exit 1; }

ACTUAL_BOUNDS="$(sqlite3 "$BUILT_TILES" "SELECT value FROM metadata WHERE name='bounds' LIMIT 1;")"
python3 - "$BUNDLED_DEMO_BBOX" "$ACTUAL_BOUNDS" <<'PY'
import sys

expected = tuple(float(value) for value in sys.argv[1].split(","))
actual = tuple(float(value) for value in sys.argv[2].split(","))
if actual != expected:
    raise SystemExit(f"built tile bounds {actual} do not match demo contract {expected}")
PY

# Stage every generated file before touching the checked-in generation. The
# publisher copies the non-generated assets (style/glyphs), validates both
# checksums, then swaps the complete maptile directory in one renameat2 syscall.
ASSET_PARENT="$(dirname "$ASSET_DIR")"
mkdir -p "$ASSET_PARENT"
STAGE="$(mktemp -d "$ASSET_PARENT/.bundled-demo-stage.XXXXXX")"
cleanup() { rm -rf "$STAGE"; }
trap cleanup EXIT

install -m 0644 "$BUILT_TILES" "$STAGE/$BUNDLED_DEMO_TILES"
sha256sum "$STAGE/$BUNDLED_DEMO_TILES" | cut -d' ' -f1 \
    > "$STAGE/${BUNDLED_DEMO_TILES}.sha256"
bash "$HERE/build-bundled-search-asset.sh" \
    "$BUILT_SEARCH" \
    "$STAGE/$BUNDLED_DEMO_SEARCH" \
    "$STAGE/$BUNDLED_DEMO_TILES"

bash "$HERE/publish-bundled-demo-assets.sh" "$STAGE" "$ASSET_DIR"

echo "installed matching bundled demo assets in $ASSET_DIR"
