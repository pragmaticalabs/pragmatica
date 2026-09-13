#!/bin/bash
# test-cloud-helpers.sh — manual-run unit tests for cloud_public_ip / cloud_ssh / cloud_node_ip.
#
# No external test runner; invoke directly:
#   bash aether/tests/integration/test/test-cloud-helpers.sh
#
# Stages a synthetic ~/.aether/clusters/<name>/bootstrap-state.json from the
# fixture, exports BOOTSTRAP_CLUSTER_NAME, then asserts cloud_public_ip behaviour.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
FIXTURE="${SCRIPT_DIR}/fixtures/bootstrap-state.json"

# common.sh requires TARGET_HOST.
export TARGET_HOST="cloud-helpers-test"
export ENV_TYPE="cloud"
export CLOUD_SOURCE_NAME="hetzner-eu"

# Stage the fixture under a throwaway cluster name to avoid clobbering real state.
TEST_CLUSTER="cloud-helpers-test-$$"
TEST_DIR="${HOME}/.aether/clusters/${TEST_CLUSTER}"
mkdir -p "$TEST_DIR"
cp "$FIXTURE" "${TEST_DIR}/bootstrap-state.json"
trap 'rm -rf "${TEST_DIR}"' EXIT
export BOOTSTRAP_CLUSTER_NAME="$TEST_CLUSTER"

# shellcheck source=../lib/common.sh
source "${INTEG_DIR}/lib/common.sh"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

# OFFLINE GUARD: cloud_public_ip's CTM-replacement fallback calls api_get
# (-> /api/v1/nodes/endpoint/<id>). With no live cluster, the real api_get would probe
# the bogus TARGET_HOST over curl and stall every test that misses bootstrap-state.
# Stub api_get for the whole file so the harness never touches the network: return
# the A1 wire shape for the known CTM-replacement node, rc 1 (no endpoint) for
# everything else — so unknown ids fail cleanly and instantly.
CTM_NODE="aether-cloud-test-b-node-01JCTMREPLACEMENT0000000001"
api_get() {
    case "$1" in
        "/api/v1/nodes/endpoint/${CTM_NODE}")
            printf '{"nodeId":"%s","address":"178.105.192.36:7100","reachable":true}' "$CTM_NODE" ;;
        *) return 1 ;;
    esac
}

# 1) node-1 maps to the first IP (hetzner-eu-core-0).
got=$(cloud_public_ip "node-1" 2>/dev/null) && [ "$got" = "203.0.113.10" ] \
    && ok "cloud_public_ip node-1 -> 203.0.113.10" \
    || fail "cloud_public_ip node-1 expected 203.0.113.10, got '${got}'"

# 2) node-3 maps to the third IP (hetzner-eu-core-2).
got=$(cloud_public_ip "node-3" 2>/dev/null) && [ "$got" = "203.0.113.12" ] \
    && ok "cloud_public_ip node-3 -> 203.0.113.12" \
    || fail "cloud_public_ip node-3 expected 203.0.113.12, got '${got}'"

# 3) Raw bootstrap nodeId is accepted as-is.
got=$(cloud_public_ip "hetzner-eu-core-1" 2>/dev/null) && [ "$got" = "203.0.113.11" ] \
    && ok "cloud_public_ip hetzner-eu-core-1 -> 203.0.113.11" \
    || fail "cloud_public_ip hetzner-eu-core-1 expected 203.0.113.11, got '${got}'"

# 4) Unknown node returns failure.
if cloud_public_ip "node-99" >/dev/null 2>&1; then
    fail "cloud_public_ip node-99 should fail (no such node)"
else
    ok "cloud_public_ip node-99 fails as expected"
fi

# 5) Missing argument returns failure (rc=2).
cloud_public_ip >/dev/null 2>&1; rc=$?
if [ "$rc" -eq 2 ]; then
    ok "cloud_public_ip without args fails fast (rc=2)"
else
    fail "cloud_public_ip no-arg expected rc=2, got rc=${rc}"
fi

# 6) BOOTSTRAP_CLUSTER_NAME unset is a hard failure (rc=2).
(
    unset BOOTSTRAP_CLUSTER_NAME CLOUD_BOOTSTRAP_CLUSTER
    cloud_public_ip "node-1" >/dev/null 2>&1; rc=$?
    exit "$rc"
)
rc=$?
if [ "$rc" -eq 2 ]; then
    ok "cloud_public_ip without BOOTSTRAP_CLUSTER_NAME fails fast (rc=2)"
else
    fail "cloud_public_ip no-cluster expected rc=2, got rc=${rc}"
fi

# 7) Missing state file is a recoverable failure (rc=1).
(
    export BOOTSTRAP_CLUSTER_NAME="cloud-helpers-test-nonexistent-$$"
    cloud_public_ip "node-1" >/dev/null 2>&1; rc=$?
    exit "$rc"
)
rc=$?
if [ "$rc" -eq 1 ]; then
    ok "cloud_public_ip missing state-file fails (rc=1)"
else
    fail "cloud_public_ip missing-state expected rc=1, got rc=${rc}"
fi

# 8) cloud_node_ip delegates to cloud_public_ip (back-compat shim).
got=$(cloud_node_ip "node-2" 2>/dev/null) && [ "$got" = "203.0.113.11" ] \
    && ok "cloud_node_ip node-2 -> 203.0.113.11 (delegates to cloud_public_ip)" \
    || fail "cloud_node_ip node-2 expected 203.0.113.11, got '${got}'"

# ---------------------------------------------------------------------------
# IP -> Hetzner server-id mapping (the resolver-robustness fix).
#
# These run WITHOUT a live cluster by stubbing `hcloud` and `api_get`. They prove:
#   (a) the awk field-match maps a public IP to the right numeric id against the
#       REAL `hcloud server list -o columns=id,ipv4 -o noheader` output shape;
#   (b) the OLD multi-line-JSON grep bug is gone (we never parse raw API JSON —
#       hcloud emits columns, awk splits on whitespace);
#   (c) cloud_public_ip resolves a CTM-replacement node (absent from
#       bootstrap-state.json) via this cluster's /api/v1/nodes/endpoint mgmt API,
#       stripping the ":port" from the advertised host:port address;
#   (d) cloud_server_id chains (c)->(a) end-to-end for a replacement node.
# ---------------------------------------------------------------------------

# Real `hcloud server list -o columns=id,ipv4 -o noheader` output (id <WS> ipv4 <WS> name).
HCLOUD_LISTING='142618875   46.224.128.182    aether-68b5f221
142619211   178.105.192.36    aether-b922ea38
142619398   167.233.119.28    aether-5a4f5c07'

# Stub `hcloud`: only the `server list -o columns=id,ipv4 -o noheader` form is used.
hcloud() {
    case "$*" in
        "server list -o columns=id,ipv4 -o noheader") printf '%s\n' "$HCLOUD_LISTING" ;;
        *) return 1 ;;
    esac
}

# 9) The IP->id awk mapping picks the exact row (the prompt's golden case).
sid=$(hcloud server list -o columns=id,ipv4 -o noheader \
        | awk -v ip=178.105.192.36 '$2==ip{print $1; exit}')
[ "$sid" = "142619211" ] \
    && ok "awk IP->id: 178.105.192.36 -> 142619211" \
    || fail "awk IP->id expected 142619211, got '${sid}'"

# 10) A non-matching IP yields empty (no false positive across rows).
sid=$(hcloud server list -o columns=id,ipv4 -o noheader \
        | awk -v ip=10.0.0.99 '$2==ip{print $1; exit}')
[ -z "$sid" ] \
    && ok "awk IP->id: unknown IP -> empty (no false match)" \
    || fail "awk IP->id unknown-IP expected empty, got '${sid}'"

# 11) cloud_public_ip resolves a CTM-replacement node (NOT in bootstrap-state) via
#     this cluster's mgmt API (stubbed api_get above), stripping ":port" from the
#     advertised host:port.
got=$(cloud_public_ip "$CTM_NODE" 2>/dev/null) && [ "$got" = "178.105.192.36" ] \
    && ok "cloud_public_ip CTM-node -> 178.105.192.36 (mgmt API, port stripped)" \
    || fail "cloud_public_ip CTM-node expected 178.105.192.36, got '${got}'"

# 12) cloud_server_id chains cloud_public_ip (mgmt API) -> hcloud IP->id for a
#     replacement node, with NO live cluster and NO raw-JSON parsing.
got=$(cloud_server_id "$CTM_NODE" 2>/dev/null) && [ "$got" = "142619211" ] \
    && ok "cloud_server_id CTM-node -> 142619211 (IP-based, hcloud-mapped)" \
    || fail "cloud_server_id CTM-node expected 142619211, got '${got}'"

# 13) cloud_server_id for a SEED node uses bootstrap-state IP (203.0.113.10) — which
#     this stub listing does NOT contain — so it must fail cleanly (rc 1, no id).
#     Note: log_fail writes its diagnostic to stdout, so route the whole call's
#     output to /dev/null and assert on rc alone (the contract is "numeric id on
#     stdout + rc 0" only on success).
if cloud_server_id "node-1" >/dev/null 2>&1; then
    fail "cloud_server_id node-1 should fail when no server has its IP"
else
    rc=$?
    ok "cloud_server_id node-1 -> fails cleanly when no server has its IP (rc=${rc})"
fi

# 14) cloud_server_id rejects an unimplemented provider.
got=$(CLOUD_PROVIDER=aws cloud_server_id "node-1" 2>/dev/null); rc=$?
if [ "$rc" -eq 2 ]; then
    ok "cloud_server_id rejects provider 'aws' (rc=2, not implemented)"
else
    fail "cloud_server_id provider 'aws' expected rc=2, got rc=${rc}"
fi

# ---------------------------------------------------------------------------
# H1/H2 (2026-09-13 cloud-JVM harness fixes): jvm_unit_show / jvm_unit_field /
# jvm_unit_exec_main_status_is_two (lib/common.sh). On --runtime jvm there is
# no docker daemon on the cloud VM — the node runs as systemd unit
# `aether-node` — so the self-drain halt reason is read via
# `systemctl show aether-node --property=...` over SSH instead of
# `docker inspect`. These tests stub `ssh` (cloud_ssh's underlying command;
# node-id -> IP resolution reuses the CTM-replacement `api_get` stub above —
# NOT yet unset, see the combined `unset -f hcloud api_get ssh` below — so no
# new fixture is needed) to prove:
#   (a) ExecMainStatus=2                     -> PASS
#   (b) ExecMainStatus=0 (graceful shutdown) -> FAIL
#   (c) ActiveState=active, ExecMainStatus=2 -> FAIL (H1's tier-2 signature
#       check requires BOTH fields; a still-active unit is never a drain halt)
#   (d) an SSH/transport error (rc=255)      -> FAIL, never a silent pass
# ---------------------------------------------------------------------------

# Stub `ssh`: cloud_ssh invokes `ssh "${SSH_OPTS[@]}" -i "$KEY" user@ip "$cmd"`
# — the remote command is always the LAST argument. Branch on which
# `systemctl show --property=...` was requested and on $STUB_SSH_RC to
# simulate a transport failure independent of the property set.
ssh() {
    local remote_cmd="${@: -1}"
    if [ -n "${STUB_SSH_RC:-}" ] && [ "${STUB_SSH_RC}" != "0" ]; then
        return "$STUB_SSH_RC"
    fi
    case "$remote_cmd" in
        *"--property=ExecMainStatus"*)
            printf 'ExecMainStatus=%s\n' "${STUB_EXEC_MAIN_STATUS:-2}" ;;
        *"--property=ActiveState,ExecMainStatus"*)
            printf 'ActiveState=%s\nExecMainStatus=%s\n' \
                "${STUB_ACTIVE_STATE:-failed}" "${STUB_EXEC_MAIN_STATUS:-2}" ;;
        *) return 127 ;;   # unrecognized command — "systemctl: command not found" shape
    esac
}

# 15) jvm_unit_show + jvm_unit_field: parse a canned systemctl-show reading.
STUB_SSH_RC=0 STUB_ACTIVE_STATE=failed STUB_EXEC_MAIN_STATUS=2
show=$(jvm_unit_show "$CTM_NODE" "ActiveState,ExecMainStatus")
got_active=$(jvm_unit_field "$show" "ActiveState")
got_exec=$(jvm_unit_field "$show" "ExecMainStatus")
[ "$got_active" = "failed" ] && [ "$got_exec" = "2" ] \
    && ok "jvm_unit_show/jvm_unit_field parse ActiveState=failed, ExecMainStatus=2" \
    || fail "jvm_unit_show/jvm_unit_field expected failed/2, got '${got_active}'/'${got_exec}'"

# 16) jvm_unit_exec_main_status_is_two: ExecMainStatus=2 -> PASS (green case).
STUB_SSH_RC=0 STUB_EXEC_MAIN_STATUS=2
if jvm_unit_exec_main_status_is_two "$CTM_NODE" "test-node" >/dev/null 2>&1; then
    ok "jvm_unit_exec_main_status_is_two: ExecMainStatus=2 -> PASS"
else
    fail "jvm_unit_exec_main_status_is_two: ExecMainStatus=2 should PASS"
fi

# 17) jvm_unit_exec_main_status_is_two: ExecMainStatus=0 (graceful shutdown,
# NOT a self-drain) -> FAIL. This is the mutation this suite exists to catch:
# a self-drain assertion that can't tell ExecMainStatus=2 from 0 is vacuous.
STUB_SSH_RC=0 STUB_EXEC_MAIN_STATUS=0
if jvm_unit_exec_main_status_is_two "$CTM_NODE" "test-node" >/dev/null 2>&1; then
    fail "jvm_unit_exec_main_status_is_two: ExecMainStatus=0 should FAIL (not a self-drain halt)"
else
    ok "jvm_unit_exec_main_status_is_two: ExecMainStatus=0 -> FAIL"
fi

# 18) jvm_unit_exec_main_status_is_two: an SSH/transport error must FAIL, never
# be scored as a pass by a string-equality guard silently matching an empty
# read against something other than "2".
STUB_SSH_RC=255
if jvm_unit_exec_main_status_is_two "$CTM_NODE" "test-node" >/dev/null 2>&1; then
    fail "jvm_unit_exec_main_status_is_two: SSH error (rc=255) should FAIL, not silently pass"
else
    ok "jvm_unit_exec_main_status_is_two: SSH error (rc=255) -> FAIL"
fi
STUB_SSH_RC=0

# 19) H1 tier-2 signature check (ActiveState=failed AND ExecMainStatus=2):
# ActiveState=active with ExecMainStatus=2 must still FAIL — a unit that is
# still running is never a designed drain halt, regardless of what a stale
# ExecMainStatus reads. Exercises the exact two-field conjunction
# test-self-drain-quorum-loss.sh's _confirm_survivor_departure tier 2 uses.
STUB_SSH_RC=0 STUB_ACTIVE_STATE=active STUB_EXEC_MAIN_STATUS=2
show=$(jvm_unit_show "$CTM_NODE" "ActiveState,ExecMainStatus")
active_state=$(jvm_unit_field "$show" "ActiveState")
exec_status=$(jvm_unit_field "$show" "ExecMainStatus")
if [ "$active_state" = "failed" ] && [ "$exec_status" = "2" ]; then
    fail "H1 signature check: ActiveState=active should FAIL, not match the drain-halt signature"
else
    ok "H1 signature check: ActiveState=active, ExecMainStatus=2 -> FAIL (not a drain halt)"
fi

# 20) H1 tier-2 signature check: the designed halt (ActiveState=failed,
# ExecMainStatus=2) DOES match — the positive control for test 19's negative,
# proving the conjunction isn't just always-false.
STUB_SSH_RC=0 STUB_ACTIVE_STATE=failed STUB_EXEC_MAIN_STATUS=2
show=$(jvm_unit_show "$CTM_NODE" "ActiveState,ExecMainStatus")
active_state=$(jvm_unit_field "$show" "ActiveState")
exec_status=$(jvm_unit_field "$show" "ExecMainStatus")
if [ "$active_state" = "failed" ] && [ "$exec_status" = "2" ]; then
    ok "H1 signature check: ActiveState=failed, ExecMainStatus=2 -> matches drain-halt signature (positive control for test 19)"
else
    fail "H1 signature check: ActiveState=failed, ExecMainStatus=2 should match the drain-halt signature"
fi

unset -f hcloud api_get ssh
unset STUB_SSH_RC STUB_ACTIVE_STATE STUB_EXEC_MAIN_STATUS

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
