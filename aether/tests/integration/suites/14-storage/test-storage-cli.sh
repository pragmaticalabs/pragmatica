#!/bin/bash
# test-storage-cli.sh — Verify storage CLI commands
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster_ready 60
    log_pass "Cluster ready"
}

# aether storage list — returns instances including the mandatory "artifacts" instance.
#
# Audit 2026-05-21 §1.16: previous form was `result=$(aether_failover storage list 2>/dev/null) || true`
# then `skip_test` on empty. That pattern masks a real CLI regression (binary crash, missing
# subcommand, transport failure) as "feature not configured" → suite is green even when the
# subsystem is broken. Per feedback_silent_stderr_is_a_trap: capture stderr and fail loudly.
test_cli_storage_list() {
    local output
    if ! output=$(aether_json storage list); then
        log_fail "aether storage list CLI failed: $(printf '%s' "$output" | head -c 300)"
        return 1
    fi
    if [ -z "$output" ]; then
        log_fail "aether storage list returned empty output (CLI present but produced no JSON)"
        return 1
    fi
    # The "artifacts" instance is mandatory for the artifact-repo service used by every deploy.
    # Match the JSON field as emitted by ClusterStorageInstanceSummary (`"name":"artifacts"`).
    if ! echo "$output" | grep -qE '"name"[[:space:]]*:[[:space:]]*"artifacts"'; then
        log_fail "aether storage list missing mandatory 'artifacts' instance: $(printf '%s' "$output" | head -c 300)"
        return 1
    fi
    local instance_count
    instance_count=$(echo "$output" | grep -cE '"name"[[:space:]]*:[[:space:]]*"' || true)
    log_pass "aether storage list returned ${instance_count} instance entries including artifacts"
}

# aether storage status artifacts — returns detail for the mandatory instance.
#
# Audit 2026-05-21 §1.16: previous form first discovered an instance name via REST and then
# `2>/dev/null) || true` + skip_test. Both halves were green-stickers. The artifacts instance
# is mandatory, so target it directly and demand success.
test_cli_storage_status() {
    local output
    if ! output=$(aether_json storage status artifacts); then
        log_fail "aether storage status artifacts CLI failed: $(printf '%s' "$output" | head -c 300)"
        return 1
    fi
    if [ -z "$output" ]; then
        log_fail "aether storage status artifacts returned empty output"
        return 1
    fi
    # ClusterStorageDetailResponse carries name + nodeCount + nodes. Assert all three are present
    # so a stray empty envelope or error payload that happens to be non-empty still fails.
    assert_contains "$output" "\"name\"" "storage status response includes name"
    assert_contains "$output" "\"nodeCount\"" "storage status response includes nodeCount"
    assert_contains "$output" "\"nodes\"" "storage status response includes nodes"
    log_pass "aether storage status artifacts returned cluster detail"
}

# aether storage list --format json — returns valid JSON.
#
# Audit 2026-05-21 §1.16 / §2.2 #25: previous regex `^\s*[{\[]` only checked the leading char
# of the response. A truncated/malformed `{"instances":...` would pass. Use the CLI's own JSON
# parser via `--field instances.0.name` to extract a known field — extraction succeeds iff the
# JSON parses cleanly AND has the expected shape. (Per feedback_minimize_dependencies, jq is
# forbidden; the project CLI is the right parser.)
test_cli_storage_list_json() {
    # First confirm the explicit --format json path runs and returns non-empty.
    local raw
    if ! raw=$(aether_json storage list); then
        log_fail "aether storage list --format json failed: $(printf '%s' "$raw" | head -c 300)"
        return 1
    fi
    if [ -z "$raw" ]; then
        log_fail "aether storage list --format json returned empty output"
        return 1
    fi
    # Re-invoke via aether_field (which uses --format value --field) to drive the CLI's JsonMapper.
    # If the upstream response is malformed, field extraction returns a non-zero exit + error
    # message on stderr — surfacing the regression we used to silently skip.
    local first_name
    if ! first_name=$(aether_field "storage list" "instances.0.name"); then
        log_fail "aether storage list --field instances.0.name failed: $(printf '%s' "$first_name" | head -c 300)"
        return 1
    fi
    if [ -z "$first_name" ]; then
        log_fail "aether storage list parsed but produced no first-instance name (instances[] empty or shape changed)"
        return 1
    fi
    log_pass "aether storage list --format json parses cleanly; first instance: ${first_name}"
}

run_test "Cluster ready" test_cluster_ready
run_test "CLI storage list" test_cli_storage_list
run_test "CLI storage status" test_cli_storage_status
run_test "CLI storage list JSON format" test_cli_storage_list_json
print_summary
