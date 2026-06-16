# ADR 0004 — LiveTrafficProvider abstraction

## Status

Accepted. 2026-06-01.

## Context

The country-take-over plan is the core product ambition: ship South Australia first,
then NSW, then Victoria, then Australia-wide, then global. Each region has its own
official data source with its own schema, freshness, and licensing. Hard-coding
South Australia logic into the app would force a rewrite per state.

## Decision

Define a single `LiveTrafficProvider` interface in `:traffic:provider-api`. Every
new state is a new `:traffic:provider-XX` module that implements the interface and
gets registered with the app.

The interface is locked in v0.2. Changing it after v0.2 ships breaks every existing
adapter.

## Interface (Kotlin, in `:traffic:provider-api`)

```kotlin
interface LiveTrafficProvider {
    val regionCode: String
    val displayName: String
    val supportedBbox: Bbox

    suspend fun fetchEvents(bbox: Bbox?, ifNoneMatch: String? = null): FetchResult
    suspend fun fetchClosures(bbox: Bbox?, ifNoneMatch: String? = null): FetchResult

    fun supportsChangeTracking(): Boolean = false
}
```

See `android/traffic/provider-api/src/main/kotlin/.../LiveTrafficProvider.kt` for
the full definition.

## v0.2 — AU-SA adapter

Direct REST polling of the public ArcGIS MapServer:

- Layer 0 (`MapServer/0/query`): roadworks and incidents (point features)
- Layer 1 (`MapServer/1/query`): road closures and detours (line features)

Polling cadence: 5 min foreground while map screen is STARTED; 15 min in the
outback; 1m/3m/10m/30m backoff on errors. No background fetch in v0.2 (Play
Store policy + Android 14 foreground-service-type rules). No push notifications.

Type / severity mapping is documented in the data-layer sub-agent plan
(see the locked memory entry for the exact table).

## v0.3 — Outback road warnings

DIT endpoint is live at `maps.sa.gov.au/.../FNRR2/MapServer`. No serverless needed.

## v0.5+ — Community hazard reports

A small backend (Postgres + PostGIS) is required. Reports are:

- submitted by users with explicit, foreground-only location or manual pin drop,
- clustered, not stored as raw points forever,
- auto-expiring,
- confidence-scored (confirm / reject buttons),
- rate-limited per anonymous device key hash,
- never containing a public username.

## v0.6+ — Police / emergency presence

Strictly opt-in, temporary, confidence-thresholded. No "track police" / "avoid
tickets" language; use "roadside police/emergency presence, slow down and drive
safely".

## v0.7+ — Live congestion

Two paths, mutually exclusive:

- Commercial API (TomTom / HERE), with user-visible cost in the About screen.
- Opt-in active-navigation speed crowdsourcing with explicit consent and no
  raw trace retention.

## Consequences

- The provider interface is the architectural pivot. New regions are cheap to add.
- Attribution must be visible per license. Store the attribution string in the
  event row at fetch time, not at render time, so we never lose it on a layout
  refactor.
- The "country take over" is a series of provider module additions, not a series
  of app rewrites.
