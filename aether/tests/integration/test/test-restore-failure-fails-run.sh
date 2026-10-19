#!/bin/bash
# test-restore-failure-fails-run.sh — stubs only (no cluster, no cloud). The REAL run_cluster_b_suites and the REAL
# print_results (run-tests.sh) with a stubbed run_suite / restore_cluster_baseline. Refs #1972.
# A restore_cluster_baseline that FAILS after a suite used to do one thing: set `aborted`, which turns the NEXT suites into
# `skipped-unrecoverable`. After the LAST suite there is no next suite, so nothing recorded the failure and the run exited 0
# with a FAIL in its log.
#   X1  one suite, restore fails                 -> the run FAILS (print_results rc 1), a failed restore record exists
#   X2  one suite, restore ok                    -> the run passes (positive control)
#   X3  two suites, restore fails after the 1st  -> the 2nd is skipped-unrecoverable, the run FAILS, run_suite ran once
#   X4  restore ok but no committed leader       -> the run FAILS (the other half of the same gate)
#   X5  two suites, restore ok both times        -> both run, the run passes
# Mutations (see the PR): drop the failed record reddens X1/X4; drop `aborted=true` reddens X3.
#   RUNNER_UNDER_TEST selects an alternate copy of run-tests.sh.
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUNNER="${RUNNER_UNDER_TEST:-${INTEG_DIR}/run-tests.sh}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap '[ -n "${KEEP:-}" ] && echo "WORK=$WORK" >&2 || rm -rf "$WORK"' EXIT
extract() { sed -n "/^$2() {/,/^}/p" "$1"; }

{
cat <<'STUB'
log_info() { :; }; log_warn() { echo "WARN $*"; }; log_error() { echo "ERROR $*"; }
NODE_COUNT=5; CLUSTER_B_MGMT=http://stub; ENV_TYPE=docker
resolve_suite_dir() { echo "$1-dir"; }
run_suite() {  # records one passed suite, counts the calls
    echo "$1" >> "$RAN"
    echo "{\"suite\":\"$1\",\"status\":\"passed\",\"pass\":1,\"fail\":0,\"duration\":0}" >> "$RESULTS_FILE"
}
restore_cluster_baseline() { local n; n=$(wc -l < "$RAN" | tr -d ' '); [ "$(echo "$RESTORE_RCS" | cut -d, -f"$n")" = "0" ]; }
ready_core_count_http() { echo 5; }
cluster_leader_http() { echo "$LEADER"; }
print_timing_report() { :; }
_run_warning_lines() { echo 0; }
STUB
cat "${INTEG_DIR}/lib/json.sh"
extract "$RUNNER" run_cluster_b_suites
extract "$RUNNER" print_results
} > "$WORK/fns.sh"

run_case() {  # <label> <restore rcs, comma list per suite> <leader> <suites...>
    local label="$1" rcs="$2" leader="$3"; shift 3
    ( export RESTORE_RCS="$rcs" LEADER="$leader" RAN="$WORK/ran.$label" RESULTS_FILE="$WORK/results.$label" \
             TIMINGS_FILE="$WORK/timings.$label" RESULTS_JSON="$WORK/json.$label"
      : > "$RAN"; : > "$RESULTS_FILE"; : > "$TIMINGS_FILE"
      source "$WORK/fns.sh"; run_cluster_b_suites "$@" > "$WORK/run.$label" 2>&1; print_results "$RESULTS_FILE" > "$WORK/print.$label" 2>&1 )
    echo $? > "$WORK/rc.$label"
}

run_case x1 1 n1 02w
if [ "$(cat "$WORK/rc.x1")" = "1" ] && grep -q '"suite":"02w-restore-baseline","status":"failed"' "$WORK/results.x1"; then ok "X1 a restore that fails after the LAST suite fails the run, with a failed record naming it"
else fail "X1 rc=$(cat "$WORK/rc.x1") results=$(cat "$WORK/results.x1")"; fi

run_case x2 0 n1 02w
if [ "$(cat "$WORK/rc.x2")" = "0" ] && ! grep -q 'restore-baseline' "$WORK/results.x2"; then ok "X2 a restore that succeeds leaves the run passing (positive control)"
else fail "X2 rc=$(cat "$WORK/rc.x2") results=$(cat "$WORK/results.x2")"; fi

run_case x3 1,0 n1 02w 03s
if [ "$(cat "$WORK/rc.x3")" = "1" ] && [ "$(wc -l < "$WORK/ran.x3" | tr -d ' ')" = "1" ] && grep -q '"suite":"03s-dir","status":"skipped-unrecoverable"' "$WORK/results.x3" && grep -q '02w-restore-baseline' "$WORK/results.x3"; then ok "X3 a restore failing after the first of two suites quarantines the second AND fails the run on its own record"
else fail "X3 rc=$(cat "$WORK/rc.x3") ran=$(cat "$WORK/ran.x3") results=$(cat "$WORK/results.x3")"; fi

run_case x4 0 "" 02w
if [ "$(cat "$WORK/rc.x4")" = "1" ] && grep -q '02w-restore-baseline' "$WORK/results.x4"; then ok "X4 a clean restore with no committed leader still fails the run"
else fail "X4 rc=$(cat "$WORK/rc.x4") results=$(cat "$WORK/results.x4")"; fi

run_case x5 0,0 n1 02w 03s
if [ "$(cat "$WORK/rc.x5")" = "0" ] && [ "$(wc -l < "$WORK/ran.x5" | tr -d ' ')" = "2" ]; then ok "X5 two suites with clean restores both run and the run passes"
else fail "X5 rc=$(cat "$WORK/rc.x5") ran=$(cat "$WORK/ran.x5")"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
