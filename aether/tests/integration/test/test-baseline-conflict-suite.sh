#!/bin/bash
# test-baseline-conflict-suite.sh — pins that suites/10-database/test-schema-baseline-conflict.sh
# asserts the already-migrated baseline contract (stubs only: no cluster, no cloud). The real script
# runs against the REAL `_api_call` (extracted verbatim from lib/common.sh) and real json_value, with
# a stub `curl` playing the server:
#   B1  server answers 409 "already applied up to version 900" twice        -> every test passes
#   B2  server answers 200 to the baseline (datasource was not migrated)    -> premise/contract test FAILS
#   B3  server answers 409 first, then 200 (not repeatable)                  -> repeatability test FAILS
#   B4  server answers 409 twice but a POST moves currentVersion             -> unchanged-version test FAILS
#   B5  server answers 409 naming the WRONG version                          -> contract test FAILS
#   B6  first 409 correct, SECOND 409 names another version                 -> repeatability test FAILS
#   B7  first call SchemaNotLeader 409, then the real conflict (retry)       -> every test passes
#   B8  SchemaNotLeader 409 forever                                          -> contract test FAILS (never a pass)
# SCRIPT_UNDER_TEST=<path> selects another copy (mutation probe against the pre-change script).
#   bash aether/tests/integration/test/test-baseline-conflict-suite.sh
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SUT="${SCRIPT_UNDER_TEST:-${INTEG_DIR}/suites/10-database/test-schema-baseline-conflict.sh}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d); [ -n "${KEEP:-}" ] && echo "WORK=$WORK" >&2
trap '[ -n "${KEEP:-}" ] || rm -rf "$WORK"' EXIT
mkdir -p "$WORK/suites/10-database" "$WORK/lib" "$WORK/bin"
cp "$SUT" "$WORK/suites/10-database/script.sh"
cp "${INTEG_DIR}/lib/json.sh" "$WORK/lib/json.sh"

{
cat <<'STUB'
log_info() { echo "INFO $*"; }
log_warn() { echo "WARN $*" >&2; }
log_fail() { echo "FAIL $*"; }
log_pass() { echo "PASS $*"; }
run_test() { if "$2"; then echo "RUN-OK $1"; else echo "RUN-FAILED $1"; fi; }
print_summary() { :; }
assert_gt() { :; }
assert_cluster_healthy() { :; }
wait_for() { local i; for i in 1 2 3; do eval "$2" && return 0; done; return 1; }
wait_for_cluster_ready() { :; }
push_blueprint() { :; }
deploy_blueprint() { :; }
slices_total_instances() { echo 3; }
_resolve_live_endpoint() { echo "http://stub"; }
API_KEY=k; CLOUD_MODE=false
source "$(dirname "${BASH_SOURCE[0]}")/json.sh"
STUB
# the real transport, verbatim
sed -n '/^_api_call() {/,/^}/p' "${INTEG_DIR}/lib/common.sh"
} > "$WORK/lib/common.sh"
cat > "$WORK/lib/cluster.sh" <<'STUB'
schema_status() {
    if [ -n "${1:-}" ]; then
        printf '{"datasource":"%s","status":"COMPLETED","currentVersion":%s}' "$1" "$(cat "$WORK/version")"
    else
        printf '[{"datasource":"database.testpersistence","status":"COMPLETED","currentVersion":%s}]' "$(cat "$WORK/version")"
    fi
}
api_get() { schema_status; }
cluster_leader_http() { echo node-1; }
MGMT_PORT=5151
BASELINE_RETRY_SLEEP=0
STUB

# Stub curl: only the baseline POST is served here; the response depends on $SCEN and the call count.
cat > "$WORK/bin/curl" <<'STUB'
#!/bin/bash
n=$(( $(cat "$WORK/calls" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$WORK/calls"
conflict() { printf '{"type":"about:blank","title":"Conflict","status":409,"detail":"Baseline conflict for datasource '"'"'database.testpersistence'"'"': versioned migrations already applied up to version %s","requestId":"mgmt"}\n__API_HTTP_STATUS:409__' "$1"; }
notleader() { cat <<'JSON'
{"type":"about:blank","title":"Conflict","status":409,"detail":"Schema baseline for datasource 'database.testpersistence' requires the leader node — current leader: node-2","requestId":"mgmt"}
__API_HTTP_STATUS:409__
JSON
}
case "$SCEN" in
    conflict)  conflict 900 ;;
    ok200)     printf '{"status":"BASELINED"}\n__API_HTTP_STATUS:200__' ;;
    nonidem)   if [ "$n" -eq 1 ]; then conflict 900; else printf '{"status":"BASELINED"}\n__API_HTTP_STATUS:200__'; fi ;;
    moves)     conflict 900; echo 950 > "$WORK/version" ;;
    wrongver)  conflict 1 ;;
    secondwrong) if [ "$n" -eq 1 ]; then conflict 900; else conflict 950; fi ;;
    notleader) if [ "$n" -eq 1 ]; then notleader; else conflict 900; fi ;;
    notleader_forever) notleader ;;
esac
STUB
chmod +x "$WORK/bin/curl"

run() {  # <scenario> -> $WORK/out.<scenario>
    echo 900 > "$WORK/version"; echo 0 > "$WORK/calls"
    env WORK="$WORK" SCEN="$1" PATH="$WORK/bin:$PATH" TARGET_HOST=stub \
        bash "$WORK/suites/10-database/script.sh" > "$WORK/out.$1" 2>&1
}
failed_tests() { grep '^RUN-FAILED' "$WORK/out.$1" | sed 's/^RUN-FAILED //' | tr '\n' '|'; }
ran() { grep -c '^RUN-' "$WORK/out.$1"; }

run conflict
if [ "$(ran conflict)" = "7" ] && [ -z "$(failed_tests conflict)" ]; then ok "B1 conflict server: 7 tests ran, none failed"
else fail "B1 ran=$(ran conflict) failed=[$(failed_tests conflict)]"; fi

# The failed set must be EXACTLY the named tests: a superset would let an unrelated failure (a broken
# stub, a typo) pass as the reddening under test.
R1="Baseline refused on migrated datasource (409)"; R2="Refused baseline is repeatable (identical 409)"; R3="Version unchanged after refused baselines"
expect_red() {  # <scenario> <label> <exact failed set, '|'-terminated>
    run "$1"
    if [ "$(ran "$1")" = "7" ] && [ "$(failed_tests "$1")" = "$3" ]; then ok "$2 reddens exactly: $3"
    else fail "$2 ran=$(ran "$1") failed=[$(failed_tests "$1")] wanted=[$3]"; fi
}
expect_red ok200 "B2 200-on-baseline" "${R1}|${R2}|"
expect_red nonidem "B3 non-idempotent second call" "${R2}|"
expect_red moves "B4 version moved by a POST" "${R3}|"
expect_red wrongver "B5 wrong version in the 409" "${R1}|${R2}|"
expect_red secondwrong "B6 second 409 differs from the first" "${R2}|"
expect_red notleader_forever "B8 SchemaNotLeader never resolves" "${R1}|${R2}|"
run notleader
if [ "$(ran notleader)" = "7" ] && [ -z "$(failed_tests notleader)" ] && grep -q 'SchemaNotLeader at http://stub:5151' "$WORK/out.notleader"; then
    ok "B7 SchemaNotLeader then leader: retried against the leader endpoint, 7 tests passed"
else fail "B7 ran=$(ran notleader) failed=[$(failed_tests notleader)]"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
