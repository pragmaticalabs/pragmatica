#!/bin/bash
# run-cloud-tests.sh — Run integration test suites against a Hetzner cloud cluster
#
# Usage: bash aether/tests/cloud/run-cloud-tests.sh [suite-name...]
#
# Without arguments, runs all cloud-compatible suites.
# With arguments, runs only the named suites (e.g., "00-smoke 04-streaming").
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEGRATION_DIR="$(cd "${SCRIPT_DIR}/../integration" && pwd)"
CLOUD_ENV="${SCRIPT_DIR}/.cloud-env"

# --- load cloud environment ---
if [ ! -f "$CLOUD_ENV" ]; then
    echo "ERROR: ${CLOUD_ENV} not found. Run deploy-cloud.sh first."
    exit 1
fi
source "$CLOUD_ENV"

# --- validate connectivity ---
echo "[$(date +%H:%M:%S)] Checking cluster connectivity..."
if ! curl -sf -H "X-API-Key: ${API_KEY}" "${CLUSTER_ENDPOINT}/health/live" >/dev/null 2>&1; then
    echo "ERROR: Cannot reach cluster at ${CLUSTER_ENDPOINT}/health/live"
    echo "Is the cluster still running? Check: hcloud server list --selector aether-cluster=cloud-test"
    exit 1
fi
echo "[$(date +%H:%M:%S)] ✓ Cluster reachable"

# --- cost guard ---
MAX_CLOUD_HOURS="${MAX_CLOUD_HOURS:-6}"
NOW=$(date +%s)
ELAPSED_S=$((NOW - DEPLOY_START))
ELAPSED_H=$((ELAPSED_S / 3600))
if [ "$ELAPSED_H" -ge "$MAX_CLOUD_HOURS" ] 2>/dev/null; then
    echo "WARNING: Cluster has been running for ${ELAPSED_H} hours (limit: ${MAX_CLOUD_HOURS}h)"
    echo "Consider running teardown-cloud.sh to avoid unnecessary costs."
    echo "Set MAX_CLOUD_HOURS to override."
fi

# --- export environment for test scripts ---
export TARGET_HOST="${TARGET_HOST}"
export MGMT_PORT="${MGMT_PORT}"
export LB_PORT="${LB_PORT}"
export LB_MGMT_PORT="${LB_MGMT_PORT}"
export APP_ENDPOINT="${APP_ENDPOINT}"
export CLUSTER_ENDPOINT="${CLUSTER_ENDPOINT}"
export API_KEY="${API_KEY}"
export AETHER_SSH_KEY="${AETHER_SSH_KEY}"
export AETHER_SSH_USER="${AETHER_SSH_USER:-root}"
export NODE_COUNT="${NODE_COUNT}"
export CLOUD_MODE="true"
export BASTION_IP="${BASTION_IP}"
export CLOUD_TIMEOUT_MULTIPLIER="${CLOUD_TIMEOUT_MULTIPLIER:-2}"

# --- suites ---
ALL_SUITES=(
    00-smoke
    02-chaos
    03-scaling
    04-streaming
    05-security
    06-deployment
    08-resources
    09-artifacts
    10-database
    11-observability
    12-network
    13-edge-cases
    14-storage
    15-delegation
)

# Skip 01-stability (soak tests) and 07-cluster-mgmt (bootstrap/destroy conflict) by default
SKIP_SUITES="${SKIP_SUITES:-01-stability,07-cluster-mgmt}"

if [ $# -gt 0 ]; then
    SUITES=("$@")
else
    SUITES=("${ALL_SUITES[@]}")
fi

# --- run ---
TOTAL_PASSED=0
TOTAL_FAILED=0
RESULTS=()

echo
echo "========================================"
echo "  Cloud Integration Tests"
echo "  Cluster: ${CLUSTER_ENDPOINT}"
echo "  App:     ${APP_ENDPOINT}"
echo "  Suites:  ${#SUITES[@]}"
echo "========================================"
echo

for suite in "${SUITES[@]}"; do
    # Check skip list
    if echo "${SKIP_SUITES}" | grep -q "${suite}"; then
        echo "[$(date +%H:%M:%S)] SKIP: ${suite} (in SKIP_SUITES)"
        RESULTS+=("${suite}: SKIPPED")
        continue
    fi

    SUITE_DIR="${INTEGRATION_DIR}/suites/${suite}"
    if [ ! -d "$SUITE_DIR" ]; then
        echo "[$(date +%H:%M:%S)] SKIP: ${suite} (directory not found)"
        RESULTS+=("${suite}: NOT FOUND")
        continue
    fi

    echo "[$(date +%H:%M:%S)] --- ${suite} ---"
    SUITE_START=$(date +%s)

    # Run via existing runner
    if bash "${INTEGRATION_DIR}/scripts/run-suite.sh" "${suite}" > "/tmp/cloud-${suite}.log" 2>&1; then
        SUITE_STATUS="PASS"
    else
        SUITE_STATUS="FAIL"
    fi

    SUITE_END=$(date +%s)
    SUITE_DURATION=$(( SUITE_END - SUITE_START ))

    # Extract pass/fail counts
    PASSED=$(grep -E 'Scripts passed' "/tmp/cloud-${suite}.log" | tail -1 | grep -oE '[0-9]+' || echo "0")
    FAILED=$(grep -E 'Scripts failed' "/tmp/cloud-${suite}.log" | tail -1 | grep -oE '[0-9]+' || echo "0")

    TOTAL_PASSED=$((TOTAL_PASSED + PASSED))
    TOTAL_FAILED=$((TOTAL_FAILED + FAILED))

    if [ "$FAILED" = "0" ]; then
        echo "[$(date +%H:%M:%S)] ✓ ${suite}: ${PASSED} passed (${SUITE_DURATION}s)"
    else
        echo "[$(date +%H:%M:%S)] ✗ ${suite}: ${PASSED} passed, ${FAILED} failed (${SUITE_DURATION}s)"
        # Show failures
        grep -E 'FAIL' "/tmp/cloud-${suite}.log" | head -10
    fi

    RESULTS+=("${suite}: passed=${PASSED} failed=${FAILED} (${SUITE_DURATION}s)")

    # Brief pause between suites for cluster recovery
    sleep 5
done

# --- summary ---
echo
echo "========================================"
echo "  Cloud Test Results"
echo "========================================"
for r in "${RESULTS[@]}"; do
    echo "  $r"
done
echo
echo "  Total: ${TOTAL_PASSED} passed, ${TOTAL_FAILED} failed"
echo "========================================"

# Logs at /tmp/cloud-<suite>.log
echo
echo "Suite logs: /tmp/cloud-*.log"

# Exit with failure if any suite failed
[ "$TOTAL_FAILED" -eq 0 ]
