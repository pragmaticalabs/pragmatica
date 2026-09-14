#!/bin/bash
# test-suite-resolution.sh — pins the 02s single-test suite and the prefix-collision boundary.
#
#   bash aether/tests/integration/test/test-suite-resolution.sh
#
# 02s-selfdrain exists so one S19 race round costs one test file instead of seven (run 3:
# 2115s for the full 02-chaos). Adding a directory beside 02-chaos is only safe because
# resolve_suite_dir globs "suites/<prefix>-*" WITH the hyphen — the same reason 02y/02w
# already coexist. If that glob ever loses its hyphen, "--suites 02" starts matching
# 02s-selfdrain and the full chaos suite silently shrinks to one file.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
RT="${INTEG_DIR}/run-tests.sh"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

# The glob under test, replicated. R0 below pins the replica against the real definition, so a
# change to resolve_suite_dir fails this file LOUDLY instead of leaving it testing a stale copy.
resolve() { ls -d "${INTEG_DIR}/suites/${1}-"* 2>/dev/null | head -1; }

echo "suite resolution — the replica matches the real definition"
if grep -qF 'ls -d "${SCRIPT_DIR}/suites/${prefix}-"*' "$RT"; then
    ok "R0 resolve_suite_dir still globs '<prefix>-*' (replica is current)"
else
    fail "R0 resolve_suite_dir changed — this file's replica is stale and its results mean nothing"
fi

echo "suite resolution — prefixes stay disjoint"
got=$(basename "$(resolve 02)" 2>/dev/null)
[ "$got" = "02-chaos" ] && ok "R1 --suites 02 still resolves to 02-chaos" \
                        || fail "R1 --suites 02 resolved to '$got' — the full chaos suite has been hijacked"
got=$(basename "$(resolve 02s)" 2>/dev/null)
[ "$got" = "02s-selfdrain" ] && ok "R2 --suites 02s resolves to 02s-selfdrain" \
                             || fail "R2 --suites 02s resolved to '$got'"

echo "suite resolution — 02s runs on the DESTRUCTIVE cluster"
# Not cosmetic: an unregistered suite falls to cluster A, and this one kills nodes. Running it
# against the non-destructive cluster is the failure the target_cluster comment in run_suite warns about.
if grep -qE '^CLUSTER_B_SUITES=\(.*\b02s\b.*\)' "$RT"; then
    ok "R3 02s is registered in CLUSTER_B_SUITES"
else
    fail "R3 02s missing from CLUSTER_B_SUITES — it would run against cluster A and kill the wrong nodes"
fi

echo "suite resolution — one file, and it is the SAME file as 02-chaos's"
n=$(find "${INTEG_DIR}/suites/02s-selfdrain" -name 'test-*.sh' | wc -l | tr -d ' ')
[ "$n" = "1" ] && ok "R4 02s holds exactly one test file" || fail "R4 02s holds ${n} test files — it exists to hold one"
# R5: 02s must reach 02-chaos's file, and must do so WITHOUT being a second path to those bytes.
# A copy drifts; a SYMLINK is enumerated by tree-walkers as a second path (it made lint count the
# file's findings twice and made test-chaos-harness W1 see SIX gate call sites where the invariant is
# five). An exec wrapper is a real file with no findings and no gate calls.
W="${INTEG_DIR}/suites/02s-selfdrain/test-self-drain-quorum-loss.sh"
if [ -L "$W" ]; then
    fail "R5 02s's entry is a SYMLINK — tree-walking checks will count 02-chaos's file twice (lint findings, W1 gate call sites)"
elif ! { grep -q '^exec ' "$W" && grep -q '02-chaos' "$W" && grep -q 'test-self-drain-quorum-loss.sh' "$W"; }; then
    fail "R5 02s's entry neither execs 02-chaos's file nor is a symlink — it may be a COPY, which drifts"
else
    ok "R5 02s execs 02-chaos's file (one implementation, and not a second path to it)"
fi
# The wrapper must carry no gate call of its own, or W1's invariant of 5 breaks again.
g=$(grep -c '_cloud_reap_after_confirmed_drain[[:space:]]*"' "$W" || true)
[ "$g" = "0" ] && ok "R5b the wrapper contributes no gate call site (W1 invariant intact)" \
               || fail "R5b the wrapper carries ${g} gate call site(s) — W1 expects exactly 5 tree-wide"

echo "suite resolution — 02-chaos is unchanged by all this"
n=$(find "${INTEG_DIR}/suites/02-chaos" -name 'test-*.sh' | wc -l | tr -d ' ')
[ "$n" = "7" ] && ok "R6 02-chaos still holds its 7 test files" \
               || fail "R6 02-chaos holds ${n} test files, expected 7 — the full suite changed size"

echo "lint gate — a symlinked suite is not counted twice"
# The find-based rules (R1/R3/R5) enumerate a symlink as a second path to the same bytes, so before
# `-type f` every finding in the S19 file was counted under BOTH paths and the baseline would have
# recorded 8 R1 findings in a file containing 4 — a permanently wrong denominator in the one
# artifact people read to judge whether things are getting worse. grep -r (R2/R4) never followed
# symlinks, so the gate disagreed with itself about what a "file" is.
LINT="${INTEG_DIR}/lint-tests.sh"
n=$(grep -c 'find "$SUITES_DIR" -type f' "$LINT")
[ "$n" = "3" ] && ok "R7 all three find-based rules carry -type f (got ${n})" \
                || fail "R7 ${n}/3 find-based rules carry -type f — a symlinked suite would be double-counted"

# Behavioural, not just structural: no finding may be attributed to the symlinked path.
lint_out=$(bash "$LINT" 2>&1 || true)
dupes=$(printf '%s\n' "$lint_out" | grep -c '02s-selfdrain' || true)
[ "$dupes" = "0" ] && ok "R8 lint attributes no finding to the 02s path" \
                   || fail "R8 ${dupes} finding(s) attributed to 02s-selfdrain — 02-chaos\x27s file is being counted twice"

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ] || exit 1
