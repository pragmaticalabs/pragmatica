#!/bin/bash
# test-deploy-id-guards.sh — stub pins for the deployment-id guards in lib/cluster.sh (#1476, harness part).
#
# No external test runner; invoke directly:
#   bash aether/tests/integration/test/test-deploy-id-guards.sh
#
# In the rc4 baseline run a refused strategy start (409) returned no deploymentId, and the suite then
# interpolated the empty id into `/api/v1/deploy/promote/`, getting a 404 that read as a missing route.
# What this pins:
#   E1  deploy_extract_id on a response with no deploymentId returns non-zero, prints nothing on stdout
#       (callers capture stdout), and names the response on stderr;
#   E2  control: a response WITH a deploymentId returns 0 and prints exactly that id;
#   R1  deploy_promote / deploy_rollback / deploy_complete / deploy_status with an empty id return
#       non-zero and issue NO request (api_post/api_get are stubbed to record calls);
#   R2  control: the same helpers with a real id DO issue the request, so R1 cannot pass because the
#       stubs never record anything.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# common.sh requires TARGET_HOST under `set -u`.
export TARGET_HOST="deploy-id-guards-test"

# shellcheck source=../lib/cluster.sh
source "${INTEG_DIR}/lib/cluster.sh"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
CALLS="${WORK}/calls"
: > "$CALLS"

# Stubs: record every request instead of sending it.
api_post() { echo "POST $1" >> "$CALLS"; echo '{}'; }
api_get()  { echo "GET $1" >> "$CALLS"; echo '{}'; }

REFUSED='{"type":"about:blank","title":"Conflict","status":409,"detail":"No current version for org.example:svc (initial deployment)"}'

echo "deploy_extract_id"
out=$(deploy_extract_id "$REFUSED" 2> "${WORK}/e1.err"); rc=$?
if [ "$rc" -ne 0 ] && [ -z "$out" ] && grep -q 'no deploymentId in the response' "${WORK}/e1.err" && grep -q '409' "${WORK}/e1.err"; then
    ok "E1 an id-less response fails at extraction, quoting the response on stderr"
else
    fail "E1 expected rc!=0, empty stdout and a stderr diagnostic quoting the response; got rc=${rc} out='${out}' err='$(cat "${WORK}/e1.err")'"
fi

out=$(deploy_extract_id '{"deploymentId":"dep-42","state":"DEPLOYED"}' 2> /dev/null); rc=$?
if [ "$rc" -eq 0 ] && [ "$out" = "dep-42" ]; then
    ok "E2 control: a response with an id yields exactly that id"
else
    fail "E2 expected rc=0 and 'dep-42'; got rc=${rc} out='${out}'"
fi

echo "request helpers"
for helper in deploy_promote deploy_rollback deploy_complete deploy_status; do
    : > "$CALLS"
    "$helper" "" > /dev/null 2> "${WORK}/${helper}.err"; rc=$?
    if [ "$rc" -ne 0 ] && [ ! -s "$CALLS" ] && grep -q 'empty deployment id' "${WORK}/${helper}.err"; then
        ok "R1 ${helper} with an empty id refuses and issues no request"
    else
        fail "R1 ${helper} expected rc!=0, no request and a named refusal; got rc=${rc} calls='$(cat "$CALLS")'"
    fi

    : > "$CALLS"
    "$helper" "dep-42" > /dev/null 2>&1; rc=$?
    if [ "$rc" -eq 0 ] && grep -q 'dep-42' "$CALLS"; then
        ok "R2 control: ${helper} with a real id issues its request"
    else
        fail "R2 ${helper} with id dep-42 expected rc=0 and a recorded request; got rc=${rc} calls='$(cat "$CALLS")'"
    fi
done

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
