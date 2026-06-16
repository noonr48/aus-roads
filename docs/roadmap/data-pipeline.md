# aus-roads — Data Pipeline Extensions

Pack-builder, `search.db`, and manifest work required by the roadmap. Source lives in `tools/map-pack-builder/` (Planetiler vector tiles + Valhalla tile builder + the FTS5 search index builder). This document is the **pipeline-side** companion to `feature-specs.md`.

**Governing principle:** keep additions **inside existing pack components** wherever possible. A new top-level component forces a **manifest schema bump** and a download-path change — high blast radius against installed packs. Adding columns/tables **inside `search.db`** does **not** bump the manifest (the consumer just queries new columns), so prefer it.

## Pipeline as it exists today (baseline — do not re-spec)

| Component | Built by | Format | In manifest |
|-----------|----------|--------|-------------|
| Vector map tiles | Planetiler (custom 5-layer profile) | `.mbtiles`, OpenMapTiles-ish, z0–z14 | yes (v1) |
| Routing tiles | Valhalla tile builder | Valhalla graph tiles | yes (v1) |
| Search index | `osm-to-search-csv.py` → `build-search-index.py` | `search.db` — FTS5 `search_index` + `search_meta(id, name, kind, class, lat, lon)` | yes (v1) |

`search_meta` already indexes, **for all of SA**, named features with `kind ∈ {road, poi, water, suburb, park}` and `class` = the raw OSM tag value. Confirmed POI inventory (drives features 1/11/15, **no pipeline change**): `amenity=fuel ×534`, `amenity=hospital ×92`, `clinic ×105`, `toilets ×118`, `tourism=camp_site ×891`, `caravan_site ×175`, plus `charging_station`, `pharmacy`, `fire_station`.

Relevant scripts (`tools/map-pack-builder/scripts/`): `osm-to-search-csv.py`, `build-search-index.sh`, `build-valhalla-tiles.sh`, `build-adelaide-test-pack.sh`.

---

## 1. `maxspeed` osmium pass → into `search.db`  ·  Feature 3 (P3)

**Goal.** Give `SpeedLimitMonitor` real posted limits. Speed limits are **not** available from Valhalla at runtime (no posted speed on `RouteManeuver`; `trace_attributes` unbridged — see `valhalla-capabilities.md` §3). So bake a road→`maxspeed` lookup into the pack.

**Approach (no manifest bump).** Add an osmium pass over the same SA PBF the search/Valhalla builds already consume, emitting a table **inside `search.db`**:

```sql
CREATE TABLE road_maxspeed (
  way_id     INTEGER,         -- OSM way id (for debugging/joins)
  name       TEXT,            -- road name (nullable)
  maxspeed_kph INTEGER,       -- normalised to km/h
  min_lat REAL, min_lon REAL, -- segment bbox for nearest-segment lookup
  max_lat REAL, max_lon REAL,
  -- optionally a coarse geometry key / s2 cell for fast spatial pre-filter
);
CREATE INDEX idx_maxspeed_bbox ON road_maxspeed(min_lat, max_lat, min_lon, max_lon);
```

**Normalisation rules (the fiddly part).**
- `maxspeed=60` → 60 km/h; `maxspeed=60 mph` → convert; `maxspeed=AU:urban`/`AU:rural` implicit defaults → map to AU conventional values; drop/ignore unparseable.
- Only highway types that carry traffic (skip `footway`, `cycleway`, etc.).
- Keep the table keyed for a **nearest-road-segment** query that is **heading-aware** (the consumer passes heading to disambiguate parallel roads) — store per-segment bbox, not just per-way.

**Consumer.** `offline:search` gains `postedLimitNear(lat, lon, heading)` reading `road_maxspeed` (nearest segment). The monitor ships first (P1/P2) and runs dormant/manual until this data exists.

**Why inside `search.db` and not a new component:** no manifest schema change, no download-path change, reuses the FTS DB the app already opens. Pack size grows modestly (a compact indexed table).

**Effort: M.** osmium pass + normalisation table (most of the work is `maxspeed` value normalisation), one new table + index in the builder, a consumer query. **No manifest bump.** Caveat: without map-matching (`trace_attributes`, P4) the nearest-segment match can pick a parallel road — heading-aware selection mitigates; the principled fix is P4.

---

## 2. Richer POI columns (opening_hours, finer categories)  ·  Feature 15 (P3)

**Goal.** The basic POI browser ships **now** on the existing `search_meta.class`. P3 enriches it.

**Approach (no manifest bump — extend `search_meta` or a sibling table).** Extend the search builder to also emit:

- `opening_hours TEXT` — raw OSM `opening_hours` (parsed in-app; do **not** try to pre-evaluate "open now" in the pipeline).
- A normalised `category`/`subcategory` so the UI can group cleanly (e.g. `fuel`, `food`, `accommodation`, `health`, `toilets`, `camping`) mapped from the raw OSM `class` value, rather than every consumer re-deriving it.
- Optionally `phone`, `brand`, `fuel:diesel`/`fuel:lpg` style flags for fuel (useful in the outback — "does this servo have diesel?").

Add as columns on `search_meta` (or a `poi_extra(id, opening_hours, category, …)` joined by `id`). Either way: **inside `search.db`, no manifest bump.**

**Consumer.** `browseByClass` (and a future "open now" filter) reads the new columns; `opening_hours` parsing is a pure-Kotlin function in `core:geo`/`offline:search` (unit-testable).

**Effort: M.** Builder changes to emit the extra tags + a category mapping table; in-app `opening_hours` parser (fiddly OSM syntax). **No manifest bump.** Risk: `opening_hours` coverage/quality in regional OSM is uneven — feature must degrade when absent.

---

## 3. Topo / DEM raster pipeline  ·  Feature 14 (P5)  ·  the big one

**Goal.** A terrain layer (hillshade, optionally contours) under the vector map. There is **no DEM/elevation source in the pipeline today** — this is a net-new component and the only roadmap item that forces a manifest bump.

**Approach.**

1. **Source a DEM** for Australia (open elevation data; resolution and licensing TBD in a design review — must be redistributable in an offline pack).
2. **Process to tiles:**
   - **Hillshade:** DEM → reproject → render hillshade → raster tiles (or ship terrain-RGB raster-DEM tiles and let MapLibre compute hillshade client-side).
   - **Contours (optional):** DEM → contour vectorisation → vector tiles (`gdal_contour` → tile).
3. **New pack component** `terrain` (raster `.mbtiles` and/or contour `.mbtiles`), shipped **separately/optionally** — terrain is large; it must not bloat the base pack.
4. **Manifest schema v2** (see §4) to declare the optional `terrain` component (URL, size, checksum, coverage bbox).
5. **App:** `data:pack`/`offline:pack-api` learn the new component; pack-downloader fetches it (resumable); a MapLibre `raster-dem`/`hillshade` (or raster) layer is added under the vector layers and toggled in UI.

**Effort: XL.** DEM sourcing + raster pipeline + new component + manifest v2 + downloader change + MapLibre rendering + the v1→v2 migration. Sequenced **last** so it can't block anything. **Must go through a manifest-v2 + DEM-licensing design review before any code.**

**Risks:** DEM licensing/coverage for AU; raster tile size (storage + download cost — the moat is offline, so this lands on-device and must stay reasonable); manifest-v2 migration against installed v1 packs; hillshade/contour visual tuning.

---

## 4. Manifest schema v2 considerations  ·  enables Feature 14

Today's manifest (v1) enumerates the base components (vector tiles, Valhalla tiles, `search.db`). **Anything that adds a new top-level downloadable component needs v2.** Note that **§1 and §2 deliberately avoid this** by living inside `search.db`; **only §3 (terrain) requires v2.**

**v2 design requirements:**

- **Backward compatible / additive:** a v1 pack must still load on a v2-aware app; the new `terrain` component is **optional**. Conversely, design so a v2 manifest degrades sanely on an older app (ignore unknown components) or gate by an explicit `schemaVersion` the app checks.
- **Per-component metadata:** `schemaVersion`, then per component: `id`, `kind` (`vector|routing|search|terrain`), `url`, `bytes`, `checksum`, `coverageBbox`, `optional: bool`.
- **Independent install/update:** components version and download independently (terrain updates without re-downloading the base map).
- **Coverage bbox per component:** so the app knows terrain covers the same SA extent (and can extend per-state later).

**Effort for the schema work itself: M** (parse/serialise + v1↔v2 compatibility logic — this part is **pure-Kotlin and high-value to unit-test**, see feature 14's test plan). The expensive part is the terrain data (§3), not the manifest.

---

## Effort summary

| Item | Feature | Phase | Manifest bump? | Effort | Where the cost is |
|------|---------|-------|----------------|--------|-------------------|
| `maxspeed` osmium pass → `search.db` | 3 | P3 | **No** | **M** | `maxspeed` value normalisation; heading-aware nearest-segment consumer |
| Richer POI columns (`opening_hours`, category) | 15 | P3 | **No** | **M** | builder tag emission + `opening_hours` parser |
| Topo / DEM raster pipeline | 14 | P5 | **Yes (v2)** | **XL** | DEM sourcing/licensing, raster tiles, downloader + rendering |
| Manifest schema v2 | 14 (enabler) | P5 | — | **M** | v1↔v2 compatibility (unit-testable) |

**Strategic ordering:** do the two in-`search.db` additions (§1, §2) in P3 — cheap, no manifest risk, immediate feature value. Defer the terrain pipeline + manifest v2 (§3, §4) to P5 behind a design review, because it is the only piece that touches the manifest contract and the only XL.
