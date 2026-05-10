#!/bin/bash
# topology.sh — Semantic topology helpers built on /api/events
#
# Replaces count-polling against /api/cluster/topology with event-driven waits.
# Rationale: auto-heal is fast; snapshot counts race with it. Events give us
# a stable, ordered record of what actually happened in the window.
#
# All helpers consume /api/events via `aether events --since <ISO-8601>`.
# Queries are pinned to a surviving node (via aether_failover → CLI) because
# each node maintains its own per-node event buffer.

LIB_DIR_TOPOLOGY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${LIB_DIR_TOPOLOGY}/common.sh"

# Current UTC timestamp in the ISO-8601 form accepted by /api/events?since=
# Usage: ts=$(topology_now)
topology_now() {
    date -u +%Y-%m-%dT%H:%M:%SZ
}

# Fetch raw events since baseline, UNIONED across all core nodes.
# Each node maintains a per-node ring buffer (notifications are local). The LB
# picks a random backend, so a single GET /api/events on the LB only sees one
# node's view — and if that node was the one we killed and restarted, the
# departure event isn't there. We query every direct node port and concatenate.
# Empty string for $since means "all buffered".
# Usage: topology_events_since "$baseline"
topology_events_since() {
    local since="${1:-}"
    local count="${NODE_COUNT:-5}"
    local merged=""
    local i base_url url events inner

    # Build the per-node base URL list. Two conventions:
    #   - docker/remote: every node shares TARGET_HOST, mgmt ports stack at MGMT_PORT+i
    #   - cloud:         each node has its own VM public IP at the fixed CLOUD_MGMT_PORT
    # Without the cloud branch, this loop hits non-existent ports on TARGET_HOST and
    # the merged result is empty → SWIM-departure waits time out without ever seeing
    # events that the cluster did emit.
    local base_urls=()
    if [ "${CLOUD_MODE:-false}" = "true" ] && command -v cloud_public_ip >/dev/null 2>&1; then
        local mgmt_port="${CLOUD_MGMT_PORT:-8080}"
        for i in $(seq 0 $((count - 1))); do
            local node_ip
            node_ip=$(cloud_public_ip "node-$((i + 1))" 2>/dev/null) || continue
            [ -z "$node_ip" ] && continue
            base_urls+=("http://${node_ip}:${mgmt_port}")
        done
    else
        local base_port="${MGMT_PORT:-5150}"
        for i in $(seq 0 $((count - 1))); do
            base_urls+=("http://${TARGET_HOST}:$((base_port + i))")
        done
    fi

    for base_url in "${base_urls[@]}"; do
        url="${base_url}/api/events"
        if [ -n "$since" ]; then
            url="${url}?since=${since}"
        fi
        events=$(curl -sfk -m 3 -H "X-API-Key: ${API_KEY}" "$url" 2>/dev/null) || continue
        if [ -z "$events" ] || [ "$events" = "[]" ]; then
            continue
        fi
        inner=$(printf '%s' "$events" | sed 's/^\[//; s/\]$//')
        if [ -z "$inner" ]; then
            continue
        fi
        if [ -n "$merged" ]; then
            merged="${merged},${inner}"
        else
            merged="$inner"
        fi
    done
    printf '[%s]' "$merged"
}

# Count events of a given type whose details.nodeId matches the given node.
# Uses regex that matches both orderings of type/nodeId within an event record.
# Usage: topology_count_node_events "$events_json" NODE_LEFT node-3
topology_count_node_events() {
    local events_json="$1" type="$2" node_id="$3"
    # Event records are flat JSON objects. Match type AND nodeId in any order.
    local pattern_a="\"type\":\"${type}\"[^}]*\"nodeId\":\"${node_id}\""
    local pattern_b="\"nodeId\":\"${node_id}\"[^}]*\"type\":\"${type}\""
    local count_a count_b
    count_a=$(printf '%s' "$events_json" | grep -oE "$pattern_a" | wc -l | tr -d ' ')
    count_b=$(printf '%s' "$events_json" | grep -oE "$pattern_b" | wc -l | tr -d ' ')
    echo $((count_a + count_b))
}

# Count events of a given type whose details.nodeId is NOT equal to exclude_id.
# Used to spot "replacement" joins (any node joined that is not the one we killed).
# Usage: topology_count_other_node_events "$events_json" NODE_JOINED node-3
topology_count_other_node_events() {
    local events_json="$1" type="$2" exclude_id="$3"
    # Extract every NODE_JOINED record's nodeId, then drop matches equal to exclude_id.
    local all_ids
    all_ids=$(printf '%s' "$events_json" | \
        grep -oE "\"type\":\"${type}\"[^}]*\"nodeId\":\"[^\"]+\"|\"nodeId\":\"[^\"]+\"[^}]*\"type\":\"${type}\"" | \
        grep -oE "\"nodeId\":\"[^\"]+\"" | \
        sed 's/"nodeId":"\([^"]*\)"/\1/')
    if [ -z "$all_ids" ]; then
        echo "0"
        return
    fi
    printf '%s\n' "$all_ids" | grep -vFx -- "$exclude_id" | wc -l | tr -d ' '
}

# Wait until a NODE_LEFT or NODE_FAILED event is observed for the given node
# since the supplied baseline. Returns 0 on success, 1 on timeout.
# Usage: wait_for_node_departure node-3 "$baseline" 60
#
# Caller may pass either fixture form (node-N) or runtime form (source-role-N).
# Events carry the runtime form, so we translate before matching on cloud.
# Timeout is scaled by TIMEOUT_SCALE (3 on cloud) so SWIM detection has enough
# headroom on Hetzner — VMs see ~50-150ms inter-node latency vs docker localhost.
wait_for_node_departure() {
    local node_id="$1" baseline="$2" timeout="${3:-60}"
    node_id=$(to_node_id "$node_id")
    timeout=$((timeout * ${TIMEOUT_SCALE:-1}))
    local deadline=$((SECONDS + timeout))
    while [ $SECONDS -lt $deadline ]; do
        local events
        events=$(topology_events_since "$baseline" 2>/dev/null) || events=""
        if [ -n "$events" ]; then
            local left failed
            left=$(topology_count_node_events "$events" NODE_LEFT "$node_id")
            failed=$(topology_count_node_events "$events" NODE_FAILED "$node_id")
            if [ "$left" -gt 0 ] || [ "$failed" -gt 0 ]; then
                return 0
            fi
        fi
        sleep 1
    done
    return 1
}

# Wait until a NODE_JOINED event is observed for any node OTHER than the
# killed one, since the supplied baseline. This is how we confirm CTM
# provisioned a replacement (rather than the original coming back).
# Usage: wait_for_replacement_of node-3 "$baseline" 120
wait_for_replacement_of() {
    local killed_id="$1" baseline="$2" timeout="${3:-120}"
    killed_id=$(to_node_id "$killed_id")
    local deadline=$((SECONDS + timeout))
    while [ $SECONDS -lt $deadline ]; do
        local events
        events=$(topology_events_since "$baseline" 2>/dev/null) || events=""
        if [ -n "$events" ]; then
            local joined_others
            joined_others=$(topology_count_other_node_events "$events" NODE_JOINED "$killed_id")
            if [ "$joined_others" -gt 0 ]; then
                return 0
            fi
        fi
        sleep 1
    done
    return 1
}

# Observe quorum window between baseline and now. Replays topology events,
# computes minimum member count at any instant, asserts it never dropped
# below quorum = ceil((expected + 1) / 2).
#
# Relies on `clusterSize` field present in NODE_JOINED / NODE_LEFT payloads.
# NODE_FAILED omits clusterSize (by design — local shutdown), so it does not
# perturb the running count; surviving nodes emit NODE_LEFT with the updated
# clusterSize a moment later.
#
# Usage: observe_quorum_window "$baseline" 5
#   → prints "min=<n> quorum=<n> ok" and returns 0 on pass, 1 on violation.
observe_quorum_window() {
    local baseline="$1" expected_size="${2:-5}" allow_empty="${3:-false}"
    local quorum=$(( (expected_size + 1 + 1) / 2 ))  # ceil((N+1)/2)
    local events
    events=$(topology_events_since "$baseline" 2>/dev/null) || events=""
    if [ -z "$events" ]; then
        # Fail-closed by default. The sole caller is a kill-scenario test that MUST
        # observe at least one NODE_LEFT/NODE_FAILED in the window — "no events" means
        # either the event buffer flushed (hiding the dip) or the kill never landed.
        # Either way, the previous "ok (no events in window)" print was a green sticker.
        # Pass `allow_empty=true` (3rd arg) for passive-observation callers.
        if [ "$allow_empty" = "true" ]; then
            echo "min=$expected_size quorum=$quorum ok (no events in window; allow_empty)"
            return 0
        fi
        echo "min=? quorum=$quorum FAIL (no events in window — kill not observed OR event buffer lost)"
        return 1
    fi
    # Extract every clusterSize value from NODE_JOINED / NODE_LEFT events in order.
    # These are the running cluster member counts as observed locally.
    local sizes
    sizes=$(printf '%s' "$events" | \
        grep -oE "\"clusterSize\":\"[0-9]+\"" | \
        sed 's/"clusterSize":"\([0-9]*\)"/\1/')
    local min=$expected_size
    if [ -n "$sizes" ]; then
        while IFS= read -r s; do
            if [ "$s" -lt "$min" ]; then
                min=$s
            fi
        done <<< "$sizes"
    fi
    if [ "$min" -lt "$quorum" ]; then
        echo "min=$min quorum=$quorum FAIL (dropped below quorum)"
        return 1
    fi
    echo "min=$min quorum=$quorum ok"
    return 0
}
