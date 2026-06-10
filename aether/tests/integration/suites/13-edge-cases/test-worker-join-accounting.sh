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
#   - Core membership truth is read from /api/cluster/generation via cluster_member_count
#     (the generation member set is built from the FSM's role-filtered coreMembers, so the
#     worker never appears there) and per-member role/state from /api/cluster/topology
#     fsmMembers (descriptor role from the FSM).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

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
    direct_api_get "/api/cluster/topology" 2>/dev/null \
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
    direct_api_get "/api/cluster/topology" 2>/dev/null \
        | grep -o "\"nodeId\"[[:space:]]*:[[:space:]]*\"${node}\"[^}]*" \
        | grep -o '"fsmState"[[:space:]]*:[[:space:]]*"[^"]*"' \
        | head -1 \
        | sed 's/.*:[[:space:]]*"\([^"]*\)"/\1/' || true
}

# Live core node ids from the topology endpoint ("coreNodes" field), one per line, worker
# excluded defensively (the route's core-id filter is transport-based, not
# descriptor-role-based, until Wave 9).
live_core_ids() {
    direct_api_get "/api/cluster/topology" 2>/dev/null \
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

    log_info "Killing core node ${victim} (leader=${leader}); worker ${WORKER_NODE} must not fill the deficit"
    kill_node "$victim"
    sleep 5

    wait_for_node_count 5 "$HEAL_TIMEOUT"
    # The worker is still a worker — it was not drafted into the core to mask the deficit.
    assert_eq "$(fsm_role_of "$WORKER_NODE")" "worker" "Worker still classified role=worker after heal"
    log_pass "Core deficit healed to 5 cores with worker present"
}

test_replacement_assigned_core() {
    # (c) The CTM-provisioned replacement carries an explicit CORE intent end-to-end (W4:
    # provisionReplacement(CORE) → ProvisionContext.role → AETHER_ROLE env + aether.role label
    # → self-asserted SWIM role → FSM descriptor role).
    local replacement rc
    replacement=$(remote_exec "docker ps --filter label=aether.provisioned-by=ctm --filter label=aether.cluster=b --format '{{.Names}}' | head -1")
    rc=$?
    if [ "$rc" -ne 0 ] || [ -z "$replacement" ]; then
        log_fail "No CTM-provisioned replacement container found after heal (remote_exec rc=${rc})"
        return 1
    fi
    log_info "Replacement container: ${replacement}"

    wait_for "replacement ${replacement} classified role=core" \
             "[ \"\$(fsm_role_of ${replacement})\" = 'core' ]" "$WORKER_JOIN_TIMEOUT"
    # And the docker label agrees with the env-stamped role (same ProvisionContext source).
    local label_role
    label_role=$(remote_exec "docker inspect -f '{{ index .Config.Labels \"aether.role\" }}' ${replacement}")
    if [ $? -ne 0 ]; then
        log_fail "docker inspect failed for replacement ${replacement}"
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
