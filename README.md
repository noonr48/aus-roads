# aus-roads

A privacy-focused offline map of South Australia with official live roadworks, incidents, closures, events, outback road warnings, and offline routing. **Play Store ready** — see `docs/notes/release-signing.md` and `docs/notes/play-store-listing.md`.

**v1.0** — 19 commits, 25+ modules, 650+ tests. Final commit `1d2d4cf`.

## Features

1. **Offline map pack** — South Australia base map, POIs, routing graph. Bundled asset, fully offline.
2. **Live traffic overlay** — official Traffic SA + DIT outback road warnings. Display-only at first, eventually affecting routing.
3. **Offline routing** — Valhalla-powered driving routes, fully offline. Closure-aware with route alternatives.
4. **Outback road warnings** — official DIT outback road warnings rendered on the map.
5. **Multi-state traffic providers** — pluggable provider architecture supporting SA, NSW, and VIC. NSW and VIC use placeholder endpoints.
6. **Offline routing engine** — Valhalla JNI wrapper (`:routing:engine-valhalla`) for fully offline driving routes with alternatives and closure awareness.
7. **Active navigation** — full-screen turn-by-turn mode with auto-zoom, next-maneuver banner, ETA countdown, and auto-reroute on deviation.
8. **Voice prompts (TTS)** — Android built-in TTS engine for spoken turn instructions with mute toggle.
9. **Live congestion overlay** — TomTom Flow Segment API integration showing green/yellow/red road congestion (requires API key).
10. **Turn-by-turn navigation polish** — lane guidance, speed limit display, arrival announcements, multi-stop routes, and route history.
11. **Route history** — last 20 routes stored locally in Room. Quick-access from route sheet. No auto-upload, local only.
12. **Tablet layout** — responsive side-by-side map + detail panel on screens > 7".
13. **Android Auto** — map + navigation on Auto head units.
14. **Dark mode auto-switch** — follows system theme setting via ThemeMode.System.
15. **Widget** — home screen widget with live route status (GlanceAppWidget).
16. **Accessibility** — contentDescription on all interactive elements, 48dp minimum touch targets, TalkBack support.

## Project structure

```
/home/benbi/Apps/aus-roads/
  android/         # Gradle root — the Android app (24 modules)
    app/             # :app — main application module
    core/            # :core:model, :core:common — pure-Kotlin domain types
    data/            # :data:pins, :data:settings, :data:pack, :data:routes — Room & DataStore
    feature/         # :feature:search, :feature:traffic, :feature:navigation
    traffic/         # :traffic:provider-api, :traffic:provider-sa, :traffic:provider-dit,
                     # :traffic:provider-nsw, :traffic:provider-vic, :traffic:provider-sa-outback,
                     # :traffic:congestion-api
    routing/         # :routing:engine-api, :routing:engine-valhalla — offline routing
    navigation/      # :navigation:tts — text-to-speech wrapper
    offline/         # :offline:pack-api, :offline:pack-downloader, :offline:search
    ui/              # :ui:designsystem — shared Compose theme
    build-logic/     # convention plugins
  tools/           # separate Gradle projects: map-pack-builder, privacy-audit
  docs/            # ADRs, design docs, privacy posture
  .github/         # CI workflows
```

## Build

Requires JDK 17+ and Android SDK with `compileSdk = 35`.

```bash
cd android
./gradlew assembleDebug          # debug build
./gradlew assembleRelease        # release build (requires signing config)
./gradlew test                   # run all unit tests
./gradlew connectedAndroidTest   # instrumented tests (requires device/emulator)
```

The first build will download Gradle 8.10.2 and the AGP/Kotlin dependencies. Expect a 5-10 minute cold build on a developer laptop.

### Release signing

See `docs/notes/release-signing.md` for keystore setup and Play Store signing configuration. Release builds produce an AAB (Android App Bundle) for Play Store distribution.

### Build flavors

- `offline` — no INTERNET permission, no network code, pure offline map.
- `withNetwork` — live traffic, congestion data, community reports (requires network).

## Play Store readiness

- AAB (Android App Bundle) builds configured for Play Store upload.
- R8/ProGuard rules for Valhalla JNI and MapLibre Native SDK.
- Release signing documentation in `docs/notes/release-signing.md`.
- Play Store listing draft in `docs/notes/play-store-listing.md`.
- Privacy policy published at `aus-roads.com/privacy`.
- OSM ODbL attribution complete. DIT / Traffic SA / VicRoads attribution per license.

## Privacy posture

aus-roads is built around a single privacy promise: **the app does not track you**.

v0.1 is offline-only by design. The app makes no network calls after the first-time map pack download, and that download is initiated by the user (not automatic). No analytics, no crash reporting, no ads, no accounts, no background location.

As of v1.0, navigation requires `ACCESS_FINE_LOCATION` (opt-in), and live congestion data is fetched via the TomTom API (requires API key). Navigation GPS data is processed in-memory only and never persisted. TTS voice prompts use the Android built-in engine with no external data transmission. Route history is local-only. Widget shows static content with no network access.

The full privacy statement is in `docs/notes/privacy.md` and is also linked from the in-app Settings → About screen.

## License

Application code: AGPL-3.0-or-later. See `LICENSE`.
Map data: ODbL-1.0 (© OpenStreetMap contributors). See in-app attribution.
Traffic data: CC-BY 4.0 (Traffic SA / DPTI), CC-BY 3.0 AU (DIT outback).
