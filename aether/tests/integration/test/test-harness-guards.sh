#!/bin/bash
# test-harness-guards.sh — manual-run probes pinning test-chaos-harness.sh's run guards
# (#1051 round 3). A regression that makes a sourced suite run its live scenario once
# leaked 2,342 processes through a driver that killed only the top PID. These probes
# show the harness fails fast and NAMED instead:
#   G1 deadline: a case that never returns is stopped at CHAOS_HARNESS_DEADLINE_S with
#      a named FAIL, and nothing of its process group survives;
#   G2 re-entry: a harness started from inside a running harness refuses;
#   G3 precondition: a suite without its source guard stops the harness before any
#      case runs; G3c is the control that the same copy WITH the guard passes it;
#   G4 one group: a harness started below a harness stays in its process group, so a
#      group kill reaches every level of an unbounded re-entry;
#   G5 top-PID kill: a driver that kills only the harness's PID (the shape of the 2,342-
#      process leak) still leaves nothing behind — the watchdog takes the group with it;
#   G6 clean exit: at the instant a harness exits normally, nothing is left in its group
#      (the watchdog's tick must not be a child that outlives it);
#   G7 unreaped kill: a killer that KILLs the harness PID and never waits leaves a zombie
#      that `kill -0` still accepts — the watchdog must treat it as gone and kill the group.
# Every probe runs in its own process group under a hard timeout AND a process-count
# ceiling (GUARD_PROBE_PROC_CEILING, default 300) that kills the whole group, so a broken
# guard fails this test as "recursion detected (ceiling)" instead of hanging or forking
# the machine to its process limit.
#
# No external test runner; invoke directly:
#   bash aether/tests/integration/test/test-harness-guards.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
HARNESS="${SCRIPT_DIR}/test-chaos-harness.sh"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

for tool in perl ps; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "  FAIL  ${tool} is required to run the process-group probes"
        echo ""; echo "  ----"; echo "  passed: 0"; echo "  failed: 1"
        exit 1
    fi
done

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# run_grouped <timeout_s> <outfile> <cmd...>: runs cmd as the leader of a new process
# group, stdin </dev/null, output to outfile. Every 0.25s it counts the group's members;
# above the ceiling it kills the whole group and reports rc=CEILING. Prints
# "rc=<n|TIMEOUT|CEILING> secs=<n> left=<n> peak=<n>": left counts processes still in the
# group 1.5s after the command ended, BEFORE this runner's own cleanup kill — i.e. what
# the guard achieved by itself; peak is the largest member count seen.
run_grouped() {
    perl -e '
        use strict; use warnings; use POSIX ":sys_wait_h";
        my ($t, $out, @cmd) = @ARGV;
        my $ceiling = $ENV{GUARD_PROBE_PROC_CEILING} || 300;
        my $topkill_at = $ENV{GUARD_PROBE_TOPKILL_AT};   # G5: kill ONLY the leader at this many seconds
        my $noreap = $ENV{GUARD_PROBE_TOPKILL_NOREAP};   # G7: after that kill, do not waitpid — leave the zombie
        my $settle = defined $ENV{GUARD_PROBE_SETTLE} ? $ENV{GUARD_PROBE_SETTLE} : 1.5;  # drain time before "left" is read; 0 = at exit
        my $start = time;
        my $pid = fork; die "fork: $!" unless defined $pid;
        if (!$pid) {
            setpgrp(0, 0) or die "setpgrp: $!";
            open STDIN, "<", "/dev/null"; open STDOUT, ">", $out or die; open STDERR, ">&STDOUT";
            exec @cmd or exit 127;
        }
        my ($rc, $peak) = (undef, 0);
        # Group members other than a zombie leader: a zombie is not a running process.
        my $members = sub { my $n = 0; for my $l (`ps -A -o pgid=,pid=,stat=`) { my ($g, $p, $st) = split " ", $l; $n++ if $g eq "$pid" && !($p eq "$pid" && $st =~ /^Z/) } $n };
        while (1) {
            my $w = waitpid($pid, WNOHANG);
            if ($w == $pid) { $rc = ($? & 127) ? 128 + ($? & 127) : $? >> 8; last }
            my $n = $members->();
            $peak = $n if $n > $peak;
            if ($n > $ceiling) { kill "KILL", -$pid; waitpid($pid, 0); $rc = "CEILING"; last }
            if (time - $start >= $t) { kill "KILL", -$pid; waitpid($pid, 0); $rc = "TIMEOUT"; last }
            if (defined $topkill_at && time - $start >= $topkill_at) {
                kill "KILL", $pid; undef $topkill_at;
                if ($noreap) { select(undef, undef, undef, $settle); $rc = "NOREAP"; last }
            }
            select(undef, undef, undef, 0.25);
        }
        select(undef, undef, undef, $settle) unless $rc eq "NOREAP";
        my $left = $members->();
        waitpid($pid, 0) if $rc eq "NOREAP";
        kill "KILL", -$pid;
        printf "rc=%s secs=%d left=%d peak=%d\n", $rc, time - $start, $left, $peak;
    ' "$@"
}
field() { printf '%s\n' "$1" | sed -n "s/.*${2}=\([^ ]*\).*/\1/p"; }
# A probe that tripped the ceiling is named as recursion, whatever else it asserted.
ceiling_hit() {
    if [ "$(field "$2" rc)" = "CEILING" ]; then
        fail "$1 recursion detected (ceiling ${GUARD_PROBE_PROC_CEILING:-300}): the group was killed at ${2}"
        return 0
    fi
    return 1
}

# G1 — deadline.
r=$(run_grouped 60 "$WORK/g1.out" env -u CHAOS_HARNESS_ACTIVE -u CHAOS_HARNESS_REGROUPED CHAOS_HARNESS_DEADLINE_S=3 CHAOS_HARNESS_SELFTEST=hang bash "$HARNESS")
if ceiling_hit G1 "$r"; then :
elif [ "$(field "$r" rc)" != "TIMEOUT" ] && [ "$(field "$r" rc)" != 0 ] && [ "$(field "$r" secs)" -lt 30 ] \
    && [ "$(field "$r" left)" = 0 ] && grep -qF "harness guard: deadline of 3s exceeded" "$WORK/g1.out"; then
    ok "G1 a case that never returns is stopped at the 3s deadline with a named FAIL, no process left (${r})"
else
    fail "G1 deadline guard did not stop a hung case cleanly (${r}): $(tail -3 "$WORK/g1.out" | tr '\n' '|')"
fi

# G2 — re-entry.
r=$(run_grouped 60 "$WORK/g2.out" env -u CHAOS_HARNESS_ACTIVE -u CHAOS_HARNESS_REGROUPED CHAOS_HARNESS_SELFTEST=reenter bash "$HARNESS")
if ceiling_hit G2 "$r"; then :
elif [ "$(field "$r" rc)" = 2 ] && [ "$(field "$r" secs)" -lt 30 ] && [ "$(field "$r" left)" = 0 ] \
    && grep -qF "harness guard: re-entered from a running harness" "$WORK/g2.out"; then
    ok "G2 a harness started inside a running harness refuses with rc 2, no process left (${r})"
else
    fail "G2 re-entry guard did not refuse (${r}): $(tail -3 "$WORK/g2.out" | tr '\n' '|')"
fi

# G3 — precondition, on a copy of the integration tree whose self-drain suite lost its
# source guard. The copy is verified by content before it is used.
mkdir -p "$WORK/copy"
cp -R "$INTEG_DIR" "$WORK/copy/integration"
COPY_SUITE="$WORK/copy/integration/suites/02-chaos/test-self-drain-quorum-loss.sh"
GUARD_LINE='if [ "${BASH_SOURCE[0]}" != "$0" ]; then'
awk -v g="$GUARD_LINE" 'skip > 0 { skip--; next } $0 == g { skip = 2; next } { print }' "$COPY_SUITE" > "$COPY_SUITE.new" \
    && mv "$COPY_SUITE.new" "$COPY_SUITE"
orig_count=$(grep -cxF "$GUARD_LINE" "$INTEG_DIR/suites/02-chaos/test-self-drain-quorum-loss.sh")
copy_count=$(grep -cxF "$GUARD_LINE" "$COPY_SUITE")
if [ "$orig_count" != 1 ] || [ "$copy_count" != 0 ]; then
    fail "G3 instrument: expected the guard line once in the suite and 0 times in the copy, got ${orig_count} and ${copy_count}"
else
    r=$(run_grouped 60 "$WORK/g3.out" env -u CHAOS_HARNESS_ACTIVE -u CHAOS_HARNESS_REGROUPED bash "$WORK/copy/integration/test/test-chaos-harness.sh")
    if ceiling_hit G3 "$r"; then :
    elif [ "$(field "$r" rc)" = 1 ] && [ "$(field "$r" secs)" -lt 30 ] && [ "$(field "$r" left)" = 0 ] \
        && grep -qF "suites/02-chaos/test-self-drain-quorum-loss.sh has no source guard" "$WORK/g3.out" \
        && ! grep -q '^  PASS  ' "$WORK/g3.out"; then
        ok "G3 a suite without its source guard stops the harness before any case, named, no process left (${r})"
    else
        fail "G3 precondition did not stop the harness (${r}): $(tail -3 "$WORK/g3.out" | tr '\n' '|')"
    fi
fi

# G3c — control: an untouched copy passes the precondition.
mkdir -p "$WORK/ctl"
cp -R "$INTEG_DIR" "$WORK/ctl/integration"
r=$(run_grouped 60 "$WORK/g3c.out" env -u CHAOS_HARNESS_ACTIVE -u CHAOS_HARNESS_REGROUPED CHAOS_HARNESS_SELFTEST=guards-only bash "$WORK/ctl/integration/test/test-chaos-harness.sh")
if ceiling_hit G3c "$r"; then :
elif [ "$(field "$r" rc)" = 0 ] && [ "$(field "$r" left)" = 0 ] && grep -qF "harness guards ok" "$WORK/g3c.out"; then
    ok "G3c control: the same copy with its source guards passes the precondition (${r})"
else
    fail "G3c control failed (${r}): $(tail -3 "$WORK/g3c.out" | tr '\n' '|')"
fi

# G4 — one group: a harness started below a harness (the re-entry guard deliberately
# bypassed for this probe) reports the same process group as its parent.
r=$(run_grouped 60 "$WORK/g4.out" env -u CHAOS_HARNESS_ACTIVE -u CHAOS_HARNESS_REGROUPED CHAOS_HARNESS_SELFTEST=nested-pgid bash "$HARNESS")
outer=$(sed -n 's/^  outer harness pgid \([0-9]*\)$/\1/p' "$WORK/g4.out")
inner=$(sed -n 's/^  inner harness pgid \([0-9]*\)$/\1/p' "$WORK/g4.out")
if ceiling_hit G4 "$r"; then :
elif [ "$(field "$r" rc)" = 0 ] && [ -n "$outer" ] && [ "$outer" = "$inner" ] && [ "$(field "$r" left)" = 0 ]; then
    ok "G4 a harness started below a harness stays in its process group (${outer} = ${inner}) (${r})"
else
    fail "G4 a nested harness left its parent's process group (outer='${outer}' inner='${inner}') (${r}): $(tail -3 "$WORK/g4.out" | tr '\n' '|')"
fi

# G5 — top-PID kill. The hung harness's own PID is killed at 3s with a 60s deadline still far off;
# the watchdog must notice the harness is gone and kill the group, so nothing is left.
r=$(GUARD_PROBE_TOPKILL_AT=3 GUARD_PROBE_SETTLE=4 run_grouped 30 "$WORK/g5.out" env -u CHAOS_HARNESS_ACTIVE -u CHAOS_HARNESS_REGROUPED CHAOS_HARNESS_DEADLINE_S=60 CHAOS_HARNESS_SELFTEST=hang bash "$HARNESS")
if ceiling_hit G5 "$r"; then :
elif [ "$(field "$r" rc)" = 137 ] && [ "$(field "$r" secs)" -lt 20 ] && [ "$(field "$r" left)" = 0 ]; then
    ok "G5 a driver that kills only the harness PID leaves no process behind — the watchdog took the group (${r})"
else
    fail "G5 killing only the harness PID left its group running (${r}): $(tail -3 "$WORK/g5.out" | tr '\n' '|')"
fi

# G6 — clean exit. A harness that runs its guards and exits 0 must leave its group empty AT THAT
# INSTANT (settle 0): the runner in CI reads the group right after the leader exits, and a watchdog
# tick implemented as a `sleep` child survives the exit trap for up to a second.
r=$(GUARD_PROBE_SETTLE=0 run_grouped 30 "$WORK/g6.out" env -u CHAOS_HARNESS_ACTIVE -u CHAOS_HARNESS_REGROUPED CHAOS_HARNESS_SELFTEST=guards-only bash "$HARNESS")
if ceiling_hit G6 "$r"; then :
elif [ "$(field "$r" rc)" = 0 ] && [ "$(field "$r" left)" = 0 ]; then
    ok "G6 a harness that exits normally leaves nothing in its group at the instant it exits (${r})"
else
    fail "G6 a normal exit left a process in the harness group (${r}): $(tail -3 "$WORK/g6.out" | tr '\n' '|')"
fi

# G7 — unreaped kill. The harness PID is KILLed at 3s and deliberately NOT reaped for 6s, so it sits
# as a zombie that `kill -0` still accepts; the watchdog must still see it as gone and empty the group.
r=$(GUARD_PROBE_TOPKILL_AT=3 GUARD_PROBE_TOPKILL_NOREAP=1 GUARD_PROBE_SETTLE=6 run_grouped 30 "$WORK/g7.out" env -u CHAOS_HARNESS_ACTIVE -u CHAOS_HARNESS_REGROUPED CHAOS_HARNESS_DEADLINE_S=60 CHAOS_HARNESS_SELFTEST=hang bash "$HARNESS")
if ceiling_hit G7 "$r"; then :
elif [ "$(field "$r" rc)" = NOREAP ] && [ "$(field "$r" left)" = 0 ]; then
    ok "G7 a killer that never reaps the harness still leaves no process behind — a zombie counts as gone (${r})"
else
    fail "G7 an unreaped (zombie) harness left its group running (${r}): $(tail -3 "$WORK/g7.out" | tr '\n' '|')"
fi

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
