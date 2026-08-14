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

# Per-node app endpoints, newline-separated (see `node_app_endpoints` in lib/cluster.sh for why
# the LB cannot be trusted and why containers are enumerated dynamically).
#
# RE-RESOLVED on demand rather than once at deploy: this suite KILLS a node, and a stale endpoint
# list makes every later call burn `_api_call`'s full 30s timeout against a dead port. Measured
# 2026-08-14 in a full run — resolving once turned a 360s readiness budget into 1254s and a 480s
# convergence budget into 4990s, and the suite reported a product failure against a healthy cluster.
ENTITY_APP_ENDPOINTS=""

refresh_app_endpoints() {
    ENTITY_APP_ENDPOINTS="$(node_app_endpoints || printf '')"
    [ -n "$ENTITY_APP_ENDPOINTS" ]
}

# POST to each node in turn until `matcher` matches the body. Echoes the matching body.
# Re-resolves once, mid-sweep, if nothing matched — that covers a kill landing between passes.
entity_post_any() {
    local path="$1" payload="$2" matcher="$3" pass ep body last=""

    [ -n "$ENTITY_APP_ENDPOINTS" ] || refresh_app_endpoints || return 1

    for pass in 1 2; do
        while IFS= read -r ep; do
            [ -z "$ep" ] && continue
            body=$(_api_call POST "${ep}${path}" "$payload" 2>/dev/null) || continue
            if printf '%s' "$body" | grep -qE "$matcher"; then
                printf '%s' "$body"
                return 0
            fi
            last="$body"
        done <<< "$ENTITY_APP_ENDPOINTS"
        [ "$pass" -eq 1 ] && refresh_app_endpoints >/dev/null 2>&1
    done

    [ -n "$last" ] && printf '%s' "$last"
    return 1
}

# Rotates over every node so a key's committed owner is actually reached. Two passes:
# ownership can commit between them, and a node killed mid-suite simply fails its leg.
#
# `EntityAlreadyExists` is treated as OUR create having landed, not as a failure. These
# keys are unique to this run and nothing else writes them, so the only way the key can
# exist is that one of our own attempts was accepted and its response never reached us
# (a lost ack — the node was killed, or the connection reset). Counting that as a
# failure UNDER-counts acks, which is what made the first run report 4/40; the readback
# in the durability assertion is what confirms the value, so a wrong guess here cannot
# manufacture a pass.
create_entity() {
    local idx="$1" key amount payload body
    key="$(key_for "$idx")"
    amount="$(amount_for "$idx")"
    payload="{\"orderId\":\"${key}\",\"status\":\"placed\",\"amount\":${amount}}"

    # `EntityAlreadyExists` counts as OUR create having landed. These keys are unique to this run and
    # nothing else writes them, so the only way the key can exist is that one of our own attempts
    # was accepted and its response never reached us (a lost ack — the node was killed, or the
    # connection reset). Counting that as failure UNDER-counts acks, which is what made the first
    # run report 4/40. The durability assertion re-reads the value, so a wrong guess here cannot
    # manufacture a pass.
    if body=$(entity_post_any "/api/entity/create" "$payload" \
                              '"outcome"[[:space:]]*:[[:space:]]*"created"|"failureType"[[:space:]]*:[[:space:]]*"EntityAlreadyExists"'); then
        return 0
    fi

    if [ -n "$body" ]; then
        log_warn "create ${key}: no node accepted; last body: $(printf '%s' "$body" | head -c 200)" >&2
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

read_amount() {
    local key="$1" body

    # A node outside the key's replica set answers `PartitionNotHeld` — a STABLE refusal meaning
    # "ask another node", NOT "absent". Summing negatives across nodes would read a live entity as
    # lost and turn the durability assertion into a false alarm, so this looks for a POSITIVE
    # answer from any node.
    #
    # Every log helper writes to STDOUT and this function's stdout IS the parsed amount, so
    # diagnostics must be redirected or they silently corrupt the compared value.
    if body=$(entity_post_any "/api/entity/get" "{\"orderId\":\"${key}\"}" \
                              '"outcome"[[:space:]]*:[[:space:]]*"found"'); then
        printf '%s' "$body" | sed -E 's/.*"amount"[[:space:]]*:[[:space:]]*(-?[0-9]+).*/\1/'
        return 0
    fi

    if [ -n "$body" ]; then
        log_warn "read ${key}: no node returned it; last body: $(printf '%s' "$body" | head -c 200)" >&2
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

    # Resolve endpoints FIRST. The readiness probe used to go through `app_post`, i.e. the pinned
    # APP_ENDPOINT — which in a full run points at whichever node a previous chaos suite killed.
    # Measured 2026-08-14: the slice was deployed and healthy, the probe never reached it, and the
    # suite burned 1254s against a 360s budget before declaring the product broken.
    if ! refresh_app_endpoints; then
        log_fail "could not resolve any per-node app endpoint — cannot reach the cluster"
        return 1
    fi

    # Probe every node: the slice need not be placed on all of them, so ANY node answering is
    # readiness. `__probe__` does not exist, and a "not found" answer still proves the route is wired.
    if ! wait_for "entity slice answering on some node" \
        'refresh_app_endpoints >/dev/null 2>&1; entity_post_any "/api/entity/get" "{\"orderId\":\"__probe__\"}" "\"outcome\"" >/dev/null' 240; then
        log_fail "entity slice never became reachable on any node"
        return 1
    fi

    log_pass "entity blueprint deployed; $(printf '%s' "$ENTITY_APP_ENDPOINTS" | grep -c .) per-node app endpoints resolved"
}

# Ownership is minted per (entity:orders, partition) arc, and the write barrier
# additionally needs each partition's replica set populated before
# minSyncReplicas can be met. Probing ONE key certifies ONE partition — the
# Forge run failed in exactly that gap — so this probes a spread of keys.
# Ownership is minted per (entity:orders, partition) arc, and the write barrier
# additionally needs each partition's replica set populated before minSyncReplicas can
# be met. Probing ONE key certifies ONE partition, so this probes a spread.
#
# Each poll uses a FRESH key block. The first version reused keys 900-911 every poll,
# so once a key existed every later poll got `EntityAlreadyExists` — which it counted as a
# failure, making convergence UNREACHABLE by construction. It timed out at 481s for
# that reason alone, with nothing to say about ownership. A probe whose own success
# poisons its next attempt measures nothing.
PROBE_ROUND=0

probe_partition_spread() {
    local i ok=1 base
    PROBE_ROUND=$((PROBE_ROUND + 1))
    base=$((20000 + PROBE_ROUND * 100))

    for ((i = 0; i < 12; i++)); do
        create_entity $((base + i)) || ok=0
    done

    [ "$ok" -eq 1 ]
}

test_ownership_converged_across_partitions() {
    if ! wait_for "entity ownership converged across partitions" 'probe_partition_spread' 240; then
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

    # Bring back the node we SIGKILLed, or the cluster is left permanently short.
    #
    # Cluster B is `restart: "no"` — the policy that makes `docker kill` authoritative — so
    # nothing resurrects the container on its own. `restore_cluster_baseline` escalates to a
    # full `restart_all_nodes` ONLY when no leader is reachable via the management API; after a
    # single-node kill the leader is perfectly fine, so it instead waits out its whole budget on
    # a node that can never return. Observed: `deficit=1`, `lastReason=NONE_PROVISIONING`,
    # "cluster WHOLE" timing out at 917s, and the harness declaring cluster B unrecoverable —
    # which SKIPS every remaining destructive suite. 02w happens to run last in
    # CLUSTER_B_SUITES, so nothing was actually skipped, but a suite that depends on its own
    # position in the list to be harmless is one reorder away from poisoning the run.
    if [ "$KILL_CONFIRMED" -eq 1 ] && [ -n "$NODE_TO_KILL" ]; then
        start_node "$NODE_TO_KILL" \
            || log_warn "cleanup: could not restart ${NODE_TO_KILL} — cluster left at N-1"
    fi
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
