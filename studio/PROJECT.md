# PROJECT ledger — aus-roads
Created: 2026-07-17 · every family hop READS this first and APPENDS before handing off

## Profile
Goal (owner's words):
"comprehensively polish it and refine it"; continue agentic refinement and development.
Constraints:
- Android offline-first map app; preserve user data and the existing dirty working tree.
- Do not claim behavior without source/test/device receipts.
- Do not commit unless the owner explicitly asks in the current turn.

## Assumed (every line tagged [A]; owner corrects once)
- [A] The current priority is reliability and interaction polish over adding a new product area.

## Decisions (X-vs-Y forks resolved; cite evidence)
- Keep the app-shell unexpected-destination criterion at 15/16: Lab 037 falsified the child-recomposer mechanism for initial composition twice; see `.deepthink/destination-recovery-boundary.md` and memory `3c6d7f7b-1662-4fcc-b20d-97719bb5bc94`.
- Next investment: real bundled-Valhalla route generation on a physical arm64 device, plus the deferred primary-destination failure boundary; deterministic route ownership and the scoped visual review are now proven.
- Visual pass: auto-reveal the selected Nearby category and remove redundant generic type copy from grouped search result subtitles.

## Shelf map (family → artifact paths)
| family | artifact | path |
|---|---|---|
| UX | whole-flow/state/error ledger | `design/ux/` |
| frontend | emulator screenshots + hierarchy receipts | `android/build/reports/vision-acceptance-20260717/` |
| reasoning | destination recovery decision | `.deepthink/destination-recovery-boundary.md` |
| code | Android app and tests | `android/` |

## Gate ledger (a family with failing gates blocks the next family)
| family | gate | pass | evidence |
|---|---|---|---|
| UX | 16-criterion walkthrough | 15/16 | `design/ux/flow-check.md` |
| reasoning | boundary mechanism falsified before production | yes | Lab 037: initial failed twice; recomposition passed twice |
| frontend | core map/search/Nearby/pin journeys visually inspected | yes | latest `refinement-final/` PNG + XML receipts |
| code | focused search presentation + both app-flavour compile | yes | focused SearchPresentation/SearchBar tests + offline/network compile |
| code | exact route ownership/lifecycle integration | yes | six Android 14 AVD cold-start + route invocations, 12/12 total; one route-test MapView per run |
| code | final non-device/release gate | yes | `test`, both debug lints, both release APK assemblies |

## Latest refinement outcome
- MapLibre style installation is explicitly returned to the main thread after offline file work. The route test is hosted by a blank `ComponentActivity`, avoiding the two-MapView race caused by replacing production `MainActivity` content; five preserved cold runs plus one independent reviewer run are green.
- The route overlay remains owned through navigation and host recreation, persists the exact completed route, and removes its native source/layer on Stop.
- Fresh device receipts prove user-location puck/halo, road scaling, Search → selection, touch hold → save → Pins persistence, and device-centred Nearby supermarkets.
- The selected Nearby category now auto-reveals without clipping; generic OSM boolean classes fall back to a meaningful kind rather than rendering **Yes**.
