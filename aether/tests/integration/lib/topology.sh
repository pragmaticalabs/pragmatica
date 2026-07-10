#!/bin/bash
# topology.sh — Semantic topology helpers for membership + departure waits
#
# Departure oracle: the AUTHORITATIVE, leaderless source for "node X is gone"
# is the membership projection at GET /api/cluster/membership. Every node serves
# its own MembershipFsm view (route RBAC=VIEWER, scope=LOCAL — no leader hop), so
# a single GET against ANY surviving node reports the victim's membership `state`.
# When the FSM moves the victim to its terminal `Dead` record, the member's entry
# renders `"state":"Dead"` (the FSM state class simple name) and
# `"countsTowardEffective":false`. DEAD members are RETAINED in `members[]`
# (incarnation-fenced rejoin), so departure is detected by the victim's entry
# being `state=="Dead"` — NOT by the victim being absent from the array.
#
# Why membership, not /api/events: the per-node in-heap event buffer that the old
# departure oracle scanned was DELETED when NODE_FAILED emission moved to the
# ungated MembershipFsm DEAD edge (publishing into the replicated
# `system:cluster-events:1.0.0` stream). That events path is now LEADER-GATED — if
# the killed victim was the leader, the NODE_FAILED publish waits for re-election,
# so an /api/events scan is flaky as a primary departure signal. We keep a cheap
# /api/events NODE_FAILED/NODE_LEFT scan as a belt-and-suspenders SECONDARY signal,
# but membership `state=="Dead"` is the primary success condition.
#
# Survivor-pinned by construction: membership reads go through api_get →
# _resolve_live_endpoint, which already skips the just-killed node (cloud: per-VM
# public-IP scan; docker/remote: mgmt-gateway round-robin / live-port / label
# discovery). So the membership poll never targets the victim — it reflects a
# surviving node's own FSM view.
#
# The legacy /api/events union helpers below (topology_events_since and the
# count/observe helpers) remain for the quorum-window + replacement + self-drain
# waits that still read the cluster-events stream; each node's view is unioned
# across all node ports there.

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
        # Per-node direct mgmt ports start at 5151 (cluster A) or 5161 (B); run-tests.sh
        # exports MGMT_PORT explicitly. The default here matters only if a suite is run
        # standalone -- pick cluster A's base.
        local base_port="${MGMT_PORT:-5151}"
        for i in $(seq 0 $((count - 1))); do
            base_urls+=("http://${TARGET_HOST}:$((base_port + i))")
        done

        # The fixed MGMT_PORT+i slots above cover only compose seeds. CTM-provisioned
        # replacements publish mgmt 8080 on EPHEMERAL host ports (DockerComputeProvider
        # `-p 8080`), so events buffered only on a replacement node were invisible to
        # this union — e.g. a departure observed solely by the node provisioned to
        # replace the victim (2026-06-11 Wave-9 gate, verdict V3). Discover them via
        # one ssh roundtrip using the same `aether.cluster` label convention as
        # _discover_endpoint_by_label (common.sh); dedupe against seeds whose 8080
        # maps back to a fixed slot.
        if [ -n "${CLUSTER_ID:-}" ] && command -v remote_exec >/dev/null 2>&1; then
            local discovered hp u
            discovered=$(remote_exec "docker ps --filter 'label=aether.cluster=${CLUSTER_ID}' --format '{{.Names}}' | while read -r n; do docker port \"\$n\" 8080/tcp 2>/dev/null | sed -n '1s/.*:\\([0-9][0-9]*\\)\$/\\1/p'; done" 2>/dev/null || true)
            for hp in $discovered; do
                u="http://${TARGET_HOST}:${hp}"
                case " ${base_urls[*]-} " in
                    *" $u "*) ;;
                    *) base_urls+=("$u") ;;
                esac
            done
        fi
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
    # Events are objects in a JSON array. The ONLY top-level `},{` is the array
    # boundary between events — nested `at`/`details` objects close with `}}`/`}`,
    # never `},{` — so splitting on `},{` yields exactly one event per line. Count
    # events carrying BOTH the requested `type` (self-describing field, added to the
    # /api/events payload) AND the victim's flat `details.nodeId` (the emitter id is
    # `"nodeId":{"id":...}` — an object — so `"nodeId":"<id>"` matches only details).
    printf '%s' "$events_json" \
        | sed 's/},{/}\n{/g' \
        | grep -cE "\"type\":\"${type}\".*\"nodeId\":\"${node_id}\"|\"nodeId\":\"${node_id}\".*\"type\":\"${type}\"" \
        | tr -d ' '
}

# Count events of a given type whose details.nodeId is NOT equal to exclude_id.
# Used to spot "replacement" joins (any node joined that is not the one we killed).
# Usage: topology_count_other_node_events "$events_json" NODE_JOINED node-3
topology_count_other_node_events() {
    local events_json="$1" type="$2" exclude_id="$3"
    # Split events on the array boundary (see topology_count_node_events), keep only
    # records of the requested type, extract each one's flat details.nodeId (the emitter
    # id is an object `"nodeId":{"id":...}` so the `"nodeId":"<id>"` string form matches
    # only details), drop the excluded id, count the rest.
    local all_ids
    all_ids=$(printf '%s' "$events_json" \
        | sed 's/},{/}\n{/g' \
        | grep -E "\"type\":\"${type}\"" \
        | grep -oE "\"nodeId\":\"[^\"]+\"" \
        | sed 's/"nodeId":"\([^"]*\)"/\1/')
    if [ -z "$all_ids" ]; then
        echo "0"
        return
    fi
    printf '%s\n' "$all_ids" | grep -vFx -- "$exclude_id" | wc -l | tr -d ' '
}

# Extract the membership `state` of one node from a /api/cluster/membership body.
# The response is { ..., "members":[ {"nodeId":"X","state":"S",...}, ... ] } where
# each member object is brace-delimited and carries NO nested braces, so we split
# the array into one object per line, keep only the victim's object, and read its
# `state`. Prints the state string (e.g. Member / Suspect / Dead) on stdout, empty
# when the node has no entry. Substring-safe: the nodeId match is anchored on the
# closing quote (`"nodeId":"<id>"`), so victim "core-4" never matches "core-40".
# Usage: membership_node_state "$membership_json" core-4
membership_node_state() {
    local membership_json="$1" node_id="$2"
    local obj
    # One member object per line. MembershipNodeDetail has no nested objects/arrays,
    # so `{...}` with no inner braces isolates each element cleanly (same split the
    # 02-chaos replica helpers use). Match the victim by its FLAT, quote-anchored
    # nodeId so "core-4" does not match "core-40".
    obj=$(printf '%s' "$membership_json" \
        | grep -oE '\{[^{}]*\}' \
        | grep -F "\"nodeId\":\"${node_id}\"" \
        | head -1)
    [ -z "$obj" ] && return 0
    printf '%s' "$obj" \
        | grep -oE '"state"[[:space:]]*:[[:space:]]*"[^"]*"' \
        | head -1 \
        | sed 's/"state"[[:space:]]*:[[:space:]]*"\([^"]*\)"/\1/'
}

# Wait until the given node is observed DEPARTED since the supplied baseline.
# Returns 0 on success, 1 on timeout. Signature + timeout/scale behaviour are
# unchanged from the old /api/events oracle so callers need no edit.
# Usage: wait_for_node_departure node-3 "$baseline" 60
#
# PRIMARY (authoritative, leaderless): poll GET /api/cluster/membership on a
# SURVIVING node (api_get resolves a live endpoint, never the just-killed victim)
# and succeed when the victim's membership entry reports `state=="Dead"` — the
# terminal FSM state. DEAD members are retained in `members[]`, so we key on the
# Dead state, not on absence.
# SECONDARY (belt-and-suspenders): the legacy /api/events NODE_FAILED/NODE_LEFT
# union scan. Cheap and kept as a corroborating signal, but it is leader-gated
# (the stream publish waits for re-election if the victim was leader), so it is
# NOT relied on as the sole condition.
#
# Caller may pass either fixture form (node-N) or runtime form (source-role-N);
# membership + events carry the runtime form, so we translate before matching.
# Timeout is scaled by TIMEOUT_SCALE (3 on cloud) so SWIM detection has enough
# headroom on Hetzner — VMs see ~50-150ms inter-node latency vs docker localhost.
wait_for_node_departure() {
    local node_id="$1" baseline="$2" timeout="${3:-60}"
    node_id=$(to_node_id "$node_id")
    timeout=$((timeout * ${TIMEOUT_SCALE:-1}))
    local deadline=$((SECONDS + timeout))
    while [ $SECONDS -lt $deadline ]; do
        # PRIMARY: membership state on a surviving node. api_get returns empty stdout
        # + non-zero rc on transport failure; ignore rc, parse stdout (empty → keep
        # polling). A victim entry of state "Dead" is the authoritative departure.
        local membership state
        membership=$(api_get "/api/cluster/membership" 2>/dev/null) || membership=""
        if [ -n "$membership" ]; then
            state=$(membership_node_state "$membership" "$node_id")
            if [ "$state" = "Dead" ]; then
                return 0
            fi
        fi
        # SECONDARY: corroborating /api/events scan (cheap, leader-gated). A
        # NODE_LEFT/NODE_FAILED for the victim is also sufficient.
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
    # These are the running cluster member counts as observed locally. Tolerate both
    # quoted ("clusterSize":"5") and unquoted ("clusterSize":5) renderings so a Jackson
    # serialization change does not silently empty `sizes` and green-sticker the check.
    local sizes
    sizes=$(printf '%s' "$events" \
        | grep -oE "\"clusterSize\"[[:space:]]*:[[:space:]]*\"?[0-9]+\"?" \
        | grep -oE '[0-9]+')
    # Parse-integrity guard: if the window's events DO carry clusterSize but we parsed
    # none, the field rendering drifted (quoted⇄unquoted, rename) — fail closed instead
    # of passing with the optimistic min=expected default. (A window with only
    # NODE_FAILED events legitimately carries no clusterSize; that is not drift.)
    if [ -z "$sizes" ] && printf '%s' "$events" | grep -q '"clusterSize"'; then
        echo "min=? quorum=$quorum FAIL (clusterSize present but unparseable — schema drift)"
        return 1
    fi
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

# Wait until a SELF_DRAIN_INITIATED event is observed for the given node since
# the supplied baseline. Returns 0 on success, 1 on timeout.
# Usage: wait_for_self_drain_event node-3 "$baseline" 60
#
# Caller may pass either fixture form (node-N) or runtime form (source-role-N).
# Events carry the runtime form, so we translate before matching on cloud.
#
# Background: SELF_DRAIN_INITIATED is published by the draining node itself when
# its SelfDrainCoordinator flips ACTIVE → DRAINING (see membership-architecture-v2-spec.md).
# It is NOT leader-gated because a partition victim is the only
# authoritative source for "I'm self-draining" — and may not be able to reach the
# leader at all. The event therefore must be polled from the survivor's own
# /api/events buffer (or any node that observed the replicated commit before the
# survivor halted). `topology_events_since` already unions across all node
# endpoints, so a single call is enough.
#
# Timing: SelfDrainCoordinator publishes synchronously at the CAS transition
# (well before Runtime.halt(2)), but the publish goes through Rabia. If quorum is
# lost (the scenario this event covers), the publish may not commit before the
# halt lands. We therefore poll on a generous budget and tolerate timeout — the
# CALLER decides whether timeout is a test failure or a known limitation.
wait_for_self_drain_event() {
    local node_id="$1" baseline="$2" timeout="${3:-60}"
    node_id=$(to_node_id "$node_id")
    timeout=$((timeout * ${TIMEOUT_SCALE:-1}))
    local deadline=$((SECONDS + timeout))
    while [ $SECONDS -lt $deadline ]; do
        local events
        events=$(topology_events_since "$baseline" 2>/dev/null) || events=""
        if [ -n "$events" ]; then
            local count
            count=$(topology_count_node_events "$events" SELF_DRAIN_INITIATED "$node_id")
            if [ "$count" -gt 0 ]; then
                return 0
            fi
        fi
        sleep 1
    done
    return 1
}

# Read R's KV-backed lifecycle state via /api/nodes/lifecycle/<id>, which reads
# the lifecycle projection straight out of KV-Store regardless of SWIM state.
# Returns the state string on stdout (empty when the atom is absent / HTTP 404).
#
# rc contract (#426 item 2 — callers MUST check rc, not just stdout emptiness):
#   0 with non-empty stdout — state found.
#   0 with empty stdout     — genuine 404 (lifecycle atom absent). This is the
#                              ONLY case that legitimately means "removed".
#   1 with empty stdout     — transport failure (curl never reached the
#                              endpoint) or an unexpected non-404/non-2xx
#                              status. UNKNOWN, not absent — the old code did
#                              `api_get ... || true` and treated ANY empty body
#                              (including a dead endpoint) as "atom absent",
#                              which let a transport outage masquerade as a
#                              successful node removal. Callers must keep
#                              polling on rc=1, mirroring the already-correct
#                              pattern in node_absent_from_status/status_node_ids
#                              below (transport failure -> assume still present).
kv_lifecycle_state() {
    local target="$1"
    local raw status body
    raw=$(api_get_with_status "/api/nodes/lifecycle/${target}" 2>/dev/null)
    status=$(printf '%s' "$raw" | grep -oE '__API_HTTP_STATUS:[0-9]+__' | tail -1 | sed 's/__API_HTTP_STATUS://;s/__//')
    body=$(printf '%s' "$raw" | sed '$d')
    case "$status" in
        404)
            return 0  # genuine 404 — lifecycle atom absent
            ;;
        2??|3??)
            printf '%s' "$body" \
                | grep -o '"state"[[:space:]]*:[[:space:]]*"[^"]*"' \
                | head -1 \
                | sed 's/"state"[[:space:]]*:[[:space:]]*"\([^"]*\)"/\1/'
            return 0
            ;;
        *)
            # #426 review follow-up (item 6): _api_call's own log_warn is
            # suppressed above (2>/dev/null) to keep the EXPECTED per-poll 404
            # quiet; surface everything else here — a transport failure or
            # unexpected status is never "removed" and must stay visible, not
            # silently swallowed alongside the benign 404 case.
            log_warn "kv_lifecycle_state(${target}): non-404 failure (status=${status:-000}) — treating as UNKNOWN, not removed"
            return 1  # transport failure ("000") or unexpected status — UNKNOWN
            ;;
    esac
}

# Report whether $target is ABSENT from /api/nodes/status cluster.nodes[].
# Returns 0 (absent) if the node-id does not appear in the status projection,
# 1 (present) otherwise. On a transport failure (empty body) we conservatively
# treat the node as still present (return 1) so the caller keeps polling rather
# than declaring removal off a failed read.
node_absent_from_status() {
    local target="$1"
    local ids
    # status_node_ids parses cluster.nodes[].id correctly (NodeInfo field is "id",
    # not "nodeId" — see status_node_ids). The old inline `grep '"nodeId"'` here
    # matched only THIS node's own top-level id, so every other node read as
    # "absent" (rc 0) on a healthy cluster — a latent false-removal bug shared with
    # the original status_node_ids parse. Route through the single corrected parser.
    if ! ids=$(status_node_ids); then
        return 1  # couldn't read — assume still present, keep polling
    fi
    if [ -z "$ids" ]; then
        return 1  # empty membership read — assume still present, keep polling
    fi
    if printf '%s\n' "$ids" | grep -Fxq -- "$target"; then
        return 1  # present
    fi
    return 0  # absent
}

# Print the set of node-ids currently in /api/nodes/status cluster.nodes[], one
# per line, sorted+deduped. Empty string (rc 1) on transport failure.
#
# This is the cloud-reliable identity source: every node (seed OR CTM replacement,
# docker OR cloud VM) appears in the cluster's own membership projection by its
# runtime NodeId. Used as the cloud substitute for the docker `aether.node-id`
# container-label snapshot (S01 / test-joining-window-kill.sh) — on cloud each node
# is a separate VM with no local container labels to inspect, so identity must come
# from the API. The output format (sorted unique node-ids, one per line) matches
# `snapshot_node_id_labels` so the same `comm -13` set-diff works unchanged.
#
# PARSE: /api/nodes/status (StatusResponse) renders cluster.nodes[] as NodeInfo
# records `{"id":"<nodeId>","isLeader":...,"kvState":...,"derivedStatus":...}` — the
# per-node id field is `"id"`, NOT `"nodeId"`. The payload's only top-level "nodeId"
# is THIS node's own id (and cluster.leaderId uses key "leaderId"), so the old
# `grep '"nodeId"'` matched exactly one entry and collapsed to a single id after
# `sort -u` (the "got 1, expected 5" cloud symptom). We isolate the cluster.nodes
# array first (everything from `"nodes":[` to its closing `]` — NodeInfo has no
# nested arrays/objects so the first `]` closes it) and extract each `"id":"..."`,
# so unrelated future top-level `"id"` fields cannot leak in.
status_node_ids() {
    local status_payload
    # #426 review follow-up (item 6): no expected/benign-failure case here
    # (unlike kv_lifecycle_state's 404) — unsuppress stderr so a transport
    # failure or unexpected status surfaces via _api_call's own log_warn
    # instead of vanishing silently.
    status_payload=$(api_get "/api/nodes/status") || true
    if [ -z "$status_payload" ]; then
        return 1  # couldn't read
    fi
    # Slice out the cluster.nodes[] array, then pull each element's "id".
    printf '%s' "$status_payload" \
        | grep -o '"nodes"[[:space:]]*:[[:space:]]*\[[^]]*\]' \
        | grep -o '"id"[[:space:]]*:[[:space:]]*"[^"]*"' \
        | sed 's/"id"[[:space:]]*:[[:space:]]*"\([^"]*\)"/\1/' \
        | sort -u
}

# v2 "node was removed" poll, bounded by the spec budget. In v2 a killed node
# simply leaves the SWIM-fed membership — there is NO DECOMMISSIONED lifecycle
# state and NO decommission-reason domain event. The authoritative v2 removal
# signal is twofold (either is sufficient):
#   (a) /api/nodes/lifecycle/<R> returns HTTP 404 (LIFECYCLE_NOT_FOUND) — the
#       lifecycle endpoint reports the node as unknown. kv_lifecycle_state
#       surfaces this as rc=0 + empty stdout (see its contract comment).
#   (b) R is absent from /api/nodes/status cluster.nodes[].
# Returns 0 as soon as either holds; 1 on timeout.
wait_for_node_removed() {
    local target="$1" timeout="${2:-8}"
    local deadline=$((SECONDS + timeout))
    while :; do
        # Wall-clock ceiling (#426 item 1) checked BEFORE every blocking sub-call,
        # not just between loop iterations. kv_lifecycle_state and
        # node_absent_from_status both resolve through _resolve_live_endpoint,
        # which on docker/remote can fall through to an SSH-based label discovery
        # with no bound on remote command execution time (ssh ConnectTimeout=10
        # only guards connection setup, not a hung remote docker daemon call). A
        # single such call has been observed to block far longer than the nominal
        # timeout (1293s against an 8s-90s budget) — checking the deadline only at
        # the top of the loop let one wedged read consume the entire run.
        if [ $SECONDS -ge $deadline ]; then
            return 1
        fi
        local state rc
        state=$(kv_lifecycle_state "$target")
        rc=$?
        # Re-check immediately after the call returns, before acting on its
        # result — a late-returning read must not buy itself another iteration.
        if [ $SECONDS -ge $deadline ]; then
            return 1
        fi
        # (a) genuine 404 — rc=0 with empty state (see kv_lifecycle_state
        # contract). rc=1 means the read failed (transport failure or an
        # unexpected status) and is NEVER a removal signal, even though its
        # stdout is also empty — #426 item 2: the old code could not tell the
        # two apart and treated a dead endpoint as "node removed".
        if [ $rc -eq 0 ] && [ -z "$state" ]; then
            return 0
        fi
        # (b) node has dropped out of the status projection's cluster.nodes[].
        if node_absent_from_status "$target"; then
            return 0
        fi
        if [ $SECONDS -ge $deadline ]; then
            return 1
        fi
        sleep 1
    done
}
