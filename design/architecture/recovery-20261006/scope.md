# Aus Roads recovery refinement — scope
Tier L: cross-module preservation and runtime/UI seams; more than six files. This shelf is an implementation contract inside mission g_e105c2a6, not a completed app or a request for owner approval.

## Problem
The recovered exploration app has useful offline keyword discovery but confirmed failed-intent ordering, unusable Essentials recovery, incomplete accessible/loading/privacy states, weak catalog validation/publication, destructive database fallback, and a pre-existing silent GPS registration/cold-start failure. The native acceptance oracle also conflates label absence with absent map geometry and observes deletion before native application. A combined native test encountered a real vendor GLES crash; its cause remains open.

## In Scope
The precise six writer lanes and verification/deployment steps in plan.md and map.md. Preserve and qualify the full adopted 77-path exploration cluster as well as these repairs. Correct defects, add failure witnesses and positive/negative tests, then run complete source-bound build/native/visual/newcomer and independent review gates. Upgrade the same signed package on the Pixel wirelessly without erasing data.

## Out of Scope
New product features, a replacement app, navigation/tab redesign, new dependencies, inferred visits, accounts/telemetry/cloud recommendations, speculative MapLibre/renderer/URI changes, increased render deadlines, keyguard bypass, uninstall/clear-data/grant/pairing/credential changes, external publication.

## Success Criteria
SC1: A failed history clear cannot delete a subsequently successful visit on Retry; existing independent preference/Undo semantics pass, and visit 501 produces useful non-retryable capacity guidance without evicting history.
SC2: Actual Essentials Retry reopens/revalidates the catalog and restores the selected category/emergency content, not merely a manually opened test repository.
SC3: Controls have names/checked semantics; unread history is not shown as zero; saving/loading/error states and flavor-specific privacy statements are truthful on actual native screens.
SC4: Invalid adopted catalog rows are rejected before READY; producer exceptions preserve the prior consistent published pair; healthy real assets still load. Initial/replacement pack events cannot be lost.
SC5: Same-view map replacement/retry/deletion preserves camera, listeners, whole prior rows and first-hit/physical negative controls. Stale deleted native pin hits do not open an unrelated pin editor. Traffic severity expressions are structurally valid and native tested. Encoder crash is investigated, not waived.
SC6: Unsupported Room versions fail closed without deleting pins/routes; supported legacy pin migrations and fresh/current databases preserve actual rows.
SC7: Real fused-adapter Task rejection/cancellation reaches bounded retries; cold no-fix becomes visible; Stop cleans callbacks and prevents late resubscription. Offline permissions remain absent.
SC8: Passing final compiler/JVM/catalog/detekt/lint/native gates, visual MET at 8/floor6, settled newcomer evidence, whole-cluster spec and adversarial review, safe owned commit and signed arm64-only artifacts.
SC9: Literal package-manager Success and installed package/signature/artifact readback on the authenticated USB-free path. Ordinary unlock and final owner acceptance remain explicit gates if unavailable; all independent work continues.

## Constraints
Original owner outcome: review/refine the map into keyword suitable-place exploration with explicit local visits/preferences and set it up on the phone without USB. Preserve old package/signing/data, stable coordinate-derived camera IDs, four-tab/nav structure, emergency functions, no-INTERNET/no-LOCATION offline flavor, and privacy-safe fixed-cardinality diagnostic metadata. No shell/dispatch/commit by children. Builders read entire source/callers before editing; parent executes proof and decides findings.

## Non-goals
Do not persist private search text as a new durable feature or reinterpret manual visits as background tracking. Do not claim statewide bundled map tiles or live venue facts. Emulator-only fixtures never run on the Pixel. Plans, proxies, isolated positives and advisory reviews do not settle whole-app acceptance.
