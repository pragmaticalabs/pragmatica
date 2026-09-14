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
link=$(readlink "${INTEG_DIR}/suites/02s-selfdrain/test-self-drain-quorum-loss.sh" 2>/dev/null)
[ "$link" = "../02-chaos/test-self-drain-quorum-loss.sh" ] \
    && ok "R5 it is a symlink to 02-chaos's copy (single source of truth)" \
    || fail "R5 not the expected symlink (got '${link:-<not a symlink>}') — a copy would drift from 02-chaos"

echo "suite resolution — 02-chaos is unchanged by all this"
n=$(find "${INTEG_DIR}/suites/02-chaos" -name 'test-*.sh' | wc -l | tr -d ' ')
[ "$n" = "7" ] && ok "R6 02-chaos still holds its 7 test files" \
               || fail "R6 02-chaos holds ${n} test files, expected 7 — the full suite changed size"

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ] || exit 1
