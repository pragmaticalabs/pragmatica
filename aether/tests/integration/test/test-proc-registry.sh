#!/bin/bash
# test-proc-registry.sh — stubs only. lib/common.sh process registry (proc_track / proc_reap), the ONE place that ends a process
# the harness started (#3424, #3427). Invariant: a registered process, and everything it forked, is gone after every exit path,
# and is never left stopped (state T).
#   P1 proc_reap ends a tree whose members ignore TERM and respawn children
#   P2 normal exit of the owning shell   P3 failing exit (set -e)   P4 TERM to the owner   P5 TERM inside the freeze window
#   P6 poll_until: a hanging predicate with NESTED subshells leaves no orphan after the timeout
#   P7 poll_until: TERM to the owner while a predicate hangs leaves nothing
#   P8 count what must NOT be there: raw kill/pkill in load.sh, poll_until, reap_bg_job and test-scale-down-victims.sh
#   Mutations: drop the CONT in proc_reap reddens P5 (state T); drop the trap install reddens P2-P4, P7; poll_until reaping only
#   direct children reddens P6.   INTEG_DIR_UNDER_TEST selects another copy.
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="${INTEG_DIR_UNDER_TEST:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }
WORK=$(mktemp -d)
REAL_PGREP=$(command -v pgrep)
cleanup() { [ -n "${PIDLIST:-}" ] && for p in $(cat "$WORK"/*.pids 2> /dev/null); do kill -CONT "$p" 2> /dev/null; kill -KILL "$p" 2> /dev/null; done; rm -rf "$WORK"; }
PIDLIST=1; trap cleanup EXIT
export TARGET_HOST=localhost; source "${INTEG_DIR}/lib/common.sh" > /dev/null 2>&1
type proc_reap > /dev/null 2>&1 || { echo "  FAIL  proc_reap is not defined in lib/common.sh"; echo "  passed: 0"; echo "  failed: 1"; exit 1; }

state_of() { ps -o stat= -p "$1" 2> /dev/null | tr -d ' '; }
# leftovers <pidfile>: the recorded pids that still exist, as "pid:state" (a zombie is dead; a stopped process is not).
leftovers() { local p s out=""; for p in $(cat "$1" 2> /dev/null); do s=$(state_of "$p"); case "$s" in '' | Z*) ;; *) out="$out $p:$s" ;; esac; done; echo "${out# }"; }
has_pids() { [ "$(grep -c . "$1" 2> /dev/null)" -ge "$2" ]; }
# A fixture loop that records its own pid and its child's pid (the child is a real sleep), then keeps running.
LOOP='( echo $BASHPID >> "$PIDS"; while :; do sleep 31 & echo $! >> "$PIDS"; wait $!; done )'  # sleep-ok: fixture process that must outlive the probe

# P1
PIDS="$WORK/p1.pids"; export PIDS; : > "$PIDS"
( trap '' TERM; echo $BASHPID >> "$PIDS"; while :; do bash -c 'echo $BASHPID >> "$PIDS"; sleep 33 & echo $! >> "$PIDS"; wait'; done ) &  # sleep-ok: respawning fixture
root=$!; proc_track "$root"
poll_until 10 has_pids "$PIDS" 3 || fail "P1 fixture never reached 3 pids"
t0=$SECONDS; proc_reap "$root"; took=$((SECONDS - t0))
left=$(leftovers "$PIDS")
if [ -z "$left" ] && [ "$(wc -l < "$PIDS")" -ge 3 ] && [ "$took" -le 8 ]; then ok "P1 a TERM-ignoring, respawning tree is gone after proc_reap ($(wc -l < "$PIDS" | tr -d ' ') pids seen, ${took}s)"
else fail "P1 left=[$left] took=${took}s"; fi

# own <label> <body>: run <body> in a subshell that owns a registered loop; the loop's pids land in $WORK/<label>.pids.
own() {
    local label="$1"; shift
    PIDS="$WORK/$label.pids"; export PIDS; : > "$PIDS"
    ( eval "$LOOP" & proc_track "$!"; poll_until 10 has_pids "$PIDS" 2; "$@" ) &
    OWNER=$!
    poll_until 15 has_pids "$PIDS" 2
}
settle() { { wait "$OWNER"; } 2> /dev/null; OWNER_RC=$?; }
body_normal() { :; }
body_fail() { set -e; false; }
body_hang() { sleep 60; }  # sleep-ok: the owner is parked here until the probe signals it

own p2 body_normal; settle; left=$(leftovers "$WORK/p2.pids")
[ -z "$left" ] && [ "$OWNER_RC" = 0 ] && ok "P2 normal exit of the owner: its loop and the loop's child are gone" || fail "P2 left=[$left] rc=$OWNER_RC"
own p3 body_fail; settle; left=$(leftovers "$WORK/p3.pids")
[ -z "$left" ] && [ "$OWNER_RC" = 1 ] && ok "P3 failing exit (set -e): nothing left, the owner keeps its failing status (rc=$OWNER_RC)" || fail "P3 left=[$left] rc=$OWNER_RC"
own p4 body_hang; kill -TERM "$OWNER"; settle; left=$(leftovers "$WORK/p4.pids")
[ -z "$left" ] && [ "$OWNER_RC" = 143 ] && ok "P4 TERM to the owner: nothing left, exit 143" || fail "P4 left=[$left] rc=$OWNER_RC"

# P5: the freeze window. pgrep is slowed, so the owner sits between STOP and CONT when the TERM arrives.
mkdir -p "$WORK/slow"; printf '#!/bin/bash\nsleep 2\nexec %s "$@"\n' "$REAL_PGREP" > "$WORK/slow/pgrep"; chmod +x "$WORK/slow/pgrep"  # sleep-ok: the stalled listing is the window
body_reap_slow() { PATH="$WORK/slow:$PATH" proc_reap; }
PIDS="$WORK/p5.pids"; export PIDS; : > "$PIDS"
( eval "$LOOP" & proc_track "$!"; poll_until 10 has_pids "$PIDS" 2; body_reap_slow ) &
OWNER=$!
poll_until 15 has_pids "$PIDS" 2
sleep 0.4  # sleep-ok: lands the signal inside the stalled listing
kill -TERM "$OWNER"
t0=$SECONDS; poll_until 20 bash -c "! kill -0 $OWNER 2>/dev/null"; took=$((SECONDS - t0)); settle
left=$(leftovers "$WORK/p5.pids")
case "$left" in *:T*) stopped=yes ;; *) stopped=no ;; esac
if [ -z "$left" ] && [ "$took" -le 15 ]; then ok "P5 TERM inside the STOP..CONT window: nothing left, none in state T, the owner ended in ${took}s"
else fail "P5 left=[$left] stopped=$stopped owner-ended-in=${took}s"; fi

# P6
hang_nested() { ( echo "$BASHPID" >> "$PIDS"; sleep 33 & echo $! >> "$PIDS"; wait ); }  # sleep-ok: hanging predicate fixture
PIDS="$WORK/p6.pids"; : > "$PIDS"; export PIDS
poll_until 2 hang_nested; rc=$?
left=$(leftovers "$PIDS")
[ "$rc" = 1 ] && [ -z "$left" ] && [ "$(wc -l < "$PIDS")" -ge 2 ] && ok "P6 poll_until timed out (rc 1) and the predicate's nested subshell and its sleep are gone" || fail "P6 rc=$rc left=[$left] pids=$(wc -l < "$PIDS")"

# P7
PIDS="$WORK/p7.pids"; : > "$PIDS"; export PIDS
( poll_until 60 hang_nested ) &
OWNER=$!
poll_until 15 has_pids "$PIDS" 2
kill -TERM "$OWNER"; settle
left=$(leftovers "$PIDS")
[ -z "$left" ] && ok "P7 TERM to a shell waiting in poll_until: the predicate tree is gone" || fail "P7 left=[$left] rc=$OWNER_RC"

# P8: count what must NOT be there.
body_of() { awk -v f="$2" '$0 ~ "^"f"\\(\\) \\{" {on=1} on {print} on && /^}/ {exit}' "$1"; }
raw='kill -(STOP|CONT|TERM|KILL)|pkill|kill -[1-9]'
n_load=$(grep -cE "$raw" "${INTEG_DIR}/lib/load.sh")
n_poll=$(body_of "${INTEG_DIR}/lib/common.sh" poll_until | grep -cE "$raw")
n_bg=$(body_of "${INTEG_DIR}/lib/common.sh" reap_bg_job | grep -cE "$raw")
n_sdv=$(grep -v '^[[:space:]]*#' "${INTEG_DIR}/test/test-scale-down-victims.sh" | grep -cE "$raw|kill -STOP|kill -CONT")
n_poll_len=$(body_of "${INTEG_DIR}/lib/common.sh" poll_until | wc -l | tr -d ' ')
if [ "$n_load" = 0 ] && [ "$n_poll" = 0 ] && [ "$n_bg" = 0 ] && [ "$n_sdv" = 0 ] && [ "$n_poll_len" -gt 10 ]; then ok "P8 raw kill/pkill outside the reaper: load.sh $n_load, poll_until $n_poll (body of $n_poll_len lines), reap_bg_job $n_bg, test-scale-down-victims.sh $n_sdv"
else fail "P8 load.sh=$n_load poll_until=$n_poll (body $n_poll_len lines) reap_bg_job=$n_bg scale-down-victims=$n_sdv"; fi

echo "  passed: $PASS"; echo "  failed: $FAIL"
[ "$FAIL" -eq 0 ]
