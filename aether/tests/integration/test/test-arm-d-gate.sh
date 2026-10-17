#!/bin/bash
# test-arm-d-gate.sh — pins that the Arm D hook is OFF by default and cannot fire accidentally.
#
#   bash aether/tests/integration/test/test-arm-d-gate.sh
#
# The S19 file is SYMLINKED into both 02-chaos and 02s-selfdrain, so an ungated Arm D hook would
# silently change 02-chaos — the suite the acceptance record rests on. The flag is a new branch,
# and an untested default is exactly how a "safe" gate changes the thing it was added to protect.
# So the OFF path is pinned as behaviour, not as intention.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SUITE="${INTEG_DIR}/suites/02-chaos/test-self-drain-quorum-loss.sh"
export TARGET_HOST="arm-d-gate-test"

# shellcheck source=../lib/topology.sh
source "${INTEG_DIR}/lib/topology.sh"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

echo "arm_d_requested — OFF by default, and fails CLOSED"
unset S19_ARM_D_DISABLE_AUTOHEAL
arm_d_requested && fail "D1 unset should be OFF" || ok "D1 unset -> OFF (02-chaos unchanged)"
S19_ARM_D_DISABLE_AUTOHEAL=false  arm_d_requested && fail "D2 'false' should be OFF" || ok "D2 'false' -> OFF"
# A gate that fails OPEN on a typo is not a gate. Each of these must leave the stock path alone.
for v in TRUE True 1 yes on ""; do
    if S19_ARM_D_DISABLE_AUTOHEAL="$v" arm_d_requested; then
        fail "D3 '${v}' wrongly enabled Arm D — the gate fails OPEN on a near-miss value"
    else
        ok "D3 '${v:-<empty>}' -> OFF (fails closed)"
    fi
done
S19_ARM_D_DISABLE_AUTOHEAL=true arm_d_requested && ok "D4 exactly 'true' -> ON" || fail "D4 'true' should be ON"

echo "autoheal_enabled_field — parses the status body, empty on anything else"
[ "$(autoheal_enabled_field '{"enabled":false}')" = "false" ] && ok "D5 parses false" || fail "D5 false"
[ "$(autoheal_enabled_field '{"enabled":true}')"  = "true"  ] && ok "D5 parses true"  || fail "D5 true"
[ -z "$(autoheal_enabled_field '{"other":1}')" ] && ok "D5 no field -> empty (unreadable, not a value)" || fail "D5 garbage"
[ -z "$(autoheal_enabled_field '')" ] && ok "D5 empty body -> empty" || fail "D5 empty"

echo "wiring — the gate precedes any API call in every Arm D function"
# Ordering invariant, not an output: a guard placed BELOW the first api_ call would fire the hook
# before deciding whether it was requested — an Arm D side effect inside a stock 02-chaos run.
for fn in test_arm_d_disable_autoheal test_arm_d_autoheal_state_in_window test_arm_d_autoheal_state_after_recovery; do
    body=$(awk "/^${fn}\(\) \{/,/^}/" "$SUITE")
    if [ -z "$body" ]; then fail "D6 ${fn} not found in the suite"; continue; fi
    g=$(printf '%s\n' "$body" | grep -n 'arm_d_requested'      | head -1 | cut -d: -f1)
    a=$(printf '%s\n' "$body" | grep -n 'api_get\|api_post'    | head -1 | cut -d: -f1)
    if [ -z "$g" ]; then
        fail "D6 ${fn} has NO arm_d_requested guard — it would run in stock 02-chaos"
    elif [ -n "$a" ] && [ "$g" -ge "$a" ]; then
        fail "D6 ${fn} guard (line ${g}) is not above its first API call (line ${a}) — Arm D would touch the cluster before checking it was requested"
    else
        ok "D6 ${fn} guards before any API call"
    fi
done

echo "wiring — the hook is registered exactly once per phase"
for t in test_arm_d_disable_autoheal test_arm_d_autoheal_state_in_window test_arm_d_autoheal_state_after_recovery; do
    n=$(grep -c "^run_test .*${t}\$" "$SUITE")
    [ "$n" = "1" ] && ok "D7 ${t} registered once" || fail "D7 ${t} registered ${n} times"
done

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ] || exit 1
