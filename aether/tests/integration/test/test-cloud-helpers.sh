#!/bin/bash
# test-cloud-helpers.sh — manual-run unit tests for cloud_public_ip / cloud_ssh / cloud_node_ip /
# node_app_endpoints (cloud branch).
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
# cloud_ssh reads AETHER_SSH_KEY under `set -u`; a CI runner or container has none, so
# the jvm_unit_* tests (ssh stubbed) must not depend on the caller's environment (#1051).
export AETHER_SSH_KEY="${AETHER_SSH_KEY:-/nonexistent/cloud-helpers-test-key}"

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
# H1/H2 (#1051): jvm_unit_show / jvm_unit_field / jvm_unit_is_drain_halt /
# jvm_unit_assert_drain_halt (lib/common.sh). On --runtime jvm there is no
# docker daemon on the cloud VM — the node runs as systemd unit `aether-node` —
# so the self-drain halt reason is read via
# `systemctl show aether-node --property=...` over SSH instead of
# `docker inspect`. These tests stub `ssh` (cloud_ssh's underlying command;
# node-id -> IP resolution reuses the CTM-replacement `api_get` stub above —
# NOT yet unset, see the combined `unset -f hcloud api_get ssh` below — so no
# new fixture is needed). Every negative case asserts the failure MESSAGE, so a
# missing helper (rc 127) cannot satisfy it. The same functions, driven through
# the suite's own _confirm_survivor_departure and test_survivor_exit_codes_are_two,
# are covered in test/test-chaos-harness.sh.
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

# 16) jvm_unit_assert_drain_halt: ActiveState=failed, ExecMainStatus=2 -> PASS
# (the positive control for 17-19: the same call can pass).
STUB_SSH_RC=0 STUB_ACTIVE_STATE=failed STUB_EXEC_MAIN_STATUS=2
out=$(jvm_unit_assert_drain_halt "$CTM_NODE" "test-node" 2>&1); rc=$?
if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -qF "[PASS]" \
    && printf '%s' "$out" | grep -qF "test-node systemd unit ActiveState=failed ExecMainStatus=2"; then
    ok "jvm_unit_assert_drain_halt: failed/2 -> PASS"
else
    fail "jvm_unit_assert_drain_halt: failed/2 should PASS (rc=${rc}): ${out}"
fi

# 17) ExecMainStatus=0 (graceful shutdown, NOT a self-drain) -> FAIL, for that reason.
STUB_SSH_RC=0 STUB_ACTIVE_STATE=failed STUB_EXEC_MAIN_STATUS=0
out=$(jvm_unit_assert_drain_halt "$CTM_NODE" "test-node" 2>&1); rc=$?
if [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -qF "got ActiveState='failed' ExecMainStatus='0'" \
    && [ "${out#*command not found}" = "$out" ]; then
    ok "jvm_unit_assert_drain_halt: ExecMainStatus=0 -> FAIL naming the value read"
else
    fail "jvm_unit_assert_drain_halt: ExecMainStatus=0 should FAIL naming ExecMainStatus='0' (rc=${rc}): ${out}"
fi

# 18) An SSH/transport error must FAIL as unreadable, never pass on an empty read.
STUB_SSH_RC=255
out=$(jvm_unit_assert_drain_halt "$CTM_NODE" "test-node" 2>&1); rc=$?
if [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -qF "test-node systemd unit unreadable: SSH/systemctl failed (rc=255)"; then
    ok "jvm_unit_assert_drain_halt: SSH error (rc=255) -> FAIL as unreadable"
else
    fail "jvm_unit_assert_drain_halt: SSH error should FAIL as unreadable (rc=${rc}): ${out}"
fi
STUB_SSH_RC=0

# 19) ActiveState=active with ExecMainStatus=2 -> FAIL: a still-running unit is never a
# drain halt, whatever a stale ExecMainStatus reads (the exit-code step and S19 tier 2
# share this predicate).
STUB_SSH_RC=0 STUB_ACTIVE_STATE=active STUB_EXEC_MAIN_STATUS=2
out=$(jvm_unit_assert_drain_halt "$CTM_NODE" "test-node" 2>&1); rc=$?
if [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -qF "got ActiveState='active' ExecMainStatus='2'" \
    && [ "${out#*command not found}" = "$out" ]; then
    ok "jvm_unit_assert_drain_halt: ActiveState=active, ExecMainStatus=2 -> FAIL naming the state read"
else
    fail "jvm_unit_assert_drain_halt: ActiveState=active should FAIL naming ActiveState='active' (rc=${rc}): ${out}"
fi

# 20) jvm_unit_is_drain_halt truth table: only failed/2 is the drain halt.
table=""
for pair in "failed 2" "active 2" "failed 0" "inactive 2" "failed 20" " "; do
    set -- $pair
    if jvm_unit_is_drain_halt "${1:-}" "${2:-}"; then table="${table}[${1:-}/${2:-}=halt]"; else table="${table}[${1:-}/${2:-}=no]"; fi
done
if [ "$table" = "[failed/2=halt][active/2=no][failed/0=no][inactive/2=no][failed/20=no][/=no]" ]; then
    ok "jvm_unit_is_drain_halt: only failed/2 is the drain halt"
else
    fail "jvm_unit_is_drain_halt truth table wrong: ${table}"
fi
set --


# --- node_app_endpoints on cloud (2026-09-24): one http://<public-ip>:<app port> per live core ------
# Uses the REAL cloud_public_ip against this file's fixture: node-1 resolves from bootstrap-state,
# the CTM replacement resolves via /api/v1/nodes/endpoint, and "ghost" misses — which cloud_public_ip
# reports through log_fail on STDOUT, the exact output a capture must not mistake for an address.
app_eps() {  # members... -> "rc|<stdout joined by ,>"
    ( CLOUD_MODE=true; APP_PORT=8070
      source "${INTEG_DIR}/lib/cluster.sh" >/dev/null 2>&1
      api_get() {
          case "$1" in
              "/api/v1/nodes/endpoint/${CTM_NODE}")
                  printf '{"nodeId":"%s","address":"178.105.192.36:7100","reachable":true}' "$CTM_NODE" ;;
              *) return 1 ;;
          esac
      }
      STUB_MEMBERS="$*"
      cloud_running_cores() { printf '%s\n' $STUB_MEMBERS; }
      out=$(node_app_endpoints 2>/dev/null); rc=$?
      printf '%s|%s' "$rc" "$(printf '%s' "$out" | tr '\n' ',')" )
}
got=$(app_eps node-1 "$CTM_NODE" ghost)
[ "$got" = "0|http://203.0.113.10:8070,http://178.105.192.36:8070" ] \
    && ok "node_app_endpoints cloud: seed + CTM replacement resolved, unresolvable member skipped" \
    || fail "node_app_endpoints cloud: expected '0|http://203.0.113.10:8070,http://178.105.192.36:8070', got '${got}'"
got=$(app_eps ghost other-ghost)
[ "$got" = "1|" ] \
    && ok "node_app_endpoints cloud: nothing resolvable -> rc 1, no output (never a [FAIL] line as an endpoint)" \
    || fail "node_app_endpoints cloud: expected '1|', got '${got}'"
got=$(app_eps)
[ "$got" = "1|" ] \
    && ok "node_app_endpoints cloud: no members -> rc 1, no output" \
    || fail "node_app_endpoints cloud (no members): expected '1|', got '${got}'"

# --- 02y pick_publish_endpoint: a re-pick must not hand back the endpoint that just failed ---------
# A killed member stays in the membership (and the endpoint list) until its departure commits, so a
# plain `head -1` re-pick returned the node that had just been killed.
pick_ep() {  # exclude -> picked endpoint (or "rc=N")
    ( log_fail() { :; }
      node_app_endpoints() { printf 'http://10.0.0.1:8070\nhttp://10.0.0.2:8070\nhttp://10.0.0.3:8070\n'; }
      eval "$(awk '/^pick_publish_endpoint\(\) \{/,/^\}/' "${INTEG_DIR}/suites/02y-stream-crash/test-stream-crash-durability.sh")"
      pick_publish_endpoint "$1" && printf '%s' "$STREAM_PUBLISH_ENDPOINT" || printf 'rc=%s' "$?" )
}
got=$(pick_ep "")
[ "$got" = "http://10.0.0.1:8070" ] && ok "02y pick_publish_endpoint: first live endpoint when nothing failed" \
    || fail "02y pick_publish_endpoint (no exclude): got '${got}'"
got=$(pick_ep "http://10.0.0.1:8070")
[ "$got" = "http://10.0.0.2:8070" ] && ok "02y pick_publish_endpoint: re-pick skips the endpoint that just failed" \
    || fail "02y pick_publish_endpoint (exclude first): got '${got}'"

unset -f hcloud api_get ssh
unset STUB_SSH_RC STUB_ACTIVE_STATE STUB_EXEC_MAIN_STATUS

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
