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
#   Mutations: dropping the body capture reddens L1/L2; ignoring the registry reddens C1 and C2.
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
    env -i PATH="$PATH" HOME="$WORK" TARGET_HOST=localhost CLOUD_MODE=true CLOUD_RUNTIME=container CLUSTER_ID=b \
        BOOTSTRAP_CLUSTER_NAME=test-b AETHER_SSH_KEY=/dev/null SUITE_TAG=03-scaling SUITE_START_EPOCH=1700000000 \
        AETHER_FAILURE_LOGS_DIR="${d}/failure-logs" CAP_DIR="$d" INTEG_DIR="$INTEG_DIR" "$@" \
        bash -c '
            set -uo pipefail
            source "$INTEG_DIR/lib/common.sh" > /dev/null 2>&1; source "$INTEG_DIR/lib/cluster.sh" > /dev/null 2>&1
            _run_with_timeout() { shift; "$@"; }
            _cloud_running_vm_ips() { [ "$1" = test-b ] && cat "$CAP_DIR/listed" 2>/dev/null; return 0; }
            ssh() { local ip="${*: -2:1}"; ip="${ip#*@}"; case " ${GONE_IPS:-} " in *" $ip "*) echo "ssh: connect to host $ip port 22: Connection timed out" >&2; return 255 ;; esac; echo "node-log $ip"; }
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

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
