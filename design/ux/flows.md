# aus-roads UX flows — C-G1

**Tier:** L — the refinement crosses the map, search, Nearby, location, pin, route, and settings surfaces.

**Scope:** the Android app's shipped Map, Nearby, Pins, Settings, About, routing, and map-pack recovery journeys. “Current” means the working tree on 2026-07-17; device proof is recorded separately in `flow-check.md`.

The interaction grammar follows familiar Android map apps: search is a top map field like Google Maps; pinch/drag/double-tap manipulate the map; a long-press creates a place; and persistent destinations live in a bottom bar. The one deliberate divergence is that Nearby is a dedicated tab, because its emergency, coordinate, and sharing tools need more room than a map overlay.

## Primary-control conventions

- **Search field, selected-place marker/banner, My location target, Directions sheet:** Google Maps/Apple Maps conventions.
- **Nearby category chips:** horizontally scrolling Google Maps-style category filters; dedicated tab divergence justified above.
- **Hold to drop a pin:** mainstream mobile-map convention; a focused canvas accepts **Enter**, **Space**, or DPAD centre at camera centre, and **Add pin** supplies a coordinate-form alternative.
- **Add pin FAB, modal forms, radio colours:** standard Material Android creation/form conventions.
- **Settings rows:** Android Settings convention — the whole labelled row is one radio/switch target.
- **Delete confirmation / creation Undo / draft guard:** destructive pin and recent-route deletion use confirmations that name the affected object; the immediately preceding pin creation remains reversible through **Undo**; leaving a changed pin draft asks **Discard** or **Keep editing**.
- **Share location:** Android Sharesheet convention; the app prepares text, then hands off to the system.

## Flow: Explore the offline map

- **Goal:** see useful roads, labels, and places without needing a connection.
- **Trigger:** launches the app or taps **Map** in the bottom navigation.
- **Start:** Map destination before the native map style is ready.
- **Steps:**
  1. system shows **“Loading offline map…”** with a spinner → then reveals the Adelaide map when the style is usable.
  2. user drags/pinches/double-taps, or focuses the canvas and uses arrow/plus/minus keys → system pans or scales roads, labels, POIs, the location halo, and saved markers together; focus is visible as a primary-colour border.
  3. optionally, user taps a visible map feature, or keyboard-selects the same named place through Search/Nearby and its result banner → system opens a compact feature sheet with its friendly type and available actions.
- **End:** the desired area or feature is visible at a legible street scale.
- **Exit Points:** bottom navigation → another destination; map failure → `edges:map-load-failed`; no usable pack → `edges:map-pack-missing`; generic traffic refresh failure → retain prior provider snapshots and **Retry** (`edges:traffic-data-unavailable`); NSW/VIC HTTP 401/403 → retain the rejected provider's prior snapshot plus unaffected providers, name the region, and offer **Open traffic settings** (`edges:traffic-credentials-invalid`); dismiss feature sheet → same camera position.
- **Step Count:** 3 (OK).

## Flow: Search for a place

- **Goal:** find a named place, road, suburb, park, water feature, or POI and see it on the map.
- **Trigger:** taps the top search field on Map.
- **Start:** interactive map with an empty search field.
- **Steps:**
  1. user types a query → system immediately acknowledges input, then shows **“Searching…”** during the 300 ms-debounced offline lookup.
  2. system groups matching results under friendly headings and shows a type/category plus coordinates for disambiguation.
  3. user taps a result → system clears the query, hides the keyboard, moves the camera, and highlights the result with a marker and named banner.
  4. user taps the banner or marker → system opens the compact details sheet.
  5. user taps **Save pin** or **Directions** → system starts the chosen downstream flow.
- **End:** the selected result is highlighted and its details/actions are available.
- **Exit Points:** **Clear search** → empty field/map; back or tap outside → leave search without changing camera; no results → choose a broader query; **Retry** → reissue the unchanged failed query (`edges:search-failed`); **Manage map pack** → Settings while retaining the unavailable query (`edges:search-data-unavailable`); details dismiss → selected marker remains.
- **Step Count:** 5 (OK).

## Flow: Find a nearby place

- **Goal:** find the closest fuel, charging, emergency service, amenity, campsite, or supermarket and put it on the map.
- **Trigger:** taps **Nearby** in the bottom navigation.
- **Start:** Nearby screen using either a current device fix or the clearly disclosed Adelaide CBD fallback.
- **Steps:**
  1. user reads the location-status line and taps a category chip → system selects the chip and shows a centered spinner.
  2. system shows distance-sorted result cards directly below the categories.
  3. user taps a result's named map action → system switches to Map, preserves tab state, centers the result, and shows its marker/banner.
  4. user taps the banner or marker → system opens place details with **Save pin** and **Directions**.
- **End:** the chosen nearby place is selected on the map.
- **Exit Points:** another category → replace results; no results → `edges:nearby-no-results`; failed category query → **Retry** the selected category (`edges:nearby-query-failed`); failed emergency lookup → retain any successful hospital/police row and **Retry** (`edges:emergency-data-unavailable`); search data still preparing → keep category/reference, distinguish emergency preparation from a completed empty lookup, and offer **Manage map pack** (`edges:nearby-data-unavailable`); bottom navigation → leave while retaining the current category; result sheet dismiss → selected marker remains.
- **Step Count:** 4 (OK).

## Flow: Show my location

- **Goal:** see where I am and center the map there, with approximate location accepted for browsing.
- **Trigger:** taps **My location** on Map, or **Use my location** on Nearby.
- **Start:** map or Nearby reference with location absent, stale, or not yet granted.
- **Steps:**
  1. user taps the location action → system uses an already-fresh fix or requests approximate and precise foreground permission together.
  2. user grants approximate or precise access → system shows a persistent scoped spinner while waiting for an active fix and keeps map browsing/fallback coordinates available.
  3. system shows the blue location puck with a zoom-aware accuracy halo and recenters Map, or refreshes Nearby distances and changes the status to **“Using your device location”**.
  4. user later taps the action again → system requests/uses a fresh fix and recenters or refreshes again; approximate fixes share the 15-minute freshness policy and remain usable across Android's coarse callback cadence.
- **End:** a current approximate/precise position is visibly in use.
- **Exit Points:** deny on Map → explanatory snackbar and continued browsing; deny on Nearby → acknowledgment snackbar plus disclosed Adelaide fallback (`edges:location-permission-denied`); permanently block → `edges:location-permission-blocked`; service off/no fix → `edges:location-fix-unavailable`; fix outside active coverage → keep the camera and offer the snackbar action **Manage map pack** (`edges:location-outside-map-pack`); navigate away while locating → cancel the one-shot recenter request.
- **Step Count:** 4 (OK).

## Flow: Drop and save a pin from the map

- **Goal:** remember an exact point on the map.
- **Trigger:** holds anywhere on the interactive map, or focuses the native map and presses Enter, Space, or DPAD centre to use camera centre.
- **Start:** ready map with no modal sheet open.
- **Steps:**
  1. user holds a point, or activates the focused map from the keyboard → system provides haptic feedback for touch where available and opens **Drop a pin here** at that coordinate/camera centre.
  2. system attempts an offline place-name suggestion while keeping the coordinate usable; user edits the name and selects one of six named colours.
  3. user taps **Save pin** → system disables Save pin, shows an inline spinner, and persists the pin.
  4. system closes the sheet, renders the coloured marker, and shows **“Saved “{name}””** with **Undo**.
- **End:** the pin is visible on Map and in Saved pins.
- **Exit Points:** dismiss an unchanged sheet → return to Map; back/outside-tap/swipe after changing name/colour is intercepted only when the sheet is actually hiding, using current save/dirty state rather than a stale callback capture, then shows **Discard unsaved pin changes?**; **Keep editing** explicitly re-shows the intact sheet and **Discard** closes it (`edges:pin-draft-dismiss`); **Directions** → route to the point without saving; save failure → `edges:pin-save-failed`; **Undo** → delete the just-saved pin immediately, with a named failure message if that compensating delete fails.
- **Step Count:** 4 (OK).

## Flow: Add a pin by coordinates

- **Goal:** save a known latitude/longitude without manipulating the map.
- **Trigger:** taps **Add pin** on the Pins tab.
- **Start:** Saved pins list or first-run empty state.
- **Steps:**
  1. user opens Add pin → system shows name, latitude, longitude, and six named colour choices with Adelaide coordinates as editable defaults.
  2. user enters values and leaves each field → system validates required name and world bounds inline on blur, preserves every value, and enables **Add pin** only when all required values are valid.
  3. user taps **Add pin** → system disables dismissal, shows a spinner in the action, and persists the pin.
  4. system closes the dialog and shows the new card in Saved pins.
- **End:** a new saved-pin card appears with its colour and coordinates.
- **Exit Points:** **Cancel**/outside dismiss with an unchanged form → close; changed draft → **Discard unsaved pin changes?** with **Discard** / **Keep editing** (`edges:pin-draft-dismiss`); blank name → `edges:pin-name-required`; invalid latitude/longitude → `edges:pin-coordinate-invalid`; write failure → `edges:pin-save-failed` with all fields retained.
- **Step Count:** 4 (OK).

## Flow: Edit or remove a saved pin

- **Goal:** rename/recolour a saved point or remove one no longer needed.
- **Trigger:** taps a pin card/marker, or its delete icon.
- **Start:** a populated Saved pins list or saved marker on Map.
- **Steps:**
  1. user taps the card/marker → system opens the pin editor/detail with name, coordinate, colour, and available actions.
  2. user changes the name/colour; leaving a blank required name shows inline validation with the draft preserved, while **Save pin changes** locks both actions, shows a spinner, and waits for the repository callback.
  3. user taps **Delete** from the list, editor, or map detail → system asks **“Delete “{name}”?”** before starting the write and shows progress only on the affected surface.
  4. update success closes the editor; delete success removes the pin and closes the owning sheet where applicable. Failure retains the pin/editor, names the pin, and leaves a direct retry path.
- **End:** the persisted change is reflected everywhere, or the confirmed deletion is absent; completion is never claimed before Room reports one affected row.
- **Exit Points:** dismiss an unchanged editor → retain original pin; dismiss a changed editor → **Discard unsaved pin changes?** with **Discard** / **Keep editing** (`edges:pin-draft-dismiss`); **Cancel** in deletion confirmation → keep pin and current surface; blank name → inline `edges:pin-name-required` and **Save pin changes** remains disabled; update failure → retained draft and **Save pin changes** retry (`edges:pin-update-failed`); delete failure → retained pin and **Try again**/Delete retry (`edges:pin-delete-failed`).
- **Step Count:** 4 (OK).

## Flow: Get directions and start navigation

- **Goal:** calculate an offline route to a selected point and optionally start spoken turn-by-turn guidance.
- **Trigger:** taps **Directions** in a feature, result, dropped-point, or pin detail sheet; or selects a recent route.
- **Start:** Map with a destination and either a fresh location or a distinct pre-focus/current camera point as origin.
- **Steps:**
  1. user taps **Directions** → system chooses a non-identical origin and shows **“Routing…”** with a spinner.
  2. system draws the route and opens a sheet with distance, time, maneuvers, and route-option switches.
  3. user changes an avoid option → system recomputes the route while preserving destination.
  4. user taps **Start navigation** → system starts guidance if precise location is granted, closes only the planning sheet, and retains the exact started route as the visible map line/session record.
  5. if only approximate location is granted, user taps **Enable precise location** or **Open location settings**, grants access, then starts guidance.
- **End:** active navigation is visible, or the calculated route remains inspectable on Map.
- **Exit Points:** **Close route**/dismiss → clear route and revoke any in-flight computation's state ownership; **Stop navigation** → queue the exact started route for history, then clear the session/line; missing origin → `edges:route-origin-unavailable`; engine absent → **Manage map pack** (`edges:route-engine-unavailable`); no route/IO/unexpected computation failure → retain origin, destination, and options with **Retry** (`edges:route-not-found`, `route-computation-failed`); history write failure → keep Map usable and **Retry** the exact pending history entry (`edges:route-history-save-failed`); precise permission denied → retain route for inspection; app background/host STOP → pause GPS while retaining the session/progress, then resume exactly once on START; provider failure/initial or active fix timeout during guidance → preserve the last navigation state behind **Retry location** / **End navigation** (`edges:navigation-location-lost`); failed off-route update → keep the previous maneuver/distance/time visible behind **Retry route** / **End navigation** (`edges:navigation-recalculation-failed`). Changing destination/options or retrying cancels and supersedes the older request so a stale native result cannot overwrite current state.
- **Step Count:** 5 (OK).

## Flow: Reuse or remove a recent route

- **Goal:** recalculate a route used before, or clear history entries.
- **Trigger:** taps **History** on Map.
- **Start:** ready map.
- **Steps:**
  1. user opens History → system shows **“Loading recent routes…”**, then **Recent routes** or the explicit empty state **“No recent routes”**; a read failure becomes an inline message with **Retry** instead of masquerading as empty.
  2. user taps an entry → system closes history and recomputes that origin/destination; or taps the named **Delete route to {destination}** action.
  3. deletion asks **“Delete route to “{destination}”?”** and explains the local consequence; **Cancel** keeps the route, while Delete starts a pessimistic write with an affected-row spinner and a global lock on sheet dismissal, route selection, and every delete action until the callback resolves.
  4. exactly one removed Room row ends the operation; failure retains the route, names its destination inline, and offers **Retry**. A selected route continues to the route sheet after successful recomputation.
- **End:** the chosen route is active, or the confirmed history entry is absent only after persistence succeeds.
- **Exit Points:** dismiss sheet → return to unchanged map; empty state → dismiss; read failure → **Retry** resubscribes or dismiss returns to Map (`edges:route-history-load-failed`); **Cancel** confirmation → retain route/history sheet; routing failure → matching route edge; delete failure → retained entry + **Retry** (`edges:route-history-delete-failed`).
- **Step Count:** 4 (OK).

## Flow: Recover or update a map pack

- **Goal:** restore a usable offline map or replace it with the latest available pack.
- **Trigger:** taps **Manage map pack**, **Re-download map**, or **Download map pack**.
- **Start:** map load/missing-pack recovery or Settings > Map pack.
- **Steps:**
  1. user opens Settings > Map pack → system shows installed version, not-installed state, or explains that downloads are unavailable in the privacy build.
  2. user taps Download/Re-download → system checks the manifest and immediately disables duplicate starts.
  3. system shows named phases and determinate bytes when known, with **Cancel** throughout the long operation.
  4. system verifies, extracts, installs, and activates the pack; Map, search, and routing reopen against the same pack identity.
- **End:** Settings shows **Installed** with version/size and Map becomes usable.
- **Exit Points:** **Cancel** → keep the previous installed pack; unavailable/not found/unreachable/invalid/corrupt → matching map-pack edge with a retry control; unexpected worker failure → stable safe copy without raw diagnostics (`edges:map-pack-worker-failed`); leave Settings → background work continues through the worker.
- **Step Count:** 4 (OK).

## Flow: Configure app preferences

- **Goal:** choose appearance, overlays, spoken alerts, traffic credentials, and attribution visibility.
- **Trigger:** taps **Settings** in bottom navigation.
- **Start:** settings loaded from local DataStore.
- **Steps:**
  1. user selects System default, Light, or Dark → system applies and persists the theme.
  2. user toggles traffic, text-to-speech, or attribution using the full labelled row → system applies and persists the setting.
  3. user enters optional NSW/VIC API keys → system masks and persists the text; route avoid options use the same persistence owner from Map.
  4. if any write fails, system names the exact setting in a shared Map/Settings snackbar; **Retry** replays that exact retained mutation and shows **Retrying…**, while **Dismiss** invalidates the stale retry.
  5. user taps **View full attribution** → system opens About; Back returns to Settings.
- **End:** the selected preferences remain active across navigation/relaunch.
- **Exit Points:** bottom navigation → leave with changes auto-saved; local persistence failure → **Retry** the exact setting mutation or **Dismiss** and keep the previous persisted value authoritative (`edges:settings-save-failed`); About Back → Settings.
- **Step Count:** 5 (OK).

## Flow: Share a location

- **Goal:** send current/fallback coordinates or an emergency place through another app.
- **Trigger:** taps **Share location** or the named share action beside an emergency result.
- **Start:** Nearby coordinates or emergency card.
- **Steps:**
  1. user taps Share → system creates plain text containing optional place name, six-decimal coordinates, and an OpenStreetMap link.
  2. Android shows its share chooser → user picks a target app.
  3. target app receives the prepared message; if the chooser cannot launch, the app copies that same complete name/coordinates/OpenStreetMap text and confirms the fallback in a snackbar.
- **End:** the system share target contains the location text, ready to send, or the identical prepared text is safely on the clipboard.
- **Exit Points:** dismiss Android chooser → return to Nearby unchanged; chooser launch/no-target failure → automatic clipboard recovery (`edges:share-target-unavailable`).
- **Step Count:** 3 (OK).
