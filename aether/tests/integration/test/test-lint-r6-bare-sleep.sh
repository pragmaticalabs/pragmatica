#!/bin/bash
# test-lint-r6-bare-sleep.sh — pins lint-tests.sh R6 (#3424): a bare sleep in a stub suite (test/*.sh) is a finding.
# Each planted form runs the REAL lint-tests.sh over a scratch tree holding only that line, so a miss names the form:
#   R1 every way to write a bare sleep is flagged (a weakened sleep regex, a comment-strip that eats the line, or a path
#      prefix the regex excludes turns the matching form green and fails here);
#   R2 the forms that must NOT be flagged stay clean (a sleep inside quoted text or a real comment, a marked `# sleep-ok: why`);
#   R3 the real test/*.sh tree carries no R6 finding.
#
#   bash aether/tests/integration/test/test-lint-r6-bare-sleep.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LINT="${INTEG_DIR}/lint-tests.sh"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

TREE=$(mktemp -d)
trap 'rm -rf "$TREE"' EXIT
IT="${TREE}/aether/tests/integration"
mkdir -p "${IT}/test" "${IT}/suites" "${IT}/lib"
cp "$LINT" "${IT}/lint-tests.sh"

# r6_count <fixture body>: how many R6 findings the real linter reports for a stub suite with this body.
r6_count() {
    printf '%s\n' "$1" > "${IT}/test/test-fixture.sh"
    bash "${IT}/lint-tests.sh" --capture 2>&1 | grep -c '^\[R6\]'
}
# control: an obviously bare sleep must count 1, or every "clean" verdict below proves nothing.
if [ "$(r6_count 'sleep 2')" = "1" ]; then ok "R0 control: a plain 'sleep 2' is exactly one R6 finding"; else fail "R0 control: the linter did not flag 'sleep 2': every verdict below is void"; fi

flagged() {  # <name> <body>
    local n; n=$(r6_count "$2")
    if [ "$n" = "1" ]; then ok "R1 flagged: $1"; else fail "R1 not flagged ($n findings): $1  <<$2>>"; fi
}
clean() {  # <name> <body>
    local n; n=$(r6_count "$2")
    if [ "$n" = "0" ]; then ok "R2 clean: $1"; else fail "R2 wrongly flagged ($n findings): $1  <<$2>>"; fi
}

flagged "sleep N;"                       'sleep 2;'
flagged "sleep \"\$X\""                  'sleep "$X"'
flagged "sleep \$X"                      'sleep $X'
flagged "fractional sleep 0.5"           'sleep 0.5'
flagged "inside a function"              $'helper() {\n    sleep 3\n}'
flagged "/bin/sleep N"                   '/bin/sleep 9'
flagged "/usr/bin/sleep N"               '/usr/bin/sleep 1'
flagged "after a quoted # (double)"      'echo "step #1"; sleep 3'
flagged "after a quoted # (single)"      "echo 'step #1'; sleep 3"
flagged "a # inside a word is not a comment"   'echo a#b; sleep 3'
flagged "quoted argument 'sleep 0.5'"      "sleep '0.5'"
flagged "sleep in a \$( ) inside double quotes" 'x="$(sleep 2)"'
flagged "a sleep-ok marker inside a string is not a marker" 'echo "# sleep-ok: x"; sleep 3'
flagged "command sleep"                  'command sleep 3'
flagged "after && "                      'true && sleep 3'
flagged "in a subshell"                  '(sleep 4)'
flagged "a sleep-ok marker with no reason" 'sleep 3  # sleep-ok:'

clean "marked '# sleep-ok: reason'"      'sleep 3  # sleep-ok: paces the stub'
clean "sleep inside a string"            'echo "sleep 14"'
clean "pure comment"                     '# sleep 3 would be a race here'
clean "trailing comment"                 'x=1  # then sleep 3'
clean "comment after a quoted #"         'echo "a #b"  # sleep 3 is not run'
clean "sleep inside a string, after a word"   'echo "wait a sleep 3 now"'
clean "sleep inside single quotes"             "echo 'wait: sleep 3'"
clean "printf with sleep text"                 'printf "%s\\n" "sleep 3 seconds"'
clean "double-quoted text around a \$( ) that has no sleep" 'x="a sleep 3 $(date) b"'
clean "poll_until"                       'poll_until 5 true'

# R3 — the real tree
real=$(bash "$LINT" --capture 2>&1 | grep -c '^\[R6\]')
if [ "$real" = "0" ]; then ok "R3 the real test/*.sh tree has no R6 finding"; else fail "R3 the real tree carries $real R6 finding(s)"; fi

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
