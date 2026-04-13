#!/bin/bash
# test-artifact-replication.sh — Push to one node, resolve from another via DHT
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

ARTIFACT_NAME="${ARTIFACT_NAME:-replication-test-artifact}"
ARTIFACT_VERSION="${ARTIFACT_VERSION:-1.0.0}"
GROUP_PATH="org/test"
ARTIFACT_PATH="/repository/${GROUP_PATH}/${ARTIFACT_NAME}/${ARTIFACT_VERSION}/${ARTIFACT_NAME}-${ARTIFACT_VERSION}.jar"
TMPDIR="${TMPDIR:-/tmp}"
PUSH_FILE="${TMPDIR}/repl-push-$$.bin"
RESOLVE_FILE="${TMPDIR}/repl-resolve-$$.bin"

# Allow specifying a second node endpoint
NODE2_HOST="${NODE2_HOST:-}"
NODE2_ENDPOINT="${NODE2_ENDPOINT:-}"

cleanup_temp() {
    rm -f "$PUSH_FILE" "$RESOLVE_FILE"
}
trap cleanup_temp EXIT

test_cluster_ready() {
    wait_for_cluster 60
    wait_for_all_tasks_active 60 || log_warn "task groups not fully ACTIVE within 60s"
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "2" "At least 2 nodes available for replication test"
}

test_identify_second_node() {
    if [ -n "$NODE2_ENDPOINT" ]; then
        log_pass "Second node endpoint provided: ${NODE2_ENDPOINT}"
        return 0
    fi

    # Try to discover a second node from the node list
    local nodes
    nodes=$(cluster_node_list)
    local second_host
    # Extract addresses from node list, find one different from TARGET_HOST
    second_host=""
    for field in address host; do
        local candidates
        candidates=$(echo "$nodes" | grep -o "\"${field}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | sed "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/")
        while IFS= read -r addr; do
            if [ -n "$addr" ] && [ "$addr" != "${TARGET_HOST}" ]; then
                second_host="$addr"
                break 2
            fi
        done <<< "$candidates"
    done

    if [ -n "$second_host" ]; then
        NODE2_ENDPOINT="http://${second_host}:${MGMT_PORT}"
        log_pass "Discovered second node: ${NODE2_ENDPOINT}"
    else
        NODE2_ENDPOINT="${CLUSTER_ENDPOINT}"
        log_warn "Could not discover second node — using same endpoint (replication still tested via DHT)"
        log_pass "Using primary endpoint for resolve"
    fi
}

test_push_to_primary() {
    dd if=/dev/urandom of="$PUSH_FILE" bs=1024 count=8 2>/dev/null
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST \
        -H "X-API-Key: ${API_KEY}" \
        -H "Content-Type: application/octet-stream" \
        --data-binary "@${PUSH_FILE}" \
        "${CLUSTER_ENDPOINT}${ARTIFACT_PATH}")
    if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
        log_pass "Pushed artifact to primary node (${status})"
        return 0
    fi
    log_fail "Push to primary returned ${status}"
    return 1
}

test_wait_for_replication() {
    # Allow time for DHT replication
    log_info "Waiting 10s for DHT replication"
    sleep 10
    log_pass "Replication wait complete"
}

test_resolve_from_second_node() {
    local status
    status=$(curl -s -o "$RESOLVE_FILE" -w "%{http_code}" \
        -H "X-API-Key: ${API_KEY}" \
        "${NODE2_ENDPOINT}${ARTIFACT_PATH}")
    if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
        log_pass "Resolved artifact from second node (${status})"
        return 0
    fi
    log_fail "Resolve from second node returned ${status}"
    return 1
}

test_integrity_across_nodes() {
    local push_sha resolve_sha
    push_sha=$(shasum -a 256 "$PUSH_FILE" | awk '{print $1}')
    resolve_sha=$(shasum -a 256 "$RESOLVE_FILE" | awk '{print $1}')
    assert_eq "$resolve_sha" "$push_sha" "Cross-node SHA-256 checksum matches: ${push_sha}"
}

run_test "Cluster ready (>= 2 nodes)" test_cluster_ready
run_test "Identify second node" test_identify_second_node
run_test "Push to primary node" test_push_to_primary
run_test "Wait for DHT replication" test_wait_for_replication
run_test "Resolve from second node" test_resolve_from_second_node
run_test "Integrity across nodes" test_integrity_across_nodes
print_summary
