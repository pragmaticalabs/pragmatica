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
#   T8  the cloud reap, by behaviour: node logs are read before the (stub) reaper runs.
#   T9  the CLOUD branch from an env -i child holding only what run_suite exports writes a log per VM.
#   T10 FAILCAP_MAX_FIRST_FAIL bounds first-fail captures per suite, with one WARN.
#   T11 a capture that ends with no node logs is a WARN, not an INFO.
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
      _run_with_timeout() { shift; "$@"; }   # `timeout` cannot run the stub docker function
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
# The cloud reap by BEHAVIOUR: the real _cloud_full_drain_recover with a stub reaper (AETHER_CLOUD_REAPER) and a
# stub `aether`; the real capture_before_destructive runs against the stub docker. The trace must read
# "docker-logs ... reap": a moved, renamed or deleted hook changes what the function DOES, which a source-line
# comparison would not see.
cat > "${WORK}/reaper.sh" <<'REAPER'
#!/bin/bash
echo "reap" >> "$EV"
exit 0
REAPER
chmod +x "${WORK}/reaper.sh"; touch "${WORK}/b.toml"
body_reap() {
    export SUITE_FAILCAP_DIR; SUITE_FAILCAP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/failcap-stub.XXXXXX")
    : > "${SUITE_FAILCAP_DIR}/suite-has-fail"      # an earlier test in this suite failed
    TEST_TAG=s20_recover; TEST_FAIL_COUNT=0
    aether() { echo "bootstrap" >> "$EV"; return 1; }
    _cloud_full_drain_recover > /dev/null
    rm -rf "$SUITE_FAILCAP_DIR"
}
scenario reap body_reap BOOTSTRAP_CLUSTER_NAME=test-b CLUSTER_ID=b CLOUD_TOML_B="${WORK}/b.toml" AETHER_CLOUD_REAPER="${WORK}/reaper.sh"
first_logs=$(grep -n '^docker-logs' "${WORK}/reap/events" | head -1 | cut -d: -f1)
first_reap=$(grep -n '^reap$' "${WORK}/reap/events" | head -1 | cut -d: -f1)
[ -n "$first_logs" ] && [ -n "$first_reap" ] && [ "$first_logs" -lt "$first_reap" ] \
    && ok "T8 cloud reap by behaviour: node logs read (event ${first_logs}) before the reaper ran (event ${first_reap})" \
    || fail "T8 trace: first-logs=[${first_logs:-none}] first-reap=[${first_reap:-none}] events=$(tr '\n' ' ' < "${WORK}/reap/events")"

# T9 ---------------------------------------------------------------------------------------------------------------
# The CLOUD branch from a test process: an `env -i` child holding ONLY what run_suite exports (plus the ambient
# operator inputs), under set -euo pipefail. The run-tests.sh-local CLUSTER_A_NAME/CLUSTER_B_NAME are NOT there; the
# first version of the hook read them and died with "unbound variable", leaving a manifest and no logs.
# Positive control: run_suite really exports BOOTSTRAP_CLUSTER_NAME (else this env is not what run_suite hands down).
if grep -q 'export BOOTSTRAP_CLUSTER_NAME="\$CLUSTER_B_NAME"' "${INTEG_DIR}/run-tests.sh"; then
    ok "T9a control: run-tests.sh exports BOOTSTRAP_CLUSTER_NAME per suite"
else fail "T9a run-tests.sh no longer exports BOOTSTRAP_CLUSTER_NAME"; fi
mkdir -p "${WORK}/cloud"; : > "${WORK}/cloud/events"
env -i PATH="$PATH" HOME="$WORK" TARGET_HOST=localhost CLOUD_MODE=true CLOUD_RUNTIME=container CLUSTER_ID=b \
    BOOTSTRAP_CLUSTER_NAME=test-b AETHER_SSH_KEY=/dev/null SUITE_TAG=02-chaos SUITE_START_EPOCH=1700000000 \
    SUITE_FAILCAP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/failcap-stub.XXXXXX")" AETHER_FAILURE_LOGS_DIR="${WORK}/cloud/failure-logs" \
    INTEG_DIR="$INTEG_DIR" bash -c '
        set -euo pipefail
        source "$INTEG_DIR/lib/common.sh" > /dev/null 2>&1; source "$INTEG_DIR/lib/cluster.sh" > /dev/null 2>&1
        _run_with_timeout() { shift; "$@"; }
        ssh() { echo "node-log ${*: -1}"; }
        _cloud_running_vm_ips() { [ "$1" = test-b ] && printf "1.1.1.1\n2.2.2.2\n"; }
        provisioning_snapshot() { echo "{}"; }
        TEST_TAG=kill_under_load
        log_fail "boom" > /dev/null' > "${WORK}/cloud/out" 2>&1
d=$(ls -d "${WORK}"/cloud/failure-logs/02-chaos/kill_under_load/*-first-fail 2>/dev/null | head -1)
if [ -n "$d" ] && [ -s "$d/vm-1.1.1.1.log" ] && [ -s "$d/vm-2.2.2.2.log" ] && grep -q 'captured 2 of 2' "$d/capture-manifest.txt" \
   && ! grep -q 'unbound variable' "${WORK}/cloud/out"; then
    ok "T9 cloud branch in an env -i test process: a log per VM, 'captured 2 of 2', no unbound variable"
else fail "T9 cloud capture: dir=[${d:-none}] out=$(tr '\n' '|' < "${WORK}/cloud/out" | head -c 300)"; fi

# T10 --------------------------------------------------------------------------------------------------------------
body_first_cap() {
    export SUITE_FAILCAP_DIR; SUITE_FAILCAP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/failcap-stub.XXXXXX")
    for t in t1 t2 t3; do ( TEST_TAG=$t; log_fail "fails" > /dev/null ); done
    ls -d "${SUITE_FAILCAP_DIR}"/first-fail-* 2>/dev/null | wc -l | tr -d ' ' > "${EV}.ff"
    rm -rf "$SUITE_FAILCAP_DIR"
}
scenario firstcap body_first_cap FAILCAP_MAX_FIRST_FAIL=2
if [ "$(cat "${WORK}/firstcap/events.ff")" = "2" ] && [ "$(count_events firstcap docker-logs)" -eq 4 ] \
   && [ "$(grep -c 'cap of 2 first-fail' "${WORK}/firstcap/out")" -eq 1 ]; then
    ok "T10 FAILCAP_MAX_FIRST_FAIL=2: two captures for three failing tests, one WARN"
else fail "T10 ff=$(cat "${WORK}/firstcap/events.ff") reads=$(count_events firstcap docker-logs) warns=$(grep -c 'cap of 2 first-fail' "${WORK}/firstcap/out")"; fi

# T11 --------------------------------------------------------------------------------------------------------------
body_empty() {
    export SUITE_FAILCAP_DIR; SUITE_FAILCAP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/failcap-stub.XXXXXX")
    docker() { case "$1" in ps) ;; esac; }      # no containers: the capture ends with a manifest and no logs
    TEST_TAG=t; log_fail "fails" > /dev/null
    rm -rf "$SUITE_FAILCAP_DIR"
}
scenario empty body_empty
grep -q 'produced NO node logs' "${WORK}/empty/out" && ! grep -q 'fail-time capture (first-fail) ->' "${WORK}/empty/out" \
    && ok "T11 a capture with no node logs is a WARN naming the directory, not an INFO capture line" \
    || fail "T11 empty capture: $(tr '\n' '|' < "${WORK}/empty/out" | head -c 300)"

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
