# ADR 0003 — Map pack format and pipeline

## Status

Accepted. 2026-06-01.

## Context

The offline map pack must:

- render crisp at high zoom,
- be small enough to download on cellular when the user opts in,
- cover the full South Australia state (~984,000 km²),
- support offline search and POI lookup,
- include a routing graph that we can later query with dynamic cost updates.

## Decision

- **Tile format**: vector tiles, single `.mbtiles` (OpenMapTiles schema, Planetiler
  basemap profile), z0–z14. No raster hillshade in v1.
- **Search index**: SQLite FTS5 (built from the same OSM extract). Defer Photon /
  Elasticsearch to v0.2+ if we need fuzzy match.
- **Routing graph**: Valhalla tiles, built with `valhalla_build_tiles` and bundled
  with the pack as `routing/valhalla_tiles.tar`.
- **OSM extract**: Geofabrik `south-australia-latest.osm.pbf` (~62 MB).
- **Delivery**: Cloudflare R2 primary, GitHub Releases mirror. R2 has zero egress
  fees (the cost driver for map packs).
- **Cadence**: GitHub Actions weekly cron (Sunday 02:00 ACST) plus `workflow_dispatch`
  for manual triggers. Fits in 30 min on the `ubuntu-latest-8-cores` runner.

## Manifest schema (v1, locked)

See `android/offline/pack-api/.../PackManifest.kt`. Key fields:

- `schemaVersion: Int = 1`
- `packVersion: String` — used as the install dir name
- `region: Region` — country + optional state
- `bbox: Bbox` — covers the full SA state
- `components: PackComponents` — tiles, routing, search with per-component sha256
- `signatures: Map<String, String>` — optional, future-proofed

## Pack download UX

- Discovery: `GET https://<cdn>/aus-roads/sa/latest.json` (no auth, ~1 KB, cached 1 h).
- Storage: `context.filesDir/mappacks/au-sa/vYYYYMMDD/`.
- Network policy: Wi-Fi only by default; cellular opt-in toggle.
- Resumable: HTTP Range + ETag. SHA-256 verify before extraction.

## Expected sizes (mid-2026)

| Artifact | Size |
|---|---|
| `tiles.mbtiles` z0–z14 | 150–300 MB |
| `valhalla_tiles.tar` | 150–250 MB |
| `search.db` | 50–150 MB |
| **Total unpacked** | **350–700 MB** |
| Total zipped | 200–400 MB |

## Consequences

- Any change to the tile format, pipeline, or manifest schema is a breaking change
  for installed apps. Lock the v1 schema; future versions must be backward-compatible
  or shipped with a migration.
- Planetiler basemap profile is verbose; a custom 5-layer profile (background,
  roads, water, pois, buildings) is a v0.2 cleanup that halves the MBTiles.
- OSM is ODbL-licensed. The app must display attribution. The pipeline must record
  the source PBF URL and extract date in the manifest.
