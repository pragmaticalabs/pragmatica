#!/bin/bash
# test-s19-identity-guard.sh — pins the W5 answering-node identity check.
#
#   bash aether/tests/integration/test/test-s19-identity-guard.sh
#
# Why this exists: the S19 arbitration polled a cached survivor IP and never checked which node
# replied. Cloud providers recycle public IPs (run 3: two IPs served SIX VMs each), so a
# replacement could answer in the survivor's place with belowThreshold=false. The read SUCCEEDS,
# so neither the empty-read degrade path nor the survivor-2 fallback fires.
#
# A validator only ever observed ACCEPTING is indistinguishable from one that cannot REJECT, so
# every case below is paired: the guard must accept the right node AND fire on a wrong one.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
export TARGET_HOST="s19-identity-test"

# shellcheck source=../lib/topology.sh
source "${INTEG_DIR}/lib/topology.sh"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

SURV="aether-cloud-test-b-jvm-node-01m2esbvb273fa99atz0fqq0d8"
STRANGER="aether-cloud-test-b-jvm-node-01m2esj5vqj3hth40wty2h0181"

# Realistic body: top-level nodeId, then a members[] array carrying OTHER nodeIds.
body_from() {
    printf '{"nodeId":"%s","strictCoreMemberCount":2,"requiredThreshold":3,"belowThreshold":true,"armed":true,"members":[{"nodeId":"%s","state":"Member","strictCore":true},{"nodeId":"%s","state":"Dead","strictCore":false}]}' \
        "$1" "$STRANGER" "$SURV"
}

echo "identity guard — accepts the right node"
out=$(membership_identity_matches "$(body_from "$SURV")" "$SURV"); rc=$?
[ "$rc" -eq 0 ] && ok "I1 correct survivor accepted (rc=0)" || fail "I1 expected rc=0, got rc=$rc"
[ "$out" = "$SURV" ] && ok "I1 observed id reported for diagnostics" || fail "I1 observed='$out'"

echo "identity guard — REJECTS a stranger (the control that matters)"
out=$(membership_identity_matches "$(body_from "$STRANGER")" "$SURV"); rc=$?
[ "$rc" -eq 1 ] && ok "I2 recycled-IP stranger rejected (rc=1)" || fail "I2 expected rc=1, got rc=$rc"
[ "$out" = "$STRANGER" ] && ok "I2 names who actually answered" || fail "I2 observed='$out'"

echo "identity guard — top-level id is not confused with a peer's"
# The survivor appears INSIDE members[] here while a stranger answered. A naive first-match over
# the whole body could find the survivor's id among the members and wrongly accept.
out=$(membership_self_node_id "$(body_from "$STRANGER")")
[ "$out" = "$STRANGER" ] && ok "I3 reads the ANSWERING node, not a members[] entry" \
                         || fail "I3 read '$out' — a peer id leaked into the identity check"

echo "identity guard — indeterminate cases VOID rather than guess"
out=$(membership_identity_matches '{"strictCoreMemberCount":2,"members":[]}' "$SURV"); rc=$?
[ "$rc" -eq 2 ] && ok "I4 body with no top-level nodeId -> indeterminate (rc=2)" || fail "I4 rc=$rc"
out=$(membership_identity_matches "$(body_from "$SURV")" ""); rc=$?
[ "$rc" -eq 2 ] && ok "I5 empty expected id -> indeterminate (rc=2)" || fail "I5 rc=$rc"

echo "identity guard — a reordered body fails SAFE, never silently wrong"
# members[] first. The extractor truncates at "members", so the prefix holds no id and the guard
# reports indeterminate (VOID). The property pinned is the DIRECTION of the failure: it must
# never fall through to a peer's id and accept.
reordered=$(printf '{"members":[{"nodeId":"%s","state":"Member","strictCore":true}],"nodeId":"%s","belowThreshold":true}' "$SURV" "$STRANGER")
out=$(membership_self_node_id "$reordered")
[ "$out" != "$SURV" ] && ok "I6 reordered body never returns a peer id (got '${out:-<empty>}')" \
                      || fail "I6 returned the peer id '$out' — silently wrong, the exact bug"
out=$(membership_identity_matches "$reordered" "$SURV"); rc=$?
[ "$rc" -ne 0 ] && ok "I6 reordered body is not ACCEPTED (rc=$rc)" || fail "I6 wrongly accepted"

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ] || exit 1
