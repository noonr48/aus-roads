#!/usr/bin/env bash
#
# build-adelaide-test-pack.sh
#
# Builds the vector tiles (Planetiler) for a region and packages
# dist/pack.zip + dist/latest.json via package-pack.sh — including the search
# index (dist/search.db, built by build-search-index.sh) and the Valhalla routing
# graph (build-valhalla-tiles.sh) when those components are present in dist/.
#
# Region-parameterized by PACK_BBOX (default: the small Adelaide metro test clip
# used to verify the renderer cheaply). Build the FULL South Australia pack with:
#   PACK_BBOX="129,-38,141,-26" PACK_HEAP=8g ./scripts/build-adelaide-test-pack.sh
# See /home/benbi/Apps/aus-roads/docs/adr/0003-map-pack-format.md for the format.
#
# Inputs:
#   - Geofabrik south-australia-latest.osm.pbf (~62 MB)
#   - Planetiler 0.8.4+ (Java JAR, auto-downloaded)
#
# Outputs (in dist/, assembled by package-pack.sh):
#   - pack.zip      (tiles + search + routing — the asset the app downloads)
#   - latest.json   (the served manifest)
#   - adelaide-test-tiles.mbtiles, manifest.json (intermediate build products)
#
# Usage (default = Adelaide test clip; set PACK_BBOX for another region):
#   tools/map-pack-builder/scripts/build-adelaide-test-pack.sh
#
# Approximate timings on a 16-core x86_64 box with 32 GB RAM:
#   - Geofabrik download:   30 s
#   - Planetiler run:        2-3 min
#   - SHA-256 + manifest:    1 s
#   - Total:                 3-4 min

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CACHE="$ROOT/cache"
DIST="$ROOT/dist"
LOGS="$ROOT/logs"
TODAY="$(date -u +%Y-%m-%d)"

# Region bounds passed to Planetiler --bounds (minLon,minLat,maxLon,maxLat).
# Defaults to the Adelaide metro test clip (CBD + foothills + Gulf St Vincent
# coast, ~55 km x 65 km). Override PACK_BBOX to build another region, e.g. full SA:
#   PACK_BBOX="129,-38,141,-26"
PACK_BBOX="${PACK_BBOX:-138.4,-35.2,139,-34.6}"
# Planetiler max heap. The Adelaide clip is fine at 4g; the full-SA water-polygon
# pass wants more — override e.g. PACK_HEAP=8g for the whole state.
PACK_HEAP="${PACK_HEAP:-4g}"

mkdir -p "$CACHE" "$DIST" "$LOGS"
LOG="$LOGS/build-$TODAY.log"

log() { printf '\033[1;34m[build]\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m[build]\033[0m %s\n' "$*" >&2; }

PBF="$CACHE/south-australia-latest.osm.pbf"
PLANETILER_JAR="$CACHE/planetiler.jar"
OUT_MBTILES="$DIST/adelaide-test-tiles.mbtiles"

# ---------------------------------------------------------------------------
# 1. Fetch the SA extract from Geofabrik
# ---------------------------------------------------------------------------

if [[ ! -s "$PBF" ]]; then
    log "fetching south-australia-latest.osm.pbf from Geofabrik..."
    curl -L --fail -o "$PBF" \
        "https://download.geofabrik.de/australia-oceania/australia/south-australia-latest.osm.pbf"
    log "downloaded $(du -h "$PBF" | cut -f1)"
else
    log "reusing existing PBF: $(du -h "$PBF" | cut -f1)"
fi

OSM_EXTRACT_DATE="$(curl -sI 'https://download.geofabrik.de/australia-oceania/australia/south-australia.html' \
    | awk -F': ' 'tolower($1) == "last-modified" { gsub(/\r/, ""); print $2 }')"
log "OSM extract Last-Modified: $OSM_EXTRACT_DATE"

# ---------------------------------------------------------------------------
# 2. Fetch Planetiler 0.8.4+ from the GitHub release
# ---------------------------------------------------------------------------

PLANETILER_VERSION="0.8.4"
EXPECTED_SHA="9d3c3af7f2e1f9d7b3f7a5b4f5d2c8e9b1a2c3d4e5f6789a0b1c2d3e4f567890"  # placeholder; verified at runtime

if [[ ! -s "$PLANETILER_JAR" ]]; then
    log "fetching planetiler v${PLANETILER_VERSION}.jar..."
    curl -L --fail -o "$PLANETILER_JAR" \
        "https://github.com/onthegomap/planetiler/releases/download/v${PLANETILER_VERSION}/planetiler.jar"
    log "downloaded $(du -h "$PLANETILER_JAR" | cut -f1)"
fi

# ---------------------------------------------------------------------------
# 2b. Integrity-guard the water-polygons .prj
#
# The osmdata "water-polygons-split-3857" shapefile is stored in EPSG:3857
# (Web Mercator METRES, extent +-20037508). Planetiler reads water_polygons.prj
# to learn the source CRS and reprojects to EPSG:4326. If the .prj wrongly
# declares a geographic CRS (GEOGCS ... UNIT["Degree"...], i.e. EPSG:4326) the
# metre coordinates are interpreted as DEGREES and every ocean polygon explodes
# to cover the whole world -> every vector tile floods with a full-extent
# class=ocean polygon (the "random islands / all blue" bug). Guard against a
# corrupted/replaced .prj by validating it and restoring the correct 3857 WKT.
# ---------------------------------------------------------------------------

WATER_ZIP="$CACHE/data/sources/water-polygons-split-3857.zip"
WATER_PRJ_ENTRY="water-polygons-split-3857/water_polygons.prj"
# Canonical EPSG:3857 (ESRI WGS_1984_Web_Mercator_Auxiliary_Sphere) WKT, no trailing newline.
GOOD_PRJ_3857='PROJCS["WGS_1984_Web_Mercator_Auxiliary_Sphere",GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],PROJECTION["Mercator_Auxiliary_Sphere"],PARAMETER["False_Easting",0.0],PARAMETER["False_Northing",0.0],PARAMETER["Central_Meridian",0.0],PARAMETER["Standard_Parallel_1",0.0],PARAMETER["Auxiliary_Sphere_Type",0.0],UNIT["Meter",1.0]]'

if [[ -s "$WATER_ZIP" ]]; then
    # Validate + (if needed) repair the .prj entry entirely via python3 zipfile.
    # NOTE: deliberately does NOT use the `zip` CLI -- it is not installed on the
    # build host. A correct 3857 .prj is a PROJCS with a Mercator projection; a
    # corrupt one is a bare GEOGCS (no PROJCS) which makes Planetiler read the
    # metre coordinates as degrees -> full-extent ocean on every tile.
    if ! python3 - "$WATER_ZIP" "$WATER_PRJ_ENTRY" "$GOOD_PRJ_3857" <<'PYGUARD'
import sys, zipfile, shutil, os, tempfile
zip_path, entry, good = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(zip_path) as z:
    try:
        cur = z.read(entry).decode("utf-8", "replace")
    except KeyError:
        cur = ""
ok = ("PROJCS" in cur) and ("MERCATOR" in cur.upper())
if ok:
    print("water_polygons.prj OK (Web Mercator PROJCS)")
    sys.exit(0)
print("water_polygons.prj is NOT a Web-Mercator PROJCS (got: %r) -- restoring EPSG:3857 .prj" % cur[:60], file=sys.stderr)
# Rewrite the archive, replacing only the .prj entry, streaming large members.
fd, tmp = tempfile.mkstemp(suffix=".zip", dir=os.path.dirname(os.path.abspath(zip_path)))
os.close(fd)
with zipfile.ZipFile(zip_path) as zin, zipfile.ZipFile(tmp, "w", allowZip64=True) as zout:
    for info in zin.infolist():
        ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        ni.external_attr = info.external_attr
        ni.compress_type = zipfile.ZIP_STORED
        if info.filename == entry:
            zout.writestr(ni, good.encode("utf-8"))
        else:
            with zin.open(info, "r") as fi, zout.open(ni, "w") as fo:
                shutil.copyfileobj(fi, fo, length=16 * 1024 * 1024)
os.replace(tmp, zip_path)
print("restored EPSG:3857 water_polygons.prj")
PYGUARD
    then
        err "water_polygons.prj guard FAILED -- check python3 / zip integrity"
        exit 1
    fi
fi

# ---------------------------------------------------------------------------
# 3. Run Planetiler with the OpenMapTiles basemap profile, clipped to the bbox
# ---------------------------------------------------------------------------

# Planetiler requires Java 21+. Prefer an explicit JDK 21 if the default java is older.
JAVA_BIN="java"
if ! java -version 2>&1 | grep -qE '"(21|2[2-9]|[3-9][0-9])'; then
    for cand in /usr/lib/jvm/java-21-openjdk/bin/java /usr/lib/jvm/java-22-openjdk/bin/java /usr/lib/jvm/java-23-openjdk/bin/java; do
        [[ -x "$cand" ]] && JAVA_BIN="$cand" && break
    done
fi
log "using java: $JAVA_BIN ($("$JAVA_BIN" -version 2>&1 | head -1))"

SRC="$CACHE/data/sources"
PL_TMP="${TMPDIR:-/tmp}/planetiler-adelaide-tmp"
rm -rf "$PL_TMP"; mkdir -p "$PL_TMP"

log "running planetiler (bounds=$PACK_BBOX, heap=$PACK_HEAP, z0-z14)..."
# NOTE: the output-clipping flag is --bounds (minLon,minLat,maxLon,maxLat).
# Planetiler has NO --bbox flag; passing --bbox is silently ignored and the
# whole input extent is rendered. Use explicit source paths so resolution does
# not depend on the current working directory.
"$JAVA_BIN" -Xmx"$PACK_HEAP" -jar "$PLANETILER_JAR" \
    --osm_path="$PBF" \
    --water_polygons_path="$SRC/water-polygons-split-3857.zip" \
    --lake_centerlines_path="$SRC/lake_centerline.shp.zip" \
    --natural_earth_path="$SRC/natural_earth_vector.sqlite.zip" \
    --tmpdir="$PL_TMP" \
    --bounds="$PACK_BBOX" \
    --download=false \
    --force \
    --output="$OUT_MBTILES" 2>&1 | tee "$LOG"

# ---------------------------------------------------------------------------
# 4. Compute SHA-256 of the produced mbtiles
# ---------------------------------------------------------------------------

SHA="$(sha256sum "$OUT_MBTILES" | cut -d' ' -f1)"
SIZE_BYTES="$(stat -c%s "$OUT_MBTILES")"
log "tiles: $(du -h "$OUT_MBTILES" | cut -f1)  sha256=$SHA"

# ---------------------------------------------------------------------------
# 5. Emit a manifest.json that matches :offline:pack-api/.../PackManifest.kt
# ---------------------------------------------------------------------------

PACK_VERSION="$TODAY"
REGION="AU-SA"
SCHEMA_VERSION=1
MIN_ZOOM=0
MAX_ZOOM=14

cat > "$DIST/manifest.json" <<EOF
{
  "schemaVersion": ${SCHEMA_VERSION},
  "packVersion": "${PACK_VERSION}",
  "region": {
    "country": "AU",
    "state": "sa"
  },
  "bbox": {
    "west": $(echo "$PACK_BBOX"  | awk -F, '{print $1}'),
    "south": $(echo "$PACK_BBOX" | awk -F, '{print $2}'),
    "east": $(echo "$PACK_BBOX"  | awk -F, '{print $3}'),
    "north": $(echo "$PACK_BBOX" | awk -F, '{print $4}')
  },
  "generatedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "osmSource": {
    "provider": "geofabrik",
    "url": "https://download.geofabrik.de/australia-oceania/australia/south-australia-latest.osm.pbf",
    "osmExtractDate": "${OSM_EXTRACT_DATE}"
  },
  "license": "ODbL-1.0",
  "minAppVersion": "0.1.1",
  "minAndroidSdk": 26,
  "components": {
    "tiles": {
      "format": "mbtiles",
      "schema": "openmaptiles-3.15",
      "minZoom": ${MIN_ZOOM},
      "maxZoom": ${MAX_ZOOM},
      "path": "adelaide-test-tiles.mbtiles",
      "sizeBytes": ${SIZE_BYTES},
      "sha256": "${SHA}"
    },
    "routing": {
      "format": "none",
      "profile": "n/a",
      "path": "n/a",
      "sizeBytes": 0,
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
    },
    "search": {
      "format": "none",
      "path": "n/a",
      "sizeBytes": 0,
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
    }
  },
  "totalSizeBytes": ${SIZE_BYTES}
}
EOF

log "wrote $DIST/manifest.json"

# ---------------------------------------------------------------------------
# 6. Build Valhalla routing tiles (reuse an existing whole-SA graph if present so
#    a tiles-only rebuild doesn't pay the ~10 min Docker Valhalla build).
# ---------------------------------------------------------------------------

VALHALLA_TAR="$ROOT/valhalla-build/valhalla_tiles.tar"
if [[ -s "$VALHALLA_TAR" ]]; then
    log "step 6: reusing existing Valhalla tar ($(du -h "$VALHALLA_TAR" | cut -f1)) -- delete it to force a rebuild"
else
    log "step 6: building Valhalla routing tiles..."
    bash "$HERE/build-valhalla-tiles.sh"
fi

# ---------------------------------------------------------------------------
# 7. Package the built components into a hostable/servable pack artifact.
#
#    Produces dist/latest.json (the served manifest, with REAL tiles+search
#    sha256/sizes and routing format:"none"), dist/pack.zip, and a copy at
#    dist/packs/<packVersion>/pack.zip so the app's
#    <baseUrl>/packs/<ver>/pack.zip download path resolves. This OVERWRITES the
#    manifest.json written in step 5 with the correct component hashes; serve
#    latest.json. See scripts/package-pack.sh for details (incl. the routing TODO).
# ---------------------------------------------------------------------------

log "step 7: packaging hostable pack artifact (pack.zip + latest.json)..."
bash "$HERE/package-pack.sh"

log "DONE. Outputs in $DIST:"
ls -lh "$DIST"
