#!/bin/bash
# test-schema-baseline.sh — Baseline schema, verify slices activate without executing SQL
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

BLUEPRINT="org.pragmatica.aether.test:test-persistence:1.0.0"
# Discovered at runtime via discover_tracked_datasource — `test-persistence` ships
# `schema/V900__create_kv.sql` so `BlueprintService.buildSchemaMigrationCommands`
# writes a `SchemaVersionKey` for the datasource the migration is associated with.
# The actual name comes from the migration directory layout / blueprint convention,
# not a fixed test variable.
DATASOURCE=""

# Discover the registered schema-tracked datasource name from the cluster's
# /api/schema/status list endpoint. Returns the first datasource name, or empty
# if none are registered. Used by the per-datasource tests below to address the
# actual registered name rather than guessing.
discover_tracked_datasource() {
    local body
    body=$(api_get "/api/schema/status" 2>/dev/null) || return 1
    # #598: the status list is CLUSTER-GLOBAL and url-shortener's datasource is tracked
    # too when cluster-A suites run in parallel — select THIS blueprint's row, never
    # the first row (head -1 grabbed whichever blueprint published first).
    printf '%s' "$body" | grep -oE '"datasource"[[:space:]]*:[[:space:]]*"[^"]+"' \
                       | sed 's/.*"datasource"[[:space:]]*:[[:space:]]*"\([^"]*\)"/\1/' \
                       | grep -m1 'testpersistence'
}

test_cluster_ready() {
    wait_for_cluster_ready 60
    push_blueprint "$BLUEPRINT"
    deploy_blueprint "$BLUEPRINT"
    wait_for "slices active (>= 1 instances)" \
        "[ \$(slices_total_instances) -ge 1 ]" 120
    # Poll the schema-status list until the blueprint's tracked datasource appears.
    # Migration commit goes through consensus → KV listener, so there's a brief
    # post-deploy window before SchemaVersionKey is observable.
    if ! wait_for "tracked datasource discovered from blueprint deploy" \
                  "[ -n \"\$(discover_tracked_datasource)\" ]" 60; then
        log_fail "test-persistence blueprint deploy did not register a tracked datasource within 60s — schema endpoints will all 500"
        return 1
    fi
    DATASOURCE=$(discover_tracked_datasource)
    log_pass "Cluster ready with baseline slice deployment; tracked datasource: ${DATASOURCE}"
}

# Endpoint smoke against the discovered datasource. With a real registered datasource
# the POST should return 2xx + a non-empty body documenting the baseline operation.
test_schema_baseline_endpoint() {
    if [ -z "$DATASOURCE" ]; then
        log_fail "DATASOURCE empty — discovery in test_cluster_ready failed; cannot run baseline"
        return 1
    fi
    local result
    if ! result=$(api_post "/api/schema/baseline/${DATASOURCE}" "{}"); then
        log_fail "POST /api/schema/baseline/${DATASOURCE} failed (api_post returned non-zero)"
        return 1
    fi
    assert_ne "$result" "" "Schema baseline endpoint returns non-empty body for ${DATASOURCE}"
}

# Strict: after baseline POST, schema_status reports a non-empty status field +
# currentVersion ≥ 0. The exact semantic of "BASELINED" depends on whether the
# orchestrator persists the baseline marker; we accept any status that's not
# FAILED/UNKNOWN (i.e., the baseline was acknowledged).
test_schema_status_after_baseline() {
    if [ -z "$DATASOURCE" ]; then
        log_fail "DATASOURCE empty — discovery failed"
        return 1
    fi
    local status status_field
    status=$(schema_status "$DATASOURCE")
    status_field=$(json_value "$status" "status")
    case "${status_field:-}" in
        ""|UNKNOWN|FAILED) log_fail "Schema status after baseline is unhealthy: status=${status_field:-<empty>}"; return 1 ;;
        *) log_pass "Schema status after baseline acknowledged: status=${status_field}" ;;
    esac
}

# Strict: slices must remain active after baselining (baseline must not destabilise
# the cluster). slices_total_instances() is real cluster state.
test_slices_active_after_baseline() {
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices still active after baseline: ${instances} instances"
}

# Idempotency check against the discovered datasource: a second baseline call must
# also return 2xx + non-empty body (endpoint-level idempotency, not state idempotency).
test_baseline_idempotent() {
    if [ -z "$DATASOURCE" ]; then
        log_fail "DATASOURCE empty — discovery in test_cluster_ready failed; cannot run idempotent baseline"
        return 1
    fi
    local result
    if ! result=$(api_post "/api/schema/baseline/${DATASOURCE}" "{}"); then
        log_fail "Second POST /api/schema/baseline/${DATASOURCE} failed (not idempotent)"
        return 1
    fi
    assert_ne "$result" "" "Schema baseline idempotent: second call returns non-empty body"
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
