#!/bin/bash
# test-schema-baseline-conflict.sh — baseline of an ALREADY-MIGRATED datasource is refused with 409.
#
# WHAT THIS PINS (and what it does not). run-tests.sh Step 7 deploys every cluster-A blueprint —
# test-persistence included — before any suite runs, and that deploy applies V900 through the
# reactive schema flow. So by the time this suite runs, `database.testpersistence` is at version
# 900 and the server's correct answer to `POST /api/v1/schema/baseline/<ds>` is
# `409 Baseline conflict ... already applied up to version 900`. This suite therefore asserts THAT
# contract. It does NOT cover the FIRST-TIME baseline path (baseline on a datasource with no applied
# versioned migration); the earlier tests of this file claimed to, and failed 2 of 3 on every run
# on docker and cloud (rc4-baseline logs, 2026-09-23; cloud 2026-09-30) because the premise never held.
# First-time coverage needs a dedicated blueprint + datasource: see the CHARTER, "Known limitations".
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
# /api/v1/schema/status list endpoint. Returns the first datasource name, or empty
# if none are registered. Used by the per-datasource tests below to address the
# actual registered name rather than guessing.
discover_tracked_datasource() {
    local body
    body=$(api_get "/api/v1/schema/status" 2>/dev/null) || return 1
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

# Version file: written by the first test, read by the later ones (run_test functions must not
# rely on shell state surviving between them).
VERSION_FILE="/tmp/s10-baseline-version.$$"
trap 'rm -f "$VERSION_FILE"' EXIT

_recorded_version() {
    [ -f "$VERSION_FILE" ] && cat "$VERSION_FILE"
    return 0
}

_current_version() {
    json_value "$(schema_status "$DATASOURCE")" "currentVersion" || true
}

_migrated() {
    local v
    v=$(_current_version)
    [ "${v:--1}" -ge 900 ] 2>/dev/null
}

# The leader's management endpoint, or empty when it cannot be derived (caller falls back to the
# live endpoint). Schema mutations are leader-bound: a non-leader answers 409 SchemaNotLeader, which
# is ALSO a 409 — so an unfiltered POST to a follower could be mistaken for the conflict under test.
_leader_mgmt_endpoint() {
    local leader ip
    leader=$(cluster_leader_http) || return 0
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        ip=$(cloud_public_ip "$leader" 2>/dev/null) || return 0
        [ -n "$ip" ] && printf '%s://%s:%s' "${MGMT_SCHEME:-http}" "$ip" "${CLOUD_MGMT_PORT:-8080}"
    elif [[ "$leader" =~ ^node-([0-9]+)$ ]]; then
        printf 'http://%s:%s' "${TARGET_HOST}" "$((MGMT_PORT + BASH_REMATCH[1] - 1))"
    fi
    return 0
}

# POST baseline to the LEADER; sets BASELINE_STATUS (HTTP status, 000 = no response) and BASELINE_BODY.
# Uses _api_call's status marker rather than api_post, which discards the status of a refusal.
# A SchemaNotLeader refusal (leadership moved, or the leader endpoint was not derivable) is retried,
# re-resolving the leader each time; it is NEVER accepted as the conflict: if retries run out the
# body still says "requires the leader node" and the callers' detail match fails the test.
BASELINE_STATUS=""
BASELINE_BODY=""
baseline_post() {
    local out endpoint attempt
    for attempt in 1 2 3 4 5; do
        endpoint=$(_leader_mgmt_endpoint)
        [ -n "$endpoint" ] || endpoint=$(_resolve_live_endpoint)
        out=$(_api_call POST "${endpoint}/api/v1/schema/baseline/${DATASOURCE}" "{}" 1) || true
        BASELINE_STATUS=$(printf '%s' "$out" | grep -oE '__API_HTTP_STATUS:[0-9]+__' | tail -1 | sed 's/__API_HTTP_STATUS://;s/__//')
        BASELINE_BODY=$(printf '%s' "$out" | sed '$d')
        case "$BASELINE_BODY" in
            *"requires the leader node"*) log_info "baseline attempt ${attempt}: SchemaNotLeader at ${endpoint}; retrying against the leader"; sleep "${BASELINE_RETRY_SLEEP:-1}" ;;
            *) return 0 ;;
        esac
    done
}

_expected_conflict_detail() {
    printf "Baseline conflict for datasource '%s': versioned migrations already applied up to version %s" "$DATASOURCE" "$1"
}

# Premise + contract: an already-migrated datasource refuses baseline with 409 naming the applied
# version. A 2xx here means the datasource was NOT migrated — the premise changed (e.g. Step 7 no
# longer pre-deploys test-persistence), so the test fails loudly instead of passing on a new path.
test_baseline_refused_on_migrated_datasource() {
    if [ -z "$DATASOURCE" ]; then
        log_fail "DATASOURCE empty — discovery in test_cluster_ready failed; cannot run baseline"
        return 1
    fi
    if ! wait_for "datasource ${DATASOURCE} migrated (currentVersion >= 900)" _migrated 60; then
        log_fail "PREMISE: ${DATASOURCE} not migrated (currentVersion=$(_current_version)); this suite asserts the already-migrated conflict contract"
        return 1
    fi
    local v
    v=$(_current_version)
    printf '%s' "$v" > "$VERSION_FILE"
    baseline_post
    if [ "$BASELINE_STATUS" != "409" ]; then
        log_fail "baseline of migrated ${DATASOURCE} (version ${v}) returned status=${BASELINE_STATUS:-none}, expected 409: $(printf '%s' "$BASELINE_BODY" | head -c 200)"
        return 1
    fi
    local want
    want=$(_expected_conflict_detail "$v")
    case "$BASELINE_BODY" in
        *"$want"*) log_pass "Baseline refused with 409 naming applied version ${v}" ;;
        *) log_fail "409 detail does not name the applied version ${v}: $(printf '%s' "$BASELINE_BODY" | head -c 300)"; return 1 ;;
    esac
}

# After the refusal the status must still be a healthy, acknowledged one (not FAILED/UNKNOWN).
test_schema_status_after_refused_baseline() {
    if [ -z "$DATASOURCE" ]; then
        log_fail "DATASOURCE empty — discovery failed"
        return 1
    fi
    local status status_field
    status=$(schema_status "$DATASOURCE")
    status_field=$(json_value "$status" "status")
    case "${status_field:-}" in
        ""|UNKNOWN|FAILED) log_fail "Schema status after refused baseline is unhealthy: status=${status_field:-<empty>}"; return 1 ;;
        *) log_pass "Schema status after refused baseline: status=${status_field}" ;;
    esac
}

# Strict: slices must remain active after baselining (baseline must not destabilise
# the cluster). slices_total_instances() is real cluster state.
test_slices_active_after_refused_baseline() {
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices still active after refused baseline: ${instances} instances"
}

# Repeatability: a second baseline call gets the IDENTICAL refusal (same status, same detail).
test_refused_baseline_is_repeatable() {
    if [ -z "$DATASOURCE" ]; then
        log_fail "DATASOURCE empty — discovery in test_cluster_ready failed; cannot run baseline"
        return 1
    fi
    local v
    v=$(_recorded_version)
    if [ -z "$v" ]; then
        log_fail "no recorded pre-baseline version (earlier test did not run to completion)"
        return 1
    fi
    baseline_post
    if [ "$BASELINE_STATUS" != "409" ]; then
        log_fail "second baseline of ${DATASOURCE} returned status=${BASELINE_STATUS:-none}, expected the identical 409: $(printf '%s' "$BASELINE_BODY" | head -c 200)"
        return 1
    fi
    case "$BASELINE_BODY" in
        *"$(_expected_conflict_detail "$v")"*) log_pass "Second baseline: identical 409 (version ${v})" ;;
        *) log_fail "second 409 differs from the first: $(printf '%s' "$BASELINE_BODY" | head -c 300)"; return 1 ;;
    esac
}

# The refusals must not have moved the datasource.
test_version_unchanged_after_refused_baselines() {
    local before now
    before=$(_recorded_version)
    now=$(_current_version)
    if [ -z "$before" ] || [ "$before" != "$now" ]; then
        log_fail "currentVersion changed across refused baselines: before=${before:-<none>} now=${now:-<none>}"
        return 1
    fi
    log_pass "currentVersion unchanged (${now}) after two refused baselines"
}

test_cluster_healthy_after_refused_baseline() {
    assert_cluster_healthy "Cluster healthy after refused baselines"
}

run_test "Cluster ready" test_cluster_ready
run_test "Baseline refused on migrated datasource (409)" test_baseline_refused_on_migrated_datasource
run_test "Schema status after refused baseline" test_schema_status_after_refused_baseline
run_test "Slices active after refused baseline" test_slices_active_after_refused_baseline
run_test "Refused baseline is repeatable (identical 409)" test_refused_baseline_is_repeatable
run_test "Version unchanged after refused baselines" test_version_unchanged_after_refused_baselines
run_test "Healthy after refused baselines" test_cluster_healthy_after_refused_baseline
print_summary
