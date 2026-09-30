#!/bin/bash
# test-harness-endpoint-and-core-count.sh — stubs only (no cluster, no cloud, no ssh).
#   E1  suites/12-network/test-gossip-encryption.sh's liveness probe fails over: the pinned
#       CLUSTER_ENDPOINT is dead (HTTP 000), the resolved live endpoint answers 200 -> the test passes.
#       (i-sixcore: core-0's VM was replaced, the raw probe got 000.)
#   E2  no cluster-probe in the fixed suites still reads the raw pinned ${CLUSTER_ENDPOINT} (static tripwire).
#   R1  restore_cluster_baseline with active=6 beside leader counted=5 FAILS, naming both numbers and ids.
#   R2  active=5, counted=5 passes (positive control).
#   R3  active=6 that settles to 5 inside the budget passes (it WAITS rather than failing at once).
#   P1  pick_non_leader never returns the node that ANSWERED the status read (the pinned entry point).
#   P2  ...unless too few other candidates exist: then it is offered as a logged fallback.
#   P3  entry point == leader: no effect, candidates are the other four.
#   R4  active=5 beside counted=4 (the other direction) also fails.
# The REAL restore_cluster_baseline / _restore_active_counted_gate / helpers are extracted verbatim from
# lib/cluster.sh, and the REAL assert_http_status / _api_call-free probe from lib/common.sh; only their
# collaborators are stubbed.
#   SCRIPT_UNDER_TEST_LIB / SCRIPT_UNDER_TEST_SUITES select alternate copies (mutation probes).
#   bash aether/tests/integration/test/test-harness-endpoint-and-core-count.sh
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CLUSTER_SH="${CLUSTER_SH_UNDER_TEST:-${INTEG_DIR}/lib/cluster.sh}"
GOSSIP="${GOSSIP_UNDER_TEST:-${INTEG_DIR}/suites/12-network/test-gossip-encryption.sh}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap '[ -n "${KEEP:-}" ] && echo "WORK=$WORK" >&2 || rm -rf "$WORK"' EXIT

extract() { sed -n "/^$2() {/,/^}/p" "$1"; }

# ---- E1: the real gossip test file against stubs ------------------------------------------
mkdir -p "$WORK/suites/12-network" "$WORK/lib" "$WORK/bin"
cp "$GOSSIP" "$WORK/suites/12-network/script.sh"
{
cat <<'STUB'
log_info() { :; }; log_warn() { :; }; log_fail() { echo "FAIL $*"; }; log_pass() { echo "PASS $*"; }
run_test() { if "$2"; then echo "RUN-OK $1"; else echo "RUN-FAILED $1"; fi; }
print_summary() { :; }
assert_eq() { if [ "$1" = "$2" ]; then log_pass "$3"; else log_fail "$3: got $1 want $2"; return 1; fi; }
assert_ne() { :; }; assert_gt() { :; }; assert_cluster_healthy() { :; }
wait_for() { return 0; }; wait_for_cluster_ready() { :; }
cluster_active_core_count() { echo 5; }
_resolve_live_endpoint() { echo "http://live:8080"; }
CLUSTER_ENDPOINT="http://pinned-dead:8080"
STUB
extract "${INTEG_DIR}/lib/common.sh" assert_http_status
} > "$WORK/lib/common.sh"
echo ':' > "$WORK/lib/cluster.sh"
# curl stub: only the live endpoint answers.
cat > "$WORK/bin/curl" <<'STUB'
#!/bin/bash
for a in "$@"; do case "$a" in http://live:8080/*) echo 200; exit 0 ;; esac; done
echo 000
STUB
chmod +x "$WORK/bin/curl"
out=$(PATH="$WORK/bin:$PATH" bash "$WORK/suites/12-network/script.sh" 2>&1)
if printf '%s' "$out" | grep -q 'RUN-OK .*[Ll]iveness\|PASS Liveness probe over encrypted transport'; then
    ok "E1 gossip liveness probe fails over to the live endpoint (pinned endpoint dead)"
else fail "E1 output: $(printf '%s' "$out" | grep -i 'live\|FAIL' | head -3 | tr '\n' '|')"; fi

# ---- E2: static tripwire ------------------------------------------------------------------
left=$(cd "${INTEG_DIR}/suites" && grep -n '\${CLUSTER_ENDPOINT}' \
    00-smoke/test-cluster-formation.sh 07-cluster-mgmt/test-bootstrap.sh 11-observability/test-alerts.sh \
    11-observability/test-invocation-traces.sh 11-observability/test-prometheus-metrics.sh \
    12-network/test-gossip-encryption.sh 05-security/test-principal-injection.sh \
    05-security/test-route-security.sh 08-resources/test-http-client.sh \
    | grep -v 'test-bootstrap.sh:1[0-9]:' | grep -v '/api/v1/scale\|blueprints/validate' || true)
if [ -z "$left" ]; then ok "E2 no raw \${CLUSTER_ENDPOINT} probe left in the fixed suites"
else fail "E2 raw pinned probes remain: $(printf '%s' "$left" | head -3 | tr '\n' '|')"; fi

# ---- R: restore_cluster_baseline -----------------------------------------------------------
{
cat <<'STUB'
log_info() { echo "INFO $*"; }; log_warn() { echo "WARN $*"; }; log_fail() { echo "FAIL $*"; }
cluster_leader_http() { echo node-1; }
restart_all_nodes() { return 0; }
_refresh_mgmt_entry_point() { return 0; }
enable_auto_heal() { return 0; }
reset_provisioning_circuit() { return 0; }
api_get() {
    case "$1" in
        /api/v1/nodes/lifecycle) echo '[]' ;;
        /api/v1/cluster/topology) echo "{\"coreCount\":${ACTIVE},\"coreNodes\":[$(seq 1 "$ACTIVE" | sed 's/.*/"node-&"/' | paste -sd, -)]}" ;;
    esac
}
activate_node() { return 0; }
scale_cluster() { return 0; }
wait_for() { eval "$2"; }
ready_core_count() { echo 5; }
await_generation_quiesced() { return 0; }
wait_for_phase() { return 0; }
cluster_no_deficit() { return 0; }
slices_active_instances() { echo 3; }
slices_target_total() { echo 3; }
provisioning_snapshot() { echo "{\"leader\":true,\"countedCoreMembers\":${COUNTED},\"deficit\":0,\"effective\":${COUNTED}}"; }
# ACTIVE can settle: SETTLE_AFTER=<seconds since start>, SETTLE_TO=<value>
cluster_active_core_count() {
    if [ -n "${SETTLE_AFTER:-}" ] && [ $((SECONDS - START)) -ge "$SETTLE_AFTER" ]; then echo "$SETTLE_TO"; else echo "$ACTIVE"; fi
}
STUB
for f in _active_core_ids _leader_counted_core_members _restore_active_counted_gate restore_cluster_baseline; do extract "$CLUSTER_SH" "$f"; done
} > "$WORK/restore.sh"
grep -c '_restore_active_counted_gate\|restore_cluster_baseline()' "$WORK/restore.sh" > /dev/null

run_restore() {  # <label> <ACTIVE> <COUNTED> [SETTLE_AFTER SETTLE_TO]
    ( export CLUSTER_ENDPOINT=http://stub NODE_COUNT=5 TIMEOUT_SCALE=1 AETHER_RESTORE_COUNTED_TIMEOUT=3 AETHER_RESTORE_COUNTED_POLL=1 CLOUD_MODE=false
      ACTIVE="$2"; COUNTED="$3"; SETTLE_AFTER="${4:-}"; SETTLE_TO="${5:-}"; START=$SECONDS
      source "$WORK/restore.sh"
      restore_cluster_baseline ) > "$WORK/r.$1" 2>&1
    echo $? > "$WORK/rc.$1"
}
run_restore r1 6 5
if [ "$(cat "$WORK/rc.r1")" = "1" ] && grep -q 'active=6 counted=5' "$WORK/r.r1" && grep -q 'node-6' "$WORK/r.r1"; then
    ok "R1 active=6 beside counted=5 fails loudly, naming both numbers and the active ids"
else fail "R1 rc=$(cat "$WORK/rc.r1") log: $(grep -c . "$WORK/r.r1") lines; $(grep 'active=' "$WORK/r.r1" | head -2 | tr '\n' '|')"; fi
run_restore r2 5 5
if [ "$(cat "$WORK/rc.r2")" = "0" ] && grep -q 'active=5 == leader counted=5 == target=5' "$WORK/r.r2"; then
    ok "R2 active=5 == counted=5 == target passes (positive control)"
else fail "R2 rc=$(cat "$WORK/rc.r2")"; fi
run_restore r3 6 5 1 5
if [ "$(cat "$WORK/rc.r3")" = "0" ]; then ok "R3 active 6 settling to 5 inside the budget passes (it waits)"
else fail "R3 rc=$(cat "$WORK/rc.r3")"; fi
run_restore r4 5 4
if [ "$(cat "$WORK/rc.r4")" = "1" ] && grep -q 'active=5 counted=4' "$WORK/r.r4"; then ok "R4 active=5 beside counted=4 fails too"
else fail "R4 rc=$(cat "$WORK/rc.r4")"; fi

# ---- P: pick_non_leader vs the pinned entry-point node ---------------------------------------
{
cat <<'STUB'
log_info() { :; }; log_warn() { echo "WARN $*" >&2; }; log_fail() { echo "FAIL $*" >&2; }
CLOUD_MODE=true
api_get() { echo "{\"nodeId\":\"${ENTRY}\",\"cluster\":{\"leaderId\":\"core-1\",\"quorate\":true}}"; }
aether_failover() { echo '[{"nodeId":"core-1","state":"READY"},{"nodeId":"core-2","state":"READY"},{"nodeId":"core-3","state":"READY"},{"nodeId":"core-4","state":"READY"},{"nodeId":"core-5","state":"READY"}]'; }
remote_exec() { :; }
STUB
extract "$CLUSTER_SH" mgmt_entry_point_node
extract "$CLUSTER_SH" pick_non_leader
} > "$WORK/pick.sh"
run_pick() {  # <label> <ENTRY> <count>
    ( export PICK_NON_LEADER_TIMEOUT=2 MGMT_ENTRY_POINT=http://stub ENTRY="$2"; source "$WORK/pick.sh"; pick_non_leader core-1 "$3" ) > "$WORK/p.$1" 2> "$WORK/pe.$1"
    echo $? > "$WORK/prc.$1"
}
run_pick p1 core-2 2
if [ "$(cat "$WORK/prc.p1")" = "0" ] && [ "$(grep -c . "$WORK/p.p1")" = "2" ] && ! grep -qx 'core-2' "$WORK/p.p1"; then
    ok "P1 the entry-point node (core-2, the status answerer) is never picked: $(tr '\n' ' ' < "$WORK/p.p1")"
else fail "P1 rc=$(cat "$WORK/prc.p1") picked=[$(tr '\n' ' ' < "$WORK/p.p1")]"; fi
run_pick p2 core-2 4
if [ "$(cat "$WORK/prc.p2")" = "0" ] && [ "$(grep -c . "$WORK/p.p2")" = "4" ] && grep -qx 'core-2' "$WORK/p.p2" && grep -q 'entry-point node core-2' "$WORK/pe.p2"; then
    ok "P2 count 4 of 5 forces the entry-point fallback, with a WARN"
else fail "P2 rc=$(cat "$WORK/prc.p2") picked=[$(tr '\n' ' ' < "$WORK/p.p2")]"; fi
run_pick p3 core-1 2
if [ "$(cat "$WORK/prc.p3")" = "0" ] && [ "$(grep -c . "$WORK/p.p3")" = "2" ] && ! grep -qx 'core-1' "$WORK/p.p3"; then
    ok "P3 entry point == leader: two other nodes picked, no fallback warn needed"
else fail "P3 rc=$(cat "$WORK/prc.p3") picked=[$(tr '\n' ' ' < "$WORK/p.p3")]"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
