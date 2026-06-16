# aus-roads — Valhalla Capabilities & Constraints

Authoritative reference for what the **offline Valhalla routing** can and cannot do today, and what gates the library-bump (P4) features. This is the single most important constraint document for phasing — read it before specing anything routing-adjacent.

**Libraries in play:** `valhalla-mobile 0.1.6` (the JNI-bridged native lib) + `valhalla-models 0.0.9` (the Kotlin data models). The mismatch between what's *modeled* and what's *callable* is the whole story.

---

## 1. The JNI ceiling — ONLY `route()` is bridged

> **`valhalla-mobile 0.1.6` bridges exactly one Valhalla action to Kotlin: `route()`.**

The other Valhalla actions — `isochrone`, `matrix` (sources-to-targets), `trace_attributes` / `trace_route` (map-matching), `locate`, and `height` (elevation sampling) — are **modeled** in `valhalla-models 0.0.9` (the request/response Kotlin types exist) but are **NOT callable**: there is **no JNI symbol** exporting them. Calling them is impossible, not merely awkward — the native entry point does not exist.

**Consequence for the roadmap (the load-bearing facts):**

| Capability | Status in 0.1.6 | Feature impact |
|------------|-----------------|----------------|
| `route()` | ✅ bridged & used | Powers nav, multi-stop (ordered `via`), servo-distance for fuel planner |
| `isochrone()` | ❌ modeled, no JNI symbol | **Blocks** the fuel-range **ring** (#1 P4). Planner ships without it. |
| `matrix()` | ❌ modeled, no JNI symbol | **Blocks** multi-stop **optimisation/reorder** (#5 P4). Ordered stops ship without it. |
| `trace_attributes()` | ❌ modeled, no JNI symbol | **Blocks** map-matching & snapped posted-speed. Hurts #3 road-ID accuracy (heading-aware nearest is the stopgap). |
| `height()` (elevation) | ❌ modeled, no JNI symbol | **Blocks** route-derived elevation/climb profiles (#13 route-altitude, planning). Device baro/GPS-alt is the stopgap. |
| `locate()` | ❌ modeled, no JNI symbol | Minor — no node/edge introspection. |

So: a **true isochrone fuel-range RING is BLOCKED** until `valhalla-mobile` is bumped. The fuel feature ships **first as a SERVO PLANNER** (nearest servo via `route()` distance + the `FuelRange` math model: "~Y km range, next fuel X km ahead"); the on-map isochrone ring is a **later phase (P4) gated on the lib bump**. Map-matching, matrix, and elevation are likewise unavailable in 0.1.6.

---

## 2. Costing options — FULLY available NOW

In contrast to the action ceiling, the **costing model is fully exposed**. `AutoCostingOptions` (auto profile) carries the fields the roadmap needs, settable on a `route()` request **today**:

| Field | Type / range | Meaning | Used by |
|-------|--------------|---------|---------|
| `useTolls` | Double 0.0–1.0 | Willingness to use toll roads (0 = avoid) | #6 avoid tolls |
| `useFerry` | Double 0.0–1.0 | Willingness to use ferries (0 = avoid) | #6 avoid ferries |
| `useTracks` | Double 0.0–1.0 | Willingness to use `highway=track` (unsealed) (0 = avoid) | #6/#7 unsealed |
| `useLivingStreets` | Double 0.0–1.0 | Willingness to use living streets | #6/#7 |
| `useHighways` | Double 0.0–1.0 | Preference for highways (1 = prefer) | #7 prefer sealed |
| `topSpeed` | Double (kph) | Cap assumed travel speed | #6 (advanced) |
| `shortest` | Boolean | Optimise distance over time | #6 |

**This makes features 6 and 7 buildable now** by mapping `RouteOptions` → these fields (see `feature-specs.md` #6/#7 and the `routing:engine-valhalla` wiring).

**There is NO explicit 4WD boolean.** "Avoid unsealed / prefer sealed" is **approximated**: low `useTracks` (≈0) + high `useHighways`/`useLivingStreets` (≈1) for sealed-only; high `useTracks` (≈1) for a 4WD "prefer tracks" profile. These are **soft weights**, not hard exclusions — extreme values can yield odd detours; defaults must be tuned, and the UI must say "approximate, verify road conditions." A real hard "sealed only" would need a road `surface` attribute baked into the **routing tiles** (a Valhalla tile-build change), which is **out of scope** for this roadmap.

**Also note (already in the API):** `RouteRequest` supports `via: List<GeoPoint>` (multi-location → ordered multi-stop, #5), `avoidPolygons` + `penalties`/`closureFactor` (closure-aware routing — already used), `departAt`, and `Maneuver.lanes: List<LaneInfo>` (lane data comes back in the route response → #8 is rendering, not data). None of these need a bump.

---

## 3. What's NOT in the route response

Two facts that shape features:

- **No posted speed limit on `RouteManeuver`.** The route response does not carry the road's `maxspeed`. Combined with `trace_attributes` being unbridged, there is **no Valhalla path to posted limits at runtime**. → Feature 3's limit **data** must come from the pack (`maxspeed` osmium pass into `search.db`, `data-pipeline.md` §1); only the over-speed **alert logic** (GPS speed vs limit + hysteresis) is buildable now.
- **No elevation in the route response** (and `height()` unbridged). → route-derived climb profiles wait for P4; #13 altitude uses the device barometer / GPS altitude.

---

## 4. What a library bump unlocks (P4)

If/when `valhalla-mobile` bridges more actions to JNI, the following become buildable (the models already exist in `valhalla-models`, so it's a native-symbol problem, not a modeling one):

| Unlocked action | Feature it enables |
|-----------------|--------------------|
| `isochrone()` | **Fuel-range ring** on the map (#1 P4) — shaded reachable area from remaining range. |
| `matrix()` | **Multi-stop optimisation** (#5 P4) — optimal visit order for N stops (true TSP-ish), vs today's hand-ordered `via`. |
| `trace_attributes()` | **Map-matching** — snap the GPS trace to roads → reliable road identification (fixes #3's "which road am I on" properly) and snapped speed. |
| `height()` | **Elevation/climb profiles** — route altitude graphs, climb totals (richer #13, trip planning). |
| `locate()` | Edge/node introspection (minor). |

**These are the only things blocking the P4 features.** Track the upstream `valhalla-mobile` releases for the added JNI symbols; when they appear, the P4 specs in `feature-specs.md` become implementable as written (the Kotlin models are already present).

---

## 5. DO-NOT-RETRY

> **Do NOT attempt to call `isochrone`, `matrix`, `trace_attributes`, `locate`, or `height` against `valhalla-mobile 0.1.6`.**
>
> They are present in `valhalla-models 0.0.9` as Kotlin types, which makes them *look* callable — they are **not**. There is **no JNI symbol**; any call cannot reach native code. This has been verified and is the reason features #1-ring, #5-optimise, route-elevation (#13), and map-matched #3 are phased to **P4 behind a library bump**.
>
> Specifically do not:
> - try to build the **fuel-range isochrone ring** on 0.1.6 — ship the **servo planner** instead (nearest servo via `route()` distance + `FuelRange` math);
> - try to build **multi-stop matrix optimisation** on 0.1.6 — ship **ordered `via` waypoints** instead;
> - try to get **posted speed limits or map-matching from Valhalla** at runtime on 0.1.6 — get limits from the **pack** (`search.db` `maxspeed` table) and use **heading-aware nearest-segment** selection as the stopgap;
> - try to get **route elevation** from Valhalla on 0.1.6 — use the **device barometer / GPS altitude**.
>
> The correct action when these are needed is to **bump `valhalla-mobile`** (and confirm the new JNI symbols), not to wrestle 0.1.6.

---

## 6. Summary table

| Action | Modeled (`valhalla-models 0.0.9`) | Callable (`valhalla-mobile 0.1.6`) | Roadmap phase if needed |
|--------|-----------------------------------|------------------------------------|-------------------------|
| `route()` | ✅ | ✅ | now (P1/P2) |
| Costing options (`useTolls`/`useFerry`/`useTracks`/`useLivingStreets`/`useHighways`/`topSpeed`/`shortest`) | ✅ | ✅ | now (P1/P2) — features 6, 7 |
| `via` multi-location | ✅ | ✅ (via `route()`) | now (P1/P2) — feature 5 ordered |
| `isochrone()` | ✅ | ❌ | **P4 (bump)** — feature 1 ring |
| `matrix()` | ✅ | ❌ | **P4 (bump)** — feature 5 optimise |
| `trace_attributes()` | ✅ | ❌ | **P4 (bump)** — map-matching, feature 3 accuracy |
| `height()` | ✅ | ❌ | **P4 (bump)** — elevation, feature 13 |
| `locate()` | ✅ | ❌ | P4 (bump) — minor |
| Posted speed in route response | n/a (not provided) | ❌ | **P3 data** — feature 3 limits via `search.db` |
