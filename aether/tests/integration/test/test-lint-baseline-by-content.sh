#!/bin/bash
# test-lint-baseline-by-content.sh — pins #2029: lint-tests.sh waivers are keyed by file + line CONTENT, not line number.
#
# Keyed by `file:LINE`, any edit above a waived line made the waiver stale and the same finding "new": lint-tests.sh
# exited 1, and run-tests.sh (which runs it under `set -euo pipefail`) aborted before provisioning anything. CI never ran
# the linter, so the PR that shifted the line merged green. These probes run the REAL lint-tests.sh over a scratch tree:
#   L1 a baselined finding is green (control: without its baseline entry the same tree is red);
#   L2 inserting lines ABOVE a waived R2 line keeps lint green (the #2029 acceptance);
#   L3 the same for an R1 finding, whose detail text used to cite a second line number;
#   L4 a NEW violation turns lint red and names file:line;
#   L5 editing the waived line voids its waiver (waivers are not blanket per file);
#   L6 two identical flagged lines need two entries (multiset, not set);
#   L7 the shipped baseline carries no line-number keys and is green against the real tree.
#
#   bash aether/tests/integration/test/test-lint-baseline-by-content.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LINT="${INTEG_DIR}/lint-tests.sh"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

# A scratch tree shaped like the repo (lint-tests.sh derives REPO_ROOT as three levels above itself).
TREE=$(mktemp -d)
trap 'rm -rf "$TREE"' EXIT
IT="${TREE}/aether/tests/integration"
SUITE="${IT}/suites/99-fixture/test-fixture.sh"
BASE="${IT}/lint-baseline.txt"
mkdir -p "${IT}/suites/99-fixture" "${IT}/lib"
cp "$LINT" "${IT}/lint-tests.sh"

write_fixture() {
    cat > "$SUITE" <<'F'
#!/bin/bash
test_one() {
    log_warn "soft"
    log_pass "hard"
}
test_two() {
    curl -s localhost 2>/dev/null || true
}
run_test "one" test_one
run_test "two" test_two
F
}

lint() { bash "${IT}/lint-tests.sh" --baseline "$BASE" 2>&1; }
capture() { bash "${IT}/lint-tests.sh" --capture 2>&1; }
# Insert N blank-ish comment lines after the shebang.
shift_down() { local n="$1" i; for ((i = 0; i < n; i++)); do sed -i.bak '1a\
# shifted' "$SUITE"; done; rm -f "${SUITE}.bak"; }

write_fixture
capture > "$BASE"

# L1
if [ "$(wc -l < "$BASE" | tr -d ' ')" = "2" ] && lint > /dev/null; then ok "L1 baselined findings (R1 + R2) are green"; else fail "L1 expected 2 baselined findings and green; got $(wc -l < "$BASE" | tr -d ' ')"; fi
: > "${BASE}.empty"
if bash "${IT}/lint-tests.sh" --baseline "${BASE}.empty" > /dev/null 2>&1; then fail "L1 control: the same tree must be RED without its baseline entries"; else ok "L1 control: same tree is red with an empty baseline"; fi

# L2 / L3 — shift every finding down by 7 lines
shift_down 7
if out=$(lint); then ok "L2 inserting 7 lines above the waived R2 and R1 findings keeps lint green"; else fail "L2 line shift turned lint red: ${out}"; fi
if grep -qE 'sh:[0-9]+ ' "$BASE"; then fail "L3 baseline still carries a line number"; else ok "L3 baseline entries carry no line numbers (R1 detail included)"; fi

# L4 — a new violation
cat >> "$SUITE" <<'F'
test_three() {
    wget -q example 2>/dev/null || true
}
run_test "three" test_three
F
if out=$(lint); then fail "L4 a NEW violation stayed green"; else
    if echo "$out" | grep -q 'NEW finding' && echo "$out" | grep -q 'wget -q example' && echo "$out" | grep -qE 'test-fixture\.sh:[0-9]+'; then ok "L4 a new violation is red and names file:line"; else fail "L4 red but the report does not name the finding: ${out}"; fi
fi

# L5 — editing the waived line voids its waiver
write_fixture
sed -i.bak 's#curl -s localhost 2>/dev/null || true#curl -s localhost:8080 2>/dev/null || true#' "$SUITE"; rm -f "${SUITE}.bak"
if lint > /dev/null; then fail "L5 an edited waived line kept its waiver"; else ok "L5 editing the waived line voids its waiver"; fi

# L6 — multiset
write_fixture
cat >> "$SUITE" <<'F'
test_four() {
    curl -s localhost 2>/dev/null || true
}
run_test "four" test_four
F
if lint > /dev/null; then fail "L6 a second identical flagged line was absorbed by one entry"; else ok "L6 a second identical flagged line needs its own entry"; fi

# L7 — shipped baseline
if grep -qE '\.sh:[0-9]+ ' "${INTEG_DIR}/lint-baseline.txt"; then fail "L7 shipped baseline carries a line-number key"; else ok "L7 shipped baseline carries no line-number keys"; fi
if out=$(bash "$LINT" 2>&1); then ok "L7 shipped baseline is green against the real suites"; else fail "L7 shipped baseline is red: ${out}"; fi

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
