#!/bin/bash
# run-all.sh — Run all test suites sequentially
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${SCRIPT_DIR}/.."
SUITE_DIR="${ROOT_DIR}/suites"

source "${ROOT_DIR}/lib/common.sh"
source "${ROOT_DIR}/lib/cluster.sh"

SKIP_BOOTSTRAP="${SKIP_BOOTSTRAP:-false}"
SKIP_CLEANUP="${SKIP_CLEANUP:-false}"

# ---------------------------------------------------------------------------
# Bootstrap cluster
# ---------------------------------------------------------------------------
if [ "$SKIP_BOOTSTRAP" != "true" ]; then
    log_step "Setting up integration test cluster"
    bash "${SCRIPT_DIR}/setup.sh"
else
    log_step "Waiting for cluster to become ready"
    wait_for_cluster 180
fi

# ---------------------------------------------------------------------------
# Run suites in order
# ---------------------------------------------------------------------------
TOTAL_SUITES=0
SUITES_PASSED=0
SUITES_FAILED=0

for suite in "${SUITE_DIR}"/*/; do
    if [ ! -d "$suite" ]; then
        continue
    fi
    suite_name="$(basename "$suite")"
    TOTAL_SUITES=$((TOTAL_SUITES + 1))

    log_info "============================================"
    log_info "  SUITE: ${suite_name}"
    log_info "============================================"

    suite_ok=true
    for test_script in "${suite}"test-*.sh; do
        if [ ! -f "$test_script" ]; then
            continue
        fi
        # Skip soak tests unless explicitly enabled
        if [[ "${SKIP_SOAK:-true}" == "true" ]] && [[ "$(basename "$test_script")" == *soak* ]]; then
            log_warn "SKIPPED (soak): $(basename "$test_script") — set SKIP_SOAK=false to enable"
            continue
        fi
        log_info "--- $(basename "$test_script") ---"
        if bash "$test_script"; then
            :
        else
            suite_ok=false
            log_error "FAILED: $(basename "$test_script")"
        fi
    done

    if [ "$suite_ok" = true ]; then
        SUITES_PASSED=$((SUITES_PASSED + 1))
    else
        SUITES_FAILED=$((SUITES_FAILED + 1))
    fi
done

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
if [ "$SKIP_CLEANUP" != "true" ]; then
    log_step "Destroying test cluster"
    bash "${SCRIPT_DIR}/cleanup.sh" 2>/dev/null || true
fi

# ---------------------------------------------------------------------------
# Final summary
# ---------------------------------------------------------------------------
echo ""
echo "============================================"
echo "  FINAL RESULTS"
echo "  Total suites: ${TOTAL_SUITES}"
echo "  Passed:       ${SUITES_PASSED}"
echo "  Failed:       ${SUITES_FAILED}"
echo "============================================"
[ "$SUITES_FAILED" -eq 0 ]
