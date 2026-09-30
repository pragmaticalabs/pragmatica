#!/bin/bash
# test-stream-publish-retry.sh — stubs only (no cluster, no cloud). The REAL stream_publish /
# stream_publish_status / stream_status / stream_replicas (lib/cluster.sh), the REAL _api_call
# (lib/common.sh) and the REAL test_publish_and_verify_count (suites/04-streaming) run against a stub
# `curl` playing the management API. Refs #1478 (count), #1750 (500 is never retried).
#   T1  503, 503, then 200 ("refused before writing")  -> ONE success in exactly 3 requests
#   T2  500 with the VERBATIM PublishOutcomeUnknown body (it contains the word "retry")
#                                           -> fails IMMEDIATELY, exactly 1 call
#   T3  503 "refused before writing" forever             -> fails after the budget, >2 calls, last body quoted
#   T4  503 with other wording (even "retryable")  -> not retried, exactly 1 call
#   T5  /info stuck at 14 of 20             -> the count test fails after its budget and prints the acked
#                                              offsets with their endpoints, the /info body, /replicas/0, and
#                                              replicas-local from EVERY node (one node's request fails and
#                                              is reported without stopping the rest)
#   T6  /info at 14, 14, then 20            -> the count test PASSES (it polls, not one read)
#   T7  /info body without totalEvents      -> fails, quoting the body (absent is never a measured 0)
#   T1b every absorbed 503 leaves a trace (status + the first 120 chars of the body) in the log
#   C1-C4 stream_coordinate against a stub catalog: integration/... wins in BOTH catalog orders; two
#       foreign namespaces and no integration entry fail loudly; one foreign match resolves (an app stream)
#   T8  the batch-of-50 test (04 test-stream-publish) with every publish refused -> prints the shortfall
#       capture (per-node replicas-local sections); T9 the same for "Publish 10 events" (test-stream-replication)
# Mutations (see the PR): no retry reddens T1; retry on 500 reddens T2; a single read reddens T6.
#   LIB_UNDER_TEST / CONSUMER_UNDER_TEST select alternate copies.
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LIB="${LIB_UNDER_TEST:-${INTEG_DIR}/lib/cluster.sh}"
CONSUMER="${CONSUMER_UNDER_TEST:-${INTEG_DIR}/suites/04-streaming/test-stream-consumer.sh}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap '[ -n "${KEEP:-}" ] && echo "WORK=$WORK" >&2 || rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"
extract() { sed -n "/^$2() {/,/^}/p" "$1"; }

{
cat <<'STUB'
log_info() { :; }; log_warn() { echo "WARN $*" >&2; }; log_fail() { echo "FAIL $*"; }; log_pass() { echo "PASS $*"; }
assert_eq() { if [ "$1" = "$2" ]; then log_pass "$3"; else log_fail "$3: got $1 want $2"; return 1; fi; }
assert_ge() { if [ "$1" -ge "$2" ] 2>/dev/null; then log_pass "$3"; else log_fail "$3: got $1 want >= $2"; return 1; fi; }
now_epoch() { date +%s; }
STREAM_NAME=consumer-test
_resolve_live_endpoint() { echo http://stub; }
stream_coordinate() { echo integration/consumer-test/1.0.0; }
api_get() { curl -sk "http://stub$1"; }
API_KEY=k; CLOUD_MODE=false; NODE_COUNT=3; TARGET_HOST=stub; MGMT_PORT=5151
STREAM_NAME=consumer-test
STUB
extract "${INTEG_DIR}/lib/common.sh" _api_call
cat "${INTEG_DIR}/lib/json.sh"
for f in stream_publish_status stream_publish stream_status stream_replicas stream_identity _stream_live_endpoints stream_shortfall_report; do extract "$LIB" "$f"; done
extract "$CONSUMER" test_publish_and_verify_count
extract "${INTEG_DIR}/suites/04-streaming/test-stream-publish.sh" test_publish_batch
extract "${INTEG_DIR}/suites/04-streaming/test-stream-replication.sh" test_publish_events_for_replication
} > "$WORK/fns.sh"

# Stub curl. PUB=<mode> for POST .../publish; INFO=<mode> for GET .../info; every call is logged.
cat > "$WORK/bin/curl" <<'STUB'
#!/bin/bash
url="${*: -1}"
echo "$*" | grep -q -- '-X POST' && meth=POST || meth=GET
echo "$meth $url" >> "$CALLS"
n=$(grep -c "^$meth $url" "$CALLS")
case "$meth $url" in
  "POST "*/publish)
    case "$PUB" in
      503then200) if [ "$n" -le 2 ]; then printf '{"detail":"Publish to stream '"'"'s'"'"' refused before writing, retry: owner not yet promoted"}\n__API_HTTP_STATUS:503__'; else printf '{"address":"a","offset":%s}\n__API_HTTP_STATUS:200__' "$n"; fi ;;
      500) printf '{"detail":"Publish outcome unknown: the event may already be in the log; retry only with the same message ID (FORWARD_TIMEOUT)"}\n__API_HTTP_STATUS:500__' ;;
      503forever) printf '{"detail":"Publish to stream '"'"'s'"'"' refused before writing, retry: not yet promoted"}\n__API_HTTP_STATUS:503__' ;;
      503plain) printf '{"detail":"temporarily unavailable, retryable"}\n__API_HTTP_STATUS:503__' ;;
      ok) printf '{"address":"a","offset":%s}\n__API_HTTP_STATUS:200__' "$n" ;;
    esac ;;
  "GET "*/info)
    case "$INFO" in
      stuck14) echo '{"totalEvents":14,"partitionDetails":[{"partition":0,"head":13,"tail":0}]}' ;;
      climb) c=14; [ "$n" -ge 3 ] && c=20; echo "{\"totalEvents\":$c,\"partitionDetails\":[{\"partition\":0}]}" ;;
      nofield) echo '{"partitionDetails":[{"partition":0}]}' ;;
    esac ;;
  "GET "*/replicas/0) echo '{"replicas":["node-1","node-2"],"REPLICAS-MARKER":true}' ;;
  "GET http://stub:5152/"*replicas-local) exit 7 ;;
  "GET "*replicas-local) echo "{\"local\":\"REPLICAS-LOCAL-FROM-${url#http://stub:}\"}" ;;
esac
STUB
chmod +x "$WORK/bin/curl"

run_fn() {  # <label> <PUB> <INFO> <expression to run after sourcing>
    ( export PATH="$WORK/bin:$PATH" CALLS="$WORK/calls.$1" PUB="$2" INFO="$3" \
             STREAM_PUBLISH_RETRY_BUDGET_S=2 STREAM_PUBLISH_RETRY_DELAY_S=0.2 STREAM_COUNT_POLL_BUDGET_S=3
      : > "$CALLS"; source "$WORK/fns.sh"; eval "$4" ) > "$WORK/out.$1" 2> "$WORK/err.$1"
    echo $? > "$WORK/rc.$1"
}
posts() { grep -c '^POST' "$WORK/calls.$1"; }

run_fn t1 503then200 "" 'stream_publish s "{}"'
if [ "$(cat "$WORK/rc.t1")" = "0" ] && [ "$(posts t1)" = "3" ]; then ok "T1 503, 503, 200 gives one success in exactly 3 requests"
else fail "T1 rc=$(cat "$WORK/rc.t1") posts=$(posts t1)"; fi

if grep -q 'attempt 1 got 503 ({"detail":"Publish to stream' "$WORK/err.t1" && grep -q 'attempt 2 got 503' "$WORK/err.t1"; then ok "T1b each absorbed 503 is logged with status and the body's start"
else fail "T1b err: $(head -c 200 "$WORK/err.t1")"; fi

run_fn t2 500 "" 'stream_publish s "{}"'
if [ "$(cat "$WORK/rc.t2")" = "1" ] && [ "$(posts t2)" = "1" ]; then ok "T2 500 fails immediately, no retry"
else fail "T2 rc=$(cat "$WORK/rc.t2") posts=$(posts t2)"; fi

run_fn t3 503forever "" 'stream_publish s "{}"'
if [ "$(cat "$WORK/rc.t3")" = "1" ] && [ "$(posts t3)" -gt 2 ] && grep -q 'refused before writing' "$WORK/err.t3"; then ok "T3 503 retryable forever fails after the budget ($(posts t3) attempts), last body quoted"
else fail "T3 rc=$(cat "$WORK/rc.t3") posts=$(posts t3) err=$(head -c 120 "$WORK/err.t3")"; fi

run_fn t4 503plain "" 'stream_publish s "{}"'
if [ "$(cat "$WORK/rc.t4")" = "1" ] && [ "$(posts t4)" = "1" ]; then ok "T4 a 503 without the refused-before-writing wording is not retried"
else fail "T4 rc=$(cat "$WORK/rc.t4") posts=$(posts t4)"; fi

run_fn t5 ok stuck14 'test_publish_and_verify_count'
if [ "$(cat "$WORK/rc.t5")" = "1" ] && grep -q 'partitionDetails' "$WORK/out.t5" && grep -q 'REPLICAS-MARKER' "$WORK/out.t5" \
   && grep -q 'did not reach published (20)' "$WORK/out.t5" && grep -q 'acked offsets.*1@0 2@0 3@0' "$WORK/out.t5" \
   && grep -q 'endpoint=http://stub' "$WORK/out.t5" \
   && grep -q 'replicas-local @ http://stub:5151: .*REPLICAS-LOCAL-FROM-5151' "$WORK/out.t5" \
   && grep -q 'replicas-local @ http://stub:5152: <request failed' "$WORK/out.t5" \
   && grep -q 'replicas-local @ http://stub:5153: .*REPLICAS-LOCAL-FROM-5153' "$WORK/out.t5"; then
    ok "T5 /info stuck at 14 prints acked offsets, /info, /replicas/0 and replicas-local from every node (a failing node does not stop the rest)"
else fail "T5 rc=$(cat "$WORK/rc.t5") out=$(grep -c . "$WORK/out.t5") lines: $(grep FAIL "$WORK/out.t5" | tail -1 | cut -c1-140)"; fi

run_fn t6 ok climb 'test_publish_and_verify_count'
if [ "$(cat "$WORK/rc.t6")" = "0" ] && grep -q 'PASS totalEvents (20) >= published (20)' "$WORK/out.t6"; then ok "T6 count 14, 14, 20 passes (polls until it reaches the published count)"
else fail "T6 rc=$(cat "$WORK/rc.t6") $(grep 'FAIL\|PASS total' "$WORK/out.t6" | tail -1 | cut -c1-140)"; fi

run_fn t7 ok nofield 'test_publish_and_verify_count'
if [ "$(cat "$WORK/rc.t7")" = "1" ] && grep -q 'totalEvents (<absent>)' "$WORK/out.t7" && grep -q 'partitionDetails' "$WORK/out.t7"; then ok "T7 absent totalEvents fails quoting the body (never a measured 0)"
else fail "T7 rc=$(cat "$WORK/rc.t7") $(grep FAIL "$WORK/out.t7" | tail -1 | cut -c1-140)"; fi

run_fn t8 500 stuck14 'STREAM_PUBLISH_RETRY_BUDGET_S=0; test_publish_batch'
if grep -q 'shortfall capture: acked offsets' "$WORK/err.t8" "$WORK/out.t8" 2>/dev/null && grep -q 'replicas-local @ http://stub:5153' "$WORK/err.t8" "$WORK/out.t8" 2>/dev/null; then
    ok "T8 batch-of-50 shortfall prints the capture with per-node replicas-local"
else fail "T8 $(grep -c . "$WORK/err.t8") err lines; $(grep -h 'shortfall\|FAIL' "$WORK/out.t8" "$WORK/err.t8" | head -2 | cut -c1-100)"; fi
run_fn t9 500 stuck14 'STREAM_PUBLISH_RETRY_BUDGET_S=0; test_publish_events_for_replication'
if grep -q 'shortfall capture: acked offsets' "$WORK/err.t9" "$WORK/out.t9" 2>/dev/null && grep -q 'replicas-local @ http://stub:5151' "$WORK/err.t9" "$WORK/out.t9" 2>/dev/null; then
    ok "T9 Publish-10-events shortfall prints the capture with per-node replicas-local"
else fail "T9 $(grep -h 'shortfall\|FAIL' "$WORK/out.t9" "$WORK/err.t9" | head -2 | cut -c1-100)"; fi

# ---- C: stream_coordinate against a stub catalog ----------------------------------------------------
COORD_UNDER_TEST="${COORD_LIB_UNDER_TEST:-$LIB}"
{
cat <<'STUB'
log_warn() { echo "WARN $*" >&2; }
STREAM_TEST_NAMESPACE=integration; STREAM_TEST_VERSION=1.0.0
api_get() { cat "$CATALOG"; }
STUB
extract "$COORD_UNDER_TEST" stream_coordinate
} > "$WORK/coord.sh"
entry() { printf '{"namespace":"%s","stream":"%s","version":"1.0.0","partitionCount":1}' "$1" "$2"; }
run_coord() {  # <label> <catalog json> <name>
    echo "$2" > "$WORK/catalog.$1"; mkdir -p "$WORK/tmp.$1"
    ( export CATALOG="$WORK/catalog.$1" TMPDIR="$WORK/tmp.$1"; source "$WORK/coord.sh"; stream_coordinate "$3" ) > "$WORK/c.$1" 2> "$WORK/ce.$1"
    echo $? > "$WORK/crc.$1"
}
PERSIST="org.pragmatica.aether.test.test-persistence"
run_coord c1a "[$(entry "$PERSIST" test-events),$(entry integration test-events)]" test-events
run_coord c1b "[$(entry integration test-events),$(entry "$PERSIST" test-events)]" test-events
if [ "$(cat "$WORK/crc.c1a")" = "0" ] && [ "$(cat "$WORK/c.c1a")" = "integration/test-events/1.0.0" ] \
   && [ "$(cat "$WORK/crc.c1b")" = "0" ] && [ "$(cat "$WORK/c.c1b")" = "integration/test-events/1.0.0" ]; then
    ok "C1 both catalog orders resolve integration/test-events/1.0.0"
else fail "C1 a=[$(cat "$WORK/c.c1a")] b=[$(cat "$WORK/c.c1b")]"; fi
run_coord c2 "[$(entry ns.one test-events),$(entry ns.two test-events)]" test-events
if [ "$(cat "$WORK/crc.c2")" = "1" ] && grep -q 'AMBIGUOUS' "$WORK/ce.c2" && grep -q 'ns.one/test-events' "$WORK/ce.c2" && grep -q 'ns.two/test-events' "$WORK/ce.c2"; then
    ok "C2 a bare name in two foreign namespaces fails loudly, naming both"
else fail "C2 rc=$(cat "$WORK/crc.c2") err=$(head -c 160 "$WORK/ce.c2")"; fi
run_coord c3 "[$(entry "$PERSIST" notifications)]" notifications
if [ "$(cat "$WORK/crc.c3")" = "0" ] && [ "$(cat "$WORK/c.c3")" = "$PERSIST/notifications/1.0.0" ]; then ok "C3 a single app-declared stream resolves by bare name"
else fail "C3 rc=$(cat "$WORK/crc.c3") out=$(cat "$WORK/c.c3")"; fi
# C4: resolved once — the second call is served from the cache even if the catalog changed underneath.
( export CATALOG="$WORK/catalog.c1a" TMPDIR="$WORK/tmp.c4"; mkdir -p "$TMPDIR"; source "$WORK/coord.sh"
  stream_coordinate test-events > "$WORK/c.c4a"
  echo "[$(entry ns.one test-events),$(entry ns.two test-events)]" > "$CATALOG"
  stream_coordinate test-events > "$WORK/c.c4b" ) 2>/dev/null
if [ "$(cat "$WORK/c.c4a")" = "integration/test-events/1.0.0" ] && [ "$(cat "$WORK/c.c4b")" = "integration/test-events/1.0.0" ]; then ok "C4 the exact coordinate is resolved once per test process and reused"
else fail "C4 a=[$(cat "$WORK/c.c4a")] b=[$(cat "$WORK/c.c4b")]"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
