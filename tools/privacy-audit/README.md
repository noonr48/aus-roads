# Privacy Audit Tooling

Automated privacy checks that run on every CI build.

## Running locally

```bash
./tools/privacy-audit/run.sh
```

## Checks performed

1. **Source permissions** — unremoved INTERNET, foreground fine/coarse GPS and
   `FOREGROUND_SERVICE_LOCATION` are allowed only in the exact
   `app/src/withNetwork/AndroidManifest.xml`. Offline grants, background/media
   location and other location permissions refuse. XML removal markers apply
   only to their own node; merged-APK checks remain separate.
2. **HttpClient source ownership** — at most one active direct constructor site
   in each exact reviewed source file: the pack downloader's
   `network/HttpClientProvider.kt`, and `TrafficSaProvider.kt`,
   `NswLiveTrafficProvider.kt`, `VicRoadsProvider.kt`, `SaOutbackProvider.kt` in
   their respective traffic modules. Full paths are enumerated in
   `HTTP_CLIENT_OWNERS` in `manifest_permissions.py`. No direct constructor is
   allowed elsewhere, including `DownloadModule.kt` or sibling files in those
   modules. Factory declarations/calls such as `createHttpClient` are not
   constructor sites. Comments and literal text are inactive; executable string
   interpolation is scanned. Empty fixtures are supported. This is lexical
   direct-call ownership, **not** runtime instance cardinality or a proof that
   every networking mechanism (including aliases/reflection) has been found.
3. **SDK references** — explicit vendor namespaces, dependency coordinates and
   plugin/reflection references for analytics/crash SDKs refuse. Google Play
   Services location remains allowed. Groovy `.gradle` group/name records use
   the grammar's direct map/named-argument owners, including active interpolation,
   defaults and nested records. Only static literals (with enclosing grouping)
   are paired; computed calls/keys/values and annotation equals-members are not
   evaluated or borrowed. The final direct duplicate key owns its field.
   Kotlin `.kt`/`.kts` pair only complete static direct call or `mapOf`
   arguments in the same parenthesized owner; grouping is supported, computed
   suffixes/templates and nested fields cannot lend values. Annotation members,
   property assignments and parameter defaults are not paired. Java has no
   named-argument inference. Both languages retain explicit namespace/literal
   scanning, including reflection. TOML records retain their parser-owned
   semantic check. Forbidden TOML locations use parser-completed statement
   endpoints and public-parser binary search rather than reparsing every physical
   line. The small `toml_locations.py` adapter clones only a per-call stdlib parser
   namespace; it never changes global rules. Its private CPython ABI and ordered
   endpoints are checked and failures refuse. A location decode fault after valid
   full input is an internal parser failure, not malformed source. This is source
   declaration checking, not language compilation validity, resolved/transitive
   dependencies, computed dependency resolution or runtime SDK presence.

HTTP and SDK source scans skip only exact `build`, `.gradle`, `test`,
`androidTest` and `testFixtures` directories, never root/filename/module
substrings. Failed walk/read/UTF-8/literal parsing refuses with a bounded,
privacy-safe seam diagnostic. Unexpected/duplicate HTTP sites are errors, never
warning-only success. Healthy source audits emit zero failure witnesses.

Groovy inputs are parsed together in one bounded JVM using the already pinned
Gradle 8.10.2 distribution's Groovy 3.0.22 jar (SHA-256
`c92c92c4b9b183f9981ba7399f36592e5e3ad6f4cdac7101b5a22cc17998d13f`).
The launcher respects `GRADLE_USER_HOME` and `JAVA_HOME` (JDK17 required).
Provision that normal Gradle wrapper before auditing a tree with `.gradle`
inputs; the audit itself never downloads, evaluates Gradle/Groovy source or
resolves dependencies. The fixed helper alone is compiled in a private temporary
classpath, inherited Java/Groovy option agents are excluded, and jar/JDK,
syntax, child-exit, complete-response and 12-second batch failures refuse. The
outer dispatcher regression deadline remains 20 seconds. Library code executes
for decoding, never input scripts, compiler transforms or class loading from
project source. Trees without Groovy inputs do not require this parser.

Dispatcher regressions and failure controls:

```bash
python3 -B -m unittest discover -s tools/privacy-audit -p 'test_*.py' -v
```

## Adding to CI

Add to `.github/workflows/ci.yml`:

```yaml
- name: Privacy audit
  run: ./tools/privacy-audit/run.sh
```
