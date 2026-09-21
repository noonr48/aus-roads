# Deep-review: aus-roads map refinement

Band: GATED + REGRADE (F=83 C=10 I=0 S=1) · Medium: code + UI + document · Date: 2026-07-17 · Status: CLOSED

## SUSPECT LIST (inherited claims treated as hypotheses)

| claim | falsifying probe | prediction | fresh disposition |
|---|---|---|---|
| Route ownership survives start and host stop/resume, then clears exactly once | inspect and rerun `MapColdStartInstrumentedTest,RouteOwnershipLifecycleInstrumentedTest`; require native line after resume and exactly two subscription starts | likely true after reviewer hardening | SURVIVED after harness repair: 5 consecutive cold runs, 10/10 tests, each route log shows exactly one native MapView |
| Nearby Supermarket selection is no longer clipped | rebuild/install, select the last category on the AVD, inspect PNG and XML bounds | likely true but prior screenshots were stale | SURVIVED: `stores.png`; text bounds `[743,650][954,703]` with the whole chip visible |
| Search copy is friendly for ordinary and boolean-valued OSM classes | run presentation/SearchBar tests, query `IGA` live, inspect XML | `shop=supermarket` true; `shop=yes` was initially suspect | SURVIVED after class fix/test: live rows say `Supermarket · coordinates`; `shop=yes` falls back to kind |
| User-location marker and road scaling are live | inject Adelaide location, wait for the stream, capture steady map and a before/after zoom pair | likely true after style readiness | SURVIVED: `map-located.png`, `scale-before.png`, `scale-after.png` |
| The complete current tree builds and releases | run all unit tests, both debug lints, and both release assemblies | likely green; warning volume expected | SURVIVED: BUILD SUCCESSFUL, 1,934 tasks; both unsigned APKs produced |

## SPEC (R-G1 — re-derived from the user's words)

ASK (VERBATIM):

> “Comprehesively recall the map app we were making. Leto's comprihensievly review that and pollish it. The last test attempt the map was not really functioning. THe map and the roads didn't scale properly, and icon to show the user location was missing. The whole hold to add pin is not there. couldn't see any store around me on the map. The search didn't seem to work. Let's comprehensively pollish it and refine it”
>
> “ok. agentically use vision and use test on tne applicatiin and refine as much as possible”
>
> “ok continue agentic refinemend and devlopment”

### Clauses (the WHAT)

| # | clause (the ask's words) | evidence artifact | verdict |
|---|---|---|---|
| 1 | “Comprehesively recall the map app we were making” | general-memory orientation + `studio/PROJECT.md` + current source/state walkthrough | MET |
| 2 | “comprihensievly review that and pollish it” | `design/ux/flow-check.md`, 83-file diff inventory, fresh vision/device/gate receipts | PARTIAL: major flows polished; one deliberate 15/16 boundary residual remains |
| 3 | “map was not really functioning” | cold-start instrumentation 1/1 plus live `map-located.png` | MET |
| 4 | “map and the roads didn't scale properly” | `scale-before.png` / `scale-after.png` and earlier real-pinch pair | MET |
| 5 | “icon to show the user location was missing” | `map-located.png` + location-update/overlay logs + My location semantics XML | MET |
| 6 | “whole hold to add pin is not there” | `hold.png`/XML → `pin-saved-map.png` → `pins.png`/XML | MET |
| 7 | “couldn't see any store around me” | device-centred `stores.png`/XML, nearest results from 355 m | MET |
| 8 | “search didn't seem to work” | live `IGA` results/selection receipts + SearchPresentation/SearchBar tests | MET |
| 9 | “agentically use vision and use test” | built-in vision inspection, Android 14 AVD journey, full Gradle gate, independent reviewers | MET |
| 10 | “refine as much as possible” / “continue agentic … development” | route ownership/lifecycle instrumentation; MapLibre main-thread correction; copy/layout refinements | MET within this bounded pass; residuals are explicit |

### Instructions (the HOW)

| # | instruction | honored — evidence |
|---|---|---|
| 1 | “stop sub-agent. i need to go to bed” followed later by “i am back. continue agentically” | previous work stopped; this resumed turn used bounded sub-agents only after the explicit continuation |
| 2 | use vision and test the application | fresh PNG + XML receipts under the ignored `refinement-final/` folder; live AVD interactions and Gradle receipts |
| 3 | preserve the shared dirty tree | no reset/checkout/destructive command; 83-file inventory retained |
| 4 | do not commit this new pass without a current explicit request | no commit made; `git status --short` remains intentionally dirty |
| 5 | use SLOANE Studio/memory/skills and prove claims | Studio ledger, Lab/deep-think decision, route instrumentation, deep-review sheet and reviewer roster |

### TABLE MAP — inherited 16-row UX rubric

The canonical disposition remains the row-by-row score in `design/ux/flow-check.md`: rows **1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16 = delivered**; row **8 = deferred** because the stable primary-destination unexpected-screen boundary has no live mechanism. Supplemental rows **4a** and **4b** pass. Count: 16/16 rows disposed, 15 delivered + 1 explicit residual.

## EVIDENCE (R-G2)

RAN so far:

- `./gradlew --no-parallel :offline:search:testDebugUnitTest --tests '...SearchPresentationTest' :feature:search:testDebugUnitTest --tests '...SearchBarTest' :app:compileOfflineDebugKotlin :app:compileWithNetworkDebugKotlin` → `BUILD SUCCESSFUL in 13s`.
- `./gradlew --no-parallel -Pausroads.includeX86_64=true :app:connectedOfflineDebugAndroidTest '-P...class=...MapColdStartInstrumentedTest,...RouteOwnershipLifecycleInstrumentedTest'` → five consecutive cold invocations; every retained XML reports `tests=2 failures=0 errors=0 skipped=0`, for 10/10 total.
- Final post-review `./gradlew --no-parallel test lintOfflineDebug lintWithNetworkDebug assembleOfflineRelease assembleWithNetworkRelease` → `BUILD SUCCESSFUL` in 3m04s; 1,934 actionable tasks.
- live AVD drive → Search results/selection, long-hold/save/list, Nearby/location/stores, and road zoom receipts under `android/build/reports/vision-acceptance-20260717/refinement-final/`.

GREEN: focused tests/compiles, connected route/cold-start, full unit/lint/release, and `git diff --check` all passed.

NOT-HAPPY:

- `shop=yes` fixture initially produced a meaningless **Yes** label under the proposed copy policy → class fixed and regression-tested to fall back to **Point of interest**.
- Initial route instrumentation reported a MapLibre worker-thread exception at `setStyle` → style preparation remains on IO, native style mutation is explicit `Dispatchers.Main.immediate`; retained rerun passed.
- Fresh route reviewer found two false-pass gaps (no native-line recheck after resume; no terminal total-start assertion) → both assertions added; fresh re-review passed.
- The first exact independent connected run then failed to observe the route source/layer for 20 seconds, while its log showed two overlapping native MapViews: the real `MainActivity` composition and the replacement test composition. An immediate rerun passed, so the prior one-green-run claim was withdrawn. The rule now uses the Compose test manifest's blank `ComponentActivity`; five cold repeats pass, and each route-test log has exactly one `MapView created` event. Receipts: `android/build/reports/route-stability-20260717/isolated-component-activity/run-{1..5}/`.
- TalkBack AVD probe bound the service and showed focus rings, but its TTS did not initialize and synthetic swipes leaked into native panning → recorded as partial evidence, not a complete sweep.

PROOF-CREATION: created `RouteOwnershipLifecycleInstrumentedTest.kt`; created `shop=yes` regression cases; created fresh post-change visual and hierarchy receipts.

INHERITED (S=1): sub-agent claims were rechecked against the current XML, source, focused tests, full gate, `git diff --check`, and fresh AVD drive. No final claim rests only on agent prose.

## WIRING (R-G2.5)

CONSUMERS:

- `SearchPresentation` repo search → SearchBar group/subtitle, Map feature/result copy, Nearby result copy, and tests; every consumer compiles in both app flavours. An out-of-tree search under `/home/benbi/Apps` and `/home/benbi/sloane-tools` returned no external consumers.
- `navigationSessionRoute` / `routeSurfaceState` search → RouteViewModel, MapScreen route line/sheet/history Stop path, unit tests, and the new Android journey.
- `RouteMapOverlay(` search → one production caller in MapScreen; it is now always composed so the null cleanup branch is reachable.
- dependency alias search → `compose-ui-test-junit4`, `compose-ui-test-manifest`, and `mockk-android` resolve from the version catalog; the test manifest is debug-only and exposes the blank AndroidX host activity.

REACHABILITY: both flavours compile; the offline debug APK was installed after the final connected test; fresh live screenshots contain the changed Search/Nearby/Map behavior. ACTIVATION: source changes require rebuilding/reinstalling; the AVD had the rebuilt app. Release artifacts were rebuilt but are unsigned and not deployed.

TWINS: offline and withNetwork app variants compile/lint/release; their two location-provider implementations remain separate deliberate twins. Current live runtime proof is offline AVD only.

CONSISTENCY: the shared SearchPresentation policy is used by Search, Map, and Nearby rather than three ad hoc label conversions; Nearby keeps Material `FilterChip` semantics and stable enum keys; route ownership follows existing StateFlow/ViewModel conventions.

INSTRUMENTATION: the new native route seam has witnesses for source/layer presence before Start, after Start, after host resume, and absence after Stop; subscription start/active counts and exact DAO payload are asserted. Its blank test host guarantees one native MapView; five retained cold runs prove the harness no longer races production composition. The MapLibre style-ready seam remains covered by cold-start instrumentation.

DERIVED-DIFFS: screenshots, connected outputs, lint reports, APKs, mappings, and other build products are ignored by `*/build/` or `*/*/build/`; they were read as receipts and are intentionally not source changes.

RIPPLE: Search copy change → shared presentation helper → Map/Nearby consumers → tests/live screenshots → flow-check/Studio ledger. Route ownership → MapScreen + native overlay + dependencies → connected test → UX ledger. Frontier empty pending independent review.

## ADVERSARIAL (R-G3)

MIRROR:

- WEAKEST: full assistive narration; the AVD TTS failure makes focus proof weaker than hardware TalkBack → seed design-review focus and retain a hardware sweep residual.
- MISSING: shift **TOMORROW-USER** performed → the user first notices whether location, Search, long-hold, and nearby stores visibly work; all four now have fresh live receipts. The less-visible real-pack route generation remains a residual rather than being hidden behind the deterministic route test.
- PUNTS:

| decision made on the user's behalf | alternative | surfaced where |
|---|---|---|
| Do not ship the child-ComposeView destination crash boundary | accept a partial boundary that catches later recomposition but not initial composition | `.deepthink/destination-recovery-boundary.md`, `flow-check.md`, final residual |
| Keep the current Australian-English hardcoded presentation helper | migrate every label into Android resources now | final advisory/residual; app currently targets Australian English |
| Treat an AVD as runtime proof | require a physical handset before any handoff | final residual explicitly says physical-device run remains |
| Use a deterministic fake route for ownership/lifecycle | require real bundled routing graph generation | route section and final residual distinguish the two |
| Leave this pass uncommitted | commit immediately | final status states uncommitted; user did not ask for a new commit in this turn |

PREMORTEM:

1. A shared MapLibre call still crosses a worker thread outside `setStyle`; reviewers must sweep all style/native mutations and coroutine contexts.
2. Visual receipts prove one density/AVD but hide clipping, semantics, or interaction regression on another device; reviewers must inspect PNG + XML and the responsive/source structure.
3. The route test could pass with a fake route while production generation is broken; reviewer must keep ownership proof and routing-engine proof distinct.

FIX-CLAIMS:

- “MapLibre style installation is explicit main-thread after IO preparation” → `MapScreen.kt` lines around `scope.launch(Dispatchers.Main.immediate)` + `withContext(Dispatchers.IO)` + `map.setStyle`; connected cold-start/route rerun passes.
- “selected Nearby category is visible” → `LazyRow` + end padding + selected-item animation; fresh XML bounds and PNG.
- “boolean OSM classes do not become labels” → full non-descriptive value set + unit fixture for `shop=yes`, `TRUE`, `0`, `unknown`.

PROCESS-CAUSE: the MapLibre thread contract lacked an integration witness at the IO→native boundary → guard=new connected cold-start/route journey. The route test reused the production activity and replaced its content after the production native map had already launched → guard=blank `ComponentActivity` from `ui-test-manifest` plus a one-MapView log invariant and repeated cold-run receipts. The copy edge lacked corpus-shaped generic-tag fixtures → guard=`shop=yes` regression tests. The clipped category had only pre-change screenshots → guard=fresh post-build PNG + XML acceptance receipt.

CLASS SWEEP: searched every `setStyle`, `mapLibreMap.style`, `addSource`, `removeSource`, `addLayer`, and `removeLayer` call in Android source; remaining native overlay mutations execute in composition-scoped effects on the main dispatcher. Searched all SearchPresentation consumers and boolean-like tag values; one shared policy now covers the class.

REVIEWER (GATED): **PASS** across three isolated read-only passes.

- Code/spec: PASS — independently parsed all five preserved stress runs, audited production style/route wiring and the debug-only blank host, and found no demonstrated blocker.
- Runtime/evidence: PASS — ran a sixth exact connected invocation (`2/2`, zero failures/errors/skips), confirmed one `MapView created` and one `MapView.onDestroy` in the route log, reran RouteViewModel 10/10, and kept the deterministic-route boundary explicit.
- Visual/UX: PASS — directly inspected the map/scale/location/search/selection/hold/save/pins/Nearby/stores PNGs and XML; every user-reported issue passes within scope, with TalkBack narration and physical-device breadth retained as advisories.

## CLOSE (R-G4)

TRACE: all three reviewer verdicts pass; the post-review full unit/lint/release matrix passed in 3m04s; both unsigned release APKs were regenerated; the offline debug APK was reinstalled and cold-launched into real `MainActivity` in 1.529s. Final visual smoke: `android/build/reports/vision-acceptance-20260717/final-activation/map-smoke.png` plus XML.

RESIDUALS:

- UX rubric blocker: initial destination-composition exceptions cannot be honestly contained by the tested child-recomposer mechanism → fires when a primary destination throws before first composition; blocked on a boundary mechanism that catches initial and later failures without killing the stable shell.
- Runtime proof: current route ownership test injects a deterministic route → fires when validating actual bundled Valhalla route generation/options on a real pack.
- Assistive proof: AVD TTS failed to initialize → fires before claiming full TalkBack narration quality; requires a physical/working-TTS device sweep.
- Device breadth: Android 14 x86_64 AVD only → fires before release certification on arm64/16 KB-page or manufacturer-specific behavior.
- Localization debt: SearchPresentation labels are Kotlin literals → fires when adding a non-Australian-English locale or formalizing resource-only copy policy.
- Lint debt: 0 errors but 146 offline / 158 network warnings → fires when warnings become release policy blockers or touched-file warnings are promoted.

ACTIVATION:

- debug app: LIVE on the Android 14 AVD after rebuild/install; `MainActivity` is the top resumed activity and the final screenshot shows the detailed Adelaide map, Search, History, hold-to-pin guidance, location control, attribution, and bottom navigation.
- release APKs: BUILT but unsigned and not deployed.
- source/docs/tests: INERT for users until the normal signed release pipeline; owner controls that step.

TRIAD and STOP: reviewer triad passes; final full gate, activation smoke, artifact checks, and last-tree whitespace verification pass. Stop condition reached with explicit residuals above.
