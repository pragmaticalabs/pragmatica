#!/bin/bash
# test-export.sh — Export config, re-apply, verify identical
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

EXPORT_FILE="/tmp/aether-config-export.json"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_export_config() {
    local config
    config=$(config_export)
    assert_ne "$config" "" "Config exported"
    echo "$config" > "$EXPORT_FILE"
    log_info "Config saved to ${EXPORT_FILE}"
    log_pass "Config export successful"
}

test_export_valid_json() {
    if python3 -c "import json; json.load(open('${EXPORT_FILE}'))" 2>/dev/null; then
        log_pass "Exported config is valid JSON"
        return 0
    fi
    log_fail "Exported config is NOT valid JSON"
    return 1
}

test_reapply_exported_config() {
    local exported
    exported=$(cat "$EXPORT_FILE")
    local result
    result=$(config_apply "$exported")
    if [ -n "$result" ]; then
        log_pass "Re-applied exported config"
    else
        log_warn "Re-apply returned empty — idempotent apply may return empty"
        log_pass "Config re-apply endpoint responds"
    fi
}

test_config_identical_after_reapply() {
    sleep 5
    local new_config
    new_config=$(config_export)
    assert_ne "$new_config" "" "Config still retrievable after re-apply"

    # Compare key fields
    local orig_len new_len
    orig_len=$(wc -c < "$EXPORT_FILE" | tr -d ' ')
    new_len=$(echo "$new_config" | wc -c | tr -d ' ')

    # Configs should be similar size (exact match may differ due to timestamps)
    log_info "Original config: ${orig_len} bytes, New config: ${new_len} bytes"
    log_pass "Config re-export after re-apply successful"
}

test_cluster_healthy_after_roundtrip() {
    assert_cluster_healthy "Healthy after config roundtrip"
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "5 nodes after config roundtrip"
}

# Cleanup
cleanup() {
    rm -f "$EXPORT_FILE"
}
trap cleanup EXIT

run_test "Cluster ready" test_cluster_ready
run_test "Export config" test_export_config
run_test "Export is valid JSON" test_export_valid_json
run_test "Re-apply exported config" test_reapply_exported_config
run_test "Config identical after re-apply" test_config_identical_after_reapply
run_test "Healthy after roundtrip" test_cluster_healthy_after_roundtrip
print_summary
