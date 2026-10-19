#!/bin/bash
# test-large-artifact.sh — Push and resolve large artifacts, verify integrity at various sizes
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

ARTIFACT_BASE="${ARTIFACT_BASE:-large-artifact-test}"
TMPDIR="${TMPDIR:-/tmp}"
MAX_SIZE_MB="${LARGE_ARTIFACT_MAX_MB:-5}"

cleanup_temp() {
    rm -f "${TMPDIR}"/large-artifact-*.bin
}
trap cleanup_temp EXIT

test_cluster_ready() {
    wait_for_cluster_ready 60
    wait_for_all_tasks_active 60 || log_warn "task groups not fully ACTIVE within 60s"
    log_pass "Cluster ready"
}

# curl_http_status <out_file> <curl args...> — prints the HTTP status ("000" when curl produced none).
# #2191: bounded by --connect-timeout/--max-time, and retried ONLY when curl itself failed (a lost link, a reset, a
# timeout: nonzero exit, status 000). Any HTTP answer, 4xx and 5xx included, is the server's verdict and is returned at
# once without a retry. The PUT targets a content-addressed path, so repeating it after a lost link is safe
# [design intent — unverified: a re-PUT of identical bytes answering 2xx has not been run against a cluster]. Every
# transport failure logs curl's exit code, so "link lost" and "server refused" read differently.
curl_http_status() {
    local out="$1"; shift
    local attempts="${LARGE_ARTIFACT_CURL_ATTEMPTS:-3}" i=1 status rc
    while :; do
        rc=0
        status=$(curl -s --connect-timeout "${LARGE_ARTIFACT_CONNECT_TIMEOUT_S:-10}" --max-time "${LARGE_ARTIFACT_MAX_TIME_S:-120}" \
                      -o "$out" -w "%{http_code}" "$@") || rc=$?
        if [ "$rc" -eq 0 ]; then
            printf '%s' "$status"
            return 0
        fi
        log_warn "curl transport failure on attempt ${i}/${attempts}: exit code ${rc}, HTTP status ${status:-000}" >&2
        if [ "$i" -ge "$attempts" ]; then
            printf '%s' "${status:-000}"
            return 0
        fi
        i=$((i + 1))
        sleep "${LARGE_ARTIFACT_RETRY_DELAY_S:-2}"
    done
}

push_and_verify_size() {
    local label="$1" size_kb="$2"
    local push_file="${TMPDIR}/large-artifact-push-${label}.bin"
    local resolve_file="${TMPDIR}/large-artifact-resolve-${label}.bin"

    # Generate
    dd if=/dev/urandom of="$push_file" bs=1024 count="$size_kb" 2>/dev/null
    local actual_size
    actual_size=$(wc -c < "$push_file" | tr -d ' ')
    log_info "Generated ${label} artifact: ${actual_size} bytes"

    # Push — must be 2xx. Use positive form so a non-numeric status (e.g. empty
    # string from a curl crash) hits the failure branch instead of silently
    # passing through `[ ... ] 2>/dev/null` arith errors.
    local push_status
    push_status=$(curl_http_status /dev/null \
        -X PUT \
        -H "X-API-Key: ${API_KEY}" \
        -H "Content-Type: application/octet-stream" \
        --data-binary "@${push_file}" \
        "${CLUSTER_ENDPOINT}/repository/org/test/${ARTIFACT_BASE}/${label}/${ARTIFACT_BASE}-${label}.jar")
    if ! { [ "$push_status" -ge 200 ] && [ "$push_status" -lt 300 ]; } 2>/dev/null; then
        log_fail "Push ${label} returned ${push_status} (expected 2xx)"
        return 1
    fi

    # Resolve — must be 2xx. Same positive-form rationale as the push check.
    local resolve_status
    resolve_status=$(curl_http_status "$resolve_file" \
        -H "X-API-Key: ${API_KEY}" \
        "${CLUSTER_ENDPOINT}/repository/org/test/${ARTIFACT_BASE}/${label}/${ARTIFACT_BASE}-${label}.jar")
    if ! { [ "$resolve_status" -ge 200 ] && [ "$resolve_status" -lt 300 ]; } 2>/dev/null; then
        log_fail "Resolve ${label} returned ${resolve_status} (expected 2xx)"
        return 1
    fi

    # Verify checksum
    local push_sha resolve_sha
    push_sha=$(shasum -a 256 "$push_file" | awk '{print $1}')
    resolve_sha=$(shasum -a 256 "$resolve_file" | awk '{print $1}')
    assert_eq "$resolve_sha" "$push_sha" "SHA-256 match for ${label} (${actual_size} bytes)"
}

test_64kb_boundary() {
    push_and_verify_size "1.0.64" 64
}

test_128kb() {
    push_and_verify_size "1.0.128" 128
}

test_1mb() {
    push_and_verify_size "1.1.0" 1024
}

test_5mb() {
    if [ "$MAX_SIZE_MB" -lt 5 ] 2>/dev/null; then
        log_warn "MAX_SIZE_MB=${MAX_SIZE_MB} — skipping 5MB test"
        log_pass "5MB test skipped by config"
        return 0
    fi
    push_and_verify_size "5.0.0" 5120
}

test_cluster_healthy_after_large_artifacts() {
    local health
    health=$(aether_field health status)
    assert_eq "$health" "healthy" "Cluster healthy after large artifact tests"
}

run_test "Cluster ready" test_cluster_ready
run_test "64KB boundary artifact" test_64kb_boundary
run_test "128KB artifact" test_128kb
run_test "1MB artifact" test_1mb
run_test "5MB artifact" test_5mb
run_test "Healthy after large artifacts" test_cluster_healthy_after_large_artifacts
print_summary
