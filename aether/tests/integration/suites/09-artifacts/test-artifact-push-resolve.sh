#!/bin/bash
# test-artifact-push-resolve.sh — Push artifact via management API, resolve, verify checksum
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

ARTIFACT_NAME="${ARTIFACT_NAME:-integration-test-artifact}"
ARTIFACT_VERSION="${ARTIFACT_VERSION:-1.0.0}"
ARTIFACT_PATH="/repository/${ARTIFACT_NAME}/${ARTIFACT_VERSION}"
TMPDIR="${TMPDIR:-/tmp}"
PUSH_FILE="${TMPDIR}/artifact-push-$$.bin"
RESOLVE_FILE="${TMPDIR}/artifact-resolve-$$.bin"

cleanup_temp() {
    rm -f "$PUSH_FILE" "$RESOLVE_FILE"
}
trap cleanup_temp EXIT

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_generate_artifact() {
    dd if=/dev/urandom of="$PUSH_FILE" bs=1024 count=4 2>/dev/null
    local size
    size=$(wc -c < "$PUSH_FILE" | tr -d ' ')
    assert_gt "$size" "0" "Generated test artifact (${size} bytes)"
}

test_push_artifact() {
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST \
        -H "X-API-Key: ${API_KEY}" \
        -H "Content-Type: application/octet-stream" \
        --data-binary "@${PUSH_FILE}" \
        "${CLUSTER_ENDPOINT}${ARTIFACT_PATH}")
    if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
        log_pass "Push artifact returned ${status}"
        return 0
    fi
    log_fail "Push artifact returned ${status} (expected 2xx)"
    return 1
}

test_resolve_artifact() {
    local status
    status=$(curl -s -o "$RESOLVE_FILE" -w "%{http_code}" \
        -H "X-API-Key: ${API_KEY}" \
        "${CLUSTER_ENDPOINT}${ARTIFACT_PATH}")
    if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
        log_pass "Resolve artifact returned ${status}"
        return 0
    fi
    log_fail "Resolve artifact returned ${status} (expected 2xx)"
    return 1
}

test_checksum_matches() {
    local push_sha resolve_sha
    push_sha=$(shasum -a 256 "$PUSH_FILE" | awk '{print $1}')
    resolve_sha=$(shasum -a 256 "$RESOLVE_FILE" | awk '{print $1}')
    assert_eq "$resolve_sha" "$push_sha" "SHA-256 checksum matches: ${push_sha}"
}

test_cluster_healthy_after_artifact_ops() {
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Cluster healthy after artifact operations"
}

run_test "Cluster ready" test_cluster_ready
run_test "Generate artifact" test_generate_artifact
run_test "Push artifact" test_push_artifact
run_test "Resolve artifact" test_resolve_artifact
run_test "Checksum matches" test_checksum_matches
run_test "Healthy after artifact ops" test_cluster_healthy_after_artifact_ops
print_summary
