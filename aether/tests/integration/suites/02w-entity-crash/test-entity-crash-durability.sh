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
#     owner AND held by `confirmation_factor` — so demanding more would assert a
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

# The BLUEPRINT coordinate (`test-entity`, which carries META-INF/blueprint.toml), not the slice
# artifact `test-entity-entity-slice`: deploying the slice coordinate is refused (no blueprint.toml),
# and that refusal was swallowed, so the suite ran for 20 minutes against an entity slice that did
# not exist (2026-09-24, after a full-drain recovery had wiped the runner's initial deployment).
ENTITY_BP="${ENTITY_BP:-org.pragmatica.aether.test:test-entity:1.0.0}"
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

# NOTE: create_entity and read_amount use entity_post_status below (it keeps a non-2xx answer's status and body).
# entity_post_any remains for the READINESS PROBE only (wait_for ... entity_post_any get __probe__): it measures
# "is the entity service answering yet", so a non-2xx (including a 503) SHOULD read as not-ready and be re-polled
# by wait_for itself; retrying inside it would change what the probe measures.
# POST to the FIRST REACHABLE endpoint and treat ITS answer as authoritative (#596 acceptance
# form). The pre-#596 shape swept every node until `matcher` matched the body — harness-side
# owner-finding that masked the product's missing owner-forwarding, which is exactly what #596
# closed. Now: a TRANSPORT failure moves to the next endpoint (this suite kills nodes — a dead
# port is the harness's problem, and the 2026-08-14 measurement shows what pinning one costs),
# but a WRONG answer from a live node is echoed and fails the caller's assertion: with product
# forwarding, reaching ANY live node must be enough. Pass 2 re-resolves endpoints and retries
# only when NOTHING was reachable (a kill landing mid-sweep).
entity_post_any() {
    local path="$1" payload="$2" matcher="$3" pass ep body

    [ -n "$ENTITY_APP_ENDPOINTS" ] || refresh_app_endpoints || return 1

    for pass in 1 2; do
        while IFS= read -r ep; do
            [ -z "$ep" ] && continue
            body=$(_api_call POST "${ep}${path}" "$payload" 2>/dev/null) || continue
            printf '%s' "$body"
            if printf '%s' "$body" | grep -qE "$matcher"; then
                return 0
            fi
            return 1
        done <<< "$ENTITY_APP_ENDPOINTS"
        [ "$pass" -eq 1 ] && refresh_app_endpoints >/dev/null 2>&1
    done

    return 1
}

# How to treat a refusal (status + body) from one node, shared by the sweep below and by create_entity/read_amount:
#   "retry" — try the next node, and keep retrying until the caller's deadline:
#             * no HTTP status at all (000: curl failure, timeout),
#             * 502 / 504 (a gateway to a dead node) and 404 (the slice is not on that node yet) — the post-kill
#               window: a survivor answering these must not be authoritative, the old `|| continue` moved on,
#             * 503 (the product's answer for a Cause.Transient, #1737/#1765),
#             * an allow-listed transient failureType, whether the answer is a 2xx outcome:refused or a non-2xx.
#   "fatal" — authoritative, fail at once with the full body: a 500, and any other refusal (StorageFailed,
#             ForwardRefused, any failureType off the allow-list, a 2xx outcome that is neither wanted nor transient).
entity_refusal_class() {
    local status="$1" body="$2"
    if transient_failure_type "$body" >/dev/null; then
        printf 'retry'
        return 0
    fi
    case "${status:-000}" in
        000|502|503|504|404) printf 'retry' ;;
        *) printf 'fatal' ;;
    esac
}

# Like entity_post_any, but KEEPS the status and body of a non-2xx answer (`_api_call` prints a body only for a
# 2xx, so a 503 transient refusal would otherwise read as "no node answered"). Output is the body followed by a
# `__ENTITY_HTTP_STATUS:NNN__` line; rc 0 iff a node answered 2xx with a body matching `matcher`, rc 1 otherwise.
# One call sweeps the endpoints (two passes, re-resolving between): a node whose refusal is class "retry" is
# skipped for the next one, so the first node to answer is NOT authoritative for a 502/504/404/503 or a transient
# type, exactly as entity_post_any's `|| continue` moved on from any non-2xx. A "fatal" refusal returns at once.
# When every node was skipped, the LAST refusal is printed (rc 1) so the caller can retry until its deadline and
# then report the full body; when nothing answered at all nothing is printed.
entity_post_status() {
    local path="$1" payload="$2" matcher="$3" pass ep out status body last=""

    [ -n "$ENTITY_APP_ENDPOINTS" ] || refresh_app_endpoints || return 1

    for pass in 1 2; do
        while IFS= read -r ep; do
            [ -z "$ep" ] && continue
            out=$(_api_call POST "${ep}${path}" "$payload" 1 2>/dev/null) || true
            status=$(printf '%s' "$out" | grep -oE '__API_HTTP_STATUS:[0-9]+__' | tail -1 | sed 's/__API_HTTP_STATUS://;s/__//')
            case "${status:-000}" in 000) continue ;; esac
            body=$(printf '%s' "$out" | sed '$d')
            case "$status" in
                2*) if printf '%s' "$body" | grep -qE "$matcher"; then
                        printf '%s\n__ENTITY_HTTP_STATUS:%s__' "$body" "$status"
                        return 0
                    fi ;;
            esac
            last=$(printf '%s\n__ENTITY_HTTP_STATUS:%s__' "$body" "$status")
            if [ "$(entity_refusal_class "$status" "$body")" = "fatal" ]; then
                printf '%s' "$last"
                return 1
            fi
        done <<< "$ENTITY_APP_ENDPOINTS"
        # Pass 2 (re-resolve the endpoints) only when NOTHING answered: a kill landing mid-sweep. A node that
        # answered with a retry-class refusal is the caller's retry loop's business, not a reason to re-ask.
        [ -n "$last" ] && break
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
    # A create refused with an ALLOW-LISTED TRANSIENT failureType, or answered 503, is retried with backoff until
    # ENTITY_CREATE_RETRY_DEADLINE_S (a post-failover owner that is not ready yet answers "retry", and the one
    # attempt this used to make read as "the cluster does not accept creates"). Anything else fails at once:
    # never ForwardRefused or StorageFailed (a genuine storage fault must not be retried into silence), and an
    # empty answer (no node reachable) is not a transient refusal either. The FULL body is logged: a 200-byte
    # cut deleted the inner cause ("... for k") that decides which failure it was.
    local deadline=$((SECONDS + ENTITY_CREATE_RETRY_DEADLINE_S)) delay="$ENTITY_CREATE_RETRY_BACKOFF_S" ft out status
    while :; do
        if out=$(entity_post_status "/api/entity/create" "$payload" \
                                    '"outcome"[[:space:]]*:[[:space:]]*"created"|"failureType"[[:space:]]*:[[:space:]]*"EntityAlreadyExists"'); then
            return 0
        fi
        status=$(printf '%s' "$out" | grep -oE '__ENTITY_HTTP_STATUS:[0-9]+__' | tail -1 | sed 's/__ENTITY_HTTP_STATUS://;s/__//')
        body=$(printf '%s' "$out" | sed '$d')
        # Retry per entity_refusal_class (503, 502/504/404, no answer, an allow-listed failureType) until the
        # deadline; a fatal refusal ends the loop at once with its full body.
        ft=$(transient_failure_type "$body") || ft=""
        [ "$(entity_refusal_class "$status" "$body")" = "fatal" ] && break
        if [ "$SECONDS" -ge "$deadline" ]; then
            log_warn "create ${key}: still refused (HTTP ${status:-none}${ft:+, ${ft}}) at the ${ENTITY_CREATE_RETRY_DEADLINE_S}s retry deadline; last body: ${body:-<no node answered>}" >&2
            return 1
        fi
        log_warn "create ${key}: refusal to retry (HTTP ${status:-none}${ft:+, ${ft}}); retrying in ${delay}s" >&2
        sleep "$delay"
        delay=$(( delay * 2 > 5 ? 5 : delay * 2 ))
    done

    if [ -n "$status" ]; then
        log_warn "create ${key}: no node accepted (HTTP ${status}, not a transient refusal; not retried); last body: ${body}" >&2
    fi
    return 1
}

create_range_recording_acks() {
    local start="$1" count="$2" outfile="$3"
    local i idx
    # Wall-clock budget: run3 spent 8977s here with no bound. Exhaustion only CAPS the acked
    # population (the durability assertion ranges over ACKED keys, so a smaller set stays a
    # valid, smaller experiment) — it is a warning, not a verdict.
    local budget="${CREATE_BUDGET:-900}"
    local phase_deadline=$((SECONDS + budget))
    for ((i = 0; i < count; i++)); do
        if [ "$SECONDS" -ge "$phase_deadline" ]; then
            log_warn "create budget (${budget}s) exhausted after ${i}/${count} creates — proceeding with the acked subset"
            return 1
        fi
        idx=$((start + i))
        if create_entity "$idx"; then
            printf '%s\n' "$idx" >> "$outfile"
        fi
    done
}

# `StorageUnavailable` (EntityError.StorageUnavailable, #1766) is the append boundary's transient refusal that
# used to be flattened into StorageFailed/ForwardRefused; those two stay OFF this list on purpose.
# Refusals that mean "retry", never "no" (#1501). Each is a `Cause.Transient` in the product and
# clears on its own: `FoldInProgress` is a partition holder still replaying its entity log before it
# may serve reads (EntityLogError.java). An EXPLICIT allow-list, so an unknown failure type is never
# retried into silence. Space-separated; extend only with a failureType the product marks transient.
ENTITY_TRANSIENT_FAILURE_TYPES="${ENTITY_TRANSIENT_FAILURE_TYPES:-FoldInProgress OwnershipNotYetCommitted LinearizableUnavailable StorageUnavailable}"
# The same allow-list bounds the CREATE retry (create_entity): ~30s, 1s doubling to 5s.
ENTITY_CREATE_RETRY_DEADLINE_S="${ENTITY_CREATE_RETRY_DEADLINE_S:-30}"
ENTITY_CREATE_RETRY_BACKOFF_S="${ENTITY_CREATE_RETRY_BACKOFF_S:-1}"
# Per-key bound on retrying a transient refusal. s27 cluster B (2026-09-25): three keys refused
# FoldInProgress in the pre-kill readback and read back exactly ~20s later.
TRANSIENT_READ_DEADLINE_S="${TRANSIENT_READ_DEADLINE_S:-60}"
TRANSIENT_READ_BACKOFF_S="${TRANSIENT_READ_BACKOFF_S:-2}"

# transient_failure_type <body>: echo the body's (first) failureType and succeed when it is on the
# allow-list — by exact name, never by substring or case-folding; fail (echoing nothing) otherwise.
transient_failure_type() {
    local ft t
    ft=$(printf '%s' "$1" | grep -oE '"failureType"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 \
        | sed -E 's/.*:[[:space:]]*"([^"]*)"$/\1/' || true)
    [ -n "$ft" ] || return 1
    for t in $ENTITY_TRANSIENT_FAILURE_TYPES; do
        if [ "$t" = "$ft" ]; then
            printf '%s' "$ft"
            return 0
        fi
    done
    return 1
}

read_amount() {
    local key="$1" body ft deadline last_transient=""

    # A node outside the key's replica set answers `PartitionNotHeld` — a STABLE refusal meaning
    # "ask another node", NOT "absent". Summing negatives across nodes would read a live entity as
    # lost and turn the durability assertion into a false alarm, so this looks for a POSITIVE
    # answer from any node.
    #
    # Three-way protocol (2026-08-24) — run4's one "mismatch" line was uninterpretable because ''
    # meant BOTH "no node has it" and "every attempt timed out", so a merely-slow cluster read as
    # data loss:
    #   rc 0 — found: the amount is on stdout.
    #   rc 3 — ABSENT: a node HOLDING the key's arc answered `"outcome":"absent"` (positive
    #          evidence — non-holders answer PartitionNotHeld, never "absent").
    #   rc 4 — NO POSITIVE ANSWER: no node answered at all (timeouts, dead ports), or a live node
    #          answered with something that is neither found nor absent nor transient.
    #   rc 5 — REFUSED-TRANSIENT: a live node kept answering an allow-listed transient refusal
    #          (e.g. FoldInProgress) until TRANSIENT_READ_DEADLINE_S ran out.
    #   rc 4 and 5 mean "the verdict cannot be measured", NEVER evidence of loss.
    #
    # A transient refusal is RETRIED, not classified (#1501): the first live answer used to be
    # final, so a partition still replaying its log landed in rc 4 as "no node answered" — false,
    # a node had answered "retry".
    #
    # Every log helper writes to STDOUT and this function's stdout IS the parsed amount, so
    # diagnostics must be redirected or they silently corrupt the compared value.
    deadline=$((SECONDS + TRANSIENT_READ_DEADLINE_S))
    local out status
    while :; do
        # The status and body of a non-2xx answer are KEPT (entity_post_status): app routes answer 503 for a
        # Cause.Transient (#1737/#1765) and `_api_call` prints a body only for a 2xx, so through entity_post_any a
        # transient refusal read as "no node answered" and was never retried.
        if out=$(entity_post_status "/api/entity/get" "{\"orderId\":\"${key}\"}" \
                                    '"outcome"[[:space:]]*:[[:space:]]*"(found|absent)"'); then
            body=$(printf '%s' "$out" | sed '$d')
            if printf '%s' "$body" | grep -qE '"outcome"[[:space:]]*:[[:space:]]*"found"'; then
                printf '%s' "$body" | sed -E 's/.*"amount"[[:space:]]*:[[:space:]]*(-?[0-9]+).*/\1/'
                return 0
            fi
            return 3
        fi
        status=$(printf '%s' "$out" | grep -oE '__ENTITY_HTTP_STATUS:[0-9]+__' | tail -1 | sed 's/__ENTITY_HTTP_STATUS://;s/__//')
        body=$(printf '%s' "$out" | sed '$d')
        # Retry per entity_refusal_class (503, 502/504/404, no answer, an allow-listed failureType) until the
        # deadline; anything else (a 500, a refusal off the allow-list) ends the loop with the full body.
        ft=$(transient_failure_type "$body") || ft=""
        [ "$(entity_refusal_class "$status" "$body")" = "fatal" ] && break
        [ -n "$body" ] && last_transient="$body"
        if [ "$SECONDS" -ge "$deadline" ]; then
            if [ "$status" = "503" ] || [ -n "$ft" ]; then
                log_warn "read ${key}: a node answered transient (HTTP ${status}${ft:+, ${ft}}) until the ${TRANSIENT_READ_DEADLINE_S}s retry deadline; last body: ${body}" >&2
                return 5
            fi
            break
        fi
        sleep "$TRANSIENT_READ_BACKOFF_S"
    done

    if [ -n "$body" ]; then
        log_warn "read ${key}: a node answered, but not found/absent (HTTP ${status:-none}; not a transient type, or unresolved at the deadline); last body: ${body}" >&2
    elif [ -n "$last_transient" ]; then
        log_warn "read ${key}: no node answered this attempt (every endpoint failed at transport or non-2xx); the previous attempt WAS answered with a transient refusal: ${last_transient}" >&2
    else
        log_warn "read ${key}: no node answered (every endpoint failed at transport or non-2xx)" >&2
    fi
    return 4
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
    # Push first — a recovery that re-bootstrapped the cluster also emptied its artifact store —
    # then deploy, and let a refusal be SEEN: the readiness wait below is the gate, but its
    # timeout cannot say why the slice never appeared.
    push_blueprint "$ENTITY_BP" >/dev/null 2>&1 || log_warn "push_blueprint ${ENTITY_BP} failed — deploying anyway"
    local deploy_out
    deploy_out=$(deploy_blueprint "$ENTITY_BP" 2>&1) || log_warn "deploy_blueprint ${ENTITY_BP} failed: $(printf '%s' "$deploy_out" | head -c 400)"

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
        'entity_post_any "/api/entity/get" "{\"orderId\":\"__probe__\"}" "\"outcome\"" >/dev/null' 240; then
        log_fail "entity slice never became reachable on any node"
        return 1
    fi

    log_pass "entity blueprint deployed; $(printf '%s' "$ENTITY_APP_ENDPOINTS" | grep -c .) per-node app endpoints resolved"
}

# Ownership is minted per (entity:orders, partition) arc, and the write barrier
# additionally needs each partition's replica set populated before
# confirmation_factor can be met. Probing ONE key certifies ONE partition — the
# Forge run failed in exactly that gap — so this probes a spread of keys.
# Ownership is minted per (entity:orders, partition) arc, and the write barrier
# additionally needs each partition's replica set populated before confirmation_factor can
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
    # `|| true`: budget exhaustion returns 1 to say "population capped", which under
    # `set -euo pipefail` would otherwise abort this function — the acked SUBSET is
    # still a valid experiment and the zero-acked gate below catches the empty case.
    create_range_recording_acks 0 "$N_PRE" "$ACKED_PRE" || true

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
    local idx key expected actual rc bad=0 unreachable=0 checked=0
    # Wall-clock phase budget: this loop had NONE, and run3/run4 ground for hours in it (the
    # 20,295s readback was this shape one test further down). Exhaustion is reported as
    # UNMEASURABLE — a budget overrun says nothing about durability.
    local budget="${READBACK_BUDGET:-900}"
    local phase_deadline=$((SECONDS + budget))

    while read -r idx; do
        [ -n "$idx" ] || continue
        if [ "$SECONDS" -ge "$phase_deadline" ]; then
            log_fail "pre-kill readback budget (${budget}s) exhausted after ${checked} keys — verdict UNMEASURABLE"
            return 1
        fi
        checked=$((checked + 1))
        key="$(key_for "$idx")"
        expected="$(amount_for "$idx")"
        actual="$(read_amount "$key")" && rc=0 || rc=$?
        case "$rc" in
            0)
                if [ "$actual" != "$expected" ]; then
                    log_error "pre-kill readback mismatch for ${key}: expected ${expected}, got '${actual}'"
                    bad=$((bad + 1))
                fi
                ;;
            3)
                log_error "pre-kill readback: ${key} ACKED but a holding node answered ABSENT"
                bad=$((bad + 1))
                ;;
            5)
                log_warn "pre-kill readback: ${key} still refused as transient at the retry deadline — not counted as loss"
                unreachable=$((unreachable + 1))
                ;;
            *)
                log_warn "pre-kill readback: ${key} gave no positive answer (see the read WARN above) — not counted as loss"
                unreachable=$((unreachable + 1))
                ;;
        esac
    done < "$ACKED_PRE"

    if [ "$unreachable" -ne 0 ]; then
        log_fail "${unreachable} pre-kill entities UNREACHABLE (no positive answer, or still transient at the deadline) — verdict unmeasurable, fix the cluster/harness first"
        return 1
    fi
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
    local total=0 missing=0 wrong=0 unreachable=0 idx key expected actual rc f
    # Wall-clock phase budget — run4's version of this loop ran 20,295s against nothing.
    # Overrun = UNMEASURABLE; only a POSITIVE "absent" from an arc-holding node counts as loss.
    local budget="${READBACK_BUDGET:-900}"
    local phase_deadline=$((SECONDS + budget))

    if [ "$KILL_CONFIRMED" -ne 1 ]; then
        log_fail "no SIGKILL was performed — this assertion would pass without testing anything"
        return 1
    fi

    for f in "$ACKED_PRE" "$ACKED_DURING"; do
        while read -r idx; do
            [ -n "$idx" ] || continue
            if [ "$SECONDS" -ge "$phase_deadline" ]; then
                log_fail "durability readback budget (${budget}s) exhausted after ${total} keys — verdict UNMEASURABLE"
                return 1
            fi
            total=$((total + 1))
            key="$(key_for "$idx")"
            expected="$(amount_for "$idx")"
            actual="$(read_amount "$key")" && rc=0 || rc=$?

            case "$rc" in
                0)
                    if [ "$actual" != "$expected" ]; then
                        wrong=$((wrong + 1))
                        log_error "CORRUPTED after SIGKILL: ${key} expected ${expected}, got ${actual}"
                    fi
                    ;;
                3)
                    missing=$((missing + 1))
                    log_error "LOST after SIGKILL: ${key} (acked; a holding node answers ABSENT)"
                    ;;
                5)
                    unreachable=$((unreachable + 1))
                    log_warn "UNREACHABLE after SIGKILL: ${key} (still refused as transient at the retry deadline — NOT counted as loss)"
                    ;;
                *)
                    unreachable=$((unreachable + 1))
                    log_warn "UNREACHABLE after SIGKILL: ${key} (no positive answer, see the read WARN above — NOT counted as loss)"
                    ;;
            esac
        done < "$f"
    done

    # Non-vacuity gate. An empty ack set makes "0 missing" trivially true —
    # #508 shipped exactly that bug, reporting "0 acked, 0 missing" as PASS.
    if [ "$total" -eq 0 ]; then
        log_fail "no ACKED creates to verify — the assertion would be vacuous"
        return 1
    fi

    log_info "post-SIGKILL: ${total} acked, ${missing} missing, ${wrong} corrupted, ${unreachable} unreachable"

    # Unreachable keys make the verdict unmeasurable — the run FAILS, but as a harness/cluster
    # health failure, explicitly NOT a durability claim. Conflating the two is what made run2's
    # "1/2 lost" line meaningless.
    if [ "$unreachable" -ne 0 ]; then
        log_fail "${unreachable}/${total} keys UNREACHABLE — durability verdict unmeasurable this run"
        return 1
    fi
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
# `/api/v1/entity/checkpoints` is a LOCAL route, and `api_get` does NOT rotate:
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
        body=$(node_api_get "$i" "/api/v1/entity/checkpoints" 2>/dev/null || printf '')
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
    # instead of on a defect. `wait_for` evaluates its predicate in a FORK
    # (_fork_bounded), so the globals collect_checkpoints sets there never reach this
    # shell (#1512: the PASS line read "across 0 node(s)"). Collect again here and
    # assert on what THIS shell holds, so the verdict and its message come from one read.
    wait_for "a successful checkpoint write somewhere in the cluster" \
        'collect_checkpoints; [ "$CHECKPOINT_HOSTING" -gt 0 ] && [ "$CHECKPOINT_WRITES" -gt 0 ]' 120 || true
    collect_checkpoints
    if [ "$CHECKPOINT_HOSTING" -gt 0 ] && [ "$CHECKPOINT_WRITES" -gt 0 ]; then
        log_pass "checkpoint driver alive across ${CHECKPOINT_HOSTING} node(s): ${CHECKPOINT_DETAIL}"
        return 0
    fi

    if [ "$CHECKPOINT_HOSTING" -eq 0 ]; then
        log_fail "no node reported an entity keyspace while the entity slice is deployed"
        return 1
    fi
    log_fail "${CHECKPOINT_HOSTING} node(s) host the keyspace but none wrote a checkpoint (${CHECKPOINT_DETAIL}) — the entity log is not being bounded"
    return 1
}

test_post_crash_liveness() {
    if ! create_entity 9999; then
        log_fail "the post-crash create was not accepted (transient refusals are retried for ${ENTITY_CREATE_RETRY_DEADLINE_S}s; the refusal body is in the warning above)"
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
    api_delete "/api/v1/blueprints/${ENTITY_BP}" >/dev/null 2>&1 || true

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
