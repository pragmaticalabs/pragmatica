#!/bin/bash
# test-03-scale-down.sh — Scale 7 -> 5 under load
# Runs after scale-up (cluster restored to 5 nodes)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

LOAD_RPS="${LOAD_RPS:-5}"
LOAD_DURATION="${LOAD_DURATION:-180}"
# Operational event tier — see aether/docs/specs/test-readiness-contract.md §4 (2.0%).
# Single moderate disruption (one node drained); small percentage lost during cutover window.
MAX_ERROR_RATE="${MAX_ERROR_RATE:-2.0}"

# No-data-loss marker — pushed before scale-down, resolved after. Files are
# shared across test_* functions via fixed paths (each run_test forks a subshell,
# so in-memory state can't cross function boundaries).
TMPDIR="${TMPDIR:-/tmp}"
MARKER_PUSH_FILE="${TMPDIR}/aether-test-03-marker-$$.bin"
MARKER_SHA_FILE="${TMPDIR}/aether-test-03-marker-$$.sha"
MARKER_PATH_FILE="${TMPDIR}/aether-test-03-marker-$$.path"
MARKER_RESOLVE_FILE="${TMPDIR}/aether-test-03-marker-$$.resolved.bin"

cleanup_marker() {
    rm -f "$MARKER_PUSH_FILE" "$MARKER_SHA_FILE" "$MARKER_PATH_FILE" "$MARKER_RESOLVE_FILE"
}
trap cleanup_marker EXIT

test_seed_config() {
    wait_for_cluster_ready 60
    wait_for_leader 60
    seed_cluster_config
    # Wait for generation to quiesce after previous suite's scale-down — ensures
    # leadership is stable before issuing scale operations (avoids "quorum unavailable"
    # on the first scale call during a brief re-election window).
    await_generation_quiesced "${CLUSTER_ENDPOINT}" "current" 60 || true
}

test_seed_marker() {
    # Push a unique random artifact through the management API before any
    # scaling happens. The repository path is unique per test process so reruns
    # never collide. Persisting the SHA-256 + path to disk (rather than env
    # vars) is mandatory: each run_test invocation forks a subshell, so test_
    # functions cannot share in-memory state.
    dd if=/dev/urandom of="$MARKER_PUSH_FILE" bs=1024 count=4 2>/dev/null
    local push_sha
    push_sha=$(shasum -a 256 "$MARKER_PUSH_FILE" | awk '{print $1}')
    printf '%s' "$push_sha" > "$MARKER_SHA_FILE"

    # Unique coordinates: PID + epoch encoded as the qualifier suffix
    # (groupId / artifactId / 1.0.0-<pid>-<epoch> / filename). Version requires
    # numeric major.minor.patch; uniqueness lives in the qualifier, which
    # accepts dashes and digits per QUALIFIER_PATTERN.
    local marker_version="1.0.0-$$-$(date +%s)"
    local marker_path="/repository/org/test/scale-down-marker/${marker_version}/marker.bin"
    printf '%s' "$marker_path" > "$MARKER_PATH_FILE"

    local status
    status=$(curl -sk -o /dev/null -w "%{http_code}" \
        -X PUT \
        -H "X-API-Key: ${API_KEY}" \
        -H "Content-Type: application/octet-stream" \
        --data-binary "@${MARKER_PUSH_FILE}" \
        "${CLUSTER_ENDPOINT}${marker_path}")
    # 200 (not 201): a stored artifact PUT returns the deploy JSON ("uploaded"/"already-present"),
    # same as .jar PUTs. The old 201 reflected the now-fixed bug where non-.jar PUTs were discarded.
    assert_eq "$status" "200" "Marker PUT stored, returned 200 (sha=${push_sha:0:12}…)"
}

test_scale_up_to_7() {
    log_info "Scaling up to 7 nodes first"
    scale_cluster 7
    # Fast-poll variant (see lib/cluster.sh:wait_for_node_count_fast) — avoids
    # the CLI/double-curl per-iter cost that pushed scale-up past 300s on
    # Hetzner remote even when the cluster was actually at 7.
    wait_for_node_count_fast 7 180
    local count
    count=$(cluster_member_count)
    assert_eq "$count" "7" "Scaled to 7 nodes"
}

test_scale_down_under_load() {
    # Production-like load: hit an app-port slice endpoint (test-echo blueprint
    # deployed by suite harness) rather than the synthetic /health/live. Exercises
    # the slice routing table that gets republished on every generation advance
    # during a scale-down — masked by /health/live which is a node-local probe.
    start_load "$LOAD_RPS" "$LOAD_DURATION" "GET" "/api/echo/health"
    sleep 5

    # Scale down to 5
    log_info "Scaling down to 5 under load"
    scale_cluster 5

    # Wait for scale-down (fast poll — see test-02-scale-up.sh comment)
    wait_for_node_count_fast 5 180

    # Wait for load to complete
    for pid in "${LOAD_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    local result
    result=$(stop_load)
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Scale-down error rate < ${MAX_ERROR_RATE}%"
}

test_5_nodes_healthy() {
    local count
    count=$(cluster_member_count)
    assert_eq "$count" "5" "5 nodes present after scale-down"
    assert_cluster_healthy "Cluster healthy at 5 nodes"
}

test_no_data_loss() {
    # Resolve the marker artifact pushed in test_seed_marker (pre-scale-down)
    # and verify byte-for-byte equality via SHA-256. This is the actual
    # no-data-loss contract: the artifact's bytes round-trip through the DHT
    # across the 7 -> 5 topology change. The previous implementation
    # (assert_ne "$(cluster_events)" "") was a tautology — the events stream
    # is always non-empty, so it asserted nothing about persisted data.

    # Pre-conditions: the seed step must have run and persisted state to disk
    # (each test_* runs in its own subshell, so we communicate via files).
    if [ ! -s "$MARKER_PUSH_FILE" ] || [ ! -s "$MARKER_SHA_FILE" ] || [ ! -s "$MARKER_PATH_FILE" ]; then
        log_fail "Marker state missing — test_seed_marker did not run or failed to persist state"
        return 1
    fi

    local expected_sha marker_path
    expected_sha=$(cat "$MARKER_SHA_FILE")
    marker_path=$(cat "$MARKER_PATH_FILE")

    # Refresh the entry-point — the original CLUSTER_ENDPOINT may now be a
    # drained node. _refresh_mgmt_entry_point rotates the exported var to any
    # live core node and returns non-zero only when every probed port is dead.
    _refresh_mgmt_entry_point || true

    # Resolve from the cluster after scale-down. Capture HTTP status separately
    # from body so we can hard-fail on non-2xx (vs silently passing on a 404
    # body that happens to be non-empty — exactly the trap the linter R3 catches).
    local status
    status=$(curl -sk -o "$MARKER_RESOLVE_FILE" -w "%{http_code}" \
        -H "X-API-Key: ${API_KEY}" \
        "${CLUSTER_ENDPOINT}${marker_path}")
    assert_eq "$status" "200" "Marker GET returned 200 after scale-down (path=${marker_path})" || return 1

    # Size sanity (4096 bytes seeded by dd). Catches the case where the cluster
    # returns 200 with an empty/truncated body — not strictly required given
    # the SHA-256 check, but yields a clearer failure mode.
    local resolved_size expected_size
    resolved_size=$(wc -c < "$MARKER_RESOLVE_FILE" | tr -d ' ')
    expected_size=$(wc -c < "$MARKER_PUSH_FILE" | tr -d ' ')
    assert_eq "$resolved_size" "$expected_size" "Resolved size matches pushed size (${expected_size} bytes)" || return 1

    # The non-fakeable assertion: SHA-256 equality. If the DHT lost or corrupted
    # the artifact across the topology change, this check fails.
    local resolve_sha
    resolve_sha=$(shasum -a 256 "$MARKER_RESOLVE_FILE" | awk '{print $1}')
    assert_eq "$resolve_sha" "$expected_sha" "Marker SHA-256 survived 7->5 scale-down"
}

run_test "Seed cluster config" test_seed_config
run_test "Seed no-data-loss marker" test_seed_marker
run_test "Scale up to 7" test_scale_up_to_7
run_test "Scale down 7 -> 5 under load" test_scale_down_under_load
run_test "5 nodes healthy" test_5_nodes_healthy
run_test "No data loss" test_no_data_loss
print_summary
