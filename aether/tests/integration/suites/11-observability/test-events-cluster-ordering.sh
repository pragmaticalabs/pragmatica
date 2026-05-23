#!/bin/bash
# test_events_cluster_ordering.sh — RC1 Step 1 cluster-scoped event log validation.
#
# Generates events from each node concurrently (POST /api/alerts/inject from every
# node), polls /api/events from each node, asserts every node sees the same ordered
# subsequence — the OB1 alert/trace race + OB2 NODE_FAILED skew should turn green
# once the replicated event log is in place.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

# Marker prefix lets us filter the cluster's event stream down to just our injected
# entries (the stream also contains LEADER_ELECTED, NODE_JOINED, etc. from the
# cluster-formation period).
MARKER="cluster-order-$$-$(date +%s)"

test_cluster_ready() {
    wait_for_cluster_ready 60
    log_pass "Cluster ready for event-ordering test"
}

# Inject events from every node round-robin so the producer set is distributed.
# `/api/alerts/inject` is the documented test injection endpoint already used by
# test-alerts.sh; alerts are surfaced as cluster events with the alert's name
# embedded in details.
test_inject_events_round_robin() {
    local injected=0
    for i in $(seq 0 $((NODE_COUNT - 1))); do
        local port=$((MGMT_PORT + i))
        local node_endpoint="http://${TARGET_HOST}:${port}"
        for j in $(seq 1 3); do
            local body
            body="{\"name\":\"${MARKER}-n${i}-e${j}\",\"severity\":\"WARNING\",\"message\":\"ordering-probe\",\"metric\":\"order.test\",\"value\":1.0}"
            if curl -sfk -m 5 -H "X-API-Key: ${API_KEY}" \
                    -H "Content-Type: application/json" \
                    -X POST -d "${body}" \
                    "${node_endpoint}/api/alerts/inject" >/dev/null 2>&1; then
                injected=$((injected + 1))
            fi
        done
    done
    if [ "${injected}" -lt $((NODE_COUNT * 2)) ]; then
        log_fail "Round-robin injection produced only ${injected} accepted events (expected ~${NODE_COUNT}*3)"
        return 1
    fi
    log_pass "Injected ${injected} alert events round-robin across ${NODE_COUNT} nodes"
}

# Give the replicated log a moment to fan out + the materialised-view subscribers
# on every node a chance to catch up.
test_wait_for_replication() {
    local deadline=$((SECONDS + 15))
    while [ $SECONDS -lt $deadline ]; do
        local port=$((MGMT_PORT + 0))
        local events
        events=$(curl -sfk -m 5 -H "X-API-Key: ${API_KEY}" \
                        "http://${TARGET_HOST}:${port}/api/events" 2>/dev/null || true)
        if printf '%s' "${events}" | grep -q "${MARKER}"; then
            log_pass "Replication confirmed (marker visible on node 0)"
            return 0
        fi
        sleep 1
    done
    log_pass "Replication window elapsed (15s — marker not yet visible; order assertion follows)"
}

# Read /api/events from every node, extract just the marker-bearing entries, and
# assert all nodes agree on the same ordered subsequence. We compare summaries +
# originEpoch/originSeq stamps that the new aggregator's `projectToEvent` adds
# to details.
#
# Tolerance: the test passes if every node sees the SAME set of marker events
# in the SAME order (subsequence match, ignoring any cluster-formation noise).
test_all_nodes_agree_on_order() {
    local reference=""
    for i in $(seq 0 $((NODE_COUNT - 1))); do
        local port=$((MGMT_PORT + i))
        local node_endpoint="http://${TARGET_HOST}:${port}"
        local events
        if ! events=$(curl -sfk -m 5 -H "X-API-Key: ${API_KEY}" \
                            "${node_endpoint}/api/events" 2>/dev/null); then
            log_fail "GET /api/events failed on node ${i} (port=${port})"
            return 1
        fi
        # Extract marker-bearing event names from the details.name field (where the
        # alert name — which carries the MARKER — is stored), preserving array order.
        local marker_summaries
        marker_summaries=$(printf '%s' "${events}" \
            | grep -oE "\"name\"[[:space:]]*:[[:space:]]*\"[^\"]*${MARKER}[^\"]*\"" \
            | sed -E "s/.*\"([^\"]*${MARKER}[^\"]*)\"$/\1/" \
            || true)
        if [ -z "${marker_summaries}" ]; then
            log_fail "Node ${i} returned no marker-bearing events (marker=${MARKER})"
            return 1
        fi
        if [ -z "${reference}" ]; then
            reference="${marker_summaries}"
            log_pass "Node ${i} reference order: $(echo "${marker_summaries}" | wc -l | tr -d ' ') events"
        else
            if [ "${marker_summaries}" != "${reference}" ]; then
                log_fail "Node ${i} marker-event order diverges from reference"
                printf 'REFERENCE:\n%s\nNODE %d:\n%s\n' "${reference}" "${i}" "${marker_summaries}" >&2
                return 1
            fi
        fi
    done
    log_pass "All ${NODE_COUNT} nodes agree on marker-event order"
}

test_cluster_healthy_after() {
    assert_cluster_healthy "Cluster healthy after event-ordering test"
}

run_test "Cluster ready"                test_cluster_ready
run_test "Inject events round-robin"    test_inject_events_round_robin
run_test "Wait for replication"         test_wait_for_replication
run_test "All nodes agree on order"     test_all_nodes_agree_on_order
run_test "Healthy after test"           test_cluster_healthy_after
print_summary
