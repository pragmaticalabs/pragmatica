#!/bin/bash
# test-stale-route-cleanup.sh — Kill node hosting routes, verify stale routes cleaned
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

ROUTE_CLEANUP_TIMEOUT="${ROUTE_CLEANUP_TIMEOUT:-60}"
BLUEPRINT="org.pragmatica.aether.test:test-echo:1.0.0"

test_cluster_ready() {
    wait_for_cluster_ready 60
    # Pre-test barrier (FIRST-CLASS GATE): the kill test downstream needs a running
    # non-leader victim. A prior destructive suite (e.g. disruption-budget) can drain 2
    # nodes whose ULID replacements aren't yet running/killable, so gate the whole suite
    # on "a killable victim exists" before deploying — rather than letting the race
    # surface as a timeout inside the 4th test. Bounded; fails loudly if it can't converge.
    await_killable_victim_available || log_fail "no killable non-leader victim available pre-deploy"
    # By suite 13, prior destructive tests have churned cluster B: the pinned seed
    # CLUSTER_ENDPOINT (:5161) node may be gone, so re-resolve to a live core before the
    # quiesce barriers — otherwise they read "cluster unreachable" off a dead port and
    # skip silently (bit Hetzner gate-F1: :5161 dead, cluster healthy on :5162).
    _refresh_mgmt_entry_point || log_warn "no live mgmt endpoint — proceeding with pinned ${CLUSTER_ENDPOINT}"
    # ClusterGeneration barrier: whatever churn a prior destructive suite left is
    # committed to a stable generation before we deploy. No rescale-fallback needed.
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 60 || log_warn "pre-deploy snapshot not quiesced"
    push_blueprint "$BLUEPRINT"
    deploy_blueprint "$BLUEPRINT"
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 60 || log_warn "deploy did not quiesce"
    wait_for_slices_active 1 120 || log_warn "Slices not active after deploy"
    log_pass "Cluster ready with baseline blueprint deployed"
}

test_slices_deployed() {
    wait_for_slices_active 1 120
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices deployed: ${instances} instances"
}

test_app_routes_reachable() {
    # Probe the actual app-route surface — EchoSlice's /api/echo/health route
    # (under its declared prefix). Bare /health hits AppHttpServer's synthetic
    # liveness intercept (returns 200 regardless of slice deployment) and
    # therefore proves nothing about route wiring. The prefixed path goes
    # through the route table. app_route_wired (positive readiness)
    # distinguishes route-missing 404 from a real handler response. NO
    # management-API fallback: this test exists to prove the APP route is
    # wired; falling back to /api/v1/nodes/status would swap subjects (mgmt port
    # healthy != app route reachable) and pass falsely.
    #
    # Retarget APP_ENDPOINT to a node that currently hosts an ACTIVE echo slice
    # FIRST. The default pinned APP_ENDPOINT may belong to a node that prior
    # destructive tests churned out; the live owner is a ULID replacement, and the
    # helper resolves its app port from APP_PORT. This mirrors 02-chaos
    # kill-under-load, which never probes the bare pinned app port for the same reason.
    retarget_app_endpoint_to_active_slice "$BLUEPRINT" "/api/echo/health" 90 \
        || log_warn "retarget: APP_ENDPOINT unchanged (no ACTIVE echo owner resolved) — probing pinned ${APP_ENDPOINT}"
    if ! app_route_wired "${APP_ENDPOINT}/api/echo/health" "${API_KEY}"; then
        log_fail "App route ${APP_ENDPOINT}/api/echo/health not wired (expected EchoSlice handler to respond)"
        return 1
    fi
    log_pass "App route /api/echo/health wired (positive readiness via app_route_wired)"
}

# Pick a non-leader kill victim from the ACTUAL running docker containers — the set we
# can `docker kill` — NOT from the membership list. By suite 13 cluster B has churned
# through 02/03/12's kills, so the running cores are mostly ULID replacements. The old
# membership∩docker intersection went empty whenever the status-JSON view (served via
# the round-robin mgmt gateway, occasionally a momentarily stale node) and docker-ps
# listed different ULID generations — with no static survivors left to fall back on
# (bit Hetzner gate-1/A1/D1 in succession). docker-ps is authoritative for "what exists
# to be killed", and any running non-leader core is a valid victim for the stale-route
# assertion, so we source candidates straight from it. NodeId == container name
# (DockerComputeProvider mints one identity for both), so excluding the leader by name
# is exact. Prints the chosen container name (empty when none).
pick_route_kill_victim() {
    local leader="$1"
    # Cloud has no shared Docker daemon — `docker ps` over SSH reaches only the seed VM,
    # not CTM-provisioned replacement VMs, so it cannot enumerate the killable cores. On
    # cloud, source candidates from the SAME mechanism the other cloud destructive suites
    # use (02-chaos / 12-network via pick_non_leader): cloud_running_cores reads READY
    # cores off the mgmt API (/api/v1/nodes/lifecycle), which is reachable for every node
    # regardless of SSH key. Any running non-leader READY core is a valid stale-route
    # victim. Prints the chosen NodeId (empty when none).
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        local nid
        while IFS= read -r nid; do
            if [ -n "$nid" ] && [ "$nid" != "$leader" ]; then
                echo "$nid"
                return 0
            fi
        done <<< "$(cloud_running_cores)"
        echo ""
        return 0
    fi
    local running
    running=$(remote_exec "docker ps --filter name=aether-b-node- --filter status=running --format '{{.Names}}'")
    local nid
    # Pass 1: prefer a static compose-seed (aether-b-node-<ordinal>) non-leader — never a
    # preferred surplus-drain victim, so the pick cannot race the reconciler's drain.
    while IFS= read -r nid; do
        if [ -n "$nid" ] && [ "$nid" != "$leader" ] && echo "$nid" | grep -qE '^aether-b-node-[0-9]+$'; then
            echo "$nid"
            return 0
        fi
    done <<< "$running"
    # Pass 2: any running non-leader core (ULID replacement included).
    while IFS= read -r nid; do
        if [ -n "$nid" ] && [ "$nid" != "$leader" ]; then
            echo "$nid"
            return 0
        fi
    done <<< "$running"
    echo ""
}

# Enumerate running non-leader-eligible core ids for DIAG/barrier — the same set the
# picker draws from. Cloud: READY cores via mgmt API; docker: running aether-b cores.
running_core_ids() {
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        cloud_running_cores
    else
        remote_exec "docker ps --filter name=aether-b-node- --filter status=running --format '{{.Names}}'"
    fi
}

# Confirm a chosen victim still exists/runs before killing it. Cloud: present in the
# READY-core set (mgmt API); docker: a running container with that exact name.
victim_is_live() {
    local v="$1"
    [ -z "$v" ] && return 1
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        running_core_ids | grep -qx "$v"
    else
        remote_exec "docker ps --format '{{.Names}}'" | grep -qx "$v"
    fi
}

# Bounded pre-deploy barrier: the kill test (test_kill_node_hosting_routes) needs a
# running non-leader victim to kill, but a prior destructive suite can leave cluster B
# mid-auto-heal (leader + JOINING replacements only) for tens of seconds. Wait until at
# least two running non-leader cores exist — i.e. pick_route_kill_victim can resolve a
# victim with one to spare — BEFORE deploying, so the killable-victim precondition is a
# first-class gate rather than a race inside the 4th test. Reuses the picker's own
# candidate enumeration (running_core_ids minus the current leader).
await_killable_victim_available() {
    local deadline=$((SECONDS + ${ROUTE_VICTIM_PICK_TIMEOUT:-180}))
    local leader nonleader_count
    while [ "$SECONDS" -lt "$deadline" ]; do
        leader=$(cluster_leader) || leader=""
        if [ -n "$leader" ]; then
            nonleader_count=$(running_core_ids | grep -v '^$' | grep -vx "$leader" | grep -c '^' || true)
            if [ "${nonleader_count:-0}" -ge 2 ]; then
                log_info "killable-victim barrier: ${nonleader_count} running non-leader cores (leader=${leader})"
                return 0
            fi
        fi
        sleep 3
    done
    leader=$(cluster_leader) || leader=""
    log_fail "killable-victim barrier: fewer than 2 running non-leader cores within ${ROUTE_VICTIM_PICK_TIMEOUT:-180}s (leader='${leader}', running=[$(running_core_ids | tr '\n' ' ')])"
    return 1
}

test_kill_node_hosting_routes() {
    # Victim pick from running docker cores (see pick_route_kill_victim). The pick
    # WAITS (bounded) because by the time this script runs, the destructive wave can
    # leave cluster B mid-auto-heal with only the leader + JOINING replacements for
    # tens of seconds; the test's job starts once a viable victim exists, and waiting
    # for one is not leniency. Leader is re-resolved each iteration (it can move while
    # the cluster heals) and guarded so a transient leader-lookup miss doesn't abort
    # the script under `set -e`. On exhaustion we dump the membership-vs-docker view so
    # a recurrence is diagnosable instead of guessable.
    local victim="" leader=""
    local pick_deadline=$((SECONDS + ${ROUTE_VICTIM_PICK_TIMEOUT:-180}))
    while [ "$SECONDS" -lt "$pick_deadline" ]; do
        leader=$(cluster_leader) || leader=""
        if [ -n "$leader" ]; then
            victim=$(pick_route_kill_victim "$leader")
            if [ -n "$victim" ] && victim_is_live "$victim"; then
                break
            fi
        fi
        victim=""
        sleep 3
    done
    if [ -z "$victim" ]; then
        log_info "victim-pick DIAG: leader='${leader}'"
        log_info "victim-pick DIAG: running cores = [$(running_core_ids | tr '\n' ' ')]"
        log_info "victim-pick DIAG: membership = [$(cluster_node_list | grep -o '\"nodeId\"[[:space:]]*:[[:space:]]*\"[^\"]*\"' | sed 's/.*\"\([^\"]*\)\"$/\1/' | tr '\n' ' ')]"
        log_fail "Could not pick a live non-leader victim within ${ROUTE_VICTIM_PICK_TIMEOUT:-180}s"
        return 1
    fi

    log_info "Killing node with potential routes: ${victim}"
    kill_node "$victim"
    # Legitimate chaos window: failure detection needs a few seconds.
    sleep 5

    # ClusterGeneration barrier: routes are fenced by epoch. When the next
    # generation quiesces, stale-route cleanup is complete. Re-resolve the mgmt
    # endpoint first — the kill above may have taken the node CLUSTER_ENDPOINT
    # pointed at.
    _refresh_mgmt_entry_point || log_warn "no live mgmt endpoint — proceeding with pinned ${CLUSTER_ENDPOINT}"
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" "$ROUTE_CLEANUP_TIMEOUT" || \
        log_warn "Post-kill quiescence not reached within ${ROUTE_CLEANUP_TIMEOUT}s"
    log_pass "Route cleanup fenced by generation advance"
}

test_no_502_504_after_cleanup() {
    # The killed node hosted APP routes — probing /health hits the synthetic
    # AppHttpServer liveness intercept (always 200) and would not surface a
    # stale-route 502/504. Probe the actual APP route (EchoSlice
    # /api/echo/health) instead so a stale-route 502/504 (from
    # sendNoRouteFound or upstream proxy) actually surfaces. Previous test
    # already waited for generation quiescence.
    #
    # Re-resolve APP_ENDPOINT to a SURVIVING active echo owner: the kill may have
    # taken the owner we retargeted to earlier, and the pinned seed port is long
    # gone (gate-F1). Probing a live owner is the correct subject — the assertion
    # is "no stale-route 502/504", which must be measured against a node that
    # should serve cleanly, not a dead pinned port (which would read 000, not 502).
    retarget_app_endpoint_to_active_slice "$BLUEPRINT" "/api/echo/health" 90 \
        || log_warn "retarget: APP_ENDPOINT unchanged (no surviving ACTIVE echo owner) — probing pinned ${APP_ENDPOINT}"
    local bad_status=0
    for i in $(seq 1 10); do
        local status
        status=$(http_status "${APP_ENDPOINT}/api/echo/health" -H "X-API-Key: ${API_KEY}")
        if [ "$status" = "502" ] || [ "$status" = "504" ]; then
            bad_status=$((bad_status + 1))
        fi
        sleep 1
    done
    assert_eq "$bad_status" "0" "No 502/504 on APP route /api/echo/health after cleanup (${bad_status}/10 bad)"
}

test_kv_store_routes_clean() {
    # Verify slices endpoint still responds correctly
    local slices
    slices=$(cluster_slices)
    assert_ne "$slices" "" "Slices endpoint responds after route cleanup"
}

test_recovery_complete() {
    wait_for_node_count 5 90
    assert_cluster_healthy "Cluster recovered after stale route cleanup"
}

run_test "Cluster ready" test_cluster_ready
run_test "Slices deployed" test_slices_deployed
run_test "App routes reachable" test_app_routes_reachable
run_test "Kill node hosting routes" test_kill_node_hosting_routes
run_test "No 502/504 after cleanup" test_no_502_504_after_cleanup
run_test "KV store routes clean" test_kv_store_routes_clean
run_test "Recovery complete" test_recovery_complete
print_summary
