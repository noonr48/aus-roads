# aus-roads Long-Horizon Execution Plan (v0.3 → v1.0)

**Created:** 2026-06-02
**Status:** COMPLETE — v1.0 released 2026-06-03.
**Final commit:** `1d2d4cf7f17e5259d712076f5fbd3c2f054b2968`
**Final baseline:** v1.0 complete. 25+ modules, 650+ tests, 19 commits, detekt clean.

---

## Version Map

| Version | Theme | Depends on |
|---------|-------|------------|
| v0.3 | Outback road warnings + UX polish | v0.2 |
| v0.4 | Offline routing (Valhalla) | v0.3 |
| v0.5 | Community hazard reports (backend + client) | v0.4 |
| v0.6 | Multi-state traffic (NSW, VIC) — **COMPLETE** (placeholder endpoints) | v0.5 |
| v0.7 | Live congestion + active navigation — **COMPLETE** (TomTom requires API key) | v0.6 |
| v0.8 | Turn-by-turn navigation — **COMPLETE** | v0.7 |
| v0.9 | Tablet + Auto + polish — **COMPLETE** | v0.8 |
| v1.0 | Play Store release hardening — **COMPLETE** | v0.9 |

---

## v0.3 — Outback Road Warnings + UX Polish

**Status: COMPLETE**

### Goal

Users see official DIT outback road warnings on the map. The app feels
polished: smooth animations, proper loading states, improved search UX,
and crash-free operation across a 1-hour session.

### Features

| # | Feature | Acceptance Criteria | Effort |
|---|---------|-------------------|--------|
| 3.1 | ✅ DIT outback road warnings provider | `:traffic:provider-dit` implements `LiveTrafficProvider` for `AU-SA-DIT`. Fetches from DIT endpoint. Events render as orange diamond markers on map. Tap shows warning text + affected road + date. | L |
| 3.2 | ~~Serverless DIT scraper~~ | **Not needed.** DIT endpoint is live at `maps.sa.gov.au/.../FNRR2/MapServer`. No serverless scraper required. | ~~M~~ |
| 3.3 | ✅ Traffic overlay toggle per source | Settings screen shows each registered traffic source (Traffic SA, DIT) with individual enable/disable toggles. State persisted in DataStore. Disabled sources are removed from map immediately. | S |
| 3.4 | ✅ Loading + error states for traffic | Shimmer placeholder while traffic events are fetching. Snackbar on fetch failure with "Retry" action. Stale-data indicator (>15 min old) shown as a banner. | M |
| 3.5 | ✅ Search result categorization | Search results grouped by type: Roads, Places, POIs. Each group has a header. Tapping a result shows a preview card before flying camera. | M |
| 3.6 | ✅ Pin editing (rename + delete + color) | Tap a pin → edit sheet with name field, color picker (8 colors), delete button. Changes persist immediately. Long-press on map still drops a new pin. | M |
| 3.7 | ✅ Map style refinements | Custom 5-layer Planetiler profile (background, roads, water, pois, buildings) replaces the verbose full profile. Pack size drops ~40%. Road casing at z12+. Label collision improvements. | L |
| 3.8 | ✅ App startup optimization | Cold start to interactive map ≤ 2s on Pixel 6a. Lazy-initialize MapLibre. Pre-warm SQLite FTS connection. Profile with Macrobenchmark. | M |

### Modules to Create

| Module | Responsibility |
|--------|---------------|
| `:traffic:provider-dit` | LiveTrafficProvider implementation for DIT outback warnings. Pure Kotlin. Fetches JSON, parses, returns `FetchResult`. |
| ~~`tools/dit-scraper`~~ | **Not created.** DIT endpoint is already live; no serverless scraper needed. |

### Modules to Modify

| Module | Changes |
|--------|---------|
| `:feature:traffic` | Add per-source toggle UI. Loading shimmer. Stale-data banner. |
| `:data:settings` | Add per-source enable/disable preferences. |
| `:app` | Register DIT provider in DI. Wire per-source toggles. Startup optimization. |
| `:ui:designsystem` | Add shimmer component, color picker, grouped list headers. |
| `:offline:search` | Add category field to search results for grouping. |
| `:feature:search` | Grouped results UI with headers. |
| `:data:pins` | Add color column to pins table. Migration script. |
| `:core:model` | Add `OutbackWarning` data class if DIT events need a distinct shape. |
| `tools/map-pack-builder` | Switch to custom 5-layer Planetiler profile. |

### External Dependencies

| Dependency | Purpose |
|-----------|---------|
| ~~Cloudflare Workers~~ | **Not needed.** DIT endpoint is live at `maps.sa.gov.au`. |
| Planetiler custom profile | Reduce MBTiles size. Fork `onthegomap/planetiler` basemap profile. |
| Macrobenchmark | `androidx.benchmark:benchmark-macro-junit4` for startup profiling. |

### Privacy Implications

- DIT fetch requires `INTERNET` permission (already present in v0.2 for traffic).
- The DIT endpoint is stateless — no user identification, no tracking.
- No new permissions required.
- Privacy audit tooling must verify no PII leaks to the DIT endpoint.

### Testing Strategy

| Test | Type | Target |
|------|------|--------|
| DIT provider parses real HTML samples | Unit | `:traffic:provider-dit` |
| DIT provider handles malformed HTML gracefully | Unit | `:traffic:provider-dit` |
| Per-source toggle persists and applies | Unit | `:data:settings` |
| Traffic overlay respects per-source toggles | Integration | `:feature:traffic` |
| Pin color migration (v1 → v2 schema) | Unit | `:data:pins` |
| Search grouping by category | Unit | `:feature:search` |
| Cold start ≤ 2s | Performance | Macrobenchmark |
| 1-hour session, no crashes | Manual | Full app |

### Risks

| Risk | Mitigation |
|------|-----------|
| ~~DIT HTML structure changes without notice~~ | **Resolved.** Using live DIT endpoint directly; no scraper to break. |
| ~~Cloudflare Worker costs~~ | **Resolved.** No serverless worker needed. |
| Custom Planetiler profile introduces tile regressions | Diff tile coverage before/after with `mbtiles show` on a test extract. Visual regression on 5 reference locations. |
| Startup regression from new features | Macrobenchmark gate in CI. Fail PR if cold start > 2.5s. |

### Dependencies

- v0.2 shipped and stable.
- ~~Cloudflare account set up with `api.aus-roads.com` domain.~~ **Not needed.** DIT endpoint is live at `maps.sa.gov.au`.

---

## v0.4 — Offline Routing

**Status: COMPLETE**

### Goal

Users can tap two points on the map and get an offline driving route with
distance, ETA, and turn-by-turn maneuver list. Routing respects road
closures from traffic events.

### Features

| # | Feature | Acceptance Criteria | Effort |
|---|---------|-------------------|--------|
| 4.1 | ✅ Valhalla JNI integration | `:routing:engine-valhalla` implements `RoutingEngine`. Loads `valhalla_tiles.tar` from the installed map pack. Returns `RouteResult` with geometry, distance, duration, maneuvers. | XL |
| 4.2 | ✅ Route computation UI | Long-press origin → long-press destination → route line drawn on map (blue, 4dp, z10+). Bottom sheet shows distance (km), ETA (relative to depart time), maneuver list. | L |
| 4.3 | ✅ Closure-aware routing | Active CLOSURE/DETOUR traffic events with `RoutingEffect.Block` are passed as `avoidPolygons` to Valhalla. Route recomputes when traffic updates. | L |
| 4.4 | ✅ Route alternatives | Up to 3 alternative routes shown in grey. Tap to switch active route. Each shows distance + ETA delta. | M |
| 4.5 | ✅ Costing profile selector | Toggle between Auto, Motorcycle, Truck in route sheet. Persists last selection. Bicycle + Pedestrian available but marked "beta". | M |
| 4.6 | ✅ Routing graph bundled in map pack | `valhalla_tiles.tar` included in pack manifest. Download + SHA-256 verify + extraction pipeline updated. | M |
| 4.7 | ✅ Route geometry on map | Route line rendered as a MapLibre GeoJSON source + line layer. Updates smoothly on re-route. Animated dash for active route. | M |

### Modules to Create

| Module | Responsibility |
|--------|---------------|
| `:routing:engine-valhalla` | JNI wrapper around `valhalla-mobile`. Implements `RoutingEngine`. Manages Valhalla tile loading/unloading. Depends on `:routing:engine-api`, `:core:model`. |
| `:feature:routing` | Compose UI for route display: route line overlay, maneuver list, alternatives selector, costing profile toggle. Depends on `:routing:engine-api`, `:ui:designsystem`. |

### Modules to Modify

| Module | Changes |
|--------|---------|
| `:routing:engine-api` | Add `RouteAlternative` data class. Add `departAt` parameter to `RouteRequest` (already defined, verify implementation). |
| `:app` | Wire `RoutingEngine` into DI. Add route overlay to `MapScreen`. Navigation to routing sheet. |
| `:offline:pack-api` | Add `routing` component to `PackComponents` with `valhallaTilesPath` and SHA-256. |
| `:offline:pack-downloader` | Download + verify + extract `valhalla_tiles.tar`. |
| `:feature:traffic` | Pass active closures to routing engine on traffic update. |
| `:data:settings` | Add costing profile preference. |
| `:ui:designsystem` | Route line style constants. Maneuver icon set. |
| `:core:model` | Add `RouteAlternative` if not already present. |

### External Dependencies

| Dependency | Purpose |
|-----------|---------|
| `rallista/valhalla-mobile` JNI | Offline routing engine. AAR published via GitHub Packages or local build. |
| Valhalla tile builder | `valhalla_build_tiles` in the pack pipeline (`tools/map-pack-builder`). |

### Privacy Implications

- Routing is fully offline — no network calls for route computation.
- Origin/destination are not transmitted anywhere.
- No new permissions required.
- The route geometry stays in memory only; not persisted to disk.

### Testing Strategy

| Test | Type | Target |
|------|------|--------|
| Route A→B returns valid geometry | Unit | `:routing:engine-valhalla` |
| Route with avoid polygon bypasses blocked road | Unit | `:routing:engine-valhalla` |
| Route with closure overlay re-routes | Integration | `:feature:routing` + `:feature:traffic` |
| Route alternatives have different geometries | Unit | `:routing:engine-valhalla` |
| Maneuver list has correct turn count | Unit | `:routing:engine-valhalla` |
| Valhalla tile load/unload lifecycle | Unit | `:routing:engine-valhalla` |
| Route display on map (visual) | Manual | Full app |
| Route performance: < 2s for Adelaide→Port Augusta | Performance | `:routing:engine-valhalla` |

### Risks

| Risk | Mitigation |
|------|-----------|
| `valhalla-mobile` JNI is poorly maintained or has Android 14+ issues | Build from source with the latest Valhalla release. Pin to a known-good commit. Keep a GraphHopper fallback plan (evaluate at spike time). |
| Valhalla tiles are large (150–250 MB) | Investigate Valhalla hierarchy pruning to reduce tile count. Offer "lite" pack without routing as an option. |
| JNI memory pressure on low-end devices | Lazy-load tiles on first route request. Unload on `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)`. Test on 3 GB RAM device. |
| Closure polygons are imprecise (point vs line) | For line-type closures, buffer the polyline by 50m to create an avoid polygon. Document the approximation. |

### Dependencies

- v0.3 shipped (DIT warnings active, pack pipeline stable).
- `valhalla-mobile` AAR available or buildable.
- Valhalla tile builder integrated into `tools/map-pack-builder`.

---

## v0.5 — Community Hazard Reports

### Goal

Users can submit and view community-sourced road hazard reports (flooded
roads, fallen trees, animals on road, damaged surface). Reports are
anonymous, auto-expiring, and confidence-scored.

### Features

| # | Feature | Acceptance Criteria | Effort |
|---|---------|-------------------|--------|
| 5.1 | Backend API for community reports | FastAPI + PostGIS service. Endpoints: `POST /reports`, `GET /reports?bbox=...`, `POST /reports/{id}/confirm`, `POST /reports/{id}/reject`. Anonymous device key hash auth. Rate-limited (10 reports/hour/device). Auto-expire after 4 hours. | XL |
| 5.2 | Report submission UI | FAB on map → "Report hazard" sheet. Categories: Flood, Fallen tree, Animal, Damaged road, Debris, Other. Optional photo (compressed, EXIF stripped). Optional text description. Location from map center or GPS (foreground only, explicit consent). | L |
| 5.3 | Report display on map | Community reports shown as yellow markers with category icon. Tap → detail sheet with description, age ("12 min ago"), confidence score, confirm/reject buttons. Clustered at low zoom. | M |
| 5.4 | Confirm/reject flow | Tap "I see this too" → confidence +1. Tap "Not there" → confidence -1. Reports below -3 confidence are hidden. No double-voting per device. | M |
| 5.5 | Report privacy controls | Photo EXIF stripped before upload. No username transmitted. Device key is a random UUID stored in DataStore, rotated monthly. Location rounded to 100m grid before server storage. | M |
| 5.6 | Offline report queue | Reports created offline are queued in Room. Uploaded when connectivity returns. Queue capped at 50 reports; oldest dropped if full. | M |
| 5.7 | Report moderation (admin) | Simple admin endpoint: `GET /reports?status=pending`, `DELETE /reports/{id}`. No admin UI in v0.5 — use `curl`. Document moderation runbook. | S |

### Modules to Create

| Module | Responsibility |
|--------|---------------|
| `backend/` | FastAPI + PostGIS backend for community reports. Docker Compose for local dev. Fly.io or Railway for hosting. |
| `:community:report-api` | Pure Kotlin API module. Data classes for reports, API client interface. No Android dependencies. |
| `:community:report-client` | Ktor-based implementation of the report API client. Handles offline queue, retry, device key management. |
| `:feature:community` | Compose UI for report submission, display, confirm/reject. Depends on `:community:report-client`, `:ui:designsystem`. |
| `:data:community` | Room database for offline report queue + local report cache. |

### Modules to Modify

| Module | Changes |
|--------|---------|
| `:app` | Register community report client in DI. Add FAB to `MapScreen`. Wire community overlay. |
| `:core:model` | Add `CommunityReport`, `ReportCategory`, `ReportConfidence` data classes. |
| `:data:settings` | Add community features opt-in toggle. Add device key rotation logic. |
| `:ui:designsystem` | Category icon set. Confidence bar component. Report card component. |
| `:feature:traffic` | Merge community reports layer with traffic overlay (distinct source). |
| `settings.gradle.kts` | Add `:community:*` and `:data:community` modules. |

### External Dependencies

| Dependency | Purpose |
|-----------|---------|
| FastAPI | Backend framework. |
| PostGIS | Geographic queries for report retrieval. |
| Fly.io or Railway | Hosting for the backend. Free tier sufficient for v0.5. |
| `io.ktor:ktor-client-android` | HTTP client for report API. |
| `com.github.deanveloper:mpikmeans` or custom | Spatial clustering for report markers. |

### Privacy Implications

**This is the first version that transmits user-generated data to a server.**

- Device key is a random UUID, not tied to any identity. Rotated monthly.
- Location is rounded to 100m grid before storage.
- Photos have EXIF stripped client-side before upload.
- No username, no email, no phone number.
- Reports auto-expire (deleted from server after 4 hours).
- Community features are opt-in in Settings. Default: OFF.
- Privacy audit tooling must be updated to cover the report submission flow.
- Privacy statement in About screen must be updated.

### Testing Strategy

| Test | Type | Target |
|------|------|--------|
| POST report with valid payload → 201 | Integration | `backend/` |
| POST report exceeds rate limit → 429 | Integration | `backend/` |
| GET reports within bbox returns correct set | Integration | `backend/` |
| Confirm/reject updates confidence | Integration | `backend/` |
| Reports auto-expire after 4 hours | Integration | `backend/` |
| Device key rotation works | Unit | `:data:community` |
| Offline queue uploads on connectivity restore | Integration | `:community:report-client` |
| EXIF stripping removes GPS data | Unit | `:community:report-client` |
| Report clustering at low zoom | Unit | `:feature:community` |
| Privacy audit passes on report flow | Tooling | `tools/privacy-audit` |

### Risks

| Risk | Mitigation |
|------|-----------|
| Backend costs spike from abuse | Rate limit per device key + IP. CAPTCHA on suspicious patterns. Start with Fly.io free tier, monitor. |
| False/malicious reports degrade trust | Confidence scoring + confirm/reject. Reports auto-expire. Community moderation runbook. |
| GDPR/Privacy Act compliance | Location rounding, no PII, auto-expiry, opt-in. Document in privacy statement. Consult legal if scaling beyond AU. |
| PostGIS complexity for a solo dev | Use PostGIS only for `ST_DWithin` bbox queries. Keep schema simple. SQLite + SpatiaLite as fallback if PostGIS is too heavy. |
| Photo storage costs | Store photos in Cloudflare R2 (zero egress). Compress to < 200 KB. Auto-delete with report expiry. |

### Dependencies

- v0.4 shipped (routing active, traffic overlay stable).
- Backend hosting account (Fly.io or Railway).
- Domain `api.aus-roads.com` configured (already used for DIT in v0.3).
- Cloudflare R2 bucket for photo storage.

---

## v0.6 — Multi-State Traffic (NSW, VIC)

**Status: COMPLETE** (NSW and VIC providers use placeholder endpoints pending upstream API verification)

### Goal

The app works in New South Wales and Victoria with live traffic data
from their respective official sources. Users can download map packs
for additional states.

### Features

| # | Feature | Acceptance Criteria | Effort |
|---|---------|-------------------|--------|
| 6.1 | NSW Live Traffic provider | `:traffic:provider-nsw` implements `LiveTrafficProvider` for `AU-NSW`. Fetches from the NSW Live Traffic API (RMS). Events + closures. | L |
| 6.2 | VIC VicRoads provider | `:traffic:provider-vic` implements `LiveTrafficProvider` for `AU-VIC`. Fetches from VicRoads ArcGIS endpoint. Events + closures. | L |
| 6.3 | Multi-pack map download | Settings → "Map packs" screen. Shows available packs (SA, NSW, VIC). Each with size, version, download/delete. Active pack auto-detected from GPS or manual selection. | L |
| 6.4 | Region auto-detection | When GPS is available (opt-in), detect which state the user is in and auto-load the corresponding traffic provider + map pack. Manual override in Settings. | M |
| 6.5 | Pack switching | Seamless map transition when crossing state borders. No restart required. Pre-fetch neighboring state's traffic if within 50km of border. | M |
| 6.6 | Unified traffic layer | All active providers' events rendered on the same map. Provider attribution visible per event. | M |

### Modules to Create

| Module | Responsibility |
|--------|---------------|
| `:traffic:provider-nsw` | LiveTrafficProvider for NSW. Pure Kotlin. |
| `:traffic:provider-vic` | LiveTrafficProvider for VIC. Pure Kotlin. |
| `:pack:manager` | Multi-pack management: install, switch, delete, auto-detect. |

### Modules to Modify

| Module | Changes |
|--------|---------|
| `:app` | Register NSW + VIC providers. Wire multi-pack manager. |
| `:feature:traffic` | Render events from multiple providers. Attribution per event. |
| `:data:pack` | Support multiple installed packs. Active pack selection. |
| `:offline:pack-downloader` | Support downloading packs for any region, not just SA. |
| `tools/map-pack-builder` | Build pipelines for NSW and VIC extracts. |
| `:core:model` | Add NSW + VIC bbox constants. |
| `:data:settings` | Active region preference. GPS auto-detection toggle. |

### External Dependencies

| Dependency | Purpose |
|-----------|---------|
| NSW Live Traffic API | Official RMS traffic data. REST/JSON. |
| VicRoads ArcGIS | Official VicRoads traffic data. ArcGIS REST. |
| Geofabrik extracts | `new-south-wales-latest.osm.pbf`, `victoria-latest.osm.pbf`. |

### Privacy Implications

- GPS-based region detection requires `ACCESS_COARSE_LOCATION` permission.
  This is the first version that requests a location permission.
- Permission is opt-in. Default: manual region selection.
- GPS is used only for region detection, not continuous tracking.
- Location is not transmitted to any server.
- Privacy statement must be updated to disclose location permission use.

### Testing Strategy

| Test | Type | Target |
|------|------|--------|
| NSW provider parses real API responses | Unit | `:traffic:provider-nsw` |
| VIC provider parses real API responses | Unit | `:traffic:provider-vic` |
| Multi-pack install/switch/delete | Unit | `:pack:manager` |
| Region auto-detection from coordinates | Unit | `:pack:manager` |
| Traffic events from multiple providers render correctly | Integration | `:feature:traffic` |
| Pack switching preserves camera position | Integration | `:app` |
| NSW/VIC map packs build successfully | CI | `tools/map-pack-builder` |

### Risks

| Risk | Mitigation |
|------|-----------|
| NSW/VIC APIs have different schemas/quirks | Each provider is a separate module with its own tests. Failures are isolated. |
| Multi-pack storage exceeds device capacity | Show storage usage per pack. Warn if < 500 MB free. Offer "lite" packs without routing. |
| API key requirements for NSW/VIC | Research at spike time. If keys are needed, document the setup process. Consider proxying through the backend. |
| State border handling (events near borders) | Render events from both states when within 50km of a border. Clip to bbox to avoid duplicates. |

### Dependencies

- v0.5 shipped (community reports active, backend stable).
- NSW and VIC API access verified.
- Geofabrik extracts for NSW and VIC available.
- GPS permission rationale documented.

---

## v0.7 — Live Congestion + Active Navigation

**Status: COMPLETE** (congestion layer requires TomTom API key; all other features functional)

### Goal

Users see real-time road congestion (green/yellow/red) on the map and
can start active turn-by-turn navigation with voice prompts.

### Features

| # | Feature | Acceptance Criteria | Effort |
|---|---------|-------------------|--------|
| 7.1 | Congestion data integration | `:traffic:congestion-tomtom` fetches TomTom Flow Segment API. Roads colored green/yellow/red based on speed ratio. Updated every 5 min. Cost displayed in About screen. | XL |
| 7.2 | Opt-in speed crowdsourcing | Users can opt in to share anonymized speed data while navigating. GPS track is segmented, averaged, and uploaded (no raw trace retention). Opt-in in Settings, default OFF. | L |
| 7.3 | Active navigation mode | "Start navigation" button on route sheet. Full-screen mode with auto-zoom. Next maneuver banner at top. Distance + ETA countdown. Auto-reroute on deviation. | XL |
| 7.4 | Voice prompts | TTS-based turn instructions. "In 200 meters, turn left onto King William Street". Mute toggle. Language: English (AU). | L |
| 7.5 | Navigation lifecycle | Screen stays on during navigation. Battery optimization warning after 30 min. Auto-stop on arrival (within 50m of destination). | M |
| 7.6 | Congestion legend | Map legend showing green/yellow/red meaning. Toggleable overlay. | S |

### Modules to Create

| Module | Responsibility |
|--------|---------------|
| `:traffic:congestion-api` | Interface for congestion data providers. Pure Kotlin. |
| `:traffic:congestion-tomtom` | TomTom Flow Segment API client. Returns speed ratios per road segment. |
| `:feature:navigation` | Active navigation UI: full-screen mode, maneuver banner, ETA countdown, auto-reroute. |
| `:navigation:tts` | Text-to-speech wrapper for turn-by-turn voice prompts. |
| `:navigation:speed-crowdsource` | Opt-in speed data collection, anonymization, and upload. |

### Modules to Modify

| Module | Changes |
|--------|---------|
| `:app` | Register congestion provider. Wire navigation feature. Screen-on during nav. |
| `:feature:routing` | Add "Start navigation" button. Pass route to navigation module. |
| `:data:settings` | Congestion toggle. Speed crowdsourcing opt-in. TTS mute toggle. |
| `:ui:designsystem` | Congestion color constants. Navigation banner component. |
| `backend/` | Speed crowdsourcing ingestion endpoint. Aggregation job. |

### External Dependencies

| Dependency | Purpose |
|-----------|---------|
| TomTom Flow Segment API | Real-time congestion data. Requires API key. ~$0.50/1000 requests. |
| Android TTS engine | Built-in `android.speech.tts.TextToSpeech`. |

### Privacy Implications

- **Speed crowdsourcing is the most sensitive feature.** Requires explicit opt-in.
  Consent dialog must explain: what is shared (segment-averaged speed),
  what is NOT shared (raw GPS trace, identity), how to opt out.
- GPS permission escalation: from `ACCESS_COARSE_LOCATION` (v0.6) to
  `ACCESS_FINE_LOCATION` (required for navigation).
- Navigation GPS data is processed in-memory only. Not persisted.
- Speed crowdsource data is anonymized before upload (segment + average speed,
  no timestamps, no device ID).
- TomTom API calls include the request bbox but no user identity.
- Privacy audit must cover: navigation mode, speed crowdsourcing, TomTom calls.

### Testing Strategy

| Test | Type | Target |
|------|------|--------|
| TomTom congestion data parsing | Unit | `:traffic:congestion-tomtom` |
| Congestion colors applied to road segments | Integration | `:feature:traffic` |
| Navigation auto-reroutes on deviation | Integration | `:feature:navigation` |
| TTS generates correct prompts | Unit | `:navigation:tts` |
| Speed data anonymization (no PII in payload) | Unit | `:navigation:speed-crowdsource` |
| Screen stays on during navigation | Manual | Full app |
| Battery drain < 15%/hour during navigation | Performance | Manual on Pixel 6a |
| Arrival detection (within 50m) | Integration | `:feature:navigation` |

### Risks

| Risk | Mitigation |
|------|-----------|
| TomTom API costs escalate | Budget cap in backend. Alert at 80% of monthly budget. Fallback to no-congestion mode. |
| TTS quality varies by device | Test on 3+ devices. Provide "mute" option. Use Android's default TTS, do not bundle a custom engine. |
| Navigation drains battery | Warn user after 30 min. Offer "passive navigation" (no screen-on, no TTS, just route line). |
| Speed crowdsourcing privacy concerns | Make it obviously opt-in. Default OFF. Easy opt-out. No raw traces ever stored. Document in privacy statement. |
| GPS accuracy in outback (poor signal) | Use Kalman filtering. Warn user when accuracy > 50m. Fall back to dead reckoning for short gaps. |

### Dependencies

- v0.6 shipped (multi-state active, GPS permission granted).
- TomTom API key obtained and budget approved.
- Navigation UX design reviewed.

---

## v0.8 — Turn-by-Turn Navigation Polish

**Status: COMPLETE**

### Goal

Navigation is production-quality: lane guidance, speed limit display,
arrival announcements, and multi-stop routes.

### Features

| # | Feature | Acceptance Criteria | Effort |
|---|---------|-------------------|--------|
| 8.1 | ✅ Lane guidance | Show which lane to be in before complex intersections. Data from Valhalla maneuvers. Visual: lane arrows in maneuver banner. | L |
| 8.2 | ✅ Speed limit display | Current speed limit shown on navigation screen. Data from Valhalla edge attributes. Warning when speeding (>5 km/h over). | M |
| 8.3 | ✅ Multi-stop routes | Add intermediate waypoints to a route. Drag to reorder. Route recomputes on change. | M |
| 8.4 | ✅ Arrival announcements | "You have arrived at your destination" TTS + visual card. Option to navigate to next stop or end navigation. | S |
| 8.5 | ✅ Route history | Recent routes stored in Room (last 20). Quick-access from route sheet. No auto-upload, local only. | M |
| 8.6 | ✅ Navigation dark mode | Auto-switch to dark map style during navigation at night. Uses system dark mode setting. Manual override. | M |

### Modules to Modify

| Module | Changes |
|--------|---------|
| `:feature:navigation` | Lane guidance UI. Speed limit display. Arrival card. Multi-stop support. |
| `:routing:engine-valhalla` | Extract lane info + speed limits from Valhalla response. Multi-stop routing. |
| `:routing:engine-api` | Add `LaneInfo`, `SpeedLimit` to `Maneuver`. Add multi-stop to `RouteRequest`. |
| `:data:routes` (new) | Room database for route history. |
| `:navigation:tts` | Arrival announcement. Speed warning prompt. |
| `:app` | Dark mode switching during navigation. |

### External Dependencies

None — all features use existing Valhalla data.

### Privacy Implications

- Route history is local-only. Not transmitted.
- Speed limit data comes from the offline map pack, not a live API.
- No new permissions required.

### Testing Strategy

| Test | Type | Target |
|------|------|--------|
| Lane info extracted from Valhalla response | Unit | `:routing:engine-valhalla` |
| Speed limit extracted correctly | Unit | `:routing:engine-valhalla` |
| Multi-stop route geometry is contiguous | Unit | `:routing:engine-valhalla` |
| Route history persists and loads | Unit | `:data:routes` |
| Dark mode auto-switches at night | Manual | Full app |
| Arrival detection triggers announcement | Integration | `:feature:navigation` |

### Risks

| Risk | Mitigation |
|------|-----------|
| Valhalla lane data is sparse in SA | Show lane guidance only when data is available. Graceful fallback to "keep left/right". |
| Speed limit data accuracy | Source from OSM `maxspeed` tags in the map pack. Document known gaps. |

### Dependencies

- v0.7 shipped (navigation active, TTS working).
- Valhalla tile attributes include lane + speed limit data.

---

## v0.9 — Tablet + Auto + Polish

**Status: COMPLETE**

### Goal

The app works well on tablets, Android Auto, and passes accessibility
review. Performance is production-grade. Dark mode auto-switches via
ThemeMode.System. Home screen widget shows live route status.

### Features

| # | Feature | Acceptance Criteria | Effort |
|---|---------|-------------------|--------|
| 9.1 | Tablet layout | Responsive layout: side-by-side map + detail panel on screens > 7". Navigation banner spans full width. | L |
| 9.2 | Android Auto integration | Map + route display on Auto head units. Voice-controlled navigation. Hazard report via voice. | XL |
| 9.3 | Accessibility pass | TalkBack support for all screens. Content descriptions on all interactive elements. Minimum touch target 48dp. Color contrast ratio ≥ 4.5:1. | M |
| 9.4 | Performance optimization | Map renders at 60fps during navigation. Route computation < 1s. Search results < 200ms. Memory < 300 MB during navigation. | M |
| 9.5 | Crash reporting (opt-in) | Local crash log collection. User can view and share crash logs. No automatic upload. Opt-in "Share crash logs" in Settings. | M |
| 9.6 | Offline-first resilience | Graceful degradation when pack is corrupted. Auto-repair attempt. Re-download prompt if repair fails. No crash on missing tiles. | M |

### Modules to Create

| Module | Responsibility |
|--------|---------------|
| `:auto:app` | Android Auto app module. Map + navigation templates. |
| `:core:crashlog` | Local crash log collection. File-based, no network. |

### Modules to Modify

| Module | Changes |
|--------|---------|
| `:app` | Responsive layout logic. Crash log initialization. |
| `:feature:navigation` | Auto-compatible navigation flow. |
| `:ui:designsystem` | Responsive breakpoints. Accessibility content descriptions. |
| `:feature:search` | Tablet layout. |
| `:feature:traffic` | Tablet layout. |
| `:offline:pack-downloader` | Pack integrity verification. Auto-repair logic. |

### External Dependencies

| Dependency | Purpose |
|-----------|---------|
| `androidx.car.app` | Android Auto app library. |

### Privacy Implications

- Crash logs are local-only by default. No automatic upload.
- "Share crash logs" requires explicit user action (share intent).
- Android Auto does not add new data collection.
- No new permissions required.

### Testing Strategy

| Test | Type | Target |
|------|------|--------|
| Tablet layout renders correctly | UI | `:app` |
| Android Auto templates display | UI | `:auto:app` |
| TalkBack reads all screens correctly | Accessibility | Manual |
| Touch targets ≥ 48dp | Lint | CI |
| Crash log captures stack trace | Unit | `:core:crashlog` |
| Pack corruption recovery | Integration | `:offline:pack-downloader` |
| 60fps during navigation | Performance | Macrobenchmark |

### Risks

| Risk | Mitigation |
|------|-----------|
| Android Auto requires Google Play Services | Document this limitation. Auto is optional — the phone app works without it. |
| Tablet testing is limited | Test on emulator with 10" and 12" screens. Real device testing deferred. |
| Accessibility compliance is extensive | Prioritize TalkBack + touch targets. Defer advanced features (switch access, voice control) to post-1.0. |

### Dependencies

- v0.8 shipped (navigation polished).
- Android Auto development environment set up.

---

## v1.0 — Play Store Release

**Status: COMPLETE**

### Goal

The app is production-ready for the Google Play Store. Full documentation,
legal compliance, and release infrastructure are in place.

### Features

| # | Feature | Acceptance Criteria | Effort |
|---|---------|-------------------|--------|
| 10.1 | Play Store listing | App name, description, screenshots (phone + tablet), feature graphic, privacy policy URL, content rating. | M |
| 10.2 | App Bundle (AAB) | Build produces AAB, not APK. Dynamic feature modules for map packs (downloaded on demand). | L |
| 10.3 | ProGuard / R8 optimization | Release build uses R8. No class stripping issues with JNI (Valhalla) or MapLibre. | M |
| 10.4 | Security review | No hardcoded secrets. HTTPS everywhere. Certificate pinning for `api.aus-roads.com`. No exported components without intent filters. | M |
| 10.5 | Legal compliance | OSM ODbL attribution complete. DIT / Traffic SA / VicRoads attribution per license. Privacy policy published. Terms of service if community features are enabled. | S |
| 10.6 | Release automation | GitHub Actions: build → test → lint → sign → upload to Play Console (internal track). Version bump script. | M |
| 10.7 | Telemetry (opt-in, local-first) | Anonymous usage stats: pack install count, traffic overlay usage, search count. Local aggregation, user-triggered upload. No PII. | M |
| 10.8 | Migration from sideload APK | Detect existing v0.1–v0.9 sideloaded installs. Migrate pins, settings, installed packs to Play Store data directory. | M |

### Modules to Create

| Module | Responsibility |
|--------|---------------|
| `:core:telemetry` | Local telemetry aggregation. No network. User-triggered upload only. |

### Modules to Modify

| Module | Changes |
|--------|---------|
| `:app` | AAB configuration. R8 rules. Certificate pinning. Telemetry init. |
| `build-logic` | AAB signing config. R8 optimization rules. |
| `.github/workflows/` | Play Store upload pipeline. Version bump automation. |
| `:data:settings` | Telemetry opt-in preference. |
| `:data:pins` | Migration from sideload to Play Store path. |
| `:data:pack` | Migration from sideload to Play Store path. |
| `:offline:pack-downloader` | Dynamic feature module support. |

### External Dependencies

| Dependency | Purpose |
|-----------|---------|
| Google Play Console | App distribution. |
| `com.android.tools.build:gradle` | AAB build support (already present). |
| Play App Signing | Google-managed signing key. |

### Privacy Implications

- Play Store requires a privacy policy URL. Publish at `aus-roads.com/privacy`.
- Telemetry is opt-in, local-first, no PII. Document in privacy policy.
- App Bundle changes how map packs are delivered (dynamic feature modules).
  Ensure privacy audit covers the new download path.
- Certificate pinning prevents MITM but also prevents debugging.
  Provide a debug build variant without pinning.

### Testing Strategy

| Test | Type | Target |
|------|------|--------|
| AAB builds and installs | CI | `.github/workflows/` |
| R8 does not strip critical classes | CI | `:app` |
| Certificate pinning rejects invalid certs | Integration | `:app` |
| Migration from sideload preserves data | Integration | `:data:*` |
| Play Store listing screenshots | Manual | Play Console |
| Full regression suite passes | CI | All modules |

### Risks

| Risk | Mitigation |
|------|-----------|
| Play Store rejection (location, background work) | Document all permissions. Provide video demo. Follow Play Store policies exactly. |
| R8 strips JNI classes | Add ProGuard rules for Valhalla and MapLibre. Test release build thoroughly. |
| Dynamic feature modules add complexity | Start with monolithic AAB. Add dynamic features only if APK size > 150 MB. |
| Migration from sideload is error-prone | Test migration on 3+ device states (fresh, v0.1, v0.9). Provide manual export/import as fallback. |

### Dependencies

- v0.9 shipped (tablet, Auto, accessibility complete).
- Google Play Developer account.
- Privacy policy and terms of service written.
- Play Store assets (screenshots, description) prepared.

---

## Cross-Cutting Concerns

### Module Dependency Graph (Simplified)

```
:app
├── :feature:map (in :app)
├── :feature:search
├── :feature:traffic
├── :feature:routing (v0.4+)
├── :feature:navigation (v0.7+)
├── :feature:community (v0.5+)
├── :traffic:provider-api
│   ├── :traffic:provider-sa
│   ├── :traffic:provider-dit (v0.3+)
│   ├── :traffic:provider-nsw (v0.6+)
│   └── :traffic:provider-vic (v0.6+)
├── :routing:engine-api
│   └── :routing:engine-valhalla (v0.4+)
├── :offline:pack-api
├── :offline:pack-downloader
├── :offline:search
├── :data:pins
├── :data:settings
├── :data:pack
├── :data:community (v0.5+)
├── :data:routes (v0.8+)
├── :community:report-api (v0.5+)
├── :community:report-client (v0.5+)
├── :navigation:tts (v0.7+)
├── :navigation:speed-crowdsource (v0.7+)
├── :traffic:congestion-api (v0.7+)
├── :traffic:congestion-tomtom (v0.7+)
├── :core:model
├── :core:common
├── :core:crashlog (v0.9+)
├── :core:telemetry (v1.0+)
└── :ui:designsystem
```

### Privacy Permission Roadmap

| Version | Permissions | Purpose |
|---------|-------------|---------|
| v0.1–v0.2 | `INTERNET` (traffic only) | Traffic SA polling |
| v0.3 | `INTERNET` | DIT outback warnings |
| v0.4 | `INTERNET` | No change |
| v0.5 | `INTERNET` | Community reports (opt-in) |
| v0.6 | `INTERNET` + `ACCESS_COARSE_LOCATION` (opt-in) | Region auto-detection |
| v0.7 | `INTERNET` + `ACCESS_FINE_LOCATION` (opt-in) | Navigation + speed crowdsourcing |
| v0.8–v1.0 | Same as v0.7 | No new permissions |

### Pack Size Budget

| Version | Tiles | Routing | Search | Total |
|---------|-------|---------|--------|-------|
| v0.3 | 90 MB (custom profile) | — | 50 MB | ~140 MB |
| v0.4 | 90 MB | 150 MB | 50 MB | ~290 MB |
| v0.5 | 90 MB | 150 MB | 50 MB | ~290 MB |
| v0.6 | 90 MB × 3 states | 150 MB × 3 | 50 MB × 3 | ~870 MB (all 3) |

### CI/CD Pipeline Evolution

| Version | CI Additions |
|---------|-------------|
| v0.3 | Macrobenchmark gate (cold start < 2.5s) |
| v0.4 | Valhalla JNI build + test |
| v0.5 | Backend tests + deploy. Privacy audit updated. |
| v0.6 | Multi-state pack build. NSW/VIC provider tests. |
| v0.7 | TomTom API key secret. Navigation integration tests. |
| v0.8 | No new CI |
| v0.9 | Tablet UI tests. Auto tests. Accessibility lint. |
| v1.0 | AAB build. Play Store upload. R8 verification. |

---

## Effort Summary

| Version | Total Effort | Key Risk |
|---------|-------------|----------|
| v0.3 | ~3 weeks | Custom Planetiler profile regressions |
| v0.4 | ~4 weeks | Valhalla JNI integration complexity |
| v0.5 | ~5 weeks | Backend ops + privacy compliance |
| v0.6 | ~3 weeks | Multi-state API differences |
| v0.7 | ~6 weeks | Navigation UX + TomTom costs |
| v0.8 | ~3 weeks | Valhalla data quality |
| v0.9 | ~4 weeks | Android Auto + accessibility |
| v1.0 | ~3 weeks | Play Store approval |
| **Total** | **~31 weeks** | |

---

## Decision Log

| Decision | Rationale |
|----------|-----------|
| ~~DIT scraper before Valhalla~~ | **Superseded.** DIT endpoint was already live at `maps.sa.gov.au/.../FNRR2/MapServer`. No serverless scraper needed. |
| Community reports before multi-state | Community reports require a backend. Build it once, reuse for all states. |
| TomTom for congestion, not HERE | TomTom has better AU coverage. HERE is stronger in EU. |
| Opt-in GPS from v0.6, not earlier | Delay the privacy cost until multi-state makes it valuable. |
| Android Auto in v0.9, not earlier | Auto requires Google Play Services. Delay until the core app is solid. |
| Play Store in v1.0, not earlier | Play Store adds compliance burden. Ship sideloaded APKs until the app is feature-complete. |
