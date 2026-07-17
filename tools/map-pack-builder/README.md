# aus-roads map-pack builder

Tools to produce a downloadable South Australia map pack for the aus-roads
Android app. See `/home/benbi/Apps/aus-roads/docs/adr/0003-map-pack-format.md`
for the full pipeline architecture and `/home/benbi/Apps/aus-roads/docs/adr/0004-provider-abstraction.md`
for the data layer that consumes the produced pack.

## What this directory produces

`dist/pack.zip` + `dist/latest.json` — the asset and manifest the aus-roads app
downloads. The pack bundles vector tiles, a SQLite FTS5 search index, and
(when a graph is present) a Valhalla routing graph.

`scripts/build-adelaide-test-pack.sh` is the region-parameterized entry point:
it runs Planetiler for the tiles, reuses (or builds) the Valhalla routing graph,
and calls `package-pack.sh` to assemble + self-verify the zip. The bbox is set
by `PACK_BBOX`:

- **Default — Adelaide test clip (~31 MB):**
  ```bash
  ./scripts/build-adelaide-test-pack.sh
  ```
- **Full South Australia (~155 MB):** whole-state tiles z0–14 + whole-SA search
  + a 105 MB Valhalla routing graph:
  ```bash
  PACK_BBOX="129,-38,141,-26" PACK_HEAP=8g ./scripts/build-adelaide-test-pack.sh
  ```

Helper scripts: `build-search-index.sh` produces `dist/search.db` (packaged when
present); `build-valhalla-tiles.sh` builds the routing graph via Docker;
`package-pack.sh` assembles the zip and includes the routing tar with
`routing.format:"valhalla"` whenever a graph is available, else `"none"`.
`build-bundled-demo-assets.sh` is the only supported way to refresh the map and
search databases checked into the APK.

## Quickstart (test pack)

```bash
cd /home/benbi/Apps/aus-roads/tools/map-pack-builder
./scripts/build-adelaide-test-pack.sh
```

This downloads the Geofabrik SA PBF (~62 MB) and Planetiler v0.8.4 (~89 MB)
into `cache/`, then produces `dist/adelaide-test-tiles.mbtiles` (~19 MB).

## Outputs

- `dist/adelaide-test-tiles.mbtiles` — vector tile pyramid z0–z14, covering the
  configured `PACK_BBOX` (default: 138.4°E–139.0°E, 35.2°S–34.6°S).
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
  adelaide-test-tiles.mbtiles.sha256
  adelaide-test-search-v1.db
  adelaide-test-search-v1.db.sha256
  style.json
```

Refresh the bundled databases with the canonical paired build:

```bash
./scripts/build-bundled-demo-assets.sh
```

That command rebuilds tiles and search from the same PBF and shared demo bbox,
then stages both and their checksums before replacing the app assets. The search
clip reads its bounds directly from the newly built MBTiles metadata. Each
adjacent `.sha256` contains the raw lowercase SHA-256 of its database. The whole
`maptile` directory is published with one Linux `renameat2(RENAME_EXCHANGE)`, so
an interrupt cannot expose mixed map/search generations. Do not copy a standalone
`dist/search.db` into the app: it may cover a different `PACK_BBOX`.

Synchronise the checksum-pinned Open Sans ranges used by bundled road and place
labels (including the extended Unicode ranges present in local names) with:

```bash
./scripts/sync-bundled-glyphs.sh
```

The MapLibre MapView loads via `asset://` URLs (see
`/home/benbi/Apps/aus-roads/docs/adr/0007-maplibre-android-integration.md`
for the wiring).

## Adelaide bbox reasoning

The test pack covers the Adelaide metro and surrounding coast/foothills at
`138.4,-35.2,139,-34.6`. The single shared value lives in
`scripts/bundled-demo-contract.sh`; the tile build and paired asset installer
both source it.

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

1. **OpenMapTiles demo profile.** The verbose default profile emits more layers
   than the app uses; a custom slim profile would shrink the MBTiles — deferred
   to v0.2 cleanup per ADR 0003.
2. **No raster hillshade.** Vector-only. Hillshade is a v0.2 polish item.

## Tools required

- `curl` (to fetch the PBF and Planetiler)
- `java` 17+ (to run Planetiler)
- `sqlite3` (to inspect the produced MBTiles and to wire the test into CI)
- `sha256sum` (manifest verification)
- ~4 GB free RAM during the build (Planetiler is heap-hungry)
- ~170 MB free disk for the build

The production SA pack also needs `docker` (for the OSRM/Valhalla container
chain), but the test pack does not.
