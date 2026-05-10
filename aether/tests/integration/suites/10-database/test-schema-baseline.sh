#!/bin/bash
# test-schema-baseline.sh — Baseline schema, verify slices activate without executing SQL
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

DATASOURCE="${TEST_DATASOURCE:-default}"
BLUEPRINT="org.pragmatica.aether.test:test-persistence:1.0.0"

test_cluster_ready() {
    wait_for_cluster 60
    push_blueprint "$BLUEPRINT"
    deploy_blueprint "$BLUEPRINT"
    wait_for "slices active (>= 1 instances)" \
        "[ \$(slices_total_instances) -ge 1 ]" 120
    log_pass "Cluster ready with baseline slice deployment"
}

# UNTESTABLE without a real datasource fixture: POST /api/schema/baseline returns
# 500 "Schema status not found for datasource" when the named datasource is not
# registered. The test-persistence blueprint deployed above registers slice-internal
# state, NOT a schema-tracked datasource named "${DATASOURCE}". Fixing this requires
# either binding ${DATASOURCE} to a real Postgres with applied migrations OR exposing
# a fixture endpoint that registers a synthetic datasource. The prior "endpoint smoke"
# conversion was wishful — the endpoint isn't a smoke target without a fixture.
test_schema_baseline_endpoint() {
    log_fail "TODO: POST /api/schema/baseline/${DATASOURCE} requires a registered schema-tracked datasource fixture; today the call returns 500 because the cluster has no datasource named '${DATASOURCE}'"
    return 1
}

# UNTESTABLE without a real datasource fixture: the meaningful assertion is
# "after baseline, schema status reports state=BASELINED with the baseline
# version recorded". The test cluster does not bind ${DATASOURCE} to a real
# Postgres with applied migrations, so observed state is empty/UNKNOWN and
# any reading would trivially pass.
test_schema_status_after_baseline() {
    log_fail "TODO: bind ${DATASOURCE} to a real datasource fixture and assert state == BASELINED with baselineVersion field present after POST /api/schema/baseline"
    return 1
}

# Strict: slices must remain active after baselining (baseline must not destabilise
# the cluster). slices_total_instances() is real cluster state.
test_slices_active_after_baseline() {
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices still active after baseline: ${instances} instances"
}

# UNTESTABLE without a real datasource fixture (same reason as test_schema_baseline_endpoint).
# Idempotency is a meaningful property to assert, but only against an endpoint that returns
# 2xx in the first place — which requires a registered datasource.
test_baseline_idempotent() {
    log_fail "TODO: idempotency assertion requires a registered datasource fixture (see test_schema_baseline_endpoint TODO above)"
    return 1
}

test_cluster_healthy_after_baseline() {
    assert_cluster_healthy "Cluster healthy after schema baseline"
}

run_test "Cluster ready" test_cluster_ready
run_test "Schema baseline endpoint" test_schema_baseline_endpoint
run_test "Schema status after baseline" test_schema_status_after_baseline
run_test "Slices active after baseline" test_slices_active_after_baseline
run_test "Baseline idempotent" test_baseline_idempotent
run_test "Healthy after baseline" test_cluster_healthy_after_baseline
print_summary
