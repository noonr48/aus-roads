# aus-roads — Long-Term Product Roadmap

**Status:** Planning baseline for the post-v1.0 feature program (the "17 features").
**Audience:** Implementers. These are working specs, not marketing.
**Scope:** This roadmap covers net-new capability layered onto the shipped v1.0 app. It does **not** re-spec what already exists.

Companion documents:

- [`feature-specs.md`](./feature-specs.md) — full spec per feature (user story, feasibility, modules, API, acceptance, tests, on-device checklist, risk).
- [`data-pipeline.md`](./data-pipeline.md) — pack-builder / `search.db` / manifest extensions and effort.
- [`valhalla-capabilities.md`](./valhalla-capabilities.md) — the `valhalla-mobile 0.1.6` JNI ceiling, costing options available now, lib-bump unlocks, DO-NOT-RETRY.

---

## 1. Vision

aus-roads is a **privacy-first, offline-first** map and turn-by-turn navigation app for South Australia, built to extend across other Australian states. It is for the regional and outback traveller who drives where mobile coverage is absent and where the big map apps degrade to a blank grid.

The product already ships (v1.0): offline vector map + search + pins, Valhalla offline routing, turn-by-turn navigation (TTS, off-route reroute, ETA), SA/NSW/VIC + outback traffic overlays, route history, settings, a Glance home-screen widget, Android Auto, BYOK traffic keys, and a pack downloader. The 17-feature program deepens the product **along its moat**, not toward feature parity with Google/Apple Maps.

## 2. The Moat — read this before proposing anything

> **The competitive advantage is OFFLINE × OUTBACK/REMOTE-AU × PRIVACY.**
> That is the exact intersection where Google Maps and Apple Maps are weakest. Every feature on this roadmap must defend that intersection.

**Non-negotiable design rules. A feature that violates any of these does not ship:**

1. **Works offline.** The feature must be fully functional with the device in airplane mode, using only on-device packs (map tiles, Valhalla tiles, `search.db`). If a feature *can* use the network (e.g. live traffic on the `withNetwork` flavor), the offline path must still deliver the core value.
2. **Respects privacy.** No accounts. No analytics. No telemetry. No cloud round-trips for core function. No background location exfiltration. Location and routing stay on the device.
3. **Earns its place in the outback.** Prefer capability that matters when you are 300 km from a town with no signal: fuel range, coordinates for emergency services, track recording, trip sharing over SMS, unsealed-road routing. De-prioritise capability that only matters in a connected city, where the incumbents already win.

**Do NOT chase Maps parity.** Lane guidance, junction view, and POI browse appear on this roadmap only because they have an *offline* implementation that strengthens the core nav/value loop — not because the incumbents have them. If a proposed feature's only justification is "Google has it," it is out of scope.

### Reference apps to emulate

| App | What we take from it |
|-----|----------------------|
| **OsmAnd** | Offline routing depth, costing/profile control, track recording, the "everything works without signal" ethos. |
| **Organic Maps** | Privacy-first stance (no accounts/tracking), lean offline UX, fast vector map. |
| **Gaia GPS** | Outdoor/topo layers, GPX workflows, coordinate readout, the off-road traveller's mental model. |

## 3. Two flavors (existing — do not change the contract)

| Flavor | Permissions | Meaning for new features |
|--------|-------------|--------------------------|
| `offline` | **No** `INTERNET`, **no** `LOCATION` | Hardest constraint. Pure-data and pure-logic features (coordinates, measure, GPX import, POI browse, fuel *planner* math) must run here. Anything needing GPS or SMS degrades gracefully or is hidden. |
| `withNetwork` | `LOCATION` (GPS) + `INTERNET` (live traffic) | Adds the GPS-driven features (track recording, speed alert, trip computer, geofence, share-my-location) and live traffic. INTERNET is for traffic only — never for core map/route/search. |

A feature spec must state which flavor(s) it targets and how it behaves in the more restricted one.

## 4. The 5-Phase Plan

Phasing is driven by **feasibility**, not by feature tier. The single biggest gate is the Valhalla JNI ceiling (see §6 and `valhalla-capabilities.md`): only `route()` is bridged to Kotlin in `valhalla-mobile 0.1.6`. That blocks the on-map fuel-range isochrone, matrix, elevation, and map-matching until the native library is bumped — so those land late, regardless of how desirable they are.

| Phase | Theme | Gate / precondition | What lands |
|-------|-------|---------------------|------------|
| **P1** | **Buildable-now cores** | None. Pure Kotlin + existing `search.db` + existing Valhalla `route()` + existing costing fields. | The geo/logic foundation: coordinate formatting, SunCalc, measure geometry, GPX, fuel-range math, the speed/trip/proximity/share **state machines**, routing-request building. Unit-testable on the JVM today. |
| **P2** | **Device integration & UI** | P1 logic merged. Requires on-device verification (GPS, services, SMS, MapLibre, Room runtime). | Wire P1 logic to real Android: foreground track recorder, live speed monitor, trip computer HUD, proximity alerts, trip-share/overdue (SMS + alarms), Compose UI, MapLibre overlays, Room DAO runtime. |
| **P3** | **Data-pipeline features** | Pack-builder additions (no Valhalla change). | Speed-limit **data** (osmium `maxspeed` pass into `search.db`), richer POI columns (opening hours, categories) powering categorised POI browse. The *monitors* shipped in P2; P3 feeds them real data. |
| **P4** | **Library-bump features** | **Blocked** until `valhalla-mobile` exposes more JNI symbols. | The on-map fuel-range **isochrone ring**, matrix (true multi-stop optimisation), elevation/height (climb profiles), trace_attributes (map-matching, snapped speed). |
| **P5** | **Major content layer** | New raster-tile pipeline + manifest schema v2 + MapLibre hillshade. | Topo / hillshade base layer. Largest single effort; isolated to the end so it cannot block the rest. |

**Sequencing principle:** ship value early (P1/P2 deliver most of the 17 features), defer the two hard dependencies (library bump → P4, raster pipeline → P5) to the end, and never let a late dependency block an early feature.

## 5. Feature × Phase matrix

Tier = the product grouping (T1 ship-first … T4 content). Phase = when it actually lands given feasibility.

| # | Feature | Tier | Phase | Feasibility | Primary blocker (if any) |
|---|---------|------|-------|-------------|--------------------------|
| 1 | Fuel/servo planner + range warning | T1 | **P1** logic → **P2** UI; **P4** for the isochrone ring | buildable-now (planner) → needs-lib-bump (ring) | Isochrone ring needs Valhalla JNI bump |
| 2 | Track recording + GPX import/export | T1 | **P1** (GPX + model) → **P2** (recorder service) | buildable-now | — |
| 3 | Speed limit + over-speed alert | T1 | **P1/P2** (monitor) → **P3** (limit data) | buildable-now (alert) → needs-data (limits) | `maxspeed` osmium pass into `search.db` |
| 4 | Offline trip-share / overdue check-in (SMS) | T1 | **P1** (composer/tracker) → **P2** (SMS + alarms) | buildable-now | — |
| 5 | Multi-stop waypoints | T2 | **P1/P2** (sequential `via`) → **P4** (matrix optimise) | buildable-now (ordered) → needs-lib-bump (reorder) | Optimal reordering needs matrix |
| 6 | Avoid tolls / unsealed / ferries | T2 | **P1** (RouteOptions) → **P2** (UI) | buildable-now | Costing fields already exposed |
| 7 | Sealed-vs-unsealed / 4WD profile | T2 | **P1** (costing wiring) → **P2** (UI) | buildable-now (approximation) | No explicit 4WD flag — approximated |
| 8 | Lane guidance / junction view | T2 | **P2** | buildable-now | `LaneInfo` already on `Maneuver` |
| 9 | Favourite trips (Home/Work + named) | T2 | **P1** (model/store) → **P2** (UI) | buildable-now | — |
| 10 | Trip computer (moving time, avg/max speed, daylight) | T2 | **P1** (logic) → **P2** (HUD) | buildable-now | SunCalc is pure Kotlin |
| 11 | Coordinate readout + emergency card | T3 | **P1** (formatter) → **P2** (card UI) | buildable-now | `search.db` has hospital/police; MGRS in `core:geo` |
| 12 | Geofence / proximity pin alerts | T3 | **P1** (engine) → **P2** (service) | buildable-now | — |
| 13 | Compass / altitude HUD + driving mode | T3 | **P2** | buildable-now (baro/GPS alt) | Elevation-from-route is P4; sensors are P2 |
| 14 | Topo / hillshade layer | T4 | **P5** | major | DEM raster pipeline + manifest v2 |
| 15 | Categorised POI browse | T4 | **P1/P2** (current `search.db`) → **P3** (richer columns) | buildable-now → needs-data | Opening hours / finer categories need pipeline |
| 16 | Measure distance / area | T4 | **P1** (geometry) → **P2** (map tool) | buildable-now | `MeasureGeometry` is pure Kotlin |
| 17 | Share location pin + accept `geo:` URIs | T4 | **P1** (parse/format) → **P2** (intents) | buildable-now | Android intent filters in P2 |

**Reading the matrix:** 14 of 17 features have their core logic in **P1 (buildable now)**. Only **#14 (topo)** is genuinely late-gated by a new pipeline; only the *enhancements* to **#1, #3, #5, #15** are gated by the library bump or a data pass. The roadmap front-loads value accordingly.

## 6. Hard constraints that drive phasing

These are research verdicts. Treat them as facts; the specs are built around them. Full detail in `valhalla-capabilities.md` and `data-pipeline.md`.

- **Valhalla 0.1.6 JNI ceiling — only `route()` is bridged.** `isochrone`, `matrix`, `trace_attributes`, `locate`, and `height` (elevation) are *modeled* in `valhalla-models 0.0.9` but are **not callable** — there is no JNI symbol. Consequence: the fuel feature ships as a **servo planner** (nearest servo via `route()` distance + a range-math model), and the **on-map isochrone "range ring" is deferred to P4** behind a library bump. Matrix-based multi-stop optimisation and route-derived elevation are likewise P4.
- **Costing options are fully available now.** `AutoCostingOptions` exposes `useTolls`, `useFerry`, `useTracks`, `useLivingStreets`, `useHighways` (Doubles 0.0–1.0), `topSpeed`, and `shortest`. Features **6** and **7** are buildable now by setting these. There is **no explicit 4WD boolean** — "avoid unsealed / prefer sealed" is approximated via low `useTracks` + high `useHighways`/`useLivingStreets`.
- **Speed limit is not in the route response.** `RouteManeuver` carries no posted speed and `trace_attributes` is unbridged. The over-speed **alert logic** (GPS speed vs limit + hysteresis) is buildable now; the **limit data** needs a modest pack-pipeline addition (an osmium pass emitting a road→`maxspeed` table **into the existing `search.db`** — no manifest schema bump if it stays inside `search.db`). Phase the monitor (P1/P2) before the data (P3).
- **The pack already carries POIs.** `search.db`'s `search_meta(name, kind, class, lat, lon)` already covers **all of SA**: `amenity=fuel ×534`, `amenity=hospital ×92`, `clinic ×105`, `toilets ×118`, `tourism=camp_site ×891`, `caravan_site ×175`, plus `charging_station`, `pharmacy`, `fire_station`. So features **1 / 11 / 15** are reachable **now** with a consumer query (`SELECT … FROM search_meta WHERE class = ?`) — **no pipeline change**. `kind ∈ {road, poi, water, suburb, park}`; POI `class` is the raw OSM `amenity=*` value.
- **Topo / hillshade is the major one.** There is no DEM / elevation source in the pipeline. It needs a raster-tile pipeline + a new pack component (manifest **v2**) + MapLibre hillshade rendering. Phase **last (P5)**.

## 7. Privacy & offline invariants

Every spec is checked against these. They are the product, not a setting.

| Invariant | Rule | How features comply |
|-----------|------|---------------------|
| **No accounts** | The app never asks who you are. | Favourites, tracks, trips, settings are all local (Room / DataStore / files). |
| **No telemetry** | Zero analytics, crash-reporting, or usage beacons. | No new SDKs that phone home. New deps are vetted in `tools/privacy-audit`. |
| **Offline core** | Map, route, search, and the core of every feature work in airplane mode. | `offline` flavor has no `INTERNET`. Features degrade, they don't break. |
| **Location stays local** | GPS never leaves the device unless the *user* explicitly sends it (e.g. share-my-location SMS, trip-share). | Track points → local Room. Share is a user-initiated `geo:` / SMS action with an explicit tap. |
| **Minimal permissions** | Only request what the feature needs, only on the flavor that needs it. | SMS permission (#4, #17) is requested at first use, `withNetwork` only. `offline` stays permission-light. |
| **No silent network** | The `withNetwork` flavor uses `INTERNET` for live traffic only. | Routing/search/map never call out. Traffic is BYOK and user-toggled. |

**Permission additions introduced by this roadmap (all `withNetwork` only, runtime-requested at first use):**

- `SEND_SMS` — features #4 (overdue check-in), #17 (share location). Optional; the feature offers a share-sheet fallback if denied.
- `SCHEDULE_EXACT_ALARM` / alarms — feature #4 overdue timer. Use inexact alarms where acceptable to avoid the special-access prompt.
- Body sensors are **not** required; compass/altitude (#13) use the standard sensor + GPS-altitude APIs, no dangerous permission.

## 8. Architecture being built in parallel

New modules (documented here, specced per feature). Module names follow the existing `group:module` convention (`au.com.ausroads.*`).

| Module | Type | Contents |
|--------|------|----------|
| `core:geo` | pure Kotlin (JVM-testable) | `CoordinateFormatter` (DMS / UTM / MGRS), `SunCalc`, `MeasureGeometry`, `Gpx` (GPX 1.1 read/write), `FuelRange` (range math). **No Android deps** → fully unit-testable. |
| `data:tracks` | Room | `Track` / `TrackPoint` entities, DAO, repository. Backs feature #2. |
| `feature:trip` | feature (logic + UI) | `SpeedLimitMonitor`, `TripComputer`, `ProximityEngine`, `TripShareComposer` + `OverdueTracker`, `TrackRecorder`, `FuelPlanner`. The state machines are pure; the services/UI are the device-verified layer. |

Extensions to existing modules:

| Module | Extension |
|--------|-----------|
| `routing:engine-api` | `RouteOptions` (the avoid/profile toggles) added to the request model. `RouteRequest` already carries `via`, `avoidPolygons`, `penalties`, `CostingProfile`, and `LaneInfo` on `Maneuver`. |
| `routing:engine-valhalla` | Costing wiring — map `RouteOptions` to `AutoCostingOptions` fields (`useTolls`/`useFerry`/`useTracks`/`useLivingStreets`/`useHighways`/`topSpeed`/`shortest`). |
| `offline:search` | POI category browse — `FtsSearchRepository` already does `WHERE s.kind = ?`; add a `browseByClass(class, …)` path over `search_meta` for the POI browser. |

The dependency rule stays: `feature:*` → `data:*` / `routing:*` / `offline:*` → `core:*`. `core:geo` depends on nothing Android.

## 9. Verification reality (read before estimating "done")

**The dev box has no Android emulator, and `sloane-tools` is sandboxed away from this repo.** CI therefore cannot run the app. CI verifies via **compile + JVM unit tests + detekt + code-review** only. That hard-splits every feature into two halves:

| Verifiable in CI (pure logic) | Requires on-device verification |
|-------------------------------|----------------------------------|
| Coordinate math (DMS/UTM/MGRS round-trips), SunCalc, `MeasureGeometry`, `FuelRange` | Compose UI rendering and interaction |
| GPX 1.1 read/write round-trips, malformed-input handling | Foreground services (track recorder, speed monitor, overdue timer) |
| Speed / trip-computer / proximity / share **state machines** | Real GPS fixes, sensor (compass/baro) input |
| Routing-**request** building (`RouteOptions` → costing options mapping) | SMS send + exact/inexact alarm firing |
| Repository SQL **construction** (query strings, args) | MapLibre rendering (overlays, hillshade, junction view) |
| | Room **DAO at runtime** (migrations, queries against a real DB) |
| | `geo:` intent filters / share-sheet round-trips |

**Implication for the plan:** P1 is the "CI-green" phase — it is where most logic lands and where most of the test value is. P2 is where on-device verification becomes mandatory and where a feature is only "done" after a human runs the device checklist.

**Every feature spec carries an explicit on-device verification checklist.** A feature is not shippable on green CI alone if it has a P2 (device) component — the checklist must be executed on hardware. CI green ⇒ "logic correct"; device checklist passed ⇒ "feature works".

### Standing CI gates (apply to every PR)

- `./gradlew :module:test` green for every touched module (JVM unit tests).
- `./gradlew detekt` clean (zero new findings; the repo is detekt-clean today).
- Compile across both flavors (`offline`, `withNetwork`) — a feature must not break the no-`INTERNET`/no-`LOCATION` build.
- `code-reviewer` pass on substantial diffs (reviewer ≠ author; findings cite evidence).
- `tools/privacy-audit` clean — no new permission or network call sneaks in unaudited.

---

## 10. Definition of done (per feature)

1. **Spec** in `feature-specs.md` is satisfied (user story + acceptance criteria).
2. **CI green:** unit tests for all pure logic, detekt clean, both flavors compile.
3. **Privacy:** no new telemetry; permissions justified and runtime-gated; `tools/privacy-audit` clean.
4. **Offline:** the offline path verified (airplane mode) for the core of the feature.
5. **On-device checklist** (from the spec) executed on hardware for any P2 component.
6. **Review:** `code-reviewer` pass on the diff.
7. **Memory:** decisions + failed approaches logged to the project tree (DO-NOT-RETRY preserved).
