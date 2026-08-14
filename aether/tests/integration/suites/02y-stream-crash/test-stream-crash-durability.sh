#!/bin/bash
# test-zz-stream-crash-durability.sh — #508
#
# ISOLATED SUITE, DELIBERATELY. This lives in its own suite rather than in 02-chaos, for a
# measured reason (#593): on that suite's shared cluster the EIGHTH chaos test fails whatever
# it is. Across six runs, position 7 passed twice and position 8 failed five times — first
# test-stream-replica-failover.sh when this test displaced it, then THIS test once it was
# reordered last. Reordering only swaps the victim; the slot is the problem. So this test
# takes a fresh cluster, following the 02z-killonly precedent.
#
# If #593 is fixed this can fold back into 02-chaos with no other change.
#
# Destructive proof that every ACKED event survives a HARD kill (SIGKILL, no graceful
# hooks) of a partition owner, across a MULTI-PARTITION stream, including events acked
# concurrently with the kill window.
#
# Why this cannot live in the in-JVM (Ember/forge) tier — empirical, from #431:
# every in-JVM stop routes through `AetherNode.stop()` -> `streamPartitionManager.close()`
# SYNCHRONOUSLY, and `killNode(graceful=false)` only shortens the async cluster/network
# teardown timeout — it never skips `close()`. `close()` is durability-NEUTRAL (it fsyncs
# only already-appended bytes and closes the FD), so graceful and hard stop are
# durability-equivalent in-JVM and the crash-mid-fsync boundary is structurally
# unreachable there. `docker kill` on cluster B (`restart:"no"`, so the kill is
# authoritative) is the only place the real boundary exists.
#
# Why the MANAGEMENT publish API cannot drive this: `/api/streams/publish/{name}`
# hardwires partition 0 (StreamRoutes.publishToPartition — #524), so it structurally
# cannot exercise per-partition WAL replay. This test drives the app-HTTP routes of the
# `test-stream-multipart` blueprint instead: a KEYLESS publish round-robins across all
# four partitions (DefaultStreamPublisher#resolvePartition), and the read route takes a
# caller-supplied `(partition, fromOffset)` so each partition's log is asserted
# INDEPENDENTLY — a bug that loses one partition's WAL while others recover is caught
# per-partition rather than hidden in an aggregate count.
#
# Durability contract under test (min-sync-replicas=2): a publish resolves only after
# `PartitionWal.append`'s `force()`, so an ACKED event is fsync-durable on >=2 nodes
# BEFORE the caller is told "published". Therefore the assertion is exactly:
#   every event whose publish returned success is present after the crash.
# Publishes that did NOT ack (in flight when the owner died) may legitimately be absent —
# that IS the contract, and asserting otherwise would be asserting a guarantee the system
# deliberately does not make.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"
source "${SCRIPT_DIR}/../../lib/generation.sh"

STREAM_NAME="${STREAM_NAME:-multipart-events}"
STREAM_BP="${STREAM_BP:-org.pragmatica.aether.test:test-stream-multipart:1.0.0}"
PARTITIONS="${PARTITIONS:-4}"          # resources.toml: partitions = 4
N_PRE="${N_PRE:-40}"                   # markers published before the kill
N_DURING="${N_DURING:-40}"             # attempted concurrently with the kill window
KILL_PARTITION="${KILL_PARTITION:-0}"  # whose owner gets SIGKILLed

MARKER_PREFIX="CRASHDUR"
ACKED_PRE="$(mktemp)"                  # marker indices whose publish returned success
ACKED_DURING="$(mktemp)"
OWNER_TO_KILL=""
PUBLISHER_PID=""

# Fixed-width zero-padded + terminator so no marker is a substring of a sibling.
marker_for() {
    printf '%s-%05d-Z' "$MARKER_PREFIX" "$1"
}

# Per-node app endpoints (see `node_app_endpoints` in lib/cluster.sh).
#
# This suite used to drive `app_post`, i.e. the single pinned APP_ENDPOINT. In a full run 02y comes
# straight after 02-chaos, which KILLS nodes — so the pin routinely points at a dead port. Measured
# 2026-08-14: 0 of 40 publishes ACKED against a cluster that was completely healthy (5 members,
# NORMAL, leader elected, and the stream's own deploy gate passed 4s earlier). Every assertion
# downstream then failed for want of data, reading as a stream-durability defect.
#
# Transport only — no assertion changed. Reads are additionally safe to rotate because replicated
# streams forward to the owner (`ForwardingReadRouter`), so any node can serve a partition read.
STREAM_APP_ENDPOINTS=""

refresh_stream_endpoints() {
    STREAM_APP_ENDPOINTS="$(node_app_endpoints || printf '')"
    [ -n "$STREAM_APP_ENDPOINTS" ]
}

# POST to each node until `matcher` matches; echoes the matching body. Re-resolves once between
# passes so a kill landing mid-sweep does not strand the suite on stale endpoints.
stream_post_any() {
    local path="$1" payload="$2" matcher="$3" pass ep body last=""

    [ -n "$STREAM_APP_ENDPOINTS" ] || refresh_stream_endpoints || return 1

    for pass in 1 2; do
        while IFS= read -r ep; do
            [ -z "$ep" ] && continue
            body=$(_api_call POST "${ep}${path}" "$payload" 2>/dev/null) || continue
            if printf '%s' "$body" | grep -qE "$matcher"; then
                printf '%s' "$body"
                return 0
            fi
            last="$body"
        done <<< "$STREAM_APP_ENDPOINTS"
        [ "$pass" -eq 1 ] && refresh_stream_endpoints >/dev/null 2>&1
    done

    [ -n "$last" ] && printf '%s' "$last"
    return 1
}

# Publish one marker through the app-HTTP route. Returns 0 only when the publish
# ACKED (HTTP success AND status=published) — that ack is the durability claim we
# later hold the system to, so it must not be inferred loosely.
#
# STICKY endpoint, deliberately NOT rotated. A keyless publish round-robins across the four
# partitions from the publisher instance's own counter, so driving every publish through ONE port is
# what spreads events deterministically over all partitions (see `StreamSlice`'s header). Rotating
# would give each node an independent counter and quietly undermine the "events spread across
# partitions" and per-partition contiguity assertions this suite exists to make.
#
# The fix is therefore to keep ONE endpoint but ensure it is a LIVE one: re-pick only when the
# current pin stops answering, which is what the old `app_post` pin could not do.
STREAM_PUBLISH_ENDPOINT=""

pick_publish_endpoint() {
    STREAM_PUBLISH_ENDPOINT="$(node_app_endpoints 2>/dev/null | head -1)"
    [ -n "$STREAM_PUBLISH_ENDPOINT" ]
}

publish_marker() {
    local idx="$1" body payload
    payload="{\"payload\":\"$(marker_for "$idx")\"}"

    [ -n "$STREAM_PUBLISH_ENDPOINT" ] || pick_publish_endpoint || return 1

    body=$(_api_call POST "${STREAM_PUBLISH_ENDPOINT}/api/stream-mp/publish" "$payload" 2>/dev/null)
    if printf '%s' "$body" | grep -q '"status"[[:space:]]*:[[:space:]]*"published"'; then
        return 0
    fi

    # The pin may have just been killed — re-pick ONCE and retry, so the sticky choice survives a
    # crash without becoming a rotation.
    #
    # The retry does NOT suppress stderr: `_api_call` logs status + the first 300 bytes of any error
    # body there, and that is the only account of WHY a publish failed. Suppressing both attempts
    # (as the first version of this did) left "39 of 40 ACKED" with nothing to explain the missing
    # one — the same silent-stderr trap this suite's siblings were fixed for.
    pick_publish_endpoint || return 1
    body=$(_api_call POST "${STREAM_PUBLISH_ENDPOINT}/api/stream-mp/publish" "$payload")
    if printf '%s' "$body" | grep -q '"status"[[:space:]]*:[[:space:]]*"published"'; then
        return 0
    fi

    log_warn "publish ${idx}: not ACKED after re-pick; last body: $(printf '%s' "$body" | head -c 200)" >&2
    return 1
}

# Publish [start .. start+count-1], appending ACKED indices to $outfile.
publish_range_recording_acks() {
    local start="$1" count="$2" outfile="$3"
    local i idx
    for ((i = 0; i < count; i++)); do
        idx=$((start + i))
        if publish_marker "$idx"; then
            printf '%s\n' "$idx" >> "$outfile"
        fi
    done
}

# Raw read of one partition from offset 0. maxEvents is passed explicitly and large:
# ReadRequest defaults to 100 when <= 0, which would silently truncate the history and
# turn a durability failure into a read-window artifact.
read_partition_raw() {
    local partition="$1"
    # `"events"` rather than a success flag: an empty partition legitimately returns an empty list,
    # and treating that as "no node answered" would rotate past the node that told the truth.
    stream_post_any "/api/stream-mp/read" \
        "{\"partition\":${partition},\"fromOffset\":0,\"maxEvents\":100000}" \
        '"events"' || printf ''
}

# Emit "offset payload" per event of a partition, in response order.
partition_events() {
    local partition="$1" body
    body=$(read_partition_raw "$partition")
    printf '%s' "$body" \
        | grep -oE '\{[^{}]*"offset"[^{}]*\}' \
        | sed -E 's/.*"offset"[[:space:]]*:[[:space:]]*([0-9]+).*"payload"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1 \2/'
}

# All payloads currently readable across every partition.
all_payloads() {
    local p
    for ((p = 0; p < PARTITIONS; p++)); do
        partition_events "$p" | awk '{print $2}'
    done
}

# How many partitions currently hold at least one event.
partitions_with_events() {
    local p n hits=0
    for ((p = 0; p < PARTITIONS; p++)); do
        n=$(partition_events "$p" | grep -c . || true)
        [ "$n" -gt 0 ] && hits=$((hits + 1))
    done
    printf '%s' "$hits"
}

json_scalar() {
    local json="$1" field="$2"
    printf '%s' "$json" \
        | grep -oE "\"${field}\"[[:space:]]*:[[:space:]]*(\"[^\"]*\"|[a-z0-9.-]+)" \
        | head -1 \
        | sed -E "s/\"${field}\"[[:space:]]*:[[:space:]]*//; s/^\"//; s/\"$//"
}

# Owner-authoritative replicas view for the partition under test (the mgmt gateway
# round-robins, so retry until we land on the owner).
replicas_snapshot_owner_view() {
    local attempts="${1:-8}" body last_body="" i served
    for ((i = 0; i < attempts; i++)); do
        body=$(api_get "/api/streams/replicas/${STREAM_NAME}/${KILL_PARTITION}" 2>/dev/null) || body=""
        if [ -n "$body" ]; then
            last_body="$body"
            served=$(json_scalar "$body" servedByOwner)
            [ "$served" = "true" ] && { printf '%s' "$body"; return 0; }
        fi
        sleep 1
    done
    printf '%s' "$last_body"
}

# Reap the concurrent publisher without silencing stderr. Muting the error stream and
# forcing success (the R2 lint pattern) hides the very failure a chaos test exists to
# surface. `wait` on an already-reaped or never-started PID legitimately returns
# non-zero, so that status is discarded EXPLICITLY via an if-guard, and anything the
# child wrote to stderr still reaches the run log.
reap_publisher() {
    if [ -n "$PUBLISHER_PID" ]; then
        if ! wait "$PUBLISHER_PID"; then
            log_info "concurrent publisher ${PUBLISHER_PID} exited non-zero (expected when the owner dies mid-publish)"
        fi
        PUBLISHER_PID=""
    fi
}

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

test_initial_state() {
    wait_for_cluster_ready 60
    wait_for_phase "NORMAL" 180 || log_warn "Cluster phase still COLD_BOOT before crash test"
    wait_for_leader 60
    wait_for "Initial: at least 5 nodes" '[ "$(cluster_member_count)" -ge 5 ]' 60 || true
    local count
    count=$(cluster_member_count)
    assert_ge "$count" "5" "Initial: at least 5 nodes (${count})"
}

test_deploy_multipart_blueprint() {
    # Quiesce BEFORE deploying. `deploy_blueprint` is deliberately single-shot — its own doc
    # states the caller must have settled the cluster first ("preceding await_generation_quiesced
    # guarantees the cluster is settled. Retries hid the actual race"). The test that runs
    # immediately before this one drains a node and loses quorum, so without this barrier the
    # deploy lands mid-reshuffle: observed 2026-08-12 as all-instances-ACTIVE timing out at 240s
    # on a run where the same deploy took ~3s on a settled cluster.
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 60 \
        || log_warn "deploy: cluster did not quiesce before blueprint deploy — deploy may race a reshuffle"

    aether_failover streams delete "$STREAM_NAME" >/dev/null 2>&1 || true
    if ! push_blueprint "$STREAM_BP" >/dev/null; then
        log_fail "Failed to push blueprint ${STREAM_BP}"
        return 1
    fi
    deploy_blueprint "$STREAM_BP" >/dev/null 2>&1 || true
    if ! wait_for "test-stream-multipart all instances ACTIVE (partitions=4, min-sync=2 committed)" \
        "[ \$(slices_active_instances_for '${STREAM_BP}') -ge \$(slices_target_total_for '${STREAM_BP}') ] && [ \$(slices_target_total_for '${STREAM_BP}') -gt 0 ]" \
        120; then
        log_fail "test-stream-multipart did not reach all-instances ACTIVE — stream ${STREAM_NAME} not established"
        return 1
    fi
    log_pass "Deployed ${STREAM_BP} (stream ${STREAM_NAME}, partitions=${PARTITIONS}, min-sync-replicas=2)"
}

test_publish_pre_kill_history() {
    publish_range_recording_acks 0 "$N_PRE" "$ACKED_PRE"
    local acked
    acked=$(grep -c . "$ACKED_PRE" || true)
    assert_eq "$acked" "$N_PRE" "All ${N_PRE} pre-kill publishes ACKED (got ${acked})"
}

# Multi-partition coverage is the property #508 exists for. If a keyless publish did
# NOT spread, the per-partition assertions below would pass vacuously on three empty
# logs — so this gate is load-bearing, not a sanity check.
test_events_spread_across_partitions() {
    local hits
    hits=$(partitions_with_events)
    assert_ge "$hits" "2" "Events spread across multiple partitions (${hits}/${PARTITIONS} non-empty)"
}

test_pre_kill_history_readable() {
    local present=0 idx
    local payloads
    payloads=$(all_payloads)
    while IFS= read -r idx; do
        [ -n "$idx" ] || continue
        printf '%s' "$payloads" | grep -qx "$(marker_for "$idx")" && present=$((present + 1))
    done < "$ACKED_PRE"
    local acked
    acked=$(grep -c . "$ACKED_PRE" || true)
    # Same non-vacuity gate: "all 0 of 0 readable" is not evidence of a working read path.
    assert_gt "$acked" "0" "Non-vacuity: pre-kill history is non-empty (${acked} acked)"
    assert_eq "$present" "$acked" "All ${acked} ACKED pre-kill markers readable before the kill (got ${present})"
}

test_identify_partition_owner() {
    local body owner served
    body=$(replicas_snapshot_owner_view 10)
    assert_ne "$body" "" "Replicas endpoint returned a body for ${STREAM_NAME}/${KILL_PARTITION}"

    # NOT asserted: servedByOwner=true. This test only needs the owner's IDENTITY to pick a
    # kill target, and `hrwOwner` is a header field every node reports (the failover test's own
    # helper notes "pre-kill we still learn hrwOwner from any node"). Requiring an
    # owner-authoritative view is ceremony borrowed from that test, which needs it because it
    # inspects the replica SET; demanding it here made the step fail on a 4-partition stream
    # where the round-robin mgmt gateway need not land on partition 0's owner within the retry
    # budget — a harness-routing artifact, not a durability signal.
    served=$(json_scalar "$body" servedByOwner)
    log_info "replicas view for partition ${KILL_PARTITION}: servedByOwner=${served} (identity-only; not asserted)"

    owner=$(json_scalar "$body" hrwOwner)
    assert_ne "$owner" "" "HRW owner identified for partition ${KILL_PARTITION}"
    assert_ne "$owner" "none" "HRW owner is not 'none'"
    OWNER_TO_KILL="$owner"
}

# The core of #508: SIGKILL the owner WHILE publishes are in flight, so the recorded
# ack set spans the kill window. Publishes that fail during the window are simply not
# recorded — the contract is about ACKED events only.
test_kill_owner_under_concurrent_publish() {
    if [ -z "$OWNER_TO_KILL" ]; then
        log_fail "No owner identified — cannot run the crash step"
        return 1
    fi

    publish_range_recording_acks "$N_PRE" "$N_DURING" "$ACKED_DURING" &
    PUBLISHER_PID=$!

    sleep 2   # let the concurrent publisher get into the window before the kill lands

    log_info "SIGKILL (docker kill) of partition-${KILL_PARTITION} owner ${OWNER_TO_KILL} with publishes in flight"
    if ! kill_node "$OWNER_TO_KILL"; then
        log_fail "kill_node failed for ${OWNER_TO_KILL}"
        reap_publisher
        return 1
    fi

    reap_publisher

    local during
    during=$(grep -c . "$ACKED_DURING" || true)
    log_info "Concurrent window: ${during}/${N_DURING} publishes ACKED across the kill"
    log_pass "Owner ${OWNER_TO_KILL} hard-killed with ${during} concurrent acks recorded"
}

test_failover_completed() {
    if ! wait_for "Partition ${KILL_PARTITION} re-owned by a surviving node" \
        "[ -n \"\$(json_scalar \"\$(replicas_snapshot_owner_view 4)\" hrwOwner)\" ]" 180; then
        log_fail "Partition ${KILL_PARTITION} never regained an owner after the crash"
        return 1
    fi
    local owner
    owner=$(json_scalar "$(replicas_snapshot_owner_view 6)" hrwOwner)
    assert_ne "$owner" "$OWNER_TO_KILL" "Partition ${KILL_PARTITION} owner changed after crash (now ${owner})"
}

# THE assertion. Every event the system ACKED — before and during the crash — must be
# readable afterwards. A missing ACKED event is a durability violation: the caller was
# told the write was durable.
test_every_acked_event_survives_the_crash() {
    local payloads missing=0 total=0 idx
    payloads=$(all_payloads)

    while IFS= read -r idx; do
        [ -n "$idx" ] || continue
        total=$((total + 1))
        if ! printf '%s' "$payloads" | grep -qx "$(marker_for "$idx")"; then
            missing=$((missing + 1))
            [ "$missing" -le 5 ] && log_warn "MISSING acked marker: $(marker_for "$idx")"
        fi
    done < <(cat "$ACKED_PRE" "$ACKED_DURING")

    # NON-VACUITY GATE. Measured 2026-08-12: on a run where the blueprint failed to activate,
    # nothing was published, and this assertion reported "Every ACKED event survived the crash
    # (0 acked, 0 missing)" — a PASS proving nothing. A durability assertion over an empty ack
    # set is exactly the looks-green-proves-nothing shape this test exists to disprove, so the
    # count is now asserted before the survival claim is allowed to mean anything.
    assert_gt "$total" "0" "Non-vacuity: at least one ACKED event to check (${total})"
    assert_eq "$missing" "0" "Every ACKED event survived the crash (${total} acked, ${missing} missing)"
}

# Per-partition, independently: offsets must be contiguous and ascending. A gap means
# an event was lost from the middle of a log — which an aggregate count can hide when
# another partition over-delivers.
test_per_partition_offsets_contiguous_and_ordered() {
    local p failures=0 checked=0
    for ((p = 0; p < PARTITIONS; p++)); do
        local offsets prev="" off gaps=0 unordered=0 n=0
        offsets=$(partition_events "$p" | awk '{print $1}')
        [ -n "$offsets" ] || continue
        checked=$((checked + 1))
        while IFS= read -r off; do
            [ -n "$off" ] || continue
            n=$((n + 1))
            if [ -n "$prev" ]; then
                [ "$off" -le "$prev" ] && unordered=$((unordered + 1))
                [ "$off" -ne $((prev + 1)) ] && gaps=$((gaps + 1))
            fi
            prev="$off"
        done <<< "$offsets"

        if [ "$gaps" -ne 0 ] || [ "$unordered" -ne 0 ]; then
            log_fail "Partition ${p}: ${n} events, ${gaps} offset gap(s), ${unordered} out-of-order — per-partition WAL replay is not contiguous"
            failures=$((failures + 1))
        else
            log_info "Partition ${p}: ${n} events, offsets contiguous and ascending"
        fi
    done

    assert_ge "$checked" "2" "Checked at least 2 non-empty partitions independently (${checked})"
    assert_eq "$failures" "0" "All ${checked} non-empty partitions replayed contiguously and in order"
}

# Liveness after recovery: the stream still accepts writes and serves them.
test_post_crash_liveness() {
    local base=$((N_PRE + N_DURING)) ok=0 i
    for ((i = 0; i < 5; i++)); do
        publish_marker $((base + i)) && ok=$((ok + 1))
    done
    assert_eq "$ok" "5" "Stream accepts new publishes after crash recovery (${ok}/5)"

    local payloads present=0
    payloads=$(all_payloads)
    for ((i = 0; i < 5; i++)); do
        printf '%s' "$payloads" | grep -qx "$(marker_for $((base + i)))" && present=$((present + 1))
    done
    assert_eq "$present" "5" "Post-crash publishes are readable (${present}/5)"
}

# Cleanup is load-bearing for the NEXT test, not just this one.
#
# Measured 2026-08-12: with this test present, the following test
# (test-stream-replica-failover.sh, which sorts immediately after it) failed at its
# FIRST step — "RF=2 replica set not placed within 60s", with an hrwOwner that was a
# ULID-named CTM replacement stuck SYNCING at confirmedOffset -1. An isolation run with
# this file held aside was 7/0 green. So this test was leaving the cluster unable to
# place a fresh RF=2 replica set.
#
# Why this test and not the other kill tests in the suite: they kill nodes but do not
# leave a FOUR-partition RF=2 stream whose owner they killed. Deleting the stream is not
# enough — placement state has to actually drain before the next test asks for a new
# replica set on the same cluster. So cleanup now waits for the deletion to be visible
# and for the generation to quiesce, instead of returning the moment the delete call
# returns.
#
# NOTE (honest status): the interaction above is MEASURED; this mitigation is NOT yet
# verified to resolve it — that needs a full suite re-run with this file present.
cleanup() {
    reap_publisher
    rm -f "$ACKED_PRE" "$ACKED_DURING"

    # UNDEPLOY FIRST — this is the whole fix, and the order is load-bearing.
    #
    # Measured across three runs: this test is the only one in the suite that leaves a live
    # REPLICATED stream behind. The other six deploy `test-echo`, which declares no streams.
    # While `test-stream-multipart` stays deployed, its 4-partition RF=2 `multipart-events`
    # keeps replicating, and the next test's own RF=2 stream cannot establish replication —
    # observed twice as replicas stuck SYNCING at confirmedOffset -1 (run 1 failed at deploy,
    # "RF=2 replica set not placed within 60s"; run 3 got past deploy and failed at failover
    # with both replicas SYNCING). Deleting the STREAM alone cannot work: the slice recreates
    # it on activation. Removing the BLUEPRINT is what actually stops it.
    aether_failover blueprints delete "$STREAM_BP" --force >/dev/null 2>&1 || true

    wait_for "blueprint ${STREAM_BP} undeployed" \
        "[ \$(slices_active_instances_for '${STREAM_BP}') -eq 0 ]" 120 \
        || log_warn "cleanup: ${STREAM_BP} still has active instances — next test may contend for replica placement"

    # Now the stream delete can actually take effect, with nothing recreating it.
    aether_failover streams delete "$STREAM_NAME" >/dev/null 2>&1 || true

    # NOT waited on: "stream gone after delete". Measured 2026-08-12 — that wait can never
    # succeed here and timed out at 240s: the test-stream-multipart SLICE is still deployed,
    # and its StreamPublisherFactory re-creates the declared stream on activation. A stream
    # cannot be deleted out from under a live slice that declares it. Deleting is still worth
    # attempting (it drops accumulated events on a shared cluster), but only as best-effort —
    # which is exactly the idiom every other chaos test in this suite uses.

    # Let the reconciler settle before handing the cluster over, so the next test does not
    # deploy into a mid-reshuffle generation. Signature is
    # (endpoint, epoch, timeout) — an earlier version passed a bare timeout, which was read
    # as the ENDPOINT and produced "could not read generation epoch from 180".
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 60 \
        || log_warn "cleanup: generation did not quiesce — next test may inherit reshuffle churn"

    restore_cluster_baseline || \
        log_warn "cleanup: restore_cluster_baseline reported non-zero; subsequent suites may inherit cluster churn"
}
trap 'cleanup' EXIT

run_test "Initial 5 nodes"                              test_initial_state
run_test "Deploy multi-partition stream blueprint"      test_deploy_multipart_blueprint
run_test "Publish ${N_PRE}-event pre-kill history"      test_publish_pre_kill_history
run_test "Events spread across partitions"              test_events_spread_across_partitions
run_test "Pre-kill history readable"                    test_pre_kill_history_readable
run_test "Identify partition owner"                     test_identify_partition_owner
run_test "SIGKILL owner under concurrent publish"       test_kill_owner_under_concurrent_publish
run_test "Failover completed"                           test_failover_completed
run_test "Every ACKED event survives the crash"         test_every_acked_event_survives_the_crash
run_test "Per-partition offsets contiguous + ordered"   test_per_partition_offsets_contiguous_and_ordered
run_test "Post-crash liveness"                          test_post_crash_liveness
print_summary
