#!/bin/bash
# test-stream-replication.sh — Verify stream replication across nodes
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

STREAM_NAME="${STREAM_NAME:-repl-test-events}"
REPLICATION_FACTOR=2

test_cluster_ready() {
    wait_for_cluster 60
    wait_for_all_tasks_active 60 || log_warn "task groups not fully ACTIVE within 60s"
    log_pass "Cluster ready"
}

test_create_stream() {
    local payload="{\"name\":\"${STREAM_NAME}\",\"partitions\":1}"
    local result
    result=$(api_post "/api/streams" "$payload")
    assert_contains "$result" "$STREAM_NAME" "Stream created: ${STREAM_NAME}"
}

test_publish_events_for_replication() {
    local success=0
    for i in $(seq 1 10); do
        local payload="{\"key\":\"repl-${i}\",\"data\":\"replicated-payload-${i}\",\"timestamp\":$(now_epoch)}"
        if stream_publish "$STREAM_NAME" "$payload" > /dev/null 2>&1; then
            success=$((success + 1))
        fi
    done
    assert_eq "$success" "10" "All 10 events published for replication test"
}

test_stream_visible_on_governor() {
    local info
    info=$(stream_info "$STREAM_NAME")
    assert_ne "$info" "" "Stream info available on governor node"
}

test_read_events_from_partition() {
    local result
    result=$(api_get "/api/streams/read/${STREAM_NAME}/0?from=0&max=10")
    # Empty response IS the failure mode — after publishing 10 events the read
    # endpoint must return a non-empty payload containing the "events" field.
    assert_ne "$result" "" "Read endpoint returns non-empty payload after publish"
    assert_contains "$result" "events" "Events readable from partition 0"
}

# Attempt to read stream data via a non-leader node.
# This exercises the replication path if governor-push replication is wired.
# If replication transport is not yet connected in AetherNode, we verify
# basic stream accessibility and skip the cross-node assertion gracefully.
test_read_from_non_governor_node() {
    local leader
    leader=$(cluster_leader)
    if [ -z "$leader" ]; then
        skip_test "Read from non-governor" "No leader detected"
        return 0
    fi

    local alt_node
    alt_node=$(pick_non_leader "$leader" 1)
    if [ -z "$alt_node" ]; then
        skip_test "Read from non-governor" "Could not pick alternate node"
        return 0
    fi

    # Determine the management port for the alternate node
    local node_index
    node_index=$(echo "$alt_node" | grep -o '[0-9]*$')
    local alt_port=$((MGMT_PORT + node_index - 1))
    local alt_endpoint="http://${TARGET_HOST}:${alt_port}"

    local result
    result=$(curl -sf -H "X-API-Key: ${API_KEY}" "${alt_endpoint}/api/streams/${STREAM_NAME}" 2>/dev/null) || true

    # Empty IS the failure mode — replication is the feature under test. If the
    # non-governor cannot serve stream metadata we have a real replication gap,
    # not a "not yet wired" excuse to log_warn and pass.
    assert_ne "$result" "" "Stream metadata reachable from non-governor ${alt_node}"
    assert_contains "$result" "$STREAM_NAME" "Stream metadata accessible from non-governor node"
}

test_stream_in_list_after_replication() {
    local streams
    streams=$(stream_list)
    assert_contains "$streams" "$STREAM_NAME" "Replicated stream visible in list"
}

run_test "Cluster ready" test_cluster_ready
run_test "Create stream with replication" test_create_stream
run_test "Publish 10 events" test_publish_events_for_replication
run_test "Stream visible on governor" test_stream_visible_on_governor
run_test "Read events from partition" test_read_events_from_partition
run_test "Read from non-governor node" test_read_from_non_governor_node
run_test "Stream in list after replication" test_stream_in_list_after_replication
print_summary
