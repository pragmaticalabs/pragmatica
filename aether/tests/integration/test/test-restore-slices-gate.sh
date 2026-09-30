#!/bin/bash
# test-restore-slices-gate.sh — stubs only. The REAL _slices_by_artifact / _slices_missing_active /
# _restore_slices_gate / restore_cluster_baseline (lib/cluster.sh) against a stubbed /api/v1/slices.
# 2026-09-30 S-prime: restore passed while test-echo was 5 x UNLOADING with no ACTIVE instance, and the next
# test failed 90s later with a misleading "no ACTIVE owner".
#   G1  echo 5 x UNLOADING (persistence ACTIVE)   -> the gate FAILS, printing each artifact's instance states
#   G2  every artifact has ACTIVE                 -> passes (positive control)
#   G3  rolling update: all LOADING, ACTIVE after 2s -> passes (the bounded wait covers it)
#   G4  old version UNLOADING, new version ACTIVE -> passes (per artifact, not per version)
#   G5  the same all-ACTIVE body on ONE line      -> passes (layout independent)
#   G6  an artifact with targetInstances 0        -> not required
#   G7  unreadable body                           -> fails (never a pass)
#   G9  an UNDEPLOYED artifact (no SliceTarget: currentVersion "") with one lingering UNLOADING row -> passes;
#       the same row on a DEPLOYED artifact fails (G1)
#   G8  echo LOADING forever                      -> fails after the budget (a stuck LOADING is not ACTIVE)
#   W1  restore_cluster_baseline itself fails on G1's body (the gate is wired in)
#   ARTIFACT_UNDER... LIB_UNDER_TEST selects an alternate lib copy (mutation probes).
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LIB="${LIB_UNDER_TEST:-${INTEG_DIR}/lib/cluster.sh}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap '[ -n "${KEEP:-}" ] && echo "WORK=$WORK" >&2 || rm -rf "$WORK"' EXIT
extract() { sed -n "/^$2() {/,/^}/p" "$1"; }

inst() { printf '{"nodeId":"%s","state":"%s","failureReason":""}' "$1" "$2"; }
slice() {  # <artifact> <target> <inst json ...>   (SliceTarget-backed: currentVersion "1.0.0")
    local a="$1" t="$2"; shift 2
    local IFS=,
    printf '{"artifact":"%s","targetInstances":%s,"minInstances":1,"currentVersion":"1.0.0","instances":[%s]}' "$a" "$t" "$*"
}
undeployed() {  # <artifact> <inst json ...>: no SliceTarget, so target falls back to instances.size(), currentVersion ""
    local a="$1"; shift
    local IFS=,
    printf '{"artifact":"%s","targetInstances":%s,"minInstances":1,"currentVersion":"","instances":[%s]}' "$a" "$#" "$*"
}
ECHO=org.pragmatica.aether.test:test-echo-echo-slice:1.0.0
PERS=org.pragmatica.aether.test:test-persistence-persistence-slice:1.0.0
echo5() { for n in 1 2 3 4 5; do inst "n$n" "$1"; echo; done | paste -sd, -; }

{
cat <<'STUB'
log_info() { echo "INFO $*"; }; log_warn() { echo "WARN $*"; }; log_fail() { echo "FAIL $*"; }
api_get() { [ "$1" = /api/v1/slices ] || return 0; [ -f "$SLICES_LATER" ] && [ $((SECONDS - START)) -ge "${LATER_AFTER:-9999}" ] && cat "$SLICES_LATER" || cat "$SLICES"; }
STUB
for f in _slices_by_artifact _slices_missing_active _restore_slices_gate; do extract "$LIB" "$f"; done
} > "$WORK/gate.sh"

run_gate() {  # <label> <slices body> [later body] [later-after]
    printf '%s' "$2" > "$WORK/slices.$1"; rm -f "$WORK/later.$1"
    [ -n "${3:-}" ] && printf '%s' "$3" > "$WORK/later.$1"
    ( export SLICES="$WORK/slices.$1" SLICES_LATER="$WORK/later.$1" LATER_AFTER="${4:-}" START=$SECONDS \
             AETHER_RESTORE_SLICES_TIMEOUT=4 AETHER_RESTORE_SLICES_POLL=1 TIMEOUT_SCALE=1
      source "$WORK/gate.sh"; _restore_slices_gate ) > "$WORK/g.$1" 2>&1
    echo $? > "$WORK/grc.$1"
}
body() { printf '{"slices":[%s]}' "$(local IFS=,; echo "$*")"; }

run_gate g1 "$(body "$(slice $ECHO 5 "$(echo5 UNLOADING)")" "$(slice $PERS 3 "$(inst n1 ACTIVE)" "$(inst n2 ACTIVE)")")"
if [ "$(cat "$WORK/grc.g1")" = "1" ] && grep -q 'test-echo-echo-slice' "$WORK/g.g1" && [ "$(grep -o 'UNLOADING' "$WORK/g.g1" | wc -l | tr -d ' ')" -ge 5 ] \
   && grep -q 'n3=UNLOADING' "$WORK/g.g1" && ! grep -q 'test-persistence-persistence-slice.*target=3' "$WORK/g.g1"; then
    ok "G1 echo 5 x UNLOADING fails the gate, printing every instance state (persistence, which is ACTIVE, is not listed)"
else fail "G1 rc=$(cat "$WORK/grc.g1") out=$(head -c 300 "$WORK/g.g1")"; fi

ALLACTIVE="$(body "$(slice $ECHO 3 "$(inst n1 ACTIVE)" "$(inst n2 ACTIVE)")" "$(slice $PERS 3 "$(inst n1 ACTIVE)")")"
run_gate g2 "$ALLACTIVE"
if [ "$(cat "$WORK/grc.g2")" = "0" ]; then ok "G2 every artifact has an ACTIVE instance passes"; else fail "G2 rc=$(cat "$WORK/grc.g2") $(head -c 200 "$WORK/g.g2")"; fi

run_gate g3 "$(body "$(slice $ECHO 3 "$(inst n1 LOADING)" "$(inst n2 LOADING)")")" "$ALLACTIVE" 2
if [ "$(cat "$WORK/grc.g3")" = "0" ]; then ok "G3 rolling update (all LOADING, ACTIVE after 2s) passes inside the bounded wait"; else fail "G3 rc=$(cat "$WORK/grc.g3") $(head -c 200 "$WORK/g.g3")"; fi

run_gate g4 "$(body "$(slice org.pragmatica.aether.test:test-echo-echo-slice:1.0.0 3 "$(inst n1 UNLOADING)")" "$(slice org.pragmatica.aether.test:test-echo-echo-slice:1.0.1 3 "$(inst n2 ACTIVE)")")"
if [ "$(cat "$WORK/grc.g4")" = "0" ]; then ok "G4 old version draining while the new one is ACTIVE passes (keyed per artifact)"; else fail "G4 rc=$(cat "$WORK/grc.g4") $(head -c 200 "$WORK/g.g4")"; fi

run_gate g5 "$(printf '%s' "$ALLACTIVE" | tr -d '\n')"
pretty=$(printf '%s' "$ALLACTIVE" | sed 's/,/,\n  /g')
run_gate g5b "$pretty"
if [ "$(cat "$WORK/grc.g5")" = "0" ] && [ "$(cat "$WORK/grc.g5b")" = "0" ]; then ok "G5 one-line and pretty-printed bodies both parse"; else fail "G5 rc=$(cat "$WORK/grc.g5")/$(cat "$WORK/grc.g5b")"; fi

run_gate g6 "$(body "$(slice $ECHO 0)" "$(slice $PERS 3 "$(inst n1 ACTIVE)")")"
if [ "$(cat "$WORK/grc.g6")" = "0" ]; then ok "G6 an artifact with targetInstances 0 is not required to run"; else fail "G6 rc=$(cat "$WORK/grc.g6") $(head -c 200 "$WORK/g.g6")"; fi

run_gate g7 ""
if [ "$(cat "$WORK/grc.g7")" = "1" ] && grep -q 'unreadable' "$WORK/g.g7"; then ok "G7 an unreadable /api/v1/slices fails (never a pass)"; else fail "G7 rc=$(cat "$WORK/grc.g7") $(head -c 200 "$WORK/g.g7")"; fi

run_gate g8 "$(body "$(slice $ECHO 3 "$(inst n1 LOADING)" "$(inst n2 LOADING)")")"
if [ "$(cat "$WORK/grc.g8")" = "1" ] && grep -q 'n1=LOADING' "$WORK/g.g8"; then ok "G8 LOADING forever fails after the budget, naming the states"; else fail "G8 rc=$(cat "$WORK/grc.g8") $(head -c 200 "$WORK/g.g8")"; fi

run_gate g9 "$(body "$(slice $PERS 3 "$(inst n1 ACTIVE)")" "$(undeployed org.pragmatica.aether.test:removed-slice:1.0.0 "$(inst n2 UNLOADING)")")"
if [ "$(cat "$WORK/grc.g9")" = "0" ]; then ok "G9 an undeployed artifact with a lingering UNLOADING row does not fail the gate (only SliceTarget-backed artifacts count)"
else fail "G9 rc=$(cat "$WORK/grc.g9") $(head -c 240 "$WORK/g.g9")"; fi
if grep -q 'SliceTarget-backed' "$WORK/g.g1"; then ok "G1b the failure message names the mechanism (SliceTarget-backed, currentVersion set)"; else fail "G1b message: $(head -c 200 "$WORK/g.g1")"; fi

# W1: restore_cluster_baseline with every earlier step stubbed green and the slices from G1
{
cat <<'STUB'
log_info() { echo "INFO $*"; }; log_warn() { echo "WARN $*"; }; log_fail() { echo "FAIL $*"; }
cluster_leader_http() { echo node-1; }; restart_all_nodes() { return 0; }; _refresh_mgmt_entry_point() { return 0; }
enable_auto_heal() { return 0; }; reset_provisioning_circuit() { return 0; }; activate_node() { return 0; }
scale_cluster() { return 0; }; wait_for() { eval "$2"; }; ready_core_count() { echo 5; }
await_generation_quiesced() { return 0; }; wait_for_phase() { return 0; }; cluster_no_deficit() { return 0; }
cluster_active_core_count() { echo 5; }; slices_active_instances() { echo 0; }; slices_target_total() { echo 5; }
provisioning_snapshot() { echo '{"countedCoreMembers":5,"effective":5,"deficit":0}'; }
_restore_active_counted_gate() { return 0; }
api_get() { case "$1" in /api/v1/nodes/lifecycle) echo '[]' ;; /api/v1/slices) cat "$SLICES" ;; esac; }
STUB
for f in _slices_by_artifact _slices_missing_active _restore_slices_gate restore_cluster_baseline; do extract "$LIB" "$f"; done
} > "$WORK/restore.sh"
( export CLUSTER_ENDPOINT=http://stub NODE_COUNT=5 TIMEOUT_SCALE=1 CLOUD_MODE=false SLICES="$WORK/slices.g1" START=$SECONDS \
         AETHER_RESTORE_SLICES_TIMEOUT=2 AETHER_RESTORE_SLICES_POLL=1
  source "$WORK/restore.sh"; restore_cluster_baseline ) > "$WORK/w1.out" 2>&1
w1rc=$?
( export CLUSTER_ENDPOINT=http://stub NODE_COUNT=5 TIMEOUT_SCALE=1 CLOUD_MODE=false SLICES="$WORK/slices.g2" START=$SECONDS \
         AETHER_RESTORE_SLICES_TIMEOUT=2 AETHER_RESTORE_SLICES_POLL=1
  source "$WORK/restore.sh"; restore_cluster_baseline ) > "$WORK/w1b.out" 2>&1
w1brc=$?
if [ "$w1rc" = "1" ] && grep -q 'NO ACTIVE instance' "$WORK/w1.out" && [ "$w1brc" = "0" ]; then ok "W1 restore_cluster_baseline fails on echo 5 x UNLOADING and passes on the all-ACTIVE body (control)"
else fail "W1 rc=${w1rc}/${w1brc} $(grep FAIL "$WORK/w1.out" | head -1 | cut -c1-120)"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
