#!/bin/bash
# test-poll-until.sh — stubs only. lib/common.sh poll_until, the wait that replaces fixed sleeps before assertions (#3424).
#   W1 a predicate that turns true later: rc 0, FIRST=1 (the wait was NOT vacuous), ELAPSED >= the delay
#   W2 a predicate already true: rc 0, FIRST=0 (a vacuous wait is visible to the caller)
#   W3 a predicate that never turns true: rc 1 at the deadline (loud, never an endless wait, never a silent pass)
#   W4 arguments reach the predicate; TIMEOUT_SCALE multiplies the deadline
#   W5 a predicate that hangs (30 s) under a 3 s deadline: rc 1 within ~4 s (each call is bounded by the remaining deadline)
#   W6 TIMEOUT_SCALE=0 (a 0 deadline): the predicate still runs once - an always-true one returns 0, a false one 1, a hanging one is cut at ~1 s
#   W7 a hanging predicate with a NESTED subshell leaves no orphan after the timeout (the whole tree is reaped, not only the direct child)
#   Mutations: always-return-0 reddens W3; returning after the first poll reddens W1.   INTEG_DIR_UNDER_TEST selects another copy.
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="${INTEG_DIR_UNDER_TEST:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
export TARGET_HOST=localhost; source "${INTEG_DIR}/lib/common.sh" > /dev/null 2>&1
type poll_until > /dev/null 2>&1 || { echo "  FAIL  poll_until is not defined in lib/common.sh"; echo "  passed: 0"; echo "  failed: 1"; exit 1; }

( sleep 2; : > "$WORK/flag" ) &  # sleep-ok: the delayed event is the fixture
flag_set() { [ -e "$WORK/flag" ]; }
poll_until 20 flag_set; rc=$?
if [ "$rc" -eq 0 ] && [ "$POLL_UNTIL_FIRST" = "1" ] && [ "$POLL_UNTIL_ELAPSED" -ge 1 ]; then ok "W1 turns true after ~2 s: rc 0, first poll false, elapsed=${POLL_UNTIL_ELAPSED}s"
else fail "W1 rc=$rc first=$POLL_UNTIL_FIRST elapsed=${POLL_UNTIL_ELAPSED:-}"; fi
wait

poll_until 20 flag_set; rc=$?
if [ "$rc" -eq 0 ] && [ "$POLL_UNTIL_FIRST" = "0" ]; then ok "W2 already true: rc 0, FIRST=0 (vacuous wait is reported)"; else fail "W2 rc=$rc first=$POLL_UNTIL_FIRST"; fi

never() { return 1; }
start=$SECONDS; poll_until 2 never; rc=$?; took=$((SECONDS - start))
if [ "$rc" -eq 1 ] && [ "$took" -ge 2 ] && [ "$took" -le 6 ]; then ok "W3 never true: rc 1 at the deadline (took ${took}s of 2)"; else fail "W3 rc=$rc took=${took}s"; fi

argp() { [ "$1" = "a b" ] && [ "$2" = "x" ]; }
poll_until 5 argp "a b" x; rc1=$?
start=$SECONDS; TIMEOUT_SCALE=2 poll_until 1 never; rc2=$?; took=$((SECONDS - start))
if [ "$rc1" -eq 0 ] && [ "$rc2" -eq 1 ] && [ "$took" -ge 2 ]; then ok "W4 arguments reach the predicate (quoted arg kept); TIMEOUT_SCALE=2 doubled a 1 s deadline (took ${took}s)"; else fail "W4 rc1=$rc1 rc2=$rc2 took=${took}s"; fi

hang() { sleep 30; }   # sleep-ok: the hanging predicate is the fixture
start=$SECONDS; poll_until 3 hang; rc=$?; took=$((SECONDS - start))
if [ "$rc" -eq 1 ] && [ "$took" -le 5 ]; then ok "W5 a predicate that hangs 30 s under a 3 s deadline is cut: rc 1 after ${took}s, not 30"; else fail "W5 rc=$rc took=${took}s (a hanging predicate overran the deadline)"; fi

# W6
always() { return 0; }
TIMEOUT_SCALE=0 poll_until 5 always; rc_t=$?; f_t=$POLL_UNTIL_FIRST
TIMEOUT_SCALE=0 poll_until 5 never; rc_f=$?
start=$SECONDS; TIMEOUT_SCALE=0 poll_until 5 hang; rc_h=$?; took=$((SECONDS - start))
if [ "$rc_t" -eq 0 ] && [ "$f_t" = "0" ] && [ "$rc_f" -eq 1 ] && [ "$rc_h" -eq 1 ] && [ "$took" -le 4 ]; then ok "W6 TIMEOUT_SCALE=0: always-true rc 0 (evaluated once), false rc 1, hanging cut after ${took}s"
else fail "W6 always rc=$rc_t first=$f_t never rc=$rc_f hang rc=$rc_h took=${took}s"; fi

# W7
nested() { ( echo "$BASHPID" >> "$WORK/nested.pids"; sleep 33 & echo $! >> "$WORK/nested.pids"; wait ); }  # sleep-ok: hanging predicate fixture
: > "$WORK/nested.pids"; export WORK
poll_until 2 nested; rc=$?
alive=0; for p in $(cat "$WORK/nested.pids"); do kill -0 "$p" 2> /dev/null && alive=$((alive + 1)); done
if [ "$rc" -eq 1 ] && [ "$(wc -l < "$WORK/nested.pids")" -ge 2 ] && [ "$alive" -eq 0 ]; then ok "W7 a hanging predicate with a nested subshell: rc 1 and none of its $(wc -l < "$WORK/nested.pids" | tr -d ' ') processes is left"
else fail "W7 rc=$rc alive=$alive of $(wc -l < "$WORK/nested.pids")"; fi

echo "  passed: $PASS"; echo "  failed: $FAIL"
[ "$FAIL" -eq 0 ]
