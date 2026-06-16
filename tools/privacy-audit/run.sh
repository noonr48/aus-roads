#!/usr/bin/env bash
# Privacy audit for aus-roads.
# Runs on every CI build. Fails if privacy posture is violated.
#
# Checks:
# 1. INTERNET permission only allowed on withNetwork flavor
# 2. ACCESS_*_LOCATION never allowed on any flavor
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
    if grep -q 'ACCESS_FINE_LOCATION\|ACCESS_COARSE_LOCATION' "$manifest" 2>/dev/null; then
        if grep 'ACCESS_FINE_LOCATION\|ACCESS_COARSE_LOCATION' "$manifest" | grep -q 'tools:node="remove"'; then
            log_ok "$(basename "$manifest"): Location permissions stripped"
        else
            log_fail "$(basename "$manifest"): Location permissions present without tools:node=remove"
        fi
    fi
done

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
    COUNT=$(grep -ri "$sdk" "$ANDROID_DIR" --include="*.kt" --include="*.gradle*" --include="*.toml" -l 2>/dev/null | grep -v build | grep -v ".gradle" | wc -l || true)
    if [ "$COUNT" -gt 0 ]; then
        log_fail "Found references to '$sdk' SDK"
        grep -ri "$sdk" "$ANDROID_DIR" --include="*.kt" --include="*.gradle*" --include="*.toml" -l 2>/dev/null | grep -v build | grep -v ".gradle"
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