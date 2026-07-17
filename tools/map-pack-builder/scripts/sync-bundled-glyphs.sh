#!/usr/bin/env bash
# Synchronise the exact Open Sans glyph ranges referenced by the bundled map.
# Every download is pinned so a changed or error response can never enter the APK.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$HERE/../../.." && pwd)"
ASSET_DIR="$PROJECT_ROOT/android/app/src/main/assets/glyphs/Open Sans Regular"
BASE_URL="https://fonts.openmaptiles.org/Open%20Sans%20Regular"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }
mkdir -p "$ASSET_DIR"

TMP=""
cleanup() { [[ -z "$TMP" ]] || rm -f "$TMP"; }
trap cleanup EXIT

while read -r range expected_hash; do
    [[ -n "$range" ]] || continue
    output="$ASSET_DIR/$range.pbf"
    if [[ -f "$output" ]] && [[ "$(sha256sum "$output" | cut -d' ' -f1)" == "$expected_hash" ]]; then
        continue
    fi

    TMP="$(mktemp "$ASSET_DIR/.${range}.tmp.XXXXXX")"
    curl -fsSL "$BASE_URL/$range.pbf" -o "$TMP"
    actual_hash="$(sha256sum "$TMP" | cut -d' ' -f1)"
    [[ "$actual_hash" == "$expected_hash" ]] || {
        echo "glyph checksum mismatch for $range: expected $expected_hash, got $actual_hash" >&2
        exit 1
    }
    chmod 0644 "$TMP"
    mv -f "$TMP" "$output"
    TMP=""
done <<'RANGES'
0-255 0c8bdb284f2ad6f69bbb434a0208393f3a517bc8bf7ed1eccc4ac4791ebbcbfe
256-511 9962ad38d064edbeb226a6c7dcf1d58e7678508a805f980d2fe81bb3178ba68a
512-767 2a7c191141c66e209d08d834df530a06a234224d48ae97d69f1bde1b1b5db404
768-1023 fea6a1c8e1be55d9725520627b108d425723b2dc189b7dddfe56abd7d8238ea9
7680-7935 d83e773d45d475f5ffd25917cd8735450586bc9d42a53fb54ccce5618241d976
8192-8447 a57611157f0597b267ef36eb07ce5a41dddcc3b2f3b5b85779108b7bb48b1cde
9984-10239 bf288e9537f8710f2f899bda3e624732837d919de9a8e29b441c587142221dd2
65024-65279 4a8cda576e7f10a063b984265d0055c3e3a4459c59cb50d0579bad71de31b33c
RANGES

echo "bundled glyph ranges are complete and checksum-pinned"
