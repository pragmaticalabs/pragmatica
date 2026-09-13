#!/bin/bash
# test-chaos-harness.sh — manual-run stub tests for the 02-chaos harness paths #1051 changed:
# full self-drain confirmation and the cloud S20 recovery path, the honest core-count reader,
# wait_for's value reader, the S19 halt-reason reads and step disposition, and the H4
# CAUGHT_UP wait.
#
# No external test runner; invoke directly:
#   bash aether/tests/integration/test/test-chaos-harness.sh
#
# Each case runs the REAL functions in a fresh subshell that sources lib/ or the suite under
# test (the suites' source guard stops before their scenario). Stubbed are commands outside
# the harness — ssh, hcloud, timeout/gtimeout, sleep (virtual time: it advances SECONDS),
# curl — and the mgmt transport api_get. Spies stand in for collaborators a stub run cannot
# execute, and only where a case says so: _cloud_full_drain_recover (reaps and bootstraps
# VMs), restart_all_nodes, and the four constituents of _cloud_recovery_barriers (leader,
# quiescence, readiness, echo redeploy). Every negative case asserts a failure MESSAGE or a
# recorded side effect, so a missing function (rc 127) cannot satisfy it.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SELF_DRAIN_SUITE="${INTEG_DIR}/suites/02-chaos/test-self-drain-quorum-loss.sh"
FAILOVER_SUITE="${INTEG_DIR}/suites/02-chaos/test-stream-replica-failover.sh"
CLUSTER_LIB="${INTEG_DIR}/lib/cluster.sh"

# ---------------------------------------------------------------------------
# Run guards (#1051 round 3). A regression that makes a sourced suite run its live
# scenario must fail fast and NAMED, never hang or fork without bound: a mutation
# driver that killed only the top PID once let such trees grow to 2,342 processes.
#   1. no re-entry: a harness started from inside a running harness refuses;
#   2. own process group (perl setpgrp) unless already a group leader, so the
#      deadline can kill every descendant, subshell trees included;
#   3. a harness-wide deadline (CHAOS_HARNESS_DEADLINE_S, default 480s): a named FAIL,
#      then the whole group is killed;
#   4. a precondition, checked by text before anything is sourced: every suite this
#      harness sources carries its source guard.
# test/test-harness-guards.sh pins 1, 3 and 4 with process-group probes.
# ---------------------------------------------------------------------------
_harness_pgid_of() { perl -e 'print getpgrp(shift)' "$1" 2>/dev/null; }
if [ -n "${CHAOS_HARNESS_ACTIVE:-}" ]; then
    echo "  FAIL  harness guard: re-entered from a running harness (pid ${CHAOS_HARNESS_ACTIVE}) — refusing to recurse"
    exit 2
fi
if [ -z "${CHAOS_HARNESS_REGROUPED:-}" ] && command -v perl >/dev/null 2>&1 \
    && [ "$(_harness_pgid_of $$)" != "$$" ]; then
    export CHAOS_HARNESS_REGROUPED=1
    exec perl -e 'setpgrp(0, 0) or die "setpgrp: $!"; exec @ARGV or die "exec: $!"' bash "$0" "$@"
fi
# Any harness started below this one (reachable only if guard 1 fails) must stay in THIS
# process group, never regroup, so a group kill or a process-count ceiling reaches it.
export CHAOS_HARNESS_REGROUPED=1
export CHAOS_HARNESS_ACTIVE=$$
HARNESS_PGID=$(_harness_pgid_of $$)
CHAOS_HARNESS_DEADLINE_S="${CHAOS_HARNESS_DEADLINE_S:-480}"
(
    trap - EXIT
    watchdog_deadline=$((SECONDS + CHAOS_HARNESS_DEADLINE_S))
    while [ "$SECONDS" -lt "$watchdog_deadline" ]; do
        command sleep 1
        kill -0 "$CHAOS_HARNESS_ACTIVE" 2>/dev/null || exit 0
    done
    echo "  FAIL  harness guard: deadline of ${CHAOS_HARNESS_DEADLINE_S}s exceeded — killing process group ${HARNESS_PGID:-<unknown>}"
    if [ -n "$HARNESS_PGID" ] && [ "$HARNESS_PGID" = "$CHAOS_HARNESS_ACTIVE" ]; then
        kill -KILL -- "-${HARNESS_PGID}" 2>/dev/null
    else
        kill -KILL "$CHAOS_HARNESS_ACTIVE" 2>/dev/null
    fi
) &
HARNESS_WATCHDOG=$!
# Not a job of this shell: its kill at exit must not print a "Terminated" notice.
disown "$HARNESS_WATCHDOG" 2>/dev/null || true
for guarded_suite in "$SELF_DRAIN_SUITE" "$FAILOVER_SUITE"; do
    if ! awk 'prev ~ /^if \[ "\$\{BASH_SOURCE\[0\]\}" != "\$0" \]; then$/ && $0 ~ /^    return 0$/ { found = 1 } { prev = $0 } END { exit !found }' "$guarded_suite"; then
        echo "  FAIL  harness guard: ${guarded_suite#"${INTEG_DIR}"/} has no source guard (if [ \"\${BASH_SOURCE[0]}\" != \"\$0\" ]; then / return 0) — sourcing it would run its live scenario; no case was run"
        echo ""
        echo "  ----"
        echo "  passed: 0"
        echo "  failed: 1"
        kill "$HARNESS_WATCHDOG" 2>/dev/null
        exit 1
    fi
done

export TARGET_HOST="chaos-harness-test"
export CLOUD_SOURCE_NAME="hetzner-eu"
export AETHER_SSH_KEY="/nonexistent/chaos-harness-key"

WORK=$(mktemp -d)
TEST_CLUSTER="chaos-harness-test-$$"
STATE_DIR="${HOME}/.aether/clusters/${TEST_CLUSTER}"
mkdir -p "$STATE_DIR"
cp "${SCRIPT_DIR}/fixtures/bootstrap-state.json" "${STATE_DIR}/bootstrap-state.json"
trap 'kill "$HARNESS_WATCHDOG" 2>/dev/null; rm -rf "$WORK" "$STATE_DIR"' EXIT
export BOOTSTRAP_CLUSTER_NAME="$TEST_CLUSTER"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

# Probe hooks for test/test-harness-guards.sh only.
case "${CHAOS_HARNESS_SELFTEST:-}" in
    guards-only)
        echo "  harness guards ok"
        exit 0
        ;;
    reenter)
        bash "$0"
        exit $?
        ;;
    nested-pgid)
        echo "  outer harness pgid $(_harness_pgid_of $$)"
        env -u CHAOS_HARNESS_ACTIVE CHAOS_HARNESS_SELFTEST=print-pgid bash "$0"
        exit $?
        ;;
    print-pgid)
        echo "  inner harness pgid $(_harness_pgid_of $$)"
        exit 0
        ;;
    hang)
        ( while :; do command sleep 1; done ) &
        while :; do command sleep 1; done
        ;;
esac

# ---------------------------------------------------------------------------
# Stubs (installed inside each case subshell, after the sources)
# ---------------------------------------------------------------------------

# ssh: consumes stdin like the real client (so a caller iterating a list on stdin must
# redirect it). The response to the n-th call for an IP is $STUB_DIR/ssh-<ip>.<n>, else
# $STUB_DIR/ssh-<ip>: first line the rc, the rest stdout. No file: rc 255, unreachable.
stub_ssh() {
    local arg target="" cmd="${*: -1}" n f
    for arg in "$@"; do
        case "$arg" in *@*) target="${arg#*@}" ;; esac
    done
    cat > /dev/null
    printf '%s|%s\n' "$target" "$cmd" >> "$STUB_DIR/ssh-calls"
    n=$(awk -F'|' -v t="$target" '$1 == t' "$STUB_DIR/ssh-calls" | wc -l | tr -d ' ')
    f="$STUB_DIR/ssh-${target}.${n}"
    [ -f "$f" ] || f="$STUB_DIR/ssh-${target}"
    if [ ! -f "$f" ]; then
        echo "ssh: connect to host ${target} port 22: Connection timed out" >&2
        return 255
    fi
    tail -n +2 "$f"
    return "$(head -1 "$f")"
}

# hcloud: `server list` picks its file by selector — `-l aether-cluster=` (the drain
# confirmation) reads hcloud-list, `-l aether-node-id` (_cloud_running_vm_ips) reads
# hcloud-nodeid-list, anything else hcloud-all-list — using <file>.<n> for the n-th call
# of that kind when present; hcloud-list-rc fails every list. `server describe <id>`:
# gone-<id> -> hcloud's exact deleted-server response (`hcloud: Server not found: <id>`,
# rc 1); describe-out-<id> -> that text on stderr, rc 1; describe-rc -> a network error;
# else the server is found.
stub_hcloud() {
    local n base
    printf '%s\n' "$*" >> "$STUB_DIR/hcloud-calls"
    case "${1:-} ${2:-}" in
        "server list")
            [ -f "$STUB_DIR/hcloud-list-rc" ] && return "$(cat "$STUB_DIR/hcloud-list-rc")"
            case "$*" in
                *"aether-cluster="*) base="hcloud-list" ;;
                *"aether-node-id"*)  base="hcloud-nodeid-list" ;;
                *)                   base="hcloud-all-list" ;;
            esac
            echo x >> "$STUB_DIR/${base}-calls"
            n=$(grep -c . "$STUB_DIR/${base}-calls")
            if [ -f "$STUB_DIR/${base}.${n}" ]; then
                cat "$STUB_DIR/${base}.${n}"
            else
                cat "$STUB_DIR/${base}" 2>/dev/null
            fi
            return 0
            ;;
        "server describe")
            if [ -f "$STUB_DIR/gone-${3:-}" ]; then
                echo "hcloud: Server not found: ${3:-}" >&2
                return 1
            fi
            if [ -f "$STUB_DIR/describe-out-${3:-}" ]; then
                cat "$STUB_DIR/describe-out-${3:-}" >&2
                return 1
            fi
            if [ -f "$STUB_DIR/describe-rc" ]; then
                echo "hcloud: request failed: connection reset" >&2
                return 1
            fi
            echo "ID: ${3:-}"
            return 0
            ;;
    esac
    return 1
}

# api_get: the response to the n-th call for a path is $STUB_DIR/api<path, / -> _>.<n>, else
# the file without the suffix: first line the rc, the rest the body. $STUB_DIR/api<key>.delay
# holds REAL seconds to wait before answering that path. No file: rc 1, empty (unreachable).
stub_api_get() {
    local key n f
    key=$(printf '%s' "$1" | tr '/' '_')
    printf '%s\n' "$1" >> "$STUB_DIR/api-calls"
    [ -f "$STUB_DIR/api${key}.delay" ] && command sleep "$(cat "$STUB_DIR/api${key}.delay")"
    n=$(grep -cxF -- "$1" "$STUB_DIR/api-calls")
    f="$STUB_DIR/api${key}.${n}"
    [ -f "$f" ] || f="$STUB_DIR/api${key}"
    [ -f "$f" ] || return 1
    tail -n +2 "$f"
    return "$(head -1 "$f")"
}

install_stubs() {
    timeout()  { shift; "$@"; }
    gtimeout() { shift; "$@"; }
    sleep()    { SECONDS=$((SECONDS + ${1%%.*})); }
    # The pinned endpoint answers /health/live, so wait_for's cloud endpoint refresh takes its
    # happy path; every other curl target is unreachable.
    curl()     { case "$*" in *"/health/live"*) return 0 ;; esac; return 7; }
    hcloud()   { stub_hcloud "$@"; }
    ssh()      { stub_ssh "$@"; }
    api_get()  { stub_api_get "$@"; }
}

# run_in <case_dir> <file to source> <command...>: runs the command in a subshell with the
# file sourced, the stubs installed, then <case_dir>/setup.sh sourced (per-case overrides and
# spies). Output and a final "RC=<rc>" line land in <case_dir>/out. ENV_TYPE comes from
# <case_dir>/env (default cloud).
run_in() {
    local dir="$1" src="$2"
    shift 2
    (
        export STUB_DIR="$dir"
        export ENV_TYPE
        ENV_TYPE=$(cat "$dir/env" 2>/dev/null || echo cloud)
        unset CLOUD_MODE
        export TIMEOUT_SCALE=1
        # shellcheck disable=SC1090
        source "$src"
        set +e
        install_stubs
        if [ -f "$dir/setup.sh" ]; then
            # shellcheck disable=SC1090,SC1091
            source "$dir/setup.sh"
        fi
        "$@"
        echo "RC=$?"
    ) < /dev/null > "$dir/out" 2>&1
}

new_case() { mktemp -d "${WORK}/case.XXXXXX"; }
# resp <file> <rc> <body>
resp() { { echo "$2"; printf '%b' "$3"; } > "$1"; }
rc_of() { sed -n 's/^RC=//p' "$1/out" | tail -1; }
has() { grep -qF -- "$2" "$1/out"; }
has_re() { grep -qE -- "$2" "$1/out"; }
lines_of() { if [ -f "$1" ]; then grep -c . "$1"; else echo 0; fi; }
show() { sed 's/^/        | /' "$1/out" | tail -n 25; }
check() { # check <case_dir> <description> <condition...>
    local dir="$1" desc="$2"
    shift 2
    # A case whose path hit a missing function or command cannot tell a correct refusal
    # from a deleted helper (rc 127 falls into the same failure text): never a PASS.
    # D5's deliberately typo'd predicate is the one exemption.
    if awk '/: command not found/ && !/no_such_helper_typo/ { found = 1 } END { exit !found }' "$dir/out"; then
        fail "$desc [a function or command on the case's path does not exist]"
        show "$dir"
        return
    fi
    if "$@"; then ok "$desc"; else fail "$desc"; show "$dir"; fi
}

VM_A="198.51.100.1"
VM_B="198.51.100.2"
LIST_AB="101 aether-cloud-x-node-a running ${VM_A}
102 aether-cloud-x-node-b running ${VM_B}"
UNIT_FAILED_2='LoadState=loaded\nActiveState=failed\nExecMainStatus=2\n'
UNIT_INACTIVE_0='LoadState=loaded\nActiveState=inactive\nExecMainStatus=0\n'
UNIT_INACTIVE_2='LoadState=loaded\nActiveState=inactive\nExecMainStatus=2\n'
UNIT_ACTIVE_0='LoadState=loaded\nActiveState=active\nExecMainStatus=0\n'
UNIT_NOT_LOADED='LoadState=not-found\nActiveState=inactive\nExecMainStatus=0\n'
TOPO_5='{"coreCount":5,"coreNodes":["n1","n2","n3","n4","n5"]}'
TOPO_3='{"coreCount":3,"coreNodes":["n1","n2","n3"]}'
TOPO_0='{"coreCount":0,"coreNodes":[]}'
TOPO_PATH_KEY="api_api_v1_cluster_topology"

# A two-VM cluster listing with per-VM ssh responses: drain_vms <dir> <resp-a> <resp-b>, each
# "<rc>|<body>" or "none" (no response file: unreachable).
drain_vms() {
    local dir="$1"
    printf '%s\n' "$LIST_AB" > "$dir/hcloud-list"
    [ "$2" != "none" ] && resp "$dir/ssh-${VM_A}" "${2%%|*}" "${2#*|}"
    [ "$3" != "none" ] && resp "$dir/ssh-${VM_B}" "${3%%|*}" "${3#*|}"
    return 0
}

echo "== A. full self-drain confirmation (_cloud_await_full_drain, lib/cluster.sh)"

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_INACTIVE_0}"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A1 jvm: one unit halted (failed/2), one stopped (loaded, inactive/0) -> confirmed, statuses recorded" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "confirmed on every VM" && has "$d" "halted aether-cloud-x-node-a (id 101, ${VM_A}): unit LoadState=loaded ActiveState=failed ExecMainStatus=2" && has "$d" "stopped aether-cloud-x-node-b (id 102, ${VM_B}): unit LoadState=loaded ActiveState=inactive ExecMainStatus=0"'
check "$d" "A1b both VMs probed although the probe reads stdin (the VM list is not consumed by ssh)" \
    eval '[ "$(lines_of "$d/ssh-calls")" = 2 ] && grep -qF "${VM_B}|systemctl show aether-node --property=LoadState,ActiveState,ExecMainStatus" "$d/ssh-calls"'
check "$d" "A1c VM set is exactly the reap's --strict-cluster label" \
    grep -qxF "server list -l aether-cluster=${TEST_CLUSTER} -o columns=id,name,status,ipv4 -o noheader" "$d/hcloud-calls"

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" none
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A2 SSH error on one VM -> NOT confirmed, refusal names the unreadable VM" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "refusing to reap" && has "$d" "unreadable aether-cloud-x-node-b (id 102, ${VM_B}, hcloud status=running): ssh rc=255" && ! has "$d" "confirmed on every VM"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "124|"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A3 SSH timeout (rc 124) -> NOT confirmed, named as timed out" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "unreadable aether-cloud-x-node-b" && has "$d" "ssh rc=124" && has "$d" "timed out after 20s"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "0|Failed to connect to bus: No such file or directory\n"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A4 unparseable systemctl output -> NOT confirmed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "unreadable aether-cloud-x-node-b" && has "$d" "unparseable systemctl output: Failed to connect to bus"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "0|LoadState=loaded\nActiveState=failed\n"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A4b ActiveState without an ExecMainStatus -> NOT confirmed (exit status must be recorded)" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "unparseable systemctl output"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "0|ActiveState=failed\nExecMainStatus=2\n"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A4c no LoadState in the read -> NOT confirmed (whether the unit is loaded must be read)" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "unreadable aether-cloud-x-node-b" && has "$d" "unparseable systemctl output"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_ACTIVE_0}"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A5 a unit still active -> NOT confirmed, named alive" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "alive aether-cloud-x-node-b (id 102, ${VM_B}): unit LoadState=loaded ActiveState=active ExecMainStatus=0"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"; echo 1 > "$d/hcloud-list-rc"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A6 hcloud enumeration fails -> NOT confirmed, no VM probed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "hcloud enumeration of aether-cluster=${TEST_CLUSTER} failed or timed out" && [ "$(lines_of "$d/ssh-calls")" = 0 ]'

d=$(new_case); : > "$d/hcloud-list"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A7 empty enumeration -> NOT confirmed (not evidence of death)" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "lists ZERO VMs with aether-cluster=${TEST_CLUSTER}"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" none; : > "$d/gone-102"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A8 unreadable VM the provider reports not found -> gone, confirmed" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "gone aether-cloud-x-node-b (id 102, ${VM_B}): hcloud reports the server not found"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" none; : > "$d/describe-rc"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A8b describe failing for another reason is not 'gone' -> NOT confirmed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "unreadable aether-cloud-x-node-b" && ! has "$d" "gone aether-cloud-x-node-b"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"; resp "$d/ssh-${VM_B}.1" 255 ""
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 30
check "$d" "A9 unreadable on round 1, dead on round 2 -> keeps probing within the bound, confirmed" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "not yet confirmed (round 1" && has "$d" "confirmed on every VM (round 2"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" none
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 12
# Virtual time advances only on sleep, and the real seconds a loaded box spends per round add to
# it, so assert the shape (2-4 rounds, not the ~13 a 1s interval gives), not an exact count.
a10_rounds=$(sed -n 's/.*NOT confirmed after \([0-9]*\) round(s) in \([0-9]*\)s (bound 12s, checked between rounds).*/\1 \2/p' "$d/out")
check "$d" "A10 persistently unreadable -> re-probes at the 5s interval until the 12s bound (checked between rounds), then refuses naming the VM" \
    eval '[ "$(rc_of "$d")" = 1 ] && [ -n "$a10_rounds" ] && [ "${a10_rounds%% *}" -ge 2 ] && [ "${a10_rounds%% *}" -le 4 ] && [ "${a10_rounds##* }" -ge 12 ] && has "$d" "Blocking: unreadable aether-cloud-x-node-b (id 102, ${VM_B}, hcloud status=running)"'

d=$(new_case); drain_vms "$d" "0|exited|2\n" "0|dead|137\n"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" container 0
check "$d" "A11 container: exited/dead containers -> confirmed, via docker inspect Status|ExitCode" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "halted aether-cloud-x-node-a (id 101, ${VM_A}): container Status=exited ExitCode=2" && grep -qF "${VM_A}|docker inspect --format '"'"'{{.State.Status}}|{{.State.ExitCode}}'"'"' aether-node" "$d/ssh-calls"'

d=$(new_case); drain_vms "$d" "0|exited|2\n" "0|running|0\n"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" container 0
check "$d" "A11b container still running -> NOT confirmed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "alive aether-cloud-x-node-b (id 102, ${VM_B}): container Status=running ExitCode=0"'

d=$(new_case); drain_vms "$d" "0|exited|2\n" "1|Error: No such object: aether-node\n"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" container 0
check "$d" "A11c docker inspect error -> NOT confirmed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "unreadable aether-cloud-x-node-b" && has "$d" "ssh rc=1: Error: No such object"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" bogus 0
check "$d" "A12 unknown runtime -> NOT confirmed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "unknown runtime '"'"'bogus'"'"'"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "" jvm 0
check "$d" "A13 no cluster name -> refused before any enumeration" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "no cluster name" && [ "$(lines_of "$d/hcloud-calls")" = 0 ]'

echo "== A'. round 3: every widening of the reap gate goes red (SF1, SF2, N-f)"

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "0|LoadState=loaded\nActiveState=activating\nExecMainStatus=0\n"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A14 a unit still activating beside a halted one -> NOT confirmed (a starting node is not dead)" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "alive aether-cloud-x-node-b (id 102, ${VM_B}): unit LoadState=loaded ActiveState=activating ExecMainStatus=0" && ! has "$d" "confirmed on every VM"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "0|LoadState=loaded\nActiveState=deactivating\nExecMainStatus=0\n"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A15 a unit still deactivating beside a halted one -> NOT confirmed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "alive aether-cloud-x-node-b (id 102, ${VM_B}): unit LoadState=loaded ActiveState=deactivating ExecMainStatus=0" && ! has "$d" "confirmed on every VM"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "0|LoadState=loaded\nActiveState=refreshing\nExecMainStatus=0\n"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A16 an ActiveState the gate does not know (refreshing) -> unreadable, NOT confirmed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "unreadable aether-cloud-x-node-b" && has "$d" "unparseable systemctl output: LoadState=loaded ActiveState=refreshing" && ! has "$d" "confirmed on every VM"'

d=$(new_case); drain_vms "$d" "0|${UNIT_FAILED_2}" "0|LoadState=not-found\nActiveState=active\nExecMainStatus=0\n"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A17 a running unit whose unit file is gone (not-found + active) -> alive, NOT confirmed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "alive aether-cloud-x-node-b (id 102, ${VM_B}): unit LoadState=not-found ActiveState=active ExecMainStatus=0" && ! has "$d" "confirmed on every VM"'

d=$(new_case); drain_vms "$d" "0|exited|2\n" "0|created|0\n"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" container 0
check "$d" "A18 a never-started container (created) beside an exited one -> alive, NOT confirmed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "alive aether-cloud-x-node-b (id 102, ${VM_B}): container Status=created ExitCode=0" && ! has "$d" "confirmed on every VM"'

d=$(new_case); drain_vms "$d" "0|${UNIT_NOT_LOADED}" "0|LoadState=loaded\nActiveState=failed\nExecMainStatus=1\n"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "A19 a loaded unit failed with exit 1 is positively halted per the ruling -> confirmed beside a not-loaded unit" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "halted aether-cloud-x-node-b (id 102, ${VM_B}): unit LoadState=loaded ActiveState=failed ExecMainStatus=1"'

describe_case() { # describe_case <label> <describe stderr text>
    local d
    d=$(new_case)
    drain_vms "$d" "0|${UNIT_FAILED_2}" none
    printf '%s\n' "$2" > "$d/describe-out-102"
    run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
    check "$d" "$1" eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "unreadable aether-cloud-x-node-b" && ! has "$d" "gone aether-cloud-x-node-b" && ! has "$d" "confirmed on every VM"'
}
describe_case "A20 describe 'hcloud: project not found (not_found)' is not a deletion -> unreadable, NOT confirmed" "hcloud: project not found (not_found)"
describe_case "A21 describe 'hcloud: hcloud: server responded with status code 404' (proxy) -> unreadable" "hcloud: hcloud: server responded with status code 404"
describe_case "A22 describe 'proxy: 404 Not Found' -> unreadable" "proxy: 404 Not Found"
describe_case "A23 describe 'hcloud: server not found: 102' (not hcloud's exact wording) -> unreadable" "hcloud: server not found: 102"
describe_case "A24 describe 'hcloud: Server not found: 999' (another id) -> unreadable" "hcloud: Server not found: 999"

d=$(new_case); printf '%s\n' "$LIST_AB" > "$d/hcloud-list.1"; printf '102 aether-cloud-x-node-b running %s\n' "$VM_B" > "$d/hcloud-list"
resp "$d/ssh-${VM_A}" 0 "$UNIT_ACTIVE_0"; resp "$d/ssh-${VM_B}" 0 "$UNIT_FAILED_2"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 12
check "$d" "A25 a VM listed in round 1 (active) and missing from later listings, not reported deleted -> NOT confirmed (partial listing)" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "unreadable (id 101): listed in an earlier round but absent from this listing and not reported deleted" && ! has "$d" "confirmed on every VM"'

d=$(new_case); printf '%s\n' "$LIST_AB" > "$d/hcloud-list.1"; printf '102 aether-cloud-x-node-b running %s\n' "$VM_B" > "$d/hcloud-list"
resp "$d/ssh-${VM_A}" 0 "$UNIT_ACTIVE_0"; resp "$d/ssh-${VM_B}" 0 "$UNIT_FAILED_2"; : > "$d/gone-101"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 12
check "$d" "A26 the same VM missing later AND reported deleted by hcloud -> gone, confirmed" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "gone (id 101): listed in an earlier round, absent now, hcloud reports the server deleted" && has "$d" "confirmed on every VM (round 2"'

echo "== L. CTO ruling 2026-09-13: a not-loaded unit is no running node, not proof of a drain"

d=$(new_case); drain_vms "$d" "0|${UNIT_NOT_LOADED}" "0|${UNIT_NOT_LOADED}"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "L1 every unit not loaded (a cluster still bootstrapping) -> NOT confirmed, refused" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "notloaded aether-cloud-x-node-a (id 101, ${VM_A}): unit LoadState=not-found ActiveState=inactive ExecMainStatus=0" && has "$d" "no VM is positively drain-halted" && ! has "$d" "confirmed on every VM"'

d=$(new_case); drain_vms "$d" "0|${UNIT_NOT_LOADED}" "0|${UNIT_FAILED_2}"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "L2 a not-loaded unit beside a positively halted one -> confirmed (reapable)" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "confirmed on every VM" && has "$d" "notloaded aether-cloud-x-node-a" && has "$d" "halted aether-cloud-x-node-b"'

d=$(new_case); drain_vms "$d" "0|${UNIT_NOT_LOADED}" "0|${UNIT_ACTIVE_0}"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "L3 a not-loaded unit beside an active one -> NOT confirmed, refused" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "alive aether-cloud-x-node-b" && ! has "$d" "confirmed on every VM"'

d=$(new_case); drain_vms "$d" "0|${UNIT_NOT_LOADED}" "0|${UNIT_INACTIVE_2}"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "L4 loaded + inactive with ExecMainStatus=2 is positively halted -> confirmed beside a not-loaded unit" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "halted aether-cloud-x-node-b (id 102, ${VM_B}): unit LoadState=loaded ActiveState=inactive ExecMainStatus=2"'

d=$(new_case); drain_vms "$d" "0|${UNIT_NOT_LOADED}" "0|${UNIT_INACTIVE_0}"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "L5 not-loaded beside a stopped (loaded, inactive/0) unit: none halted -> NOT confirmed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "stopped aether-cloud-x-node-b" && has "$d" "no VM is positively drain-halted"'

d=$(new_case); drain_vms "$d" "0|${UNIT_NOT_LOADED}" none; : > "$d/gone-102"
run_in "$d" "$CLUSTER_LIB" _cloud_await_full_drain "$TEST_CLUSTER" jvm 0
check "$d" "L6 not-loaded beside a gone VM: none halted -> NOT confirmed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "gone aether-cloud-x-node-b" && has "$d" "no VM is positively drain-halted"'

echo "== B. cloud S20 (test_cluster_recovers_to_five_on_duty)"

# Spies: the reap+bootstrap (records, advances virtual time by RECOVER_TAKES_S) and the
# barrier constituents (record their order).
s20_setup() {
    local dir="$1" verdict="$2"
    cat > "$dir/setup.sh" <<EOF
VERDICT_FILE="\$STUB_DIR/verdict"
echo "$verdict" > "\$VERDICT_FILE"
export CLOUD_RUNTIME=jvm
CLOUD_DRAIN_CONFIRM_BOUND_S=0
_cloud_full_drain_recover() { echo reap >> "\$STUB_DIR/reap-calls"; SECONDS=\$((SECONDS + \${RECOVER_TAKES_S:-200})); return \${RECOVER_RC:-0}; }
restart_all_nodes() { echo restart >> "\$STUB_DIR/restart-calls"; return 0; }
wait_for_leader() { echo leader >> "\$STUB_DIR/barrier-calls"; return 0; }
await_generation_quiesced() { echo quiesce >> "\$STUB_DIR/barrier-calls"; return 0; }
wait_for_cluster_ready() { echo "ready \$2" >> "\$STUB_DIR/barrier-calls"; return 0; }
_reestablish_echo_baseline() { echo echo >> "\$STUB_DIR/barrier-calls"; return \${ECHO_RC:-0}; }
EOF
    resp "$dir/api_api_v1_health" 0 '{"status":"healthy"}'
}

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" none
resp "$d/$TOPO_PATH_KEY" 0 "$TOPO_0"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B1 an unreadable VM (while a live responder reports 0 cores) -> S20 refuses, reap never called" \
    eval '[ "$(rc_of "$d")" = 1 ] && [ ! -f "$d/reap-calls" ] && has "$d" "did NOT reap" && has "$d" "budget 600s" && has "$d" "unreadable aether-cloud-x-node-b"'

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_ACTIVE_0}"
resp "$d/$TOPO_PATH_KEY" 0 "$TOPO_0"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B2 a unit still active -> S20 refuses, reap never called" \
    eval '[ "$(rc_of "$d")" = 1 ] && [ ! -f "$d/reap-calls" ] && has "$d" "alive aether-cloud-x-node-b"'

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"
resp "$d/$TOPO_PATH_KEY" 0 "$TOPO_5"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B3 confirmed drain -> one reap, PASS line prints the elapsed and the 600s budget" \
    eval '[ "$(rc_of "$d")" = 0 ] && [ "$(lines_of "$d/reap-calls")" = 1 ] && has_re "$d" "recovered to 5 healthy cores 2[01][0-9]s after drain confirmation began \\(budget 600s"'
check "$d" "B3b barriers run after recovery in restart_all_nodes' order, echo redeploy included" \
    eval '[ "$(tr "\n" "," < "$d/barrier-calls")" = "leader,quiesce,ready 5,echo," ]'

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"
resp "$d/$TOPO_PATH_KEY" 0 "$TOPO_5"; echo "RECOVER_TAKES_S=700" >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B4 reap+rebootstrap past the budget -> FAIL with the measured elapsed and the budget, no barriers" \
    eval '[ "$(rc_of "$d")" = 1 ] && has_re "$d" "took 7[01][0-9]s, not under the 600s budget" && [ ! -f "$d/barrier-calls" ]'

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"
resp "$d/$TOPO_PATH_KEY" 0 "$TOPO_3"; echo "TIMEOUT_SCALE=3" >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B5 never 5 cores (cloud TIMEOUT_SCALE=3) -> FAIL within the UNSCALED remaining budget, elapsed + budget + last count printed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has_re "$d" "timeout: (3[6-9][0-9]|40[0-2])s\\)" && has_re "$d" "remaining (3[6-9][0-9]|40[0-2])s of the 600s S20 budget" && has "$d" "last observed value: 3" && has_re "$d" "not back to 5 healthy cores \\(elapsed 6[0-2][0-9]s, budget 600s" && ! has "$d" "recovered to 5 healthy cores"'

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
polls=$(sed -n 's/.*(\([0-9]*\) of \([0-9]*\) read(s) failed).*/\1 \2/p' "$d/out" | tail -1)
topo_calls=$(grep -cxF "/api/v1/cluster/topology" "$d/api-calls" 2>/dev/null || echo 0)
check "$d" "B6 topology unreadable -> FAIL says the read failed, no shell-error noise, one read per poll" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "last observed value: <read failed: _cluster_active_core_count_checked rc=1, no value>" && ! has "$d" "integer expression expected" && [ -n "$polls" ] && [ "${polls%% *}" = "${polls##* }" ] && [ "$topo_calls" = "${polls##* }" ]'

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"
echo "unset CLOUD_RUNTIME" >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B7 CLOUD_RUNTIME unset on cloud -> refused before probing or reaping" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "CLOUD_RUNTIME is '"'"'<unset>'"'"' on a cloud run" && [ ! -f "$d/hcloud-calls" ] && [ ! -f "$d/reap-calls" ]'

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"
# 6s of virtual budget left, and the topology read takes 8 REAL seconds: the poll starts inside
# the budget (unless the box stalls this case for 6s) and ends past it.
resp "$d/$TOPO_PATH_KEY" 0 "$TOPO_5"; echo "RECOVER_TAKES_S=594" >> "$d/setup.sh"; echo 8 > "$d/${TOPO_PATH_KEY}.delay"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B8 5 cores read on a poll that started in budget but ended past it -> FAIL, not PASS" \
    eval '[ "$(rc_of "$d")" = 1 ] && has_re "$d" "5 healthy cores reached only after 6[0-2][0-9]s, not under the 600s budget" && ! has "$d" "S20 (cloud): recovered"'

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"
resp "$d/$TOPO_PATH_KEY" 0 "$TOPO_5"; echo "ECHO_RC=1" >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B9 echo baseline not restored -> S20 FAILs after the recovery PASS" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "post-recovery barriers (leader election / test-echo baseline) failed"'

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"
echo 'BOOTSTRAP_CLUSTER_NAME=""' >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B10 no cluster name -> refused, reap never called" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "no cluster name" && [ ! -f "$d/reap-calls" ]'

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"
echo "RECOVER_RC=1" >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B11 reap+rebootstrap fails -> FAIL with elapsed and budget" \
    eval '[ "$(rc_of "$d")" = 1 ] && has_re "$d" "full-drain recovery \\(reap \\+ rebootstrap\\) failed \\(elapsed [0-9]+s, budget 600s\\)"'

d=$(new_case); s20_setup "$d" quorum-held; resp "$d/$TOPO_PATH_KEY" 0 "$TOPO_3"; echo "TIMEOUT_SCALE=3" >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B12 quorum-held -> restart_all_nodes path, no drain probe or reap, FAIL prints the scaled 180s it enforced" \
    eval '[ "$(rc_of "$d")" = 1 ] && [ "$(lines_of "$d/restart-calls")" = 1 ] && ! grep -qF "aether-cluster=" "$d/hcloud-calls" 2>/dev/null && [ ! -f "$d/reap-calls" ] && has "$d" "budget 180s)" && has "$d" "within 180s after restart_all_nodes (RECOVERY_BUDGET_S=60 x TIMEOUT_SCALE=3"'

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_NOT_LOADED}" "0|${UNIT_NOT_LOADED}"
resp "$d/$TOPO_PATH_KEY" 0 "$TOPO_0"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B15 S20 on a cluster whose units are all not loaded (bootstrapping) -> refuses, reap never called" \
    eval '[ "$(rc_of "$d")" = 1 ] && [ ! -f "$d/reap-calls" ] && has "$d" "did NOT reap" && has "$d" "no VM is positively drain-halted"'

d=$(new_case); s20_setup "$d" quorum-lost; echo docker > "$d/env"
resp "$d/$TOPO_PATH_KEY" 0 "$TOPO_5"; echo "TIMEOUT_SCALE=2" >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B14 docker/remote PASS states the scaled budget enforced (remote TIMEOUT_SCALE=2 -> 120s)" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "S20: cluster recovered to 5 healthy cores within 120s of restart"'

d=$(new_case); s20_setup "$d" quorum-lost; echo docker > "$d/env"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B13 docker: an unreadable count is reported as a failed read, never as 0" \
    eval '[ "$(rc_of "$d")" = 1 ] && [ "$(lines_of "$d/restart-calls")" = 1 ] && has "$d" "last observed value: <read failed: _cluster_active_core_count_checked rc=1, no value>" && has "$d" "within 60s of restart" && ! has "$d" "integer expression expected"'

echo "== B'. round 3: S20 budget covers confirmation, preflight, boundary (SF1 V7/V8, N-g, N-i)"

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"; resp "$d/ssh-${VM_B}.1" 255 ""
resp "$d/$TOPO_PATH_KEY" 0 "$TOPO_5"
printf 'CLOUD_DRAIN_CONFIRM_BOUND_S=200\nCLOUD_DRAIN_PROBE_INTERVAL_S=100\nRECOVER_TAKES_S=550\n' >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B16 100s spent confirming + 550s reap+bootstrap -> FAIL: the budget clock starts before confirmation" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "confirmed on every VM (round 2" && has_re "$d" "took 6[5-7][0-9]s, not under the 600s budget" && ! has "$d" "S20 (cloud): recovered"'

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" none
printf 'TIMEOUT_SCALE=3\nCLOUD_DRAIN_CONFIRM_BOUND_S=10\n' >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B17 the confirmation bound is not scaled by TIMEOUT_SCALE (cloud scale 3 -> still 10s)" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "(bound 10s, checked between rounds)" && [ ! -f "$d/reap-calls" ]'

d=$(new_case); s20_setup "$d" quorum-lost; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"
echo "RECOVER_RC=2" >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" test_cluster_recovers_to_five_on_duty
check "$d" "B18 recovery refused in preflight (rc 2) -> S20 says nothing was destroyed, not that the reap failed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "full-drain recovery refused in preflight — nothing destroyed, no reap attempted" && ! has "$d" "(reap + rebootstrap) failed"'

case_budget() { for e in 599 600 601; do if _s20_within_budget "$e" 600; then echo "WB[$e]=in;"; else echo "WB[$e]=out;"; fi; done; }
d=$(new_case); run_in "$d" "$SELF_DRAIN_SUITE" case_budget
check "$d" "B19 both S20 budget checks use one strict comparison: 599 in, 600 out, 601 out" \
    eval 'has "$d" "WB[599]=in;" && has "$d" "WB[600]=out;" && has "$d" "WB[601]=out;"'

echo "== K. _cloud_full_drain_recover itself (real function; the reaper script and aether CLI are stubbed)"

k_setup() {
    cat > "$1/setup.sh" <<EOF
export CLUSTER_ID=b
export CLOUD_TOML_B="\$STUB_DIR/b.toml"
: > "\$CLOUD_TOML_B"
printf '#!/bin/sh\necho "\$*" >> "%s/reaper-args"\nexit 0\n' "\$STUB_DIR" > "\$STUB_DIR/reaper.sh"
chmod +x "\$STUB_DIR/reaper.sh"
export AETHER_CLOUD_REAPER="\$STUB_DIR/reaper.sh"
aether() { echo "\$*" >> "\$STUB_DIR/aether-calls"; echo "Step 12/12: Done."; }
EOF
}

d=$(new_case); k_setup "$d"
run_in "$d" "$CLUSTER_LIB" _cloud_full_drain_recover
check "$d" "K1 recovery logs its evidence as per-VM confirmation, reaps strictly by cluster label, then bootstraps" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "full self-drain confirmed from per-VM evidence (_cloud_reap_after_confirmed_drain)" && ! has "$d" "0 active cores" && grep -qxF -- "--cluster ${TEST_CLUSTER} --strict-cluster --destroy --force" "$d/reaper-args" && grep -q "^cluster bootstrap " "$d/aether-calls"'

d=$(new_case); k_setup "$d"; echo "unset CLOUD_TOML_B" >> "$d/setup.sh"
run_in "$d" "$CLUSTER_LIB" _cloud_full_drain_recover
check "$d" "K2 no bootstrap TOML -> rc 2 (preflight), the reaper never runs" \
    eval '[ "$(rc_of "$d")" = 2 ] && [ ! -f "$d/reaper-args" ] && has "$d" "refusing before destroying anything"'

d=$(new_case); k_setup "$d"; echo "export AETHER_CLOUD_REAPER=/nonexistent/cloud-reaper.sh" >> "$d/setup.sh"
run_in "$d" "$CLUSTER_LIB" _cloud_full_drain_recover
check "$d" "K3 reaper missing -> rc 2 (preflight), nothing destroyed" \
    eval '[ "$(rc_of "$d")" = 2 ] && [ ! -f "$d/aether-calls" ] && has "$d" "not found or not executable (resolved path: /nonexistent/cloud-reaper.sh) — nothing destroyed"'

d=$(new_case); k_setup "$d"; echo 'BOOTSTRAP_CLUSTER_NAME=""' >> "$d/setup.sh"
run_in "$d" "$CLUSTER_LIB" _cloud_full_drain_recover
check "$d" "K4 no cluster name -> rc 2 (preflight), the reaper never runs" \
    eval '[ "$(rc_of "$d")" = 2 ] && [ ! -f "$d/reaper-args" ] && has "$d" "BOOTSTRAP_CLUSTER_NAME unset — refusing an unscoped reap"'

d=$(new_case); k_setup "$d"; echo "export CLUSTER_ID=zz" >> "$d/setup.sh"
run_in "$d" "$CLUSTER_LIB" _cloud_full_drain_recover
check "$d" "K5 unrecognized CLUSTER_ID -> rc 2 (preflight), the reaper never runs" \
    eval '[ "$(rc_of "$d")" = 2 ] && [ ! -f "$d/reaper-args" ] && has "$d" "unrecognized CLUSTER_ID='"'"'zz'"'"'" && has "$d" "nothing destroyed"'

echo "== W. every reap in the harness goes through positive per-VM confirmation (CTO ruling 3)"

d=$(new_case)
{
    echo "recover call sites:"
    awk '!/^[[:space:]]*#/ && /(^|[;&|!(`[:space:]])_cloud_full_drain_recover([;&|)`[:space:]]|$)/ { print "  " FILENAME ":" FNR ": " $0 }' "$INTEG_DIR"/lib/*.sh "$INTEG_DIR"/suites/*/*.sh
    echo "gate call sites:"
    awk '!/^[[:space:]]*#/ && /_cloud_reap_after_confirmed_drain[[:space:]]+"/ { print "  " FILENAME ":" FNR ": " $0 }' "$INTEG_DIR"/lib/*.sh "$INTEG_DIR"/suites/*/*.sh
} > "$d/out" 2>&1
w1_recover=$(sed -n '/^recover call sites:/,/^gate call sites:/p' "$d/out" | grep -c '^  ')
w1_gate=$(sed -n '/^gate call sites:/,$p' "$d/out" | grep -c '^  ')
check "$d" "W1 _cloud_full_drain_recover has exactly one caller (inside the gate); S20 and the 4 restart_all_nodes reap paths call the gate" \
    eval '[ "$w1_recover" = 1 ] && grep -qF "_cloud_full_drain_recover && rc=0 || rc=\$?" "$d/out" && [ "$w1_gate" = 5 ] && [ "$(grep -c "lib/cluster.sh:.*_cloud_reap_after_confirmed_drain \"restart_all_nodes\"" "$d/out")" = 4 ]'

r_setup() { # r_setup <dir> <runtime or -unset->
    cat > "$1/setup.sh" <<'EOF'
CLOUD_DRAIN_CONFIRM_BOUND_S=0
_cloud_full_drain_recover() { echo reap >> "$STUB_DIR/reap-calls"; return 0; }
wait_for_leader() { echo leader >> "$STUB_DIR/barrier-calls"; return 0; }
await_generation_quiesced() { echo quiesce >> "$STUB_DIR/barrier-calls"; return 0; }
wait_for_cluster_ready() { echo "ready $2" >> "$STUB_DIR/barrier-calls"; return 0; }
_reestablish_echo_baseline() { echo echo >> "$STUB_DIR/barrier-calls"; return 0; }
EOF
    if [ "$2" = "-unset-" ]; then echo "unset CLOUD_RUNTIME" >> "$1/setup.sh"; else echo "export CLOUD_RUNTIME=$2" >> "$1/setup.sh"; fi
}
nodeid_vms() { # the same two VMs as _cloud_running_vm_ips lists them (name status ipv4 labels)
    printf 'aether-cloud-x-node-a running %s aether-node-id=aether-cloud-%s-node-a,aether-cluster=%s\naether-cloud-x-node-b running %s aether-node-id=aether-cloud-%s-node-b,aether-cluster=%s\n' \
        "$VM_A" "$TEST_CLUSTER" "$TEST_CLUSTER" "$VM_B" "$TEST_CLUSTER" "$TEST_CLUSTER" > "$1/hcloud-nodeid-list"
}

d=$(new_case); r_setup "$d" -unset-; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"
run_in "$d" "$CLUSTER_LIB" _cloud_reap_after_confirmed_drain "W2" 0
check "$d" "W2 the gate with CLOUD_RUNTIME unset -> rc 3, reap never called" \
    eval '[ "$(rc_of "$d")" = 3 ] && [ ! -f "$d/reap-calls" ] && [ ! -f "$d/hcloud-calls" ] && ! has "$d" "full self-drain of" && has "$d" "W2: CLOUD_RUNTIME is '"'"'<unset>'"'"' — cannot read VM drain state, refusing to reap or rebootstrap"'

d=$(new_case); r_setup "$d" jvm; drain_vms "$d" "0|${UNIT_FAILED_2}" none; nodeid_vms "$d"; resp "$d/$TOPO_PATH_KEY" 0 "$TOPO_0"
run_in "$d" "$CLUSTER_LIB" restart_all_nodes
check "$d" "R1 restart_all_nodes: mgmt API reports 0 cores but a VM is unreadable -> nominated, refused, reap never called" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "a CANDIDATE full self-drain; confirming from per-VM evidence before any reap" && has "$d" "unreadable aether-cloud-x-node-b" && has "$d" "restart_all_nodes: full self-drain not positively confirmed on every VM — refusing to reap or rebootstrap" && [ ! -f "$d/reap-calls" ]'

d=$(new_case); r_setup "$d" jvm; drain_vms "$d" "0|${UNIT_ACTIVE_0}" "0|${UNIT_ACTIVE_0}"; nodeid_vms "$d"
run_in "$d" "$CLUSTER_LIB" restart_all_nodes
check "$d" "R2 restart_all_nodes: mgmt API down and no VM answers its mgmt port, but every unit is ACTIVE (silent-but-alive) -> refused, reap never called" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "NONE answer their mgmt port directly" && has "$d" "silence is not death" && has "$d" "alive aether-cloud-x-node-a" && [ ! -f "$d/reap-calls" ]'

d=$(new_case); r_setup "$d" jvm; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_FAILED_2}"; nodeid_vms "$d"
run_in "$d" "$CLUSTER_LIB" restart_all_nodes
check "$d" "R3 restart_all_nodes: silent mgmt ports AND every unit halted -> confirmed, reaped once, barriers run" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "confirmed on every VM" && [ "$(lines_of "$d/reap-calls")" = 1 ] && [ "$(tr "\n" "," < "$d/barrier-calls")" = "leader,quiesce,ready 4,echo," ]'

d=$(new_case); r_setup "$d" jvm; drain_vms "$d" "0|${UNIT_FAILED_2}" "0|${UNIT_ACTIVE_0}"; nodeid_vms "$d"; resp "$d/$TOPO_PATH_KEY" 0 "$TOPO_0"
run_in "$d" "$CLUSTER_LIB" restart_all_nodes
check "$d" "R4 restart_all_nodes: 0 cores reported, one unit halted, one still active -> refused, reap never called" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "alive aether-cloud-x-node-b" && [ ! -f "$d/reap-calls" ]'

echo "== C. _cluster_active_core_count_checked / cluster_active_core_count (lib/cluster.sh)"

case_core_count() {
    local v rc
    v=$(_cluster_active_core_count_checked) && rc=0 || rc=$?
    echo "VALUE=[${v}] CHECKED_RC=${rc} CALLS=$(grep -c . "$STUB_DIR/api-calls" 2>/dev/null || echo 0) LEGACY=[$(cluster_active_core_count)]"
}
core_case() { # core_case <label> <expected VALUE..CALLS prefix> <rc> <body or -none->
    local d
    d=$(new_case)
    [ "$4" = "-none-" ] || resp "$d/${TOPO_PATH_KEY}.1" "$3" "$4"
    [ "$4" = "-none-" ] || resp "$d/${TOPO_PATH_KEY}.2" "$3" "$4"
    run_in "$d" "$CLUSTER_LIB" case_core_count
    check "$d" "$1" has "$d" "$2"
}
d=$(new_case); resp "$d/${TOPO_PATH_KEY}.1" 0 "$TOPO_5"
run_in "$d" "$CLUSTER_LIB" case_core_count
check "$d" "C1 one read, parsed once: the blip that used to follow the validating read cannot turn 5 into 0" \
    has "$d" "VALUE=[5] CHECKED_RC=0 CALLS=1 LEGACY=[0]"
core_case "C2 failed read -> no value, rc 1 (legacy wrapper still prints 0)" "VALUE=[] CHECKED_RC=1 CALLS=1 LEGACY=[0]" 1 "-none-"
core_case "C3 error body without counts -> no value, rc 2" "VALUE=[] CHECKED_RC=2 CALLS=1 LEGACY=[0]" 0 '{"type":"about:blank","status":503}'
core_case "C4 coreCount null -> no value, rc 2" "VALUE=[] CHECKED_RC=2" 0 '{"coreCount":null}'
core_case "C5 coreNodes longer than coreCount -> the nodes count, as before" "VALUE=[3] CHECKED_RC=0 CALLS=1 LEGACY=[3]" 0 '{"coreCount":2,"coreNodes":["a","b","c"]}'
core_case "C6 truncated coreNodes array -> no value, rc 2" "VALUE=[] CHECKED_RC=2" 0 '{"coreNodes":["a","b"'
core_case "C7 a genuine 0 is a value" "VALUE=[0] CHECKED_RC=0 CALLS=1 LEGACY=[0]" 0 "$TOPO_0"

echo "== D. wait_for value reader (lib/common.sh)"

wait_setup() { # wait_setup <dir> <reader body>
    echo docker > "$1/env"
    cat > "$1/setup.sh" <<EOF
stub_reader() { echo read >> "\$STUB_DIR/reads"; $2; }
EOF
}
PRED='echo eval >> "$STUB_DIR/evals"; [ "$WAIT_FOR_VALUE" -eq 5 ]'

d=$(new_case); wait_setup "$d" "return 1"
run_in "$d" "$CLUSTER_LIB" wait_for "probe" "$PRED" 10 5 stub_reader
check "$d" "D1 reader fails every poll -> predicate never evaluated, FAIL says the read failed" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "last observed value: <read failed: stub_reader rc=1, no value> (2 of 2 read(s) failed)" && [ ! -f "$d/evals" ] && ! has "$d" "integer expression expected" && ! has "$d" "shell error"'

d=$(new_case); wait_setup "$d" "echo 4"
run_in "$d" "$CLUSTER_LIB" wait_for "probe" "$PRED" 10 5 stub_reader
check "$d" "D2 reader returns 4 -> FAIL states the last value read" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "last observed value: 4 (0 of 2 read(s) failed)" && [ "$(lines_of "$d/evals")" = 2 ]'

d=$(new_case); wait_setup "$d" "echo 5"
run_in "$d" "$CLUSTER_LIB" wait_for "probe" "$PRED" 10 5 stub_reader
check "$d" "D3 reader returns 5 -> PASS on one read" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "[PASS]" && [ "$(lines_of "$d/reads")" = 1 ]'

d=$(new_case); wait_setup "$d" "true"
run_in "$d" "$CLUSTER_LIB" wait_for "probe" "$PRED" 10 5 stub_reader
check "$d" "D4 reader succeeds with no output -> a failed read, not a value" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "<read failed: stub_reader rc=0, no value>" && [ ! -f "$d/evals" ]'

d=$(new_case); wait_setup "$d" "true"
run_in "$d" "$CLUSTER_LIB" wait_for "typo" '[ "$(no_such_helper_typo)" -eq 0 ]' 2 5
check "$d" "D5 a typo'd predicate (no reader) is still called a shell error, not an unreachable read" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "wait_for predicate emitted shell error (rc=2)" && ! has "$d" "unreachable"'

d=$(new_case); echo docker > "$d/env"
run_in "$d" "$CLUSTER_LIB" wait_for_node_count 5 10
check "$d" "D6 wait_for_node_count on an unreachable API -> read-failed FAIL, no per-poll shell errors" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "last observed value: <read failed: _cluster_member_count_checked rc=1, no value>" && ! has "$d" "integer expression expected"'

case_wfv_leak() {
    wait_for "first (reader)" '[ "$WAIT_FOR_VALUE" -eq 5 ]' 4 2 "echo 5"
    wait_for "second (no reader)" '[ "${WAIT_FOR_VALUE:-}" = 5 ]' 4 2
}
d=$(new_case); echo docker > "$d/env"
run_in "$d" "$CLUSTER_LIB" case_wfv_leak
check "$d" "D7 a value read by one wait is not visible to a later wait without a reader" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "first (reader) (" && has "$d" "second (no reader) (timed out"'

case_wfv_leak_on_timeout() {
    wait_for "first (reader, times out)" '[ "$WAIT_FOR_VALUE" -eq 5 ]' 4 2 "echo 4"
    wait_for "second (no reader)" '[ "${WAIT_FOR_VALUE:-}" = 4 ]' 4 2
}
d=$(new_case); echo docker > "$d/env"
run_in "$d" "$CLUSTER_LIB" case_wfv_leak_on_timeout
check "$d" "D8 a value read by a wait that TIMED OUT is not visible to a later wait without a reader" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "first (reader, times out) (timed out" && has "$d" "second (no reader) (timed out"'

echo "== E. S19 tier 2 (_confirm_survivor_departure) on cloud"

SURV1_IP="203.0.113.10"   # node-1 -> hetzner-eu-core-0 in fixtures/bootstrap-state.json
SURV2_IP="203.0.113.11"   # node-2 -> hetzner-eu-core-1
tier2_setup() { # tier2_setup <dir> <runtime or -unset->
    cat > "$1/setup.sh" <<EOF
SURVIVOR_EXIT_BUDGET_S=0
SSH_STDERR_FILE="\$STUB_DIR/ssh-stderr"
SURVIVORS_FILE="\$STUB_DIR/survivors"
printf 'node-1\nnode-2\n' > "\$SURVIVORS_FILE"
EOF
    if [ "$2" = "-unset-" ]; then echo "unset CLOUD_RUNTIME" >> "$1/setup.sh"; else echo "export CLOUD_RUNTIME=$2" >> "$1/setup.sh"; fi
}

d=$(new_case); tier2_setup "$d" jvm; resp "$d/ssh-${SURV1_IP}" 0 "$UNIT_FAILED_2"
run_in "$d" "$SELF_DRAIN_SUITE" _confirm_survivor_departure node-1 ""
check "$d" "E1 jvm failed/2 -> departure confirmed from systemctl show" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "designed drain halt confirmed via SSH systemctl-show" && grep -qF "${SURV1_IP}|systemctl show aether-node --property=ActiveState,ExecMainStatus" "$d/ssh-calls"'

d=$(new_case); tier2_setup "$d" jvm; resp "$d/ssh-${SURV1_IP}" 0 'ActiveState=active\nExecMainStatus=2\n'
run_in "$d" "$SELF_DRAIN_SUITE" _confirm_survivor_departure node-1 ""
check "$d" "E2 jvm active/2 -> S19 violation naming the state read" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "systemd unit reports ActiveState='"'"'active'"'"' ExecMainStatus='"'"'2'"'"'"'

d=$(new_case); tier2_setup "$d" jvm; resp "$d/ssh-${SURV1_IP}" 0 'ActiveState=failed\nExecMainStatus=0\n'
run_in "$d" "$SELF_DRAIN_SUITE" _confirm_survivor_departure node-1 ""
check "$d" "E3 jvm failed/0 -> S19 violation" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "ExecMainStatus='"'"'0'"'"' — not a designed drain halt"'

d=$(new_case); tier2_setup "$d" jvm; resp "$d/ssh-${SURV1_IP}" 0 'ActiveState=failed\r\nExecMainStatus=2\r\n'
run_in "$d" "$SELF_DRAIN_SUITE" _confirm_survivor_departure node-1 ""
check "$d" "E4 jvm failed/2 with CRLF line ends -> still confirmed" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "designed drain halt confirmed"'

d=$(new_case); tier2_setup "$d" -unset-; resp "$d/ssh-${SURV1_IP}" 0 "$UNIT_FAILED_2"
run_in "$d" "$SELF_DRAIN_SUITE" _confirm_survivor_departure node-1 ""
check "$d" "E5 CLOUD_RUNTIME unset -> refused, no docker inspect default" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "CLOUD_RUNTIME is '"'"'<unset>'"'"'" && [ ! -f "$d/ssh-calls" ]'

d=$(new_case); tier2_setup "$d" jvm; resp "$d/ssh-${SURV1_IP}" 1 'Failed to get properties: Access denied\n'
run_in "$d" "$SELF_DRAIN_SUITE" _confirm_survivor_departure node-1 ""
check "$d" "E7 SSH connected but the remote read failed with output -> S19 violation naming the runtime and rc" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "SSH connected but the remote drain-state read (jvm runtime) failed (rc=1): Failed to get properties"'

d=$(new_case); tier2_setup "$d" jvm; echo 'SURVIVOR_IPS_FILE="$STUB_DIR/no-ip-cache"' >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" _confirm_survivor_departure node-1 ""
check "$d" "E8 SSH unreachable with no output -> degrades with a warning naming the runtime, not a violation at tier 2" \
    eval 'has "$d" "tier-2 (SSH drain-state read, jvm runtime) corroboration unavailable (rc=255" && ! has "$d" "remote drain-state read (jvm runtime) failed"'

d=$(new_case); tier2_setup "$d" container; resp "$d/ssh-${SURV1_IP}" 0 '2|2026-09-13T13:07:28Z\n'
run_in "$d" "$SELF_DRAIN_SUITE" _confirm_survivor_departure node-1 ""
check "$d" "E6 container path unchanged: docker inspect exit 2 -> confirmed" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "container exit code=2" && grep -qF "${SURV1_IP}|docker inspect --format '"'"'{{.State.ExitCode}}|{{.State.FinishedAt}}'"'"' aether-node" "$d/ssh-calls"'

echo "== F. exit-code step (test_survivor_exit_codes_are_two) on cloud jvm"

case_h2() { TEST_FAIL_COUNT=0; test_survivor_exit_codes_are_two; local rc=$?; echo "FAILS=${TEST_FAIL_COUNT};"; return "$rc"; }

d=$(new_case); tier2_setup "$d" jvm; resp "$d/ssh-${SURV1_IP}" 0 "$UNIT_FAILED_2"; resp "$d/ssh-${SURV2_IP}" 0 "$UNIT_FAILED_2"
run_in "$d" "$SELF_DRAIN_SUITE" case_h2
check "$d" "F1 both survivors failed/2 -> PASS, no FAIL latched" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "FAILS=0;" && has "$d" "Survivor node-2 systemd unit ActiveState=failed ExecMainStatus=2"'

d=$(new_case); tier2_setup "$d" jvm; resp "$d/ssh-${SURV1_IP}" 0 'ActiveState=failed\nExecMainStatus=0\n'; resp "$d/ssh-${SURV2_IP}" 0 "$UNIT_FAILED_2"
run_in "$d" "$SELF_DRAIN_SUITE" case_h2
check "$d" "F2 first survivor exit 0, second fine -> the first FAIL stays latched" \
    eval 'has "$d" "FAILS=1;" && has "$d" "Survivor node-1 systemd unit is not in the drain-halt state: expected ActiveState=failed ExecMainStatus=2, got ActiveState='"'"'failed'"'"' ExecMainStatus='"'"'0'"'"'"'

d=$(new_case); tier2_setup "$d" jvm; resp "$d/ssh-${SURV1_IP}" 0 "$UNIT_FAILED_2"; resp "$d/ssh-${SURV2_IP}" 0 'ActiveState=active\nExecMainStatus=2\n'
run_in "$d" "$SELF_DRAIN_SUITE" case_h2
check "$d" "F3 ExecMainStatus=2 on a still-active unit -> FAIL (same predicate as S19 tier 2)" \
    eval 'has "$d" "FAILS=1;" && has "$d" "got ActiveState='"'"'active'"'"' ExecMainStatus='"'"'2'"'"'"'

d=$(new_case); tier2_setup "$d" jvm; resp "$d/ssh-${SURV2_IP}" 0 "$UNIT_FAILED_2"
run_in "$d" "$SELF_DRAIN_SUITE" case_h2
check "$d" "F4 SSH error on a survivor -> FAIL as unreadable" \
    eval 'has "$d" "FAILS=1;" && has "$d" "Survivor node-1 systemd unit unreadable: SSH/systemctl failed (rc=255)"'

d=$(new_case); tier2_setup "$d" jvm; echo 'rm -f "$SURVIVORS_FILE"' >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" case_h2
check "$d" "F5 no survivors recorded -> FAIL, not a zero-assertion pass" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "Survivors file missing entries"'

d=$(new_case); echo docker > "$d/env"
printf 'SURVIVORS_FILE="$STUB_DIR/survivors"\nprintf "aether-b-node-4\\naether-b-node-5\\n" > "$SURVIVORS_FILE"\nexport AETHER_SSH_USER=root\n' > "$d/setup.sh"
resp "$d/ssh-chaos-harness-test" 0 '2\n'
run_in "$d" "$SELF_DRAIN_SUITE" case_h2
check "$d" "F6 docker/remote: exit code 2 read by docker inspect is attributed to DrainProcedure's Runtime.halt(2)" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "FAILS=0;" && has "$d" "Survivor aether-b-node-4 exit code is 2 (Runtime.halt(2) via DrainProcedure)" && ! has "$d" "SelfDrainCoordinator"'

echo "== G. exit-code step disposition and S20 step name"

disp_case() { # disp_case <label> <env> <runtime or -unset-> <verdict> <command> <expected substring>
    local d
    d=$(new_case)
    echo "$2" > "$d/env"
    tier2_setup "$d" "$3"
    cat >> "$d/setup.sh" <<EOF
VERDICT_FILE="\$STUB_DIR/verdict"
echo "$4" > "\$VERDICT_FILE"
EOF
    resp "$d/ssh-${SURV1_IP}" 0 "$UNIT_FAILED_2"
    resp "$d/ssh-${SURV2_IP}" 0 "$UNIT_FAILED_2"
    run_in "$d" "$SELF_DRAIN_SUITE" $5
    # Locals, not $6/$7: the eval runs inside check, where the positional parameters are
    # check's own (bash dynamic scoping still shows it disp_case's locals).
    local want_first="$6" want_second="${7:-}"
    check "$d" "$1" eval 'has "$d" "$want_first" && { [ -z "$want_second" ] || has "$d" "$want_second"; }'
}
case_record() { _s19_record_exit_code_step; echo "P=${TESTS_PASSED} F=${TESTS_FAILED} S=${TESTS_SKIPPED};"; }
case_label() { echo "LABEL=[$(_s20_test_label)]"; }

disp_case "G1 docker -> run" docker jvm quorum-lost _s19_exit_code_disposition "run"
disp_case "G2 cloud container -> SKIPPED (P=0 F=0 S=1)" cloud container quorum-lost case_record "P=0 F=0 S=1;"
disp_case "G3 cloud jvm, quorum lost -> run through the halt-reason assertion, recorded PASS (P=1 F=0 S=0)" cloud jvm quorum-lost case_record "P=1 F=0 S=0;" "Survivor node-1 systemd unit ActiveState=failed ExecMainStatus=2"
disp_case "G4 cloud jvm, quorum held -> SKIPPED, not a guaranteed FAIL (P=0 F=0 S=1)" cloud jvm quorum-held case_record "P=0 F=0 S=1;"
disp_case "G4b quorum-held skip states why" cloud jvm quorum-held _s19_exit_code_disposition "skip S19 quorum-held"
disp_case "G5 cloud, CLOUD_RUNTIME unset -> recorded FAIL by the runtime refusal, not SKIPPED (P=0 F=1 S=0)" cloud -unset- quorum-lost case_record "P=0 F=1 S=0;" "CLOUD_RUNTIME is '<unset>' on a cloud run"
disp_case "G6 docker S20 name keeps the spec's 60s" docker jvm quorum-lost case_label "LABEL=[Cluster recovers to 5 healthy cores within 60s (S20)]"
disp_case "G7 cloud full-drain S20 name states the 600s budget" cloud jvm quorum-lost case_label "LABEL=[Cluster recovers to 5 healthy cores within 600s of drain confirmation (S20, cloud full drain)]"
d=$(new_case); echo cloud > "$d/env"; tier2_setup "$d" jvm
printf 'VERDICT_FILE="$STUB_DIR/verdict"\necho quorum-held > "$VERDICT_FILE"\nTIMEOUT_SCALE=3\n' >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" case_label
check "$d" "G8 cloud quorum-held S20 name states the scaled 180s that branch enforces" \
    has "$d" "LABEL=[Cluster recovers to 5 healthy cores within 180s after restart (S20, cloud quorum-held)]"

# G9 runs the suite's OWN scenario section (the lines after `trap 'cleanup' EXIT`, which the
# source guard keeps out of every other case) with run_test/skip_test/print_summary recording
# instead of running, so the step wiring below the guard is pinned too.
case_run_section() {
    run_test()      { echo "RUN_TEST|$1|$2"; }
    skip_test()     { echo "SKIP_TEST|$1|$2"; }
    print_summary() { echo "PRINT_SUMMARY"; }
    local section
    section=$(sed -n "/^trap 'cleanup' EXIT\$/,\$p" "$SELF_DRAIN_SUITE" | sed '1d')
    echo "SECTION_LINES=$(printf '%s\n' "$section" | grep -c .);"
    eval "$section"
}
d=$(new_case); echo cloud > "$d/env"; tier2_setup "$d" container
printf 'VERDICT_FILE="$STUB_DIR/verdict"\necho quorum-lost > "$VERDICT_FILE"\n' >> "$d/setup.sh"
run_in "$d" "$SELF_DRAIN_SUITE" case_run_section
g9_lines=$(sed -n 's/^SECTION_LINES=\([0-9]*\);$/\1/p' "$d/out")
check "$d" "G9 the scenario section records the exit-code step through its disposition and names S20 by its enforced budget" \
    eval '[ "${g9_lines:-0}" -ge 8 ] && has "$d" "SKIP_TEST|Survivor exit codes are 2 (Runtime.halt(2))|GAP-A (cloud --runtime container)" && ! has "$d" "RUN_TEST|Survivor exit codes are 2" && has "$d" "RUN_TEST|Cluster recovers to 5 healthy cores within 600s of drain confirmation (S20, cloud full drain)|test_cluster_recovers_to_five_on_duty" && has "$d" "PRINT_SUMMARY"'

echo "== H. H4 CAUGHT_UP wait (test_identify_owner_and_caught_up_replica)"

REPL_KEY="api_api_v1_streams_ns_repl-failover-events_1_replicas_0"
V_AUTH_SYNCING='{"hrwOwner":"node-1","servedByOwner":true,"replicas":[{"nodeId":"node-1","state":"CAUGHT_UP","confirmedOffset":19,"isHrwOwner":true},{"nodeId":"node-2","state":"SYNCING","confirmedOffset":3,"isHrwOwner":false}]}'
V_AUTH_CAUGHT='{"hrwOwner":"node-1","servedByOwner":true,"replicas":[{"nodeId":"node-1","state":"CAUGHT_UP","confirmedOffset":19,"isHrwOwner":true},{"nodeId":"node-2","state":"CAUGHT_UP","confirmedOffset":19,"isHrwOwner":false}]}'
V_NONOWNER_CLAIM='{"hrwOwner":"node-1","servedByOwner":false,"replicas":[{"nodeId":"node-2","state":"CAUGHT_UP","confirmedOffset":19,"isHrwOwner":false}]}'
V_OWNER_MOVED='{"hrwOwner":"node-2","servedByOwner":true,"replicas":[{"nodeId":"node-2","state":"CAUGHT_UP","confirmedOffset":19,"isHrwOwner":true},{"nodeId":"node-3","state":"CAUGHT_UP","confirmedOffset":19,"isHrwOwner":false}]}'

case_h4() {
    TEST_FAIL_COUNT=0
    OWNER_TO_KILL=""
    test_identify_owner_and_caught_up_replica
    local rc=$?
    echo "OWNER_TO_KILL=${OWNER_TO_KILL}; FAILS=${TEST_FAIL_COUNT}; REPLICA_CALLS=$(grep -c 'replicas/0' "$STUB_DIR/api-calls");"
    return "$rc"
}
h4_case() { # h4_case <wait_s> <first view> <later views...>; prints the case dir
    local d n=1 v
    d=$(new_case)
    echo docker > "$d/env"
    : > "$d/setup.sh"
    [ "$1" = "-default-" ] || printf 'CAUGHT_UP_REPLICA_WAIT_S=%s\n' "$1" > "$d/setup.sh"
    resp "$d/api_api_v1_streams" 0 '[{"namespace":"ns","stream":"repl-failover-events","version":"1"}]'
    shift
    for v in "$@"; do
        resp "$d/${REPL_KEY}.${n}" 0 "$v"
        n=$((n + 1))
    done
    resp "$d/${REPL_KEY}" 0 "${!#}"
    run_in "$d" "$FAILOVER_SUITE" case_h4
    echo "$d"
}

d=$(h4_case 6 "$V_AUTH_SYNCING")
check "$d" "H1 no replica ever CAUGHT_UP -> FAIL after the wait" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "No CAUGHT_UP non-owner replica in an owner-authoritative view before kill within 6s"'

d=$(h4_case 6 "$V_AUTH_SYNCING" "$V_AUTH_CAUGHT")
check "$d" "H2 replica CAUGHT_UP on the next authoritative view -> PASS, owner unchanged" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "A CAUGHT_UP replica other than owner node-1 exists" && has "$d" "OWNER_TO_KILL=node-1; FAILS=0;"'

d=$(h4_case 6 "$V_AUTH_SYNCING" "$V_NONOWNER_CLAIM")
check "$d" "H3 a non-owner view claiming CAUGHT_UP is never judged -> FAIL" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "No CAUGHT_UP non-owner replica in an owner-authoritative view"'

d=$(h4_case 6 "$V_AUTH_SYNCING" "$V_OWNER_MOVED")
check "$d" "H4 owner moved in a refreshed view -> excluded and targeted is the new owner" \
    eval '[ "$(rc_of "$d")" = 0 ] && has "$d" "HRW owner moved node-1 -> node-2" && has "$d" "OWNER_TO_KILL=node-2; FAILS=0;"'

d=$(h4_case 6 "$V_NONOWNER_CLAIM")
check "$d" "H5 initial view never owner-authoritative -> fails without waiting (only the initial 10 attempts)" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "REPLICA_CALLS=10;" && ! has "$d" "No CAUGHT_UP non-owner replica"'

# 4s wait: a 3s pause leaves ~1s, so each refresh gets 1 retry — 2 or 3 replica calls in all
# depending on real time spent, where uncapped refreshes make 11 or more.
d=$(h4_case 4 "$V_AUTH_SYNCING" "$V_NONOWNER_CLAIM")
check "$d" "H6 refresh retries are capped by the time left (~1s left after the pause -> 1 retry, not 10)" \
    eval '[ "$(rc_of "$d")" = 1 ] && { has "$d" "REPLICA_CALLS=2;" || has "$d" "REPLICA_CALLS=3;"; }'

d=$(h4_case -default- "$V_AUTH_SYNCING")
check "$d" "H7 the suite's own CAUGHT_UP_REPLICA_WAIT_S (60s) bounds the wait" \
    eval '[ "$(rc_of "$d")" = 1 ] && has "$d" "before kill within 60s"'

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
