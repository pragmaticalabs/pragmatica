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
# (-> /api/nodes/endpoint/<id>). With no live cluster, the real api_get would probe
# the bogus TARGET_HOST over curl and stall every test that misses bootstrap-state.
# Stub api_get for the whole file so the harness never touches the network: return
# the A1 wire shape for the known CTM-replacement node, rc 1 (no endpoint) for
# everything else — so unknown ids fail cleanly and instantly.
CTM_NODE="aether-cloud-test-b-node-01JCTMREPLACEMENT0000000001"
api_get() {
    case "$1" in
        "/api/nodes/endpoint/${CTM_NODE}")
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
#       bootstrap-state.json) via this cluster's /api/nodes/endpoint mgmt API,
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

unset -f hcloud api_get

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
