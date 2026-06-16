# aus-roads map-pack builder

Tools to produce a downloadable South Australia map pack for the aus-roads
Android app. See `/home/benbi/Apps/aus-roads/docs/adr/0003-map-pack-format.md`
for the full pipeline architecture and `/home/benbi/Apps/aus-roads/docs/adr/0004-provider-abstraction.md`
for the data layer that consumes the produced pack.

## What this directory produces

Two pipelines live here:

1. **`scripts/build-adelaide-test-pack.sh`** — produces a small (~20 MB) test
   pack clipped to the Adelaide metropolitan bounding box. Used during v0.1.x
   development to verify the in-app map renderer without the cost of building
   the full state pack.

2. (future) **`scripts/build-sa-pack.sh`** — produces the full South Australia
   pack (~150-300 MB tiles + 150-250 MB valhalla routing tiles + 50-150 MB
   SQLite FTS5 search index). Runs on a 16-core x86_64 box in ~30 min; targets
   GitHub Actions weekly cron.

The full state pipeline is deferred to v0.1.2 because it requires
`valhalla_build_tiles` (native) and a custom SQLite FTS5 builder. For v0.1.x
the test pack is sufficient to demo the renderer.

## Quickstart (test pack)

```bash
cd /home/benbi/Apps/aus-roads/tools/map-pack-builder
./scripts/build-adelaide-test-pack.sh
```

This downloads the Geofabrik SA PBF (~62 MB) and Planetiler v0.8.4 (~89 MB)
into `cache/`, then produces `dist/adelaide-test-tiles.mbtiles` (~19 MB).

## Outputs

- `dist/adelaide-test-tiles.mbtiles` — 19 MB vector tile pyramid z0–z14,
  covering Adelaide metro (138.55°E–138.70°E, 35.00°S–34.85°S).
- `dist/style.json` — MapLibre style spec with the OpenMapTiles layers we care
  about: roads, water, parks, buildings, road names, suburb names, POIs.
- `dist/manifest.json` — manifest matching
  `android/offline/pack-api/.../PackManifest.kt`. SHA-256 of the tiles is
  computed at build time so the in-app downloader can verify the download.

## Bundling the test pack into the app (v0.1.x only)

For the first 1-2 builds while the in-app pack downloader is not yet
implemented, the test pack is bundled into the app at:

```
android/app/src/main/assets/maptile/
  adelaide-test-tiles.mbtiles
  style.json
```

The MapLibre MapView loads via `asset://` URLs (see
`/home/benbi/Apps/aus-roads/docs/adr/0007-maplibre-android-integration.md`
for the wiring).

## Adelaide bbox reasoning

The test pack covers a 20 km × 20 km box centered roughly on the Adelaide
CBD — north of the airport, south of the city centre, west of the hills, east
of the parklands. This is enough to demo every layer in the OpenMapTiles
schema (roads, water, parks, buildings, POIs, place names) at zoom 12–14
without bloating the file past 20 MB.

## Reproducing the build from scratch

The full reproducibility chain is:

1. Geofabrik SA PBF (south-australia-latest.osm.pbf) — daily, CC-BY ODbL.
2. Planetiler v0.8.4 (https://github.com/onthegomap/planetiler) — MIT.
3. OpenMapTiles schema v3.15 (https://github.com/openmaptiles/openmaptiles) — CC-BY.

The `cache/` directory is intentionally git-ignored. To rebuild from a clean
state:

```bash
rm -rf cache dist
./scripts/build-adelaide-test-pack.sh
```

## Disk footprint

- Planetiler JAR: ~89 MB (one-time cache)
- SA PBF: ~62 MB (one-time cache)
- Adelaide tiles: ~19 MB (dist artifact)
- Total: ~170 MB transient, ~19 MB persistent.

## Known limitations

1. **No routing graph.** v0.1.x ships tiles only. Valhalla routing tiles are
   a v0.4 concern.
2. **No search index.** v0.1.x ships tiles only. SQLite FTS5 search is a
   v0.1.1 concern.
3. **OpenMapTiles demo profile.** The `--area=monaco` profile is verbose
   (5+ layers for the same features). A custom 5-layer profile would halve
   the MBTiles; deferred to v0.2 cleanup per ADR 0003.
4. **No raster hillshade.** Vector-only. Hillshade is a v0.2 polish item.

## Tools required

- `curl` (to fetch the PBF and Planetiler)
- `java` 17+ (to run Planetiler)
- `sqlite3` (to inspect the produced MBTiles and to wire the test into CI)
- `sha256sum` (manifest verification)
- ~4 GB free RAM during the build (Planetiler is heap-hungry)
- ~170 MB free disk for the build

The production SA pack also needs `docker` (for the OSRM/Valhalla container
chain), but the test pack does not.
