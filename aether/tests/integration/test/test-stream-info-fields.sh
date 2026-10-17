#!/bin/bash
# test-stream-info-fields.sh — stub pins for the stream-info helpers in lib/cluster.sh (#1478, harness part).
#
# No external test runner; invoke directly:
#   bash aether/tests/integration/test/test-stream-info-fields.sh
#
# 04-streaming asked the catalog METADATA route for `totalEvents` and `name`. That body carries neither
# (its fields are `stream`, `partitionCount`, ...), and `${msg_count:-0}` rendered the absent field as a
# measured 0, so the failure read as "the product reports zero events". What this pins:
#   F1  a /info body WITHOUT totalEvents makes stream_total_events fail, print nothing on stdout, and name
#       "field absent" on stderr — never a fabricated 0;
#   F2  control: a body WITH totalEvents 20 prints exactly 20 (F1 cannot pass because the stub is deaf);
#   F3  control: a body with a genuinely MEASURED totalEvents 0 prints 0 and returns 0 — absent and zero
#       stay distinguishable;
#   F4  stream_total_events asks /info, not the metadata route;
#   N1  a metadata body WITHOUT `stream` makes stream_declared_name fail naming "field absent";
#   N2  control: a body with "stream":"test-events" prints test-events.
# api_get is stubbed to serve a canned body per route; no cluster is involved.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# common.sh requires TARGET_HOST under `set -u`.
export TARGET_HOST="stream-info-fields-test"

# shellcheck source=../lib/cluster.sh
source "${INTEG_DIR}/lib/cluster.sh"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
CALLS="${WORK}/calls"
: > "$CALLS"

CATALOG='{"streams":[{"namespace":"integration","stream":"test-events","version":"1.0.0"}]}'
INFO_BODY=""
META_BODY=""

# The catalog lists the stream; /info and the metadata route answer the canned bodies below.
api_get() {
    echo "GET $1" >> "$CALLS"
    case "$1" in
        /api/v1/streams) printf '%s' "$CATALOG" ;;
        */info) printf '%s' "$INFO_BODY" ;;
        *) printf '%s' "$META_BODY" ;;
    esac
}

NO_TOTAL='{"namespace":"integration","stream":"test-events","version":"1.0.0","refCount":1,"partitionCount":1}'
WITH_TOTAL='{"name":"integration:test-events:1.0.0","partitions":1,"totalEvents":20,"totalBytes":4096,"partitionDetails":[]}'
ZERO_TOTAL='{"name":"integration:test-events:1.0.0","partitions":1,"totalEvents":0,"totalBytes":4096,"partitionDetails":[]}'

echo "stream_total_events"
INFO_BODY="$NO_TOTAL"
out=$(stream_total_events test-events 2> "${WORK}/f1.err"); rc=$?
if [ "$rc" -ne 0 ] && [ -z "$out" ] && grep -q 'field absent' "${WORK}/f1.err" && ! grep -q "got '0'" "${WORK}/f1.err"; then
    ok "F1 a body with no totalEvents fails with 'field absent' and prints no count"
else
    fail "F1 expected rc!=0, empty stdout, 'field absent' on stderr; got rc=${rc} out='${out}' err='$(cat "${WORK}/f1.err")'"
fi

INFO_BODY="$WITH_TOTAL"
out=$(stream_total_events test-events 2> "${WORK}/f2.err"); rc=$?
if [ "$rc" -eq 0 ] && [ "$out" = "20" ]; then
    ok "F2 control: totalEvents 20 prints 20"
else
    fail "F2 expected rc=0 out=20; got rc=${rc} out='${out}' err='$(cat "${WORK}/f2.err")'"
fi

INFO_BODY="$ZERO_TOTAL"
out=$(stream_total_events test-events 2> "${WORK}/f3.err"); rc=$?
if [ "$rc" -eq 0 ] && [ "$out" = "0" ]; then
    ok "F3 control: a measured totalEvents 0 prints 0 and succeeds"
else
    fail "F3 expected rc=0 out=0; got rc=${rc} out='${out}' err='$(cat "${WORK}/f3.err")'"
fi

: > "$CALLS"
INFO_BODY="$WITH_TOTAL"
stream_total_events test-events > /dev/null 2>&1
if grep -qx 'GET /api/v1/streams/integration/test-events/1.0.0/info' "$CALLS"; then
    ok "F4 stream_total_events asks the /info route"
else
    fail "F4 expected a GET of .../1.0.0/info; calls: $(tr '\n' '|' < "$CALLS")"
fi

echo "stream_declared_name"
META_BODY='{"namespace":"integration","version":"1.0.0","refCount":1,"partitionCount":1}'
out=$(stream_declared_name test-events 2> "${WORK}/n1.err"); rc=$?
if [ "$rc" -ne 0 ] && [ -z "$out" ] && grep -q 'field absent' "${WORK}/n1.err"; then
    ok "N1 a metadata body with no stream field fails with 'field absent'"
else
    fail "N1 expected rc!=0, empty stdout, 'field absent' on stderr; got rc=${rc} out='${out}' err='$(cat "${WORK}/n1.err")'"
fi

META_BODY="$NO_TOTAL"
out=$(stream_declared_name test-events 2> "${WORK}/n2.err"); rc=$?
if [ "$rc" -eq 0 ] && [ "$out" = "test-events" ]; then
    ok "N2 control: the stream field prints test-events"
else
    fail "N2 expected rc=0 out=test-events; got rc=${rc} out='${out}' err='$(cat "${WORK}/n2.err")'"
fi

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
