# State ownership
No new persisted entity, schema version, identity or automatic data backfill is planned. Existing entities remain single-owned.

## Entities, fields and invariants
| Entity/value | Fields touched | Sole state owner | Invariant |
|---|---|---|---|
| Exploration preferences/visits | existing ExplorationPreferences.interests, preferNew:Boolean, visits:Map<String,ExploreCategory> | ExplorationStore | Only explicit successful writes change history; no implicit eviction or reset; v1 identity retained. |
| Pending/failed exploration intent | existing keyed ordered mutations/clear intent | ExploreViewModel | A newer successful history-affecting intent supersedes an older failed clear, while unrelated failed interests remain retryable. |
| Explore presentation value | additive visitLimitReached:Boolean, non-null, default false; existing read/saving/error fields | ExploreViewModel | Capacity is distinguished from storage failure; UI never fabricates unread values. |
| Essentials query/retry value | existing category/reference/token/generation/readiness and additive catalog retry job if needed | NearbyViewModel | Publication requires current READY epoch/token/reference; one retry owns bootstrap and cannot resurrect canceled data. |
| Catalog instance | existing SQLite handle, availability, generation | FtsSearchRepository | READY implies validated supported rows; replacement/query serialize; no split repository owner. |
| Public catalog pair | existing sa-search.db + sa-search.json hashes/provenance | bundle-search-catalog.py | Ordinary failed publication preserves the previous consistent pair; loader verifies metadata/hash rather than trusting filenames. |
| Saved pins | existing Room pin rows/schema | PinDatabase/DAO | Supported migration preserves all rows; unsupported version fails without destructive reset. |
| Saved routes | existing Room route/history rows/schema | RouteHistoryDatabase/RouteHistoryDao | Unsupported version must not delete history. |
| GPS tracking session | existing navigation state/jobs/retry count/fix time; additive monotonic session-start reference | NavigationViewModel | No unbounded silent cold acquisition; Stop owns cleanup and no late session work can publish. |
| Fused subscription | callback/request/registration Task, attempt metadata | FusedNavigationLocationSource | Registration failure terminates upstream flow and removal runs on cancellation/failure; no offline dependency. |

## Relations and SSOT
UI reads ViewModel state and persisted Store flows; it does not maintain a second history/preference copy. Visits use existing normalized public result identity, not row IDs or GPS inference. Map first-hit is resolved against current pin IDs; absent IDs are not converted to a different editing action. Search, camera and hazard readers share the same repository/generation. Private diagnostic storage remains PlacesHealth's fixed metadata; source failures must not include user queries/coordinates/names/paths.

Rejected: synchronizing duplicate stores, silently deleting oldest visits at capacity, replacing coordinate camera IDs with OSM IDs, or weakening failed-intent retention globally to mask the failed-clear bug.

## Migration / rollback notes
Additive transient UI fields default false; no persisted schema changes or backfill. Existing v1-to-v2 pin migrations remain supported; do not add destructive fallback or a invented migration for unknown versions. Source rollback restores only this recovery patch over the retained pre-repair snapshot, never the adopted exploration cluster. A phone data rollback is not assumed; same-signature in-place upgrade is qualified on a disposable copy first, and owner data is never cleared.
