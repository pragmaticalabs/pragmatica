#!/bin/bash
# test-suite-polls.sh — stubs only. The suite sites that poll instead of sleeping (#3424, #3427 round 1) wait on the artifact their
# assertion reads and fail loudly when the wait times out. Functions are extracted from the suite files (the files run their
# tests when sourced), then run against stubbed cluster reads.
#   S1 apply: test_config_converges returns only once the marker is readable on EVERY node (a healthy cluster is not the event)
#   S2 export: test_config_identical_after_reapply waits for the canonical equality it asserts (a stale second export is not a pass or a fail)
#   S3 pub-sub / S4 traces: a timed-out wait is a FAIL at the wait, rc 1 (never the unconditional log_pass)
#   Mutations: S1 polling cluster_is_healthy reddens S1; S2 polling non-emptiness reddens S2; S3/S4 ignoring poll_until's rc redden S3/S4.
#   INTEG_DIR_UNDER_TEST selects another copy of aether/tests/integration.
set -uo pipefail
unset AETHER_SSH_USER HCLOUD_TOKEN
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="${INTEG_DIR_UNDER_TEST:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
export TARGET_HOST=localhost; source "${INTEG_DIR}/lib/common.sh" > /dev/null 2>&1
source "${INTEG_DIR}/lib/cluster.sh" > /dev/null 2>&1
# fn <file> <name>: the function definition (from `name() {` to the closing `}` at column 0).
fn() { awk -v n="$2" '$0 ~ "^"n"\\(\\) *\\{" {p=1} p {print} p && /^}/ {exit}' "${INTEG_DIR}/suites/$1"; }
load() { local f=$1; shift; local n; for n in "$@"; do eval "$(fn "$f" "$n")"; done; }
LOGS="$WORK/logs"; : > "$LOGS"
log_fail() { echo "FAIL: $*" >> "$LOGS"; }; log_pass() { echo "PASS: $*" >> "$LOGS"; }; log_info() { :; }
assert_cluster_healthy() { :; }
NODE_COUNT=3

# S1 — the marker shows on node 3 only 3 s after the test starts; the cluster is healthy from the first instant.
load 07-cluster-mgmt/test-apply.sh _marker_on_all_nodes test_config_converges
cluster_is_healthy() { return 0; }
node_api_get() { if [ "$1" = 2 ] && [ ! -e "$WORK/marker" ]; then printf '{}'; else printf '{"test.integration.marker":"applied"}'; fi; }
json_value() { printf '%s' "$1" | grep -oE "\"$2\":\"[^\"]*\"" | sed -E 's/.*:"([^"]*)"/\1/'; }
( sleep 3; : > "$WORK/marker" ) &  # sleep-ok: the delayed propagation is the fixture
test_config_converges > /dev/null 2>&1
if [ -e "$WORK/marker" ]; then ok "S1 test_config_converges returned only after the marker reached every node"; else fail "S1 returned before the marker reached node 3 (the wait was vacuous)"; fi
wait

# S2 — the second export carries a stale extra key for 3 s after the re-apply, then equals the first.
load 07-cluster-mgmt/test-export.sh _canonical_config _export_has_roundtrip_key _export_canonical_equals test_config_identical_after_reapply
CFG='{"a":"1","test.export.roundtrip":"reapplied"}'
config_export() { if [ -e "$WORK/applied" ] && [ ! -e "$WORK/settled" ]; then printf '{"a":"1","stale":"x","test.export.roundtrip":"reapplied"}'; else printf '%s' "$CFG"; fi; }
config_apply() { : > "$WORK/applied"; ( sleep 3; : > "$WORK/settled" ) > /dev/null 2>&1 & printf '{"ok":true}'; }  # sleep-ok: the delayed settle is the fixture
: > "$LOGS"; test_config_identical_after_reapply > /dev/null 2>&1; rc=$?
if [ "$rc" -eq 0 ] && ! grep -q '^FAIL' "$LOGS"; then ok "S2 the re-apply export is awaited until it equals the first (stale body for 3 s did not fail the test)"
else fail "S2 rc=$rc $(head -c 200 "$LOGS")"; fi
wait

# S3 — the events never become readable: the wait itself fails, rc 1, and no later assertion is reached.
load 08-resources/test-pub-sub.sh _pubsub_events_delivered test_subscriber_receives_events
STREAM_NAME=s; _publish_with_retry() { return 0; }; now_epoch() { echo 1; }
stream_identity() { echo id; }; aether_json() { printf '[]'; }
assert_eq() { return 0; }; assert_ne() { return 0; }
: > "$LOGS"; TIMEOUT_SCALE=0 test_subscriber_receives_events > /dev/null 2>&1; rc=$?
if [ "$rc" -ne 0 ] && grep -q '^FAIL: Fewer than 10 events' "$LOGS"; then ok "S3 pub-sub: events never readable -> FAIL at the wait, rc $rc"
else fail "S3 rc=$rc logs=$(head -c 200 "$LOGS")"; fi

# S4 — the injected traces never become visible: FAIL at the wait, never the unconditional "Injected 3 synthetic traces" pass.
load 11-observability/test-invocation-traces.sh _injected_traces_visible test_generate_traceable_requests
_inject_trace() { printf 'req-%s' "$2"; }
api_get() { printf '{"traces":[]}'; }
TRACE_OP_1=a; TRACE_OP_2=b; TRACE_OP_3=c
: > "$LOGS"; TIMEOUT_SCALE=0 test_generate_traceable_requests > /dev/null 2>&1; rc=$?
if [ "$rc" -ne 0 ] && grep -q '^FAIL: Injected traces not visible' "$LOGS" && ! grep -q '^PASS: Injected 3' "$LOGS"; then ok "S4 traces: never visible -> FAIL at the wait, no unconditional pass"
else fail "S4 rc=$rc logs=$(head -c 300 "$LOGS")"; fi

echo "  passed: $PASS"; echo "  failed: $FAIL"
[ "$FAIL" -eq 0 ]
