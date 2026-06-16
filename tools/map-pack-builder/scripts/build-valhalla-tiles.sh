#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CACHE_DIR="$SCRIPT_DIR/../cache"
DIST_DIR="$SCRIPT_DIR/../dist"
PBF="$CACHE_DIR/south-australia-latest.osm.pbf"
TILE_DIR="$DIST_DIR/valhalla_tiles"
TAR_FILE="$DIST_DIR/routing/valhalla_tiles.tar"

# Pinned to the version that built the shipped routing graph (and the only image
# pulled locally). ":latest" would pull a different/unavailable build on a fresh
# rebuild — failing offline, or drifting the graph format away from the app's
# bundled libvalhalla-wrapper.so.
VALHALLA_IMAGE="ghcr.io/valhalla/valhalla:3.5.0"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

# Check dependencies
for cmd in docker tar; do
    command -v "$cmd" >/dev/null 2>&1 || { log "ERROR: $cmd not found"; exit 1; }
done

[ -f "$PBF" ] || { log "ERROR: PBF not found at $PBF"; exit 1; }

mkdir -p "$TILE_DIR" "$DIST_DIR/routing"

log "generating Valhalla config..."
docker run --rm -v "$DIST_DIR:/data" \
    "$VALHALLA_IMAGE" \
    valhalla_build_config \
    --mjolnir-tile-dir /data/valhalla_tiles \
    --mjolnir-timezone /data/valhalla_tiles/timezones.sqlite \
    --mjolnir-admin /data/valhalla_tiles/admins.sqlite \
    > "$DIST_DIR/valhalla.json"

log "building Valhalla tiles from OSM PBF (this may take several minutes)..."
docker run --rm \
    -v "$CACHE_DIR:/data/cache:ro" \
    -v "$DIST_DIR:/data/dist" \
    "$VALHALLA_IMAGE" \
    valhalla_build_tiles \
    -c /data/dist/valhalla.json \
    /data/cache/south-australia-latest.osm.pbf

log "creating tar archive..."
cd "$TILE_DIR" && tar -cf "$TAR_FILE" .
cd "$SCRIPT_DIR"

# Compute SHA-256 and size
SHA256=$(sha256sum "$TAR_FILE" | awk '{print $1}')
SIZE=$(stat -c%s "$TAR_FILE")

log "done."
log "  tar: $TAR_FILE"
log "  size: $SIZE bytes ($(echo "scale=1; $SIZE / 1048576" | bc) MB)"
log "  sha256: $SHA256"
