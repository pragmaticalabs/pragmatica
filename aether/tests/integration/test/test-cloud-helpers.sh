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
CTM_NODE="aether-test-b-node-01JCTMREPLACEMENT0000000001"
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

# --- _resolve_live_endpoint on cloud: dead-pin memory and per-run endpoint state --------------------
# curl is stubbed to record every probed URL. A URL answers iff it is listed in "$ALIVE" (a file, so
# liveness can flip between calls). Two calls are made; the result and probes of the SECOND are returned.
resolve2() {  # retry_s pin_alive_on_2nd sticky_run sticky_ep -> "result2|probes2"
    ( export TMPDIR="$(mktemp -d)"; ENV_TYPE=cloud; CLUSTER_ID=b; NODE_COUNT=1; CLOUD_PIN_RETRY_S="$1"
      CLUSTER_ENDPOINT="http://pin:8080"; export AETHER_RUN_ID=r1
      [ -n "$4" ] && printf '%s' "$4" > "${TMPDIR}/aether-live-endpoint-b-$3"
      [ "$3" != r1 ] && [ -n "$4" ] && printf '%s' "$4" > "${TMPDIR}/aether-live-endpoint-b"  # pre-scoping name
      ALIVE="${TMPDIR}/alive"; printf 'http://live:8080/health/live\n' > "$ALIVE"
      PROBES="${TMPDIR}/probes"; : > "$PROBES"
      curl() { local u; for u in "$@"; do case "$u" in http*) echo "$u" >> "$PROBES" ;; esac; done
               local x; for x in "$@"; do case "$x" in http*) grep -qxF "$x" "$ALIVE" && return 0 ;; esac; done; return 7; }
      to_node_id() { return 1; }
      _resolve_live_endpoint >/dev/null 2>&1
      [ "$2" = yes ] && echo "http://pin:8080/health/live" >> "$ALIVE"
      : > "$PROBES"; out=$(_resolve_live_endpoint 2>/dev/null)
      printf '%s|%s' "$out" "$(tr '\n' ',' < "$PROBES")"; rm -rf "$TMPDIR" )
}
got=$(resolve2 30 no r1 "http://live:8080")
[ "$got" = "http://live:8080|http://live:8080/health/live," ] \
    && ok "_resolve_live_endpoint cloud: a dead pin is probed once, then skipped inside the retry window" \
    || fail "_resolve_live_endpoint cloud (dead-pin memory): got '${got}'"
got=$(resolve2 0 yes r1 "http://live:8080")
[ "$got" = "http://pin:8080|http://pin:8080/health/live," ] \
    && ok "_resolve_live_endpoint cloud: after the window a revived pin is preferred again" \
    || fail "_resolve_live_endpoint cloud (pin revived): got '${got}'"
got=$(resolve2 30 no r0 "http://live:8080")
case "$got" in
    *"http://live:8080"*) fail "_resolve_live_endpoint cloud: read ANOTHER run's (or the pre-scoping) endpoint file: '${got}'" ;;
    *) ok "_resolve_live_endpoint cloud: endpoint files from another run, or the pre-scoping name, are never read" ;;
esac
got=$(resolve2 30 no r1 "http://dead-sticky:8080")
case "$got" in
    *"dead-sticky"*) fail "_resolve_live_endpoint cloud: re-probed a dead remembered endpoint: '${got}'" ;;
    *) ok "_resolve_live_endpoint cloud: a dead remembered endpoint is forgotten, not re-probed every call" ;;
esac

# resolve_seq SETUP BETWEEN -> "probes1|out2|probes2|record": SETUP is eval'd before the first call and
# BETWEEN before the second, so a case can stage files, flip liveness or change the pin. record is the
# dead-pin record after the second call ("none" if absent).
resolve_seq() {
    ( export TMPDIR="$(mktemp -d)"; ENV_TYPE=cloud; CLUSTER_ID=b; NODE_COUNT=1; CLOUD_PIN_RETRY_S=30
      CLOUD_MGMT_PORT=8080; CLUSTER_ENDPOINT="http://pin:8080"; export AETHER_RUN_ID=r1
      ALIVE="${TMPDIR}/alive"; printf 'http://live:8080/health/live\n' > "$ALIVE"
      PROBES="${TMPDIR}/probes"; : > "$PROBES"
      curl() { local u; for u in "$@"; do case "$u" in http*) echo "$u" >> "$PROBES" ;; esac; done
               local x; for x in "$@"; do case "$x" in http*) grep -qxF "$x" "$ALIVE" && return 0 ;; esac; done; return 7; }
      to_node_id() { return 1; }
      eval "$1"; _resolve_live_endpoint >/dev/null 2>&1; p1=$(tr '\n' ',' < "$PROBES")
      eval "$2"; : > "$PROBES"; out=$(_resolve_live_endpoint 2>/dev/null); p2=$(tr '\n' ',' < "$PROBES")
      rec=$(cat "$(_pin_dead_file)" 2>/dev/null || echo none)
      printf '%s|%s|%s|%s' "$p1" "$out" "$p2" "$rec"; rm -rf "$TMPDIR" )
}
PIN_PROBE="http://pin:8080/health/live,"
got=$(resolve_seq '' 'echo "http://pin:8080/health/live" >> "$ALIVE"; CLOUD_PIN_RETRY_S=0')
[ "$got" = "${PIN_PROBE}|http://pin:8080|${PIN_PROBE}|none" ] \
    && ok "_resolve_live_endpoint cloud: a pin that answers again clears its dead-pin record" \
    || fail "_resolve_live_endpoint cloud (record cleared on pin success): got '${got}'"
got=$(resolve_seq '' 'CLUSTER_ENDPOINT="http://pin2:8080"')
case "$got" in
    *"|http://pin2:8080/health/live,"*) ok "_resolve_live_endpoint cloud: the skip applies only to the pin that died, not a new pin" ;;
    *) fail "_resolve_live_endpoint cloud (same-pin guard): a new pin was not probed first: '${got}'" ;;
esac
STAGE_OTHER='now=$(date +%s); for f in b b- b-r0 b-norun; do printf "%s http://pin:8080\n" "$now" > "${TMPDIR}/aether-pin-dead-$f"; done'
got=$(resolve_seq "$STAGE_OTHER" '')
case "$got" in
    "${PIN_PROBE}"*) ok "_resolve_live_endpoint cloud: another run's dead-pin record (or an unscoped one) never skips this run's pin" ;;
    *) fail "_resolve_live_endpoint cloud (dead-pin record scoped per run): pin not probed first: '${got}'" ;;
esac
for bad in garbage 12abc; do
    got=$(resolve_seq "printf '%s http://pin:8080\n' $bad > \"\$(_pin_dead_file)\"" '')
    case "$got" in
        "${PIN_PROBE}|"*"|"[0-9]*" http://pin:8080") ok "_resolve_live_endpoint cloud: a malformed record ('${bad}') is treated as expired and rewritten" ;;
        *) fail "_resolve_live_endpoint cloud (malformed record '${bad}'): got '${got}'" ;;
    esac
done
got=$(resolve_seq 'printf "%s http://pin:8080\n" "$(( $(date +%s) - 31 ))" > "$(_pin_dead_file)"' '')
case "$got" in
    "${PIN_PROBE}"*) ok "_resolve_live_endpoint cloud: a record older than the window no longer skips the pin" ;;
    *) fail "_resolve_live_endpoint cloud (expired record): pin not probed: '${got}'" ;;
esac
got=$(resolve_seq 'NODE_COUNT=2; to_node_id() { echo "$1"; }; cloud_public_ip() { case "$1" in node-1) echo pin ;; node-2) echo live ;; esac; }' '')
case "$got" in
    "${PIN_PROBE}http://live:8080/health/live,|"*) ok "_resolve_live_endpoint cloud: the fallback scan does not re-probe the dead pin under node-1's address" ;;
    *) fail "_resolve_live_endpoint cloud (scan skips the pin): got '${got}'" ;;
esac
got=$(resolve_seq 'NODE_COUNT=2; to_node_id() { echo "$1"; }; cloud_public_ip() { case "$1" in node-1) echo pin ;; node-2) echo dead2 ;; esac; }' \
                  'echo "http://pin:8080/health/live" >> "$ALIVE"')
[ "$got" = "${PIN_PROBE}http://dead2:8080/health/live,|http://pin:8080|http://dead2:8080/health/live,${PIN_PROBE}|none" ] \
    && ok "_resolve_live_endpoint cloud: a pin revived inside the window is still found when every other node is dead" \
    || fail "_resolve_live_endpoint cloud (revived pin, last resort): got '${got}'"
got=$(CLOUD_PIN_RETRY_S=abc bash -c 'source "$1/lib/common.sh" 2>&1 >/dev/null; echo "=$CLOUD_PIN_RETRY_S"' _ "$INTEG_DIR" 2>&1)
case "$got" in
    *"CLOUD_PIN_RETRY_S='abc'"*"=30") ok "common.sh: a non-numeric CLOUD_PIN_RETRY_S warns and falls back to 30" ;;
    *) fail "common.sh (CLOUD_PIN_RETRY_S validation): got '${got}'" ;;
esac

# --- reap_cloud_cluster: a CTM replacement is recognised by `aether-<cluster>-node-*` (#1487) ------
# Both rows carry ONLY an aether-node-id label (no aether-cluster, no seed IP), so the node-id
# pattern is the only rule that can admit or refuse them: this cluster's replacement is deleted,
# another cluster's node is left alone.
reap_probe() {
    ( source "${INTEG_DIR}/lib/cluster.sh" >/dev/null 2>&1
      W="$(mktemp -d)"; : > "$W/deleted"; echo 0 > "$W/lists"
      sleep() { :; }; _run_with_timeout() { shift; "$@"; }; _cloud_seed_ips() { :; }
      log_info() { :; }; log_warn() { :; }; log_fail() { echo "FAIL $*"; }
      hcloud() {
          case "$1 $2" in
              "server list")
                  local n; n=$(cat "$W/lists"); echo $((n + 1)) > "$W/lists"
                  [ "$n" -eq 0 ] || return 0
                  printf '%s\n' "301 vm-a running 198.51.100.1 aether-node-id=aether-test-b-node-01REPLACEMENT" \
                                 "302 vm-b running 198.51.100.2 aether-node-id=aether-other-node-01STRANGER" ;;
              "server delete") echo "$3" >> "$W/deleted" ;;
          esac
      }
      reap_cloud_cluster test-b; rc=$?
      printf 'rc=%s deleted=%s' "$rc" "$(tr '\n' ',' < "$W/deleted")"; rm -rf "$W" )
}
got=$(reap_probe)
[ "$got" = "rc=0 deleted=301," ] \
    && ok "reap_cloud_cluster: deletes this cluster's CTM replacement by node-id, leaves another cluster's node" \
    || fail "reap_cloud_cluster (node-id pattern): got '${got}'"

unset -f hcloud api_get ssh
unset STUB_SSH_RC STUB_ACTIVE_STATE STUB_EXEC_MAIN_STATUS

# --- 02w: a transient entity refusal is retried, not classified (#1501) --------------------
# The suite's own read_amount / entity_post_any / transient_failure_type are extracted verbatim and
# driven by a stub `_api_call` that pops one body per call from a queue file (`__DOWN__` = transport
# failure). The FoldInProgress body is the one captured in the s27 cluster-B run log (line 1428),
# byte for byte — including its truncation at 200 bytes by the suite's own `head -c 200`.
W02="${INTEG_DIR}/suites/02w-entity-crash/test-entity-crash-durability.sh"
FOLD_BODY="$(cat "${SCRIPT_DIR}/fixtures/s27-02w-foldinprogress-body.txt")"
W_WORK="$(mktemp -d)"
w_defs() {
    grep -E '^(KEY_PREFIX|ENTITY_TRANSIENT_FAILURE_TYPES|TRANSIENT_READ_DEADLINE_S|TRANSIENT_READ_BACKOFF_S)=' "$W02"
    local fn
    for fn in key_for amount_for entity_post_any transient_failure_type read_amount \
              test_pre_kill_state_readable test_every_acked_entity_survives_the_crash; do
        awk -v f="$fn" '$0 ~ "^" f "\\(\\) \\{" {on=1} on {print} on && /^\}/ {exit}' "$W02"
    done
}
w_defs > "${W_WORK}/defs.sh"
for fn in entity_post_any transient_failure_type read_amount test_pre_kill_state_readable test_every_acked_entity_survives_the_crash; do
    grep -q "^${fn}() {" "${W_WORK}/defs.sh" || fail "W0 ${fn} not extracted from the 02w suite (examined NOTHING)"
done
w_run() {  # <snippet> <queued bodies...> -> "rc=<rc> out=<stdout> calls=<n>"; stderr -> $W_WORK/err
    local snippet="$1"; shift
    printf '%s\n' "$@" > "${W_WORK}/queue"; : > "${W_WORK}/calls"
    ( source "${W_WORK}/defs.sh"
      ENTITY_APP_ENDPOINTS="http://w-stub:8070"; KILL_CONFIRMED=1
      TRANSIENT_READ_BACKOFF_S=0
      [ -n "${W_DEADLINE:-}" ] && TRANSIENT_READ_DEADLINE_S="$W_DEADLINE"
      refresh_app_endpoints() { :; }
      log_warn() { echo "WARN $*" >&2; }; log_error() { echo "ERROR $*" >&2; }
      log_fail() { echo "FAIL $*" >&2; }; log_pass() { echo "PASS $*" >&2; }; log_info() { echo "INFO $*" >&2; }
      _api_call() {
          local b; printf "%s\n" "$3" >> "${W_WORK}/calls"
          b=$(head -1 "${W_WORK}/queue"); tail -n +2 "${W_WORK}/queue" > "${W_WORK}/queue.n"; mv "${W_WORK}/queue.n" "${W_WORK}/queue"
          [ "$b" = "__DOWN__" ] || [ -z "$b" ] && return 1
          printf '%s' "$b"
      }
      out=$(eval "$snippet"); rc=$?
      printf 'rc=%s out=%s calls=%s' "$rc" "$out" "$(grep -c . "${W_WORK}/calls")" ) 2> "${W_WORK}/err"
}
FOUND3='{"outcome":"found","orderId":"ENTDUR-00003-Z","amount":24}'
got=$(w_run 'read_amount ENTDUR-00003-Z' "$FOLD_BODY" "$FOLD_BODY" "$FOUND3")
[ "$got" = "rc=0 out=24 calls=3" ] \
    && ok "W1 captured FoldInProgress body is retried until the key reads back (rc 0, amount 24)" \
    || fail "W1 FoldInProgress retry: got '${got}'; $(tr '\n' '|' < "${W_WORK}/err")"
got=$(W_DEADLINE=0 w_run 'read_amount ENTDUR-00003-Z' "$FOLD_BODY")
if [ "$got" = "rc=5 out= calls=1" ] && grep -q 'transient FoldInProgress until the 0s retry deadline' "${W_WORK}/err" \
   && grep -q 'still replaying its log' "${W_WORK}/err" && ! grep -q 'no node answered' "${W_WORK}/err"; then
    ok "W2 FoldInProgress past the deadline is rc 5, reported with the true last body, never 'no node answered'"
else fail "W2 transient at deadline: got '${got}'; $(tr '\n' '|' < "${W_WORK}/err")"; fi
got=$(w_run 'read_amount ENTDUR-00003-Z' '{"outcome":"failed","failureType":"EntityCorrupt","failure":"x"}' "$FOUND3")
[ "$got" = "rc=4 out= calls=1" ] && grep -q 'not a transient type' "${W_WORK}/err" \
    && ok "W3 a failureType off the allow-list is NOT retried (rc 4 after one call)" \
    || fail "W3 non-transient: got '${got}'; $(tr '\n' '|' < "${W_WORK}/err")"
got=$(w_run 'read_amount ENTDUR-00003-Z' "$FOLD_BODY" '{"outcome":"absent","orderId":"ENTDUR-00003-Z"}')
[ "$got" = "rc=3 out= calls=2" ] \
    && ok "W4 genuine loss stays detectable: FoldInProgress then ABSENT is rc 3" \
    || fail "W4 absent after transient: got '${got}'"
got=$(w_run 'read_amount ENTDUR-00003-Z' __DOWN__ __DOWN__)
[ "$got" = "rc=4 out= calls=2" ] && grep -q 'no node answered' "${W_WORK}/err" \
    && ok "W5 transport failure on every endpoint is still rc 4 'no node answered'" \
    || fail "W5 no answer: got '${got}'; $(tr '\n' '|' < "${W_WORK}/err")"
printf '3\n' > "${W_WORK}/acked"
got=$(w_run "ACKED_PRE='${W_WORK}/acked'; test_pre_kill_state_readable" "$FOLD_BODY" "$FOUND3")
[ "$got" = "rc=0 out= calls=2" ] && grep -q '^PASS every pre-kill ACKED entity reads back' "${W_WORK}/err" \
    && [ "$(grep -c "\"orderId\":\"ENTDUR-00003-Z\"" "${W_WORK}/calls")" = 2 ] \
    && ok "W6 pre-kill readback passes through a FoldInProgress refusal (the s27 red)" \
    || fail "W6 pre-kill readback: got '${got}'; $(tr '\n' '|' < "${W_WORK}/err")"
: > "${W_WORK}/empty"
got=$(w_run "ACKED_PRE='${W_WORK}/acked'; ACKED_DURING='${W_WORK}/empty'; test_every_acked_entity_survives_the_crash" "$FOLD_BODY" "$FOUND3")
[ "$got" = "rc=0 out= calls=2" ] && grep -q '^PASS all 1 ACKED entities survived' "${W_WORK}/err" \
    && [ "$(grep -c "\"orderId\":\"ENTDUR-00003-Z\"" "${W_WORK}/calls")" = 2 ] \
    && ok "W7 post-kill readback passes through a FoldInProgress refusal" \
    || fail "W7 post-kill readback: got '${got}'; $(tr '\n' '|' < "${W_WORK}/err")"
got=$(w_run "ACKED_PRE='${W_WORK}/acked'; ACKED_DURING='${W_WORK}/empty'; test_every_acked_entity_survives_the_crash" "$FOLD_BODY" '{"outcome":"absent"}')
[ "$got" = "rc=1 out= calls=2" ] && grep -q '^FAIL 1/1 lost' "${W_WORK}/err" \
    && ok "W8 post-kill: an ACKED key that is truly ABSENT after the refusal still FAILS as loss" \
    || fail "W8 post-kill loss: got '${got}'; $(tr '\n' '|' < "${W_WORK}/err")"
rm -rf "$W_WORK"

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
