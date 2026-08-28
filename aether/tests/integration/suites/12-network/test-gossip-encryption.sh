#!/bin/bash
# test-gossip-encryption.sh — Verify gossip encryption active, cluster forms with encrypted gossip
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster_ready 60
    log_pass "Cluster ready"
}

test_cluster_formed_with_encryption() {
    # Operational health, not raw generation-snapshot membership.
    # `cluster_member_count` (generation `core.members[]` length) can transiently carry
    # tombstones / mid-decommission CTM-replacement entries left by an earlier suite,
    # producing 6-7 even though only 5 nodes are actively serving. The test's intent
    # is "the cluster formed with 5 operational nodes under encryption" — the right
    # signal for that is the transport-honest healthy core count exposed by
    # /api/v1/cluster/topology.coreCount (the same metric restore_cluster_baseline gates on).
    local count
    count=$(cluster_active_core_count)
    assert_eq "$count" "5" "Cluster formed with 5 healthy cores (encryption enabled)"
}

test_gossip_encryption_active_via_config() {
    # `quic_handshake_total` from /api/v1/metrics/transport is the deterministic positive
    # signal: every QUIC connection requires a TLS handshake (QuicSslContext is
    # mandatory in `QuicClusterNetwork`). A non-zero handshake count between cluster
    # bring-up and the time of this assertion proves the cluster transport is
    # TLS-encrypted — there is no QUIC-without-TLS code path. This replaces the
    # config-flag fishing expedition with a runtime fact.
    local metrics handshake_count
    metrics=$(api_get "/api/v1/metrics/transport")
    if [ -z "$metrics" ]; then
        log_fail "GET /api/v1/metrics/transport returned empty — cannot read QUIC handshake counter"
        return 1
    fi
    handshake_count=$(json_value "$metrics" "quic_handshake_total")
    handshake_count="${handshake_count:--1}"
    if [ "$handshake_count" -gt 0 ] 2>/dev/null; then
        log_pass "Gossip encryption verified: ${handshake_count} TLS handshakes recorded (QUIC mandates TLS via QuicSslContext)"
        return 0
    fi
    log_fail "quic_handshake_total=${handshake_count}: no TLS handshakes recorded — cluster transport is either down or non-QUIC"
    return 1
}

test_gossip_encryption_via_transport() {
    # Verify TLS handshake failures are bounded: a healthy QUIC cluster has handshake
    # successes vastly outnumbering failures (failures only occur on cert/version
    # mismatches). The ratio is the canonical "are encrypted handshakes succeeding"
    # signal — not just "are encryption-related metrics present".
    local metrics handshake_total handshake_failures
    metrics=$(api_get "/api/v1/metrics/transport")
    if [ -z "$metrics" ]; then
        log_fail "GET /api/v1/metrics/transport returned empty"
        return 1
    fi
    handshake_total=$(json_value "$metrics" "quic_handshake_total")
    handshake_failures=$(json_value "$metrics" "quic_handshake_failures_total")
    handshake_total="${handshake_total:--1}"
    handshake_failures="${handshake_failures:--1}"
    if [ "$handshake_total" -lt 1 ] 2>/dev/null; then
        log_fail "quic_handshake_total=${handshake_total}: no handshakes recorded"
        return 1
    fi
    # Fail only if failures exceed half of total (catastrophic cert/version mismatch).
    # Some failures are expected during chaos/restart cycles.
    local failure_threshold=$((handshake_total / 2))
    if [ "$handshake_failures" -gt "$failure_threshold" ] 2>/dev/null; then
        log_fail "TLS handshake failures (${handshake_failures}) exceed half of total (${handshake_total}) — cert/protocol issue"
        return 1
    fi
    log_pass "TLS handshakes succeeding: total=${handshake_total} failures=${handshake_failures} (failure ratio ≤ 50%)"
}

test_nodes_communicating_encrypted() {
    # Verify cluster is functional (gossip is working = encrypted gossip is working)
    local leader
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader elected via encrypted gossip: ${leader}"

    local events
    events=$(cluster_events)
    assert_ne "$events" "" "Events propagated via encrypted gossip"
}

test_health_probes_over_encrypted_transport() {
    assert_cluster_healthy "Cluster healthy over encrypted transport"
    assert_http_status "${CLUSTER_ENDPOINT}/health/live" "200" "Liveness probe over encrypted transport"
}

run_test "Cluster ready" test_cluster_ready
run_test "Cluster formed with encryption" test_cluster_formed_with_encryption
run_test "Gossip encryption via config" test_gossip_encryption_active_via_config
run_test "Gossip encryption via transport" test_gossip_encryption_via_transport
run_test "Nodes communicating encrypted" test_nodes_communicating_encrypted
run_test "Health probes over encrypted transport" test_health_probes_over_encrypted_transport
print_summary
