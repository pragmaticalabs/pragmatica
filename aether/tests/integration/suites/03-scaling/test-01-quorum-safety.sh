#!/bin/bash
# test-01-quorum-safety.sh — Verify rejection of unsafe scale operations
# Runs first: no actual scaling, just validates rejection of invalid requests
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_seed_config() {
    wait_for_cluster_ready 60
    wait_for_leader 60
    seed_cluster_config
}

test_initial_state() {
    local count
    count=$(cluster_member_count)
    assert_ge "$count" "3" "Initial: at least 3 nodes"
}

# Scale rejection tests hit the leader directly so the validator returns a proper 4xx.
# Two URL conventions:
#   - docker:  per-node mgmt ports are stacked at ${TARGET_HOST}:${MGMT_PORT}+${i}
#   - cloud:   each node has its own public IP; mgmt port is fixed (CLOUD_MGMT_PORT)
# Prints "<http status><TAB><problem detail>" ("000<TAB>" when no node answered).
direct_scale_response() {
    local body="$1"
    local urls=()
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        local leader leader_ip
        leader=$(cluster_leader)
        if [ -z "$leader" ] || [ "$leader" = "none" ]; then
            printf '000\t\n'
            return 0
        fi
        leader_ip=$(cloud_public_ip "$leader" 2>/dev/null) || { printf '000\t\n'; return 0; }
        urls=("http://${leader_ip}:${CLOUD_MGMT_PORT:-8080}/api/v1/cluster/scale")
    else
        for i in $(seq 0 $((NODE_COUNT - 1))); do
            urls+=("http://${TARGET_HOST}:$((MGMT_PORT + i))/api/v1/cluster/scale")
        done
    fi
    local url status body_file
    body_file=$(mktemp -t direct_scale.XXXXXX)
    for url in "${urls[@]}"; do
        status=$(curl -sk -o "$body_file" -w '%{http_code}' \
            -X POST \
            -H "X-API-Key: ${API_KEY}" \
            -H "Content-Type: application/json" \
            -d "$body" "$url")
        if [ "$status" != "000" ] && [ -n "$status" ]; then
            printf '%s\t%s\n' "$status" "$(_extract_problem_detail "$(head -c 500 "$body_file")")"
            rm -f "$body_file"
            return 0
        fi
    done
    rm -f "$body_file"
    printf '000\t\n'
}

# #1069: a refusal proves the validator ran only if the validator wrote it. These bodies were the
# pre-#581 `{"coreCount":N}` shape, which the request decoder refuses with HTTP 400
# `Type mismatch: expected int, got unknown` before any quorum or max check — so all three tests
# passed without the validator ever running. The detail must now name the validator's own refusal.
assert_scale_refused() {
    local name="$1" body="$2" expected_detail="$3"
    local response status detail
    response=$(direct_scale_response "$body")
    status="${response%%$'\t'*}"
    detail="${response#*$'\t'}"
    if ! [ "$status" -ge 400 ] 2>/dev/null; then
        log_fail "${name} was NOT rejected (status: ${status})"
        return 1
    fi
    if [[ "$detail" != *"$expected_detail"* ]]; then
        log_fail "${name} was rejected (status: ${status}) but NOT by the scale validator: expected a detail containing '${expected_detail}', got '${detail:-<none>}'"
        return 1
    fi
    log_pass "${name} rejected (status: ${status}, detail: ${detail})"
}

test_reject_scale_to_1() {
    assert_scale_refused "Scale to 1" '{"role":"core","count":1,"expectedVersion":0}' "Quorum safety violation"
}

test_reject_scale_to_2() {
    assert_scale_refused "Scale to 2" '{"role":"core","count":2,"expectedVersion":0}' "Quorum safety violation"
}

# 21, not 20: the validator refuses an even core count before it compares against coreMax, so 20 is
# refused as "Invalid core count" and never reaches the max check this test is named for. 21 is odd
# and above both seeded maxima (docker cluster-config.toml max = 15, cloud B coreMax = 9).
test_reject_scale_above_max() {
    assert_scale_refused "Scale to 21" '{"role":"core","count":21,"expectedVersion":0}' "Invalid core max"
}

test_cluster_unchanged() {
    local count
    count=$(cluster_member_count)
    assert_ge "$count" "3" "Cluster unchanged after rejected scale operations"
    assert_cluster_healthy "Cluster still healthy"
}

run_test "Seed cluster config" test_seed_config
run_test "Initial state" test_initial_state
run_test "Reject scale to 1" test_reject_scale_to_1
run_test "Reject scale to 2" test_reject_scale_to_2
run_test "Reject scale above max" test_reject_scale_above_max
run_test "Cluster unchanged" test_cluster_unchanged
print_summary
