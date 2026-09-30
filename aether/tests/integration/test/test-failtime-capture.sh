#!/bin/bash
# test-failtime-capture.sh — pins lib/capture.sh's fail-time evidence capture with stubs only (a stub
# `docker`, a stub `remote_exec`; no cluster, no ssh, no cloud). Why it exists: on 2026-09-30 a 02-chaos test
# failed and a LATER destructive step recreated every container before the suite-end capture ran, so no log
# from the failing window survived.
#   T1  the first [FAIL] of a test captures every node's log into failure-logs/<suite>/<test>/<stamp>-first-fail/.
#   T2  a second [FAIL] in the same test captures nothing more (once per failing test, not per assertion).
#   T3  log_fail's verdict is unchanged: the latch counts both, the [FAIL] file gains only those lines, and
#       stdout carries only the [FAIL] line, nothing from the capture (log_fail runs inside $( ... )).
#   T4  ORDER: a FAIL, then restart_all_nodes (its compose recreate stubbed): the outgoing containers' logs are
#       read BEFORE the recreate, from a before-restart_all_nodes directory, in a different test process.
#   T5  control: restart_all_nodes with no [FAIL] recorded in the suite captures nothing.
#   T6  control: without SUITE_FAILCAP_DIR (any stub suite, a standalone run) log_fail never touches docker.
#   T7  the pre-destructive cap holds: FAILCAP_MAX_PRE_DESTRUCTIVE=1 lets the second restart_all_nodes through
#       uncaptured, with a WARN.
#   INTEG_DIR_UNDER_TEST=<path> selects another copy of aether/tests/integration (used for the mutation probes).
#   bash aether/tests/integration/test/test-failtime-capture.sh
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="${INTEG_DIR_UNDER_TEST:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
touch "${WORK}/compose.yml"

# scenario <name> <body-function-name> [ENV=VAL...] — runs the body in a fresh bash with the real lib/common.sh and
# lib/cluster.sh sourced and docker/remote_exec stubbed. Events land in ${WORK}/<name>/events in call order.
scenario() {
    local name="$1" body="$2"; shift 2
    local d="${WORK}/${name}"; mkdir -p "$d"
    : > "${d}/events"
    ( export TARGET_HOST=localhost ENV_TYPE=docker CLOUD_MODE=false EV="${d}/events" \
        AETHER_FAILURE_LOGS_DIR="${d}/failure-logs" SUITE_TAG=02-chaos CLUSTER_ID=a \
        CLUSTER_NAME=aether-b-node- COMPOSE_FILE="${WORK}/compose.yml" "$@"
      source "${INTEG_DIR}/lib/common.sh" > /dev/null 2>&1 || echo "SOURCE-FAILED common.sh" >> "$EV"
      source "${INTEG_DIR}/lib/cluster.sh" > /dev/null 2>&1 || echo "SOURCE-FAILED cluster.sh" >> "$EV"
      docker() {
          case "$1" in
              ps) printf 'aether-a-node-1\naether-a-node-2\n' ;;
              logs) echo "docker-logs $3" >> "$EV"; echo "log line of $3" ;;
          esac
      }
      remote_exec() { case "$1" in *"compose -f docker-compose-b.yml down"*) echo "recreate" >> "$EV" ;; esac; return 1; }
      $body ) > "${d}/out" 2>&1
}
caps() { ls -d "${WORK}/$1/failure-logs/02-chaos/$2"/* 2>/dev/null; }   # scenario test-tag -> capture dirs
count_events() { grep -c "^$2" "${WORK}/$1/events" 2>/dev/null || true; }

# T1-T3 ------------------------------------------------------------------------------------------------------------
body_first_fail() {
    export SUITE_FAILCAP_DIR; SUITE_FAILCAP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/failcap-stub.XXXXXX")
    TEST_TAG=kill_under_load; TEST_FAIL_COUNT=0
    fail_file_before=$(_harness_fail_lines)
    log_fail "first assertion" > "${EV}.out1"
    log_fail "second assertion" > "${EV}.out2"
    echo "latch=${TEST_FAIL_COUNT} fail-lines=$(( $(_harness_fail_lines) - fail_file_before )) stdout-lines=$(wc -l < "${EV}.out1" | tr -d ' ')+$(wc -l < "${EV}.out2" | tr -d ' ')" > "${EV}.verdict"
    # log_fail inside $( ... ) (a fresh test, so its capture runs inside the substitution): stdout is the [FAIL] line only.
    TEST_TAG=other_test; stdout=$(log_fail "inside a command substitution")
    echo "subst-stdout-lines=$(printf '%s\n' "$stdout" | wc -l | tr -d ' ')" >> "${EV}.verdict"
    rm -rf "$SUITE_FAILCAP_DIR"
}
scenario first_fail body_first_fail
d=$(caps first_fail kill_under_load)
if [ "$(printf '%s\n' "$d" | grep -c .)" -eq 1 ] && printf '%s' "$d" | grep -Eq -- '/[0-9]{8}T[0-9]{6}Z-first-fail$' \
   && [ -s "$d/aether-a-node-1.log" ] && [ -s "$d/aether-a-node-2.log" ]; then
    ok "T1 first [FAIL]: one timestamped first-fail directory, a log per node"
else fail "T1 first [FAIL]: dirs=[$(printf '%s' "$d" | tr '\n' ' ')] out=$(tr '\n' '|' < "${WORK}/first_fail/out" | head -c 300)"; fi
n=$(count_events first_fail docker-logs)  # kill_under_load: 1 capture x 2 nodes; other_test: 1 capture x 2 nodes
[ "$n" -eq 4 ] && ok "T2 two [FAIL]s in one test: 2 captures in all (kill_under_load once, other_test once) = 4 reads, not 6" || fail "T2 docker-logs reads: $n"
v=$(tr '\n' ' ' < "${WORK}/first_fail/events.verdict" 2>/dev/null | sed 's/ $//')
[ "$v" = "latch=2 fail-lines=2 stdout-lines=1+1 subst-stdout-lines=1" ] && ok "T3 verdict untouched: ${v}" || fail "T3 verdict: '${v}'"

# T4-T5, T7 --------------------------------------------------------------------------------------------------------
body_fail_then_recreate() {
    export SUITE_FAILCAP_DIR; SUITE_FAILCAP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/failcap-stub.XXXXXX")
    ( TEST_TAG=kill_under_load; log_fail "the failing test" > /dev/null )      # one test process fails ...
    ( TEST_TAG=s20_recover; TEST_FAIL_COUNT=0; restart_all_nodes > /dev/null )  # ... a later one recreates
    ( TEST_TAG=s20_again;   TEST_FAIL_COUNT=0; restart_all_nodes > /dev/null )
    ls -d "${SUITE_FAILCAP_DIR}"/pre-* 2>/dev/null | wc -l | tr -d ' ' > "${EV}.pre"
    rm -rf "$SUITE_FAILCAP_DIR"
}
scenario recreate body_fail_then_recreate
pre=$(caps recreate s20_recover | grep 'before-restart_all_nodes$' | head -1)
first_recreate=$(grep -n '^recreate' "${WORK}/recreate/events" | head -1 | cut -d: -f1)
# 2 reads for kill_under_load's first-fail + 2 for the pre-destructive capture, all before the first recreate.
reads_before=$(awk -v n="${first_recreate:-0}" 'NR < n' "${WORK}/recreate/events" | grep -c '^docker-logs')
if [ -n "$pre" ] && [ -s "$pre/aether-a-node-1.log" ] && [ -n "$first_recreate" ] && [ "$reads_before" -ge 4 ]; then
    ok "T4 fail, then restart_all_nodes: before-restart_all_nodes captured; ${reads_before} node-log reads precede the first recreate (event line ${first_recreate})"
else fail "T4 pre=[$pre] first_recreate=[${first_recreate:-none}] reads_before=${reads_before}"; fi
stamp_pre=$(basename "$pre" | cut -c1-16); stamp_ff=$(basename "$(caps recreate kill_under_load | head -1)" | cut -c1-16)
[ -n "$stamp_ff" ] && [[ "$stamp_ff" < "$stamp_pre" || "$stamp_ff" == "$stamp_pre" ]] \
    && ok "T4b the failing test's capture stamp (${stamp_ff}) does not postdate the pre-recreate stamp (${stamp_pre})" \
    || fail "T4b stamps: first-fail=[${stamp_ff}] pre=[${stamp_pre}]"

body_recreate_no_fail() {
    export SUITE_FAILCAP_DIR; SUITE_FAILCAP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/failcap-stub.XXXXXX")
    TEST_TAG=s20_recover; TEST_FAIL_COUNT=0
    restart_all_nodes > /dev/null
    ls -d "${SUITE_FAILCAP_DIR}"/pre-* 2>/dev/null | wc -l | tr -d ' ' > "${EV}.pre"
    rm -rf "$SUITE_FAILCAP_DIR"
}
scenario nofail body_recreate_no_fail
# restart_all_nodes logs its own [FAIL] when the stubbed recreate fails, but only AFTER the recreate, so the
# pre-destructive decision was taken with no FAIL recorded. Reads before the first recreate must be zero.
first_recreate=$(grep -n '^recreate' "${WORK}/nofail/events" | head -1 | cut -d: -f1)
reads_before=$(awk -v n="${first_recreate:-0}" 'NR < n' "${WORK}/nofail/events" | grep -c '^docker-logs')
[ -n "$first_recreate" ] && [ "$reads_before" -eq 0 ] && [ "$(cat "${WORK}/nofail/events.pre")" = "0" ] \
    && ok "T5 control: no [FAIL] in the suite, so nothing is captured before the recreate" \
    || fail "T5 first_recreate=[${first_recreate:-none}] reads_before=${reads_before} pre=$(cat "${WORK}/nofail/events.pre" 2>/dev/null)"

body_cap() {
    export SUITE_FAILCAP_DIR; SUITE_FAILCAP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/failcap-stub.XXXXXX")
    ( TEST_TAG=a; log_fail "failing" > /dev/null )
    ( TEST_TAG=b; restart_all_nodes > /dev/null )
    ( TEST_TAG=c; restart_all_nodes > /dev/null )
    ls -d "${SUITE_FAILCAP_DIR}"/pre-* 2>/dev/null | wc -l | tr -d ' ' > "${EV}.pre"
    rm -rf "$SUITE_FAILCAP_DIR"
}
scenario cap body_cap FAILCAP_MAX_PRE_DESTRUCTIVE=1
if [ "$(cat "${WORK}/cap/events.pre")" = "1" ] && grep -q 'cap of 1 pre-destructive' "${WORK}/cap/out"; then
    ok "T7 FAILCAP_MAX_PRE_DESTRUCTIVE=1: one pre-destructive capture, the second is skipped with a WARN"
else fail "T7 pre=$(cat "${WORK}/cap/events.pre" 2>/dev/null) out=$(grep -c 'cap of' "${WORK}/cap/out")"; fi

# T6 ---------------------------------------------------------------------------------------------------------------
body_unarmed() { TEST_TAG=t; log_fail "not under run-tests.sh" > /dev/null; restart_all_nodes > /dev/null; }
scenario unarmed body_unarmed
[ "$(count_events unarmed docker-logs)" -eq 0 ] && [ ! -d "${WORK}/unarmed/failure-logs" ] \
    && ok "T6 control: no SUITE_FAILCAP_DIR, so log_fail and restart_all_nodes never read docker or write failure-logs" \
    || fail "T6 unarmed run captured: $(count_events unarmed docker-logs) reads"

# T8 ---------------------------------------------------------------------------------------------------------------
# The cloud reap cannot run under a stub (it shells out to tools/cloud-reaper.sh and `aether cluster bootstrap`), so
# its call site is pinned by ORDER in the shipped text: inside _cloud_full_drain_recover the capture precedes the
# reaper invocation. Positive control: both anchors must be found, or this examined nothing.
fn=$(awk '/^_cloud_full_drain_recover\(\) \{/,/^\}/' "${INTEG_DIR}/lib/cluster.sh")
cap_line=$(printf '%s\n' "$fn" | grep -n '^    capture_before_destructive "cloud-reap"$' | head -1 | cut -d: -f1)
reap_line=$(printf '%s\n' "$fn" | grep -n 'reap_out=\$("\$reaper"' | head -1 | cut -d: -f1)
[ -n "$cap_line" ] && [ -n "$reap_line" ] && [ "$cap_line" -lt "$reap_line" ] \
    && ok "T8 _cloud_full_drain_recover: capture_before_destructive (line ${cap_line}) precedes the reaper call (line ${reap_line})" \
    || fail "T8 cloud reap order: capture=[${cap_line:-missing}] reap=[${reap_line:-missing}]"

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
