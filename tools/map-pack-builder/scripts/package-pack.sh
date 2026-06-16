#!/usr/bin/env bash
#
# package-pack.sh
#
# Turns the already-built dist/ components (adelaide-test-tiles.mbtiles + search.db)
# into a hostable/servable map pack artifact that the aus-roads in-app downloader
# can consume directly:
#
#   dist/latest.json                       -- the manifest the app fetches at <baseUrl>/latest.json
#   dist/pack.zip                          -- the single pack archive (tiles + search at their manifest paths)
#   dist/packs/<packVersion>/pack.zip      -- copy at the EXACT path the app downloads (<baseUrl>/packs/<ver>/pack.zip)
#
# The app's PackVerifier hashes each component file (sha256) after extracting the
# zip into the install dir, keyed by the manifest component `path`. It SKIPS a
# component only when its `format == "none"`. So:
#   - tiles  -> real path/sha256/size, file IS in the zip
#   - search -> real path/sha256/size, file IS in the zip
#   - routing-> format:"none" (the 101 MB Valhalla tar is intentionally NOT shipped
#               in this demo pack; see TODO below) so the verifier skips it.
#
# Idempotent: safe to re-run; it recomputes hashes from the live files every time
# and overwrites the artifacts. Inputs are READ from dist/ (built by
# build-adelaide-test-pack.sh); this script never rebuilds tiles/search.
#
# `zip` is NOT assumed to be installed -- when absent we build the archive with
# python3's zipfile module (deterministic STORED/DEFLATED entries, fixed mtime).
#
# Usage:
#   tools/map-pack-builder/scripts/package-pack.sh
#
# TODO(routing): when the Valhalla routing tar is checksummed and small enough to
#   ship (or hosted separately), flip routing.format back to "valhalla", add
#   valhalla_tiles.tar to the zip at components.routing.path, and fill real
#   sizeBytes/sha256. Until then routing stays format:"none" (verifier-skipped).

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DIST="$ROOT/dist"

log() { printf '\033[1;34m[package]\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m[package]\033[0m %s\n' "$*" >&2; }

# ---------------------------------------------------------------------------
# Inputs (must already exist in dist/, produced by build-adelaide-test-pack.sh)
# ---------------------------------------------------------------------------

TILES_PATH="adelaide-test-tiles.mbtiles"   # path as declared in the manifest + inside the zip
SEARCH_PATH="search.db"

TILES_FILE="$DIST/$TILES_PATH"
SEARCH_FILE="$DIST/$SEARCH_PATH"

for f in "$TILES_FILE" "$SEARCH_FILE"; do
    if [[ ! -s "$f" ]]; then
        err "missing/empty required component: $f"
        err "run build-adelaide-test-pack.sh first to produce the dist/ components"
        exit 1
    fi
done

# ---------------------------------------------------------------------------
# Pack identity / region / bbox (kept in sync with build-adelaide-test-pack.sh)
# ---------------------------------------------------------------------------

ADELAIDE_BBOX="138.4,-35.2,139,-34.6"   # minLon,minLat,maxLon,maxLat
BBOX_WEST="$(echo "$ADELAIDE_BBOX"  | awk -F, '{print $1}')"
BBOX_SOUTH="$(echo "$ADELAIDE_BBOX" | awk -F, '{print $2}')"
BBOX_EAST="$(echo "$ADELAIDE_BBOX"  | awk -F, '{print $3}')"
BBOX_NORTH="$(echo "$ADELAIDE_BBOX" | awk -F, '{print $4}')"

# packVersion: reuse the value already in dist/manifest.json if present (so the
# zip path matches a manifest produced earlier in the same build), else today (UTC).
PACK_VERSION=""
if [[ -s "$DIST/manifest.json" ]]; then
    PACK_VERSION="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("packVersion",""))' "$DIST/manifest.json" 2>/dev/null || true)"
fi
[[ -n "$PACK_VERSION" ]] || PACK_VERSION="$(date -u +%Y-%m-%d)"

GENERATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
OSM_EXTRACT_DATE="2026-05-31T20:21:36Z"   # informational; refreshed by the full build

SCHEMA_VERSION=1
MIN_ZOOM=0
MAX_ZOOM=14
MIN_APP_VERSION="0.1.1"
MIN_ANDROID_SDK=26

# ---------------------------------------------------------------------------
# 1. Real sha256 + sizes of the live component files
# ---------------------------------------------------------------------------

TILES_SHA="$(sha256sum "$TILES_FILE" | cut -d' ' -f1)"
TILES_SIZE="$(stat -c%s "$TILES_FILE")"
SEARCH_SHA="$(sha256sum "$SEARCH_FILE" | cut -d' ' -f1)"
SEARCH_SIZE="$(stat -c%s "$SEARCH_FILE")"
TOTAL_SIZE=$(( TILES_SIZE + SEARCH_SIZE ))   # routing excluded while format=="none"

log "tiles : $TILES_PATH  size=$TILES_SIZE  sha256=$TILES_SHA"
log "search: $SEARCH_PATH  size=$SEARCH_SIZE  sha256=$SEARCH_SHA"
log "packVersion=$PACK_VERSION  totalSizeBytes=$TOTAL_SIZE"

# ---------------------------------------------------------------------------
# 2. Build pack.zip containing the component files at their manifest `path`s.
#    Prefer the `zip` CLI when present; otherwise fall back to python3 zipfile.
#    Either way the archive entries are exactly: adelaide-test-tiles.mbtiles, search.db
# ---------------------------------------------------------------------------

ZIP_OUT="$DIST/pack.zip"
rm -f "$ZIP_OUT"

if command -v zip >/dev/null 2>&1; then
    log "using zip CLI to build pack.zip"
    # -X strips extra file attributes (more deterministic); -j would junk paths, so
    # we cd into dist/ and add by relative name to keep entries flat at the manifest paths.
    ( cd "$DIST" && zip -X -q "$ZIP_OUT" "$TILES_PATH" "$SEARCH_PATH" )
else
    log "zip CLI not found -- building pack.zip via python3 zipfile"
    python3 - "$ZIP_OUT" "$DIST" "$TILES_PATH" "$SEARCH_PATH" <<'PYZIP'
import sys, zipfile, os

zip_out, dist = sys.argv[1], sys.argv[2]
members = sys.argv[3:]

# Deterministic: fixed mtime + stable member order + DEFLATE. Writing into a
# ZipInfo with a constant date_time avoids embedding the current timestamp so
# repeated runs over identical inputs produce byte-stable archives.
FIXED_DT = (1980, 1, 1, 0, 0, 0)

with zipfile.ZipFile(zip_out, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as z:
    for name in sorted(members):
        src = os.path.join(dist, name)
        zi = zipfile.ZipInfo(filename=name, date_time=FIXED_DT)
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.external_attr = (0o644 & 0xFFFF) << 16  # -rw-r--r--
        with open(src, "rb") as fi, z.open(zi, "w") as fo:
            # stream in chunks so a multi-MB member never loads fully into RAM
            while True:
                chunk = fi.read(16 * 1024 * 1024)
                if not chunk:
                    break
                fo.write(chunk)
print("wrote %s (%d bytes)" % (zip_out, os.path.getsize(zip_out)))
PYZIP
fi

ZIP_SIZE="$(stat -c%s "$ZIP_OUT")"
log "pack.zip: $(du -h "$ZIP_OUT" | cut -f1) ($ZIP_SIZE bytes)"

# ---------------------------------------------------------------------------
# 3. Emit latest.json (the served manifest). Matches PackManifest.kt:
#    - routing.format = "none" -> PackVerifier skips it
#    - tiles/search  = real path/sha256/sizeBytes; files ARE inside pack.zip
# ---------------------------------------------------------------------------

LATEST="$DIST/latest.json"
cat > "$LATEST" <<EOF
{
  "schemaVersion": ${SCHEMA_VERSION},
  "packVersion": "${PACK_VERSION}",
  "region": {
    "country": "AU",
    "state": "sa"
  },
  "bbox": {
    "west": ${BBOX_WEST},
    "south": ${BBOX_SOUTH},
    "east": ${BBOX_EAST},
    "north": ${BBOX_NORTH}
  },
  "generatedAt": "${GENERATED_AT}",
  "osmSource": {
    "provider": "geofabrik",
    "url": "https://download.geofabrik.de/australia-oceania/australia/south-australia-latest.osm.pbf",
    "osmExtractDate": "${OSM_EXTRACT_DATE}"
  },
  "license": "ODbL-1.0",
  "minAppVersion": "${MIN_APP_VERSION}",
  "minAndroidSdk": ${MIN_ANDROID_SDK},
  "components": {
    "tiles": {
      "format": "mbtiles",
      "schema": "openmaptiles-3.15",
      "minZoom": ${MIN_ZOOM},
      "maxZoom": ${MAX_ZOOM},
      "path": "${TILES_PATH}",
      "sizeBytes": ${TILES_SIZE},
      "sha256": "${TILES_SHA}"
    },
    "routing": {
      "format": "none",
      "profile": "none",
      "path": "routing/valhalla_tiles.tar",
      "sizeBytes": 0,
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
    },
    "search": {
      "format": "sqlite-fts5",
      "path": "${SEARCH_PATH}",
      "sizeBytes": ${SEARCH_SIZE},
      "sha256": "${SEARCH_SHA}"
    }
  },
  "totalSizeBytes": ${TOTAL_SIZE}
}
EOF

# Validate the JSON we just wrote (fail loudly on a malformed manifest).
python3 -c 'import json,sys;json.load(open(sys.argv[1]))' "$LATEST"
log "wrote $LATEST (valid JSON)"

# Keep manifest.json in sync with latest.json (handy for inspection / parity).
cp -f "$LATEST" "$DIST/manifest.json"
log "synced $DIST/manifest.json"

# ---------------------------------------------------------------------------
# 4. Stage the zip at the EXACT path the app downloads:
#    <baseUrl>/packs/<packVersion>/pack.zip
# ---------------------------------------------------------------------------

PACK_DIR="$DIST/packs/$PACK_VERSION"
mkdir -p "$PACK_DIR"
cp -f "$ZIP_OUT" "$PACK_DIR/pack.zip"
log "staged $PACK_DIR/pack.zip"

# ---------------------------------------------------------------------------
# 5. Self-verify: extract pack.zip to a temp dir, re-hash each component, and
#    confirm it matches latest.json. This mirrors what PackVerifier does on-device.
# ---------------------------------------------------------------------------

VERIFY_TMP="$(mktemp -d)"
trap 'rm -rf "$VERIFY_TMP"' EXIT
( cd "$VERIFY_TMP" && unzip -q "$ZIP_OUT" )

verify_one() {
    local rel="$1" want_sha="$2" want_size="$3" label="$4"
    local f="$VERIFY_TMP/$rel"
    if [[ ! -s "$f" ]]; then err "VERIFY FAIL: $label missing from zip ($rel)"; exit 1; fi
    local got_sha got_size
    got_sha="$(sha256sum "$f" | cut -d' ' -f1)"
    got_size="$(stat -c%s "$f")"
    if [[ "$got_sha" != "$want_sha" || "$got_size" != "$want_size" ]]; then
        err "VERIFY FAIL: $label sha/size mismatch"
        err "  manifest: sha=$want_sha size=$want_size"
        err "  in-zip  : sha=$got_sha size=$got_size"
        exit 1
    fi
    log "verify OK: $label ($rel)"
}

verify_one "$TILES_PATH"  "$TILES_SHA"  "$TILES_SIZE"  "tiles"
verify_one "$SEARCH_PATH" "$SEARCH_SHA" "$SEARCH_SIZE" "search"

# Confirm the served paths resolve under dist/.
[[ -s "$LATEST" ]]              && log "OK: dist/latest.json present"
[[ -s "$PACK_DIR/pack.zip" ]]   && log "OK: dist/packs/$PACK_VERSION/pack.zip present"

log "DONE. Hostable artifacts in $DIST:"
echo "  - latest.json                       (the served manifest)"
echo "  - pack.zip                          ($(du -h "$ZIP_OUT" | cut -f1))"
echo "  - packs/$PACK_VERSION/pack.zip       (download path)"
echo
echo "Serve locally with:  $HERE/serve-pack.sh [port]"
echo "Then the app base URL is http://<host>:<port>/  ->"
echo "  manifest: http://<host>:<port>/latest.json"
echo "  pack zip: http://<host>:<port>/packs/$PACK_VERSION/pack.zip"
