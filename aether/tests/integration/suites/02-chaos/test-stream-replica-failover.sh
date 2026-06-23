#!/bin/bash
# test-stream-replica-failover.sh — Destructive proof that a stream partition's
# COMPLETE history survives the hard death of its HRW owner: after the owner is
# killed, a previously-CAUGHT_UP replica is promoted and must serve EVERY event
# (count == N, not >=1), accept live batches post-repair, and converge to a
# CAUGHT_UP owner whose replica set has no lagging confirmedOffset.
#
# This is the real acceptance the non-destructive 04-streaming suite lacks:
# test-stream-replication.sh asserts only metadata + `event_count >= 1` and never
# kills the owner. Owner-kill is destructive, so this lives in cluster B
# (sequential, `restart:"no"` → `docker kill` is authoritative) — see
# test-kill-leader.sh for the kill/re-election model this mirrors.
#
# Stream-replication acceptance for #260 / #261 / #333. Uses the regression-sensor
# endpoint shipped for that class: GET /api/streams/replicas/{name}/{partition}
# (response: { stream, partition, hrwOwner, servedByOwner, ownerHeadOffset,
#  earliestRetainedOffset, replicas:[{nodeId,state,confirmedOffset,isHrwOwner}] }).
# The endpoint is OWNER-AWARE, not owner-forwarded: its replica set is authoritative
# only on the HRW owner (servedByOwner=true). When false, hrwOwner names the node to
# re-query — this test retries through the round-robin mgmt gateway until it lands on
# the owner rather than hand-mapping nodeId→port (which breaks for CTM replacement ids).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"
source "${SCRIPT_DIR}/../../lib/generation.sh"

# Unique per-run name so a re-run (or a prior 04-streaming run on a shared cluster)
# cannot pollute the partition under test with stale offsets.
STREAM_NAME="${STREAM_NAME:-chaos-replica-failover-$$}"
PARTITION=0                 # /api/streams/publish/{name} always writes partition 0
                            # (StreamRoutes.publishToPartition), and we create the
                            # stream single-partition, so all markers land here.
N_EVENTS="${N_EVENTS:-20}"  # distinct markers published before the kill
K_EVENTS="${K_EVENTS:-5}"   # additional markers published after repair (liveness)

MARKER_PREFIX="FLVR-FAILOVER-MARKER"
KILLED_OWNER=""             # set by the kill step; consumed by cleanup()

# ---------------------------------------------------------------------------
# Marker helpers
# ---------------------------------------------------------------------------
# A marker is a fixed-width, zero-padded, terminator-suffixed token so its Base64
# form is distinct and never a substring of a sibling's (verified: no collisions
# across indices). The publish path stores `data` RAW
# (StreamRoutes.publishToPartition → request.data().getBytes(UTF_8)); the READ path
# Base64-ENCODES it back (EventRecord.fromRawEvent). So we publish the raw marker
# and, on read-back, grep for its Base64 — counting Base64 occurrences is the exact,
# decode-correct completeness signal. (The CLI `streams publish` base64s its arg, so
# we deliberately publish via the raw-HTTP stream_publish helper instead.)
marker_for() {
    local idx="$1"
    printf '%s-%04d-Z' "$MARKER_PREFIX" "$idx"
}

marker_b64_for() {
    # No newline in input; base64 of a ~26-byte string is one unwrapped line.
    marker_for "$1" | base64
}

# Publish markers [start_idx .. start_idx+count-1] via the raw-HTTP publish helper.
# Echoes the number that returned success on stdout; never aborts under set -e.
publish_markers() {
    local start_idx="$1" count="$2"
    local i idx marker payload ok=0
    for ((i = 0; i < count; i++)); do
        idx=$((start_idx + i))
        marker="$(marker_for "$idx")"
        payload="{\"key\":\"k-${idx}\",\"data\":\"${marker}\",\"timestamp\":$(now_epoch)}"
        if stream_publish "$STREAM_NAME" "$payload" >/dev/null 2>&1; then
            ok=$((ok + 1))
        fi
    done
    printf '%s' "$ok"
}

# Count how many of markers [start_idx .. start_idx+count-1] appear (by Base64) in
# a read of the partition. Reads through the GOVERNOR read-preference (the CLI/HTTP
# default), which routes to the partition's current HRW owner — exactly the path a
# consumer uses, so it proves the PROMOTED owner serves the history. Echoes the hit
# count on stdout; logs the raw body head on a short count for diagnosis.
count_markers_present() {
    local start_idx="$1" count="$2"
    local body i idx b64 found=0
    # --limit must exceed the full tail (N + K) so a single read returns everything.
    body=$(aether_failover streams read "$STREAM_NAME" "$PARTITION" \
                --limit $(( (N_EVENTS + K_EVENTS) * 2 + 10 )) --format json 2>/dev/null) || {
        log_warn "count_markers_present: 'streams read' exited non-zero"
        printf '%s' 0
        return 0
    }
    if [ -z "$body" ]; then
        log_warn "count_markers_present: 'streams read' returned empty body"
        printf '%s' 0
        return 0
    fi
    for ((i = 0; i < count; i++)); do
        idx=$((start_idx + i))
        b64="$(marker_b64_for "$idx")"
        # grep -c counts MATCHING LINES; the JSON is one line per read so a present
        # marker contributes 1. -F = fixed string (base64 has no regex metachars but
        # be explicit), so each unique token is counted independently.
        if printf '%s' "$body" | grep -Fq -- "$b64"; then
            found=$((found + 1))
        fi
    done
    if [ "$found" -lt "$count" ]; then
        log_warn "count_markers_present: ${found}/${count} markers in read body (first 300 chars: ${body:0:300})"
    fi
    printf '%s' "$found"
}

# Verify the partition is served IN ORDER: each offset O in [0 .. upto] must carry
# marker_for(O)'s Base64 in its event object (i.e. publish order == read order, no
# reordering/gap). Stronger than a presence count — proves the promoted owner's log
# is contiguous and correctly sequenced, not just "the right set of bytes exists".
# Echoes the number of in-order offsets matched on stdout.
count_inorder_offsets() {
    local upto="$1"
    local body i obj data_b64 expect matched=0
    body=$(aether_failover streams read "$STREAM_NAME" "$PARTITION" \
                --limit $(( (N_EVENTS + K_EVENTS) * 2 + 10 )) --format json 2>/dev/null) || {
        printf '%s' 0
        return 0
    }
    [ -n "$body" ] || { printf '%s' 0; return 0; }
    for ((i = 0; i <= upto; i++)); do
        # Isolate the event object whose offset is exactly i. The trailing [^0-9]
        # after ${i} prevents offset 2 from matching "offset":20; the leading
        # `:[[:space:]]*` anchors so 2 never matches the 2 inside "offset":12.
        obj=$(printf '%s' "$body" \
            | grep -oE "\\{[^{}]*\"offset\"[[:space:]]*:[[:space:]]*${i}[^0-9][^{}]*\\}" \
            | head -1)
        [ -n "$obj" ] || continue
        data_b64=$(printf '%s' "$obj" | sed -E 's/.*"data"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/')
        expect="$(marker_b64_for "$i")"
        if [ "$data_b64" = "$expect" ]; then
            matched=$((matched + 1))
        fi
    done
    printf '%s' "$matched"
}

# ---------------------------------------------------------------------------
# Replica-endpoint helpers (owner-aware → retry to reach the owner)
# ---------------------------------------------------------------------------
# Fetch the replicas snapshot for the partition, preferring an OWNER-authoritative
# view. The mgmt gateway round-robins /api across cores, so a single call may hit a
# non-owner (servedByOwner=false, partial replica set). Retry up to N times to land
# on the owner; if no owner-authoritative view is seen, return the last body anyway
# (the caller decides — e.g. pre-kill we still learn hrwOwner from any node).
# Echoes the chosen JSON body on stdout.
replicas_snapshot_owner_view() {
    local attempts="${1:-8}"
    local body last_body="" i served
    for ((i = 0; i < attempts; i++)); do
        body=$(api_get "/api/streams/replicas/${STREAM_NAME}/${PARTITION}" 2>/dev/null) || body=""
        if [ -n "$body" ]; then
            last_body="$body"
            served=$(json_scalar "$body" servedByOwner)
            if [ "$served" = "true" ]; then
                printf '%s' "$body"
                return 0
            fi
        fi
        sleep 1
    done
    printf '%s' "$last_body"
}

# Extract a top-level scalar field (string/number/bool) from the flat replicas JSON
# header via grep+sed — the same no-jq pattern the existing suites use. Works for
# hrwOwner (string), servedByOwner (bool), ownerHeadOffset (number).
json_scalar() {
    local json="$1" field="$2"
    printf '%s' "$json" \
        | grep -oE "\"${field}\"[[:space:]]*:[[:space:]]*(\"[^\"]*\"|[a-z0-9.-]+)" \
        | head -1 \
        | sed -E "s/\"${field}\"[[:space:]]*:[[:space:]]*//; s/^\"//; s/\"$//"
}

# Resolve the partition's HRW owner NodeId from any replicas view (header field).
partition_hrw_owner() {
    local body
    body=$(api_get "/api/streams/replicas/${STREAM_NAME}/${PARTITION}" 2>/dev/null) || body=""
    json_scalar "$body" hrwOwner
}

# Predicate (for wait_for): the partition has an owner-authoritative view whose
# hrwOwner is non-empty AND differs from the killed owner. Used to gate on failover.
owner_changed_and_authoritative() {
    local old_owner="$1"
    local body served owner
    body=$(replicas_snapshot_owner_view 4)
    [ -n "$body" ] || return 1
    served=$(json_scalar "$body" servedByOwner)
    owner=$(json_scalar "$body" hrwOwner)
    [ "$served" = "true" ] || return 1
    [ -n "$owner" ] || return 1
    [ "$owner" != "none" ] || return 1
    [ "$owner" != "$old_owner" ] || return 1
    return 0
}

# Does the replicas JSON contain a CAUGHT_UP replica that is NOT the killed owner?
# Each replica object is `{"nodeId":"..","state":"..","confirmedOffset":N,"isHrwOwner":bool}`.
# We scan replica objects, requiring state==CAUGHT_UP and nodeId != excluded.
has_caught_up_replica_excluding() {
    local json="$1" excluded="$2"
    local obj nid state
    # Split the replicas array into one object per line (objects are brace-delimited
    # and contain no nested braces), then test each.
    while IFS= read -r obj; do
        [ -n "$obj" ] || continue
        nid=$(printf '%s' "$obj" | sed -E 's/.*"nodeId"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/')
        state=$(printf '%s' "$obj" | sed -E 's/.*"state"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/')
        if [ "$state" = "CAUGHT_UP" ] && [ -n "$nid" ] && [ "$nid" != "$excluded" ]; then
            return 0
        fi
    done < <(printf '%s' "$json" | grep -oE '\{[^{}]*"nodeId"[^{}]*\}')
    return 1
}

# True iff NO CAUGHT_UP replica's confirmedOffset lags ownerHeadOffset-1 (the true
# tail offset on the owner). ownerHeadOffset is head+1 (next-expected), so the last
# durable offset is ownerHeadOffset-1; a CAUGHT_UP replica must have confirmed it.
# This is the #333 write-idle-residual sensor.
no_caught_up_replica_lags_tail() {
    local json="$1"
    local head tail obj state conf
    head=$(json_scalar "$json" ownerHeadOffset)
    [ -n "$head" ] || return 1
    [ "$head" -ge 1 ] 2>/dev/null || return 1
    tail=$((head - 1))
    while IFS= read -r obj; do
        [ -n "$obj" ] || continue
        state=$(printf '%s' "$obj" | sed -E 's/.*"state"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/')
        conf=$(printf '%s' "$obj" | sed -E 's/.*"confirmedOffset"[[:space:]]*:[[:space:]]*([0-9-]+).*/\1/')
        if [ "$state" = "CAUGHT_UP" ]; then
            [ -n "$conf" ] || return 1
            if [ "$conf" -lt "$tail" ] 2>/dev/null; then
                log_warn "no_caught_up_replica_lags_tail: CAUGHT_UP replica confirmedOffset=${conf} < tail=${tail}"
                return 1
            fi
        fi
    done < <(printf '%s' "$json" | grep -oE '\{[^{}]*"nodeId"[^{}]*\}')
    return 0
}

# ===========================================================================
# Tests
# ===========================================================================

test_initial_state() {
    wait_for_cluster_ready 60
    # NORMAL phase gates SWIM cold-boot suppression — kill during cold boot races
    # auto-heal and confuses owner re-resolution. Warn-then-continue per suite norm.
    wait_for_phase "NORMAL" 180 || log_warn "Cluster phase still COLD_BOOT before chaos kill"
    wait_for_leader 60
    local count
    count=$(cluster_member_count)
    assert_ge "$count" "5" "Initial: at least 5 nodes (${count})"
}

test_create_stream_single_partition() {
    local payload result
    payload="{\"name\":\"${STREAM_NAME}\",\"partitions\":1}"
    result=$(api_post "/api/streams" "$payload")
    assert_contains "$result" "$STREAM_NAME" "Stream created single-partition: ${STREAM_NAME}"
}

test_publish_initial_history() {
    local ok
    ok=$(publish_markers 0 "$N_EVENTS")
    assert_eq "$ok" "$N_EVENTS" "Published all ${N_EVENTS} pre-kill markers"
}

# Sanity that the FULL pre-kill history is readable BEFORE any kill — establishes
# the publish→read invariant at strength N (not the suite-04 >=1) so a post-kill
# shortfall is unambiguously attributable to failover, not a publish/read defect.
test_full_history_present_before_kill() {
    local present
    present=$(count_markers_present 0 "$N_EVENTS")
    assert_eq "$present" "$N_EVENTS" "All ${N_EVENTS} markers readable pre-kill (got ${present})"
}

# Identify, via the new endpoint, the partition's HRW owner AND prove a surviving
# CAUGHT_UP replica exists to be promoted. Records the owner for the kill step.
OWNER_TO_KILL=""
test_identify_owner_and_caught_up_replica() {
    local body owner served
    body=$(replicas_snapshot_owner_view 10)
    assert_ne "$body" "" "Replicas endpoint returned a body for ${STREAM_NAME}/${PARTITION}"

    served=$(json_scalar "$body" servedByOwner)
    assert_eq "$served" "true" "Reached an owner-authoritative replicas view (servedByOwner)"

    owner=$(json_scalar "$body" hrwOwner)
    assert_ne "$owner" "" "HRW owner identified: ${owner}"
    assert_ne "$owner" "none" "HRW owner is not 'none'"
    OWNER_TO_KILL="$owner"

    # A promotable replica MUST exist, else killing the owner cannot preserve history.
    if has_caught_up_replica_excluding "$body" "$owner"; then
        log_pass "A CAUGHT_UP replica other than owner ${owner} exists (promotable)"
    else
        log_fail "No CAUGHT_UP non-owner replica before kill — replication not established (body head: ${body:0:300})"
        return 1
    fi
}

test_kill_owner_and_failover() {
    if [ -z "$OWNER_TO_KILL" ]; then
        log_fail "Owner identification step did not record an owner to kill"
        return 1
    fi
    log_info "Killing partition owner: ${OWNER_TO_KILL}"
    kill_node "$OWNER_TO_KILL"
    KILLED_OWNER="$OWNER_TO_KILL"

    # The pinned mgmt entry point may have been the node we just killed — rotate to
    # a surviving core so subsequent CLI/HTTP calls reach the cluster.
    rotate_mgmt_entry_point || log_warn "No surviving core node reachable after owner kill"

    # Authoritative removal signal: the killed owner leaves the SWIM-fed membership.
    if ! wait_for_node_removed "$KILLED_OWNER" 90; then
        log_fail "Killed owner ${KILLED_OWNER} still present in membership after 90s"
        return 1
    fi
    log_pass "Killed owner ${KILLED_OWNER} removed from membership"

    # Failover convergence: the partition must re-resolve to a NEW owner that can
    # serve an owner-authoritative replicas view (servedByOwner=true, hrwOwner != killed).
    if ! wait_for "partition owner re-resolved (!= ${KILLED_OWNER}, authoritative)" \
            "owner_changed_and_authoritative \"${KILLED_OWNER}\"" 180; then
        log_fail "No new owner-authoritative view (owner != ${KILLED_OWNER}) within 180s"
        return 1
    fi
    local new_owner
    new_owner=$(partition_hrw_owner)
    assert_ne "$new_owner" "$KILLED_OWNER" "New owner ${new_owner} differs from killed ${KILLED_OWNER}"
}

# THE assertion the existing suite lacks: the promoted owner serves the COMPLETE
# pre-kill history — count == N, not >=1.
test_complete_history_after_failover() {
    local present
    # Allow a brief settle for the read router to bind to the new owner + backfill.
    if ! wait_for "all ${N_EVENTS} markers readable post-failover" \
            "[ \"\$(count_markers_present 0 ${N_EVENTS})\" = \"${N_EVENTS}\" ]" 120; then
        present=$(count_markers_present 0 "$N_EVENTS")
        log_fail "Post-failover history INCOMPLETE: ${present}/${N_EVENTS} markers served by promoted owner"
        return 1
    fi
    present=$(count_markers_present 0 "$N_EVENTS")
    assert_eq "$present" "$N_EVENTS" "Promoted owner serves ALL ${N_EVENTS} pre-kill markers (complete history)"
}

# Post-repair liveness: the new owner accepts further live batches and serves them
# in order (offsets N..N+K-1 are contiguous after the pre-kill 0..N-1).
test_post_repair_liveness() {
    local ok present
    ok=$(publish_markers "$N_EVENTS" "$K_EVENTS")
    assert_eq "$ok" "$K_EVENTS" "Accepted ${K_EVENTS} live markers after repair"

    if ! wait_for "all ${K_EVENTS} post-repair markers readable" \
            "[ \"\$(count_markers_present ${N_EVENTS} ${K_EVENTS})\" = \"${K_EVENTS}\" ]" 90; then
        present=$(count_markers_present "$N_EVENTS" "$K_EVENTS")
        log_fail "Post-repair markers not all served: ${present}/${K_EVENTS}"
        return 1
    fi

    # Order/contiguity: the FULL tail (0..N+K-1) must ALL be present on the new owner.
    present=$(count_markers_present 0 $((N_EVENTS + K_EVENTS)))
    assert_eq "$present" "$((N_EVENTS + K_EVENTS))" \
        "Full ordered tail of ${N_EVENTS}+${K_EVENTS} markers present on new owner"

    # In-order proof: offset O carries marker_for(O) for every O in 0..N+K-1 — the
    # promoted owner's log is contiguous and correctly sequenced (live batches landed
    # in order after the pre-kill history, no reordering or offset gap).
    local inorder
    inorder=$(count_inorder_offsets $((N_EVENTS + K_EVENTS - 1)))
    assert_eq "$inorder" "$((N_EVENTS + K_EVENTS))" \
        "Offsets 0..$((N_EVENTS + K_EVENTS - 1)) served in publish order on new owner"
}

# Endpoint convergence: after repair the partition has a CAUGHT_UP owner and NO
# CAUGHT_UP replica's confirmedOffset lags the owner's true tail (#333 sensor).
test_replica_set_converged() {
    local body
    if ! wait_for "owner-authoritative replicas view with no lagging CAUGHT_UP replica" \
            "no_caught_up_replica_lags_tail \"\$(replicas_snapshot_owner_view 4)\"" 120; then
        body=$(replicas_snapshot_owner_view 6)
        log_fail "Replica set did not converge (no lagging CAUGHT_UP within budget). View: ${body:0:400}"
        return 1
    fi
    body=$(replicas_snapshot_owner_view 8)
    assert_ne "$body" "" "Owner-authoritative replicas view available after convergence"

    # The owner itself must be CAUGHT_UP (isHrwOwner replica in CAUGHT_UP state).
    local owner_obj owner_state
    owner_obj=$(printf '%s' "$body" | grep -oE '\{[^{}]*"isHrwOwner"[[:space:]]*:[[:space:]]*true[^{}]*\}' | head -1)
    if [ -z "$owner_obj" ]; then
        # Fallback: some views mark the owner via isHrwOwner ordering — match by hrwOwner id.
        local oid
        oid=$(json_scalar "$body" hrwOwner)
        owner_obj=$(printf '%s' "$body" | grep -oE "\\{[^{}]*\"nodeId\"[[:space:]]*:[[:space:]]*\"${oid}\"[^{}]*\\}" | head -1)
    fi
    owner_state=$(printf '%s' "$owner_obj" | sed -E 's/.*"state"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/')
    assert_eq "$owner_state" "CAUGHT_UP" "Converged partition owner is CAUGHT_UP"

    log_pass "No CAUGHT_UP replica lags ownerHeadOffset (confirmedOffsets reached the tail)"
}

# Restore cluster for the next suite via the semantic baseline restore (re-enables
# auto-heal, resets the CTM circuit breaker, scales back to NODE_COUNT, waits for N
# ON_DUTY healthy cores). Best-effort stream delete first so a shared cluster does
# not accumulate per-run streams. Runs on ANY exit path (incl. set -e abort).
cleanup() {
    aether_failover streams delete "$STREAM_NAME" >/dev/null 2>&1 || true
    restore_cluster_baseline || \
        log_warn "cleanup: restore_cluster_baseline reported non-zero; subsequent suites may inherit cluster churn"
}
trap 'cleanup' EXIT

run_test "Initial 5 nodes"                          test_initial_state
run_test "Create single-partition stream"           test_create_stream_single_partition
run_test "Publish ${N_EVENTS}-event history"        test_publish_initial_history
run_test "Full history readable before kill"        test_full_history_present_before_kill
run_test "Identify HRW owner + CAUGHT_UP replica"   test_identify_owner_and_caught_up_replica
run_test "Kill owner and fail over"                 test_kill_owner_and_failover
run_test "Complete history after failover"          test_complete_history_after_failover
run_test "Post-repair liveness (live batches)"      test_post_repair_liveness
run_test "Replica set converged (no lag)"           test_replica_set_converged
print_summary
