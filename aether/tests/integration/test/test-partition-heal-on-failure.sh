#!/bin/bash
# test-partition-heal-on-failure.sh — pins that suites/12-network/test-partition-quorum-gate.sh
# heals every partition it injected, on every exit path (stubs only: no cloud, no docker, no ssh).
#   H1  S05 forced to fail (cloud): heal is called for BOTH minority nodes.
#   H2  the script dies right after the partition (cloud) and the minority is no longer a live
#       member (container_for_node empty): the EXIT-trap cleanup heals BY NODE ID and logs it.
#   H3  docker mode with S05 forced to fail: `docker network connect` for both containers
#       (local behaviour unchanged).
#   H5  ORDER: in H1 (and in H1b, where the first heal FAILS) each minority heal precedes S06's start.
#       Without it the in-step heal is unpinned: run_test masks a step failure and the EXIT cleanup
#       heals later, so deleting the in-step heals (or `|| return 1` on the first) left H1-H4 green.
#   S1  every majority read FAILS: S05 is INCONCLUSIVE (loud), never a violation and never a pass.
#   S2  the first two reads fail, later ones succeed: S05 passes (a failed read is unknown, retried).
#   S3  a SUCCESSFUL read reports leaderId=none: that is the violation.
#   S4  reads go to the leader's own endpoint (node_mgmt_endpoint), never the pinned one, whose view
#       (a partitioned node's: leaderless, not quorate) would score as a false violation.
#   H4  docker cleanup with no container found: heal is skipped LOUDLY, naming node and reason.
# The real script runs end to end against a fake tree whose lib/*.sh are stubs that record calls.
#   SCRIPT_UNDER_TEST=<path> selects another copy (used for the mutation probe).
#   bash aether/tests/integration/test/test-partition-heal-on-failure.sh
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SUT="${SCRIPT_UNDER_TEST:-${SCRIPT_DIR}/../suites/12-network/test-partition-quorum-gate.sh}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/suites/12-network" "$WORK/lib" "$WORK/bin"
cp "$SUT" "$WORK/suites/12-network/script.sh"

cat > "$WORK/lib/common.sh" <<'STUB'
log_info() { echo "INFO $*"; }
log_warn() { echo "WARN $*"; }
log_fail() { echo "FAIL $*"; }
log_pass() { echo "PASS $*"; }
run_test() { echo "RUN-START $1" >> "$CALLS"; if "$2"; then echo "RUN-OK $1"; else echo "RUN-FAILED $1"; fi; }
print_summary() { :; }
assert_eq() { :; }
assert_ne() { :; }
assert_cluster_healthy() { :; }
wait_for() { return 0; }
wait_for_cluster_ready() { :; }
wait_for_phase() { :; }
wait_for_leader() { :; }
restore_cluster_baseline() { :; }
to_node_id() { echo "core-${1#node-}"; }
_resolve_live_endpoint() { echo http://pinned; }
API_KEY=k
remote_exec() { echo "remote_exec $1" >> "$CALLS"; }
STUB
cat > "$WORK/lib/cluster.sh" <<'STUB'
cluster_active_core_count() { echo 5; }
cluster_leader() { echo node-1; }
node_mgmt_endpoint() { echo http://majority; }
cluster_quorate() { echo "${STUB_QUORATE:-true}"; }
pick_non_leader() { printf 'node-2\nnode-3\n'; }
# Membership vanishes once any partition was injected (CTM replaced the nodes).
cloud_running_cores() { [ -f "$WORK/partitioned" ] || printf 'core-2\ncore-3\n'; }
cloud_partition_node() {
    echo "partition $1" >> "$CALLS"; touch "$WORK/partitioned"
    if [ "${STUB_DIE_ON:-}" = "$1" ]; then exit 9; fi
    return 0
}
cloud_heal_partition() { echo "heal $1" >> "$CALLS"; [ "${STUB_HEAL_FAIL:-}" = "$1" ] && return 1; return 0; }
STUB
echo 'kv_lifecycle_state() { echo READY; }' > "$WORK/lib/topology.sh"
# Stub curl = the majority node's mgmt API. STUB_CURL: ok (default) | fail | flaky (first 2 reads fail)
# | none (successful read, leaderId=none) | pinned_minority (only http://majority answers correctly).
cat > "$WORK/bin/curl" <<'CURLSTUB'
#!/bin/bash
n=$(( $(cat "$CALLS.curl" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$CALLS.curl"
url="${*: -1}"; echo "curl $url" >> "$CALLS"
case "${STUB_CURL:-ok}" in
    fail) exit 7 ;;
    flaky) [ "$n" -le 2 ] && exit 7 ;;
    none) echo "{\"cluster\":{\"leaderId\":null,\"quorate\":true}}"; exit 0 ;;
esac
case "$url" in
    http://majority/*) echo "{\"cluster\":{\"leaderId\":\"node-1\",\"quorate\":${STUB_QUORATE:-true}}}" ;;
    *) echo "{\"cluster\":{\"leaderId\":null,\"quorate\":false}}" ;;   # a partitioned/pinned node's view
esac
CURLSTUB
chmod +x "$WORK/bin/curl"

# run <label> <env...> -> $WORK/calls.<label>, $WORK/out.<label>
run() {
    local label="$1"; shift
    rm -f "$WORK/partitioned"; : > "$WORK/calls.$label"
    env WORK="$WORK" CALLS="$WORK/calls.$label" CLUSTER_ID=b PATH="$WORK/bin:$PATH" S05_POLL_S=0.2 "$@" \
        bash "$WORK/suites/12-network/script.sh" > "$WORK/out.$label" 2>&1
}

# H1
run h1 CLOUD_MODE=true STUB_QUORATE=false CLOUD_PARTITION_SWIM_WINDOW_S=3 TIMEOUT_SCALE=1
if grep -q 'S05 violation' "$WORK/out.h1" && grep -qx 'heal core-2' "$WORK/calls.h1" && grep -qx 'heal core-3' "$WORK/calls.h1"; then
    ok "H1 forced S05 failure still heals both minority nodes"
else fail "H1 calls: $(tr '\n' '|' < "$WORK/calls.h1")"; fi

# H2: die on the second partition call; only the EXIT trap can heal. Membership is empty by then.
run h2 CLOUD_MODE=true STUB_DIE_ON=core-3 CLOUD_PARTITION_SWIM_WINDOW_S=3 TIMEOUT_SCALE=1
if grep -qx 'heal core-2' "$WORK/calls.h2" && grep -qx 'heal core-3' "$WORK/calls.h2" \
   && grep -q 'WARN cleanup: node-3 is not a live member.*by node id core-3' "$WORK/out.h2"; then
    ok "H2 cleanup with empty container_for_node heals by node id and logs it"
else fail "H2 calls: $(tr '\n' '|' < "$WORK/calls.h2") log: $(grep -c cleanup "$WORK/out.h2") cleanup lines"; fi

# H3 (docker: rc from remote_exec stub is 0, containers resolve to a fixed name via the ps stub)
sed -i.bak 's|^remote_exec() {.*|remote_exec() { echo "remote_exec $1" >> "$CALLS"; case "$1" in *"docker ps"*) echo "ctr-$(echo "$1" \| sed -E "s/.*node-id=([^\x27]*)\x27.*/\\1/")";; esac; }|' "$WORK/lib/common.sh"
run h3 STUB_QUORATE=false
if grep -q 'S05 violation' "$WORK/out.h3" && grep -q 'docker network connect aether-b-network ctr-node-2' "$WORK/calls.h3" \
   && grep -q 'docker network connect aether-b-network ctr-node-3' "$WORK/calls.h3"; then
    ok "H3 docker mode: forced S05 failure reconnects both containers"
else fail "H3 calls: $(tr '\n' '|' < "$WORK/calls.h3")"; fi

# H4: docker, no container resolvable at cleanup
sed -i.bak 's|^remote_exec() {.*|remote_exec() { echo "remote_exec $1" >> "$CALLS"; }|' "$WORK/lib/common.sh"
run h4 STUB_QUORATE=true
if grep -q 'WARN cleanup: SKIPPING heal for node-2: no docker container' "$WORK/out.h4" \
   && grep -q 'WARN cleanup: SKIPPING heal for node-3: no docker container' "$WORK/out.h4"; then
    ok "H4 docker cleanup skip is loud, naming node and reason"
else fail "H4 out: $(grep -c SKIPPING "$WORK/out.h4") SKIPPING lines"; fi

# H5: heal-before-S06 ordering. First index of a line in the call log, 0 if absent.
first_line() { grep -n -m1 -- "$2" "$WORK/calls.$1" | cut -d: -f1; }
heals_precede_s06() {  # <label>
    local s06 h2 h3
    s06=$(first_line "$1" '^RUN-START Cluster heals'); h2=$(first_line "$1" '^heal core-2$'); h3=$(first_line "$1" '^heal core-3$')
    [ -n "$s06" ] && [ -n "$h2" ] && [ -n "$h3" ] && [ "$h2" -lt "$s06" ] && [ "$h3" -lt "$s06" ]
}
if heals_precede_s06 h1; then ok "H5a heals of both nodes precede S06 start (S05 failure)"
else fail "H5a order: $(tr '\n' '|' < "$WORK/calls.h1")"; fi
run h1b CLOUD_MODE=true STUB_QUORATE=false STUB_HEAL_FAIL=core-2 CLOUD_PARTITION_SWIM_WINDOW_S=3 TIMEOUT_SCALE=1
if heals_precede_s06 h1b; then ok "H5b second heal precedes S06 start even when the first heal fails"
else fail "H5b order: $(tr '\n' '|' < "$WORK/calls.h1b")"; fi

# S1-S4: the S05 monitor's read discipline (cloud mode, window 3s)
run s1 CLOUD_MODE=true STUB_CURL=fail CLOUD_PARTITION_SWIM_WINDOW_S=3 TIMEOUT_SCALE=1
if grep -q 'S05 inconclusive' "$WORK/out.s1" && ! grep -q 'S05 violation' "$WORK/out.s1" && ! grep -q 'PASS S05' "$WORK/out.s1" \
   && grep -qx 'heal core-2' "$WORK/calls.s1"; then ok "S1 all reads failing is inconclusive (not a violation, not a pass) and still heals"
else fail "S1 out: $(grep -c 'S05' "$WORK/out.s1") S05 lines: $(grep 'S05' "$WORK/out.s1" | head -2 | cut -c1-90 | tr '\n' '|')"; fi
run s2 CLOUD_MODE=true STUB_CURL=flaky CLOUD_PARTITION_SWIM_WINDOW_S=3 TIMEOUT_SCALE=1
if grep -q 'PASS S05: majority stayed quorate' "$WORK/out.s2" && ! grep -q 'S05 violation\|S05 inconclusive' "$WORK/out.s2"; then
    ok "S2 two failed reads then successes: S05 passes (failed read = unknown)"
else fail "S2 out: $(grep 'S05' "$WORK/out.s2" | head -2 | cut -c1-90 | tr '\n' '|')"; fi
run s3 CLOUD_MODE=true STUB_CURL=none CLOUD_PARTITION_SWIM_WINDOW_S=3 TIMEOUT_SCALE=1
if grep -q 'S05 violation: the majority.*NO leader' "$WORK/out.s3"; then ok "S3 a successful leaderId=none read is the violation"
else fail "S3 out: $(grep 'S05' "$WORK/out.s3" | head -2 | cut -c1-90 | tr '\n' '|')"; fi
run s4 CLOUD_MODE=true CLOUD_PARTITION_SWIM_WINDOW_S=3 TIMEOUT_SCALE=1
if grep -q 'PASS S05' "$WORK/out.s4" && grep -q '^curl http://majority/' "$WORK/calls.s4" && ! grep -q '^curl http://pinned' "$WORK/calls.s4"; then
    ok "S4 majority reads go to the leader's endpoint, none to the pinned node"
else fail "S4 curl targets: $(grep '^curl' "$WORK/calls.s4" | sort | uniq -c | tr '\n' '|')"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
