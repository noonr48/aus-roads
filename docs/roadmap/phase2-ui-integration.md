# aus-roads — Phase 2: UI + Device Integration Plan

**Goal.** Phase 1 shipped verified PURE logic — unit-tested, some of it device-tested on a real Pixel 9 — but **none of it is reachable from the app.** The app today has only three tabs (Map / Pins / Settings, plus an About route) and routing/navigation on the map. Phase 2 wires that proven logic to Compose UI, foreground services, sensors, and SMS/alarms so each capability becomes a feature a driver can actually use.

**Scope boundary.** This plan covers only logic that is **already built and verified** in `core:geo`, `data:tracks`, `feature:trip`, `routing:engine-*`, and `offline:search`. It does **not** add new pure logic, new pack components, or anything blocked on a `valhalla-mobile` JNI symbol (those are P3/P4/P5 in `feature-specs.md`).

**Source root.** All paths below are under `/home/benbi/Apps/aus-roads/android/` (the Gradle root is `android/`, **not** the repo root — note that when grepping). Package root: `au.com.ausroads`.

---

## 0. Ground truth — what Phase 1 actually left us

Verified before writing this plan (against the live tree, not the spec sketches). Where the shipped API differs from `feature-specs.md`, the **shipped** signature governs.

| Layer | Shipped symbol(s) | Path | Notes vs. spec |
|---|---|---|---|
| `core:geo` | `CoordinateFormatter.decimalDegrees() / dms() / utm(): Utm / mgrs(precision=5)` | `core/geo/.../CoordinateFormatter.kt` | **MGRS is present** (`mgrs(lat,lon,precision)`). **No `parse(text): GeoPoint`** — coordinate→text only; text→GeoPoint is a gap (see §2D). |
| `core:geo` | `SunCalc`, `MeasureGeometry`, `Gpx`, `FuelRange` | `core/geo/.../{SunCalc,MeasureGeometry,Gpx,FuelRange}.kt` | All pure, unit-tested. |
| `data:tracks` | `Track`, `TrackPoint`, `TrackDao`, `TrackDatabase`, `TrackRepository`/`RoomTrackRepository`, `TrackStats`, `RecordedPoint`, Hilt `TracksDataModule` | `data/tracks/.../` | Room store complete + DI module already provided. |
| `feature:trip` | `TrackRecorder`, `TripComputer`, `ProximityEngine`, `SpeedLimitMonitor`, `TripShareComposer` (`compose(...)`), `OverdueTracker` (`OverdueState`, `CheckIn`, `stateAt(...)`), `FuelPlanner` (`plan(...)`, `nearestFuelKmAlong(...)`, `FuelPlan`), Hilt `Module` | `feature/trip/.../` | **All eight present** in `src/main`. `TripShareComposer` formats text only — "the Android wiring (SMS intent, permission dance) is wired up later in the app layer." |
| `routing:engine-api` | `RouteRequest.options: RouteOptions`; `RouteOptions(avoidTolls, avoidUnsealed, avoidFerries)` | `routing/engine-api/.../RoutingEngine.kt` | **Three flags only** — not the wider `preferSealed/shortest/topSpeedKph` set the spec sketched. `avoidUnsealed` → low `use_tracks`. `RouteResult.warnings` exists. |
| `offline:search` | `SearchRepository.browseByCategory(cat, limit=200)`, `nearestByCategory(lat,lon,cat,limit=20,maxDistanceDegrees=0.5)`, `maxspeedNear(lat,lon,maxDistanceDegrees=0.05): Int?` | `offline/search/.../SearchRepository.kt` | `SearchResult(name, kind, className, latitude, longitude)`. `maxspeedNear` reads the `road_speed` table (49,493 SA rows) and **tolerates packs without it** (returns null). |
| `offline:search` | `PoiCategory` — `FUEL, EV_CHARGING, HOSPITAL(hospital+clinic), PHARMACY, POLICE, FIRE_STATION, TOILETS, DRINKING_WATER, CAMPING(camp_site+caravan_site), SUPERMARKET` | `offline/search/.../PoiCategory.kt` | 10 categories, OSM-class-backed. |
| `feature:navigation` | `NavigationLocationProvider` (Hilt `@Singleton`) → `locationUpdates(intervalMs): Flow<NavigationLocation>`; `NavigationOverlay`, `NavigationViewModel`, `NavigationState` | `feature/navigation/.../` | **Backed by Play Services `FusedLocationProviderClient`** — this is the GPS source the whole Nearby/Speed/Tracks/Trip stack reuses (see §3). |

### Flavor / permission reality (load-bearing for every GPS feature)

`app/build.gradle.kts` defines one flavor dimension `network` with two flavors:

- **`offline`** — no `INTERNET`, **no location at all.** The base `AndroidManifest.xml` actively *strips* `ACCESS_COARSE/FINE_LOCATION` via `tools:node="remove"` as a safety net against transitive deps.
- **`withNetwork`** — `src/withNetwork/AndroidManifest.xml` re-adds `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, **`FOREGROUND_SERVICE_DATA_SYNC`**, **`POST_NOTIFICATIONS`**, and `ACCESS_COARSE/FINE_LOCATION`.

**Consequence:** any feature needing live GPS or notifications is `withNetwork`-only and must degrade gracefully (or be absent) on `offline`. **`FOREGROUND_SERVICE_LOCATION` is not yet declared** — the manifest has `FOREGROUND_SERVICE_DATA_SYNC`. Track recording (§2.2B) must **add `FOREGROUND_SERVICE_LOCATION`** to the `withNetwork` manifest. Single ABI: **arm64-v8a only** (relevant to §2.2D, the 16KB fix).

**Reference point.** The map already centres on `AdelaideCbd = LatLng(-34.92, 138.62)` (`ui/map/MapScreen.kt:116`) and exposes `mapLibreMap.cameraPosition.target`. Until a shared GPS provider lands, **the map-pack centre / current camera target is the offline-safe reference point** for Nearby/Coordinates/Fuel.

### On-device verification harness (available now)

A physical **arm64 phone over ADB** is wired: instrumented tests (`connectedWithNetworkDebugAndroidTest`), `adb exec-out screencap` → local **MiniCPM vision** model for visual assertions, and `adb shell input` for UI driving. Every UI feature below is verified by the **same three-rung ladder**:
1. **VM unit test** (JVM, CI) — the ViewModel's state reduction over fake logic + fake providers.
2. **Screenshot + vision** — launch the flavor build on the phone, `adb input` to the surface, `screencap`, route the PNG to MiniCPM with a yes/no assertion ("Does this show a Nearby tab listing fuel stations with distances?").
3. **`adb input` interaction** — tap categories / toggles / record-stop and re-screenshot to prove state transitions.

GPS-dependent features add a fourth rung: **real movement** OR `adb emu geo fix` / mock-location provider injection.

---

# Sub-phase 2A — **This session** (offline-safe, no new permissions)

Two features that need **zero GPS and zero new permissions** — the fastest proven-logic-to-UI wins, and both run on the `offline` flavor.

## 2A.1 — Nearby tab  ·  *IN PROGRESS this session*

**What it surfaces.** `offline:search` POI browse (already verified, 49k-row pack) + `CoordinateFormatter` (decimal/DMS/UTM/MGRS) + an emergency hospital/police shortcut. The single highest-value outback feature ("where am I, what's near me") and it works **fully offline**.

| Aspect | Detail |
|---|---|
| **UI surface** | A new **fourth bottom-bar tab `Nearby`** (`AusRoadsDestination.Nearby`, added to `bottomBarItems` in `ui/navigation/AusRoadsNavHost.kt`). Screen = a vertical list of category chips (Fuel / Hospital / Police / Toilets / Camping / Water / EV / Pharmacy / Fire / Supermarket → `PoiCategory`), a **Coordinates card** at top (current reference point in DD + DMS + MGRS, one-tap copy of MGRS), an **Emergency strip** (nearest Hospital + nearest Police, distance + bearing) pinned near the top, and per-category nearest results with distance + tap-to-route. |
| **ViewModel** | new `NearbyViewModel` (`ui/nearby/`), Hilt. Holds `referencePoint: GeoPoint` (map camera target initially), `selectedCategory`, `results: List<NearbyResult>`. Calls `nearestByCategory(...)`; for emergency, `nearestByCategory(cat=HOSPITAL/POLICE, limit=1)`; coordinates via `CoordinateFormatter`; distance/bearing via `MeasureGeometry.bearingDegrees` + path length. |
| **Verified logic wired** | `SearchRepository.nearestByCategory` / `browseByCategory`; `PoiCategory`; `CoordinateFormatter.{decimalDegrees,dms,mgrs}`; `MeasureGeometry.bearingDegrees`. |
| **Data / location / flavor** | `search.db` (already opened by the app). **Reference point = map-pack centre / chosen point** — works on **both flavors, no permission.** GPS ("near *me*") is a 2B follow-up via the shared provider (§3). |
| **On-device verification** | (1) `NearbyViewModelTest`: fake `SearchRepository` returns known SA servos → assert ordering/distance/bearing + emergency selection. (2) Screenshot+vision: "Nearby tab shows a coordinates card and a fuel list with km distances." (3) `adb input tap` a category chip → re-screenshot shows that category's results; tap MGRS → clipboard assert. Spot-check a couple of SA towns that nearest hospital/police are genuinely nearest. |
| **Effort / risk** | **M / Low.** All logic verified; pure read path; no permission. Only real care: emergency selection correctness (safety-relevant) and reference-point clarity in copy ("near map centre", not "near you", until GPS lands). |

## 2A.2 — Routing avoid-options toggles  ·  *IN PROGRESS this session*

**What it surfaces.** `RouteOptions(avoidTolls, avoidUnsealed, avoidFerries)` — already mapped to Valhalla `use_tolls` / `use_ferry` / `use_tracks` in `routing:engine-valhalla` and unit-tested. Pure request shaping; fully offline.

| Aspect | Detail |
|---|---|
| **UI surface** | Three toggles in the **existing route sheet** on the Map tab (the sheet that takes origin/destination and triggers routing in `ui/map/MapScreen.kt` + `RouteViewModel`). A small "Route options" expander with switches: **Avoid tolls / Avoid unsealed / Avoid ferries**. A caption notes these are *soft* preferences (Valhalla weights, not hard exclusions) and surfaces any `RouteResult.warnings`. |
| **ViewModel** | Extend `RouteViewModel` (`ui/map/`): hold a `RouteOptions` in UI state (default all-off = today's behaviour), pass it through `RouteRequest(options = …)`. Persist last choice in `DataStore` (optional, low effort). |
| **Verified logic wired** | `RouteOptions` → `RouteRequest.options` → existing Valhalla costing mapping (already golden-tested). |
| **Data / location / flavor** | None — pure request field. Works on **both flavors** (routing is on-device). No GPS, no permission. |
| **On-device verification** | (1) `RouteViewModelTest`: toggling each switch yields a `RouteRequest` with the expected `RouteOptions` flag; defaults = baseline. (2) Screenshot+vision: "Route sheet shows three avoid toggles." (3) `adb input`: flip Avoid-ferries on a known ferry-crossing OD pair (e.g. a Murray River crossing) → re-screenshot/diff route length to confirm the route changed. |
| **Effort / risk** | **S / Low.** Logic + costing mapping already verified; this is UI plumbing into an existing sheet. Subtlety: soft weights can produce odd detours at extremes — keep defaults conservative and show warnings. |

**2A exit criteria.** Both features build green on `offline` and `withNetwork`, pass their VM unit tests, and pass a vision-asserted screenshot run on the arm64 phone. A `code-reviewer` pass on the diff before "done." Confirm the **GeoPoint named-arg** rule (lat/lon are a known silent-swap class here — construct `GeoPoint(latitude = …, longitude = …)`, never positionally) and that the **release R8 reflection keeps** for routing still hold.

---

# Sub-phase 2B — **Next** (GPS, foreground service, sensors)

Everything here needs live GPS, so it is **`withNetwork`-only** and depends on the shared LocationProvider (§3) landing first. Verification needs **real movement** or `adb emu geo fix` / mock location.

## 2B.1 — Track recording + GPX import/export

| Aspect | Detail |
|---|---|
| **UI surface** | A **Record control** (start/pause/resume/stop) — surfaced on the Map tab as an overlay FAB *and* a section in a Tracks list. A new **Tracks screen** (reachable from Nearby or its own tab/entry) backed by `data:tracks`: list of saved tracks (name, distance, point count, date), tap → render the track polyline on the map. **GPX export/import via the Storage Access Framework** (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`) — no storage permission needed. |
| **ViewModel** | `TrackRecordingViewModel` (drives `TrackRecorder`, feeds it `LocationFix`es from the shared provider, exposes live `RecorderState`); `TracksListViewModel` (over `TrackRepository`). GPX I/O calls `Gpx.write` / `Gpx.read` and persists via `TrackDao`. |
| **Verified logic wired** | `TrackRecorder` (sampling state machine), `data:tracks` (Room store + `TrackStats`), `core:geo.Gpx` (GPX 1.1 codec). |
| **Data / location / flavor** | **GPS required → `withNetwork` only.** **Must add `FOREGROUND_SERVICE_LOCATION`** to `src/withNetwork/AndroidManifest.xml` and run a **foreground `LocationService`** (notification, Doze-survivable). **GPX import/export/render is permission-free and works on `offline`** (decouple the codec/list UI from the recorder so the `offline` flavor still imports/views tracks). |
| **On-device verification** | (1) `TrackRecordingViewModelTest` + `TracksListViewModelTest` with synthetic fixes (sampling gate, distance accrual, pause/resume) — extends existing `feature:trip` tests at the VM layer. (2) **Real movement OR `adb emu geo fix <lon> <lat>` sequence** to drive the foreground service; screencap the live distance/point counter incrementing; verify it survives screen-off/backgrounding (Doze) and process death. (3) SAF round-trip: export → re-open the `.gpx` in a viewer; import a foreign GPX → renders. (4) `offline` flavor: import/render works, record UI absent. |
| **Effort / risk** | **L / Med-High.** The foreground service + Doze/OEM background-throttling + process-death recovery are the real work (the logic is done). Mock-location injection for CI-on-device is fiddly. |

## 2B.2 — Over-speed alert in navigation

| Aspect | Detail |
|---|---|
| **UI surface** | Extend the existing **`NavigationOverlay`** (`feature:navigation`): a **posted-limit badge** (the maxspeed for the current road) and an **alert state** (subtle chime + visual flash) when speed exceeds limit + margin. |
| **ViewModel** | Extend `NavigationViewModel`: on each `NavigationLocation`, call `SpeedLimitMonitor.onLocation(fix, postedLimit)` where `postedLimit = search.maxspeedNear(lat, lon)`. Expose `SpeedAlert` + current limit to the overlay. Margin is a Settings value. |
| **Verified logic wired** | `feature:trip.SpeedLimitMonitor` (hysteresis state machine) + `offline:search.maxspeedNear` (the 49,493-row `road_speed` table). |
| **Data / location / flavor** | `road_speed` table in `search.db` (present). **GPS speed → `withNetwork` only.** Degrades silently when `maxspeedNear` returns null (no false alarms; tolerant of packs without the table). |
| **On-device verification** | (1) `NavigationViewModelTest`: synthetic speed series vs a fixed limit → over/clear edges + margin + null-limit-is-silent. (2) `adb emu geo fix` a path along a known-limit SA road while feeding speed → screencap the badge + alert appearing/clearing. (3) Spot-check the posted limit matches reality on a few SA highways; confirm parallel-road mismatch is acceptable (true snapping waits for the P4 `trace_attributes` symbol — do **not** attempt map-matching now). |
| **Effort / risk** | **M / Med.** Logic verified; risk is **nearest-segment picking the wrong parallel road** without map-matching — mitigate with the heading-aware nearest already in the lookup, and accept the known limit. |

## 2B.3 — Trip-computer overlay

| Aspect | Detail |
|---|---|
| **UI surface** | A **glanceable HUD panel** during navigation (toggle on `NavigationOverlay`, or a compact strip): moving time, avg speed, max speed, distance today, and **daylight remaining** (large type, dimmable). |
| **ViewModel** | Extend `NavigationViewModel` (or a dedicated `TripComputerViewModel`): feed `TripComputer.onLocation(fix)`; expose `TripStats`. Daylight from `SunCalc.daylightRemaining(now, lat, lon)`. |
| **Verified logic wired** | `feature:trip.TripComputer` (moving/stopped partition, avg/max) + `core:geo.SunCalc`. |
| **Data / location / flavor** | **GPS for live speed → `withNetwork`.** Daylight is pure astronomy — **works on `offline`** given the reference point + clock, so the daylight readout can appear without GPS. No pack change. |
| **On-device verification** | (1) `TripComputerViewModelTest`: synthetic fixes → moving avg excludes stops, max captured, reset; `SunCalc` vs Adelaide/Coober Pedy/Ceduna almanac tables within minutes. (2) Screenshot+vision: "HUD shows moving time, avg speed, and daylight-remaining." (3) Real drive (or geo-fix replay) sanity at dusk: daylight → 0 after sunset; SA UTC+9:30/+10:30 DST correctness explicitly checked. |
| **Effort / risk** | **M / Low.** Logic done + device-checkable cheaply (daylight needs no GPS). Clamp GPS speed noise on "max". |

**2B exit criteria.** `withNetwork` build green; `FOREGROUND_SERVICE_LOCATION` added and the service Doze-verified; each VM test green; a geo-fix-replay or real-movement screenshot run for each. Shared provider (§3) in place. `code-reviewer` on the diff.

---

# Sub-phase 2C — **Next** (SMS, alarms, geofence, fuel)

## 2C.1 — Trip-share / overdue check-in

| Aspect | Detail |
|---|---|
| **UI surface** | A **"Share my trip"** action (from Nearby / a trip sheet): pick destination + ETA + check-in time → `TripShareComposer.compose(...)` → fire an **SMS intent** (`ACTION_SENDTO` `smsto:` prefilled — no `SEND_SMS` auto-send unless the user opts in) or the share sheet. An **overdue** arm: schedule a **WorkManager** job to the check-in time; if not checked in, post a **notification** prompting an overdue SMS with last-known coordinates. |
| **ViewModel** | `TripShareViewModel`: builds the message, launches the intent, arms `OverdueTracker` (`CheckIn` + `stateAt`), schedules the Worker, exposes `OverdueState`. |
| **Verified logic wired** | `feature:trip.TripShareComposer` (text only — Android wiring is explicitly this layer's job) + `OverdueTracker` (`OverdueState`/`CheckIn`/`stateAt`). |
| **Data / location / flavor** | Compose is permission-free (**`offline` = compose-to-share-sheet only**). Auto-send + last-known coords + notifications → **`withNetwork`** (`POST_NOTIFICATIONS` already declared; `SEND_SMS` only if auto-send is offered). Last-known coords reuse the shared provider. |
| **On-device verification** | (1) `TripShareViewModelTest`: message formatting (length budget, `geo:` URI), arm→checkIn→no-fire, arm→deadline→exactly-one-fire (fake clock). (2) `adb input` through the share flow; screencap the prefilled SMS composer. (3) WorkManager: advance time (or use `adb shell cmd jobscheduler`/test clock) → notification fires once; survives backgrounding. (4) `offline`: share-sheet only, no SMS permission requested. |
| **Effort / risk** | **M / Med.** Logic done; risk is **exact-alarm restrictions on Android 13+** (prefer inexact WorkManager; avoid the scary exact-alarm prompt) and **never auto-sending to anyone the user didn't pick.** Reboot persistence of the armed timer. |

## 2C.2 — Proximity / geofence pin alerts

| Aspect | Detail |
|---|---|
| **UI surface** | A per-pin **"Alert me near here"** toggle + radius on the **Pins** tab (extend `PinsScreen.kt` / `EditPinSheet`). When driving, a notification/chime fires on entering a pin's radius. |
| **ViewModel** | `ProximityViewModel` (or fold into the recording/nav service): feed `ProximityEngine.onLocation(fix)` with the armed pins as targets; surface `ProximityAlert`s. Runs in the same foreground location service as 2B.1 to share the GPS stream. |
| **Verified logic wired** | `feature:trip.ProximityEngine` (edge-triggered, hysteresis) reading `data:pins`. |
| **Data / location / flavor** | Pins = existing local store. **GPS → `withNetwork`.** Deliberately our own distance math, **not** OS/Play geofencing (no extra Play deps / network). |
| **On-device verification** | (1) `ProximityViewModelTest`: synthetic path crossing radii → enter fires once, no re-fire inside, exit re-arms; multi-pin; boundary hysteresis. (2) Geo-fix-replay a path toward a real pin with screen off → notification fires once; no boundary false-positive storm on noisy real GPS. (3) Battery sanity over a multi-hour replay. |
| **Effort / risk** | **M / Med.** Logic done; risk is **continuous-GPS battery** (tune sampling, ideally share 2B.1's service) and OEM background limits. |

## 2C.3 — Fuel / servo planner

| Aspect | Detail |
|---|---|
| **UI surface** | Either a **card inside Nearby** (recommended — keeps it near the POI data) or a small dedicated sheet: a **fuel-range input** (tank L, % remaining, L/100km) → range estimate + nearest servo(s) ahead with **route** distance + a Low/Critical warning. |
| **ViewModel** | `FuelPlannerViewModel`: `FuelRange.rangeKm(...)` + `FuelPlanner.plan(...)` (which uses `nearestFuelKmAlong` over `search.db` fuel + `route()` distance). Reference point from the shared provider (GPS) or map centre. |
| **Verified logic wired** | `core:geo.FuelRange` + `feature:trip.FuelPlanner` (+ `PoiCategory.FUEL` via `nearestByCategory`). |
| **Data / location / flavor** | `amenity=fuel` rows in `search.db` (present) + on-device `route()`. **Works on `offline`** if anchored to the map centre (no GPS needed for the *planner*; the on-map **range ring** is the P4 `isochrone` item — out of scope). |
| **On-device verification** | (1) `FuelPlannerViewModelTest`: fake search (known Stuart Hwy roadhouses) + fake engine (known distances) → range arithmetic, nearest selection, Low/Critical thresholds. (2) Screenshot+vision: "Fuel card shows range left and next servo distance." (3) Spot-check nearest servo + route distance around remote SA. |
| **Effort / risk** | **S-M / Low.** Logic done. `route()` to many servos can be slow — cap candidates via the `search.db` bbox pre-filter before routing (already the planner's approach). Consumption is user-estimated (garbage-in caveat in copy). |

**2C exit criteria.** Per-feature VM tests green; SMS/alarm/notification flows device-verified (with the no-auto-send and exact-alarm-avoidance guarantees); proximity verified not to storm on real GPS; `code-reviewer` on each diff.

---

# Cross-cutting architecture

## 3. Shared location-source architecture (prerequisite for all 2B/2C GPS features)

**Problem.** Nearby (GPS follow-up), Tracks, Over-speed, Trip-computer, Proximity, and Fuel all need one consistent fix stream, and it must vanish cleanly on the `offline` flavor (which has no location permission at all).

**Decision.** Introduce a single **`LocationProvider`** abstraction (interface in a shared module — `core:common` or a small `core:location`) exposing `fun locationUpdates(intervalMs): Flow<LocationFix>` + `suspend fun lastKnown(): LocationFix?`. **Reuse `feature:navigation`'s `NavigationLocationProvider`** as the `withNetwork` implementation — it already wraps Play-Services `FusedLocationProviderClient` and emits `NavigationLocation` (lat/lon/speedKmh/bearing/accuracy/timestamp). The `offline` flavor binds a **no-op / map-centre provider** (never requests permission; emits the chosen reference point or nothing), so the same ViewModels compile and run on both flavors with the flavor choosing the binding via Hilt.

- **Why not per-feature providers:** duplicated permission logic, multiple concurrent `FusedLocationProviderClient` subscriptions (battery), and inconsistent fix semantics. One provider, one foreground subscription (shared by the 2B.1 service), fan-out via `Flow`.
- **Why reuse `NavigationLocationProvider` rather than rewrite:** it is already on-device-proven for nav; wrapping it behind the interface is cheaper and lower-risk than a parallel GPS stack.
- **`LocationFix`** = the shared model (lat, lon, speed m/s, bearing, accuracy, time) that `TrackRecorder`/`TripComputer`/`SpeedLimitMonitor`/`ProximityEngine` already consume — adapt `NavigationLocation` to it (km/h ↔ m/s).
- **Reference-point ladder for offline-safe features (Nearby/Coordinates/Fuel):** GPS `lastKnown` (if `withNetwork` + permission) → else the **map camera target** → else `AdelaideCbd`. Copy must say which ("near map centre" vs "near you").

**Verification.** A `LocationProviderTest` over a fake; flavor-binding smoke test (the `offline` build must not pull a `FusedLocationProviderClient` subscription — assert no location-permission request on `offline`).

**Effort / risk:** **M / Med.** Mostly a clean interface + Hilt flavor binding over an existing provider; risk is the foreground-service lifecycle and making the `offline` no-op truly inert.

## 4. The 16KB native-lib alignment fix

**What.** Android 15+ on some devices uses **16KB memory pages**; native `.so` libraries must be **16KB-aligned** or they fail to load. MapLibre (`org.maplibre.gl:android-sdk` **11.5.2**) and the Valhalla `.so` are the native deps that matter (single ABI: **arm64-v8a**). Resolve by **bumping dependencies** to 16KB-aligned releases (MapLibre and any NDK/AGP/`valhalla-mobile` bumps required) and confirming the packaged `.so`s are aligned.

| Aspect | Detail |
|---|---|
| **Work** | Bump MapLibre (and AGP/NDK as needed) to a 16KB-aligned version; verify alignment of shipped `.so`s (e.g. `objdump`/`zipalign -c`/Play's 16KB check). Keep the single arm64-v8a ABI. |
| **Risk / DO-NOT-RETRY guardrails** | A MapLibre bump can regress rendering. After the bump, **device-verify the map still renders**, AND re-confirm the two known MapLibre DO-NOT-RETRYs still hold: (a) **data-driven `circle-color` rendering invisible markers** — keep the working color expression, don't reintroduce the data-driven form that rendered nothing; (b) **missing-glyph / font failure** — keep the working glyph/font setup, don't drop back to the config that produced missing glyphs. (These are recorded in Sloane project memory for aus-roads — recall before touching MapLibre.) |
| **Verification** | Build the `withNetwork`/`offline` release, install on the arm64 phone, screencap the map → vision-assert "the vector map renders with labels/glyphs and pins are visible." Confirm no native-load crash on a 16KB-page device (or emulator image configured for 16KB). |
| **Effort / risk** | **S-M / Med.** The bump is small; the risk is a rendering regression — which is exactly why the post-bump device screenshot + the two DO-NOT-RETRY re-checks are mandatory gates. |

---

# Known gaps & deferrals (not in Phase 2)

- **Coordinate parse-back** (DMS/MGRS text → `GeoPoint`): `CoordinateFormatter` is **output-only today** (no `parse`). Receiving a `geo:`/MGRS string to drop a pin (spec features 11/17 input side) needs a `parse`/`GeoUri` helper added to `core:geo` first — small pure-logic add, but it is **new logic**, so it's out of this UI-only phase. Note for 2C if `geo:` receive is wanted.
- **Wider `RouteOptions`** (`preferSealed`/`shortest`/`topSpeedKph`, 4WD profile): only `avoidTolls/avoidUnsealed/avoidFerries` are shipped — the richer set is a routing-API change, not Phase-2 UI.
- **Map-matched speed / "which road am I on"** (over-speed accuracy), **fuel range ring**, **isochrone/matrix** features: all P4, blocked on `valhalla-mobile` JNI symbols — **do not attempt against the current lib.**
- **Topo/hillshade** (P5) and **opening-hours POI columns** (P3 data): out of scope.

# Sequencing summary

| Order | Item | Flavor | New perm | Effort | Risk |
|---|---|---|---|---|---|
| **2A** | Nearby tab (POI + coords + emergency) | both | none | M | Low |
| **2A** | Routing avoid-options toggles | both | none | S | Low |
| **2B pre** | Shared `LocationProvider` (§3) | flavor-split | — | M | Med |
| **2B** | Track recording + GPX (SAF) | withNetwork (GPX on both) | `FOREGROUND_SERVICE_LOCATION` | L | Med-High |
| **2B** | Over-speed alert | withNetwork | none (perms present) | M | Med |
| **2B** | Trip-computer overlay | withNetwork (daylight on both) | none | M | Low |
| **2C** | Trip-share / overdue check-in | withNetwork (compose on both) | `SEND_SMS` only if auto-send | M | Med |
| **2C** | Proximity / geofence pin alerts | withNetwork | none | M | Med |
| **2C** | Fuel / servo planner | both | none | S-M | Low |
| **any** | 16KB native-lib alignment fix (§4) | both | none | S-M | Med |

**Standing rules for every Phase-2 task.** Construct `GeoPoint` with **named args** (lat/lon silent-swap class). Keep the **release R8 reflection keeps** for routing. Each UI feature ships only after **VM unit test + vision-asserted screenshot + `adb input` interaction** (GPS features add geo-fix-replay or real movement), and a **`code-reviewer`** pass on the diff before "done."
