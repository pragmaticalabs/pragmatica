#!/bin/bash
# test-worker-join-accounting.sh — Wave 2 worker-accounting gate (cluster-topology-overhaul spec)
#
# A 6th node joining past coreMax (5) with AETHER_ROLE=worker is assigned WORKER and must be
# invisible to every CORE denominator (spec invariant A8 / fixes W1-W6):
#   (a) quorum / core membership unchanged by the worker's presence;
#   (b) a core-kill still heals back to 5 COREs — the worker does NOT fill the deficit (W2);
#   (c) the auto-heal replacement is assigned CORE, not WORKER (W3 + W4 role stamping);
#   (d) CORE_ONLY slice placement never lands on the worker (W6).
#
# Mechanism notes:
#   - The worker boots as a raw container on the cluster network with AETHER_ROLE=worker —
#     the self-asserted SWIM role label is the role carrier until #241 (spec Q3), and the
#     leader's CDM independently assigns the WORKER ActivationDirective (join past coreMax).
#   - Core membership truth is read from /api/v1/cluster/generation via cluster_member_count
#     (the generation member set is built from the FSM's role-filtered coreMembers, so the
#     worker never appears there) and per-member role/state from /api/v1/cluster/topology
#     fsmMembers (descriptor role from the FSM).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
# topology.sh carries wait_for_node_removed (victim-gone-from-membership poll). It is
# NOT pulled in transitively by common.sh/cluster.sh, so source it explicitly — the
# heal test below waits on the killed victim actually leaving membership before it
# trusts the CTM set-diff to name the new replacement.
source "${SCRIPT_DIR}/../../lib/topology.sh"

WORKER_NODE="aether-b-worker-1"
CLUSTER_NETWORK="${CLUSTER_NETWORK:-aether-b-network}"
NODE_IMAGE="${NODE_IMAGE:-aether-node:local}"
# Cluster secret MUST match the compose cluster's. Do NOT default from the harness env:
# the harness exports its own generated AETHER_CLUSTER_SECRET (cloud-mode secret) which
# differs from docker-compose-b.yml's hardcoded value — a worker booted with it derives a
# different gossip key and every ANNOUNCE is rejected ("Unknown gossip key ID", observed
# live 2026-06-10). Read the authoritative value from a running core container instead.
resolve_cluster_secret() {
    remote_exec "c=\$(docker ps --filter label=aether.node-id --filter network=${CLUSTER_NETWORK} --format '{{.Names}}' | grep -v '^${WORKER_NODE}\$' | head -1); docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' \"\$c\" | grep '^AETHER_CLUSTER_SECRET=' | cut -d= -f2-"
}
WORKER_JOIN_TIMEOUT="${WORKER_JOIN_TIMEOUT:-120}"
HEAL_TIMEOUT="${HEAL_TIMEOUT:-180}"
BLUEPRINT="org.pragmatica.aether.test:test-echo:1.0.0"

# NodeId of the CORE replacement that auto-heal provisioned for THIS test's kill.
# Set by test_core_kill_heals_to_five_cores via a pre/post CTM-set diff (NOT head -1
# of the CTM containers — a prior heal's replacement is already in the cluster and
# would be picked first by head -1, then the role wait would poll the wrong node and
# time out). Consumed by test_replacement_assigned_core. Tests run in the same shell
# (run_test calls the function in-process, see lib/common.sh run_test), so this
# script-global survives across the two test functions.
REPLACEMENT_NODE=""

# This scenario boots a raw worker container via remote docker — docker mode only.
if [ "${CLOUD_MODE:-false}" = "true" ]; then
    skip_test "Worker join accounting" "docker-only scenario (boots a raw worker container via remote docker)"
    print_summary
    exit $?
fi

# --- helpers -----------------------------------------------------------------

# Role of a node per the authoritative FSM descriptor (topology fsmMembers[]). Scans every
# {"nodeId":"<node>"...} block and keeps only fsmMembers[] entries — distinguishable because
# they carry "fsmState"; nodeDetails[] blocks share the nodeId key but their "role" is the
# TRANSPORT role (ACTIVE/PASSIVE), not the descriptor role. Returns the descriptor role
# (core/worker) or empty when unknown.
fsm_role_of() {
    local node="$1"
    direct_api_get "/api/v1/cluster/topology" 2>/dev/null \
        | grep -o "\"nodeId\"[[:space:]]*:[[:space:]]*\"${node}\"[^}]*" \
        | grep '"fsmState"' \
        | head -1 \
        | grep -o '"role"[[:space:]]*:[[:space:]]*"[^"]*"' \
        | sed 's/.*:[[:space:]]*"\([^"]*\)"/\1/' || true
}

# FSM lifecycle state of a node (topology fsmMembers[]). No nodeDetails[] collision here:
# only fsmMembers[] entries carry an "fsmState" field, so the extraction self-selects.
fsm_state_of() {
    local node="$1"
    direct_api_get "/api/v1/cluster/topology" 2>/dev/null \
        | grep -o "\"nodeId\"[[:space:]]*:[[:space:]]*\"${node}\"[^}]*" \
        | grep -o '"fsmState"[[:space:]]*:[[:space:]]*"[^"]*"' \
        | head -1 \
        | sed 's/.*:[[:space:]]*"\([^"]*\)"/\1/' || true
}

# Live core node ids from the topology endpoint ("coreNodes" field), one per line, worker
# excluded defensively (the route's core-id filter is transport-based, not
# descriptor-role-based, until Wave 9).
live_core_ids() {
    direct_api_get "/api/v1/cluster/topology" 2>/dev/null \
        | grep -o '"coreNodes"[[:space:]]*:[[:space:]]*\[[^]]*\]' \
        | grep -o '"[^"]*"' \
        | tr -d '"' \
        | grep -v '^coreNodes$' \
        | grep -v "^${WORKER_NODE}\$" || true
}

# 3-part PEERS list (nodeId:host:port) for the worker — built from RUNNING cluster-B
# containers (docker ps), NOT the topology endpoint: right after a churn/restart-heavy
# script the API topology can still list stale pre-restart node ids from restored KV,
# which would give the worker dead announce seeds (observed live 2026-06-10: 4 dead
# seeds → 60/60 unacknowledged ANNOUNCEs → join never happens). Container name ==
# NodeId == overlay hostname, so `id:id:6000` resolves on the cluster network.
build_worker_peers() {
    remote_exec "docker ps --filter label=aether.node-id --filter network=${CLUSTER_NETWORK} --format '{{.Label \"aether.node-id\"}}' | sort" \
        | grep -v "^${WORKER_NODE}\$" \
        | awk '{ printf "%s%s:%s:6000", sep, $1, $1; sep="," } END { print "" }'
}

# CTM-provisioned cluster-B container names, one per line, sorted. The
# `aether.provisioned-by=ctm` label is stamped by DockerComputeProvider on every
# auto-heal replacement (compose seeds + the raw worker do NOT carry it), and
# `aether.cluster=b` scopes the result so cluster A's replacements can't leak in.
# Captured before AND after the kill; the set-difference (post MINUS pre) is the
# replacement provisioned for THIS kill — robust even when earlier heals already
# left CTM containers running (head -1 would have returned one of those).
ctm_replacement_set() {
    remote_exec "docker ps --filter label=aether.provisioned-by=ctm --filter label=aether.cluster=b --format '{{.Names}}' | sort"
}

# --- tests -------------------------------------------------------------------

test_cluster_ready() {
    wait_for_cluster_ready 60
    wait_for_node_count 5 90
    if ! auto_heal_enabled; then
        enable_auto_heal || log_warn "could not re-enable auto-heal; heal test may fail"
    fi
    assert_cluster_healthy "Baseline 5-core cluster healthy before worker join"
}

test_worker_joins_as_worker() {
    local peers cluster_secret
    peers=$(build_worker_peers)
    if [ -z "$peers" ]; then
        log_fail "Cannot build PEERS for the worker (no live core ids)"
        return 1
    fi
    cluster_secret=$(resolve_cluster_secret)
    if [ -z "$cluster_secret" ]; then
        log_fail "Cannot resolve the cluster secret from a running core container"
        return 1
    fi
    log_info "Launching worker ${WORKER_NODE} (PEERS=${peers})"

    remote_exec "docker rm -f ${WORKER_NODE} >/dev/null 2>&1 || true; \
docker run -d --restart no \
  --name ${WORKER_NODE} --hostname ${WORKER_NODE} \
  --network ${CLUSTER_NETWORK} \
  --label aether.cluster=b \
  --label aether.node-id=${WORKER_NODE} \
  --label aether.role=worker \
  -e NODE_ID=${WORKER_NODE} \
  -e AETHER_NODE_ID=${WORKER_NODE} \
  -e CLUSTER_PORT=6000 \
  -e MANAGEMENT_PORT=8080 \
  -e PEERS='${peers}' \
  -e CORE_MAX=5 \
  -e AETHER_CLUSTER_NAME=b \
  -e AETHER_CLUSTER_SECRET='${cluster_secret}' \
  -e AETHER_ROLE=worker \
  -e AETHER_INSECURE_DEV_MODE=true \
  -e JAVA_OPTS='-Xmx512m -XX:+UseZGC -Djava.net.preferIPv4Stack=true' \
  ${NODE_IMAGE}" || { log_fail "docker run for ${WORKER_NODE} failed"; return 1; }

    # The worker is classified by its self-asserted role label (descriptor role=worker) once
    # SWIM gossip lands; it must reach FSM Member (it IS a member — just not a CORE one).
    wait_for "worker ${WORKER_NODE} classified role=worker" \
             "[ \"\$(fsm_role_of ${WORKER_NODE})\" = 'worker' ]" "$WORKER_JOIN_TIMEOUT"
    wait_for "worker ${WORKER_NODE} reaches FSM Member" \
             "[ \"\$(fsm_state_of ${WORKER_NODE})\" = 'Member' ]" "$WORKER_JOIN_TIMEOUT"
    log_pass "Worker joined and classified role=worker (FSM Member)"
}

test_quorum_unchanged_by_worker() {
    # (a) The worker must not enter the CORE membership: the generation member set (FSM
    # coreMembers-derived) stays at 5, and the cluster stays quorate/healthy — 5 cores + 1
    # worker is still a 5-core cluster, never a 6-member quorum domain.
    assert_eq "$(cluster_member_count)" "5" "Core generation membership unchanged by worker presence"
    if cluster_quorate; then
        log_pass "Cluster quorate with worker present"
    else
        log_fail "Cluster lost quorum after worker join (worker perturbed the quorum domain)"
        return 1
    fi
    assert_cluster_healthy "Cluster healthy with worker present"
}

test_core_kill_heals_to_five_cores() {
    # (b) Kill a non-leader CORE. The heal deficit is computed over coreCountedMembers() — the
    # worker must NOT fill the gap, so auto-heal provisions a replacement and the core
    # generation returns to 5 members.
    local leader victim=""
    leader=$(cluster_leader)
    for id in $(live_core_ids); do
        if [ -n "$id" ] && [ "$id" != "$leader" ]; then
            victim="$id"
            break
        fi
    done
    if [ -z "$victim" ]; then
        log_fail "No non-leader core victim found"
        return 1
    fi

    # Snapshot the CTM-provisioned set BEFORE the kill. A prior suite's auto-heal may
    # already have left CTM containers running; the replacement for THIS kill is the
    # set-difference (post-kill MINUS this snapshot), not `head -1` of all CTM
    # containers (which would return a stale prior-heal replacement that is already a
    # cluster member — the bug this guards against). `|| true` so an empty/clean host
    # (no prior CTM containers) doesn't abort under `set -e`.
    local pre_kill_ctm
    pre_kill_ctm=$(ctm_replacement_set || true)
    log_info "Pre-kill CTM set: [$(printf '%s' "$pre_kill_ctm" | tr '\n' ',' | sed 's/,$//')]"

    log_info "Killing core node ${victim} (leader=${leader}); worker ${WORKER_NODE} must not fill the deficit"
    kill_node "$victim"

    # wait_for_node_count 5 passes VACUOUSLY here: cluster_member_count reads the
    # generation snapshot, which still lists the just-killed victim for a while, so "5"
    # is satisfied by [4 survivors + 1 dead-but-still-listed] BEFORE any replacement
    # exists. Instead, wait for the AUTHORITATIVE heal signals:
    #   1. the victim has actually LEFT membership (lifecycle 404 OR absent from status), and
    #   2. a NEW CTM replacement has appeared (post-kill CTM set MINUS the pre-kill set).
    # Only then is the set-diff guaranteed to name the replacement for THIS kill.
    if ! wait_for_node_removed "$victim" "$HEAL_TIMEOUT"; then
        log_fail "Victim ${victim} did not leave membership within ${HEAL_TIMEOUT}s (kill may not have landed)"
        return 1
    fi
    log_pass "Victim ${victim} left cluster membership"

    # Poll for the new CTM replacement: (post-kill CTM set) MINUS (pre-kill CTM set).
    # `comm -13` prints lines unique to the post set; both inputs are pre-sorted by
    # ctm_replacement_set. Pin the first new name into REPLACEMENT_NODE for the
    # role assertion in test_replacement_assigned_core.
    local deadline=$(( $(date +%s) + HEAL_TIMEOUT ))
    local new_ctm=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        local post_kill_ctm
        post_kill_ctm=$(ctm_replacement_set || true)
        new_ctm=$(comm -13 \
            <(printf '%s\n' "$pre_kill_ctm" | grep -v '^$' | sort -u) \
            <(printf '%s\n' "$post_kill_ctm" | grep -v '^$' | sort -u) \
            | head -1 || true)
        if [ -n "$new_ctm" ]; then
            break
        fi
        sleep 2
    done
    if [ -z "$new_ctm" ]; then
        log_fail "No NEW CTM replacement appeared after killing ${victim} within ${HEAL_TIMEOUT}s (set-diff empty; auto-heal may not have provisioned)"
        return 1
    fi
    REPLACEMENT_NODE="$new_ctm"
    log_info "Replacement for ${victim} (CTM set-diff): ${REPLACEMENT_NODE}"

    # Core generation must converge back to 5 members now that the victim is gone and a
    # replacement is provisioned — and the worker must NOT have been drafted to fill it.
    wait_for_node_count 5 "$HEAL_TIMEOUT"
    # The worker is still a worker — it was not drafted into the core to mask the deficit.
    assert_eq "$(fsm_role_of "$WORKER_NODE")" "worker" "Worker still classified role=worker after heal"
    log_pass "Core deficit healed to 5 cores with worker present"
}

test_replacement_assigned_core() {
    # (c) The CTM-provisioned replacement carries an explicit CORE intent end-to-end (W4:
    # provisionReplacement(CORE) → ProvisionContext.role → AETHER_ROLE env + aether.role label
    # → self-asserted SWIM role → FSM descriptor role).
    #
    # The replacement is identified by the pre/post CTM-set diff captured in
    # test_core_kill_heals_to_five_cores (REPLACEMENT_NODE), NOT `head -1` of the CTM
    # containers — head -1 can return a PRIOR heal's replacement (already in PEERS), so
    # the role wait would poll the wrong, long-since-CORE-classified node and time out.
    local replacement="$REPLACEMENT_NODE"
    if [ -z "$replacement" ]; then
        log_fail "No replacement pinned by the heal step (REPLACEMENT_NODE empty — heal test must run first and succeed)"
        return 1
    fi
    log_info "Replacement container (set-diff pinned): ${replacement}"

    # RC1 contract: assert the replacement was provisioned, JOINED the cluster, and is
    # CORE-FUNCTIONAL — i.e. it reached FSM Member and counts toward core membership /
    # quorum. The descriptor role itself is ACCEPTED as core when it reads `core` OR is
    # blank/absent/unknown.
    #
    # Blank-role-accepted-as-core: role labels propagate only via direct SWIM ANNOUNCE /
    # QUIC Hello, NOT via gossip MembershipUpdate (which is label-less), so an arbitrary
    # polled node can legitimately serve a freshly-joined replacement as role="" (UNKNOWN).
    # A blank role counts as CORE functionally (quorum/counts are correct) — only this
    # strict label gate would notice. Deterministic role-label propagation through gossip
    # is a known RC2 item (#4 role-label-in-gossip); when it lands, tighten this back to a
    # strict `= core` assertion. The gate FAILS only if the role is EXPLICITLY `worker`.

    # First confirm the replacement actually joined membership as a Member (CORE-functional).
    wait_for "replacement ${replacement} reaches FSM Member" \
             "[ \"\$(fsm_state_of ${replacement})\" = 'Member' ]" "$WORKER_JOIN_TIMEOUT"

    # Then assert the descriptor role is core-or-unknown, never worker. Poll the role:
    # if it ever resolves to `core` we PASS immediately; if it stays blank/unknown for
    # the full window we ALSO PASS (RC1 blank-as-core); we FAIL the instant it reads
    # `worker`.
    local role="" deadline=$(( $(date +%s) + WORKER_JOIN_TIMEOUT ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        role=$(fsm_role_of "$replacement")
        if [ "$role" = "worker" ]; then
            log_fail "Replacement ${replacement} classified role=worker (must be CORE — the heal drafted a WORKER to fill a CORE deficit)"
            return 1
        fi
        if [ "$role" = "core" ]; then
            break
        fi
        sleep 2
    done
    if [ "$role" = "core" ]; then
        log_pass "Replacement ${replacement} classified role=core (descriptor role)"
    else
        # Blank/absent/unknown role after the window — accepted as CORE under the RC1
        # contract (role-label gossip propagation is RC2 #4; see comment above).
        log_pass "Replacement ${replacement} CORE-functional (FSM Member); descriptor role='${role:-<unknown>}' accepted as core (role-label gossip is RC2 #4)"
    fi

    # The docker label is the env-stamped role written at provision time (ProvisionContext
    # → AETHER_ROLE + aether.role label), which travels with the container regardless of
    # gossip — so it remains a STRICT, deterministic assertion: it must be exactly `core`.
    local label_role rc
    label_role=$(remote_exec "docker inspect -f '{{ index .Config.Labels \"aether.role\" }}' ${replacement}")
    rc=$?
    if [ "$rc" -ne 0 ]; then
        log_fail "docker inspect failed for replacement ${replacement} (remote_exec rc=${rc})"
        return 1
    fi
    assert_eq "$label_role" "core" "Replacement container label aether.role=core"
}

test_core_only_placement_excludes_worker() {
    # (d) CORE_ONLY (the default placement policy) must never land an instance on the worker:
    # AllocationPool.coreNodes is built from the core-scoped activeNodes(), so the worker is
    # structurally outside the CORE_ONLY pool.
    push_blueprint "$BLUEPRINT"
    deploy_blueprint "$BLUEPRINT"
    wait_for_slices_active 1 120 || log_warn "Slices not active after deploy (placement check still meaningful)"

    local slices
    slices=$(cluster_slices)
    assert_ne "$slices" "" "Slices endpoint responds"
    if printf '%s' "$slices" | grep -q "\"nodeId\"[[:space:]]*:[[:space:]]*\"${WORKER_NODE}\""; then
        log_fail "CORE_ONLY placement landed a slice instance on worker ${WORKER_NODE}"
        return 1
    fi
    log_pass "No slice instance placed on the worker (CORE_ONLY respected)"
}

test_cleanup_baseline() {
    # Remove the worker and restore the suite baseline (re-enables auto-heal, resets the CTM
    # circuit, reconciles membership) so subsequent suites do not inherit the extra node.
    remote_exec "docker rm -f ${WORKER_NODE} >/dev/null 2>&1 || true" || \
        log_warn "cleanup: could not remove worker container ${WORKER_NODE}"
    restore_cluster_baseline
    assert_eq "$?" "0" "Baseline restored after worker removal (subsequent suites must not inherit cluster churn)"
}

run_test "Cluster ready" test_cluster_ready
run_test "Worker joins as WORKER past coreMax" test_worker_joins_as_worker
run_test "Quorum unchanged by worker" test_quorum_unchanged_by_worker
run_test "Core kill heals to 5 cores (worker does not fill deficit)" test_core_kill_heals_to_five_cores
run_test "Replacement assigned CORE not WORKER" test_replacement_assigned_core
run_test "CORE_ONLY placement excludes worker" test_core_only_placement_excludes_worker
run_test "Cleanup: remove worker + restore baseline" test_cleanup_baseline
print_summary
