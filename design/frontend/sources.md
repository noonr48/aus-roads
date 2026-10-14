# Native source map

| Spine | Actual source | Why off shelf |
|---|---|---|
| Direction/structure | brief.md; real ExploreScreen.kt Scaffold, DiscoveryBody and InterestsSheet | Existing Android app, not a web mock. |
| Tokens | android/ui/designsystem/src/main/kotlin/au/com/ausroads/ui/designsystem/Theme.kt; native MaterialTheme typography and component defaults | Canonical theme remains untouched. No CSS or new palette/type literals. Existing screen spacing and 28dp progress diameter are reused. C47 reuses the already-present8dp inter-item value; no new numeric, palette or type token. |
| Build | android/app/src/main/java/au/com/ausroads/ui/explore/ExploreScreen.kt; main values/explore.xml | Actual shipping Compose surface, same navigation/data owners. |
| Wiring | controls.md; existing ExploreViewModel/ExplorationStore | Dismissal changes modal state only; queued native writes belong to destination VM. |
| UX states/errors | ../ux/states.md and ../ux/edges.md | Tier S change to existing surfaces; no new flow. |
| Static check | actual Kotlin compiler, detekt/lint; diff whitespace gate | CSS design-lint has no native Kotlin selector and is not claimed as native validation. |
| Perceptual check | actual native test cache PNGs → immutable visual_loop captures | Only deployed guest pixels, never recreated HTML. Subsequent rounds must follow latest source change. |

Known baseline: c41 real-IME action was below the keyboard (1619 > 1517 px); c39 native saving/read-error images show text-only feedback, no explicit Close and duplicated clipped error behind the sheet. Standard modal dimming is retained, not treated as a defect. C46 both original native gates passed81+80; actual four-screen normal-native board reached7.5, not the8/floor6 visual bar. C47 density baseline uses native XML viewport430–2127 and first-card top1304 (51.5% preceding content); only the promotional Text and12→8dp inter-item spacing change. All latest-source native/static/visual/newcomer/release checks remain required; candidate source is not yet accepted.
