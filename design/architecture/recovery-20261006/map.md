# Runtime and writer map
All paths below are relative to /home/benbi/Apps/aus-roads. New tests are explicitly NEW. Each lane is a non-overlapping writer lease, not an independent release.

## Entry points and seams
MainActivity -> existing NavHost -> Map / Explore(nearby) / Pins / Settings.
Discover gesture -> ExploreViewModel -> ExplorationStore/DataStore -> observed state -> ExploreScreen.
Essentials Retry -> NearbyViewModel -> SearchInitializer -> FtsSearchRepository READY/generation -> current emergency/category lookup -> NearbyScreen.
Application boot/pack flow -> SearchInitializer -> contained active component or bundled fallback -> shared repository -> search/cameras/hazards.
Map preparation(IO) -> awaitPreparedStyle(Main) -> native view/style -> overlays/first-hit consumer -> Room pin identity.
Navigation start -> feature NavigationViewModel -> withNetwork fused adapter -> Google Task/callback -> bounded collection/watchdog -> Stop.
Room providers -> existing migrations -> private pin/route database; unsupported version must not destroy rows.

## Module inventory / exact lease files
### A — local intent and Essentials backend (app state/DataStore orchestration)
- android/app/src/main/java/au/com/ausroads/ui/explore/ExploreViewModel.kt — ordered local mutation queue and UI state owner.
- android/app/src/main/java/au/com/ausroads/ui/explore/ExplorationStore.kt — sole authoritative interests/visits writer.
- android/app/src/main/java/au/com/ausroads/ui/nearby/NearbyViewModel.kt — emergency/category lookup and catalog retry owner.
- android/app/src/test/java/au/com/ausroads/ui/explore/ExploreViewModelTest.kt — persistence intent regressions.
- android/app/src/test/java/au/com/ausroads/ui/explore/ExploreVisitOrderingRegressionTest.kt — NEW old-API failed-clear counterexample for baseline execution.
- android/app/src/test/java/au/com/ausroads/ui/nearby/NearbyViewModelTest.kt — retry/readiness/replacement/cancellation contracts.

### B — truthful accessible presentation (readers of A's state)
- android/app/src/main/java/au/com/ausroads/ui/explore/ExploreScreen.kt — discovery/interests/clear-history/native UI.
- android/app/src/main/java/au/com/ausroads/ui/nearby/NearbyScreen.kt — Essentials recovery actions.
- android/app/src/main/java/au/com/ausroads/ui/settings/SettingsScreen.kt — accessible bound settings controls.
- android/app/src/main/res/values/explore.xml — exploration copy.
- android/app/src/main/res/values/strings.xml — shared/flavor-selected privacy copy.
- android/app/src/offline/res/values/strings.xml — primary truthful offline sharing and GPS-refusal override; no permission/capability change.
- android/app/src/androidTest/java/au/com/ausroads/ui/explore/ExploreJourneyInstrumentedTest.kt — actual Activity discovery/recovery/semantics proof.
- android/app/src/androidTest/java/au/com/ausroads/ui/settings/SettingsPresentationInstrumentedTest.kt — native accessibility/presentation proof.

### C — catalog trust and public producer (offline search / app bootstrap / Python tool)
- android/offline/search/src/main/kotlin/au/com/ausroads/offline/search/FtsSearchRepository.kt — serialized validated database owner.
- android/offline/search/src/androidTest/kotlin/au/com/ausroads/offline/search/DiscoveryInstrumentedTest.kt — real SQLite domain/replacement controls.
- android/app/src/main/java/au/com/ausroads/di/SearchInitializer.kt — contained catalog selection, validation/fallback and health.
- android/app/src/main/java/au/com/ausroads/AusRoadsApp.kt — boot/pack observation registration.
- android/app/src/androidTest/java/au/com/ausroads/ui/explore/CatalogBootstrapInstrumentedTest.kt — bootstrap/real failure witnesses.
- tools/map-pack-builder/scripts/bundle-search-catalog.py — public asset validation and exception-safe publication.
- tools/map-pack-builder/tests/test_bundle_search_catalog.py — producer failure/rollback tests.

### D — map native join and oracle (app map/Android test)
- android/app/src/main/java/au/com/ausroads/ui/map/MapScreen.kt — native first-hit to current pin editor.
- android/app/src/main/java/au/com/ausroads/ui/map/TrafficMapOverlay.kt — typed severity expression and native traffic overlays.
- android/app/src/androidTest/java/au/com/ausroads/ui/map/MapAssetJourneyInstrumentedTest.kt — strict actual native replacement/retry/deletion controls.
- android/app/src/androidTest/java/au/com/ausroads/ui/map/TrafficExpressionInstrumentedTest.kt — NEW real SDK expression/layer contracts.

### E — fail-closed data providers (app pins / data routes)
- android/app/src/main/java/au/com/ausroads/di/AppModule.kt — pin Room provider.
- android/data/routes/src/main/kotlin/au/com/ausroads/data/routes/di/RoutesModule.kt — route Room provider.
- android/data/pins/src/main/kotlin/au/com/ausroads/data/pins/PinDatabase.kt — existing legacy migrations, only repair if necessary.
- android/app/src/androidTest/java/au/com/ausroads/ui/settings/DataPreservationInstrumentedTest.kt — NEW real SQLite/Room row preservation and unknown-version refusal.

### F — observable bounded GPS (network adapter / feature navigation)
- android/app/src/withNetwork/java/au/com/ausroads/di/NavigationLocationModule.kt — real fused client registration/removal and Task failure bridge.
- android/app/src/testWithNetwork/java/au/com/ausroads/di/FusedNavigationLocationSourceTest.kt — NEW actual adapter/Task tests.
- android/feature/navigation/src/main/kotlin/au/com/ausroads/feature/navigation/NavigationViewModel.kt — bounded retry, cold/stale watchdog and Stop.
- android/feature/navigation/src/test/kotlin/au/com/ausroads/feature/navigation/NavigationViewModelTest.kt — retry/cold-start/Stop regression proof.

### Primary — integration/proof/release
- design/architecture/recovery-20261006/{scope,map,data-model,contracts,plan,risks}.md — NEW shelf and evidence contract.
- docs/places-exploration.md, studio/PROJECT.md — adopted docs; correct stale counts/claims only against final evidence.
- design/ux/states.md, design/ux/edges.md — adopted existing-surface state and error/recovery matrix, expanded by primary without new flows.
- android/app/src/main/java/au/com/ausroads/di/PlacesHealth.kt — primary fixed NavigationGpsEvent/attempt persistence through existing bounded I/O worker.
- android/feature/navigation/src/main/kotlin/au/com/ausroads/feature/navigation/NavigationOverlay.kt — primary cold/stale gpsLost-first banner consumption.
- android/feature/navigation/src/main/res/values/strings.xml — primary truthful GPS unavailable/recovery copy.
- android/app/src/androidTest/java/au/com/ausroads/ui/map/NavigationFailureJourneyInstrumentedTest.kt — NEW primary actual owner-Activity/notification Stop and persistent GPS failure crossing proof.
- /home/benbi/work/aus-roads-explore-20261005/run_native.py — external actual guest acceptance controller; preserve original entrypoint, strengthen declared named-case completeness/source binding.
- /home/benbi/lab/experiments/1042-20261006-ausroads-cache-render-ack — unchanged pre-repair production snapshot, retained control/provenance, exhausted native-probe budget.
- All remaining adopted 77-path cluster — qualify/read/review, not unleased discretionary mutations.

### G — adopted routing authority and native/config fence
- android/app/src/main/java/au/com/ausroads/di/InstalledPackPaths.kt — add optional, typed routing selection using existing adoption/containment; preserve map/search defaults.
- android/app/src/main/java/au/com/ausroads/di/RoutingInitializer.kt — adopted selection, unavailable invalidation, payload-free outcomes.
- android/app/src/main/java/au/com/ausroads/di/PlacesHealth.kt — shared fixed bounded routing-init witness; exclusively leased to G while active.
- android/routing/engine-valhalla/src/main/kotlin/au/com/ausroads/routing/engine/valhalla/ValhallaRoutingEngine.kt — coherent readiness and serialized configuration/native request boundary; reported km/mi lengths normalized into domain meters; no navigation/service or native-actor-close redesign.
- android/routing/engine-valhalla/src/test/kotlin/au/com/ausroads/routing/engine/valhalla/RouteDistanceUnitsTest.kt — NEW parent actual SDK-response/dispatcher/parser controls for kilometers, subkilometer, miles and real zero; not JNI proof.
- android/app/src/test/java/au/com/ausroads/di/InstalledPackPathsTest.kt — optional-routing containment controls.
- android/routing/engine-valhalla/src/test/kotlin/au/com/ausroads/routing/engine/valhalla/ValhallaRoutingEngineTest.kt — replace tautological test with actual readiness/failure/fencing controls where JVM platform permits.
- android/routing/engine-valhalla/build.gradle.kts — test-only existing version-catalog MockK/coroutines-test dependency if needed; runtime dependencies and versions remain unchanged.
- android/app/src/androidTest/java/au/com/ausroads/routing/RoutingAuthorityInstrumentedTest.kt — NEW real Android isolated adoption/config-failure/witness crossing; valid-tar prerequisites fail visibly, never skip into acceptance.

### H — protect adopted pack bytes from stale Retry
- android/offline/pack-downloader/src/main/kotlin/au/com/ausroads/offline/download/MapPackDownloadWorker.kt — never extract/delete a currently or previously referenced version on stale Retry; supplied malformed manifest must not silently disable verification.
- android/offline/pack-downloader/src/main/kotlin/au/com/ausroads/offline/download/download/PackInstaller.kt — finalization/refusal cleanup must preserve referenced artifacts.
- android/offline/pack-downloader/src/main/kotlin/au/com/ausroads/offline/download/eviction/EvictionManager.kt — primary join: reuse the production state mutex across extraction, publication and stale-directory cleanup, without reentrant locking; preserve previous when current is absent.
- android/offline/pack-downloader/src/main/kotlin/au/com/ausroads/offline/download/download/PackExtractor.kt — primary join: canonical separator-boundary ZIP containment and fixed payload-free refusal; prevent sibling-prefix escape into referenced bytes.
- android/offline/pack-downloader/src/test/kotlin/au/com/ausroads/offline/download/download/PackInstallerTest.kt — original install/rollback contracts plus referenced-version/invalid-manifest controls.
- android/offline/pack-downloader/src/test/kotlin/au/com/ausroads/offline/download/WorkerFailureContractTest.kt — exact resolved existing worker error/cancellation contract; preserve ordinary cancellation semantics.
- android/offline/pack-downloader/src/test/kotlin/au/com/ausroads/offline/download/ReferencedPackRetryTest.kt — NEW narrow actual-worker/installer or extracted real boundary controls; not a substitute fake pipeline.
No downloader storage-layout, pack schema, version/identity, CDN, dependency, navigation owner or credentials change. Any additional essential path needs a lease amendment first.

### I — UX shelf only
- design/ux/states.md, design/ux/edges.md — existing surfaces expanded from actual final resource/state contracts, no code mutation or historical PASS claim.

### J — native queue/cancellation crossing (test-only, one compound lifecycle)
- android/app/src/androidTest/java/au/com/ausroads/ui/settings/MapPackQueueJourneyInstrumentedTest.kt — NEW leased native proof: actual delayed production Worker and MainActivity/Hilt singleton, native KEEP owner, explicitly injected Cancel rejection and persistent payload-free witness, restored real UI Cancel and native CANCELLED. Network flavor only; fail physical/wrong-flavor/nonempty-unfinished-work preconditions. No fake screens/WorkInfo, new dependencies, public pack downloads or production hooks. Preserve application-owned pack/data authority; only test-created UUIDs may be cancelled during cleanup. Existing native inventory must admit this new eligible case explicitly without removing original cases or weakening START/PASS/source/APK/touch predicates.

### K — source-manifest privacy gate (post-job31 confirmed predicate repair)
- tools/privacy-audit/run.sh — existing source front door, correct network FINE/COARSE allowance and per-permission removal semantics; background/media-location remain forbidden. Preserve unrelated audit checks.
- tools/privacy-audit/manifest_permissions.py — NEW stdlib XML permission checker, fixed-cardinality refusals, explicit project root for isolated negative fixtures.
- tools/privacy-audit/test_manifest_permissions.py — NEW real front-door healthy and mixed-removal/forbidden/offline/malformed controls, complete static argument and parser-location failure regressions.
- tools/privacy-audit/toml_locations.py — NEW small parent-owned per-call stdlib statement-end producer from scoped Lab1579 evidence; private ABI and ordered-position failures refuse. The existing checker owns semantic detection, exact completing-line binary search and internal-fault classification; no second TOML grammar, global monkeypatch or changed20s fixture deadline.
No permission, manifest, dependency, GPS runtime or signer changes. Parent runs tests; separate trusted reviews follow final integration.

### L — shipping artifact gate (post-job31 confirmed predicate repair)
- tools/verification/check_shipping_artifacts.py — NEW owned canonical replacement for the read-only external check_artifacts.py: complete packaged asset identity and flavor permissions, exact package/version/certificate/ABI using existing SDK and signers.
- tools/verification/test_check_shipping_artifacts.py — NEW positive/negative ZIP/manifest/certificate/ABI gate controls and real CLI refusal coverage.
The original external checker is retained unchanged as historical source. No fake artifact may certify actual shipping bytes; final four real arm64 APKs are checked last. No credential/signing-key creation or phone mutation.

### Primary — additional verified gate obligations after job31
- android/app/src/androidTest/java/au/com/ausroads/ui/map/CatalogRefreshJourneyInstrumentedTest.kt and MapAssetJourneyInstrumentedTest.kt — require actual pointer-file absence rather than decoded null before disposable fixtures; preserve all original cases and bytes.
- /home/benbi/work/aus-roads-explore-20261005/{run_native.py,make_native_binding.py,test_run_native.py} — preserve original front door, identities, status/skip/crash/foreground/touch/deadline rules; add source-set/compiler-producer and fixture identity guards with negative controls.
- /home/benbi/work/aus-roads-explore-20261005/{native_build_evidence.py,test_native_build_evidence.py,native-case-inventory.json} — NEW immutable source-set/pre-build→post-build/artifact provenance guard and original named-case inventory, exercised by the real compiler wrapper and rejected-state controls.
- /home/benbi/work/aus-roads-explore-20261005/check_native_fixture_refusal.py — NEW actual dedicated-guest malformed-pointer negative: original named case must refuse before mutation and exact fixture bytes remain; never a Pixel or product-success test.
- Any additional external helper/test needs a further explicit declaration before mutation.

## External dependencies
Existing pinned Kotlin/Compose/Hilt/Room/DataStore/coroutines, MapLibre Android 11.6.1, Google fused provider in network source set only, existing Python SQLite toolchain. No dependency upgrade planned.

## Hot spots and glossary
FtsSearchRepository has search/camera/hazard fan-in: preserve one shared lock/generation and optional-table compatibility. MapScreen has native lifecycle/service fan-in: Lab1042 already probed it; no speculative cache/lifecycle/SDK fix. Catalog READY means validated queryable data, not map rendering. Map native application is separate from Room/Compose state convergence. A visit is only an explicit persisted action. Publisher rollback means exception safety; multi-file power-loss atomicity is not assumed.
