# Deep think: primary-destination recovery boundary
Date: 2026-07-17 · Tier: COUNCIL · Domain: coding/Android Compose · Status: DONE
Overlays: none matched
Parent: aus-roads agentic refinement and development

## §1 FRAME  (T-G1)
Operative ask (VERBATIM): "ok continue agentic refinemend and devlopment"
Mode: DIRECTIVE · Clauses: 1 atomic deliverable — continue improving the app without overstating completion
Referents: "app" → `/home/benbi/Apps/aus-roads` [live-read]; remaining recovery gap → `design/ux/edges.md` key `unexpected-screen-failure` [live-read]
Goal: Decide whether the remaining whole-destination recovery criterion can be implemented truthfully on the pinned Compose stack.
Done means:
- [x] Distinguish operational loading failures from arbitrary destination composition failures.
- [x] Kill-test any claimed whole-destination boundary before production use.
- [x] Preserve the stable shell and current production behavior if the mechanism is not proven.
- [x] Record the dead branch and redirect work to provable refinements.
Hard constraints:
- No in-tree `try/catch` may be presented as a Compose error boundary.
- A passing design must contain both first composition and later recomposition exceptions.
- The production tree must not become the experiment ground.
- The UX score remains 15/16 unless the exact criterion is live and kill-tested.
Unknowns:
- Can a separate child `Recomposer` contain both exception timings? → isolated Android Lab run

Assumptions:
- [V] Compose Runtime 1.7.5 production recomposition errors rethrow ← installed bytecode and official AndroidX source inspection
- [V] The app has one shell/window composition around one `NavHost` ← `MainActivity.kt`, `AusRoadsApp.kt`, `AusRoadsNavHost.kt`
- [A] A separately owned child recomposer can own first composition as well as later recomposition ← verify by injected device tests

## §2 BRANCHES  (T-G2)
A) Retain honest operational recovery only — works because feature state machines already own asynchronous failures and the shell stays stable · dead if: a real operational failure still strands a defined flow · cost/risk: criterion remains 15/16 and arbitrary composition crashes remain process-level
B) Child composition with a distinct recomposer — works because later recomposition can fail on a separate runner while the shell stays in the window recomposer · dead if: P3 reproduces an injected first-composition exception reaching the parent rule instead of rendering recovery · cost/risk: experimental API, nested Android view/composition lifecycle, focus and ownership complexity
Strawman check: B could have closed the exact criterion without a second process while keeping one `NavController`; its complexity was acceptable only if both kill tests passed.
CHOSEN: B for falsification because the initial-composition kill test is the deciding criterion | P3/P4 decide.
Loser's valid regime: a distinct child recomposer may isolate some later recomposition exceptions, but that narrow regime does not satisfy the app-shell criterion.

## §2.5 COUNCIL  (COUNCIL tier only)
Runtime correctness: production Compose rethrows; only an actually separate error owner is credible · lean B only with both kill tests · high confidence · the other lenses miss attach/apply timing.
UX honesty: recovery copy that cannot catch first-open failure is worse than an explicit 15/16 residual · lean A unless proven · high confidence · the other lenses miss user expectation created by the buttons.
Lifecycle/ownership: one child `NavHost` preserves entry locals better than per-destination roots, but nested view attachment is risky · lean probe B · medium confidence · the other lenses miss lifecycle-owner timing.
Maintainability: B adds experimental runtime and disposal machinery for a rare path · lean A · high confidence · the other lenses miss long-term upgrade burden.
Verification: injected initial and later exceptions are the smallest decisive sensors · lean Lab before core · high confidence · the other lenses miss false-green unit abstractions.

## §3 PROBE PLAN  (T-G3)
- [x] P1 inspect pinned runtime APIs/bytecode → predict: normal in-tree handling is unsupported; a distinct recomposer API exists
- [x] P2 compile a minimal pinned Android probe → predict: app and instrumentation APKs compile
- [x] P3 inject initial and later failures into mechanism 1 → predict: both publish parent recovery within two seconds and Retry remounts healthy content
- [x] P4 move child insertion to the guarded seam and repeat → predict: Android performs first composition inside the guarded insertion; both tests pass
- [x] P5 inspect the aus-roads production diff → predict: no error-boundary code was promoted
- [x] P6 close the Lab ledger and durable memory candidate → predict: both return closed receipts

## §4 EVIDENCE LEDGER  (T-G3)
| # | check | predicted | result | class | means |
|---|-------|-----------|--------|-------|-------|
| P1 | Compose 1.7.5 installed API/bytecode + official source | shared production errors rethrow; distinct recomposer is available | confirmed | [V] | a Lab probe is required; normal wrappers are dead |
| P2 | Lab app + test APK build | compile green | green after one test-import compatibility correction | [V] | runtime mechanism can be tested on-device |
| P3 | two instrumentation kill tests, mechanism 1 | both contained | recomposition passed; first composition escaped | [V] SURPRISE | `createComposition()` returned before lifecycle-driven first composition |
| P3a | update assumption/branch after surprise | initial ownership seam was too early | recorded before repair in Lab ledger | [V] | one bounded mechanism repair remained |
| P4 | two instrumentation kill tests, guarded child insertion | both contained | recomposition passed again; first composition escaped again through outer apply/attach | [V] SURPRISE | B is dead for the required criterion |
| P4a | sweep dependent artifacts | no production boundary should remain | no boundary source was added; UX ledger still says Required/15-of-16 | [V] | no placebo shipped or scored |
| P5 | `git diff` production review | only prior work plus separate visual/test refinements | confirmed | [V] | the experiment stayed isolated |
| P6 | Lab 037 + memory checkpoint | closed experiment and durable dead-branch receipt | Lab closed; memory candidate closed as `3c6d7f7b-1662-4fcc-b20d-97719bb5bc94` | [V] | future sessions receive the do-not-retry result |

## §5 STATE  (T-G4)
Established: a child recomposer isolates later recomposition in this probe but not first composition mounted through an outer Compose `AndroidView` transaction [V].
DEAD (do not retry): in-tree composable `try/catch`, normal sibling `ComposeView`, and `AndroidView`-mounted child-recomposer boundary on Compose 1.7.5 — initial failure escaped 2× (P3, P4).
Attempt counters: child-recomposer mechanisms: 2
Now: retain the honest 15/16 boundary residual · Next: improve real route/lifecycle proof and visual details.

## §6 VERDICT  (T-G5)
Re-read §1: does the answer below address every done-means bullet? yes
Operative-ask audit: every operative word of the VERBATIM ask maps to a mechanism in the answer? yes
Answer: Do not implement the proposed whole-destination boundary. It reliably recovered from later recomposition but failed the required first-composition case twice, so it would create recovery controls that cannot cover the failure they promise. Keep the existing feature-owned operational recoveries, retain the documented 15/16 score, and spend this refinement pass on real integration and visual proof.
Claims & evidence:
- The proposed boundary is insufficient for the exact criterion ← P3, P4
- No production placebo was shipped ← P4a, P5
- The dead branch is durably recorded ← P6
Confidence: H because two device falsifiers reproduced the decisive failure while the control recomposition case passed.
Wrong if: a materially different mount seam contains an injected first-composition exception without taking down the shell on the same pinned production architecture.
Residuals: whole-destination recovery remains unimplemented; cause hypothesis is unsupported initial-composition ownership on this Compose architecture.
Residual risk / not verified: Activity/Fragment/process isolation and future supported Compose recovery APIs were not prototyped.
Not consulted (deliberate): separate-process UI — disproportionate to this offline map app and not a small refinement.
MEMORY_HANDOFF: title=Lab 037: child Compose recomposer does not contain initial destination composition · kind=research · content=dead branch plus root cause and revisit conditions · evidence=P3/P4 and Lab XML · assumptions=Compose 1.7.5 + both timings required · revisit_if=supported runtime boundary or materially different initial-mount seam · MEMORY_RECEIPT `3c6d7f7b-1662-4fcc-b20d-97719bb5bc94`
Surface to user: I did not ship the proposed recovery screen because its first-open crash test failed twice. The app keeps its proven feature-level recovery, and this pass moves on to device-tested navigation and visible polish instead.
