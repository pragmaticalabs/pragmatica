#!/bin/bash
# test-s05-isolation.sh — stubs only (a stub curl and stubbed partition primitives; no cluster, no cloud). 12-network S05.
# Run 7 (cloud): the two minority partition firewalls landed ~20 s apart and the established QUIC flows outlived the provider
# firewall, so for a window the "2-vs-3 partition" was a half-cut node that still held links to a quorum; it won an election and
# deposed the healthy leader (product #1748). The harness was exposing that, and also measuring firewall statefulness.
#   C1  both partitions are applied CONCURRENTLY (both start before either finishes), and one failing is a loud FAIL
#   I1-I7  the S05 clock starts only after ISOLATION: every majority node's own membership shows both minority nodes gone and the
#       leader has no live link to either; an asymmetric cut, a live link or an unreadable read is NOT isolation, and a split
#       that never completes is an honest FAIL naming who still sees whom
#   O1-O2  order in the test function: apply both -> confirm isolation -> monitor; no isolation = no monitoring, but the heal still runs
#   L1-L4  LEADER CONTINUITY: a leader other than the pre-partition one at ANY majority read (isolation wait or window) is a FAIL,
#       the PASS line prints the leaders actually observed, an unreadable read stays unknown; L1 is v1792's run-7 stub
#   M1-M2  the failure text prints the HTTP status and body of the last read, and does not say "the leader may be down" when the
#       answering node returned a forwarding error
#   Mutations: sequential apply reddens C1; no isolation wait reddens O1 (and I-cases for the function removed); the old wording reddens M1.
#   SUITE_UNDER_TEST selects another copy of the suite file.
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SUITE="${SUITE_UNDER_TEST:-${INTEG_DIR}/suites/12-network/test-partition-quorum-gate.sh}"
if ! awk 'prev ~ /^if \[ "\$\{BASH_SOURCE\[0\]\}" != "\$0" \]; then$/ && $0 ~ /^    return 0$/ { found = 1 } { prev = $0 } END { exit !found }' "$SUITE"; then
    echo "  FAIL  ${SUITE##*/} has no source guard — sourcing it would run its live scenario; no case was run"; echo "  passed: 0"; echo "  failed: 1"; exit 1
fi
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }
WORK=$(mktemp -d)
trap '[ -n "${KEEP:-}" ] && echo "WORK=$WORK" >&2 || rm -rf "$WORK"' EXIT

# curl stub. Response files: $D/<kind>.<host>[.<n>] (n = the n-th call of that kind to that host), first line the HTTP code, the
# rest the body; kind is membership|topology|status by URL path. No file: curl rc 7 and "000". HTTP errors exit 0 (no -f).
install_stubs='
curl() {
    local out="" w="" url="${*: -1}" a prev="" host path kind n f code
    for a in "$@"; do [ "$prev" = "-o" ] && out="$a"; [ "$prev" = "-w" ] && w="$a"; prev="$a"; done
    host="${url#http://}"; host="${host%%/*}"; path="/${url#http://*/}"
    case "$path" in */cluster/membership) kind=membership ;; */cluster/topology) kind=topology ;; */nodes/status) kind=status ;; *) kind=other ;; esac
    n=$(( $(cat "$D/n.$kind.$host" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$D/n.$kind.$host"
    f="$D/$kind.$host.$n"; [ -f "$f" ] || f="$D/$kind.$host"
    if [ ! -f "$f" ]; then [ -n "$w" ] && printf 000; return 7; fi
    code=$(head -1 "$f")
    if [ -n "$out" ]; then tail -n +2 "$f" > "$out"; else tail -n +2 "$f"; fi
    [ -n "$w" ] && printf "%s" "$code"
    return 0
}
sleep() { SECONDS=$((SECONDS + ${1%%.*})); }
node_mgmt_endpoint() { echo "http://$1"; }
to_node_id() { echo "$1"; }
'

member_body() {  # <state of node-2> <state of node-5>  -> membership JSON (flat member objects, as the API renders them)
    printf '200\n{"nodeId":"self","belowThreshold":false,"members":['
    printf '{"nodeId":"node-1","state":"Member","strictCore":true},{"nodeId":"node-3","state":"Member","strictCore":true},{"nodeId":"node-4","state":"Member","strictCore":true}'
    [ "$1" != "absent" ] && printf ',{"nodeId":"node-2","state":"%s","strictCore":false}' "$1"
    [ "$2" != "absent" ] && printf ',{"nodeId":"node-5","state":"%s","strictCore":false}' "$2"
    printf ']}'
}
topo_body() {  # <health node-2> <health node-5>
    printf '200\n{"coreCount":3,"nodeDetails":['
    printf '{"nodeId":"node-1","role":"ACTIVE","assignedRole":"CORE","health":"CONNECTED","hostname":"","zone":"","address":"a"}'
    [ "$1" != "absent" ] && printf ',{"nodeId":"node-2","role":"ACTIVE","assignedRole":"CORE","health":"%s","hostname":"","zone":"","address":"b"}' "$1"
    [ "$2" != "absent" ] && printf ',{"nodeId":"node-5","role":"ACTIVE","assignedRole":"CORE","health":"%s","hostname":"","zone":"","address":"c"}' "$2"
    printf ']}'
}

run_case() {  # <label> <snippet>  -> output in $WORK/out.<label>, rc in rc.<label>
    local label="$1" snip="$2"
    mkdir -p "$WORK/d.$label"
    ( export D="$WORK/d.$label" TARGET_HOST=localhost API_KEY=k NODE_COUNT=5 CLUSTER_ID=b ENV_TYPE=docker
      export S05_POLL_S=0 S05_ISOLATION_POLL_S=1
      unset CLOUD_MODE
      source "$SUITE" > /dev/null 2>&1
      set +e
      eval "$install_stubs"
      PARTITION_DURATION_S=10
      eval "$snip" ) > "$WORK/out.$label" 2>&1
    echo $? > "$WORK/rc.$label"
}
setup_isolated() {  # <label> [state2 state5 health2 health5]
    local d="$WORK/d.$1"; mkdir -p "$d"
    local s2="${2:-Dead}" s5="${3:-Suspect}" h2="${4:-DISCOVERED}" h5="${5:-absent}"
    for h in node-1 node-3 node-4; do member_body "$s2" "$s5" > "$d/membership.$h"; done
    topo_body "$h2" "$h5" > "$d/topology.node-1"
}
ISO='_s05_wait_isolated 6 node-1 "node-2 node-5" "node-1
node-3
node-4"'

# ---- C1: concurrent apply -------------------------------------------------------------------------------------------------
C1_STUB='disconnect_node_from_network() { echo "start $1" >> "$D/order"; command sleep 1; echo "end $1" >> "$D/order"; [ "$1" != "${FAILING:-}" ]; }
log_info() { echo "INFO $*"; }; log_fail() { echo "FAIL $*"; }'
run_case c1 "$C1_STUB"'
_s05_partition_both nodeA nodeB; echo "RC=$?"'
if [ "$(sed -n '1,2p' "$WORK/d.c1/order" | grep -c '^start')" = "2" ] && [ "$(sed -n '3,4p' "$WORK/d.c1/order" | grep -c '^end')" = "2" ] && grep -q 'RC=0' "$WORK/out.c1" && grep -q 'both partitions applied' "$WORK/out.c1"; then
    ok "C1 both partitions are applied concurrently (start, start, end, end), rc 0, and the completion spread is logged"
else fail "C1 order=$(tr '\n' ',' < "$WORK/d.c1/order") out=$(head -c 200 "$WORK/out.c1")"; fi
run_case c1f "FAILING=nodeB; $C1_STUB"'
_s05_partition_both nodeA nodeB; echo "RC=$?"'
if grep -q 'RC=1' "$WORK/out.c1f" && grep -q 'FAIL S05: partition not fully applied (nodeA rc=0, nodeB rc=1)' "$WORK/out.c1f"; then
    ok "C1b one partition failing: rc 1 and a FAIL naming which one (the 2-vs-3 split does not exist)"
else fail "C1b out=$(head -c 240 "$WORK/out.c1f")"; fi

# ---- I: isolation ---------------------------------------------------------------------------------------------------------
log_stub='log_info() { echo "INFO $*"; }; log_fail() { echo "FAIL $*"; }; '
setup_isolated i1
run_case i1 "$log_stub$ISO"'; echo "RC=$?"'
if grep -q 'RC=0' "$WORK/out.i1" && grep -q 'INFO S05: isolation confirmed' "$WORK/out.i1"; then
    ok "I1 every majority node shows both minority nodes not-Member and the leader has no live link: isolation confirmed (rc 0)"
else fail "I1 out=$(head -c 240 "$WORK/out.i1")"; fi

setup_isolated i2; member_body Dead Member > "$WORK/d.i2/membership.node-3"   # node-3 still sees node-5 as a Member
run_case i2 "$log_stub$ISO"'; echo "RC=$?"'
if grep -q 'RC=1' "$WORK/out.i2" && grep -q 'FAIL S05: isolation never completed within 6s' "$WORK/out.i2" && grep -q 'node-3->node-5=Member' "$WORK/out.i2" \
   && ! grep -q 'node-4->' "$WORK/out.i2"; then
    ok "I2 asymmetric cut (one majority node still sees node-5 as Member): never isolated, honest FAIL naming exactly node-3->node-5"
else fail "I2 out=$(head -c 320 "$WORK/out.i2")"; fi

setup_isolated i3
for h in node-1 node-3 node-4; do member_body Member Member > "$WORK/d.i3/membership.$h.1"; member_body Member Suspect > "$WORK/d.i3/membership.$h.2"; done
run_case i3 "$log_stub$ISO"'; echo "RC=$?"'
if grep -q 'RC=0' "$WORK/out.i3" && [ "$(cat "$WORK/d.i3/n.membership.node-3")" -ge 3 ]; then
    ok "I3 the split completes after two polls (Member, Member -> Suspect, Dead): the wait polls until then, then confirms"
else fail "I3 out=$(head -c 240 "$WORK/out.i3") polls=$(cat "$WORK/d.i3/n.membership.node-3" 2>/dev/null)"; fi

setup_isolated i4 Dead Dead CONNECTED absent
run_case i4 "$log_stub$ISO"'; echo "RC=$?"'
if grep -q 'RC=1' "$WORK/out.i4" && grep -q 'leader-link->node-2=CONNECTED' "$WORK/out.i4"; then
    ok "I4 membership says gone but the leader still has a live link (CONNECTED) to node-2: not isolated (QUIC outlived the firewall)"
else fail "I4 out=$(head -c 300 "$WORK/out.i4")"; fi

setup_isolated i5; rm -f "$WORK/d.i5/membership.node-4"
run_case i5 "$log_stub$ISO"'; echo "RC=$?"'
if grep -q 'RC=1' "$WORK/out.i5" && grep -q 'node-4->node-2=?' "$WORK/out.i5"; then
    ok "I5 an unreadable majority node is unknown, never isolation: FAIL after the budget, node-4 reported '?'"
else fail "I5 out=$(head -c 300 "$WORK/out.i5")"; fi

setup_isolated i6
run_case i6 "$log_stub"'membership_node_state() { return 127; }
'"$ISO"'; echo "RC=$?"'
if grep -q 'RC=1' "$WORK/out.i6" && grep -q 'node-3->node-2=?' "$WORK/out.i6" && ! grep -q 'isolation confirmed' "$WORK/out.i6"; then
    ok "I6 a missing/failing membership parser reads as UNKNOWN ('?'), never as 'the node is gone': no vacuous isolation"
else fail "I6 out=$(head -c 300 "$WORK/out.i6")"; fi

run_case i7 "$log_stub"'_s05_wait_isolated 6 node-1 "node-2 node-5" ""; echo "RC=$?"'
if grep -q 'RC=1' "$WORK/out.i7" && grep -q 'no majority nodes could be enumerated' "$WORK/out.i7"; then
    ok "I7 an empty majority list cannot confirm isolation: FAIL, not a vacuous pass"
else fail "I7 out=$(head -c 300 "$WORK/out.i7")"; fi

# ---- O: ordering in the test function ------------------------------------------------------------------------------------
O_STUB='kv_lifecycle_state() { echo READY; }; container_for_node() { echo "c-$1"; }
_s05_partition_both() { echo BOTH >> "$D/seq"; return 0; }
_s05_wait_isolated() { echo WAIT >> "$D/seq"; return "${ISO_RC:-0}"; }
monitor_majority_during_partition() { echo MONITOR >> "$D/seq"; return 0; }
connect_node_to_network() { echo "HEAL $1" >> "$D/seq"; return 0; }
log_info() { :; }; log_warn() { :; }; log_fail() { echo "FAIL $*"; }; log_pass() { :; }
MINORITY_FILE="$D/minority"; LEADER_FILE="$D/leader"; printf "node-2\nnode-5\n" > "$MINORITY_FILE"; printf node-1 > "$LEADER_FILE"
_s05_majority_ids() { printf "node-1\nnode-3\nnode-4\n"; }'
run_case o1 "$O_STUB"'
test_partition_does_not_destabilize_majority; echo "RC=$?"'
if [ "$(tr '\n' ' ' < "$WORK/d.o1/seq")" = "BOTH WAIT MONITOR HEAL c-node-2 HEAL c-node-5 " ] && grep -q 'RC=0' "$WORK/out.o1"; then
    ok "O1 order: both partitions applied -> isolation confirmed -> monitor window -> heal both"
else fail "O1 seq=$(tr '\n' ' ' < "$WORK/d.o1/seq") out=$(head -c 160 "$WORK/out.o1")"; fi
run_case o2 "ISO_RC=1; $O_STUB"'
test_partition_does_not_destabilize_majority; echo "RC=$?"'
if [ "$(tr '\n' ' ' < "$WORK/d.o2/seq")" = "BOTH WAIT HEAL c-node-2 HEAL c-node-5 " ] && grep -q 'RC=1' "$WORK/out.o2"; then
    ok "O2 isolation never completes: NO monitoring window runs, the test fails, and both nodes are still healed"
else fail "O2 seq=$(tr '\n' ' ' < "$WORK/d.o2/seq") out=$(head -c 160 "$WORK/out.o2")"; fi

# ---- L: LEADER CONTINUITY (v1816: the isolation wait must not be where a deposition heals unseen) ---------------------------
# Time-scripted world (virtual sleep). t=0 is the moment the partition is applied. Before H the leader endpoint answers the run-7
# forwarding failure (503 "Leader node-2 is not connected for management forward"); from H on the majority reports leader $AFTER
# (quorate). Minority membership/links stay Member/CONNECTED until I, then Dead/DISCOVERED. At base (clock starts at once) the three
# failed reads FAIL; the isolation wait used to let the re-election complete inside it and then start the clock on the NEW leader,
# so S05 PASSED printing "stable leader (node-1)" while every read said node-3 (#1748 masked).
WORLD='
curl() {
    local out="" w="" url="${*: -1}" a prev="" t=$(( SECONDS - T0 )) code body st h
    for a in "$@"; do [ "$prev" = "-o" ] && out="$a"; [ "$prev" = "-w" ] && w="$a"; prev="$a"; done
    case "$url" in
      */nodes/status) if [ "$t" -lt "$H" ]; then code=503; body="{\"error\":\"Leader node-2 is not connected for management forward\"}"; else code=200; body="{\"cluster\":{\"leaderId\":\"$AFTER\",\"quorate\":true}}"; fi ;;
      */cluster/membership) st=Member; [ "$t" -ge "$I" ] && st=Dead; code=200; body="{\"members\":[{\"nodeId\":\"node-1\",\"state\":\"Member\"},{\"nodeId\":\"node-3\",\"state\":\"Member\"},{\"nodeId\":\"node-4\",\"state\":\"Member\"},{\"nodeId\":\"node-2\",\"state\":\"$st\"},{\"nodeId\":\"node-5\",\"state\":\"$st\"}]}" ;;
      */cluster/topology) h=CONNECTED; [ "$t" -ge "$I" ] && h=DISCOVERED; code=200; body="{\"nodeDetails\":[{\"nodeId\":\"node-2\",\"health\":\"$h\"},{\"nodeId\":\"node-5\",\"health\":\"$h\"}]}" ;;
      *) code=000; body="" ;;
    esac
    if [ -n "$out" ]; then printf "%s" "$body" > "$out"; else printf "%s" "$body"; fi
    [ -n "$w" ] && printf "%s" "$code"
    [ "$code" = 000 ] && return 7; return 0
}
S05_POLL_S=1; S05_ISOLATION_POLL_S=1; S05_LEADERS_SEEN_FILE="$D/seen"; : > "$S05_LEADERS_SEEN_FILE"; T0=$SECONDS
log_info() { :; }; log_warn() { :; }; log_fail() { echo "FAIL $*"; }; log_pass() { echo "PASS $*"; }
flow() { _s05_wait_isolated 45 node-1 "node-2 node-5" "node-1
node-3
node-4"; local g=$?; echo "GATE_RC=$g"; [ "$g" -eq 0 ] && { monitor_majority_during_partition node-1; echo "MON_RC=$?"; }; }
'
run_case l1 'H=8; I=12; AFTER=node-3; '"$WORLD"'flow'
if grep -q 'GATE_RC=2' "$WORK/out.l1" && grep -q "FAIL S05 violation: the majority leader changed during the isolation wait: the pre-partition leader was 'node-1'" "$WORK/out.l1" \
   && grep -q 'reports .node-3.' "$WORK/out.l1" && ! grep -q 'PASS' "$WORK/out.l1" && ! grep -q 'MON_RC' "$WORK/out.l1"; then
    ok "L1 #1748 inside the isolation wait: the majority re-elects node-3 while the minority links are still CONNECTED: FAIL 'leader changed', never a PASS 'stable leader (node-1)' (v1792's stub: base FAIL, head 7cc2eb58e PASS)"
else fail "L1 out=$(head -c 500 "$WORK/out.l1")"; fi
run_case l2 'H=0; I=2; AFTER=node-1; '"$WORLD"'flow'
if grep -q 'GATE_RC=0' "$WORK/out.l2" && grep -q 'MON_RC=0' "$WORK/out.l2" && grep -q 'PASS S05: majority stayed quorate with a stable leader (node-1)' "$WORK/out.l2" \
   && grep -q 'leaders observed at every read.*: node-1 x[0-9]*;' "$WORK/out.l2" && ! grep -q 'node-3' "$WORK/out.l2"; then
    ok "L2 control: the leader never changes: PASS, and the line prints the leaders actually observed (node-1 xN only)"
else fail "L2 out=$(head -c 500 "$WORK/out.l2")"; fi
run_case l3 'H=0; I=1; AFTER=node-1; '"$WORLD"'
S05_LEADERS_SEEN_FILE="$D/seen3"; : > "$S05_LEADERS_SEEN_FILE"
_s05_wait_isolated 45 node-1 "node-2 node-5" "node-1
node-3
node-4"; echo "GATE_RC=$?"
AFTER=node-3; T0=$(( SECONDS - 100 )); H=0
monitor_majority_during_partition node-1; echo "MON_RC=$?"'
if grep -q 'GATE_RC=0' "$WORK/out.l3" && grep -q 'MON_RC=1' "$WORK/out.l3" && grep -q "FAIL S05 violation: the majority leader changed during the partition window: the pre-partition leader was 'node-1', http://node-1 reports 'node-3'" "$WORK/out.l3" && ! grep -q 'PASS' "$WORK/out.l3"; then
    ok "L3 the leader changes only inside the monitoring window (isolation already confirmed): the monitor FAILS 'leader changed', never 'stable leader'"
else fail "L3 out=$(head -c 500 "$WORK/out.l3")"; fi
run_case l4 'H=8; I=12; AFTER=node-1; '"$WORLD"'flow'
if grep -q 'GATE_RC=0' "$WORK/out.l4" && grep -q 'MON_RC=0' "$WORK/out.l4" && grep -q 'leaders observed at every read.*: node-1 x[0-9]*;' "$WORK/out.l4" && ! grep -q 'PASS.*node-3' "$WORK/out.l4"; then
    ok "L4 unreadable leader reads (503 forwarding failure for 8 s) stay UNKNOWN: they neither fail nor count; once readable the leader is node-1, so PASS prints only node-1"
else fail "L4 out=$(head -c 500 "$WORK/out.l4")"; fi

# ---- M: failure wording ---------------------------------------------------------------------------------------------------
mkdir -p "$WORK/d.m1"
for i in 1 2 3 4 5 6; do printf '503\n{"title":"Service Unavailable","detail":"Leader hetzner-eu-core-2 is not connected for management forward /api/v1/nodes/status"}' > "$WORK/d.m1/status.node-1"; done
run_case m1 'log_fail() { echo "FAIL $*"; }; log_pass() { :; }; log_warn() { :; }; log_info() { :; }
monitor_majority_during_partition node-1; echo "RC=$?"'
if grep -q 'RC=1' "$WORK/out.m1" && grep -q 'last read: HTTP 503, body: .*Leader hetzner-eu-core-2 is not connected for management forward' "$WORK/out.m1" \
   && ! grep -q 'the leader may be down' "$WORK/out.m1" && grep -q 'the leader may have changed' "$WORK/out.m1"; then
    ok "M1 three failed reads: the FAIL prints the HTTP status and the forwarding-error body, and does not claim the leader may be down"
else fail "M1 out=$(head -c 500 "$WORK/out.m1")"; fi
mkdir -p "$WORK/d.m2"; printf '200\n{"leaderId":"node-1","quorate":true,"cluster":{}}' > "$WORK/d.m2/status.node-1"
run_case m2 'log_fail() { echo "FAIL $*"; }; log_pass() { echo "PASS $*"; }; log_warn() { :; }; log_info() { :; }
monitor_majority_during_partition node-1; echo "RC=$?"'
if grep -q 'RC=0' "$WORK/out.m2" && grep -q 'PASS S05: majority stayed quorate' "$WORK/out.m2"; then ok "M2 control: healthy 200 reads still pass S05"
else fail "M2 out=$(head -c 300 "$WORK/out.m2")"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
