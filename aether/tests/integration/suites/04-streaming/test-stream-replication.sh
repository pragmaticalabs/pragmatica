#!/bin/bash
# test-stream-replication.sh — Verify stream replication across nodes
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

STREAM_NAME="${STREAM_NAME:-repl-test-events}"
REPLICATION_FACTOR=2

test_cluster_ready() {
    wait_for_cluster_ready 60
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

    # Compose the alt node's management endpoint. Cloud and docker-remote layouts
    # differ: cloud has one VM per node with mgmt on a fixed port (CLOUD_MGMT_PORT,
    # default 8080), so the alt host is the node's own public IP. Docker-remote
    # collocates all 5 nodes on TARGET_HOST and host-maps a per-node port range
    # (MGMT_PORT + index). Mirrors the convention in `reassign_task_group`
    # (lib/cluster.sh:1402-1407).
    local alt_endpoint
    if [ "${ENV_TYPE:-}" = "cloud" ]; then
        local alt_ip
        if ! alt_ip=$(cloud_public_ip "$alt_node"); then
            skip_test "Read from non-governor" "cloud_public_ip lookup failed for ${alt_node}"
            return 0
        fi
        alt_endpoint="http://${alt_ip}:${CLOUD_MGMT_PORT:-8080}"
    else
        local node_index
        node_index=$(echo "$alt_node" | grep -o '[0-9]*$')
        local alt_port=$((MGMT_PORT + node_index - 1))
        alt_endpoint="http://${TARGET_HOST}:${alt_port}"
    fi

    local result rc
    # `_api_call`-style error capture so a connection-refused at the alt endpoint
    # surfaces as a warn rather than silently collapsing to empty body (which the
    # `assert_ne` below would conflate with "stream metadata absent").
    result=$(curl -sf -H "X-API-Key: ${API_KEY}" --connect-timeout 5 \
                  "${alt_endpoint}/api/streams/${STREAM_NAME}" 2>/dev/null) && rc=0 || rc=$?
    if [ "$rc" -ne 0 ] && [ -z "$result" ]; then
        log_warn "Read from non-governor: curl rc=${rc} from ${alt_endpoint}/api/streams/${STREAM_NAME} (empty body — treating as missing metadata for assertion)"
    fi

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
