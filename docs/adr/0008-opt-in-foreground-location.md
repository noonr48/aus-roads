# ADR 0008 — Opt-in foreground location in both app flavors

## Status

Accepted. 2026-07-17.

## Context

ADR 0001 made the v0.1 absence of location permission part of the first release
promise. That constraint protected the initial map-rendering milestone, but it no
longer describes the product: the map now has a user-location marker and recenter
control, Nearby can search around the device, and active navigation can follow the
user. Those features need Android foreground location access.

Treating all location access as incompatible with an offline product also confuses
two separate capabilities. Android can provide a device fix without giving the app
network access. The `offline` flavor can therefore offer useful foreground location
features while retaining its OS-enforced no-`INTERNET` boundary.

## Decision

Both `offline` and `withNetwork` declare `ACCESS_COARSE_LOCATION` and
`ACCESS_FINE_LOCATION` through the shared application manifest.

- The runtime prompt is user-initiated from a location feature. Location permission
  is not an installation or app-browsing prerequisite: browsing and searching the
  map, dropping pins, and planning or inspecting routes from manually chosen points
  remain usable when permission is denied.
- Approximate location supports the user marker, recentering, and Nearby. Precise
  location is required only when the user starts active live turn-by-turn
  navigation; calculating and inspecting a route does not require it. Both choices
  use the same foreground permission flow.
- With approximate-only access, the route sheet explains that map browsing works
  but live turn-by-turn directions need precise location and offers **Enable
  precise location**. If Android has blocked another prompt, it offers **Open
  location settings**. Declining leaves the computed route available to inspect.
- Location subscriptions run only while the relevant UI is in the foreground and
  stop when its lifecycle stops. The app does not declare
  `ACCESS_BACKGROUND_LOCATION`.
- Fixes used by the map marker, recentering, Nearby, and navigation are ephemeral.
  They are not written to a location-history database, analytics system, crash
  report, or telemetry stream.
- The `offline` flavor continues to omit `INTERNET`. It uses Android's local
  `LocationManager`, so its fixes cannot be transmitted by the app. When permission
  is absent or no usable fix is available, location-aware browsing falls back to an
  explicit map reference point, ultimately Adelaide.
- The `withNetwork` flavor may use its declared network access for separately
  disclosed features such as map-pack downloads and live traffic. Granting location
  does not opt the user into analytics, location history, or background uploads.

This decision supersedes only ADR 0001's "no location" consequence. ADR 0001's
offline-first purpose, the `offline` flavor's no-`INTERNET` boundary, and the wider
no-accounts/no-analytics privacy promise remain accepted.

## Consequences

- Permission and denial states are part of the Map, Nearby, and active-navigation
  UX, not an installation prerequisite. Approximate-only access must never disable
  Map or Nearby merely because precise access is absent.
- Features must label whether they are using a device fix or a fallback reference
  point; they must not present Adelaide or the map centre as the user's position.
- New continuous or screen-off location features require a separate design and
  policy review. This ADR does not authorize background location.
- CI permits the coarse/fine foreground pair in both application variants, rejects
  `ACCESS_BACKGROUND_LOCATION`, and continues to reject `INTERNET` in `offline`.

## Verification

- Inspect both merged manifests for the coarse/fine foreground pair and absence of
  `ACCESS_BACKGROUND_LOCATION`.
- Inspect the `offline` merged manifest for absence of `INTERNET`.
- Run `tools/privacy-audit/run.sh`.
- Device-test approximate and precise grants, denial, and settings recovery. Confirm
  Map/Nearby work with approximate access, the computed route remains inspectable
  without precise access, and active live turn-by-turn starts only after a precise
  grant. Confirm Map/Nearby still provide their non-location fallback when no
  location access is granted.
