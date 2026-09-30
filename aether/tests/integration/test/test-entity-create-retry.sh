#!/bin/bash
# test-entity-create-retry.sh — stubs only. The REAL entity_post_status / create_entity / transient_failure_type /
# key_for / amount_for and the allow-list variables of suites/02w-entity-crash/test-entity-crash-durability.sh run
# against a stubbed `_api_call` that behaves like the real one when asked for the status (prints
# `<body>\n__API_HTTP_STATUS:N__`, rc 0 only for 2xx/3xx, rc 1 otherwise; status 000 = no answer).
# 02w's Post-crash liveness made ONE create attempt and reported "the cluster does not accept creates" for what
# was a post-failover transient refusal, with the body cut to 200 bytes so the inner cause was gone. And once app
# routes answer 503 for a Cause.Transient (#1737/#1765), `_api_call`'s "body only on 2xx" would hide that refusal.
#   E1  200 + allow-listed OwnershipNotYetCommitted twice, then created -> succeeds after 3 requests
#   E1b 503 + StorageUnavailable body twice, then 200 created          -> succeeds after 3 requests
#   E1c 503 with a body carrying no failureType, then created          -> retried (the status alone is enough)
#   E2  500 + StorageFailed                                            -> fails at once, 1 request, FULL body logged
#   E3  500 + ForwardRefused                                           -> fails at once, 1 request
#   E4  503 forever                                                    -> fails at the deadline, full body logged
#   E5  no answer anywhere (status 000)                                -> swept until the deadline, then fails
#   Two-endpoint cases (a survivor's refusal must not be authoritative; rule in entity_refusal_class):
#   N1  A=502, B=200 created/found  -> success   N2  A=404, B=200 -> success   N3  A=504, B=200 -> success
#   N4  A=500 StorageFailed, B=200  -> FAILS at once (authoritative), 1 request
#   N5  every node 504              -> swept until the deadline, fails with the last full body
#   E6  200 + EntityAlreadyExists                                      -> succeeds, 1 request (our own lost ack)
#   E7  200 + StorageFailed (a refusal carried in a 2xx body)          -> fails at once
#   R1  READ: 503 + FoldInProgress twice, then found            -> the amount, after 3 requests
#   R2  READ: 503 with no failureType twice, then found         -> the amount (the status alone is enough)
#   R3  READ: 200 + FoldInProgress twice, then found            -> the amount (the pre-#1765 shape still works)
#   R4  READ: 503 forever                                       -> rc 5 at TRANSIENT_READ_DEADLINE_S, full body logged
#   R5  READ: 500 + StorageFailed                               -> rc 4 after 1 request, full body logged
#   R6  READ: no answer                                         -> rc 4 "no node answered" once the deadline passes
#   R7  READ: 200 absent                                        -> rc 3
#   The readiness probe (wait_for ... entity_post_any) is deliberately left on entity_post_any: it measures "is the
#   service answering yet", so a non-2xx must read as not-ready and be re-polled by wait_for itself.
#   Mutations: read path back on entity_post_any (R1, R2, R4); no retry (E1, E1b, E1c, E4); drop the non-2xx body capture (E1b, E1c, E2 body, E4); StorageFailed
#   allow-listed (E2, E7).   SCRIPT_UNDER_TEST=<path> selects another copy.
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SUT="${SCRIPT_UNDER_TEST:-${INTEG_DIR}/suites/02w-entity-crash/test-entity-crash-durability.sh}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap '[ -n "${KEEP:-}" ] && echo "WORK=$WORK" >&2 || rm -rf "$WORK"' EXIT
extract() { sed -n "/^$2() {/,/^}/p" "$1"; }

{
cat <<'STUB'
log_warn() { echo "WARN $*" >&2; }
KEY_PREFIX=ENTDUR
ENTITY_APP_ENDPOINTS="${EPS:-http://n1:8070}"
refresh_app_endpoints() { return 1; }
# Request n answers STATUS_<n>/BODY_<n>, else STATUS/BODY. `want_status` ($4) is honoured like the real _api_call;
# without it a non-2xx prints NOTHING (the real behaviour that hid 503 bodies).
_api_call() {
    local n=$(( $(cat "$CALLS" 2>/dev/null || echo 0) + 1 )) status body
    echo "$n" > "$CALLS"
    eval "status=\${STATUS_${n}:-\$STATUS}; body=\${BODY_${n}:-\$BODY}"
    if [ "$status" -ge 200 ] && [ "$status" -lt 400 ] 2>/dev/null; then
        printf '%s' "$body"
        [ -n "$4" ] && printf '\n__API_HTTP_STATUS:%s__' "$status"
        return 0
    fi
    [ -n "$4" ] && printf '%s\n__API_HTTP_STATUS:%s__' "$body" "${status:-000}"
    return 1
}
STUB
grep -E '^(ENTITY_TRANSIENT_FAILURE_TYPES|ENTITY_CREATE_RETRY_[A-Z_]*|TRANSIENT_READ_[A-Z_]*)=' "$SUT"
for f in key_for amount_for transient_failure_type entity_refusal_class entity_post_any entity_post_status create_entity read_amount; do extract "$SUT" "$f"; done
} > "$WORK/fns.sh"

run() {  # <label> [VAR=value ...]
    local label="$1"; shift
    : > "$WORK/calls.$label"
    ( export CALLS="$WORK/calls.$label" ENTITY_CREATE_RETRY_DEADLINE_S=3 ENTITY_CREATE_RETRY_BACKOFF_S=1 "$@"
      source "$WORK/fns.sh"; create_entity 9999 ) > "$WORK/out.$label" 2> "$WORK/err.$label"
    echo $? > "$WORK/rc.$label"
}
calls() { cat "$WORK/calls.$1"; }
TRANSIENT200='{"outcome":"refused","failureType":"OwnershipNotYetCommitted","message":"owner not committed"}'
TRANSIENT503='{"outcome":"refused","failureType":"StorageUnavailable","message":"Durable entity storage for key '"'"'ENTDUR-09999-Z'"'"' is temporarily unavailable, retry: OwnerNotActivated-TAIL"}'
CREATED='{"outcome":"created","orderId":"ENTDUR-09999-Z"}'
LONG_INNER="Durable entity storage operation failed for key 'ENTDUR-09999-Z': $(printf 'inner-cause-%.0s' 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16) StreamError.PartitionNotHeld-TAIL-MARKER"
STORAGE="{\"outcome\":\"refused\",\"failureType\":\"StorageFailed\",\"message\":\"${LONG_INNER}\"}"

run e1 STATUS=200 BODY="$TRANSIENT200" BODY_3="$CREATED"
if [ "$(cat "$WORK/rc.e1")" = "0" ] && [ "$(calls e1)" = "3" ]; then ok "E1 200 + allow-listed transient, twice, then created succeeds after 3 requests"
else fail "E1 rc=$(cat "$WORK/rc.e1") calls=$(calls e1)"; fi

run e1b STATUS=503 BODY="$TRANSIENT503" STATUS_3=200 BODY_3="$CREATED"
if [ "$(cat "$WORK/rc.e1b")" = "0" ] && [ "$(calls e1b)" = "3" ]; then ok "E1b 503 + StorageUnavailable twice, then 200 created succeeds after 3 requests"
else fail "E1b rc=$(cat "$WORK/rc.e1b") calls=$(calls e1b)"; fi

run e1c STATUS=503 BODY='{"title":"Service Unavailable"}' STATUS_2=200 BODY_2="$CREATED"
if [ "$(cat "$WORK/rc.e1c")" = "0" ] && [ "$(calls e1c)" = "2" ]; then ok "E1c a 503 whose body has no failureType is still retried"
else fail "E1c rc=$(cat "$WORK/rc.e1c") calls=$(calls e1c)"; fi

run e2 STATUS=500 BODY="$STORAGE"
if [ "$(cat "$WORK/rc.e2")" = "1" ] && [ "$(calls e2)" = "1" ] && grep -q 'TAIL-MARKER' "$WORK/err.e2"; then ok "E2 500 + StorageFailed fails at once (1 request) with the FULL body, tail included, logged"
else fail "E2 rc=$(cat "$WORK/rc.e2") calls=$(calls e2) tail-logged=$(grep -c TAIL-MARKER "$WORK/err.e2")"; fi

run e3 STATUS=500 BODY='{"outcome":"refused","failureType":"ForwardRefused","message":"x"}'
if [ "$(cat "$WORK/rc.e3")" = "1" ] && [ "$(calls e3)" = "1" ]; then ok "E3 500 + ForwardRefused fails at once (never allow-listed)"
else fail "E3 rc=$(cat "$WORK/rc.e3") calls=$(calls e3)"; fi

run e4 STATUS=503 BODY="$TRANSIENT503"
if [ "$(cat "$WORK/rc.e4")" = "1" ] && [ "$(calls e4)" -ge 2 ] && grep -q 'retry deadline; last body: {.*OwnerNotActivated-TAIL' "$WORK/err.e4"; then ok "E4 503 forever fails at the deadline ($(calls e4) requests) with the full body logged"
else fail "E4 rc=$(cat "$WORK/rc.e4") calls=$(calls e4) err=$(tail -1 "$WORK/err.e4" | cut -c1-120)"; fi

run e5 STATUS=000 BODY='curl: (7) Failed to connect'
# no HTTP status anywhere: keep sweeping until the deadline (the post-kill window), then fail; not fatal.
if [ "$(cat "$WORK/rc.e5")" = "1" ] && [ "$(calls e5)" -ge 3 ] && grep -q 'retry deadline; last body: <no node answered>' "$WORK/err.e5"; then ok "E5 no answer anywhere is swept until the deadline ($(calls e5) requests), then fails"
else fail "E5 rc=$(cat "$WORK/rc.e5") calls=$(calls e5) err=$(tail -1 "$WORK/err.e5" | cut -c1-120)"; fi

run e6 STATUS=200 BODY='{"outcome":"refused","failureType":"EntityAlreadyExists"}'
if [ "$(cat "$WORK/rc.e6")" = "0" ] && [ "$(calls e6)" = "1" ]; then ok "E6 EntityAlreadyExists counts as our create having landed (1 request)"
else fail "E6 rc=$(cat "$WORK/rc.e6") calls=$(calls e6)"; fi

run e7 STATUS=200 BODY="$STORAGE"
if [ "$(cat "$WORK/rc.e7")" = "1" ] && [ "$(calls e7)" = "1" ]; then ok "E7 a StorageFailed refusal carried in a 200 body fails at once"
else fail "E7 rc=$(cat "$WORK/rc.e7") calls=$(calls e7)"; fi

# ---- reads ---------------------------------------------------------------------------------------
run_read() {  # <label> [VAR=value ...]  -> stdout in out.<label>, rc in rc.<label>
    local label="$1"; shift
    : > "$WORK/calls.$label"
    ( export CALLS="$WORK/calls.$label" TRANSIENT_READ_DEADLINE_S=3 TRANSIENT_READ_BACKOFF_S=1 "$@"
      source "$WORK/fns.sh"; read_amount ENTDUR-00001-Z ) > "$WORK/out.$label" 2> "$WORK/err.$label"
    echo $? > "$WORK/rc.$label"
}
FOUND='{"outcome":"found","orderId":"ENTDUR-00001-Z","amount":73}'
FOLD503='{"outcome":"refused","failureType":"FoldInProgress","message":"replaying"}'

run_read r1 STATUS=503 BODY="$FOLD503" STATUS_3=200 BODY_3="$FOUND"
if [ "$(cat "$WORK/rc.r1")" = "0" ] && [ "$(cat "$WORK/out.r1")" = "73" ] && [ "$(calls r1)" = "3" ]; then ok "R1 read: 503 FoldInProgress twice, then found returns the amount after 3 requests"
else fail "R1 rc=$(cat "$WORK/rc.r1") out=$(cat "$WORK/out.r1") calls=$(calls r1)"; fi

run_read r2 STATUS=503 BODY='{"title":"Service Unavailable"}' STATUS_3=200 BODY_3="$FOUND"
if [ "$(cat "$WORK/rc.r2")" = "0" ] && [ "$(cat "$WORK/out.r2")" = "73" ] && [ "$(calls r2)" = "3" ]; then ok "R2 read: a 503 with no failureType is retried (the status alone is enough)"
else fail "R2 rc=$(cat "$WORK/rc.r2") out=$(cat "$WORK/out.r2") calls=$(calls r2)"; fi

run_read r3 STATUS=200 BODY="$FOLD503" BODY_3="$FOUND"
if [ "$(cat "$WORK/rc.r3")" = "0" ] && [ "$(cat "$WORK/out.r3")" = "73" ] && [ "$(calls r3)" = "3" ]; then ok "R3 read: a 200 + FoldInProgress body is still retried (pre-#1765 shape)"
else fail "R3 rc=$(cat "$WORK/rc.r3") out=$(cat "$WORK/out.r3") calls=$(calls r3)"; fi

run_read r4 STATUS=503 BODY="$TRANSIENT503"
if [ "$(cat "$WORK/rc.r4")" = "5" ] && [ "$(calls r4)" -ge 2 ] && grep -q 'retry deadline; last body: {.*OwnerNotActivated-TAIL' "$WORK/err.r4"; then ok "R4 read: 503 forever returns rc 5 at the deadline ($(calls r4) requests), full body logged"
else fail "R4 rc=$(cat "$WORK/rc.r4") calls=$(calls r4) err=$(tail -1 "$WORK/err.r4" | cut -c1-120)"; fi

run_read r5 STATUS=500 BODY="$STORAGE"
if [ "$(cat "$WORK/rc.r5")" = "4" ] && [ "$(calls r5)" = "1" ] && grep -q 'TAIL-MARKER' "$WORK/err.r5"; then ok "R5 read: 500 StorageFailed returns rc 4 after 1 request with the FULL body logged"
else fail "R5 rc=$(cat "$WORK/rc.r5") calls=$(calls r5) tail-logged=$(grep -c TAIL-MARKER "$WORK/err.r5")"; fi

run_read r6 STATUS=000 BODY='curl: (7) Failed to connect' TRANSIENT_READ_DEADLINE_S=0
if [ "$(cat "$WORK/rc.r6")" = "4" ] && [ "$(calls r6)" = "2" ] && grep -q 'no node answered' "$WORK/err.r6"; then ok "R6 read: no answer at the deadline returns rc 4 'no node answered' (sweeps first)"
else fail "R6 rc=$(cat "$WORK/rc.r6") calls=$(calls r6)"; fi

run_read r7 STATUS=200 BODY='{"outcome":"absent"}'
if [ "$(cat "$WORK/rc.r7")" = "3" ] && [ "$(calls r7)" = "1" ]; then ok "R7 read: absent returns rc 3"
else fail "R7 rc=$(cat "$WORK/rc.r7") calls=$(calls r7)"; fi

# ---- two endpoints: a survivor's refusal is not authoritative unless it is fatal ---------------------
TWO=$'http://a:8070\nhttp://b:8070'
run e_n1 EPS="$TWO" STATUS_1=502 BODY_1='{"title":"Bad Gateway"}' STATUS_2=200 BODY_2="$CREATED"
run e_n2 EPS="$TWO" STATUS_1=404 BODY_1='{"title":"Not Found"}' STATUS_2=200 BODY_2="$CREATED"
run e_n3 EPS="$TWO" STATUS_1=504 BODY_1='{"title":"Gateway Timeout"}' STATUS_2=200 BODY_2="$CREATED"
if [ "$(cat "$WORK/rc.e_n1")" = "0" ] && [ "$(calls e_n1)" = "2" ] && [ "$(cat "$WORK/rc.e_n2")" = "0" ] && [ "$(calls e_n2)" = "2" ] \
   && [ "$(cat "$WORK/rc.e_n3")" = "0" ] && [ "$(calls e_n3)" = "2" ]; then ok "N1-N3 create: A=502 / 404 / 504, B=200 created -> success on the second node (1 sweep, 2 requests)"
else fail "N1-N3 create: rc/calls 502=$(cat "$WORK/rc.e_n1")/$(calls e_n1) 404=$(cat "$WORK/rc.e_n2")/$(calls e_n2) 504=$(cat "$WORK/rc.e_n3")/$(calls e_n3)"; fi
run e_n4 EPS="$TWO" STATUS_1=500 BODY_1="$STORAGE" STATUS_2=200 BODY_2="$CREATED"
if [ "$(cat "$WORK/rc.e_n4")" = "1" ] && [ "$(calls e_n4)" = "1" ] && grep -q 'TAIL-MARKER' "$WORK/err.e_n4"; then ok "N4 create: A=500 StorageFailed, B=200 -> fails at once (authoritative), full body logged"
else fail "N4 rc=$(cat "$WORK/rc.e_n4") calls=$(calls e_n4)"; fi
run e_n5 EPS="$TWO" STATUS=504 BODY='{"title":"Gateway Timeout","detail":"GW-TAIL"}'
if [ "$(cat "$WORK/rc.e_n5")" = "1" ] && [ "$(calls e_n5)" -ge 4 ] && grep -q 'retry deadline; last body: {.*GW-TAIL' "$WORK/err.e_n5"; then ok "N5 create: every node 504 -> swept until the deadline ($(calls e_n5) requests), fails with the last full body"
else fail "N5 rc=$(cat "$WORK/rc.e_n5") calls=$(calls e_n5) err=$(tail -1 "$WORK/err.e_n5" | cut -c1-100)"; fi

run_read rn1 EPS="$TWO" STATUS_1=502 BODY_1='{"title":"Bad Gateway"}' STATUS_2=200 BODY_2="$FOUND"
run_read rn2 EPS="$TWO" STATUS_1=404 BODY_1='{"title":"Not Found"}' STATUS_2=200 BODY_2="$FOUND"
if [ "$(cat "$WORK/rc.rn1")" = "0" ] && [ "$(cat "$WORK/out.rn1")" = "73" ] && [ "$(calls rn1)" = "2" ] \
   && [ "$(cat "$WORK/rc.rn2")" = "0" ] && [ "$(cat "$WORK/out.rn2")" = "73" ] && [ "$(calls rn2)" = "2" ]; then ok "N6 read: A=502 / A=404, B=200 found -> the amount from the second node"
else fail "N6 read: 502=$(cat "$WORK/rc.rn1")/$(cat "$WORK/out.rn1")/$(calls rn1) 404=$(cat "$WORK/rc.rn2")/$(cat "$WORK/out.rn2")/$(calls rn2)"; fi
run_read rn4 EPS="$TWO" STATUS_1=500 BODY_1="$STORAGE" STATUS_2=200 BODY_2="$FOUND"
if [ "$(cat "$WORK/rc.rn4")" = "4" ] && [ "$(calls rn4)" = "1" ] && grep -q 'TAIL-MARKER' "$WORK/err.rn4"; then ok "N7 read: A=500 StorageFailed, B=200 found -> rc 4 at once (authoritative), full body logged"
else fail "N7 rc=$(cat "$WORK/rc.rn4") calls=$(calls rn4)"; fi
run_read rn5 EPS="$TWO" STATUS=504 BODY='{"title":"Gateway Timeout","detail":"GW-TAIL"}'
if [ "$(cat "$WORK/rc.rn5")" = "4" ] && [ "$(calls rn5)" -ge 4 ] && grep -q 'GW-TAIL' "$WORK/err.rn5"; then ok "N8 read: every node 504 -> swept until the deadline ($(calls rn5) requests), rc 4 with the last full body"
else fail "N8 rc=$(cat "$WORK/rc.rn5") calls=$(calls rn5)"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
