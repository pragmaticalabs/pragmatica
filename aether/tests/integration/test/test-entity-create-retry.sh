#!/bin/bash
# test-entity-create-retry.sh — stubs only. The REAL create_entity / transient_failure_type / key_for /
# amount_for and the allow-list variables of suites/02w-entity-crash/test-entity-crash-durability.sh run
# against a stubbed entity_post_any. 02w's Post-crash liveness made ONE create attempt and reported "the
# cluster does not accept creates" for what was a post-failover transient refusal, with the body cut to 200
# bytes so the inner cause was gone.
#   E1  OwnershipNotYetCommitted twice, then created -> succeeds after 3 requests
#   E2  StorageFailed                                -> fails at once, 1 request, the FULL body logged
#   E3  ForwardRefused                               -> fails at once, 1 request
#   E4  a transient type forever                     -> fails at the deadline, several requests, full body logged
#   E5  no node reachable (empty answer)             -> fails at once, 1 request
#   E6  EntityAlreadyExists                          -> succeeds, 1 request (our own lost ack)
#   SCRIPT_UNDER_TEST=<path> selects another copy; ENTITY_TRANSIENT_FAILURE_TYPES can be exported to it.
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
# Serve BODY_<n> (or BODY) for request n, and honour the matcher like the real entity_post_any: print the
# body, return 0 iff it matches, 1 otherwise; an empty body is "nothing reachable" (rc 1, no output).
entity_post_any() {
    local n=$(( $(cat "$CALLS" 2>/dev/null || echo 0) + 1 )) body
    echo "$n" > "$CALLS"
    eval "body=\${BODY_${n}:-\$BODY}"
    [ -n "$body" ] || return 1
    printf '%s' "$body"
    printf '%s' "$body" | grep -qE "$3"
}
STUB
grep -E '^(ENTITY_TRANSIENT_FAILURE_TYPES|ENTITY_CREATE_RETRY_[A-Z_]*|TRANSIENT_READ_[A-Z_]*)=' "$SUT"
for f in key_for amount_for transient_failure_type create_entity; do extract "$SUT" "$f"; done
} > "$WORK/fns.sh"

run() {  # <label> [VAR=value ...]  -> rc in $WORK/rc.<label>, stderr in $WORK/err.<label>, calls in $WORK/calls.<label>
    local label="$1"; shift
    : > "$WORK/calls.$label"
    ( export CALLS="$WORK/calls.$label" ENTITY_CREATE_RETRY_DEADLINE_S=3 ENTITY_CREATE_RETRY_BACKOFF_S=1 "$@"
      source "$WORK/fns.sh"; create_entity 9999 ) > "$WORK/out.$label" 2> "$WORK/err.$label"
    echo $? > "$WORK/rc.$label"
}
calls() { cat "$WORK/calls.$1"; }
TRANSIENT='{"outcome":"refused","failureType":"OwnershipNotYetCommitted","message":"owner not committed"}'
CREATED='{"outcome":"created","orderId":"ENTDUR-09999-Z"}'
LONG_INNER="Durable entity storage operation failed for key 'ENTDUR-09999-Z': $(printf 'inner-cause-%.0s' 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16) StreamError.PartitionNotHeld-TAIL-MARKER"
STORAGE="{\"outcome\":\"refused\",\"failureType\":\"StorageFailed\",\"message\":\"${LONG_INNER}\"}"

run e1 BODY="$TRANSIENT" BODY_3="$CREATED"
if [ "$(cat "$WORK/rc.e1")" = "0" ] && [ "$(calls e1)" = "3" ]; then ok "E1 transient, transient, created succeeds after 3 requests"
else fail "E1 rc=$(cat "$WORK/rc.e1") calls=$(calls e1)"; fi

run e2 BODY="$STORAGE"
if [ "$(cat "$WORK/rc.e2")" = "1" ] && [ "$(calls e2)" = "1" ] && grep -q 'TAIL-MARKER' "$WORK/err.e2"; then ok "E2 StorageFailed fails at once (1 request) and the FULL body, tail included, is logged"
else fail "E2 rc=$(cat "$WORK/rc.e2") calls=$(calls e2) tail-logged=$(grep -c TAIL-MARKER "$WORK/err.e2")"; fi

run e3 BODY='{"outcome":"refused","failureType":"ForwardRefused","message":"x"}'
if [ "$(cat "$WORK/rc.e3")" = "1" ] && [ "$(calls e3)" = "1" ]; then ok "E3 ForwardRefused fails at once (never allow-listed)"
else fail "E3 rc=$(cat "$WORK/rc.e3") calls=$(calls e3)"; fi

run e4 BODY="$TRANSIENT"
if [ "$(cat "$WORK/rc.e4")" = "1" ] && [ "$(calls e4)" -ge 2 ] && grep -q 'retry deadline; last body: {.*owner not committed' "$WORK/err.e4"; then ok "E4 a transient type forever fails at the deadline ($(calls e4) requests), full body logged"
else fail "E4 rc=$(cat "$WORK/rc.e4") calls=$(calls e4) err=$(tail -1 "$WORK/err.e4" | cut -c1-120)"; fi

run e5 BODY=""
if [ "$(cat "$WORK/rc.e5")" = "1" ] && [ "$(calls e5)" = "1" ]; then ok "E5 no node reachable is not retried as a transient refusal"
else fail "E5 rc=$(cat "$WORK/rc.e5") calls=$(calls e5)"; fi

run e6 BODY='{"outcome":"refused","failureType":"EntityAlreadyExists"}'
if [ "$(cat "$WORK/rc.e6")" = "0" ] && [ "$(calls e6)" = "1" ]; then ok "E6 EntityAlreadyExists counts as our create having landed (1 request)"
else fail "E6 rc=$(cat "$WORK/rc.e6") calls=$(calls e6)"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
