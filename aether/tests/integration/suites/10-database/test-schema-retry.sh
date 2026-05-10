#!/bin/bash
# test-schema-retry.sh — Verify schema retry endpoint and recovery
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

DATASOURCE="${TEST_DATASOURCE:-default}"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

# UNTESTABLE without a registered schema-tracked datasource: GET /api/schema/status
# returns 500 "Schema status not found for datasource" when the named datasource is
# not bound. The test-persistence blueprint deployed by the suite registers slice-
# internal state but NOT a schema-tracked datasource. The prior "endpoint smoke"
# conversion was wishful — without a fixture this assertion is structural, not behavioural.
test_schema_status_before_retry() {
    log_fail "TODO: schema_status('${DATASOURCE}') requires a registered datasource fixture; today returns 500 because the cluster has no datasource named '${DATASOURCE}'"
    return 1
}

# UNTESTABLE without a registered datasource (same reason). POST /api/schema/retry
# returns 500 "Schema is not in FAILED state" only AFTER datasource registration.
# Without the fixture, returns 500 "Schema status not found for datasource".
test_schema_retry_endpoint() {
    log_fail "TODO: POST /api/schema/retry/${DATASOURCE} requires a registered datasource fixture (see test_schema_status_before_retry TODO above)"
    return 1
}

# UNTESTABLE without a failure-injection fixture: the meaningful assertion is
# "after a failed migration, retry transitions state from FAILED → HEALTHY".
# Without a way to introduce a failed migration first, this test is observing
# the steady-state schema status and any value (or empty body) trivially passes.
test_schema_status_after_retry() {
    log_fail "TODO: requires fault-injection fixture (deploy slice with deliberately-failing migration, then retry, then assert state transitions FAILED → HEALTHY)"
    return 1
}

# UNTESTABLE without a registered datasource (same reason). Idempotency is a real
# property to assert, but only against an endpoint that returns 2xx in the first place.
test_retry_idempotent() {
    log_fail "TODO: idempotency assertion requires a registered datasource fixture (see test_schema_retry_endpoint TODO above)"
    return 1
}

test_cluster_healthy_after_retry() {
    assert_cluster_healthy "Cluster healthy after schema retry"
}

run_test "Cluster ready" test_cluster_ready
run_test "Schema status before retry" test_schema_status_before_retry
run_test "Schema retry endpoint" test_schema_retry_endpoint
run_test "Schema status after retry" test_schema_status_after_retry
run_test "Retry idempotent" test_retry_idempotent
run_test "Healthy after retry" test_cluster_healthy_after_retry
print_summary
