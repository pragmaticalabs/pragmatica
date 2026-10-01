#!/bin/bash
# test-load-bodies-and-vm-capture.sh — stubs only (a stub `curl`, stub `ssh`/provider; no cluster, no cloud).
# Two diagnostics so the next run can attribute 03 Scale_down's 503s (S-triple-prime: 12 of 482 requests at 503, no
# body recorded, and the fail-time capture had omitted core-4 (178.105.27.245), the scale-down victim the load was
# pinned to):
#   L1  the load generator records, per non-2xx: UTC time, status, endpoint and the BODY (<= 512 bytes), one line per
#       failure, into failure-logs/<suite>/load-failures-<test>.log
#   L2  stop_load logs a per-detail histogram next to the status histogram: two different 503 `detail`s are two rows
#   C1  a VM that left mid-suite (a scale-down victim) but still answers is CAPTURED, and the manifest says it is no
#       longer listed; a node added mid-suite is captured too
#   C2  a remembered VM that is gone and unreachable is logged as GONE in the manifest, and the rest are captured
#   C3  control: a registry left by a PREVIOUS run is forgotten at suite start (its VM is not captured)
#   C4  wiring: run_test and run_suite remember/forget the VM set
#   B1  the suite-end capture KEEPS this run's load-failure bodies (its stale-clear used to delete them)
#   B2  a 000 after a 503-with-body has an EMPTY detail (scratch truncated per tick); curl is bounded (--connect-timeout/--max-time)
#   D1-D4  the logs of every voter removed by a scale-down are captured at demotion, bounded, once per test, never failing
#   Mutations: dropping the body capture reddens L1/L2; ignoring the registry reddens C1 and C2; going back to
#   `_run_with_timeout _cloud_running_vm_ips` (a function under a real `timeout`) reddens C1-C3.
#   INTEG_DIR_UNDER_TEST=<path> selects another copy of aether/tests/integration.
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="${INTEG_DIR_UNDER_TEST:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap '[ -n "${KEEP:-}" ] && echo "WORK=$WORK" >&2 || rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"
# A `timeout` binary that behaves like coreutils': it can run a BINARY on PATH only. Given a shell function it exec-fails
# with rc 127 ("failed to run command"), which is how a production run on Linux treated `_run_with_timeout
# _cloud_running_vm_ips`. NO `_run_with_timeout` stub below: the tests use the real helper, so they walk the production path.
cat > "$WORK/bin/timeout" <<'STUB'
#!/bin/bash
[ "$1" = "-k" ] && shift 2
shift
exec "$@"
STUB
chmod +x "$WORK/bin/timeout"
# ssh as a real executable (a binary, like production), so the real `timeout` path can run it.
cat > "$WORK/bin/ssh" <<'STUB'
#!/bin/bash
ip="${*: -2:1}"; ip="${ip#*@}"
[ -n "${SSH_LOG:-}" ] && echo "$ip" >> "$SSH_LOG"
case " ${GONE_IPS:-} " in *" $ip "*) echo "ssh: connect to host $ip port 22: Connection timed out" >&2; exit 255 ;; esac
echo "node-log $ip"
STUB
chmod +x "$WORK/bin/ssh"

# ---- L: load generator -------------------------------------------------------------------------------------------
# Stub curl: parses `-o <file>` and answers every request 503; odd calls say "Quorum disappeared", even "Route table
# propagating" (two different `detail`s, as the two 503 branches would).
cat > "$WORK/bin/curl" <<'STUB'
#!/bin/bash
out=""; args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do [ "${args[$i]}" = "-o" ] && out="${args[$((i + 1))]}"; done
n=$(( $(cat "$CALLS" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$CALLS"
if [ $((n % 2)) -eq 1 ]; then body='{"type":"about:blank","title":"Service Unavailable","status":503,"detail":"Quorum disappeared — quiescing app HTTP routing"}'
else body='{"type":"about:blank","title":"Service Unavailable","status":503,"detail":"Route table propagating"}'; fi
[ -n "$out" ] && [ "$out" != /dev/null ] && printf '%s' "$body" > "$out"
printf '503'
STUB
chmod +x "$WORK/bin/curl"
FLOGS="$WORK/failure-logs"
( export PATH="$WORK/bin:$PATH" CALLS="$WORK/curl.calls" TARGET_HOST=localhost AETHER_FAILURE_LOGS_DIR="$FLOGS" SUITE_TAG=03-scaling TEST_TAG=Scale_down \
         API_KEY=k APP_ENDPOINT=http://178.105.27.245:8070
  source "${INTEG_DIR}/lib/load.sh" > /dev/null 2>&1
  start_load 20 2 GET /api/echo/health > /dev/null 2>&1
  sleep 3
  stop_load > "$WORK/stop.out" 2> "$WORK/stop.err" )
LF="$FLOGS/03-scaling/load-failures-Scale_down.log"
n_lines=$(grep -c . "$LF" 2>/dev/null || echo 0)
if [ "$n_lines" -ge 4 ] && awk -F'\t' '$2 == "503" && $3 == "http://178.105.27.245:8070/api/echo/health" && $1 ~ /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z$/' "$LF" | grep -q . \
   && grep -q 'Quorum disappeared' "$LF" && grep -q 'Route table propagating' "$LF"; then
    ok "L1 every failure is a line with time, status 503, the endpoint and the BODY ($n_lines lines, both details present)"
else fail "L1 lines=${n_lines} sample=$(head -2 "$LF" 2>/dev/null | cut -c1-160 | tr '\n' '|')"; fi
hist=$(grep 'Failure details' "$WORK/stop.err" | head -1)
if printf '%s' "$hist" | grep -q 'Quorum disappeared' && printf '%s' "$hist" | grep -q 'Route table propagating' \
   && [ "$(printf '%s' "$hist" | grep -o '503 |' | wc -l | tr -d ' ')" -ge 2 ]; then
    ok "L2 the per-detail histogram has TWO rows for two different 503 details: $(printf '%s' "$hist" | cut -c1-200)"
else fail "L2 histogram: ${hist:-<none>}"; fi

# ---- C: VM registry / capture --------------------------------------------------------------------------------------
cloud_scenario() {  # <name> <body-fn> [ENV=VAL...]
    local name="$1" body="$2"; shift 2
    local d="${WORK}/${name}"; mkdir -p "$d"
    env -i PATH="$WORK/bin:$PATH" HOME="$WORK" TARGET_HOST=localhost CLOUD_MODE=true CLOUD_RUNTIME=container CLUSTER_ID=b \
        BOOTSTRAP_CLUSTER_NAME=test-b AETHER_SSH_KEY=/dev/null SUITE_TAG=03-scaling SUITE_START_EPOCH=1700000000 \
        AETHER_FAILURE_LOGS_DIR="${d}/failure-logs" CAP_DIR="$d" INTEG_DIR="$INTEG_DIR" "$@" \
        bash -c '
            set -uo pipefail
            source "$INTEG_DIR/lib/common.sh" > /dev/null 2>&1; source "$INTEG_DIR/lib/cluster.sh" > /dev/null 2>&1
            _cloud_running_vm_ips() { [ "$1" = test-b ] && cat "$CAP_DIR/listed" 2>/dev/null; return 0; }
            provisioning_snapshot() { echo "{}"; }
            '"$(declare -f "$body")
$body" > "${d}/out" 2>&1
}
body_leave() {
    printf '1.1.1.1\n2.2.2.2\n3.3.3.3\n' > "$CAP_DIR/listed"
    capture_forget_vms b 03-scaling; capture_remember_vms b 03-scaling          # suite start
    printf '1.1.1.1\n2.2.2.2\n3.3.3.3\n4.4.4.4\n' > "$CAP_DIR/listed"
    capture_remember_vms b 03-scaling                                           # a joiner appears mid-suite (a test start)
    printf '1.1.1.1\n2.2.2.2\n4.4.4.4\n' > "$CAP_DIR/listed"                    # 3.3.3.3 was a scale-down victim: gone from the list
    export SUITE_FAILCAP_DIR; SUITE_FAILCAP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/failcap-stub.XXXXXX")
    TEST_TAG=Scale_down; log_fail "boom" > /dev/null
}
cloud_scenario leave body_leave
d=$(ls -d "$WORK"/leave/failure-logs/03-scaling/Scale_down/*-first-fail 2>/dev/null | head -1)
if [ -n "$d" ] && [ -s "$d/vm-3.3.3.3.log" ] && [ -s "$d/vm-4.4.4.4.log" ] && grep -q 'vm 3.3.3.3 rc=0 .*state=remembered-not-listed' "$d/capture-manifest.txt" \
   && grep -q 'captured 4 of 4' "$d/capture-manifest.txt"; then
    ok "C1 a VM that left mid-suite (3.3.3.3) but still answers is captured and marked remembered-not-listed; the joiner (4.4.4.4) too: 4 of 4"
else fail "C1 dir=[${d:-none}] manifest=$(tr '\n' '|' < "$d/capture-manifest.txt" 2>/dev/null | cut -c1-300) out=$(head -c 200 "$WORK/leave/out")"; fi

cloud_scenario gone body_leave GONE_IPS=3.3.3.3
d=$(ls -d "$WORK"/gone/failure-logs/03-scaling/Scale_down/*-first-fail 2>/dev/null | head -1)
if [ -n "$d" ] && grep -q 'vm 3.3.3.3 GONE' "$d/capture-manifest.txt" && [ -s "$d/vm-1.1.1.1.log" ] && [ -s "$d/vm-4.4.4.4.log" ] \
   && grep -q 'captured 3 of 4' "$d/capture-manifest.txt"; then
    ok "C2 a remembered VM that is gone is logged GONE in the manifest and the others are captured (3 of 4)"
else fail "C2 dir=[${d:-none}] manifest=$(tr '\n' '|' < "$d/capture-manifest.txt" 2>/dev/null | cut -c1-300)"; fi

body_stale() {
    mkdir -p "$AETHER_FAILURE_LOGS_DIR/vm-registries"; printf '9.9.9.9\n' > "$(_capture_vm_registry_file test-b 03-scaling)"
    printf '1.1.1.1\n' > "$CAP_DIR/listed"
    capture_forget_vms b 03-scaling; capture_remember_vms b 03-scaling           # suite start of a NEW run
    export SUITE_FAILCAP_DIR; SUITE_FAILCAP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/failcap-stub.XXXXXX")
    TEST_TAG=t; log_fail "boom" > /dev/null
}
cloud_scenario stale body_stale
d=$(ls -d "$WORK"/stale/failure-logs/03-scaling/t/*-first-fail 2>/dev/null | head -1)
if [ -n "$d" ] && [ -s "$d/vm-1.1.1.1.log" ] && [ ! -e "$d/vm-9.9.9.9.log" ] && grep -q 'captured 1 of 1' "$d/capture-manifest.txt"; then
    ok "C3 a registry left by a previous run is forgotten at suite start (9.9.9.9 not captured)"
else fail "C3 dir=[${d:-none}] files=$(ls "$d" 2>/dev/null | tr '\n' ' ')"; fi

if grep -q 'capture_remember_vms "\${CLUSTER_ID:-a}"' "${INTEG_DIR}/lib/common.sh" \
   && grep -q 'capture_forget_vms "\$target_cluster"' "${INTEG_DIR}/run-tests.sh" && grep -q 'capture_remember_vms "\$target_cluster"' "${INTEG_DIR}/run-tests.sh"; then
    ok "C4 wiring: run_test remembers the VM set at every test start; run_suite forgets then remembers at suite start"
else fail "C4 wiring missing in common.sh run_test or run-tests.sh run_suite"; fi

# ---- 03 diagnostics hardening (round: fix/03-diag-harness) -----------------------------------------------------------
# B1: the suite-end capture's stale-clear deleted load-failures-<test>.log (written by stop_load just before it).
body_persist_then_capture() {
    local d="$AETHER_FAILURE_LOGS_DIR/03-scaling"
    mkdir -p "$d"
    printf 'x\n' > "$d/vm-9.9.9.9.log"                                   # a stale node log of an earlier run
    printf 'old\tbody\n' > "$d/load-failures-OldTest.log"; touch -t 202001010000 "$d/load-failures-OldTest.log"  # earlier run's bodies
    source "$INTEG_DIR/lib/load.sh" > /dev/null 2>&1
    printf '2026-10-01T00:00:00Z\t503\thttp://h:8070/api/echo/health\t{"detail":"Quorum disappeared"}\n' > "/tmp/load_failure_bodies_$$.txt"
    SUITE_TAG=03-scaling TEST_TAG=Scale_down _load_persist_failure_bodies > /dev/null 2>&1
    printf '1.1.1.1\n' > "$CAP_DIR/listed"
    capture_forget_vms b 03-scaling; capture_remember_vms b 03-scaling
    capture_node_logs 03-scaling b 1700000000 > /dev/null 2>&1               # the suite-end capture (no out_dir)
}
cloud_scenario persist body_persist_then_capture
D="$WORK/persist/failure-logs/03-scaling"
if grep -q 'Quorum disappeared' "$D/load-failures-Scale_down.log" 2>/dev/null && [ -s "$D/vm-1.1.1.1.log" ] \
   && [ ! -e "$D/vm-9.9.9.9.log" ] && [ ! -e "$D/load-failures-OldTest.log" ]; then
    ok "B1 the suite-end capture KEEPS this run's load-failure bodies, still clears stale node logs and an earlier run's bodies"
else fail "B1 files=$(ls "$D" 2>/dev/null | tr '\n' ' ') out=$(head -c 200 "$WORK/persist/out")"; fi

# B2: a 000 (no answer) after a 503-with-body must not inherit the 503's body; curl is bounded
cat > "$WORK/bin/curl-seq" <<'STUB'
#!/bin/bash
out=""; args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do [ "${args[$i]}" = "-o" ] && out="${args[$((i + 1))]}"; done
echo "$*" >> "$ARGS_LOG"
n=$(( $(cat "$CALLS" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$CALLS"
if [ "$n" -eq 1 ]; then printf '{"detail":"Quorum disappeared"}' > "$out"; printf '503'; else printf '000'; fi
STUB
chmod +x "$WORK/bin/curl-seq"; cp "$WORK/bin/curl" "$WORK/bin/curl-keep"; cp "$WORK/bin/curl-seq" "$WORK/bin/curl"
rm -f "$WORK/tick.calls" "$WORK/tick.args" "$WORK/tick.bodies"
( export PATH="$WORK/bin:$PATH" CALLS="$WORK/tick.calls" ARGS_LOG="$WORK/tick.args" TARGET_HOST=localhost API_KEY=k
  source "${INTEG_DIR}/lib/load.sh" > /dev/null 2>&1
  S=$(mktemp)
  _load_tick "$WORK/tick.bodies" "$S" GET http://h:8070/x > /dev/null
  _load_tick "$WORK/tick.bodies" "$S" GET http://h:8070/x > /dev/null )
row1=$(sed -n 1p "$WORK/tick.bodies"); row2=$(sed -n 2p "$WORK/tick.bodies")
d2=$(printf '%s' "$row2" | awk -F'\t' '{print $4}'); s2=$(printf '%s' "$row2" | awk -F'\t' '{print $2}')
if printf '%s' "$row1" | grep -q 'Quorum disappeared' && [ "$s2" = "000" ] && [ -z "$d2" ] \
   && [ "$(grep -c -- '--connect-timeout 2 --max-time 5' "$WORK/tick.args")" = "2" ]; then
    ok "B2 a 503-with-body then a 000: the 000 row's detail is EMPTY (scratch truncated per tick); both requests carry --connect-timeout 2 --max-time 5"
else fail "B2 row1=[$(printf '%s' "$row1" | cut -c1-80)] row2=[$(printf '%s' "$row2" | cut -c1-80)] args=$(head -c 160 "$WORK/tick.args")"; fi
cp "$WORK/bin/curl-keep" "$WORK/bin/curl"

# D: capture at demotion
body_demotion() {
    export SUITE_FAILCAP_DIR; SUITE_FAILCAP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/failcap-stub.XXXXXX")
    export ENV_TYPE=cloud; TEST_TAG=Scale_down
    echo 0 > "$CAP_DIR/vcalls"
    _cluster_voters() { local n; n=$(( $(cat "$CAP_DIR/vcalls") + 1 )); echo "$n" > "$CAP_DIR/vcalls"
        if [ "$n" -le 2 ]; then printf 'node-1\nnode-2\nnode-3\nnode-4\nnode-5\nnode-6\nnode-7\n'; else printf 'node-1\nnode-2\nnode-3\nnode-4\nnode-5\n'; fi; }
    cloud_public_ip() { case "$1" in node-6) echo 6.6.6.6 ;; node-7) echo 7.7.7.7 ;; esac; }
    DEMOTION_CAPTURE_POLL_INTERVAL_S=0.1
    before=$'node-1\nnode-2\nnode-3\nnode-4\nnode-5\nnode-6\nnode-7'
    capture_at_demotion "$before" 10; echo "RC=$?"
    [ -n "${TWICE:-}" ] && { echo 0 > "$CAP_DIR/vcalls"; capture_at_demotion "$before" 10; echo "RC2=$?"; }
    return 0
}
cloud_scenario demo body_demotion ENV_TYPE=cloud
dd=$(ls -d "$WORK"/demo/failure-logs/03-scaling/Scale_down/*-demotion 2>/dev/null | head -1)
if [ -n "$dd" ] && [ -s "$dd/voter-node-6.log" ] && [ -s "$dd/voter-node-7.log" ] && grep -q 'removed voters \[node-6 node-7' "$dd/capture-manifest.txt" \
   && grep -q 'RC=0' "$WORK/demo/out" && [ "$(grep -c . "$WORK/demo/out")" = "1" ]; then
    ok "D1 at demotion EVERY removed voter's log is captured while it still answers (node-6 and node-7), rc 0, nothing printed"
else fail "D1 dir=[${dd:-none}] files=$(ls "$dd" 2>/dev/null | tr '\n' ' ') out=$(head -c 200 "$WORK/demo/out")"; fi

cloud_scenario demog body_demotion ENV_TYPE=cloud GONE_IPS=6.6.6.6
dd=$(ls -d "$WORK"/demog/failure-logs/03-scaling/Scale_down/*-demotion 2>/dev/null | head -1)
if [ -n "$dd" ] && [ -s "$dd/voter-node-7.log" ] && grep -q 'voter node-6 (6.6.6.6) rc=255' "$dd/capture-manifest.txt" && grep -q 'RC=0' "$WORK/demog/out"; then
    ok "D2 a victim that already halted (ssh fails) does not stop the other capture, is noted in the manifest, and the hook returns 0"
else fail "D2 files=$(ls "$dd" 2>/dev/null | tr '\n' ' ') manifest=$(cat "$dd/capture-manifest.txt" 2>/dev/null | tr '\n' '|')"; fi

body_demotion_unarmed() { unset SUITE_FAILCAP_DIR; export ENV_TYPE=cloud; _cluster_voters() { echo node-1; }
    capture_at_demotion $'node-1\nnode-2' 2; echo "RC=$?"; echo "SSH_CALLS=$(ls "$CAP_DIR"/failure-logs/03-scaling/*/ 2>/dev/null | wc -l | tr -d ' ')"; }
cloud_scenario demou body_demotion_unarmed ENV_TYPE=cloud
if grep -q 'RC=0' "$WORK/demou/out" && grep -q 'SSH_CALLS=0' "$WORK/demou/out" && [ -z "$(ls -d "$WORK"/demou/failure-logs/03-scaling/*/*-demotion 2>/dev/null)" ]; then
    ok "D3 outside run-tests.sh (unarmed): the hook does nothing and returns 0"
else fail "D3 out=$(head -c 200 "$WORK/demou/out")"; fi

cloud_scenario demo2 body_demotion ENV_TYPE=cloud TWICE=1 SSH_LOG="$WORK/ssh.demo2"
if [ "$(grep -c . "$WORK/ssh.demo2")" = "2" ] && grep -q 'RC2=0' "$WORK/demo2/out"; then
    ok "D4 once per test: a second call for the same test reads no log (2 ssh calls over two invocations, one per victim)"
else fail "D4 ssh calls=$(grep -c . "$WORK/ssh.demo2" 2>/dev/null) out=$(head -c 120 "$WORK/demo2/out")"; fi

# D5: whatever the body does, the hook returns 0 and cannot end a `set -e` caller
body_demotion_hardfail() { _capture_at_demotion_body() { return 7; }; set -e; capture_at_demotion x 1; echo "SURVIVED"; }
cloud_scenario demof body_demotion_hardfail ENV_TYPE=cloud
if grep -q 'SURVIVED' "$WORK/demof/out"; then ok "D5 a body that fails (rc 7) cannot fail the caller or end a set -e script"
else fail "D5 out=$(head -c 160 "$WORK/demof/out")"; fi

# D6 (N1): a log_fail raised INSIDE the capture must not add a [FAIL] to the test (HARNESS_FAIL_FILE=/dev/null, as _failcap_capture)
body_demotion_logfail() {
    export SUITE_FAILCAP_DIR; SUITE_FAILCAP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/failcap-stub.XXXXXX"); export ENV_TYPE=cloud
    _capture_at_demotion_body() { log_fail "inner failure"; }
    local before; before=$(_harness_fail_lines); TEST_FAIL_COUNT=0
    capture_at_demotion x 1
    echo "DELTA=$(( $(_harness_fail_lines) - before )) TFC=${TEST_FAIL_COUNT} RC=$?"
}
cloud_scenario demolf body_demotion_logfail ENV_TYPE=cloud
if grep -q 'DELTA=0 TFC=0 RC=0' "$WORK/demolf/out"; then ok "D6 a log_fail inside the demotion capture adds nothing to the fail file or the test's latch"
else fail "D6 out=$(head -c 160 "$WORK/demolf/out")"; fi

# D7 (N2a): fd 7 (the suite's saved stderr) is closed in the capture, so a background job cannot hold the suite's stderr pipe
body_demotion_fd7() {
    exec 7>&2
    _capture_at_demotion_body() { if { true >&7; } 2>/dev/null; then echo FD7_OPEN; else echo FD7_CLOSED; fi > "$CAP_DIR/fd7"; }
    capture_at_demotion x 1
}
cloud_scenario demofd body_demotion_fd7 ENV_TYPE=cloud
if [ "$(cat "$WORK/demofd/fd7" 2>/dev/null)" = "FD7_CLOSED" ]; then ok "D7 the capture runs with fd 7 closed (it would otherwise hold the suite's stderr pipe)"
else fail "D7 fd7=$(cat "$WORK/demofd/fd7" 2>/dev/null)"; fi

# D8 (N2b): an ssh that IGNORES SIGTERM is still bounded (timeout -k): ssh-bound 1s + kill grace 1s, never forever
mkdir -p "$WORK/bin-realtimeout"
cat > "$WORK/bin-realtimeout/ssh" <<'STUB'
#!/bin/bash
trap '' TERM
while :; do sleep 1; done
STUB
chmod +x "$WORK/bin-realtimeout/ssh"
body_demotion_hang() {
    export SUITE_FAILCAP_DIR; SUITE_FAILCAP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/failcap-stub.XXXXXX"); export ENV_TYPE=cloud; TEST_TAG=Scale_down
    echo 0 > "$CAP_DIR/vcalls"
    _cluster_voters() { local n; n=$(( $(cat "$CAP_DIR/vcalls") + 1 )); echo "$n" > "$CAP_DIR/vcalls"
        if [ "$n" -le 1 ]; then printf 'node-1\nnode-6\n'; else printf 'node-1\n'; fi; }
    cloud_public_ip() { echo 6.6.6.6; }
    DEMOTION_CAPTURE_POLL_INTERVAL_S=0.1
    local t0=$SECONDS
    capture_at_demotion $'node-1\nnode-6' 10
    echo "ELAPSED=$(( SECONDS - t0 ))"
}
if command -v timeout >/dev/null 2>&1 || command -v gtimeout >/dev/null 2>&1; then
    cloud_scenario demohang body_demotion_hang ENV_TYPE=cloud PATH="$WORK/bin-realtimeout:$PATH" FAILCAP_SSH_TIMEOUT_S=1 TIMEOUT_KILL_GRACE_S=1
    el=$(sed -n 's/^ELAPSED=//p' "$WORK/demohang/out")
    if [ -n "$el" ] && [ "$el" -le 8 ]; then ok "D8 an ssh that ignores SIGTERM is killed after the grace: the capture ended in ${el}s (bound 1s + grace 1s)"
    else fail "D8 elapsed=[${el}] out=$(head -c 160 "$WORK/demohang/out")"; fi
    pkill -KILL -f "$WORK/bin-realtimeout/ssh" 2>/dev/null
else ok "D8 (skipped: no timeout/gtimeout binary on this host; the fallback loop kills -9)"; fi

# D9 (N2c): reap_bg_job ends a job that ignores SIGTERM within its bound (+ the kill step)
body_reap() {
    ( trap '' TERM; while :; do sleep 1; done ) &
    local pid=$!
    local t0=$SECONDS
    reap_bg_job "$pid" 2
    local alive=no; kill -0 "$pid" 2>/dev/null && alive=yes
    echo "REAP alive=${alive} elapsed=$(( SECONDS - t0 ))"
}
cloud_scenario reap body_reap
if grep -q 'REAP alive=no elapsed=[0-5]$' "$WORK/reap/out"; then ok "D9 reap_bg_job: a job ignoring SIGTERM is gone within its bound (2s) plus the kill step"
else fail "D9 out=$(head -c 160 "$WORK/reap/out")"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
