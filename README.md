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
./gradlew assembleRelease        # release APKs (unsigned without signing config)
./gradlew test                   # run all unit tests
./gradlew -Pausroads.includeX86_64=true \
  :app:connectedOfflineDebugAndroidTest \
  :app:connectedWithNetworkDebugAndroidTest \
  :offline:search:connectedDebugAndroidTest
                                # all instrumented suites on an x86_64 emulator
```

`x86_64` is an emulator-only opt-in. Omit `ausroads.includeX86_64` from
release/shipping builds; production artifacts contain `arm64-v8a` only.

The first build will download Gradle 8.10.2 and the AGP/Kotlin dependencies. Expect a 5-10 minute cold build on a developer laptop.

### Release signing

See `docs/notes/release-signing.md` for keystore setup and Play Store signing
configuration. `assembleWithNetworkRelease` produces an APK;
`bundleWithNetworkRelease` produces an AAB for Play Store distribution.

### Build flavors

- `offline` — no INTERNET permission; maps/search/routing and opt-in foreground GPS stay on-device.
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

The `offline` flavor has no `INTERNET` permission. Foreground location is optional:
the app, map browsing, search, pins, and route planning remain usable without a
grant. Approximate location supports the user marker, recentering, and Nearby in
both flavors. Precise location is required only when the user starts active live
turn-by-turn navigation.

When only approximate location is granted, the route sheet explains that live
turn-by-turn directions need precise location and offers **Enable precise
location**. If Android has blocked another prompt, it offers **Open location
settings** instead. Location fixes are processed in memory only and never
persisted, and no background location permission is declared. There is no
analytics, crash reporting, advertising, or account. Live congestion uses the
TomTom API only when configured with an API key; TTS uses Android's built-in
engine. Route history remains local and the widget makes no network request.

The full privacy statement is in `docs/notes/privacy.md` and is also linked from the in-app Settings → About screen.

## License

Application code: AGPL-3.0-or-later. See `LICENSE`.
Map data: ODbL-1.0 (© OpenStreetMap contributors). See in-app attribution.
Traffic data: CC-BY 4.0 (Traffic SA / DPTI), CC-BY 3.0 AU (DIT outback).
