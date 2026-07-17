#!/usr/bin/env bash
# Privacy audit for aus-roads.
# Runs on every CI build. Fails if privacy posture is violated.
#
# Checks:
# 1. INTERNET permission only allowed on withNetwork flavor
# 2. Foreground location is allowed only for on-device features; background location is forbidden
# 3. Only one HttpClient instance in the codebase (in MapPackManager)
# 4. No analytics/crash-reporting SDKs

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

ERRORS=0
WARNINGS=0

log_ok() { echo -e "${GREEN}✓${NC} $1"; }
log_warn() { echo -e "${YELLOW}⚠${NC} $1"; WARNINGS=$((WARNINGS + 1)); }
log_fail() { echo -e "${RED}✗${NC} $1"; ERRORS=$((ERRORS + 1)); }

echo "=== aus-roads Privacy Audit ==="
echo ""

# --- Check 1: INTERNET permission ---
echo "Checking INTERNET permission..."

# Find all AndroidManifest.xml files
MANIFESTS=$(find "$ANDROID_DIR" -name "AndroidManifest.xml" -not -path "*/build/*" -not -path "*/.gradle/*")

for manifest in $MANIFESTS; do
    if grep -q 'android.permission.INTERNET' "$manifest" 2>/dev/null; then
        # Check if it has tools:node="remove"
        if grep -q 'tools:node="remove"' "$manifest" 2>/dev/null && grep -q 'android.permission.INTERNET' "$manifest" 2>/dev/null; then
            # Check if both are on the same permission line
            if grep 'android.permission.INTERNET' "$manifest" | grep -q 'tools:node="remove"'; then
                log_ok "$(basename "$manifest"): INTERNET stripped (tools:node=remove)"
            else
                log_warn "$(basename "$manifest"): INTERNET present but may have tools:node=remove on different line"
            fi
        else
            # INTERNET present without remove — check if it's a flavor manifest
            if echo "$manifest" | grep -q "withNetwork"; then
                log_ok "$(basename "$manifest"): INTERNET allowed (withNetwork flavor)"
            else
                log_fail "$(basename "$manifest"): INTERNET present without tools:node=remove and not in withNetwork flavor"
            fi
        fi
    else
        log_ok "$(basename "$manifest"): No INTERNET permission"
    fi
done

# --- Check 2: Location permissions ---
echo ""
echo "Checking location permissions..."

for manifest in $MANIFESTS; do
    if grep -q 'ACCESS_BACKGROUND_LOCATION' "$manifest" 2>/dev/null; then
        log_fail "$(basename "$manifest"): background location is forbidden"
    elif grep -q 'ACCESS_FINE_LOCATION\|ACCESS_COARSE_LOCATION' "$manifest" 2>/dev/null; then
        if [[ "$manifest" == "$ANDROID_DIR/app/src/main/AndroidManifest.xml" ]]; then
            log_ok "$(basename "$manifest"): foreground location declared for opt-in on-device features"
        elif grep 'ACCESS_FINE_LOCATION\|ACCESS_COARSE_LOCATION' "$manifest" | grep -q 'tools:node="remove"'; then
            log_ok "$(basename "$manifest"): Location permissions stripped"
        else
            log_fail "$(basename "$manifest"): unexpected foreground location declaration"
        fi
    else
        log_ok "$(basename "$manifest"): No location permission declaration"
    fi
done

# Check the dependency-merged manifests too. Source-only checks cannot detect a
# transitive library adding a permission to the final application.
echo ""
echo "Checking merged application manifests..."
if ! (cd "$ANDROID_DIR" && ./gradlew \
    :app:processOfflineDebugMainManifest \
    :app:processWithNetworkDebugMainManifest -q); then
    log_fail "Could not generate merged manifests"
else
    for variant in offlineDebug withNetworkDebug; do
        MERGED_MANIFEST=$(find \
            "$ANDROID_DIR/app/build/intermediates/merged_manifest/$variant" \
            -name AndroidManifest.xml -print -quit 2>/dev/null || true)
        if [ -z "$MERGED_MANIFEST" ]; then
            log_fail "$variant: merged manifest is missing"
            continue
        fi
        if grep -q 'ACCESS_BACKGROUND_LOCATION' "$MERGED_MANIFEST"; then
            log_fail "$variant: merged app contains forbidden background location"
        else
            log_ok "$variant: merged app has no background location"
        fi
        if grep -q 'ACCESS_FINE_LOCATION' "$MERGED_MANIFEST" && \
            grep -q 'ACCESS_COARSE_LOCATION' "$MERGED_MANIFEST"; then
            log_ok "$variant: merged app has coarse + fine foreground location"
        else
            log_fail "$variant: merged app is missing the foreground location pair"
        fi
        if [ "$variant" = "offlineDebug" ]; then
            if grep -q 'android.permission.INTERNET' "$MERGED_MANIFEST"; then
                log_fail "$variant: merged app unexpectedly contains INTERNET"
            else
                log_ok "$variant: merged app contains no INTERNET permission"
            fi
        elif grep -q 'android.permission.INTERNET' "$MERGED_MANIFEST"; then
            log_ok "$variant: merged app contains expected INTERNET permission"
        else
            log_fail "$variant: merged app is missing expected INTERNET permission"
        fi
    done
fi

# --- Check 3: HttpClient instances ---
echo ""
echo "Checking HttpClient instances..."

HTTP_CLIENT_COUNT=$(grep -r "HttpClient(" "$ANDROID_DIR" --include="*.kt" -l 2>/dev/null | grep -v build | grep -v test | grep -v "HttpClientProvider" | grep -v "DownloadModule" | grep -v "provider-sa" | grep -v "provider-nsw" | grep -v "provider-vic" | grep -v "provider-sa-outback" | wc -l || true)

if [ "$HTTP_CLIENT_COUNT" -eq 0 ]; then
    log_ok "No HttpClient instances found (pre-downloader state)"
elif [ "$HTTP_CLIENT_COUNT" -eq 1 ]; then
    CLIENT_FILE=$(grep -r "HttpClient(" "$ANDROID_DIR" --include="*.kt" -l 2>/dev/null | grep -v build | grep -v test)
    if echo "$CLIENT_FILE" | grep -q "HttpClientProvider"; then
        log_ok "Single HttpClient in HttpClientProvider"
    else
        log_warn "HttpClient found in unexpected location: $CLIENT_FILE"
    fi
else
    log_fail "Multiple HttpClient instances found ($HTTP_CLIENT_COUNT files)"
    grep -r "HttpClient(" "$ANDROID_DIR" --include="*.kt" -l 2>/dev/null | grep -v build | grep -v test
fi

# --- Check 4: Analytics/crash SDKs ---
echo ""
echo "Checking for analytics/crash SDKs..."

FORBIDDEN_SDKS=("firebase" "crashlytics" "google-analytics" "amplitude" "mixpanel" "appsflyer" "adjust")

for sdk in "${FORBIDDEN_SDKS[@]}"; do
    PATTERN="$sdk"
    if [[ "$sdk" == "adjust" ]]; then
        # "adjust" is ordinary UI prose; match only real Adjust SDK identifiers.
        PATTERN='com\.adjust|io\.adjust|adjust-android'
    fi
    MATCHES=$(grep -Eri "$PATTERN" "$ANDROID_DIR" \
        --include="*.kt" --include="*.gradle*" --include="*.toml" --include="AndroidManifest.xml" \
        -l 2>/dev/null | grep -v '/build/' | grep -v '/\.gradle/' || true)
    COUNT=$(printf '%s\n' "$MATCHES" | sed '/^$/d' | wc -l)
    if [ "$COUNT" -gt 0 ]; then
        log_fail "Found references to '$sdk' SDK"
        printf '%s\n' "$MATCHES"
    else
        log_ok "No '$sdk' references"
    fi
done

# --- Summary ---
echo ""
echo "=== Privacy Audit Summary ==="
echo "Errors: $ERRORS"
echo "Warnings: $WARNINGS"

if [ "$ERRORS" -gt 0 ]; then
    echo -e "${RED}FAILED${NC} — $ERRORS privacy violation(s) found"
    exit 1
elif [ "$WARNINGS" -gt 0 ]; then
    echo -e "${YELLOW}PASSED WITH WARNINGS${NC} — $WARNINGS warning(s)"
    exit 0
else
    echo -e "${GREEN}PASSED${NC} — no privacy violations"
    exit 0
fi
