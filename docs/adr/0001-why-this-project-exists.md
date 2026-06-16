# ADR 0001 — Why this project exists

## Status

Accepted. 2026-06-01.

## Context

The South Australian government publishes official live traffic data (Traffic SA) and
outback road warnings (DIT) as open data. The OSM community maintains an excellent
and frequently-updated map of South Australia. There is no single Android app that
combines these as an offline-first, privacy-respecting product.

Existing alternatives (Google Maps, Waze, Apple Maps):

- require always-on internet,
- collect precise location history,
- depend on Google Play Services / Apple ID,
- and do not support the AU outback well.

The first credible promise is:

> A privacy-focused offline South Australia map with official live roadworks,
> incidents, closures, events, and outback road warnings.

## Decision

Build `aus-roads`: a single Android app that ships two products side-by-side.

1. **Offline map pack** — derived from OpenStreetMap, downloaded once, updated weekly.
2. **Live traffic overlay** — fetched over the network, displayed on top of the offline
   map, eventually affecting routing.

The two are architecturally separate. Live traffic is not baked into the map pack.

## Consequences

- The provider interface (`LiveTrafficProvider`) is the architectural pivot for the
  country-take-over. New states are new provider modules; the app code is unchanged.
- v0.1 is offline-only. The "no INTERNET" / "no location" posture is part of the
  brand promise. CI enforces it.
- v0.5+ requires a small backend for community reports. This is deferred until v0.4
  is shipped.
- All trademarks and data sources are attributed in-app.
