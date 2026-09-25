#!/bin/bash
# test-restore-gate.sh — pins the three cloud-evidence changes of #1482 with stubs only
# (no network, no cloud, no ssh to a real host):
#   R1-R5  cluster_no_deficit (lib/cluster.sh) against provisioning snapshots. R2 is the
#          in-flight shape: 4 core members counted, 1 replacement in flight, deficit 0 — the
#          product's `effective` includes the in-flight replacement, so deficit alone passes
#          it. R1 is the positive control (a whole cluster must pass).
#   C1-C5  capture_node_logs' cloud branch (run-tests.sh): logs per VM with rc recorded, a
#          time window, and an explicit statement when nothing was captured.
#   F1-F2  cloud_partition_node (lib/cluster.sh): the partition firewall carries the cluster
#          under `aether-chaos-cluster` — NEVER `aether-cluster`, which the Hetzner provider reads
#          as a missed ingress firewall and so refuses every CTM replacement (#1500) — on create,
#          and is relabelled (legacy `aether-cluster` stripped) when an earlier run's is reused.
#   K1-K5  tools/cloud-reaper.sh against a stubbed Hetzner API: a leaked chaos firewall is
#          listed in dry-run and deleted with --destroy in every selector mode, never outside
#          its cluster, and a protected cluster's chaos firewall is kept.
#   P1-P5  CLOUD_RESOURCES_PROVISIONED (run-tests.sh): a cluster-B-only run sets it after B's
#          bootstrap succeeds — not when it fails, not on --skip-deploy — and --keep-on-failure
#          never reports "nothing to reap" over live VMs.
#
# No external test runner; invoke directly:
#   bash aether/tests/integration/test/test-restore-gate.sh
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# --- R: cluster_no_deficit -----------------------------------------------------------------
# Extract the function verbatim so the probe exercises the shipped text, then stub its two
# collaborators. The snapshot is built in the product's record-component order.
awk '/^cluster_no_deficit\(\) \{/,/^\}/' "${INTEG_DIR}/lib/cluster.sh" > "${WORK}/gate_fn.sh"
if [ ! -s "${WORK}/gate_fn.sh" ]; then
    fail "R0 cluster_no_deficit not found in lib/cluster.sh (extraction examined NOTHING)"
fi

snapshot() {  # counted effective deficit reachedFullMembership
    printf '{"leader":true,"configuredCoreCount":5,"countedCoreMembers":%s,"effective":%s,"deficit":%s,"armedForProvisioning":false,"reachedFullMembership":%s,"quorumSafe":true,"lastTrigger":"TICK","lastReason":"r","deficitAgeMs":0,"circuitBreaker":{"consecutiveFailures":0,"tripped":false,"nextAllowedMs":0},"lastProvisionFailure":null}' "$1" "$2" "$3" "$4"
}
gate() {  # prints PASS or fail for the snapshot in $1
    ( API_KEY=k NODE_COUNT=5
      _resolve_live_endpoint() { echo http://stub; }
      curl() { printf '%s' "$GATE_SNAP"; }
      source "${WORK}/gate_fn.sh"
      GATE_SNAP="$1"; if cluster_no_deficit; then echo PASS; else echo fail; fi )
}
expect_gate() {  # label expected snapshot
    local got; got=$(gate "$3")
    [ "$got" = "$2" ] && ok "$1 → $got" || fail "$1 → expected $2, got $got"
}
expect_gate "R1 whole cluster: 5 counted, deficit 0 (positive control)" PASS "$(snapshot 5 5 0 true)"
expect_gate "R2 4 counted + 1 in flight, deficit 0 (in-flight shape)"   fail "$(snapshot 4 5 0 true)"
expect_gate "R3 deficit 1"                                               fail "$(snapshot 4 4 1 true)"
expect_gate "R4 not leader (zeroed snapshot)"                            fail "$(snapshot 0 0 0 false)"
expect_gate "R5 deficit 10 (a leading 0 is not deficit 0)"               fail "$(snapshot 5 5 10 true)"

# --- C: capture_node_logs cloud branch ----------------------------------------------------
awk '/^capture_node_logs\(\) \{/,/^\}/' "${INTEG_DIR}/run-tests.sh" > "${WORK}/cap_fn.sh"
if ! grep -q '^        cloud)' "${WORK}/cap_fn.sh"; then
    fail "C0 capture_node_logs has no cloud branch (extraction examined NOTHING)"
fi
capture() {  # mode vms enum_rc -> runs a capture for cluster b into $WORK/<mode>
    local dir="${WORK}/$1"; mkdir -p "$dir"
    ( set -euo pipefail
      SCRIPT_DIR="$dir"; ENV_TYPE=cloud; CLUSTER_A_NAME=test-a; CLUSTER_B_NAME=test-b
      AETHER_SSH_KEY=/dev/null; SSH_OPTS=(-o ConnectTimeout=1); CAP_MODE="$1"
      log_info() { echo "INFO $*"; }; log_warn() { echo "WARN $*"; }
      _run_with_timeout() { shift; "$@"; }
      provisioning_snapshot() { echo '{"countedCoreMembers":4}'; }
      ssh() {
          case "$CAP_MODE" in
              denied) echo "Permission denied (publickey)." >&2; return 255 ;;
              *) echo "node-log ${*: -1}" ;;
          esac
      }
      source "${WORK}/cap_fn.sh"
      _cloud_running_vm_ips() { [ "$1" = test-b ] || return 1; [ "$CAP_ENUM_RC" -eq 0 ] || return "$CAP_ENUM_RC"; printf '%s' "$CAP_VMS"; }
      CAP_VMS="$2" CAP_ENUM_RC="$3" capture_node_logs "13-edge-cases" b 1700000000 ) > "${dir}/out.txt" 2>&1
    echo "${dir}/failure-logs/13-edge-cases"
}
d=$(capture two $'1.1.1.1\n2.2.2.2' 0)
if [ -s "$d/vm-1.1.1.1.log" ] && [ -s "$d/vm-2.2.2.2.log" ] && grep -q 'captured 2 of 2' "$d/capture-manifest.txt" \
    && grep -q 'rc=0' "$d/capture-manifest.txt"; then ok "C1 two VMs: a log each, manifest records rc and 'captured 2 of 2'"
else fail "C1 two VMs: $(cat "$d/capture-manifest.txt" 2>/dev/null | tr '\n' '|')"; fi
if grep -q -- '--since 1700000000' "$d/vm-1.1.1.1.log"; then ok "C2 capture window is the suite start (--since), not a fixed tail"
else fail "C2 no --since window in the remote command: $(cat "$d/vm-1.1.1.1.log")"; fi
d=$(capture none '' 0)
if grep -q 'NO VMs found' "$d/capture-manifest.txt" && ! ls "$d"/vm-*.log >/dev/null 2>&1; then ok "C3 zero VMs: manifest says nothing was captured"
else fail "C3 zero VMs: $(cat "$d/capture-manifest.txt" | tr '\n' '|')"; fi
d=$(capture denied '3.3.3.3' 0)
if grep -q 'captured 0 of 1' "$d/capture-manifest.txt" && grep -q 'NONE returned logs' "${d%/failure-logs/*}/out.txt"; then
    ok "C4 ssh refused: reads as 0 of 1 with a WARN, not as a capture"
else fail "C4 ssh refused: $(cat "$d/capture-manifest.txt" | tr '\n' '|')"; fi
d=$(capture unavail '' 1)
if grep -q 'enumeration UNAVAILABLE' "$d/capture-manifest.txt"; then ok "C5 failed enumeration is reported as UNAVAILABLE, not as no VMs"
else fail "C5 failed enumeration: $(cat "$d/capture-manifest.txt" | tr '\n' '|')"; fi

# --- F: partition firewall labels ---------------------------------------------------------
mkdir -p "${WORK}/bin"
cat > "${WORK}/bin/hcloud" <<'EOF'
#!/bin/bash
echo "hcloud $*" >> "$HC_LOG"
case "$1 $2" in
    "firewall create") [ "${HC_EXISTS:-0}" = 1 ] && { echo "name is already used (uniqueness_error)" >&2; exit 1; }; exit 0 ;;
    "firewall describe") echo 4242 ;;
esac
exit 0
EOF
chmod +x "${WORK}/bin/hcloud"
partition() {  # exists(0|1) -> prints the hcloud call log
    local node="restore-gate-test-$$"
    # common.sh requires TARGET_HOST; localhost is inert here (partition never ssh's).
    ( export PATH="${WORK}/bin:$PATH" HC_LOG="${WORK}/hc-$1.log" HC_EXISTS="$1" HOME="${WORK}" TARGET_HOST=localhost
      : > "$HC_LOG"
      source "${INTEG_DIR}/lib/common.sh" >/dev/null 2>&1 || echo "SOURCE-FAILED common.sh" >> "$HC_LOG"
      source "${INTEG_DIR}/lib/cluster.sh" >/dev/null 2>&1 || echo "SOURCE-FAILED cluster.sh" >> "$HC_LOG"
      cloud_server_id() { echo 777; }
      _cloud_transport_ports() { echo "8090 8190"; }
      export BOOTSTRAP_CLUSTER_NAME=test-b
      cloud_partition_node "$node" >/dev/null 2>&1 || true
      rm -f "/tmp/aether-partition-fw-${node}.id" )
    cat "${WORK}/hc-$1.log"
}
calls=$(partition 0)
if printf '%s' "$calls" | grep -q 'firewall create .*--label aether-chaos-cluster=test-b.*--label aether-role=partition' \
   && ! printf '%s' "$calls" | grep -q 'aether-cluster='; then
    ok "F1 new partition firewall is created with aether-chaos-cluster, and no aether-cluster label"
else fail "F1 create call: $(printf '%s' "$calls" | head -2 | tr '\n' '|')"; fi
calls=$(partition 1)
if printf '%s' "$calls" | grep -q 'firewall add-label --overwrite .* aether-chaos-cluster=test-b aether-role=partition' \
   && printf '%s' "$calls" | grep -q 'firewall remove-label .* aether-cluster$' \
   && ! printf '%s' "$calls" | grep -q 'aether-cluster='; then
    ok "F2 reused (pre-existing) firewall is relabelled, and a legacy aether-cluster label is stripped"
else fail "F2 exists path: $(printf '%s' "$calls" | tr '\n' '|')"; fi

# --- K: cloud-reaper.sh reaps chaos firewalls ---------------------------------------------
# A stub `curl` serves a tiny Hetzner account from a JSON file, applying label selectors the way
# the API does (`k=v` exact, bare `k` presence), and removes a resource on DELETE so the
# reaper's own post-destroy re-inventory sees the result. Firewalls:
#   11  aether-partition-n1   aether-chaos-cluster=test-b   (a leaked chaos firewall)
#   12  test-b-ingress        aether-cluster=test-b         (positive control: always reaped)
#   13  aether-partition-n9   aether-chaos-cluster=test-a   (another cluster's: never in scope)
#   14  aether-partition-pg   aether-chaos-cluster=test-pg  (protected cluster: never deleted)
REAPER="$(cd "${INTEG_DIR}/../../.." && pwd)/tools/cloud-reaper.sh"
mkdir -p "${WORK}/kbin"
cat > "${WORK}/kbin/curl" <<'STUB'
#!/bin/bash
out="" method=GET url=""
while [ $# -gt 0 ]; do
    case "$1" in
        -o) out="$2"; shift 2 ;;
        -X) method="$2"; shift 2 ;;
        -w|-H|-d) shift 2 ;;
        -*) shift ;;
        *) url="$1"; shift ;;
    esac
done
path="${url#*/v1/}"
echo "${method} ${path}" >> "$K_LOG"
res="${path%%\?*}"; res="${res%%/*}"
case "${method} ${path}" in
    "GET "*"label_selector="*)
        sel=$(printf '%s' "$path" | sed -E 's/.*label_selector=([^&]*).*/\1/; s/%3D/=/g')
        k="${sel%%=*}"; v=""; has=0
        [ "$k" != "$sel" ] && { v="${sel#*=}"; has=1; }
        jq --arg r "$res" --arg k "$k" --arg v "$v" --arg has "$has" \
           '{($r): [ (.[$r] // [])[] | select(if $has == "1" then .labels[$k] == $v else (.labels | has($k)) end) ]}' \
           "$K_WORLD" > "$out" ;;
    "GET firewalls/"*) printf '{"firewall":{"applied_to":[]}}' > "$out" ;;
    "DELETE "*)
        id="${path##*/}"
        jq --arg r "$res" --argjson id "$id" '.[$r] = [ (.[$r] // [])[] | select(.id != $id) ]' "$K_WORLD" > "$K_WORLD.n" \
            && mv "$K_WORLD.n" "$K_WORLD"
        : > "$out"; printf 204; exit 0 ;;
    *) printf '{}' > "$out" ;;
esac
printf 200
STUB
chmod +x "${WORK}/kbin/curl"
reap() {  # reaper args... -> "rc=<rc> deleted=<ids,>"; the reaper's full output lands in $WORK/k.out
    printf '%s' '{"firewalls":[
      {"id":11,"name":"aether-partition-n1","labels":{"aether-chaos-cluster":"test-b","aether-role":"partition"}},
      {"id":12,"name":"test-b-ingress","labels":{"aether-cluster":"test-b","aether-source":"hetzner-eu"}},
      {"id":13,"name":"aether-partition-n9","labels":{"aether-chaos-cluster":"test-a","aether-role":"partition"}},
      {"id":14,"name":"aether-partition-pg","labels":{"aether-chaos-cluster":"test-pg","aether-role":"partition"}}]}' \
      > "${WORK}/k-world.json"
    : > "${WORK}/k-calls"
    ( export PATH="${WORK}/kbin:$PATH" K_LOG="${WORK}/k-calls" K_WORLD="${WORK}/k-world.json" HCLOUD_TOKEN=stub-not-a-token
      /bin/bash "$REAPER" "$@" > "${WORK}/k.out" 2>&1; printf 'rc=%s' "$?" )
    printf ' deleted=%s' "$(sed -n 's/^DELETE firewalls\/\([0-9]*\)$/\1/p' "${WORK}/k-calls" | sort | tr '\n' ',')"
}
listed() { grep -qE "^[[:space:]]+$1[[:space:]]" "${WORK}/k.out"; }
if [ ! -x "$REAPER" ]; then
    fail "K0 tools/cloud-reaper.sh not found at ${REAPER} (examined NOTHING)"
fi
got=$(reap --cluster test-b)
if [ "$got" = "rc=0 deleted=" ] && listed 11 && listed 12 && ! listed 13 && ! listed 14; then
    ok "K1 dry-run --cluster test-b lists the leaked chaos firewall and the ingress control, deletes nothing"
else fail "K1 dry-run: ${got}; $(grep -E 'aether-partition|ingress' "${WORK}/k.out" | tr '\n' '|')"; fi
got=$(reap --cluster test-b --strict-cluster)
if [ "$got" = "rc=0 deleted=" ] && listed 11 && listed 12 && ! listed 13; then
    ok "K2 dry-run --strict-cluster also lists the chaos firewall (exact aether-chaos-cluster=test-b)"
else fail "K2 strict dry-run: ${got}"; fi
got=$(reap --cluster test-b --strict-cluster --destroy --force)
[ "$got" = "rc=0 deleted=11,12," ] \
    && ok "K3 --strict-cluster --destroy deletes test-b's chaos and ingress firewalls, nothing else" \
    || fail "K3 strict destroy: ${got}; $(tail -3 "${WORK}/k.out" | tr '\n' '|')"
got=$(reap --cluster test-b --destroy --force)
[ "$got" = "rc=0 deleted=11,12," ] \
    && ok "K4 non-strict --cluster --destroy deletes the chaos firewall, never another cluster's" \
    || fail "K4 non-strict destroy: ${got}"
got=$(reap --destroy --force)
[ "$got" = "rc=0 deleted=11,12,13," ] \
    && ok "K5 catch-all --destroy reaps every chaos firewall except the protected cluster's (14 kept)" \
    || fail "K5 catch-all destroy: ${got}; $(tail -3 "${WORK}/k.out" | tr '\n' '|')"

# --- P: provisioned flag and the preserve message -----------------------------------------
awk '/^cloud_bringup_cluster_b\(\) \{/,/^\}/' "${INTEG_DIR}/run-tests.sh" > "${WORK}/bringup_fn.sh"
awk '/^preserve_on_failure\(\) \{/,/^\}/' "${INTEG_DIR}/run-tests.sh" > "${WORK}/preserve_fn.sh"
if [ ! -s "${WORK}/bringup_fn.sh" ] || [ ! -s "${WORK}/preserve_fn.sh" ]; then
    fail "P0 cloud_bringup_cluster_b / preserve_on_failure not found (extraction examined NOTHING)"
fi
got=$( ( set -euo pipefail
         SKIP_DEPLOY=false; B_SUITES=(13); CLUSTER_B_MGMT=http://stub; LB_APP_ENDPOINT=a; LB_MGMT_ENDPOINT=m; CLOUD_RUNTIME=container
         log_step() { :; }; log_info() { :; }; log_warn() { :; }; log_error() { :; }
         bootstrap_cloud_cluster_b() { :; }; wait_for_node_count_on() { :; }; wait_for_leader_on() { :; }
         await_generation_quiesced() { :; }; discover_endpoints() { :; }; collect_blueprints() { :; }
         deploy_blueprints() { :; }; connectivity_preflight() { :; }
         source "${WORK}/bringup_fn.sh"
         cloud_bringup_cluster_b
         echo "flag=${CLOUD_RESOURCES_PROVISIONED:-unset}" ) 2>&1 )
[ "$got" = "flag=true" ] && ok "P1 cluster-B bring-up marks cloud resources provisioned" || fail "P1 after B bring-up: ${got}"
bringup() {  # skip_deploy bootstrap_rc -> "flag=<value>" printed by the EXIT of the subshell
    ( set -euo pipefail
      SKIP_DEPLOY="$1"; BOOT_RC="$2"; B_SUITES=(13); CLUSTER_B_MGMT=http://stub; LB_APP_ENDPOINT=a; LB_MGMT_ENDPOINT=m; CLOUD_RUNTIME=container
      trap 'echo "flag=${CLOUD_RESOURCES_PROVISIONED:-unset}"' EXIT
      log_step() { :; }; log_info() { :; }; log_warn() { :; }; log_error() { :; }
      bootstrap_cloud_cluster_b() { return "$BOOT_RC"; }; wait_for_node_count_on() { :; }; wait_for_leader_on() { :; }
      await_generation_quiesced() { :; }; discover_endpoints() { :; }; collect_blueprints() { :; }
      deploy_blueprints() { :; }; connectivity_preflight() { :; }
      source "${WORK}/bringup_fn.sh"
      cloud_bringup_cluster_b ) 2>/dev/null
}
# No `|| true` inside: errexit must stay live, or a failed bootstrap would fall through to the flag.
got=$(bringup false 1)
[ "$got" = "flag=unset" ] && ok "P4 failed B bootstrap (errexit) leaves the flag unset" || fail "P4 failed bootstrap: ${got}"
got=$(bringup true 0)
[ "$got" = "flag=unset" ] && ok "P5 --skip-deploy (reused cluster) does not mark resources provisioned" || fail "P5 skip-deploy: ${got}"
preserve() {  # flag -> preserve output
    ( ENV_TYPE=cloud; REPO_ROOT=/stub; CLUSTER_A_NAME=test-a; CLUSTER_B_NAME=test-b
      [ "$1" = set ] && CLOUD_RESOURCES_PROVISIONED=true
      log_step() { echo "STEP $*"; }; log_info() { echo "INFO $*"; }; log_warn() { echo "WARN $*"; }
      source "${WORK}/preserve_fn.sh"; preserve_on_failure 1 ) 2>&1
}
out=$(preserve unset)
if ! printf '%s' "$out" | grep -q 'nothing to reap' && printf '%s' "$out" | grep -q 'cloud-reaper.sh$'; then
    ok "P2 no completed bootstrap: points at a dry-run listing instead of claiming nothing exists"
else fail "P2 unset flag: $(printf '%s' "$out" | tr '\n' '|')"; fi
out=$(preserve set)
if printf '%s' "$out" | grep -q -- '--cluster test-b --destroy --force'; then ok "P3 provisioned: prints the per-cluster reap commands"
else fail "P3 set flag: $(printf '%s' "$out" | tr '\n' '|')"; fi

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
