# aus-roads flow walkthrough — C-G5

**Walkthrough basis:** current Kotlin/resources plus emulator receipts in `android/build/reports/vision-acceptance-20260717/`. The post-route-threading and copy/layout rebuild is recorded under `refinement-final/`; “device-evidenced keyboard” is reserved for receipts 75–77: first Tab focuses the native canvas with a visible border, hardware zoom/pan changes the viewport, and Enter opens pin creation. “Structurally focusable” still means a real Compose/native control when a particular control was not physically traversed in this run.

**Gate status:** **OPEN — 15/16**. The recovery gaps that previously blocked route computation/history, active-navigation location loss/recalculation, settings writes, saved-pin reads, and generic/credential traffic failures are closed in the current source. Receipts through 92 plus `refinement-final/` prove the steady map/location puck, live road/label scaling, first-Tab map focus and visible border, keyboard pan/zoom/Enter, touch hold/save/list persistence, friendly Search and selection, and device-centred Nearby stores with an entirely visible selected category. Criterion 15 passes. Criterion 8 remains open only because the planned primary-destination unexpected-screen boundary has no live implementation.

**Current automated receipts:** `./gradlew --no-parallel test lintOfflineDebug lintWithNetworkDebug assembleOfflineRelease assembleWithNetworkRelease` is green, as are both release APK assemblies. Six focused Android 14 AVD invocations are 12/12 total for cold map start plus `RouteOwnershipLifecycleInstrumentedTest`; the five preserved stress runs and fresh independent reviewer run each show exactly one route-test MapView. They prove the exact route remains owned and drawn through navigation start and `CREATED → RESUMED`, exactly one replacement location subscription starts, progress survives, Stop persists the exact route, and all ViewModel/native route state is removed. Existing route/history/navigation and traffic unit receipts remain green.

## Flow: Explore the offline map

- **Walkthrough:**
  1. launch → map load seam exists (`states.md`: Offline map canvas).
  2. ready → roads, road names, POIs, demo scope, search, History, blue location puck/halo, attribution, first-use pin guidance, and bottom navigation coexist; [refinement-final/map-located.png](../../android/build/reports/vision-acceptance-20260717/refinement-final/map-located.png) and [map-located.xml](../../android/build/reports/vision-acceptance-20260717/refinement-final/map-located.xml) are the latest steady-state receipts.
  3. double-tap/pinch/zoom → roads, labels, and the location halo stay on the same camera and rescale coherently; the latest [scale-before.png](../../android/build/reports/vision-acceptance-20260717/refinement-final/scale-before.png) / [scale-after.png](../../android/build/reports/vision-acceptance-20260717/refinement-final/scale-after.png) pair visibly changes road, building, and label scale while keeping the blue puck anchored. The earlier real-pinch pair [54a-before-pinch.png](../../android/build/reports/vision-acceptance-20260717/54a-before-pinch.png) / [54b-after-pinch.png](../../android/build/reports/vision-acceptance-20260717/54b-after-pinch.png) remains the multi-touch receipt.
  4. theme/orientation change → map and Compose chrome remain usable in [38-map-dark.png](../../android/build/reports/vision-acceptance-20260717/38-map-dark.png), [39-map-landscape-dark.png](../../android/build/reports/vision-acceptance-20260717/39-map-landscape-dark.png), and restored light receipt 40.
  5. tap feature → compact feature details state exists; [24-refined-feature-sheet.png](../../android/build/reports/vision-acceptance-20260717/24-refined-feature-sheet.png).
  6. missing/load-failed branches → both route to Settings via `edges:map-pack-missing` / `map-load-failed`; native load-listener and offline-style preparation exceptions share the same actionable state. Failure injection was not device-run.
  7. first Tab from launch → [75-focus-border.png](../../android/build/reports/vision-acceptance-20260717/75-focus-border.png) visibly proves the 3 dp primary border around the whole map; [75-focus-border.xml](../../android/build/reports/vision-acceptance-20260717/75-focus-border.xml) records the native map as `focusable=true` and `focused=true`.
  8. plus/minus and arrow keys on that focused canvas → [76-keyboard-zoom-pan.png](../../android/build/reports/vision-acceptance-20260717/76-keyboard-zoom-pan.png) / [76-keyboard-zoom-pan.xml](../../android/build/reports/vision-acceptance-20260717/76-keyboard-zoom-pan.xml) prove the changed camera/road scale while the map retains focus. The capture has compositor gaps outside the map evidence, so the focused XML and viewport change are the relevant proof.
  9. press Enter → [77-keyboard-drop-pin.png](../../android/build/reports/vision-acceptance-20260717/77-keyboard-drop-pin.png) and [77-keyboard-drop-pin.xml](../../android/build/reports/vision-acceptance-20260717/77-keyboard-drop-pin.xml) prove **Drop a pin here** opened at camera centre with the Name field focused and Directions/Save pin available.
  10. enabled traffic failure → generic failure says **Traffic unavailable** with **Retry**; sanitized NSW/VIC HTTP 401/403 says **{region} traffic needs a valid API key. Check it in Settings.** with **Open traffic settings**. Source/tests prove the rejected provider's last snapshot remains, unaffected providers refresh, recovery clears guidance, 304 retains data, successful empty clears it, and disabling/leaving Map cancels polling plus active refresh. Current reports are green 14/14 ViewModel, 7/7 presentation, 4/4 provider-status typing, 10/10 FetchResult, NSW 5/5, and VIC 6/6.
- **Dead-ends found:** the map flow itself has a usable exit for missing/load-failed/traffic/outside-coverage states. The global `edges:unexpected-screen-failure` boundary is still not implemented.
- **Keyboard:** physical focus, arrow pan, plus/minus zoom, Enter pin creation, and visible focus are proven. Search/Nearby provide a keyboard-reachable identification/details path for named places; direct arbitrary feature hit-testing remains a touch shortcut rather than a required Explore end.
- **Fixes:** add the planned app-shell unexpected-screen boundary; a full TalkBack sweep remains useful evidence but is no longer a criterion-15 blocker.

## Flow: Search for a place

- **Walkthrough:**
  1. type `IGA` → opaque field, visible focused outline, and grouped friendly results with category/coordinates exist; [refinement-final/search.png](../../android/build/reports/vision-acceptance-20260717/refinement-final/search.png) and [search.xml](../../android/build/reports/vision-acceptance-20260717/refinement-final/search.xml) prove the latest query field and `Supermarket · coordinates` copy without a redundant singular generic type. Regression tests also prevent raw boolean OSM values such as `shop=yes` becoming the label **Yes**.
  2. select result → orange marker and normal base-map label exist without a custom duplicate label; [refinement-final/selected.png](../../android/build/reports/vision-acceptance-20260717/refinement-final/selected.png) and [selected.xml](../../android/build/reports/vision-acceptance-20260717/refinement-final/selected.xml).
  3. selected banner → **IGA · Supermarket** is visible and focusable in the final selected-result receipt; the earlier [33-selected-result-banner.xml](../../android/build/reports/vision-acceptance-20260717/33-selected-result-banner.xml) also proves named details semantics.
  4. open details → friendly **Point of interest / Supermarket**, **Save pin**, **Directions** exist; [34-selected-detail-sheet.png](../../android/build/reports/vision-acceptance-20260717/34-selected-detail-sheet.png) and its XML.
  5. zero results → explicit no-results row exists in code; not device-run.
  6. query failure → the same query remains and **Retry** increments a retry generation, re-entering Searching and reissuing it despite `distinctUntilChanged`; the ViewModel test receipt covers fail → unchanged retry → recovered result.
  7. index unavailable → the query remains with **Manage map pack**; readiness recovery also reruns it automatically. The branch exists in code/tests but has not been failure-injected on the emulator.
- **Dead-ends found:** none in the implemented Search state machine. Cross-screen map-pack recovery and exact-query retry are explicit; only device proof of those injected branches remains.
- **Keyboard:** receipt 70 proves visible field focus plus focusable Clear/results; receipt 71 proves the selected-result banner. Retry/Manage and detail-sheet actions are native Compose controls in visual source order. Marker tap has the same banner/details action path.
- **Fixes:** failure-inject both recovery branches on device; exhaustive TalkBack narration remains a useful follow-up, not a keyboard dead-end.

## Flow: Find a nearby place

- **Walkthrough:**
  1. open Nearby → [refinement-final/nearby.png](../../android/build/reports/vision-acceptance-20260717/refinement-final/nearby.png) and [nearby.xml](../../android/build/reports/vision-acceptance-20260717/refinement-final/nearby.xml) prove category chips, one non-duplicated **Using your device location** status, coordinates, Share/Refresh, and emergency actions are present and focusable.
  2. choose Supermarket → [refinement-final/stores.png](../../android/build/reports/vision-acceptance-20260717/refinement-final/stores.png) and [stores.xml](../../android/build/reports/vision-acceptance-20260717/refinement-final/stores.xml) prove the selected chip auto-reveals completely and the device-centred, nearest-first results begin at 355 m with named map actions.
  3. a device-centred category with zero hits → **Try Adelaide CBD** is a live action: it clears the applied device fix, resets the reference to Adelaide CBD, and reruns the retained category; **Use my location** clears fallback mode and seeks a fresh device fix. This reversible source path is not yet device-run.
  4. tap **View OTR Pulteney Street on map** → Map centers and marks the result; [27-nearby-on-map.png](../../android/build/reports/vision-acceptance-20260717/27-nearby-on-map.png).
  5. selected-result banner/details branch is device-proven by receipts 33–34.
  6. category exception → selected category is retained with **Couldn’t load nearby places. Try again.** and **Retry**; unit tests cover failure and recovery.
  7. emergency lookup → labelled spinner, independent hospital/police outcomes, true-empty copy, partial-result retention, and failure **Retry** now exist; unit tests cover error → recovery.
  8. repository still preparing → category state retains the selection and offers **Manage map pack**; Emergency says **Emergency details will appear when the map data is ready.** with the same direct action instead of displaying true-empty copy.
- **Dead-ends found:** none in the implemented category/emergency state machine. Repository preparation, query failure, partial results, and true empty now lead to distinct, actionable states; their final device rendering remains unproven.
- **Keyboard:** receipts 72–73 prove category, location, share/refresh, emergency, and named store actions are focusable in the rendered hierarchy; their Compose declaration order follows the visual top-to-bottom flow.
- **Fixes:** device-run category/emergency preparing, loading, error, partial, empty, **Try Adelaide CBD** / **Use my location** reversal, Retry, and Manage states; run a TalkBack narration sweep.

## Flow: Show my location

- **Walkthrough:**
  1. tap location without permission → Android permission flow exists; [06-location-permission.png](../../android/build/reports/vision-acceptance-20260717/06-location-permission.png).
  2. grant approximate → earlier build showed a blue puck/located FAB; [07-location-coarse-fix.png](../../android/build/reports/vision-acceptance-20260717/07-location-coarse-fix.png).
  3. age the approximate fix → testing exposed that Android privacy-aged timestamps could clear the puck and make recenter reject an active callback.
  4. current working tree adds an approximate-location freshness window and live-callback display-time policy in both build flavours; [31-coarse-puck-policy.png](../../android/build/reports/vision-acceptance-20260717/31-coarse-puck-policy.png) proves the blue puck and located FAB on the patched coarse-only run.
  5. the location halo now converts reported metres to pixels at camera zoom, clamps to 18–120 px, and updates on camera idle; [44-zoom-aware-coarse-puck.png](../../android/build/reports/vision-acceptance-20260717/44-zoom-aware-coarse-puck.png) visibly proves the broad approximate uncertainty halo instead of false pinpoint precision.
  6. Nearby uses the same 15-minute approximate freshness policy and ignores the post-refresh minimum timestamp for coarse callbacks; receipts [72-latest-nearby.png](../../android/build/reports/vision-acceptance-20260717/72-latest-nearby.png) / [72-latest-nearby.xml](../../android/build/reports/vision-acceptance-20260717/72-latest-nearby.xml) prove the settled screen says **Using your device location**, exposes device coordinates, offers **Refresh my location**, and recalculates emergency distances around that reference.
  7. the latest Map receipt [refinement-final/map-located.png](../../android/build/reports/vision-acceptance-20260717/refinement-final/map-located.png) visibly proves the blue puck/accuracy halo and located FAB on the road canvas; [map-located.xml](../../android/build/reports/vision-acceptance-20260717/refinement-final/map-located.xml) keeps My location focusable.
  8. while waiting, Map replaces the location FAB icon with a persistent scoped spinner and Nearby shows an inline spinner beside its waiting status; Map/Nearby denial now both acknowledge the fallback, and outside coverage offers snackbar **Manage map pack** while keeping the camera. These are code-covered, not emulator failure-injected.
- **Dead-ends found:** the missing-puck, one-time denial, wait-feedback, and outside-coverage recovery gaps are closed in code.
- **Keyboard:** My location/Use my location/Open location settings are structurally focusable; OS permission dialog is platform-owned.
- **Fixes:** device-run the two persistent wait spinners, both denial acknowledgments, and outside-coverage **Manage map pack**; convert the manually proven coarse Nearby refresh into repeatable instrumentation.

## Flow: Drop and save a pin from the map

- **Walkthrough:**
  1. touch hold → haptic-backed **Drop a pin here** opens with a reverse-geocoded draft; [refinement-final/hold.png](../../android/build/reports/vision-acceptance-20260717/refinement-final/hold.png) and [hold.xml](../../android/build/reports/vision-acceptance-20260717/refinement-final/hold.xml) are the latest touch receipts.
  2. physical keyboard Enter → [77-keyboard-drop-pin.png](../../android/build/reports/vision-acceptance-20260717/77-keyboard-drop-pin.png) / [77-keyboard-drop-pin.xml](../../android/build/reports/vision-acceptance-20260717/77-keyboard-drop-pin.xml) prove the same sheet opens from the first-Tab-focused map, the Adelaide Name field receives focus, and six named RadioButtons plus Directions/Save pin are available.
  3. **Save pin** → spinner/error state exists; success closes, persists, renders the selected marker, and offers Undo. [refinement-final/pin-saved-map.png](../../android/build/reports/vision-acceptance-20260717/refinement-final/pin-saved-map.png) proves **Saved “Erindale”**, **Undo**, and the new green marker.
  4. Pins tab → the same saved object and coordinates exist in [refinement-final/pins.png](../../android/build/reports/vision-acceptance-20260717/refinement-final/pins.png) / [pins.xml](../../android/build/reports/vision-acceptance-20260717/refinement-final/pins.xml).
  5. save failure → inline message retains draft and re-enables **Save pin** in code.
  6. edit the name, then dismiss → [80-fixed-discard-guard.png](../../android/build/reports/vision-acceptance-20260717/80-fixed-discard-guard.png) / [80-fixed-discard-guard.xml](../../android/build/reports/vision-acceptance-20260717/80-fixed-discard-guard.xml) prove a genuinely dirty draft opens **Discard unsaved pin changes?** with safe-default **Keep editing** focused.
  7. choose **Keep editing** → [83-keep-editing-resume.png](../../android/build/reports/vision-acceptance-20260717/83-keep-editing-resume.png) / [83-keep-editing-resume.xml](../../android/build/reports/vision-acceptance-20260717/83-keep-editing-resume.xml) prove the bottom sheet is explicitly re-shown with `_resumeAdelaide` intact and focused, plus Directions/Save pin restored. The final class fix reads `isSaving`/dirty state through current-state holders, guards only `SheetValue.Hidden`, and calls `sheetState.show()` after Keep editing, closing both stale-capture and hidden-sheet failure modes.
- **Dead-ends found:** none in code; changed drafts no longer disappear silently.
- **Keyboard:** map key delivery, Name focus, safe confirmation focus, and the focusable colour/action set are device-evidenced. Visual order matches the sheet's declaration order.
- **Fixes:** device-run explicit **Discard** and write-failure branches; the primary keyboard creation/Keep editing path is proven.

## Flow: Add a pin by coordinates

- **Walkthrough:**
  1. Pins initial read → **Loading saved pins…** is distinct from true empty; a read exception says the user can still add and offers **Retry saved pins**, which cancels and resubscribes.
  2. Pins > Add pin → form state exists with editable defaults; the FAB remains available during read recovery.
  3. leaving blank name or invalid/non-finite/out-of-bounds coordinate fields → exact inline requirement appears under that field on blur, every value remains, and **Add pin** stays disabled; pure coordinate validation tests cover the bounds.
  4. valid **Add pin** → pessimistic spinner, locked dismissal, Room insert, then list state exist in code.
  5. write failure → message/full-draft retention exist; changed Cancel/outside dismiss asks **Discard** or **Keep editing**. Edit-sheet swipe guarding only vetoes `SheetValue.Hidden`; the AlertDialog Add flow uses the same explicit draft confirmation.
- **Dead-ends found:** none in code; no emulator journey receipt yet.
- **Keyboard:** the declaration order Name → Latitude → Longitude → colours → actions matches visual top-to-bottom/left-to-right order; all fields, radio choices, Add pin, Cancel, and discard confirmation are native focusable controls.
- **Fixes:** run one invalid-blur → correction → changed-dismiss → keep-editing → Add-pin emulator journey; capture XML proving labelled/selected colour choices.

## Flow: Edit or remove a saved pin

- **Walkthrough:**
  1. pin card → Edit pin sheet exists; leaving a blank name shows **Enter a name** inline and preserves the draft.
  2. **Save pin changes** → only that action shows a spinner; editor fields, Delete, and dismissal lock until the repository reports one updated row. Success then closes; failure leaves draft and named error in place.
  3. list/editor/map Delete → each path first asks **Delete “{name}”?** and explains that the saved pin is removed from this device.
  4. confirmed list deletion shows progress only on that card and a named **Try again** card on failure; editor/map deletion retains its owning sheet, names the error, and keeps Delete as the retry path.
  5. changed sheet dismissal asks **Discard unsaved pin changes?**; **Keep editing** restores the intact draft and **Discard** keeps the original persisted pin. `PinListViewModelTest` covers success-after-write, missing-row failure, repository failure with unchanged data, retry, blank-name rejection, and cancellation semantics.
- **Device proof:** [58-final-pin-detail.png](../../android/build/reports/vision-acceptance-20260717/58-final-pin-detail.png) and [58-final-pin-detail.xml](../../android/build/reports/vision-acceptance-20260717/58-final-pin-detail.xml) prove the named marker detail; [59-final-pin-delete-confirm.png](../../android/build/reports/vision-acceptance-20260717/59-final-pin-delete-confirm.png) and [59-final-pin-delete-confirm.xml](../../android/build/reports/vision-acceptance-20260717/59-final-pin-delete-confirm.xml) prove **Delete “Burnside”?**, consequence copy, **Cancel**, and **Delete** on the final rebuild.
- **Dead-ends found:** none in pin update/delete or changed-draft paths. Completion is callback-truthful and every failure/guard retains the object/context; recent-route deletion follows the same named-confirmation and retained-retry contract.
- **Keyboard:** card semantics now expose **Edit pin {name}**, the icon exposes **Delete pin {name}**, and confirmation/fields/colours/actions are native focusable controls in visual order.
- **Fixes:** device-run blur validation, Edit-sheet keep/discard, update success/failure, and all three delete success/failure paths. An Undo after confirmed deletion is optional, not required to escape failure.

## Flow: Get directions and start navigation

- **Walkthrough:**
  1. feature/pin Directions → origin policy chooses live location or a distinct camera point; missing origin shows the specific snackbar. [05-directions-no-location.png](../../android/build/reports/vision-acceptance-20260717/05-directions-no-location.png) covers one failure branch.
  2. routing in progress → spinner/pill state exists.
  3. successful result → the new Android 14 AVD journey composes the real Map screen with a deterministic route, asserts sheet/Start controls plus the native `route-line`, starts navigation, retains that exact route and line across host stop/resume, then verifies exact history persistence and complete native/UI teardown on Stop. It complements rather than replaces a real bundled-data route-generation journey.
  4. engine unavailable → message carries **Manage map pack**; no-route/IO/generic failures carry **Retry**, which replays the retained request.
  5. clear, new destination, option change, and Retry each cancel the previous job and increment ownership generation; late native completions cannot publish stale Active/Error state. Current `RouteViewModelTest` source covers clear-during-flight and older-calls-finishing-last.
  6. Start navigation snapshots the exact `RouteResult`, closes only the planning sheet, and keeps that snapshot as the visible line until Stop. Stop queues that same route for history before clearing the session; a write failure retains the exact pending history entry behind an indefinite **Retry** snackbar while Map remains usable.
  7. initial/active location loss → both app flavours close their location stream after 15 s without an initial fix or 30 s without an active update; navigation preserves the last Navigating state and renders **Current location is unavailable. Turn on Location, then retry.** with **Retry location** / **End navigation**. Host STOP/disposal pauses GPS without discarding the session or progress, START resumes exactly one subscription, explicit Stop while backgrounded remains final, and generation ownership rejects stale callbacks/failures. Retry restores progress and restarts tracking; revoked permission transitions to precise-location-required.
  8. failed off-route recomputation → dedicated `RecalculationFailed` state visibly renders **Couldn’t update route. Previous guidance is still shown — check the road ahead.** with **Retry route** / **End navigation**, while the previous maneuver/distance/time remain beneath it even when TTS is off. Retry uses the latest position and retained destination; generation ownership prevents a late non-cooperative result from replacing newer, permission-rejected, or stopped navigation. Current reports are green 10/10 ViewModel state/ownership plus 6/6 geometry.
  9. approximate-only navigation → precise explanation and settings/request actions exist.
- **Dead-ends found:** none in route computation/history save, active location loss, or recalculation failure: each retains the route/guidance or exact pending-history context and exposes Retry/Manage/**End navigation**. Successful end-to-end routing and precise-permission upgrade remain unproven on this bundled dataset.
- **Keyboard:** exposed sheet switches/buttons follow their visual declaration order and are structurally focusable. The optional waypoint component validates blank engaged input inline on blur but is not wired into Map, so it is not part of this flow. Route geometry is visual while maneuver text supplies the readable equivalent; a full active-overlay TalkBack run remains a proof follow-up.
- **Fixes:** device-run successful bundled-data route generation + option recompute + precise permission + failed recalculation **Retry route**/**End navigation** journeys. Exact route ownership, host lifecycle, progress, history persistence, Stop, and native line cleanup now have repeatable instrumentation.

## Flow: Reuse or remove a recent route

- **Walkthrough:**
  1. History → distinct **Loading recent routes…**, explicit true-empty/populated, and read-error states exist.
  2. select → origin/destination are restored and recomputed.
  3. delete action → semantics name **Delete route to {destination}**; confirmation title repeats the destination and consequence before any mutation.
  4. confirm → only the affected row shows a spinner, but the lock is global: sheet hiding plus selection/Delete on every row are disabled until the success/failure callback resolves, preventing a concurrent selection or second delete.
  5. missing-row/storage failure → route remains in the observed list with **Couldn’t delete the route to “{destination}”. Please try again.** and **Retry**; cancellation is rethrown rather than reported as failure.
  6. read exception → **Recent routes couldn’t be loaded. Try again.** replaces false empty; **Retry** increments the load generation, cancels, and resubscribes.
  7. the prior ViewModel report on disk is green 7/7, including read failure versus true empty and recovery; source also covers retained route-save Retry. The global sheet/row delete lock is source-evidenced and still needs a rendered interaction test.
- **Dead-ends found:** none in history load/save/delete: each failure keeps the Map/route usable and offers Retry or dismissal.
- **Keyboard:** history rows expose **Select route to {destination}**; delete icons, confirmations, load/delete Retry, and sheet dismissal are focusable in visual order.
- **Fixes:** device-run empty/populated/select plus injected read/save/delete failure branches.

## Flow: Recover or update a map pack

- **Walkthrough:**
  1. missing/load failure → Settings recovery action exists.
  2. standard build → check/download/verify/extract/install phases and Cancel exist; privacy build replaces the dead button with an honest unavailable message.
  3. known manifest/checksum/network failures have friendly messages; previous pack remains active during replacement.
  4. generic worker diagnostics remain in logs/state; the UI shows only **The map pack couldn’t be installed. Your current map is still available — try again.**
- **Dead-ends found:** no copy leak/dead end in the generic worker branch. No emulator/device receipt in this acceptance set proves cancel/repair/activation.
- **Keyboard:** all settings actions are structurally focusable.
- **Fixes:** test generic failure/retry and cancel preserving the current pack; prove repair activates Map/search/routing together; capture phase and success receipts.

## Flow: Configure app preferences

- **Walkthrough:**
  1. Settings → theme radio group, full-row Traffic/TTS/Attribution switches, masked pasteable API fields, map-pack and About sections exist. [14-settings.png](../../android/build/reports/vision-acceptance-20260717/14-settings.png) proves visual layout.
  2. [36-settings.png](../../android/build/reports/vision-acceptance-20260717/36-settings.png) and [36-settings-semantics.xml](../../android/build/reports/vision-acceptance-20260717/36-settings-semantics.xml) prove labelled radio/switch semantics; dark portrait, dark landscape, and restored-light receipts 37–40 prove responsive theme/orientation behaviour.
  3. selection/toggle/key edits auto-save through DataStore and immediately update UI.
  4. persistence failure → a shared Map/Settings snackbar says **Couldn’t save “{setting}”. Try again.**, retains the exact typed/toggled mutation, and offers **Retry** / **Dismiss**; retry shows **Retrying…**, success clears the error, and dismissal invalidates stale ownership. Route preferences use this same NavHost-owned stream while visible on Map.
- **Dead-ends found:** none in setting persistence; the previous persisted value remains authoritative while the exact failed request has an explicit retry/clean-dismiss decision.
- **Keyboard:** radio rows, full switch rows, text fields, snackbar Retry/Dismiss, map-pack actions, and About are structurally focusable in visual/declaration order.
- **Fixes:** add an automated relaunch persistence test and device-run injected failure → Retry/Dismiss on both Map and Settings.

## Flow: Share a location

- **Walkthrough:**
  1. Nearby share action → deterministic plain text includes optional name, six-decimal coordinates, and OSM URL; unit-level formatter is inspectable.
  2. Android chooser launches best-effort.
  3. launch exception/no handler → the app copies the exact prepared label/coordinates/OSM URL and shows **No sharing app was available. The location was copied instead.**
- **Dead-ends found:** none in code; chooser failure preserves the same complete text through the clipboard. The external handoff/fallback has no device receipt.
- **Keyboard:** Share location and named emergency share icons are structurally focusable; Android chooser is platform-owned.
- **Fixes:** device-run current-location and named-emergency sharing plus forced chooser-failure clipboard equivalence/snackbar.

## Gate-closing queue

1. **Rubric blocker:** implement `edges:unexpected-screen-failure` so a failed primary destination retains a stable shell with **Retry screen** / **Open map**.
2. **Proof gap:** device-run Search/Nearby preparing/error recovery, share fallback, safe generic worker failure, Map denial/outside coverage, location-wait spinners, Add/Edit pin validation/discard, route recovery/recalculation/navigation, generic/credential traffic failure, route-history global delete lock, settings-write Retry/Dismiss, and map-pack repair/cancel/activation. The prior recovery/copy-polish rows are now source-backed.
3. **Assistive-tech proof:** run a complete TalkBack narration/focus sweep on hardware. The Android 14 AVD bound TalkBack and visibly focused Search and the map canvas in `refinement-final/talkback-01.png` / `talkback-current.png`, but its TTS did not initialize and synthetic swipes leaked into native map panning, so this is intentionally only partial focus evidence. Physical keyboard map focus/pan/zoom/Enter and the critical draft-safe focus/resume path remain proven by receipts 75–77, 80, and 83.

Replace **OPEN** with **PASS** when the unexpected-destination boundary is live and re-walked; the other rows are high-value polish/proof work but no longer strand the defined flows.

## Rubric self-score

**15/16 pass** using the rubric's stated denominator (the supplemental 4a and 4b checks also pass). Passing: 1, 2, 3, 4, 5, 6, **7**, **9**, 10, 11, 12, **13**, **14**, **15**, **16**. Failing: **8**.

- **7 passes:** generic worker diagnostics are no longer interpolated; traffic auth failures propagate only sanitized region codes (never keys, headers, URLs, bodies, or raw status copy), and all inspected error paths render stable user copy while technical detail stays in logs/state.
- **13 passes:** every actual >1 s wait now has a spinner/progress mechanism, including persistent Map/Nearby location waits; the >10 s map-pack operation has named progress and Cancel. Navigation enters its active state synchronously rather than exposing an unrepresented initialization wait.
- **14 passes:** exposed Add/Edit pin fields validate required/range rules inline on blur and preserve all values; repository failures retain the draft. The optional waypoint component follows the same rule but is not wired into the current Map flow.
- **15 passes:** receipts 75–77 prove first-Tab native-map focus, the visible 3 dp focus border, arrow/plus-minus delivery, and Enter pin creation; receipts 77, 80, 83, and 70–73 prove critical sheet/search/Nearby focusable actions, safe initial confirmation focus, and intact draft resume after **Keep editing**. Remaining controls are native Compose/platform controls declared in visual top-to-bottom/left-to-right order. No required flow step is mouse-only: direct map-feature tapping is an optional shortcut with the keyboard-reachable Search/Nearby banner/details path.
- **16 passes:** changed Drop/Add/Edit pin dismissal is guarded by named **Discard** / **Keep editing** choices; unchanged drafts still exit cleanly.
- **8 fails:** every defined operation now has Retry/Manage/**End navigation**/dismiss recovery, but a primary destination that fails during composition/navigation still has no live `edges:unexpected-screen-failure` boundary. Until the shell can retain navigation and offer **Retry screen** / **Open map**, that exceptional path is a dead end.
