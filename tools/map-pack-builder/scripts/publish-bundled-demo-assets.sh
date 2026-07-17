#!/usr/bin/env bash
# Validate and atomically publish one complete bundled map/search generation.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=bundled-demo-contract.sh
source "$HERE/bundled-demo-contract.sh"

GENERATED_DIR="${1:?usage: publish-bundled-demo-assets.sh GENERATED_DIR ASSET_DIR}"
ASSET_DIR="${2:?usage: publish-bundled-demo-assets.sh GENERATED_DIR ASSET_DIR}"
ASSET_PARENT="$(dirname "$ASSET_DIR")"

command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }
command -v sqlite3 >/dev/null || { echo "sqlite3 is required" >&2; exit 1; }
[[ -d "$ASSET_DIR" ]] || { echo "existing asset directory is required: $ASSET_DIR" >&2; exit 1; }

validate_generation() {
    local directory="$1"
    local tiles="$directory/$BUNDLED_DEMO_TILES"
    local search="$directory/$BUNDLED_DEMO_SEARCH"
    local tiles_hash_file="${tiles}.sha256"
    local search_hash_file="${search}.sha256"
    local expected_tiles expected_search actual_bounds

    for artifact in "$tiles" "$tiles_hash_file" "$search" "$search_hash_file"; do
        [[ -s "$artifact" ]] || { echo "missing bundled artifact: $artifact" >&2; return 1; }
    done

    expected_tiles="$(tr -d '[:space:]' < "$tiles_hash_file")"
    expected_search="$(tr -d '[:space:]' < "$search_hash_file")"
    [[ "$expected_tiles" =~ ^[0-9a-f]{64}$ ]] || { echo "invalid tile checksum" >&2; return 1; }
    [[ "$expected_search" =~ ^[0-9a-f]{64}$ ]] || { echo "invalid search checksum" >&2; return 1; }
    [[ "$(sha256sum "$tiles" | cut -d' ' -f1)" == "$expected_tiles" ]] || {
        echo "tile checksum mismatch" >&2
        return 1
    }
    [[ "$(sha256sum "$search" | cut -d' ' -f1)" == "$expected_search" ]] || {
        echo "search checksum mismatch" >&2
        return 1
    }
    [[ "$(sqlite3 "$tiles" 'PRAGMA integrity_check;')" == "ok" ]] || {
        echo "bundled tiles failed SQLite integrity check" >&2
        return 1
    }
    [[ "$(sqlite3 "$search" 'PRAGMA integrity_check;')" == "ok" ]] || {
        echo "bundled search failed SQLite integrity check" >&2
        return 1
    }

    actual_bounds="$(sqlite3 "$tiles" "SELECT value FROM metadata WHERE name='bounds' LIMIT 1;")"
    [[ "$actual_bounds" == "$BUNDLED_DEMO_BBOX" ]] || {
        echo "bundled tile bounds $actual_bounds do not match $BUNDLED_DEMO_BBOX" >&2
        return 1
    }
}

validate_generation "$GENERATED_DIR"

# Build the complete next directory beside the current one. Keeping the two
# directories on one filesystem is required for an atomic RENAME_EXCHANGE.
PUBLISH_DIR="$(mktemp -d "$ASSET_PARENT/.maptile-generation.XXXXXX")"
cleanup() { rm -rf "$PUBLISH_DIR"; }
trap cleanup EXIT
cp -a "$ASSET_DIR/." "$PUBLISH_DIR/"
install -m 0644 \
    "$GENERATED_DIR/$BUNDLED_DEMO_TILES" \
    "$GENERATED_DIR/${BUNDLED_DEMO_TILES}.sha256" \
    "$GENERATED_DIR/$BUNDLED_DEMO_SEARCH" \
    "$GENERATED_DIR/${BUNDLED_DEMO_SEARCH}.sha256" \
    "$PUBLISH_DIR/"
validate_generation "$PUBLISH_DIR"

# Linux renameat2(RENAME_EXCHANGE) changes the directory pointer in one syscall.
# An interrupt therefore leaves either the complete old generation or the
# complete new generation at ASSET_DIR; it can never leave a mixed four-file set.
python3 - "$PUBLISH_DIR" "$ASSET_DIR" <<'PY'
import ctypes
import os
import sys

source, destination = (os.fsencode(path) for path in sys.argv[1:])
libc = ctypes.CDLL(None, use_errno=True)
renameat2 = getattr(libc, "renameat2", None)
if renameat2 is None:
    raise SystemExit("renameat2 is required for generation-atomic publication")
renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
renameat2.restype = ctypes.c_int
at_fdcwd = -100
rename_exchange = 2
if renameat2(at_fdcwd, source, at_fdcwd, destination, rename_exchange) != 0:
    error_number = ctypes.get_errno()
    raise OSError(error_number, os.strerror(error_number))
PY

# PUBLISH_DIR now names the old generation. Cleanup removes it; a SIGKILL here
# can at worst leave that hidden backup behind, never corrupt the live assets.
echo "published bundled demo generation atomically"
