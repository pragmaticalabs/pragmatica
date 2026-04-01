#!/bin/bash
# run-suite.sh — Run a specific test suite
# Usage: ./run-suite.sh <suite-name>
#   e.g., ./run-suite.sh 02-chaos
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SUITE_DIR="${SCRIPT_DIR}/../suites"
SUITE_NAME="${1:?Usage: run-suite.sh <suite-name> (e.g. 02-chaos)}"

source "${SCRIPT_DIR}/../lib/common.sh"

SUITE_PATH="${SUITE_DIR}/${SUITE_NAME}"
if [ ! -d "$SUITE_PATH" ]; then
    log_error "Suite not found: ${SUITE_PATH}"
    log_info "Available suites:"
    ls -d "${SUITE_DIR}"/*/ 2>/dev/null | while read -r d; do basename "$d"; done
    exit 1
fi

log_info "=== Running suite: ${SUITE_NAME} ==="
SUITE_PASSED=0
SUITE_FAILED=0

for test_script in "${SUITE_PATH}"/test-*.sh; do
    if [ ! -f "$test_script" ]; then
        continue
    fi
    test_name="$(basename "$test_script")"
    # Skip soak tests unless explicitly enabled
    if [[ "${SKIP_SOAK:-true}" == "true" ]] && [[ "$test_name" == *soak* ]]; then
        log_warn "SKIPPED (soak): ${test_name} — set SKIP_SOAK=false to enable"
        continue
    fi
    log_info "--- ${test_name} ---"
    if bash "$test_script"; then
        SUITE_PASSED=$((SUITE_PASSED + 1))
    else
        SUITE_FAILED=$((SUITE_FAILED + 1))
        log_error "FAILED: ${test_name}"
    fi
done

echo ""
echo "========================================"
echo "  Suite: ${SUITE_NAME}"
echo "  Scripts passed: ${SUITE_PASSED}"
echo "  Scripts failed: ${SUITE_FAILED}"
echo "========================================"
[ "$SUITE_FAILED" -eq 0 ]
