# Privacy Audit Tooling

Automated privacy checks that run on every CI build.

## Running locally

```bash
./tools/privacy-audit/run.sh
```

## Checks performed

1. **INTERNET permission** — only allowed on `withNetwork` build flavor
2. **Location permissions** — `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` must be stripped on all flavors
3. **HttpClient instances** — only one allowed, must be in `HttpClientProvider`
4. **Analytics/crash SDKs** — Firebase, Crashlytics, Amplitude, Mixpanel, etc. must not be present

## Adding to CI

Add to `.github/workflows/ci.yml`:

```yaml
- name: Privacy audit
  run: ./tools/privacy-audit/run.sh
```
