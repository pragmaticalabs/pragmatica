#!/bin/bash
# test-cloud-helpers.sh — manual-run unit tests for cloud_public_ip / cloud_ssh / cloud_node_ip.
#
# No external test runner; invoke directly:
#   bash aether/tests/integration/test/test-cloud-helpers.sh
#
# Stages a synthetic ~/.aether/clusters/<name>/bootstrap-state.json from the
# fixture, exports BOOTSTRAP_CLUSTER_NAME, then asserts cloud_public_ip behaviour.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
FIXTURE="${SCRIPT_DIR}/fixtures/bootstrap-state.json"

# common.sh requires TARGET_HOST.
export TARGET_HOST="cloud-helpers-test"
export ENV_TYPE="cloud"
export CLOUD_SOURCE_NAME="hetzner-eu"

# Stage the fixture under a throwaway cluster name to avoid clobbering real state.
TEST_CLUSTER="cloud-helpers-test-$$"
TEST_DIR="${HOME}/.aether/clusters/${TEST_CLUSTER}"
mkdir -p "$TEST_DIR"
cp "$FIXTURE" "${TEST_DIR}/bootstrap-state.json"
trap 'rm -rf "${TEST_DIR}"' EXIT
export BOOTSTRAP_CLUSTER_NAME="$TEST_CLUSTER"

# shellcheck source=../lib/common.sh
source "${INTEG_DIR}/lib/common.sh"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

# 1) node-1 maps to the first IP (hetzner-eu-core-0).
got=$(cloud_public_ip "node-1" 2>/dev/null) && [ "$got" = "203.0.113.10" ] \
    && ok "cloud_public_ip node-1 -> 203.0.113.10" \
    || fail "cloud_public_ip node-1 expected 203.0.113.10, got '${got}'"

# 2) node-3 maps to the third IP (hetzner-eu-core-2).
got=$(cloud_public_ip "node-3" 2>/dev/null) && [ "$got" = "203.0.113.12" ] \
    && ok "cloud_public_ip node-3 -> 203.0.113.12" \
    || fail "cloud_public_ip node-3 expected 203.0.113.12, got '${got}'"

# 3) Raw bootstrap nodeId is accepted as-is.
got=$(cloud_public_ip "hetzner-eu-core-1" 2>/dev/null) && [ "$got" = "203.0.113.11" ] \
    && ok "cloud_public_ip hetzner-eu-core-1 -> 203.0.113.11" \
    || fail "cloud_public_ip hetzner-eu-core-1 expected 203.0.113.11, got '${got}'"

# 4) Unknown node returns failure.
if cloud_public_ip "node-99" >/dev/null 2>&1; then
    fail "cloud_public_ip node-99 should fail (no such node)"
else
    ok "cloud_public_ip node-99 fails as expected"
fi

# 5) Missing argument returns failure (rc=2).
cloud_public_ip >/dev/null 2>&1; rc=$?
if [ "$rc" -eq 2 ]; then
    ok "cloud_public_ip without args fails fast (rc=2)"
else
    fail "cloud_public_ip no-arg expected rc=2, got rc=${rc}"
fi

# 6) BOOTSTRAP_CLUSTER_NAME unset is a hard failure (rc=2).
(
    unset BOOTSTRAP_CLUSTER_NAME CLOUD_BOOTSTRAP_CLUSTER
    cloud_public_ip "node-1" >/dev/null 2>&1; rc=$?
    exit "$rc"
)
rc=$?
if [ "$rc" -eq 2 ]; then
    ok "cloud_public_ip without BOOTSTRAP_CLUSTER_NAME fails fast (rc=2)"
else
    fail "cloud_public_ip no-cluster expected rc=2, got rc=${rc}"
fi

# 7) Missing state file is a recoverable failure (rc=1).
(
    export BOOTSTRAP_CLUSTER_NAME="cloud-helpers-test-nonexistent-$$"
    cloud_public_ip "node-1" >/dev/null 2>&1; rc=$?
    exit "$rc"
)
rc=$?
if [ "$rc" -eq 1 ]; then
    ok "cloud_public_ip missing state-file fails (rc=1)"
else
    fail "cloud_public_ip missing-state expected rc=1, got rc=${rc}"
fi

# 8) cloud_node_ip delegates to cloud_public_ip (back-compat shim).
got=$(cloud_node_ip "node-2" 2>/dev/null) && [ "$got" = "203.0.113.11" ] \
    && ok "cloud_node_ip node-2 -> 203.0.113.11 (delegates to cloud_public_ip)" \
    || fail "cloud_node_ip node-2 expected 203.0.113.11, got '${got}'"

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
