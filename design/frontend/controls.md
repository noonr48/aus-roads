# Recovery control wiring — unchanged data owners

| Control | Handler → real action | Observed proof |
|---|---|---|
| Discover search / categories / radius / place | setQuery/setCategory/setRadius → same ExploreViewModel/catalog; onMap → existing in-app map; setVisited → native ordered store | C46 original both-flavor full gates81+80 passed; c47 only reduces header spacing/removes promotional Text. Fresh c47 gates/pixels pending. |
| Your interests | ExploreScreen.interests=true → existing modal | C46 original real-IME physical recovery passes and retains all500 visits; c47 source unchanged at this control, fresh gate pending. |
| Close | onDismiss → interests=false only | C46 original held-native-write dismissal/reopen and persisted readback pass; fresh c47 gate pending. Never a Save/Cancel action. |
| Interest / New places first | ExploreViewModel.setInterest/setPreferNew → original ordered ExplorationStore/DataStore writer | Original native held-write/persistence predicates retained and pass c46 both flavors; fresh c47 gate pending. |
| Try again | ExploreViewModel.retry → original preferences observation/catalog recovery | Actual malformed-history and catalog Retry cases retained and pass c46 both flavors; fresh c47 gate pending. |
| Clear visit history | confirmation only → ExploreViewModel.clearVisits → original visit-only mutation | Full malformed-history Cancel/Confirm and non-visit-key preservation retained and pass c46 both flavors; fresh c47 gate pending. |
| Capacity recovery | actual physical Your interests tap → modal; not a history deletion | c41 red 78fd3d2ece52cb6c proves the viewport defect; same real-keyboard inequality/tap/500-row predicate retained. |

The active modal owns all preference-read/write/capacity feedback while open; the body resumes presentation after Close. No new persistent sink, callback, public state type, schema or permission is introduced. Existing PREFERENCES_READ/WRITE and real native test diagnostics remain the failure witnesses. No acceptance from handler counts or source alone.
