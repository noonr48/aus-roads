# aus-roads — Feature Specifications (17 features)

Working specs. Each feature: **ID · tier · user story · offline/data dependency · feasibility verdict · target module(s) · API sketch · acceptance criteria · unit-test plan · on-device verification checklist · risk.**

Feasibility verdicts (defined in `valhalla-capabilities.md` / `data-pipeline.md`):

- **buildable-now** — implementable today with shipped libs, the existing `search.db`, and existing Valhalla `route()` + costing fields.
- **needs-data** — requires a pack-builder / `search.db` addition (no Valhalla change).
- **needs-lib-bump** — blocked on `valhalla-mobile` exposing more JNI symbols (isochrone/matrix/elevation/trace).
- **major** — a whole new pipeline component (raster DEM + manifest v2).

Grouping below is **by phase** (when the core lands), per the 5-phase plan in `00-overview.md`. Package root throughout: `au.com.ausroads`.

> **Reminder for every spec:** `offline` flavor has no `INTERNET`/`LOCATION`. Where a feature needs GPS/SMS it targets `withNetwork`; the spec states the offline degradation. CI proves logic; the on-device checklist proves the feature.

---

# PHASE 1 — Buildable-now cores (pure logic, CI-green)

P1 lands the `core:geo` math, the `data:tracks` model, the routing-options model, and the **state machines** for the GPS-driven features. Everything here is JVM-unit-testable; nothing here needs a device. The Compose UI and Android services that consume these land in P2.

---

## Feature 2 — Track recording + GPX import/export  ·  T1

> **P1 = GPX codec + Room model + recorder state machine. P2 = the foreground recording service + UI.** Listed first because its GPX codec and track model underpin #16, #17 and the share features.

**User story.** Driving the Oodnadatta Track with no signal, I start recording. The app logs my path the whole way. Back at camp I export the day as a `.gpx` to share or archive; next trip I import a mate's `.gpx` route and see it on the map — all offline.

**Offline / data dependency.** Fully offline. Recording needs GPS (`withNetwork`). GPX import/export and rendering an imported track need **no** permission and work on the `offline` flavor. No pack dependency.

**Feasibility.** buildable-now. GPX 1.1 is a pure-Kotlin codec; `data:tracks` is standard Room; the recorder is a sampling state machine.

**Target modules.** `core:geo` (`Gpx`), `data:tracks` (`Track`/`TrackPoint` + DAO), `feature:trip` (`TrackRecorder`).

**API sketch.**

```kotlin
// core:geo
object Gpx {
    fun write(track: GpxTrack): String              // GPX 1.1 document
    fun read(xml: String): GpxParseResult            // tolerant of foreign extensions
}
data class GpxTrack(val name: String?, val points: List<GpxPoint>)
data class GpxPoint(val lat: Double, val lon: Double, val ele: Double?, val time: Instant?)
sealed interface GpxParseResult { /* Success(track) | Malformed(reason) */ }

// data:tracks
@Entity data class Track(@PrimaryKey val id: Long, val name: String, val startedAt: Long, val endedAt: Long?,
                         val distanceMeters: Double, val pointCount: Int)
@Entity data class TrackPoint(val trackId: Long, val lat: Double, val lon: Double,
                              val ele: Double?, val speedMps: Float?, val tMillis: Long)
@Dao interface TrackDao { /* insertTrack, appendPoints(batch), listTracks, pointsFor(id), delete */ }

// feature:trip
class TrackRecorder(private val dao: TrackDao, private val clock: Clock) {
    fun start(name: String); fun onLocation(fix: LocationFix); fun pause(); fun resume(); fun stop(): Long
    val state: StateFlow<RecorderState>   // Idle | Recording(dist,pts,elapsed) | Paused
}
```

Sampling rule: append a point when moved ≥ N m **or** ≥ T s since last, whichever first (decimate jitter while stationary). Batch-insert to Room.

**Acceptance criteria.**
- Record → points accrue; live distance/point-count/elapsed update; pause stops accrual, resume continues.
- Stop persists the track; it reopens with identical geometry.
- Export produces valid GPX 1.1 (`<trk><trkseg><trkpt lat lon><ele><time>`); re-import round-trips to the same points (within float tolerance).
- Import tolerates foreign GPX (extra namespaces, missing `ele`/`time`); malformed XML → a friendly error, never a crash.
- Imported track renders as a polyline on the map.

**Unit-test plan (CI).**
- `Gpx.write` → fixed track → byte-stable, schema-valid document; `read(write(t)) == t` within tolerance.
- Foreign-extension fixtures parse; truncated/garbage XML → `Malformed`, no throw.
- `TrackRecorder` (fed synthetic `LocationFix`es + fake `Clock`): sampling threshold honored; distance accumulation correct (known coordinates → known metres); pause/resume transitions; stationary jitter decimated.
- `TrackDao` SQL strings / entity mapping (logic-level).

**On-device verification checklist.**
- [ ] Foreground service keeps recording with screen off and app backgrounded (Doze).
- [ ] Real GPS drift does not inflate distance while parked (sampling gate holds on hardware).
- [ ] Multi-hour recording: no memory growth, no DB lock; survives process death (recovers in-progress track).
- [ ] Export writes to scoped storage; the file opens in OsmAnd / a GPX viewer.
- [ ] Import via Storage Access Framework renders correctly.
- [ ] `offline` flavor: import/export/render works with no permissions; recording UI is absent (no GPS).

**Risk.** Background-location throttling differs per OEM (Doze). GPX in the wild is messy — the parser must be defensive (covered by fixtures). Storage-permission UX on modern Android.

---

## Feature 4 — Offline trip-share / overdue check-in (SMS)  ·  T1

> **P1 = the message composer + overdue state machine. P2 = real SMS send + alarm scheduling.**

**User story.** Before leaving phone coverage I tell my partner "I'll be at William Creek by 5pm." The app composes an SMS with my plan and a check-in time. If I haven't checked in by 5pm (and I'm momentarily in a coverage pocket), it sends an "overdue" SMS with my last known coordinates so someone knows where to look.

**Offline / data dependency.** Designed for the no-signal case: it **composes** offline and **queues**; the SMS leaves when the device next has cell signal (standard carrier behaviour). Needs `SEND_SMS` + GPS → `withNetwork`. The `offline` flavor offers compose-to-share-sheet only (no auto-send). No pack dependency.

**Feasibility.** buildable-now.

**Target modules.** `feature:trip` (`TripShareComposer`, `OverdueTracker`); a thin Android SMS/alarm wrapper in P2.

**API sketch.**

```kotlin
class TripShareComposer {
    fun shareMessage(plan: TripPlan): String     // "<dest> ETA <time>. Track me: <geo-uri>"
    fun overdueMessage(last: LocationFix, plan: TripPlan): String
}
data class TripPlan(val destinationLabel: String, val etaLocal: LocalDateTime, val checkInBy: LocalDateTime)

class OverdueTracker(private val clock: Clock) {
    fun arm(plan: TripPlan); fun checkIn(); fun onTick(now: Instant): OverdueAction   // None | FireOverdue
    val state: StateFlow<CheckInState>           // Disarmed | Armed(by) | CheckedIn | Overdue
}
```

**Acceptance criteria.**
- Compose produces a concise SMS with destination, ETA, and a `geo:` link (≤ 1 segment where possible).
- Arm with a check-in time → `Armed`; check-in before the deadline → `CheckedIn`, no overdue fires.
- No check-in past the deadline → exactly one `FireOverdue` (idempotent; never spams).
- Overdue message includes last-known lat/lon and the destination.
- SMS recipients are local-only (no server); nothing is uploaded.

**Unit-test plan (CI).**
- `shareMessage`/`overdueMessage` formatting (length budget, `geo:` URI correctness, local-time rendering).
- `OverdueTracker` with a fake `Clock`: arm→checkIn→no-fire; arm→deadline-pass→exactly one fire; re-tick after fire → no second fire; disarm cancels.

**On-device verification checklist.**
- [ ] `SEND_SMS` requested at first use; denial falls back to the share sheet (no crash).
- [ ] SMS actually queues with no signal and delivers when signal returns.
- [ ] Overdue alarm fires at the right wall-clock time with the app backgrounded / after reboot (if persisted).
- [ ] Exact-alarm special access avoided where inexact suffices (no scary permission prompt unless necessary).
- [ ] `offline` flavor: compose → share sheet only; no SMS permission requested.

**Risk.** Carrier SMS variability; exact-alarm restrictions on Android 13+; reboot persistence of the armed timer. Must never auto-send to anyone the user didn't pick.

---

## Feature 6 — Avoid tolls / unsealed / ferries  ·  T2

**User story.** Towing a van, I don't want dirt roads or ferries, and I'd rather skip toll roads through the city. I flip three toggles and the route respects them.

**Offline / data dependency.** Fully offline — pure routing-request shaping. No pack change (Valhalla tiles already carry the road attributes).

**Feasibility.** buildable-now. The costing fields exist today (`valhalla-capabilities.md` §2).

**Target modules.** `routing:engine-api` (`RouteOptions`), `routing:engine-valhalla` (costing wiring), `feature:navigation` (toggle UI in P2).

**API sketch.**

```kotlin
// routing:engine-api — added to RouteRequest
data class RouteOptions(
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
    val avoidUnsealed: Boolean = false,    // → low useTracks
    val preferSealed: Boolean = false,     // → high useHighways/useLivingStreets
    val shortest: Boolean = false,
    val topSpeedKph: Int? = null,
)
// routing:engine-valhalla — mapping
// avoidTolls    → useTolls   = 0.0      (else 1.0)
// avoidFerries  → useFerry   = 0.0      (else 1.0)
// avoidUnsealed → useTracks  = 0.0..0.1
// preferSealed  → useHighways/useLivingStreets ≈ 1.0
// shortest      → shortest   = true
// topSpeedKph   → topSpeed
```

**Acceptance criteria.**
- Each toggle measurably changes the route where an alternative exists (e.g. avoid-ferry reroutes around a ferry crossing).
- Toggles compose (multiple at once).
- No option silently dropped; if Valhalla can't honor one for a given OD pair, surface a `RouteResult.warnings` note.
- Defaults = today's behaviour (no regression when all off).

**Unit-test plan (CI).**
- `RouteOptions` → `AutoCostingOptions` field mapping is exhaustive and correct (each flag → expected Double/bool).
- Default `RouteOptions` produces the current baseline costing (golden).
- Request-builder serialisation snapshot.

**On-device verification checklist.**
- [ ] Avoid-ferry: route around a known ferry (e.g. a Murray River crossing) when toggled.
- [ ] Avoid-unsealed: route prefers sealed where a sealed alternative exists.
- [ ] Avoid-tolls: avoids a tolled link where one exists.
- [ ] Re-route after toggling mid-trip updates correctly.

**Risk.** Low. The only subtlety is that `useTracks`/`useHighways` are *soft* weights, not hard exclusions — extreme settings can produce odd detours; document and tune defaults.

---

## Feature 7 — Sealed-vs-unsealed / 4WD routing profile  ·  T2

**User story.** I'm in a 2WD sedan and must stay on sealed roads; or I'm in a 4WD and *prefer* tracks for a scenic route. I pick a profile and routing matches my vehicle.

**Offline / data dependency.** Fully offline. No pack change.

**Feasibility.** buildable-now **(approximation)**. There is **no explicit 4WD boolean** in Valhalla costing — profiles are presets over the existing weights (`valhalla-capabilities.md` §2).

**Target modules.** `routing:engine-api` (profile → `RouteOptions` presets), `routing:engine-valhalla`, `feature:navigation` (profile picker, P2).

**API sketch.**

```kotlin
enum class VehicleProfile { SEALED_ONLY, STANDARD, FOUR_WD_PREFER_TRACKS }
fun VehicleProfile.toRouteOptions(): RouteOptions = when (this) {
    SEALED_ONLY            -> RouteOptions(avoidUnsealed = true, preferSealed = true)        // useTracks≈0
    STANDARD               -> RouteOptions()                                                 // defaults
    FOUR_WD_PREFER_TRACKS  -> RouteOptions(preferSealed = false /* high useTracks */)        // useTracks≈1
}
```

**Acceptance criteria.**
- `SEALED_ONLY` avoids unsealed wherever a sealed path exists; `FOUR_WD_PREFER_TRACKS` is willing to use tracks.
- Profile selection persists (DataStore) and applies to subsequent routes.
- A clear in-UI caveat: "approximate; verify road conditions" (we model preference, not a road-condition DB).

**Unit-test plan (CI).**
- Each `VehicleProfile` → expected `RouteOptions` → expected costing weights (golden).
- Profile round-trips through persistence (logic-level).

**On-device verification checklist.**
- [ ] `SEALED_ONLY` on a route with a tempting dirt shortcut stays sealed.
- [ ] `FOUR_WD_PREFER_TRACKS` will take an unsealed segment when it's clearly better.
- [ ] Persisted profile survives app restart and is honored by nav.

**Risk.** **Expectation management** — this is a *preference*, not real surface/condition data; OSM `surface`/`tracktype` coverage in the outback is patchy. The caveat copy is part of the spec. (A future hard "sealed only" would need a surface attribute baked into routing tiles — out of scope here.)

---

## Feature 9 — Favourite trips (Home/Work + named)  ·  T2

**User story.** I save Home, Work, and "The Shack at Robe." One tap routes me there — fully offline, no account.

**Offline / data dependency.** Fully offline. Local Room store. No pack change.

**Feasibility.** buildable-now.

**Target modules.** new `data:favourites` (or a table in `data:routes`), `feature:navigation`/`feature:search` (UI in P2). Pure model + DAO in P1.

**API sketch.**

```kotlin
@Entity data class Favourite(@PrimaryKey val id: Long, val label: String,
                             val kind: FavKind, val lat: Double, val lon: Double)
enum class FavKind { HOME, WORK, NAMED }
@Dao interface FavouriteDao { /* upsert, list, byKind(HOME|WORK), delete */ }
// HOME/WORK are singletons (upsert replaces); NAMED is unbounded.
```

**Acceptance criteria.**
- Save current map point / search result as Home, Work, or a named favourite.
- Home/Work are unique (re-saving replaces); named favourites are a list.
- Tapping a favourite starts routing to it.
- Everything local; no identity, no sync.

**Unit-test plan (CI).**
- Home/Work singleton upsert semantics; named-list CRUD (logic-level DAO).
- Label validation / dedupe.

**On-device verification checklist.**
- [ ] Save from map long-press and from a search result.
- [ ] Home/Work appear as quick actions; tap routes.
- [ ] Survive app restart; deletion works.
- [ ] Surfaces in the Glance widget / Android Auto quick-destinations if wired.

**Risk.** Low. Minor: privacy of "Home" location on a shared device — it's local-only, which is the mitigation.

---

## Feature 10 — Trip computer (moving time, avg/max speed, daylight remaining)  ·  T2

> **P1 = the computer + SunCalc. P2 = the HUD.**

**User story.** Crossing the Nullarbor I want a dash readout: how long I've actually been moving, average and top speed, distance today, and — critically — **how much daylight is left** so I can make camp before dark. Offline.

**Offline / data dependency.** Fully offline. Needs GPS for live speed (`withNetwork`); daylight is pure astronomy (works on `offline` given a position + clock). No pack change.

**Feasibility.** buildable-now. `SunCalc` is pure Kotlin.

**Target modules.** `core:geo` (`SunCalc`), `feature:trip` (`TripComputer`), HUD UI in P2.

**API sketch.**

```kotlin
// core:geo
object SunCalc {
    fun sunrise(date: LocalDate, lat: Double, lon: Double): Instant
    fun sunset(date: LocalDate, lat: Double, lon: Double): Instant
    fun daylightRemaining(now: Instant, lat: Double, lon: Double): Duration
}
// feature:trip
class TripComputer(private val clock: Clock) {
    fun onLocation(fix: LocationFix)
    val stats: StateFlow<TripStats>   // movingTime, stoppedTime, distance, avgSpeed, maxSpeed, daylightLeft
    fun reset()
}
```

Moving vs stopped: speed below a threshold (e.g. < 1 m/s) accrues stopped time, not moving time — so "moving average" excludes rest stops.

**Acceptance criteria.**
- Moving time excludes stationary periods; stopped time tracked separately.
- Avg speed = distance / moving time; max speed = peak instantaneous.
- Daylight-remaining matches an almanac for SA locations within a couple of minutes; goes to zero after sunset; handles polar-free SA latitudes cleanly.
- Reset zeroes everything.

**Unit-test plan (CI).**
- `SunCalc` vs known sunrise/sunset tables (Adelaide, Coober Pedy, Ceduna) within tolerance; date-boundary and far-inland-longitude cases.
- `TripComputer` with synthetic fixes: moving/stopped partition correct; avg excludes stops; max captured; reset.

**On-device verification checklist.**
- [ ] HUD updates smoothly from real GPS without jank.
- [ ] Moving average looks right across a real drive with rest stops.
- [ ] Daylight-remaining matches reality at dusk.
- [ ] Readable in a driving/HUD context (large type, glanceable).

**Risk.** Low. GPS speed noise can inflate "max"; clamp/smooth. Time-zone correctness for daylight (SA is UTC+9:30/+10:30 with DST) — test explicitly.

---

## Feature 11 — Coordinate readout + emergency card (lat/lon + MGRS, nearest hospital/police, UHF)  ·  T3

> **P1 = `CoordinateFormatter` + the `search.db` nearest queries. P2 = the emergency card UI.** The single most outback-defining feature — this is "I need to tell the RFDS where I am."

**User story.** Someone's hurt 200 km from the nearest town, no signal. I open the emergency card: it shows my position in lat/lon **and** MGRS (what emergency services and the military use), the nearest hospital and police station with distance/bearing, and the local UHF channel convention. I read the MGRS grid over the radio.

**Offline / data dependency.** Fully offline — this is the whole point. Coordinates need a position (GPS on `withNetwork`, or a tapped map point on `offline`). Nearest hospital/police come from `search.db` (`class IN ('hospital','police')`), which already covers SA (hospitals ×92; police present). No pack change.

**Feasibility.** buildable-now. MGRS/UTM conversion lives in `core:geo`; the nearest queries reuse `FtsSearchRepository.nearest(...)`.

**Target modules.** `core:geo` (`CoordinateFormatter`), `offline:search` (nearest-by-class), `feature:trip` or a small `feature:emergency` (card UI, P2).

**API sketch.**

```kotlin
// core:geo
object CoordinateFormatter {
    fun toDms(lat: Double, lon: Double): String      // 30°57'12"S 135°45'06"E
    fun toUtm(lat: Double, lon: Double): UtmRef       // zone, easting, northing
    fun toMgrs(lat: Double, lon: Double): String      // e.g. 53J PQ 12345 67890
    fun parse(text: String): GeoPoint?                // accept DMS / decimal / MGRS back
}
// offline:search
suspend fun SearchRepository.nearestByClass(lat: Double, lon: Double, cls: String): SearchResult?
// emergency card aggregates: position (DMS+MGRS), nearestHospital, nearestPolice, UHF guidance
```

**Acceptance criteria.**
- Position shown in **decimal, DMS, and MGRS** simultaneously; values verifiably correct.
- `parse` round-trips MGRS and DMS back to lat/lon.
- Nearest hospital and police shown with distance + bearing, pulled from `search.db`.
- UHF guidance: the standard outback convention (e.g. UHF Ch 40 highway, Ch 5/35 emergency) presented as static reference text — clearly labelled as a convention, not a live channel.
- Works fully offline; one-tap copy of the MGRS string.

**Unit-test plan (CI).**
- MGRS conversion vs authoritative reference points (cross-check against a known proj/MGRS table) across SA's UTM zones (52/53/54) and both hemispherical bands.
- DMS formatting + `parse` round-trip; decimal precision.
- `nearestByClass` SQL/arg construction; distance/bearing math (`MeasureGeometry`).

**On-device verification checklist.**
- [ ] On-hardware GPS position MGRS matches a handheld GPS / authoritative app at the same spot.
- [ ] Nearest hospital/police are actually the nearest (spot-check a few SA towns).
- [ ] Card is readable in bright sun; MGRS copy works.
- [ ] `offline` flavor: tap a map point → full coordinate readout, no permission.

**Risk.** **Correctness is safety-critical** — an MGRS bug could misdirect a rescue. Conversion must be tested hard against an authoritative source and the band-letter / 100 km-square edges verified. UHF text must be clearly "convention, verify locally."

---

## Feature 12 — Geofence / proximity pin alerts  ·  T3

> **P1 = the `ProximityEngine`. P2 = the background service + notifications.**

**User story.** I drop pins on the turnoff to a station gate and a fuel stop. As I approach within my set radius, the app alerts me — so I don't blow past an unmarked turn at 110 km/h. Offline.

**Offline / data dependency.** Fully offline. Needs GPS (`withNetwork`). Pins are the existing local store. No pack change. (Reuses pins, not OS geofencing APIs — those can require network/Play services; we do it on-device.)

**Feasibility.** buildable-now.

**Target modules.** `feature:trip` (`ProximityEngine`), reads `data:pins`, background eval service in P2.

**API sketch.**

```kotlin
class ProximityEngine(private val radiusMeters: Double) {
    fun setTargets(pins: List<GeoPin>)
    fun onLocation(fix: LocationFix): List<ProximityAlert>   // entered-radius edges only
    // edge-triggered: fire on enter, not every fix; re-arm on exit (hysteresis)
}
data class ProximityAlert(val pinId: Long, val distanceMeters: Double)
```

**Acceptance criteria.**
- Alert fires once on entering a pin's radius; does not re-fire while inside; re-arms after leaving.
- Multiple pins handled; per-pin or global radius.
- Hysteresis prevents flicker at the boundary.
- No network; uses our own distance math, not OS/Play geofencing.

**Unit-test plan (CI).**
- `ProximityEngine` with a synthetic path crossing radii: enter fires once, no re-fire inside, exit re-arms; multiple targets; boundary hysteresis.
- Distance math vs known coordinate pairs.

**On-device verification checklist.**
- [ ] Background service evaluates against real GPS with screen off; alert (notification + optional sound) fires approaching a real pin.
- [ ] Battery impact acceptable over a multi-hour drive (sane GPS sampling).
- [ ] No false-positive storm at the boundary on real noisy GPS.

**Risk.** Battery (continuous GPS) — tune sampling and consider speed-adaptive radius. OEM background limits.

---

## Feature 16 — Measure distance / area  ·  T4

> **P1 = `MeasureGeometry`. P2 = the on-map measuring tool.**

**User story.** Planning a paddock or a campsite, I tap points on the offline map and read back the path distance and enclosed area.

**Offline / data dependency.** Fully offline. No permission, no pack change — works on `offline`.

**Feasibility.** buildable-now. Pure geometry in `core:geo`.

**Target modules.** `core:geo` (`MeasureGeometry`), map tool UI in P2.

**API sketch.**

```kotlin
object MeasureGeometry {
    fun pathLengthMeters(points: List<GeoPoint>): Double          // haversine sum
    fun polygonAreaSqMeters(ring: List<GeoPoint>): Double         // spherical excess / shoelace-on-sphere
    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double
}
```

**Acceptance criteria.**
- Path length accurate (haversine) — validated against known distances.
- Polygon area accurate for SA-scale polygons; auto-closes the ring.
- Live update as points are added/dragged/removed; clear in ha / km² / m² and km / m.

**Unit-test plan (CI).**
- Length vs known great-circle distances (e.g. Adelaide–Ceduna).
- Area vs a known-area test polygon (a 1° box, a real reserve outline) within tolerance.
- Degenerate inputs (0/1/2 points) handled.

**On-device verification checklist.**
- [ ] Tap-to-add, drag-to-adjust, undo on the real map; readout updates live.
- [ ] Numbers sane against a known landmark distance.
- [ ] `offline` flavor: fully usable with no permission.

**Risk.** Low. Just pick the right area formula for the scale (spherical, not planar).

---

## Feature 17 — Share location pin + accept incoming `geo:` URIs  ·  T4

> **P1 = `geo:` parse/format. P2 = the Android intent filters + share sheet.**

**User story.** A friend texts me a `geo:` link to a campsite; I tap it and it drops a pin in aus-roads. I long-press my own spot and share it back as a `geo:` link or plain coordinates — no app account, no link shortener, no server.

**Offline / data dependency.** Fully offline. Parsing/formatting needs nothing; sharing uses the OS share sheet. Works on `offline`. No pack change.

**Feasibility.** buildable-now. (Pairs with #4's `geo:` composing and #11's coordinate parsing.)

**Target modules.** `core:geo` (reuse `CoordinateFormatter.parse` + a `GeoUri` helper), `:app` intent filters + share in P2.

**API sketch.**

```kotlin
object GeoUri {
    fun parse(uri: String): GeoPoint?           // geo:lat,lon[;u=..][?q=lat,lon(label)]
    fun format(p: GeoPoint, label: String? = null): String   // "geo:lat,lon?q=lat,lon(label)"
}
// :app — <intent-filter> for scheme "geo" → drop pin / preview
```

**Acceptance criteria.**
- App registers for `geo:` intents; opening one drops/previews a pin at the right spot.
- Share emits a valid `geo:` URI (and a plain "lat, lon" text option) via the OS share sheet.
- Handles `geo:` variants (`;u=`, `?q=…(label)`); bad input → graceful no-op.
- No server, no shortener; coordinates only.

**Unit-test plan (CI).**
- `GeoUri.parse` across the spec's variants + malformed inputs (`Success`/null).
- `format` round-trips through `parse`; label encoding.

**On-device verification checklist.**
- [ ] Tapping a `geo:` link in SMS/another app opens aus-roads and drops the pin.
- [ ] Share from a long-press produces a link that Google Maps / other apps also accept.
- [ ] `offline` flavor: receive + drop works with no network.

**Risk.** Low. Intent-filter collisions with other map apps (the user chooses the handler — fine). Strict URI parsing to avoid junk pins.

---

# PHASE 2 — Device integration & UI

P2 wires the P1 logic to real Android: foreground services, sensors, SMS/alarms, Compose UI, and MapLibre overlays. **Everything in P2 has a mandatory on-device checklist** (see each feature's checklist above for the GPS/SMS/service items; the two features below originate in P2). P2 also delivers the UI/service halves of features 2, 4, 6, 7, 9, 10, 11, 12, 16, 17 — those device checklists live with each feature above.

---

## Feature 8 — Lane guidance / junction view  ·  T2

**User story.** Approaching a multi-lane exit near Adelaide, the nav tells me which lanes to be in (and shows a junction sketch), so I don't miss the ramp — offline.

**Offline / data dependency.** Fully offline. Lane data comes from the **route response** (Valhalla maneuvers), not the network. The `Maneuver` model **already carries `lanes: List<LaneInfo>?`** — so the data path exists; this is rendering. No pack change.

**Feasibility.** buildable-now. (Originates in P2 because it's pure UI/rendering with no new logic to unit-test beyond formatting.)

**Target modules.** `feature:navigation` (lane bar + junction view), maybe a small `:ui:designsystem` lane component.

**API sketch.**

```kotlin
// already present: Maneuver.lanes: List<LaneInfo>?  (LaneInfo.isValid, indications)
@Composable fun LaneGuidanceBar(lanes: List<LaneInfo>)   // highlight valid lanes for the maneuver
// junction view = a stylised arrow diagram from the maneuver geometry; no signpost imagery (offline)
```

**Acceptance criteria.**
- When `Maneuver.lanes` is present, a lane bar highlights the lane(s) to use for the upcoming maneuver.
- Lane bar appears at an appropriate distance before the maneuver and clears after.
- A simple junction/arrow view renders for the active maneuver; absent gracefully when no lane data.
- Pure offline — no signpost/streetview imagery.

**Unit-test plan (CI).**
- `LaneInfo` → lane-bar view-model mapping (which arrows highlighted given indications + validity).
- Distance-to-show threshold logic.

**On-device verification checklist.**
- [ ] Lane bar shows correctly on a real multi-lane SA junction; highlights the right lanes.
- [ ] Timing (appear/clear) feels right while driving.
- [ ] No lane data → nav looks normal (no empty bar).

**Risk.** OSM lane (`turn:lanes`) coverage in regional SA is thin — the feature must degrade invisibly when absent. Don't over-invest in junction artwork (offline, no imagery; a clean arrow diagram is enough).

---

## Feature 13 — Compass / altitude HUD + driving mode  ·  T3

**User story.** Off the grid I want a heading compass and current altitude on a big, glanceable HUD, plus a simplified "driving mode" screen (large speed, next turn, minimal chrome) for long remote legs.

**Offline / data dependency.** Fully offline. Compass = magnetometer; altitude = barometer or GPS altitude; heading from GPS course. Needs sensors (and GPS for course → `withNetwork`); no dangerous permission, no pack change. (Route-derived elevation profiles are a **P4** lib-bump item; this feature uses *device* sensors only.)

**Feasibility.** buildable-now. (P2 — sensor + UI; little pure logic.)

**Target modules.** `feature:trip`/`feature:navigation` (HUD + driving mode), a small sensor wrapper.

**API sketch.**

```kotlin
data class HudState(val headingDeg: Float, val altitudeMeters: Float?, val speedKph: Float?)
class CompassAltimeter { val state: StateFlow<HudState> /* fuses magnetometer + baro/GPS-alt + GPS course */ }
// Driving mode = a full-screen Compose route: big speed, next-turn glyph, ETA, minimal chrome.
```

**Acceptance criteria.**
- Compass shows heading; smoothed against sensor jitter; respects device orientation.
- Altitude from barometer when available, else GPS altitude, labelled with source.
- Driving mode: large speed + next turn + ETA, minimal chrome, dimmable for night.
- Works offline.

**Unit-test plan (CI).**
- Heading smoothing / orientation-fusion math (pure function over sensor samples).
- Unit conversions (m/s↔km/h, m↔ft if offered).
- Driving-mode view-model state reduction.

**On-device verification checklist.**
- [ ] Compass points true on hardware (sanity vs a known direction); handles tilt.
- [ ] Barometric altitude reads plausibly; falls back to GPS altitude on devices without a barometer.
- [ ] Driving mode is glanceable, readable at night, doesn't sleep mid-drive.

**Risk.** Magnetometer calibration / declination (consider true-vs-magnetic north and SA declination). Barometer absent on many phones (fallback path is mandatory). Keep-screen-on battery cost in driving mode.

---

# PHASE 3 — Data-pipeline features

P3 adds data to the pack so the P1/P2 monitors get real inputs. **No Valhalla change.** See `data-pipeline.md` for the pipeline work and effort.

---

## Feature 3 — Speed limit + over-speed alert  ·  T1

> **Split across phases:** the **alert monitor** is P1/P2 (buildable now); the **limit data** is P3 (a `maxspeed` osmium pass into `search.db`). Ship the monitor first; it lights up when the data lands.

**User story.** On a remote highway I drift over the limit without realising. The app knows the posted limit and warns me (a subtle chime / visual) when I exceed it by my chosen margin — offline.

**Offline / data dependency.** Fully offline. Alert needs GPS speed (`withNetwork`). **Limit data** needs the pipeline addition: an osmium pass emitting a road→`maxspeed` table **into `search.db`** (no manifest schema bump if kept inside `search.db` — `data-pipeline.md` §1). Until then the monitor runs against a user-set default or is dormant.

**Feasibility.** **needs-data** for limits; **buildable-now** for the alert. (Speed limit is **not** in the route response and `trace_attributes` is unbridged — `valhalla-capabilities.md` §3.)

**Target modules.** `feature:trip` (`SpeedLimitMonitor`), `offline:search` (a `maxspeed` lookup table in `search.db`), `tools/map-pack-builder` (the osmium pass).

**API sketch.**

```kotlin
// feature:trip — buildable now
class SpeedLimitMonitor(private val overByMarginKph: Int = 5) {
    fun onLocation(fix: LocationFix, postedLimitKph: Int?): SpeedAlert   // None | Over(byKph)
    // hysteresis: fire when speed > limit + margin; clear when back under limit - small band
}
// offline:search — needs P3 data
suspend fun SearchRepository.postedLimitNear(lat: Double, lon: Double, headingDeg: Float?): Int?
// reads the maxspeed table baked into search.db (nearest road segment)
```

**Acceptance criteria.**
- Monitor fires when GPS speed exceeds (limit + margin) and clears with hysteresis (no flicker at the threshold).
- Margin is user-configurable; alert is subtle (chime + visual), not nagging.
- With data present, the posted limit for the current road is shown; absent → monitor is silent (no false alarms).
- Fully offline.

**Unit-test plan (CI).**
- `SpeedLimitMonitor` with synthetic speed series vs a limit: over/clear edges, margin, hysteresis, no-data (null limit) → no alert.
- (P3) `postedLimitNear` SQL/arg construction against the `maxspeed` table; nearest-segment selection logic.

**On-device verification checklist.**
- [ ] (Monitor, pre-data) feeding a manual limit: alert fires/clears correctly on a real drive.
- [ ] (Post-data) posted limit matches reality on several SA roads; matched to the correct road when parallel roads are near.
- [ ] Alert is not annoying at steady highway speed; margin works.

**Risk.** **Map-matching the GPS point to the right road segment without `trace_attributes`** is the hard part — a naive nearest-road can pick a parallel road or service lane. Mitigate with heading-aware nearest-segment selection; accept that true snapping waits for P4 map-matching. OSM `maxspeed` coverage/units (km/h vs implicit) must be normalised in the pass.

---

## Feature 15 — Categorised POI browse (fuel / hospital / toilets / camping)  ·  T4

> **P1/P2 = browse the POIs already in `search.db`. P3 = richer columns (opening hours, finer categories).** The basic browser is buildable now; P3 makes it richer.

**User story.** No signal, low tank, need a loo and a place to camp. I open POI browse, pick "Fuel" / "Toilets" / "Camping", and see them near me with distance and a tap-to-route — all from the offline pack.

**Offline / data dependency.** Fully offline. The data is **already present**: `search_meta` has `amenity=fuel ×534`, `hospital ×92`, `clinic ×105`, `toilets ×118`, `tourism=camp_site ×891`, `caravan_site ×175`, etc. — for all of SA, **no pipeline change** for the basic browser. P3 adds opening-hours / category columns (`data-pipeline.md` §2).

**Feasibility.** **buildable-now** (basic browse) → **needs-data** (opening hours, finer grouping).

**Target modules.** `offline:search` (`browseByClass`), `feature:search` (category browser UI, P2).

**API sketch.**

```kotlin
// offline:search — buildable now (search_meta already has class)
suspend fun SearchRepository.browseByClass(
    cls: String, nearLat: Double, nearLon: Double, limit: Int
): List<SearchResult>     // SELECT … FROM search_meta WHERE class = ? ORDER BY <distance>
// Categories map to OSM class values: fuel→amenity=fuel, hospital→amenity=hospital,
// toilets→amenity=toilets, camping→tourism=camp_site (+ caravan_site), etc.
// P3 adds: opening_hours, finer subcategory columns.
```

**Acceptance criteria.**
- Pick a category → nearby POIs of that class, sorted by distance, with tap-to-route.
- Categories cover at least: fuel, hospital/clinic, toilets, camping/caravan, pharmacy, police, charging.
- Fully offline from `search.db`; correct counts (e.g. fuel returns from the 534).
- (P3) opening-hours shown where available; "open now" filter optional.

**Unit-test plan (CI).**
- `browseByClass` SQL/arg construction; class→OSM-value mapping table; distance ordering logic.
- (P3) opening-hours parse (`opening_hours` syntax) — pure function.

**On-device verification checklist.**
- [ ] Each category returns plausible nearby results around several SA towns.
- [ ] Tap-to-route works from a browse result.
- [ ] `offline` flavor: full browse with no permission/network.
- [ ] (P3) opening-hours render; "open now" matches a known case.

**Risk.** Low for basic browse (data exists). P3 `opening_hours` parsing is fiddly (OSM syntax). Category→class mapping needs care (camping spans `camp_site`+`caravan_site`).

---

# PHASE 4 — Library-bump features (BLOCKED on `valhalla-mobile`)

**These cannot be built until `valhalla-mobile` exposes the relevant JNI symbols.** Today only `route()` is bridged; `isochrone`, `matrix`, `trace_attributes`, `locate`, `height` are modeled but **not callable** (`valhalla-capabilities.md` §1, §3, and the DO-NOT-RETRY). Do **not** attempt these against 0.1.6 — the symbols don't exist. Each item below names its required symbol.

---

## Feature 1 (P4 portion) — Fuel range **isochrone ring** on the map  ·  T1

> **The fuel feature ships in P1/P2 as a SERVO PLANNER.** The on-map **range ring** is this P4 item. See the planner spec immediately below; this is the deferred enhancement.

**User story.** I want a shaded ring on the map showing how far I can drive on my remaining fuel, so I can see at a glance which towns are in reach.

**Offline / data dependency.** Fully offline. **Requires Valhalla `isochrone()`** — currently unbridged. Blocked.

**Feasibility.** **needs-lib-bump** (`isochrone` JNI symbol).

**Target modules.** `routing:engine-api` (an `isochrone()` method once available), `routing:engine-valhalla`, `feature:trip`/map overlay.

**API sketch (post-bump).**

```kotlin
// requires valhalla-mobile to bridge isochrone()
suspend fun RoutingEngine.rangePolygon(origin: GeoPoint, rangeKm: Double, profile: CostingProfile): List<GeoPoint>
// FuelRange (P1) computes rangeKm from fuel+consumption; isochrone turns it into a drive-time/distance polygon
```

**Acceptance criteria (post-bump).** Shaded reachable area for the computed range; updates as fuel/consumption change; offline.

**Risk.** Hard-blocked. **Do not implement against 0.1.6.** Track the upstream lib for the symbol.

---

## Feature 1 (P1/P2 portion) — Fuel / servo planner + range warning  ·  T1  ·  **buildable-now**

> Specced here next to its P4 ring for continuity, but **this half ships in P1 (math) + P2 (UI)** — it is buildable now and does not wait for the bump.

**User story.** Tank's getting low out past Coober Pedy. The app tells me "~180 km range left; next fuel 95 km ahead at the Glendambo roadhouse" and warns me before I'd be stranded — offline.

**Offline / data dependency.** Fully offline. Uses `search.db` `amenity=fuel ×534` + Valhalla `route()` distance to the nearest servo + a fuel-range math model. **No isochrone needed for the planner.** No pack change.

**Feasibility.** **buildable-now** (planner). The isochrone *ring* is the P4 item above.

**Target modules.** `core:geo` (`FuelRange`), `offline:search` (nearest fuel via `browseByClass`/`nearestByClass`), `routing:engine-*` (`route()` distance), `feature:trip` (`FuelPlanner`).

**API sketch.**

```kotlin
// core:geo
object FuelRange {
    fun rangeKm(tankLitres: Double, fractionRemaining: Double, lPer100km: Double): Double
    fun reserveWarning(rangeKm: Double, distanceToNextFuelKm: Double, reserveKm: Double): FuelStatus
    // FuelStatus = Ok | Low | Critical (next fuel beyond range minus reserve)
}
// feature:trip
class FuelPlanner(private val search: SearchRepository, private val engine: RoutingEngine) {
    suspend fun plan(here: GeoPoint, fuelState: FuelState): FuelPlan
    // FuelPlan: rangeKm, nearestServo (name+routeDistanceKm), status, list of in-range servos ahead
}
```

**Acceptance criteria.**
- Computes range from tank size, % remaining, and consumption.
- Finds nearest servo(s) and reports **route** distance (via `route()`), not just crow-flies.
- Warns when the next fuel is beyond (range − reserve): clear Low/Critical states.
- Fully offline from the pack.

**Unit-test plan (CI).**
- `FuelRange.rangeKm` arithmetic; `reserveWarning` thresholds (Ok/Low/Critical edges).
- `FuelPlanner` with a fake `SearchRepository` (returns known servos) + fake engine (known distances): nearest selection, status, in-range filtering.

**On-device verification checklist.**
- [ ] Nearest servo + route distance correct around remote SA (Stuart Hwy roadhouses).
- [ ] Warning triggers at the right point on a real low-fuel scenario.
- [ ] `offline` flavor: full planner with no network (uses pack + on-device routing).

**Risk.** Consumption is user-estimated (garbage-in). `route()` to many servos can be slow — cap candidates via a `search.db` bbox/nearest pre-filter before routing. (The richer **ring** visualisation is the P4 blocker, not this.)

---

## Feature 5 (P4 portion) — Multi-stop **optimisation** (reorder)  ·  T2

> **Multi-stop itself ships in P1/P2 as ordered `via` waypoints** (buildable now — `RouteRequest.via` already exists). **Optimal reordering** ("best order to visit N stops") is this P4 item — it needs the **matrix** API. See the ordered-waypoints spec below.

**Feasibility.** **needs-lib-bump** (`matrix` JNI symbol) for true optimisation. Blocked on 0.1.6.

**Risk.** Hard-blocked for reordering. A naive nearest-neighbour heuristic over repeated `route()` calls is a possible stopgap but is O(N²) route calls and explicitly *not* optimal — treat as a separate, optional, clearly-labelled heuristic, not the matrix feature.

---

## Feature 5 (P1/P2 portion) — Multi-stop waypoints (ordered)  ·  T2  ·  **buildable-now**

**User story.** Adelaide → Clare → Burra → home, in that order, in one route with turn-by-turn through every stop — offline.

**Offline / data dependency.** Fully offline. **`RouteRequest.via: List<GeoPoint>` already exists** — Valhalla `route()` handles multi-location requests. No pack change.

**Feasibility.** **buildable-now** (ordered stops). Reordering is the P4 matrix item above.

**Target modules.** `routing:engine-api` (already has `via`), `feature:navigation` (waypoint list UI + reorder-by-hand, P2).

**API sketch.**

```kotlin
// already present: RouteRequest(origin, destination, via = listOf(...), ...)
// feature:navigation: an editable, hand-orderable waypoint list; nav advances through legs.
```

**Acceptance criteria.**
- Add/remove/manually reorder stops; route passes through them in the given order.
- Turn-by-turn announces arrival at each stop and continues to the next.
- ETA per leg and overall.
- Fully offline.

**Unit-test plan (CI).**
- Waypoint list view-model (add/remove/reorder) reductions.
- `RouteRequest.via` assembly from the list.
- Leg-advance state machine (arrive stop → next leg).

**On-device verification checklist.**
- [ ] A 3-stop route navigates through all stops in order with correct arrival prompts.
- [ ] Reordering by hand re-routes correctly.
- [ ] Per-leg ETA sane on a real drive.

**Risk.** Low for ordered. The *optimisation* (P4) is the blocked part — keep the UI honest ("stops are visited in your order"; no "optimise" button until matrix lands).

---

## Other P4 unlocks (no dedicated feature # — enablers)

- **Elevation / climb profile** (`height` symbol): route elevation graphs, climb totals — feeds a richer #13 (route-derived altitude) and trip planning. **needs-lib-bump.**
- **Map-matching / snapped speed** (`trace_attributes` symbol): snap the GPS trace to roads → reliable road identification for #3's posted-limit lookup, and accurate moving distance for #2/#10. **needs-lib-bump.** This is the principled fix for #3's "which road am I on" problem.

See `valhalla-capabilities.md` for the full symbol list and the DO-NOT-RETRY.

---

# PHASE 5 — Major content layer

The largest single effort; isolated at the end so it never blocks P1–P4.

---

## Feature 14 — Topo / hillshade layer  ·  T4

**User story.** Planning a 4WD trip, I want terrain — contours and hillshade — under the map so I can read the country, offline like everything else.

**Offline / data dependency.** Fully offline **once built**, but it needs an entirely new data path. There is **no DEM / elevation source in the pipeline today.** Requires: a raster-tile (DEM → hillshade/contour) pipeline, a **new pack component**, a **manifest schema v2** to carry it, and MapLibre hillshade rendering (`data-pipeline.md` §3).

**Feasibility.** **major.** This is the only feature gated by a brand-new pipeline component rather than a code/data tweak.

**Target modules.** `tools/map-pack-builder` (DEM → raster tiles), `offline:pack-api` / `data:pack` (manifest v2 + new component download), `feature:*` map layer toggle, MapLibre hillshade/raster-DEM source.

**API sketch (high level).**

```
DEM source (e.g. open elevation data) → reproject → raster tiles (hillshade) and/or contour vector tiles
  → new pack component "terrain" → manifest v2 lists it → pack-downloader fetches it
  → MapLibre raster-dem / hillshade layer toggled in UI
```

**Acceptance criteria.**
- Hillshade (and optionally contours) renders under the existing vector map; toggleable.
- Terrain ships as a **separate, optional** pack component (it's large — don't bloat the base pack).
- Manifest v2 is backward-compatible (v1 packs still load; terrain is additive).
- Fully offline once downloaded; no runtime tile fetch.

**Unit-test plan (CI).**
- Manifest v2 parse/serialise; **v1↔v2 compatibility** (old packs load, new component optional) — pure logic, high-value.
- Component-selection / download-planning logic (which components present, sizes).
- (Pipeline scripts get their own fixture tests outside the app where feasible.)

**On-device verification checklist.**
- [ ] Hillshade renders correctly and aligns with the vector map.
- [ ] Terrain component downloads/installs/toggles independently of the base pack.
- [ ] v1 packs still work after the v2 manifest change (no regression).
- [ ] Pack-size and storage impact acceptable; download is resumable.

**Risk.** **Highest on the roadmap.** DEM licensing/coverage for AU; raster tiles are large (storage + download); manifest v2 migration risk to existing installs; MapLibre raster-DEM/hillshade tuning. Strictly sequenced last and behind a manifest-v2 design review.

---

# Appendix — feasibility at a glance

| # | Feature | Verdict | Lands | Hard dependency |
|---|---------|---------|-------|-----------------|
| 1 | Fuel/servo planner | buildable-now | P1/P2 | — (ring = P4 isochrone) |
| 1r | Fuel range **ring** | needs-lib-bump | P4 | `isochrone` |
| 2 | Track record + GPX | buildable-now | P1/P2 | — |
| 3 | Speed-limit alert | buildable-now (alert) / needs-data (limits) | P1/P2 + P3 | `maxspeed` pass |
| 4 | Trip-share / overdue SMS | buildable-now | P1/P2 | — |
| 5 | Multi-stop (ordered) | buildable-now | P1/P2 | — |
| 5o | Multi-stop **optimise** | needs-lib-bump | P4 | `matrix` |
| 6 | Avoid tolls/unsealed/ferries | buildable-now | P1/P2 | — |
| 7 | Sealed/4WD profile | buildable-now (approx) | P1/P2 | — (no 4WD flag) |
| 8 | Lane guidance / junction | buildable-now | P2 | OSM lane coverage |
| 9 | Favourite trips | buildable-now | P1/P2 | — |
| 10 | Trip computer | buildable-now | P1/P2 | — |
| 11 | Coordinates + emergency card | buildable-now | P1/P2 | — |
| 12 | Geofence / proximity | buildable-now | P1/P2 | — |
| 13 | Compass / altitude HUD | buildable-now | P2 | (route-elev = P4) |
| 14 | Topo / hillshade | major | P5 | DEM pipeline + manifest v2 |
| 15 | POI browse | buildable-now / needs-data | P1/P2 + P3 | richer columns (P3) |
| 16 | Measure distance/area | buildable-now | P1/P2 | — |
| 17 | Share pin / `geo:` URIs | buildable-now | P1/P2 | — |
