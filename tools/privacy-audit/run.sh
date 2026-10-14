#!/usr/bin/env bash
# Privacy audit for aus-roads.
# Fails if source privacy posture is violated; merged APK audits remain separate.
#
# Checks:
# 1. INTERNET grants only allowed in app/src/withNetwork/AndroidManifest.xml
# 2. LOCATION grants only for current foreground GPS in that exact source set
# 3. At most one direct HttpClient constructor per exact reviewed source owner
# 4. No analytics/crash-reporting SDKs

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Explicit fixture/source root override; no arguments still audits this repository.
if [ "$#" -eq 0 ]; then
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
elif [ "$#" -eq 2 ] && [ "$1" = "--project-root" ] && [ -n "$2" ]; then
    PROJECT_ROOT="$2"
else
    echo "[component=privacy-audit seam=manifest-permissions class=invalid_arguments] Usage: run.sh [--project-root PATH]" >&2
    exit 1
fi
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

# --- Checks 1 and 2: XML source manifest permissions ---
echo "Checking INTERNET and location permissions..."

if python3 "$SCRIPT_DIR/manifest_permissions.py" --project-root "$PROJECT_ROOT"; then
    log_ok "Manifest permissions respect source flavor policy"
else
    log_fail "[component=privacy-audit seam=manifest-permissions class=checker_failed] Manifest permission audit refused"
fi

# --- Check 3: active source constructor ownership, not runtime cardinality ---
echo ""
echo "Checking HttpClient instances..."

if python3 "$SCRIPT_DIR/manifest_permissions.py" --httpclient-ownership --project-root "$PROJECT_ROOT"; then
    log_ok "HttpClient constructor sites respect reviewed source ownership"
else
    log_fail "[component=privacy-audit seam=httpclient-ownership class=checker_failed] HttpClient ownership audit refused"
fi

# --- Check 4: Analytics/crash SDKs ---
echo ""
echo "Checking for analytics/crash SDKs..."

if python3 "$SCRIPT_DIR/manifest_permissions.py" --sdk-references --project-root "$PROJECT_ROOT"; then
    log_ok "No forbidden SDK references in source/dependency declarations"
else
    log_fail "[component=privacy-audit seam=sdk-references class=checker_failed] SDK reference audit refused"
fi

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