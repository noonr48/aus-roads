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
#   - routing-> real path/sha256/size + format:"valhalla" and the tar IS in the zip
#               WHEN a Valhalla graph is available; else format:"none" (skipped).
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
# Routing: when a built Valhalla graph is present (valhalla-build/valhalla_tiles.tar,
#   or an already-staged dist/routing/valhalla_tiles.tar) it is added to the zip at
#   components.routing.path with real format:"valhalla"/sha256/size, so PackVerifier
#   checks it and the app's RoutingInitializer loads it (reads the .tar directly via
#   Valhalla tile_extract). With no tar present, routing stays format:"none".

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
# Pack identity / region / bbox. The region bounds come from dist/manifest.json
# (written by build-adelaide-test-pack.sh — the single source of the bbox); fall
# back to the Adelaide test clip when no manifest is present.
# ---------------------------------------------------------------------------

DEFAULT_BBOX="138.4,-35.2,139,-34.6"   # minLon,minLat,maxLon,maxLat (Adelaide test clip)
BBOX_WEST=""; BBOX_SOUTH=""; BBOX_EAST=""; BBOX_NORTH=""
if [[ -s "$DIST/manifest.json" ]]; then
    BBOX_LINE="$(python3 -c 'import json,sys
b=json.load(open(sys.argv[1]))["bbox"]
print(b["west"], b["south"], b["east"], b["north"])' "$DIST/manifest.json" 2>/dev/null || true)"
    if [[ -n "$BBOX_LINE" ]]; then
        BBOX_WEST="$(echo "$BBOX_LINE"  | awk '{print $1}')"
        BBOX_SOUTH="$(echo "$BBOX_LINE" | awk '{print $2}')"
        BBOX_EAST="$(echo "$BBOX_LINE"  | awk '{print $3}')"
        BBOX_NORTH="$(echo "$BBOX_LINE" | awk '{print $4}')"
    fi
fi
if [[ -z "$BBOX_WEST" || -z "$BBOX_SOUTH" || -z "$BBOX_EAST" || -z "$BBOX_NORTH" ]]; then
    BBOX_WEST="$(echo "$DEFAULT_BBOX"  | awk -F, '{print $1}')"
    BBOX_SOUTH="$(echo "$DEFAULT_BBOX" | awk -F, '{print $2}')"
    BBOX_EAST="$(echo "$DEFAULT_BBOX"  | awk -F, '{print $3}')"
    BBOX_NORTH="$(echo "$DEFAULT_BBOX" | awk -F, '{print $4}')"
fi

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

# Routing: ship the Valhalla graph when one is built. Look in valhalla-build/ (where
# build-valhalla-tiles.sh leaves the whole-SA graph) or an already-staged dist/routing/.
# When present, stage it at the manifest path, add it to the zip, and emit a real
# format/sha/size so PackVerifier checks it; otherwise routing stays "none".
ROUTING_REL="routing/valhalla_tiles.tar"
ROUTING_FORMAT="none"; ROUTING_PROFILE="none"; ROUTING_SIZE=0
ROUTING_SHA="0000000000000000000000000000000000000000000000000000000000000000"
ROUTING_SRC=""
for cand in "$ROOT/valhalla-build/valhalla_tiles.tar" "$DIST/$ROUTING_REL"; do
    [[ -s "$cand" ]] && ROUTING_SRC="$cand" && break
done
if [[ -n "$ROUTING_SRC" ]]; then
    mkdir -p "$DIST/routing"
    [[ "$ROUTING_SRC" -ef "$DIST/$ROUTING_REL" ]] || cp -f "$ROUTING_SRC" "$DIST/$ROUTING_REL"
    ROUTING_FORMAT="valhalla"
    ROUTING_PROFILE="auto"
    ROUTING_SHA="$(sha256sum "$DIST/$ROUTING_REL" | cut -d' ' -f1)"
    ROUTING_SIZE="$(stat -c%s "$DIST/$ROUTING_REL")"
fi

TOTAL_SIZE=$(( TILES_SIZE + SEARCH_SIZE + ROUTING_SIZE ))

log "tiles : $TILES_PATH  size=$TILES_SIZE  sha256=$TILES_SHA"
log "search: $SEARCH_PATH  size=$SEARCH_SIZE  sha256=$SEARCH_SHA"
if [[ "$ROUTING_FORMAT" != "none" ]]; then
    log "routing: $ROUTING_REL  size=$ROUTING_SIZE  sha256=$ROUTING_SHA"
else
    log "routing: none (no Valhalla tar found — shipping tiles+search only)"
fi
log "packVersion=$PACK_VERSION  totalSizeBytes=$TOTAL_SIZE"

# ---------------------------------------------------------------------------
# 2. Build pack.zip containing the component files at their manifest `path`s.
#    Prefer the `zip` CLI when present; otherwise fall back to python3 zipfile.
#    Either way the archive entries are exactly: adelaide-test-tiles.mbtiles, search.db
# ---------------------------------------------------------------------------

ZIP_OUT="$DIST/pack.zip"
rm -f "$ZIP_OUT"

# Archive members, each stored at its manifest `path`. Routing is included only when
# a Valhalla tar was staged above (else the pack is tiles+search only).
MEMBERS=( "$TILES_PATH" "$SEARCH_PATH" )
[[ "$ROUTING_FORMAT" != "none" ]] && MEMBERS+=( "$ROUTING_REL" )

if command -v zip >/dev/null 2>&1; then
    log "using zip CLI to build pack.zip (${MEMBERS[*]})"
    # -X strips extra file attributes (more deterministic); -j would junk paths, so
    # we cd into dist/ and add by relative name to keep entries at the manifest paths.
    ( cd "$DIST" && zip -X -q "$ZIP_OUT" "${MEMBERS[@]}" )
else
    log "zip CLI not found -- building pack.zip via python3 zipfile (${MEMBERS[*]})"
    python3 - "$ZIP_OUT" "$DIST" "${MEMBERS[@]}" <<'PYZIP'
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
      "format": "${ROUTING_FORMAT}",
      "profile": "${ROUTING_PROFILE}",
      "path": "${ROUTING_REL}",
      "sizeBytes": ${ROUTING_SIZE},
      "sha256": "${ROUTING_SHA}"
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
[[ "$ROUTING_FORMAT" != "none" ]] && verify_one "$ROUTING_REL" "$ROUTING_SHA" "$ROUTING_SIZE" "routing"

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
