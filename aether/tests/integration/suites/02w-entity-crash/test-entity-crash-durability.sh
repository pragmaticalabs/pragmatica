#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 02w — durable-entity crash durability (#345 I3)
#
# The SIGKILL tier Forge structurally cannot provide.
#
# `DurableEntityForgeTest` proves FAILOVER: stop a node, ownership moves,
# survivors serve the state. It cannot prove CRASH durability, because every
# in-JVM stop routes through `AetherNode.stop()` -> `close()`, which closes the
# WAL cleanly — graceful and hard stop are durability-EQUIVALENT in-JVM
# (established empirically for streams in #431/#508), so the crash-mid-fsync
# boundary is unreachable there. `docker kill` on cluster B (`restart: "no"`,
# so nothing resurrects the container) is the only place that boundary exists.
#
# What is asserted, and why scoped this way:
#
#   * Every entity create that ACKED must read back with its EXACT written
#     value after the owner is SIGKILLed. The ack IS the durability claim — an
#     entity write does not resolve until the record is fsync-durable on the
#     owner AND held by `minSyncReplicas` — so demanding more would assert a
#     guarantee the system does not make, and demanding less would not test the
#     one it does. Creates that did NOT ack may legitimately be absent.
#   * The amount is derived from the key index, so a readback proves the value
#     belongs to THAT key. A constant would pass even if the fold mixed keys up.
#   * Assertions are on DATA, never on a self-reported status field (#508 passed
#     11/0 while a status-gated test failed on the same cluster at the same
#     moment).
#   * The #345 I3 checkpoint surface is read as a LIVENESS SENSOR: a checkpoint
#     driver that stopped has no other symptom, and it is the only thing that
#     bounds an entity log.
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"
source "${SCRIPT_DIR}/../../lib/generation.sh"

ENTITY_BP="${ENTITY_BP:-org.pragmatica.aether.test:test-entity-entity-slice:1.0.0}"
N_PRE="${N_PRE:-40}"
N_DURING="${N_DURING:-40}"

KEY_PREFIX="ENTDUR"
ACKED_PRE="$(mktemp)"
ACKED_DURING="$(mktemp)"
NODE_TO_KILL=""
CREATOR_PID=""
KILL_CONFIRMED=0

# Fixed-width zero-padded + terminator so no key is a substring of a sibling.
key_for() {
    printf '%s-%05d-Z' "$KEY_PREFIX" "$1"
}

# Derived from the index: a readback proves the value belongs to THAT key.
amount_for() {
    printf '%d' "$(( $1 * 7 + 3 ))"
}

# `app_post` round-robins across nodes, and only the committed owner of a key's
# arc may accept a create — so a single attempt lands on a non-owner most of the
# time and is refused with NotCurrentOwner. Retrying until one node accepts is
# what turns that into a create; it is NOT a durability retry, and the ack it
# records is a single node's single acceptance.
create_entity() {
    local idx="$1" key amount body attempt payload last_body=""
    key="$(key_for "$idx")"
    amount="$(amount_for "$idx")"
    payload="{\"orderId\":\"${key}\",\"status\":\"placed\",\"amount\":${amount}}"

    for ((attempt = 0; attempt < 10; attempt++)); do
        # Let the LAST attempt's stderr through. `_api_call` deliberately logs
        # status + the first 300 bytes of any error body there — it was built for
        # exactly this, after `curl -sf` silently dropped the bodies that named
        # cloud failures ("NotLeader", "TaskGroupInactive"). Blanket `2>/dev/null`
        # threw that away, which is why the first run of this suite recorded 36
        # failed creates with no recoverable cause. Unsuppressing all ten attempts
        # would print 400 near-identical warns; one per create is the bounded middle.
        if [ "$attempt" -eq 9 ]; then
            body=$(app_post "/api/entity/create" "$payload") || continue
        else
            body=$(app_post "/api/entity/create" "$payload" 2>/dev/null) || continue
        fi
        printf '%s' "$body" | grep -q '"outcome"[[:space:]]*:[[:space:]]*"created"' && return 0
        last_body="$body"
    done

    # The case with NO diagnostic at all before this: HTTP 2xx whose outcome is not
    # "created". A refusal like NotCurrentOwner is a business outcome, not an HTTP
    # error, so `_api_call` returns 0 and stays silent and the body is dropped on the
    # floor. Telling "every node refused ownership" apart from "the minSyncReplicas
    # barrier is unsatisfiable" is exactly what a low ack count needs, and only the
    # body says which.
    if [ -n "$last_body" ]; then
        log_warn "create ${key}: 10 attempts, none accepted; last 2xx body: $(printf '%s' "$last_body" | head -c 200)" >&2
    fi
    return 1
}

create_range_recording_acks() {
    local start="$1" count="$2" outfile="$3"
    local i idx
    for ((i = 0; i < count; i++)); do
        idx=$((start + i))
        if create_entity "$idx"; then
            printf '%s\n' "$idx" >> "$outfile"
        fi
    done
}

# A node outside the key's replica set answers PartitionNotHeld — a STABLE
# refusal, not "absent" — so this looks for a positive answer across attempts
# rather than summing negatives. "I do not hold this" and "this does not exist"
# are different claims and must not be conflated.
read_amount() {
    local key="$1" body attempt payload last_body=""
    payload="{\"orderId\":\"${key}\"}"

    for ((attempt = 0; attempt < 10; attempt++)); do
        # Same bounded-diagnostic shape as create_entity: only the final attempt
        # lets `_api_call`'s stderr through. Every log helper here writes to
        # STDOUT, and this function's stdout IS the parsed amount — so any
        # diagnostic must be explicitly redirected or it silently corrupts the
        # value the durability assertion compares.
        if [ "$attempt" -eq 9 ]; then
            body=$(app_post "/api/entity/get" "$payload") || continue
        else
            body=$(app_post "/api/entity/get" "$payload" 2>/dev/null) || continue
        fi
        if printf '%s' "$body" | grep -q '"outcome"[[:space:]]*:[[:space:]]*"found"'; then
            printf '%s' "$body" | sed -E 's/.*"amount"[[:space:]]*:[[:space:]]*(-?[0-9]+).*/\1/'
            return 0
        fi
        last_body="$body"
    done

    if [ -n "$last_body" ]; then
        log_warn "read ${key}: 10 attempts, never found; last 2xx body: $(printf '%s' "$last_body" | head -c 200)" >&2
    fi
    printf ''
    return 1
}

reap_creator() {
    if [ -n "$CREATOR_PID" ] && kill -0 "$CREATOR_PID" 2>/dev/null; then
        if kill "$CREATOR_PID" 2>/dev/null; then
            wait "$CREATOR_PID" 2>/dev/null
        fi
        log_info "concurrent creator ${CREATOR_PID} reaped (non-zero exit expected when the owner dies mid-create)"
    fi
    CREATOR_PID=""
}

# ---------------------------------------------------------------------------
# tests
# ---------------------------------------------------------------------------

test_deploy_entity_blueprint() {
    await_generation_quiesced >/dev/null 2>&1 || log_warn "generation not quiesced before deploy — proceeding"
    deploy_blueprint "$ENTITY_BP" >/dev/null 2>&1 || true

    if ! wait_for "entity slice answering" \
        '[ -n "$(app_post "/api/entity/get" "{\"orderId\":\"__probe__\"}" 2>/dev/null)" ]' 180; then
        log_fail "entity slice never became reachable"
        return 1
    fi
    log_pass "entity blueprint deployed and answering"
}

# Ownership is minted per (entity:orders, partition) arc, and the write barrier
# additionally needs each partition's replica set populated before
# minSyncReplicas can be met. Probing ONE key certifies ONE partition — the
# Forge run failed in exactly that gap — so this probes a spread of keys.
test_ownership_converged_across_partitions() {
    if ! wait_for "entity ownership converged across partitions" \
        'ok=1; for i in 900 901 902 903 904 905 906 907 908 909 910 911; do create_entity "$i" || ok=0; done; [ "$ok" -eq 1 ]' 240; then
        log_fail "entity ownership never converged across all partitions"
        return 1
    fi
    log_pass "every probed partition accepts writes"
}

test_create_pre_kill_history() {
    create_range_recording_acks 0 "$N_PRE" "$ACKED_PRE"

    local acked
    acked=$(grep -c . "$ACKED_PRE" || true)
    log_info "pre-kill: ${acked}/${N_PRE} creates ACKED"

    if [ "${acked:-0}" -eq 0 ]; then
        log_fail "no pre-kill create ACKED — nothing to hold the system to"
        return 1
    fi
    log_pass "${acked} pre-kill creates ACKED"
}

test_pre_kill_state_readable() {
    local idx key expected actual bad=0
    while read -r idx; do
        [ -n "$idx" ] || continue
        key="$(key_for "$idx")"
        expected="$(amount_for "$idx")"
        actual="$(read_amount "$key" || true)"
        if [ "$actual" != "$expected" ]; then
            log_error "pre-kill readback mismatch for ${key}: expected ${expected}, got '${actual}'"
            bad=$((bad + 1))
        fi
    done < "$ACKED_PRE"

    if [ "$bad" -ne 0 ]; then
        log_fail "${bad} pre-kill entities did not read back correctly"
        return 1
    fi
    log_pass "every pre-kill ACKED entity reads back with its written value"
}

# `pick_non_leader` REQUIRES an observed leader and fail-fasts without one — its own
# docstring says the caller must wait_for_leader first, because a candidate picked
# against a racing re-election might BE the leader by the time it is killed.
test_identify_node_to_kill() {
    local leader
    wait_for_leader >/dev/null 2>&1 || true
    leader=$(cluster_leader 2>/dev/null || printf '')

    if [ -z "$leader" ] || [ "$leader" = "none" ]; then
        log_fail "no stable leader observed — cannot pick a node to kill safely"
        return 1
    fi

    NODE_TO_KILL=$(pick_non_leader "$leader" 2>/dev/null || printf '')
    if [ -z "$NODE_TO_KILL" ]; then
        log_fail "could not identify a node to kill"
        return 1
    fi
    log_pass "will SIGKILL ${NODE_TO_KILL}"
}

# The core: SIGKILL WHILE creates are in flight, so the recorded ack set spans
# the kill window rather than stopping safely before it.
test_kill_node_under_concurrent_creates() {
    if [ -z "$NODE_TO_KILL" ]; then
        log_fail "no node identified — cannot run the crash step"
        return 1
    fi

    create_range_recording_acks "$N_PRE" "$N_DURING" "$ACKED_DURING" &
    CREATOR_PID=$!

    sleep 2   # let the concurrent creator get into the window before the kill lands

    log_info "SIGKILL (docker kill) of ${NODE_TO_KILL} with creates in flight"
    if ! kill_node "$NODE_TO_KILL"; then
        log_fail "kill_node failed for ${NODE_TO_KILL}"
        reap_creator
        return 1
    fi

    KILL_CONFIRMED=1
    reap_creator

    local during
    during=$(grep -c . "$ACKED_DURING" || true)
    log_info "concurrent window: ${during}/${N_DURING} creates ACKED across the kill"
    log_pass "${NODE_TO_KILL} hard-killed with ${during} concurrent acks recorded"
}

# A cluster that never settles after the crash is a REAL failure, not a caveat to
# wave through. Demoting it to a warning and passing anyway would hide exactly the
# condition worth reporting — and would leave the durability assertion below running
# against a cluster still in motion, so its verdict would mean less than it appears to.
test_failover_completed() {
    if [ "$KILL_CONFIRMED" -ne 1 ]; then
        log_fail "no SIGKILL was performed — there is no failover to assess"
        return 1
    fi

    if ! wait_for "cluster settled after SIGKILL" 'await_generation_quiesced >/dev/null 2>&1' 240; then
        log_fail "cluster never reached a steady state within 240s of the SIGKILL"
        return 1
    fi
    log_pass "cluster reached a post-crash steady state"
}

# THE assertion.
# NON-VACUITY, part 2 — and the part the first run of this suite was missing.
#
# Guarding only against an empty ack set is NOT enough. On that run the node-pick
# failed, so no SIGKILL was ever performed, and this test happily reported "all 4
# ACKED entities survived the crash" — a pass asserting nothing, because there was
# no crash. That is the #508 lesson one level up: the ack-count gate was present and
# still let a hollow pass through. The crash itself must be a precondition.
test_every_acked_entity_survives_the_crash() {
    local total=0 missing=0 wrong=0 idx key expected actual f

    if [ "$KILL_CONFIRMED" -ne 1 ]; then
        log_fail "no SIGKILL was performed — this assertion would pass without testing anything"
        return 1
    fi

    for f in "$ACKED_PRE" "$ACKED_DURING"; do
        while read -r idx; do
            [ -n "$idx" ] || continue
            total=$((total + 1))
            key="$(key_for "$idx")"
            expected="$(amount_for "$idx")"
            actual="$(read_amount "$key" || true)"

            if [ -z "$actual" ]; then
                missing=$((missing + 1))
                log_error "LOST after SIGKILL: ${key} (acked, now unreadable)"
            elif [ "$actual" != "$expected" ]; then
                wrong=$((wrong + 1))
                log_error "CORRUPTED after SIGKILL: ${key} expected ${expected}, got ${actual}"
            fi
        done < "$f"
    done

    # Non-vacuity gate. An empty ack set makes "0 missing" trivially true —
    # #508 shipped exactly that bug, reporting "0 acked, 0 missing" as PASS.
    if [ "$total" -eq 0 ]; then
        log_fail "no ACKED creates to verify — the assertion would be vacuous"
        return 1
    fi

    log_info "post-SIGKILL: ${total} acked, ${missing} missing, ${wrong} corrupted"

    if [ "$missing" -ne 0 ] || [ "$wrong" -ne 0 ]; then
        log_fail "${missing}/${total} lost and ${wrong}/${total} corrupted after the crash"
        return 1
    fi
    log_pass "all ${total} ACKED entities survived the crash with their exact values"
}

# The checkpoint driver is the only thing bounding an entity log, and a driver
# that stopped shows no other symptom — writes and reads keep succeeding. This
# reads the #345 I3 observability surface as a regression sensor.
#
# `/api/entity/checkpoints` is a LOCAL route, and `api_get` does NOT rotate:
# `_resolve_live_endpoint` returns the pinned CLUSTER_ENDPOINT whenever it is
# healthy, and only walks other nodes once that one is DEAD. The previous shape
# therefore asked ONE node ten times and would have concluded "no node reported an
# entity keyspace" even with four other nodes reporting one. A per-node question
# needs a per-node sweep — `node_api_get <offset>`, which resolves each node's own
# management port on docker and its own public IP on cloud.
#
# Writes are summed CLUSTER-WIDE because zero on a given node is not a defect:
# `checkpointPartition` skips any partition whose `checkpointableThrough` is -1,
# i.e. one this node never folded, so a node hosting the keyspace while owning no
# partition correctly writes nothing. Only "nowhere in the cluster" is a failure.
CHECKPOINT_HOSTING=0
CHECKPOINT_WRITES=0
CHECKPOINT_FAILURES=0
CHECKPOINT_DETAIL=""

collect_checkpoints() {
    local i body w f
    CHECKPOINT_HOSTING=0
    CHECKPOINT_WRITES=0
    CHECKPOINT_FAILURES=0
    CHECKPOINT_DETAIL=""

    for i in $(seq 0 $((NODE_COUNT - 1))); do
        body=$(node_api_get "$i" "/api/entity/checkpoints" 2>/dev/null || printf '')
        printf '%s' "$body" | grep -q '"keyspace"' || continue
        CHECKPOINT_HOSTING=$((CHECKPOINT_HOSTING + 1))
        # Sum EVERY occurrence: a node may host more than one keyspace, and the
        # greedy `.*"writes"` sed this replaced would report only the last one — or,
        # on a body with no match at all, pass the whole JSON through unchanged and
        # blow up the numeric comparison it fed. `|| true` because `pipefail` turns a
        # no-match grep into a failed assignment under `set -e`.
        w=$(printf '%s' "$body" | grep -oE '"writes"[[:space:]]*:[[:space:]]*[0-9]+' \
            | grep -oE '[0-9]+$' | awk '{s += $1} END {print s + 0}' || true)
        f=$(printf '%s' "$body" | grep -oE '"failures"[[:space:]]*:[[:space:]]*[0-9]+' \
            | grep -oE '[0-9]+$' | awk '{s += $1} END {print s + 0}' || true)
        CHECKPOINT_WRITES=$((CHECKPOINT_WRITES + ${w:-0}))
        CHECKPOINT_FAILURES=$((CHECKPOINT_FAILURES + ${f:-0}))
        CHECKPOINT_DETAIL="${CHECKPOINT_DETAIL}node-$((i + 1))=${w:-0}w/${f:-0}f "
    done
}

test_checkpoint_driver_is_alive() {
    # Bounded wait rather than a single sample: ENTITY_CHECKPOINT_INTERVAL is 30s,
    # so sampling once can land before the first tick and fail on a tick boundary
    # instead of on a defect. `wait_for` evals its predicate in the CURRENT shell,
    # so the collected globals survive it.
    if wait_for "a successful checkpoint write somewhere in the cluster" \
        'collect_checkpoints; [ "$CHECKPOINT_HOSTING" -gt 0 ] && [ "$CHECKPOINT_WRITES" -gt 0 ]' 120; then
        log_pass "checkpoint driver alive across ${CHECKPOINT_HOSTING} node(s): ${CHECKPOINT_DETAIL}"
        return 0
    fi

    collect_checkpoints
    if [ "$CHECKPOINT_HOSTING" -eq 0 ]; then
        log_fail "no node reported an entity keyspace while the entity slice is deployed"
        return 1
    fi
    log_fail "${CHECKPOINT_HOSTING} node(s) host the keyspace but none wrote a checkpoint (${CHECKPOINT_DETAIL}) — the entity log is not being bounded"
    return 1
}

test_post_crash_liveness() {
    if ! create_entity 9999; then
        log_fail "cluster does not accept new entity creates after the crash"
        return 1
    fi

    local actual
    actual="$(read_amount "$(key_for 9999)" || true)"
    if [ "$actual" != "$(amount_for 9999)" ]; then
        log_fail "post-crash create did not read back correctly (got '${actual}')"
        return 1
    fi
    log_pass "cluster accepts and serves new entity writes after the crash"
}

cleanup() {
    reap_creator
    rm -f "$ACKED_PRE" "$ACKED_DURING" 2>/dev/null
    # Removing the BLUEPRINT is what actually stops the slice — undeploying the
    # instance leaves the blueprint active and the controller re-places it.
    api_delete "/api/blueprints/${ENTITY_BP}" >/dev/null 2>&1 || true
}

trap 'cleanup' EXIT

run_test "Deploy durable-entity blueprint"            test_deploy_entity_blueprint
run_test "Ownership converged across partitions"      test_ownership_converged_across_partitions
run_test "Create ${N_PRE}-entity pre-kill history"    test_create_pre_kill_history
run_test "Pre-kill state readable"                    test_pre_kill_state_readable
run_test "Identify node to kill"                      test_identify_node_to_kill
run_test "SIGKILL node under concurrent creates"      test_kill_node_under_concurrent_creates
run_test "Failover completed"                         test_failover_completed
run_test "Every ACKED entity survives the crash"      test_every_acked_entity_survives_the_crash
run_test "Checkpoint driver alive"                    test_checkpoint_driver_is_alive
run_test "Post-crash liveness"                        test_post_crash_liveness

print_summary
