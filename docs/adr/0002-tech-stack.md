# ADR 0002 — Tech stack

## Status

Accepted. 2026-06-01.

## Context

The app must render an offline vector map, fetch small live-data updates, persist pins
and downloaded map packs, and run on a wide range of Android devices. It must also
support an iOS port in the future (the "country take over" plan is multi-platform
eventually) and must not be hostile to Play Store policy on location / background
work.

## Decision

**Pure native Android** (Kotlin + Jetpack Compose + AndroidX), single-platform for
v0.1-v1.x. The "country take over" is a data problem, solved by the per-state
`LiveTrafficProvider` interface — not a platform problem. An iOS port can be
considered later as a deliberate choice, not a forced consequence of KMP.

Key choices:

- Kotlin 2.0.21 with the K2 compiler; Compose compiler is a Kotlin plugin
  (`org.jetbrains.kotlin.plugin.compose`).
- AGP 8.7.2, Gradle 8.10.2.
- minSdk 26, targetSdk 35, compileSdk 35. Java 17 toolchain.
- MapLibre Native Android SDK 11.5.2 for the map renderer (upstream, not the Compose wrapper).
- Ktor Client 2.3.13 with OkHttp engine for HTTP (also used by traffic providers).
- kotlinx.serialization 1.7.3 for JSON.
- Room 2.7.0 with KSP 2.0.21-1.0.27 for persistence.
- Hilt 2.52 for DI.
- kotlinx-datetime for timestamp handling.
- androidx-datastore 1.1.1 for settings persistence.
- androidx-navigation 2.8.4 with type-safe routes.
- Valhalla 3.7+ via the Rallista/valhalla-mobile JNI library for offline routing.
- Glance 1.1.1 for home screen widgets.
- WorkManager 2.9.1 for background pack downloads.
- detekt 1.23.7 for static analysis.
- JUnit 4.13.2 + JUnit Jupiter 5.11.3 + MockK 1.13.13 for testing.

## Rationale for not picking alternatives

- **Kotlin Multiplatform / Compose Multiplatform**: doubles build complexity for one
  developer with no near-term iOS port. We can adopt it later if/when iOS is real.
- **Flutter**: weaker offline-map story, adds a bridge layer over MapLibre.
- **React Native**: same bridge problem, plus the JNI-to-Valhalla story is fragile.
- **GraphHopper for routing**: upstream says "offline routing is no longer officially
  supported".
- **OSRM for routing**: the OSRM maintainers state OSRM "keeps its routing graph in
  memory, which means it's not really suited for running on resource-constrained
  mobile devices" (osrm-backend#5983). Valhalla's tile-based hierarchical design is
  a much better fit for Android.

## Module layout (25+ modules)

```
:app                          (main application module)
:build-logic                  (composite build with convention plugins)
:core:model                   (pure Kotlin, no Android)
:core:common                  (pure Kotlin, no Android)
:data:pins                    (Room DB for pins)
:data:settings                (DataStore for settings)
:data:pack                    (Room DB for pack metadata)
:data:routes                  (Room DB for route history)
:feature:search               (Compose search UI with FTS5)
:feature:traffic              (Compose traffic overlay UI)
:feature:navigation           (Compose active navigation UI)
:traffic:provider-api         (pure Kotlin, no Android)
:traffic:provider-stub        (pure Kotlin, v0.1 stub only)
:traffic:provider-sa          (Traffic SA ArcGIS provider)
:traffic:provider-sa-outback  (DIT outback warnings provider)
:traffic:provider-nsw         (NSW Live Traffic provider, placeholder endpoints)
:traffic:provider-vic         (VIC VicRoads provider, placeholder endpoints)
:traffic:congestion-api       (pure Kotlin, congestion contract)
:routing:engine-api           (pure Kotlin, routing contract)
:routing:engine-valhalla      (Valhalla JNI wrapper, offline routing)
:navigation:tts               (Android TTS wrapper for voice prompts)
:offline:pack-api             (pure Kotlin, manifest schema)
:offline:pack-downloader      (map pack download + verify pipeline)
:offline:search               (SQLite FTS5 search engine)
:ui:designsystem              (Compose, no domain types)
```

Dependency direction: `:app` → `:feature:*` → `:traffic:*`, `:routing:*`, `:offline:*`,
`:core:data` → `:core:model`, `:core:common`. `:core:*` modules avoid Android
dependencies where possible.

## Consequences

- The Kotlin / Compose / AGP versions are pinned and the version catalog is the
  single source of truth.
- Build-logic / convention plugins are mandatory for new modules.
- Switching to KMP or Flutter mid-build is a category of work we explicitly
  forbid without a written re-architecture decision.
