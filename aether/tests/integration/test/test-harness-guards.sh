#!/bin/bash
# test-harness-guards.sh — manual-run probes pinning test-chaos-harness.sh's run guards
# (#1051 round 3). A regression that makes a sourced suite run its live scenario once
# leaked 2,342 processes through a driver that killed only the top PID. These probes
# show the harness fails fast and NAMED instead:
#   G1 deadline: a case that never returns is stopped at CHAOS_HARNESS_DEADLINE_S with
#      a named FAIL, and nothing of its process group survives;
#   G2 re-entry: a harness started from inside a running harness refuses;
#   G3 precondition: a suite without its source guard stops the harness before any
#      case runs; G3c is the control that the same copy WITH the guard passes it.
# Every probe runs in its own process group under a hard timeout that kills the whole
# group, so a broken guard fails this test instead of hanging or leaking.
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
# group, stdin </dev/null, output to outfile. Prints "rc=<n|TIMEOUT> secs=<n> left=<n>":
# left counts processes still in that group 1.5s after the command ended, BEFORE this
# runner's own cleanup kill — i.e. what the guard achieved by itself.
run_grouped() {
    perl -e '
        use strict; use warnings; use POSIX ":sys_wait_h";
        my ($t, $out, @cmd) = @ARGV;
        my $start = time;
        my $pid = fork; die "fork: $!" unless defined $pid;
        if (!$pid) {
            setpgrp(0, 0) or die "setpgrp: $!";
            open STDIN, "<", "/dev/null"; open STDOUT, ">", $out or die; open STDERR, ">&STDOUT";
            exec @cmd or exit 127;
        }
        my $rc;
        while (1) {
            my $w = waitpid($pid, WNOHANG);
            if ($w == $pid) { $rc = ($? & 127) ? 128 + ($? & 127) : $? >> 8; last }
            if (time - $start >= $t) { kill "KILL", -$pid; waitpid($pid, 0); $rc = "TIMEOUT"; last }
            select(undef, undef, undef, 0.2);
        }
        select(undef, undef, undef, 1.5);
        my $left = 0;
        for my $l (`ps -A -o pgid=`) { $l =~ s/\s//g; $left++ if $l eq "$pid" }
        kill "KILL", -$pid;
        printf "rc=%s secs=%d left=%d\n", $rc, time - $start, $left;
    ' "$@"
}
field() { printf '%s\n' "$1" | sed -n "s/.*${2}=\([^ ]*\).*/\1/p"; }

# G1 — deadline.
r=$(run_grouped 60 "$WORK/g1.out" env -u CHAOS_HARNESS_ACTIVE CHAOS_HARNESS_DEADLINE_S=3 CHAOS_HARNESS_SELFTEST=hang bash "$HARNESS")
if [ "$(field "$r" rc)" != "TIMEOUT" ] && [ "$(field "$r" rc)" != 0 ] && [ "$(field "$r" secs)" -lt 30 ] \
    && [ "$(field "$r" left)" = 0 ] && grep -qF "harness guard: deadline of 3s exceeded" "$WORK/g1.out"; then
    ok "G1 a case that never returns is stopped at the 3s deadline with a named FAIL, no process left (${r})"
else
    fail "G1 deadline guard did not stop a hung case cleanly (${r}): $(tail -3 "$WORK/g1.out" | tr '\n' '|')"
fi

# G2 — re-entry.
r=$(run_grouped 60 "$WORK/g2.out" env -u CHAOS_HARNESS_ACTIVE CHAOS_HARNESS_SELFTEST=reenter bash "$HARNESS")
if [ "$(field "$r" rc)" = 2 ] && [ "$(field "$r" secs)" -lt 30 ] && [ "$(field "$r" left)" = 0 ] \
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
    r=$(run_grouped 60 "$WORK/g3.out" env -u CHAOS_HARNESS_ACTIVE bash "$WORK/copy/integration/test/test-chaos-harness.sh")
    if [ "$(field "$r" rc)" = 1 ] && [ "$(field "$r" secs)" -lt 30 ] && [ "$(field "$r" left)" = 0 ] \
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
r=$(run_grouped 60 "$WORK/g3c.out" env -u CHAOS_HARNESS_ACTIVE CHAOS_HARNESS_SELFTEST=guards-only bash "$WORK/ctl/integration/test/test-chaos-harness.sh")
if [ "$(field "$r" rc)" = 0 ] && [ "$(field "$r" left)" = 0 ] && grep -qF "harness guards ok" "$WORK/g3c.out"; then
    ok "G3c control: the same copy with its source guards passes the precondition (${r})"
else
    fail "G3c control failed (${r}): $(tail -3 "$WORK/g3c.out" | tr '\n' '|')"
fi

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
