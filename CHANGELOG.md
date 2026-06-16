# Changelog

## v1.0 (2026-06-03)
- Play Store release preparation
- Accessibility fixes (contentDescription, 48dp touch targets)
- Widget with live traffic status
- Dark mode auto-switch
- Release signing documentation
- Play Store listing draft

## v0.9 (2026-06-03)
- Dark mode auto-switch via ThemeMode.System
- Widget with live data (GlanceAppWidget)
- Widget receiver registered in AndroidManifest

## v0.8 (2026-06-03)
- Route history UI (last 20 routes in ModalBottomSheet)
- Arrival announcements via TTS
- Tablet layout (NavigationRail for sw600dp+)
- Android Auto stub (notification capability)
- Widget stub

## v0.7 (2026-06-03)
- Active navigation system (NavigationViewModel + NavigationOverlay)
- GPS integration (FusedLocationProviderClient)
- TTS voice prompts (AU English)
- Battery warning after 30 min
- KeepScreenOn during navigation
- Speed limit display
- Lane guidance data model
- Multi-stop routes (via waypoints)
- Route history (Room persistence)
- Congestion API interface

## v0.6 (2026-06-02)
- NSW Live Traffic provider
- VicRoads traffic provider
- R8 release build fix (SLF4J ProGuard rules)
- Privacy audit fixes
- String externalization (28 strings)
- @Preview functions (6)
- ViewModel unit tests (11)

## v0.4 (2026-06-02)
- Valhalla offline routing engine
- Route rendering on map (blue line)
- Route info sheet (distance, duration, maneuvers)
- Valhalla tile generation pipeline

## v0.3 (2026-06-02)
- DIT outback road warnings provider
- Multi-provider architecture (Set<LiveTrafficProvider>)
- Per-source traffic toggles
- Search result categorization (grouped by kind)
- Pin editing (rename + color + delete)

## v0.2 (2026-06-02)
- Traffic SA provider (ArcGIS polling)
- Traffic events rendered on map (GeoJSON layers)
- Traffic event details bottom sheet
- Traffic status pill
- Traffic toggle in Settings

## v0.1.2 (2026-06-02)
- Free-text search (SQLite FTS5, 128k features)
- Search bar UI with camera fly-to
- Map pack downloader (Ktor + WorkManager)
- Build flavors (offline/withNetwork)
- Privacy audit tooling

## v0.1.1 (2026-06-02)
- MapLibre Native Android SDK integration
- Hilt dependency injection
- Room 2.7.0 databases
- DataStore settings
- All UI screens wired
- ProGuard rules

## v0.1 (2026-06-02)
- Initial project scaffold
- 8 modules, offline map foundation
- Privacy posture (no INTERNET, no LOCATION)
