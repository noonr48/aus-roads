# aus-roads privacy posture

## Summary

aus-roads is built around a single privacy promise: **the app does not track you**.

This is not a marketing line. It is enforced in code and in CI.

## v0.1 (offline-only) guarantees

- **No INTERNET permission** in the shipped manifest. The app is a self-contained
  APK with an offline vector map of South Australia. Network calls are impossible
  at the OS level.
- **No ACCESS_*_LOCATION permission**. No GPS. No "my location" button.
- **No Google Play Services dependency**. No Firebase, no analytics SDK, no
  crash reporter.
- **No background work**. No WorkManager jobs. No foreground services. No push
  notifications.
- **No cloud backup**. `android:allowBackup="false"` and
  `data_extraction_rules` are configured to exclude everything.
- **No accounts**. There is no sign-in flow.

A CI gate (track F3 of the v0.1 sprint) runs `aapt dump permissions` on every
build and fails if any transitive dependency adds `INTERNET`,
`ACCESS_FINE_LOCATION`, or `ACCESS_COARSE_LOCATION`.

## v0.1.1 (current)

v0.1.1 adds MapLibre Native Android SDK 11.5.2 for offline map rendering and Hilt for dependency injection. The privacy posture is unchanged:

- **No INTERNET permission.** MapLibre's AAR bundles INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, ACCESS_COARSE_LOCATION, and ACCESS_FINE_LOCATION in its manifest. All five are stripped via `tools:node="remove"` in the app's AndroidManifest.xml. The merged manifest (verified via `aapt2 dump permissions`) contains only the auto-generated DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION.
- **No location permissions.** No ACCESS_*_LOCATION in the merged manifest.
- **No network code.** Zero calls to HttpURLConnection, OkHttpClient, Retrofit, or URL.openConnection() in any source file.
- **No analytics or tracking.** No Firebase, Crashlytics, Google Play Services, or third-party analytics SDKs.
- **Offline map tiles.** The bundled Adelaide test pack (19 MB MBTiles) is loaded from assets. No remote tile fetching.
- **Local persistence only.** Room databases (pins, pack metadata) and DataStore (settings) are stored locally. No cloud sync.

## v0.2+ (live traffic overlay)

When the Traffic SA overlay is added in v0.2, the privacy posture changes in
one specific way: **the app gains the `INTERNET` permission to fetch live
traffic data, but only when the user has the live overlay enabled**.

What does NOT change in v0.2:

- No location permission is added. Live traffic is fetched on a timer; the
  app does not know where you are.
- No background location.
- No analytics, no crash reporting.
- No accounts, no sign-in.

What is added in v0.2:

- The app fetches the visible viewport's traffic events every 5–15 minutes.
- The app stores the fetched events in a local Room database. No event is
  sent off-device.
- The user can disable the live overlay at any time; the app returns to a
  pure-offline state and `INTERNET` is not used (the permission remains
  declared, but no code path is hit).

## v0.5+ (community hazard reports)

If you choose to submit a hazard report:

- The report is sent to a small aus-roads server, over HTTPS, with no
  identifier attached other than a rotating, anonymous device key hash.
- The report includes only the pin location, the hazard type, and the
  timestamp. No name, no email, no phone, no precise continuous trace.
- Reports auto-expire. The default is 30 minutes for moving hazards (debris,
  stopped vehicle) and 4 hours for static hazards (lane closed, road
  closed). A moderation job can extend or shorten.
- You can delete all your reports from the Settings screen.

## v0.6 (multi-state traffic)

v0.6 adds NSW and VIC traffic providers alongside the existing SA and DIT providers. The privacy posture evolves:

- **INTERNET required (withNetwork flavor).** Multi-state traffic providers (`:traffic:provider-nsw`, `:traffic:provider-vic`) and DIT outback warnings (`:traffic:provider-dit`) require network access. The app ships with the `INTERNET` permission when built with the `withNetwork` flavor.
- **Valhalla routing is fully offline.** Route computation uses bundled `valhalla_tiles.tar` and makes no network calls. No INTERNET needed for routing.
- **All traffic providers use ETag conditional GETs.** Requests include `If-None-Match` headers; servers return 304 Not Modified when data hasn't changed, minimising bandwidth.
- **No analytics, no tracking, no user identification.** Traffic fetches include no device ID, no user agent fingerprinting, no telemetry. The only data sent is the HTTP request itself.
- **NSW and VIC providers use placeholder endpoints.** The provider modules are wired and tested but the upstream API endpoints are placeholders until verified against the live NSW RMS and VicRoads APIs.

What does NOT change in v0.6:

- No location permission for traffic fetching.
- No analytics or crash reporting.
- No accounts, no sign-in.
- Community hazard reports remain opt-in with the same privacy guarantees as v0.5.

## v0.7 (live congestion + active navigation)

v0.7 adds active navigation and live congestion data. The privacy posture evolves:

- **GPS permission escalation.** Navigation requires `ACCESS_FINE_LOCATION` (upgraded from `ACCESS_COARSE_LOCATION` in v0.6). This is opt-in; the app functions without it but navigation mode is unavailable.
- **Navigation GPS data is processed in-memory only.** Location samples during navigation are used for route progress tracking and auto-reroute. They are not written to disk, not persisted in any database, and not transmitted to any server.
- **Speed crowdsourcing is opt-in, default OFF.** Users can choose to share anonymized, segment-averaged speed data while actively navigating. Raw GPS traces are never stored or uploaded. The consent dialog explains exactly what is shared (segment + average speed) and what is not (raw trace, identity). Opt-out is available at any time in Settings.
- **TTS uses Android's built-in engine.** Voice prompts are generated locally via `android.speech.tts.TextToSpeech`. No audio data is sent to any external server.
- **TomTom congestion API calls include only the request bbox.** No user identity, no device ID, no tracking. API key is required (documented in About screen).

What does NOT change in v0.7:

- No analytics or crash reporting.
- No accounts, no sign-in.
- Community hazard reports remain opt-in with the same privacy guarantees as v0.5.
- All traffic providers continue to use ETag conditional GETs.

## v0.8 (turn-by-turn navigation polish)

v0.8 adds lane guidance, speed limits, multi-stop routes, route history, and arrival announcements. The privacy posture evolves:

- **Route history stored locally in Room.** The last 20 routes are saved on-device for quick access. Route data is never uploaded to any server.
- **No route data transmitted.** Origin, destination, waypoints, and route geometry remain local-only. No network calls for route history.
- **Android Auto uses notification channel only.** The Auto integration displays navigation via a notification-based template. No new data collection or transmission.
- **Widget shows static content, no network access.** The home screen widget displays cached route info from the local Room database. It makes no network requests.

What does NOT change in v0.8:

- No analytics or crash reporting.
- No accounts, no sign-in.
- No new permissions required.
- Community hazard reports remain opt-in with the same privacy guarantees as v0.5.
- All traffic providers continue to use ETag conditional GETs.
- Navigation GPS data continues to be processed in-memory only.

## v0.9 (tablet + Auto + polish)

v0.9 adds tablet layout, Android Auto, dark mode auto-switch, and a home screen widget. The privacy posture evolves:

- **Widget shows cached content, no network access.** The GlanceAppWidget displays route info from the local Room database. It makes no network requests.
- **Dark mode auto-switch reads system theme only.** No data transmitted. Uses `ThemeMode.System` to follow the device setting.
- **Android Auto uses notification channel only.** No new data collection or transmission beyond what v0.8 established.
- **Tablet layout is a UI-only change.** No new permissions, no new data flows.

What does NOT change in v0.9:

- No analytics or crash reporting.
- No accounts, no sign-in.
- No new permissions required.
- Community hazard reports remain opt-in with the same privacy guarantees as v0.5.
- All traffic providers continue to use ETag conditional GETs.
- Navigation GPS data continues to be processed in-memory only.

## v1.0 (Play Store release)

v1.0 is the Play Store release. The privacy posture is final:

- **No new data collection.** v1.0 adds no telemetry, no analytics, no crash reporting.
- **Accessibility fixes are UI-only.** contentDescription and 48dp touch targets add no data flows.
- **Release signing uses Play App Signing.** Google manages the signing key; no impact on privacy.
- **Play Store listing requires a privacy policy URL.** Published at `aus-roads.com/privacy`, covering all data flows documented in this file.

What does NOT change in v1.0:

- No analytics or crash reporting.
- No accounts, no sign-in.
- No new permissions required beyond what v0.7 established.
- Community hazard reports remain opt-in with the same privacy guarantees as v0.5.
- All traffic providers continue to use ETag conditional GETs.
- Navigation GPS data continues to be processed in-memory only.

## Final privacy posture (v1.0)

| Data type | Stored | Transmitted | Opt-in | Retention |
|-----------|--------|-------------|--------|-----------|
| Map tiles | Device storage | No | N/A | Until user deletes pack |
| Pins | Room DB | No | N/A | Until user deletes |
| Settings | DataStore | No | N/A | Until user resets |
| Route history | Room DB | No | N/A | Last 20 routes, local only |
| Traffic events | Room DB | Fetched from official APIs | Traffic toggle | ETag-cached, local only |
| Navigation GPS | In-memory only | No | Location permission | Not persisted |
| Community reports | Room DB + server | HTTPS to aus-roads server | Opt-in (default OFF) | 4 hours on server, local until delete |
| Speed crowdsource | Segment averages only | HTTPS to aus-roads server | Opt-in (default OFF) | Anonymized, no raw traces |
| Widget | Room DB (read-only) | No | N/A | Mirrors route history |

**The app does not track you. No analytics, no crash reporting, no accounts, no background uploads.**

## What the app does NOT do, ever

- sell your data,
- share your data with advertisers,
- use your data to train third-party ML models,
- contact you with marketing,
- require an account to function,
- track you across apps,
- operate a background service that uploads anything.

## Contact

If you have a privacy concern, file an issue on the public repository.
