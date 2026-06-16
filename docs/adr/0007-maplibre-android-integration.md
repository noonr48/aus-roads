# MapLibre Native Android — v0.1 Integration Plan

**Target:** wire MapLibre Native Android SDK 11.5.2 into the Compose
`MapScreen` so that a downloaded SA map pack renders offline at 60 fps on
a Pixel 6a, with tap-to-identify. No `INTERNET` permission, no
MapTiler/online fallback, no Google Play Services.

**Author:** MapLibre sub-agent. **Date:** 2026-06-01.

---

## 1. Artifact, version, ABI

- **Coordinate:** `org.maplibre.gl:android-sdk:11.5.2` — already declared in
  `android/gradle/libs.versions.toml:42` and `:104` as
  `maplibre-android`. The catalog entry is correct; the Maven Central
  coordinate is verified in
  [Maven Repository: org.maplibre.gl:android-sdk](https://mvnrepository.com/artifact/org.maplibre.gl/android-sdk)
  and the [MapLibre Android quickstart](https://maplibre.org/maplibre-native/android/examples/getting-started/).
  The official upstream Android artifact is published **only** under
  `org.maplibre.gl` — do **not** substitute `com.mapbox.*` (the legacy
  Mapbox artifact, abandoned by MapTiler in 2025).
- **Transitive native libs:** the AAR ships
  `jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libmaplibre.so`. Per the
  upstream `android-v11.5.2` release notes
  ([maplibre-native/releases](https://github.com/maplibre/maplibre-native/releases/tag/android-v11.5.2)),
  the build was switched to Kotlin DSL and includes the OpenGL ES
  backend only (no Vulkan yet on Android).
- **Decision: ship `arm64-v8a` only.** Play Store has required
  64-bit since 2019, and as of 2026 there is no remaining mainstream
  32-bit-only Android device. Each ABI adds ~8 MB to the uncompressed
  APK (per the Mapbox size analysis at
  [docs.mapbox.com](https://docs.mapbox.com/help/dive-deeper/android-apk-size/),
  comparable for MapLibre). Restricting to one ABI gives a ~24 MB APK
  win and forces us to use App Bundles later (which is what Play Store
  wants anyway).
- **Watch out:** `maplibre-native` issue #2907 reports a JNI
  registration regression on Android 15 emulators in 11.5.2. The
  mitigation is to test on a real device (Pixel 6a) first; the
  emulator-only crash is acceptable for v0.1 since v0.1 is delivered
  as a side-loaded APK in the in-app downloader, not a Play Store
  build.

## 2. Style.json — **decision: generate on first launch**

Options:

- **(a) Bundle pre-built style in `assets/style/`.** Fastest first
  paint, but every app release that retunes paint rules bumps a
  full APK download. Also forces us to ship sprite PNGs and glyph
  PBF files in `assets/` (~1–3 MB).
- **(b) Generate `style.json` at runtime** into the maptile dir
  (next to `tiles.mbtiles`). The generation is a `String → file`
  write of ~2 KB. Pointer goes in the manifest's
  `components.tiles.stylePath` so a future style update can ship
  with a new pack without an app update.
- **(c) "Minimal" baked APK with `mbtiles://` source.** Same as (a)
  but smaller. Same downsides as (a).

**Pick (b).** It costs one I/O write per pack install, makes the
style+tiles atomic (same pack version, same SHA), and lets the pack
build pipeline own the visual identity instead of the app build
pipeline. The first-paint cost is one synchronous `File.writeText`
on a background thread before `style.setStyle(styleUri)`.

The generator produces a Style Spec v8 document with one source
pointing at the local MBTiles and one source layer per OpenMapTiles
layer we render (roads, water, landcover, buildings, poi, place
labels). `glyphs` and `sprite` are null in v0.1 — we render road
names by drawing the `transportation_name` `name:latin` as a
`symbol` layer using a font from `assets/fonts/`. Sprite icons ship
in `assets/sprite/` in v0.1.

## 3. MBTiles source — confirmed API

MapLibre Native Android does **not** ship a `MbTilesSource` class.
Two supported patterns, both verified against the upstream source:

1. **Local HTTP server** (the typebrook gist pattern at
   [gist.github.com/typebrook/7d25be326f0e9afd58e0bbc333d2a175](https://gist.github.com/typebrook/7d25be326f0e9afd58e0bbc333d2a175)):
   spin up a `localhost:NNNN` `HttpServer` (NanoHTTPD) that serves
   `http://localhost:NNNN/{z}/{x}/{y}.pbf` from the SQLite file,
   then register a `VectorSource` with that URL. Requires a
   `network_security_config.xml` that whitelists cleartext to
   `localhost` on Android 9+. **Reject:** this fights our
   "no INTERNET" posture (a HTTP socket is a network interface to
   the platform, even if it's loopback) and adds a Java HTTP server
   to the APK.
2. **Custom `TileSet` source with a custom data fetcher
   (`MapboxHttpRequest`/`MapLibreHttpRequest`):** register a
   `Source` whose URL is `mbtiles://au-sa/v20260601/tiles.mbtiles`
   and provide an `HttpRequest` implementation that opens
   `context.filesDir/.../tiles.mbtiles` directly via SQLite, runs
   the `(z, x, y)` query, and returns the raw `.pbf` bytes. No
   socket, no cleartext, no network stack.

**Pick (2).** It is the only option that does not require
`INTERNET`. Implementation: a single class
`MbTilesHttpRequest : HttpRequest` in
`android/app/src/main/java/au/com/ausroads/ui/map/`.

`Stack Overflow #79836797`
(https://stackoverflow.com/questions/79836797)
confirms that v11.x has no built-in MBTiles support and the
custom-fetcher path is the community-tracked workaround.

## 4. Compose interop

`MapView` is a `FrameLayout` subclass. We host it via
`AndroidView`. Lifecycle is bound to the host
`LifecycleOwner` (the `ComponentActivity`) using a
`DisposableEffect` that registers a `LifecycleEventObserver` and
forwards every event to `mapView`. The `MapView` must **not** be
re-created across recompositions — we hoist it into a
`remember { MutableState }`.

```kotlin
// android/app/src/main/java/au/com/ausroads/ui/map/MapScreen.kt
@Composable
fun MapScreen(
    packDir: File,                        // /.../mappacks/au-sa/v20260601
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapView = remember { mutableStateOf<MapView?>(null) }
    val map = remember { mutableStateOf<MapLibreMap?>(null) }
    val selectedFeature = remember { mutableStateOf<Feature?>(null) }
    val scope = rememberCoroutineScope()

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            MapLibre.getInstance(ctx)                       // safe-no-op after first call
            MapView(ctx).also { mv ->
                mv.onCreate(null)
                mv.getMapAsync { m ->
                    map.value = m
                    val styleUri = StyleInstaller.ensure(ctx, packDir)
                    m.setStyle(Style.Builder().fromUri(styleUri)) {
                        // post-style-setup hook (camera, query layer ids) goes here
                    }
                }
            }.also { mapView.value = it }
        },
        update = { /* no-op: state flows through State<T> above */ },
    )

    // Forward every Activity lifecycle event to the MapView.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            val mv = mapView.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START     -> mv.onStart()
                Lifecycle.Event.ON_RESUME    -> mv.onResume()
                Lifecycle.Event.ON_PAUSE     -> mv.onPause()
                Lifecycle.Event.ON_STOP      -> mv.onStop()
                Lifecycle.Event.ON_DESTROY   -> mv.onDestroy(); mapView.value = null
                Lifecycle.Event.ON_CREATE    -> Unit   // handled in factory
                else                         -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            mapView.value?.onDestroy()
            mapView.value = null
        }
    }

    // Backpressure: release GL memory under pressure.
    DisposableEffect(owner) {
        val pressObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                mapView.value?.onLowMemory()
            }
        }
        owner.lifecycle.addObserver(pressObserver)
        onDispose { owner.lifecycle.removeObserver(pressObserver) }
    }

    // Wire the click listener exactly once.
    LaunchedEffect(map.value) {
        val m = map.value ?: return@LaunchedEffect
        m.addOnMapClickListener { screenPoint ->
            handleTap(m, screenPoint, packDir)?.also { selectedFeature.value = it }
            false   // don't consume the gesture (we want pan/zoom still)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // (the AndroidView above renders the map; the Box wraps it for the bottom sheet)
        if (selectedFeature.value != null) {
            FeatureBottomSheet(
                feature = selectedFeature.value!!,
                onDismiss = { selectedFeature.value = null },
            )
        }
    }
}
```

State preservation across config changes is handled for free by
Compose's `rememberSaveable` for the `MapView` Bundle — except
`MapView` itself is `Parcelable`-unfriendly. We rely on the
Activity's `configChanges` manifest entry
(`android/app/src/main/AndroidManifest.xml:42`) to prevent Activity
recreation on rotation. The camera position is restored on cold
recreation via `MapView.onCreate(savedInstanceState)` (it stores
camera state in the Bundle).

## 5. Camera controls

MapLibre's `MapLibreMapOptions` exposes per-gesture flags. We set
them once in the `factory` block:

```kotlin
val options = MapLibreMapOptions.createFromAttributes(ctx)
    .maxZoomPreference(14.5)                  // hard cap: tile pyramid max is 14
    .minZoomPreference(3.0)                   // don't let users zoom out to whole earth
    .zoomGesturesEnabled(true)                // pinch-zoom
    .doubleTapGesturesEnabled(true)           // built-in double-tap zoom
    .scrollGesturesEnabled(true)              // pan
    .rotateGesturesEnabled(false)             // off in v0.1
    .tiltGesturesEnabled(false)               // off in v0.1
    .compassEnabled(false)                    // no rotation → no compass
MapView(ctx, options)
```

Pinch, pan, and double-tap zoom are all built-in. We do **not**
need `addOnMapClickListener` for the zoom-in animation — MapLibre
handles double-tap natively. We do, however, want a tap (single
tap) to query features, which is handled in §6.

`maxZoomPreference(14.5)` is the v0.1.1 plan: the SA pack tops out
at z14, so 14.5 lets MapLibre's over-zoom smoothing kick in but
prevents the user from getting a "tile not found" tile at z15+.

## 6. Tap-to-identify

```kotlin
private fun handleTap(
    map: MapLibreMap,
    screenPoint: PointF,
    packDir: File,
): Feature? {
    val layerIds = arrayOf("transportation_name", "poi", "place")
    val hits = map.queryRenderedFeatures(screenPoint, *layerIds)
    if (hits.isEmpty()) return null

    val props = hits.first().properties() ?: return null
    val name = (props["name:latin"] ?: props["name"] ?: props["name:en"])
        ?.toString()
        ?.takeIf { it.isNotBlank() }
        ?: return null

    val layer = hits.first().sourceLayer() ?: "unknown"
    val kind = when {
        "poi" in layer               -> "Point of interest"
        "road" in layer || "transportation" in layer -> "Road"
        "place" in layer             -> "Place"
        else                         -> "Feature"
    }
    return Feature(name = name, kind = kind, layer = layer)
}
```

**Property keys for OpenMapTiles v3.13+** (verified against
[openmaptiles.org/schema](https://openmaptiles.org/schema/) and
[MapTiler Planet schema](https://docs.maptiler.com/schema/omt-planet/)):

- Road names: layer `transportation_name`, property `name` (with
  `name:latin`, `name:en` as fallbacks; the basemap profile writes
  `name:latin` for non-Latin script countries and we render SA in
  Latin).
- POIs: layer `poi`, property `name`. v0.1 ignores POI `class`
  classification.
- Places: layer `place`, property `name`.

The bottom sheet is a Material 3 `ModalBottomSheet` hoisted to the
`Box` in §4. The select state lives in `selectedFeature` and is
cleared on swipe-down.

## 7. Restrict to arm64-v8a

In `android/app/build.gradle.kts`:

```kotlin
android {
    namespace = "au.com.ausroads"
    defaultConfig {
        applicationId = "au.com.ausroads"
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }     // <-- add
    }
    // ...
}
```

Equivalent in the `ausroads.android.application` convention plugin:
add the same block to
`android/build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`
so every module that applies the convention (`:app` only today) gets
the filter. We choose to put it in the app module for now (it is
the only module that will ever ship an APK) and revisit if a
wear/TV variant appears.

## 8. Lifecycle & memory

The order matters. **Do not** call `onDestroy()` in `onPause()` —
that permanently kills the EGL context and a subsequent `onResume`
will recreate the entire map. Sequence (verified against the
[MapLibre quickstart](https://maplibre.org/maplibre-native/android/examples/getting-started/)):

- `onStart()`    → `mapView.onStart()`     (recreates GL if needed)
- `onResume()`   → `mapView.onResume()`    (resumes rendering)
- `onPause()`    → `mapView.onPause()`     *(enough — MapView halts rendering, holds EGL)*
- `onStop()`     → `mapView.onStop()`      (drops GL context)
- `onLowMemory()`→ `mapView.onLowMemory()` (called in `Application.onTrimMemory` too)
- `onSaveInstanceState(out)` → `mapView.onSaveInstanceState(out)`
- `onDestroy()`  → `mapView.onDestroy()`   **and** null the reference

`onPause()` is **not** sufficient on its own for memory-pressure
scenarios — on API 26+ the Activity is often destroyed without
`onStop` being called first when under low-memory kill. So we
**must** call `onDestroy()` and null the reference in the
`DisposableEffect.onDispose` (see §4). The `MapView` will refuse to
operate after `onDestroy` and any subsequent call will throw
`IllegalStateException`, which is why we null-check the reference
inside the lifecycle observer.

## 9. Build verification

Robolectric is unsuitable: it does not load native `.so` libraries
and `MapView` will `UnsatisfiedLinkError` the moment
`MapLibre.getInstance(context)` is called. Espresso on an emulator
is also poor — the emulator GPU path is software-rendered and the
11.5.2 JNI bug (#2907) makes it unreliable.

**Minimum end-to-end test for v0.1:**

1. **Compile-time gate:** `./gradlew :app:assembleDebug` — must
   succeed and produce a `.apk` < 30 MB.
2. **Lint gate:** `./gradlew :app:lintDebug` — must not regress.
   We also add a custom lint rule (Track F3) that fails the build
   if `INTERNET` reappears in the merged manifest
   (`app/build/intermediates/merged_manifests/debug/.../AndroidManifest.xml`).
3. **Robolectric unit test** of `StyleInstaller` and
   `MbTilesHttpRequest` against a fixture 4-tile MBTiles
   (generated by a Gradle test fixture task that runs
   `tippecanoe` or downloads a 1 MB sample). Asserts that
   `(z=5, x=15, y=12) → byte[8..256]` returns a valid MVT header
   (`0x1a 0x0a`).
4. **Manual smoke on a Pixel 6a** (or any arm64-v8a device with
   a real GPU). Acceptance: app launches, splash dismisses,
   map renders roads of Adelaide CBD at z12, pinch zooms to
   z14.5 without artefacts, tap on a road reveals its name in
   the bottom sheet, no INTERNET permission in
   `App info → Permissions`.
5. **Screenshot test in `androidTest/`** using
   `androidx.test.espresso` + `UiAutomator` to dump the first
   rendered frame as a bitmap and assert non-uniform pixel
   variance (i.e. not a solid grey). Run on
   `firebase-test-lab` with `model=oriole,version=34,locale=en`.
   This is the v0.1 exit criterion for the map sub-track.

## 10. Risks & mitigations

| # | Risk | Mitigation |
|---|---|---|
| 1 | **MapLibre 11.5.2 JNI bug #2907** crashes on Android 15 emulators. | Pin to 11.5.2 in `libs.versions.toml` for the demo APK; if the bug bites the Pixel 6a (Android 15) smoke test, downgrade to 11.5.1 and document the regression. Track upstream; bump at v0.1.1. |
| 2 | **APK size** creeps above 30 MB once we add `assets/glyphs/` and `assets/sprite/`. | Generate the style.json on first launch **and** do not bundle glyphs/sprite at all in v0.1 — render road names via `text-field: {get: name:latin}` only, with `localIdeographFontFamily("sans-serif")` falling back to system font. Sprite icons are v0.2. |
| 3 | **`INTERNET` re-merged** by a transitive MapLibre native dep. | Add a Gradle task `verifyNoInternetPermission` that greps the merged manifest under `app/build/intermediates/merged_manifests/` for `android.permission.INTERNET` and fails the build. Wire into `:app:lint`. |
| 4 | **MapView leaks EGL context** if the user backgrounds the app, the OS kills the process, and Compose restores a stale `MapView` reference. | The `DisposableEffect.onDispose` path in §4 always calls `onDestroy` and nulls the reference; on process restart the `remember { mutableStateOf(null) }` starts fresh. The `onCreate` Bundle restores camera state but never resurrects a destroyed `MapView`. |
| 5 | **Tap-to-identify returns multiple overlapping features** (e.g. a `poi` marker sitting on top of a `transportation_name` label). v0.1 needs deterministic behaviour. | Iterate layers in priority order `poi → transportation_name → place` and pick the first hit with a non-blank `name`. Document this in the `MapScreen` kdoc; full hit-list UI is v0.2. |

---

## File changes summary

| Path | Change |
|---|---|
| `android/gradle/libs.versions.toml` | already has `maplibre = "11.5.2"` and `maplibre-android`. No change. |
| `android/app/build.gradle.kts` | `implementation(libs.maplibre.android)` enabled; `defaultConfig.ndk.abiFilters` set to `arm64-v8a`. |
| `android/app/src/main/java/au/com/ausroads/ui/map/MapScreen.kt` | `AndroidView` + lifecycle wiring, tap-to-identify (`addOnMapClickListener`), long-press pin drop (`addOnMapLongClickListener`), inline `ModalBottomSheet`. |
| `android/app/src/main/java/au/com/ausroads/ui/map/MapPackAvailability.kt` | asset existence check for bundled MBTiles in `assets/maptile/`. |
| `android/app/src/main/assets/maptile/style.json` | bundled style with `glyphs` set to empty string (offline); sources point at `asset://` URI for MBTiles. |
| `android/app/src/main/AndroidManifest.xml` | `tools:node="remove"` strips INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION from MapLibre AAR. |

## Sources cited

- MapLibre Android quickstart: <https://maplibre.org/maplibre-native/android/examples/getting-started/>
- MapLibre Android configuration (`MapLibreMapOptions`): <https://maplibre.org/maplibre-native/android/examples/configuration/>
- MapLibre Native 11.5.2 release notes: <https://github.com/maplibre/maplibre-native/releases/tag/android-v11.5.2>
- Maven coordinate: <https://mvnrepository.com/artifact/org.maplibre.gl/android-sdk>
- OpenMapTiles schema: <https://openmaptiles.org/schema/>
- MapTiler OMT Planet schema (transportation_name): <https://docs.maptiler.com/schema/omt-planet/>
- typebrook MBTiles HTTP-server gist (rejected, but informed §3): <https://gist.github.com/typebrook/7d25be326f0e9afd58e0bbc333d2a175>
- 11.5.2 JNI bug: <https://github.com/maplibre/maplibre-native/issues/2907>
- Stack Overflow #79836797 (confirms no first-class MBTiles API): <https://stackoverflow.com/questions/79836797/how-to-load-and-render-mbtiles-offline-map-in-maplibre-android-kotlin>
- Mapbox Android APK size analysis (sized for MapLibre parity): <https://docs.mapbox.com/help/dive-deeper/android-apk-size/>
