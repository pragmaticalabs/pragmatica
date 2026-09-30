#!/bin/bash
# test-entity-create-retry.sh — stubs only. The REAL entity_create_post / create_entity / transient_failure_type /
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
#   E5  no answer (status 000)                                         -> fails, no retry loop (2 = the sweep passes)
#   E6  200 + EntityAlreadyExists                                      -> succeeds, 1 request (our own lost ack)
#   E7  200 + StorageFailed (a refusal carried in a 2xx body)          -> fails at once
#   Mutations: no retry (E1, E1b, E1c, E4); drop the non-2xx body capture (E1b, E1c, E2 body, E4); StorageFailed
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
ENTITY_APP_ENDPOINTS="http://n1:8070"
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
for f in key_for amount_for transient_failure_type entity_create_post create_entity; do extract "$SUT" "$f"; done
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
# two requests = entity_create_post's own two sweep passes over the endpoints, exactly as entity_post_any did;
# create_entity adds no retry loop on top ("retrying" never logged).
if [ "$(cat "$WORK/rc.e5")" = "1" ] && [ "$(calls e5)" = "2" ] && ! grep -q 'retrying' "$WORK/err.e5"; then ok "E5 no answer (transport failure) is not retried by create_entity (only the two endpoint sweep passes)"
else fail "E5 rc=$(cat "$WORK/rc.e5") calls=$(calls e5)"; fi

run e6 STATUS=200 BODY='{"outcome":"refused","failureType":"EntityAlreadyExists"}'
if [ "$(cat "$WORK/rc.e6")" = "0" ] && [ "$(calls e6)" = "1" ]; then ok "E6 EntityAlreadyExists counts as our create having landed (1 request)"
else fail "E6 rc=$(cat "$WORK/rc.e6") calls=$(calls e6)"; fi

run e7 STATUS=200 BODY="$STORAGE"
if [ "$(cat "$WORK/rc.e7")" = "1" ] && [ "$(calls e7)" = "1" ]; then ok "E7 a StorageFailed refusal carried in a 200 body fails at once"
else fail "E7 rc=$(cat "$WORK/rc.e7") calls=$(calls e7)"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
