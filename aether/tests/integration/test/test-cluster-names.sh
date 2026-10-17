#!/bin/bash
# test-cluster-names.sh — unit + structural pins for resolve_cluster_name (lib/cluster.sh).
#
# No external test runner; invoke directly:
#   bash aether/tests/integration/test/test-cluster-names.sh
#
# What this pins, and why each case exists:
#   U1/U2  the historical defaults are unchanged, per runtime — a run that sets no
#          override must behave exactly as it did before the override existed;
#   U3     THE REGRESSION: an explicit name survives the --runtime jvm branch. That
#          branch used to overwrite both names unconditionally, so a jvm arm silently
#          lost its override and collapsed back onto the shared name;
#   U4     an explicit name is honoured in container mode too;
#   U5     the PROPERTY the fix exists for: two arms resolve to DIFFERENT names, which
#          is what keeps their aether-cluster labels, state dirs and reaper scopes apart;
#   U6     an empty override is treated as absent, not as an empty cluster name;
#   U7/U8  invalid name and invalid slot are refused with rc 2 BEFORE any provisioning —
#          a bad Hetzner label would otherwise surface as an opaque API error after VMs
#          are already billed;
#   S1     STRUCTURAL: run-tests.sh must capture CLUSTER_{A,B}_NAME_EXPLICIT strictly
#          ABOVE the first resolver call. A capture placed below the defaults would read
#          back the default this script just assigned and make every override a silent
#          no-op — a bug no unit test of the pure function can see, because the function
#          would still be correct.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# common.sh requires TARGET_HOST under `set -u`.
export TARGET_HOST="cluster-names-test"

# shellcheck source=../lib/cluster.sh
source "${INTEG_DIR}/lib/cluster.sh"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

# eq <label> <expected> <actual>
eq() {
    if [ "$2" = "$3" ]; then ok "$1"; else fail "$1 — expected '$2', got '$3'"; fi
}

# rc_is <label> <expected_rc> <actual_rc>
rc_is() {
    if [ "$2" = "$3" ]; then ok "$1"; else fail "$1 — expected rc=$2, got rc=$3"; fi
}

echo "resolve_cluster_name — defaults preserved"
CLUSTER_A_NAME_EXPLICIT=""; CLUSTER_B_NAME_EXPLICIT=""
eq "U1 container a -> test-a"                "test-a"            "$(resolve_cluster_name a container)"
eq "U1 container b -> test-b"                "test-b"            "$(resolve_cluster_name b container)"
eq "U2 jvm a -> cloud-test-a-jvm"            "cloud-test-a-jvm"  "$(resolve_cluster_name a jvm)"
eq "U2 jvm b -> cloud-test-b-jvm"            "cloud-test-b-jvm"  "$(resolve_cluster_name b jvm)"

echo "resolve_cluster_name — explicit override wins"
CLUSTER_A_NAME_EXPLICIT="arm2-a"; CLUSTER_B_NAME_EXPLICIT="arm2-b"
eq "U3 jvm b override survives jvm rename"   "arm2-b"            "$(resolve_cluster_name b jvm)"
eq "U3 jvm a override survives jvm rename"   "arm2-a"            "$(resolve_cluster_name a jvm)"
eq "U4 container b override honoured"        "arm2-b"            "$(resolve_cluster_name b container)"
eq "U4 container a override honoured"        "arm2-a"            "$(resolve_cluster_name a container)"

echo "resolve_cluster_name — two arms stay distinct (the isolation property)"
arm1_b=$(CLUSTER_B_NAME_EXPLICIT="" resolve_cluster_name b jvm)
arm2_b=$(CLUSTER_B_NAME_EXPLICIT="campaign-arm-d" resolve_cluster_name b jvm)
if [ -n "$arm1_b" ] && [ -n "$arm2_b" ] && [ "$arm1_b" != "$arm2_b" ]; then
    ok "U5 two jvm arms resolve to different names ('${arm1_b}' vs '${arm2_b}')"
else
    fail "U5 two jvm arms collided on '${arm1_b}' — this is the reaper cross-reap bug"
fi

echo "resolve_cluster_name — empty override is absent, not empty"
CLUSTER_A_NAME_EXPLICIT=""; CLUSTER_B_NAME_EXPLICIT=""
eq "U6 empty override falls back to default" "cloud-test-b-jvm"  "$(resolve_cluster_name b jvm)"

echo "resolve_cluster_name — invalid input refused before provisioning"
CLUSTER_B_NAME_EXPLICIT="not a valid label!"
out=$(resolve_cluster_name b jvm 2>/dev/null); rc=$?
rc_is "U7 invalid name rejected"             2                   "$rc"
eq    "U7 invalid name prints nothing"       ""                  "$out"
CLUSTER_B_NAME_EXPLICIT=""
out=$(resolve_cluster_name z jvm 2>/dev/null); rc=$?
rc_is "U8 invalid slot rejected"             2                   "$rc"

echo "run-tests.sh — structural wiring"
RT="${INTEG_DIR}/run-tests.sh"
cap_line=$(grep -n '^CLUSTER_A_NAME_EXPLICIT=' "$RT" | head -1 | cut -d: -f1)
use_line=$(grep -n 'resolve_cluster_name a container' "$RT" | head -1 | cut -d: -f1)
if [ -n "$cap_line" ] && [ -n "$use_line" ] && [ "$cap_line" -lt "$use_line" ]; then
    ok "S1 explicit capture (line ${cap_line}) precedes first resolver call (line ${use_line})"
else
    fail "S1 capture/use ordering wrong — capture='${cap_line}' use='${use_line}'; an override would be a silent no-op"
fi

# No literal cluster-name assignment may return: it would bypass the resolver entirely.
literals=$(grep -cE '^[[:space:]]*CLUSTER_[AB]_NAME="(test-[ab]|cloud-test-[ab]-jvm)"[[:space:]]*$' "$RT" || true)
if [ "$literals" = "0" ]; then
    ok "S2 no literal cluster-name assignment bypasses the resolver"
else
    fail "S2 ${literals} literal cluster-name assignment(s) reintroduced — override would be overwritten"
fi

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ] || exit 1
