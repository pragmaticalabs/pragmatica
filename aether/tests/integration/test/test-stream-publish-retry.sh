#!/bin/bash
# test-stream-publish-retry.sh — stubs only (no cluster, no cloud). The REAL stream_publish /
# stream_publish_status / stream_status / stream_replicas (lib/cluster.sh), the REAL _api_call
# (lib/common.sh) and the REAL test_publish_and_verify_count (suites/04-streaming) run against a stub
# `curl` playing the management API. Refs #1478 (count), #1750 (500 is never retried).
#   T1  503 "retryable" then 200            -> publish succeeds after a retry (2 calls)
#   T2  500 "Publish outcome unknown ..."   -> fails IMMEDIATELY, exactly 1 call
#   T3  503 "retryable" forever             -> fails after the budget, >2 calls, last body quoted
#   T4  503 WITHOUT retry wording           -> not retried, exactly 1 call
#   T5  /info stuck at 14 of 20             -> the count test fails after its budget, quoting the /info
#                                              body (partitionDetails) and the /replicas/0 body
#   T6  /info at 14, 14, then 20            -> the count test PASSES (it polls, not one read)
#   T7  /info body without totalEvents      -> fails, quoting the body (absent is never a measured 0)
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
_resolve_live_endpoint() { echo http://stub; }
stream_coordinate() { echo integration/consumer-test/1.0.0; }
api_get() { curl -sk "http://stub$1"; }
API_KEY=k; CLOUD_MODE=false
STREAM_NAME=consumer-test
STUB
extract "${INTEG_DIR}/lib/common.sh" _api_call
cat "${INTEG_DIR}/lib/json.sh"
for f in stream_publish_status stream_publish stream_status stream_replicas; do extract "$LIB" "$f"; done
extract "$CONSUMER" test_publish_and_verify_count
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
      503then200) if [ "$n" -eq 1 ]; then printf '{"detail":"Publish refused: owner not yet promoted on this node — retryable"}\n__API_HTTP_STATUS:503__'; else printf '{"ok":true}\n__API_HTTP_STATUS:200__'; fi ;;
      500) printf '{"detail":"Publish outcome unknown (FORWARD_TIMEOUT)"}\n__API_HTTP_STATUS:500__' ;;
      503forever) printf '{"detail":"not yet promoted — retryable"}\n__API_HTTP_STATUS:503__' ;;
      503plain) printf '{"detail":"service unavailable"}\n__API_HTTP_STATUS:503__' ;;
      ok) printf '{"ok":true}\n__API_HTTP_STATUS:200__' ;;
    esac ;;
  "GET "*/info)
    case "$INFO" in
      stuck14) echo '{"totalEvents":14,"partitionDetails":[{"partition":0,"head":13,"tail":0}]}' ;;
      climb) c=14; [ "$n" -ge 3 ] && c=20; echo "{\"totalEvents\":$c,\"partitionDetails\":[{\"partition\":0}]}" ;;
      nofield) echo '{"partitionDetails":[{"partition":0}]}' ;;
    esac ;;
  "GET "*/replicas/0) echo '{"replicas":["node-1","node-2"],"REPLICAS-MARKER":true}' ;;
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
if [ "$(cat "$WORK/rc.t1")" = "0" ] && [ "$(posts t1)" = "2" ]; then ok "T1 503 retryable then 200 succeeds after one retry"
else fail "T1 rc=$(cat "$WORK/rc.t1") posts=$(posts t1)"; fi

run_fn t2 500 "" 'stream_publish s "{}"'
if [ "$(cat "$WORK/rc.t2")" = "1" ] && [ "$(posts t2)" = "1" ]; then ok "T2 500 fails immediately, no retry"
else fail "T2 rc=$(cat "$WORK/rc.t2") posts=$(posts t2)"; fi

run_fn t3 503forever "" 'stream_publish s "{}"'
if [ "$(cat "$WORK/rc.t3")" = "1" ] && [ "$(posts t3)" -gt 2 ] && grep -q 'not yet promoted' "$WORK/err.t3"; then ok "T3 503 retryable forever fails after the budget ($(posts t3) attempts), last body quoted"
else fail "T3 rc=$(cat "$WORK/rc.t3") posts=$(posts t3) err=$(head -c 120 "$WORK/err.t3")"; fi

run_fn t4 503plain "" 'stream_publish s "{}"'
if [ "$(cat "$WORK/rc.t4")" = "1" ] && [ "$(posts t4)" = "1" ]; then ok "T4 503 without retry wording is not retried"
else fail "T4 rc=$(cat "$WORK/rc.t4") posts=$(posts t4)"; fi

run_fn t5 ok stuck14 'test_publish_and_verify_count'
if [ "$(cat "$WORK/rc.t5")" = "1" ] && grep -q 'partitionDetails' "$WORK/out.t5" && grep -q 'REPLICAS-MARKER' "$WORK/out.t5" && grep -q 'did not reach published (20)' "$WORK/out.t5"; then
    ok "T5 /info stuck at 14 fails after the budget, quoting the /info and /replicas/0 bodies"
else fail "T5 rc=$(cat "$WORK/rc.t5") out=$(grep -c . "$WORK/out.t5") lines: $(grep FAIL "$WORK/out.t5" | tail -1 | cut -c1-140)"; fi

run_fn t6 ok climb 'test_publish_and_verify_count'
if [ "$(cat "$WORK/rc.t6")" = "0" ] && grep -q 'PASS totalEvents (20) >= published (20)' "$WORK/out.t6"; then ok "T6 count 14, 14, 20 passes (polls until it reaches the published count)"
else fail "T6 rc=$(cat "$WORK/rc.t6") $(grep 'FAIL\|PASS total' "$WORK/out.t6" | tail -1 | cut -c1-140)"; fi

run_fn t7 ok nofield 'test_publish_and_verify_count'
if [ "$(cat "$WORK/rc.t7")" = "1" ] && grep -q 'totalEvents (<absent>)' "$WORK/out.t7" && grep -q 'partitionDetails' "$WORK/out.t7"; then ok "T7 absent totalEvents fails quoting the body (never a measured 0)"
else fail "T7 rc=$(cat "$WORK/rc.t7") $(grep FAIL "$WORK/out.t7" | tail -1 | cut -c1-140)"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
