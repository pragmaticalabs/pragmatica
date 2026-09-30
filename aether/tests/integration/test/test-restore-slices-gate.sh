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
#   G9  an UNDEPLOYED artifact (no SliceTarget: version "") with one lingering UNLOADING row -> passes;
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

# ---- fixtures are built from the REAL wire record, never from keys typed here --------------------------
# `ClusterSliceInfo(artifact, targetInstances, minInstances, version, instances)` is what /api/v1/slices serialises
# (aether/node/.../ManagementApiResponses.java:119-123; SliceRoutes.toClusterSliceInfo fills it, `version` falling
# back to "" when no SliceTarget backs the artifact). Its component names ARE the JSON keys. An earlier revision
# parsed `currentVersion` (the Java accessor on the SliceTarget value), its hand-written fixture used the same wrong
# key, and the gate passed vacuously on every real cluster: a fixture built from the premise it checks cannot
# falsify it.
REPO_ROOT="$(cd "${INTEG_DIR}/../../.." && pwd)"
RECORD_SRC="${RECORD_SRC_UNDER_TEST:-${REPO_ROOT}/aether/node/src/main/java/org/pragmatica/aether/api/ManagementApiResponses.java}"
SCALEWAIT_SRC="${REPO_ROOT}/aether/cli/src/test/java/org/pragmatica/aether/cli/ScaleWaitTest.java"
record_components() {  # prints "<type> <name>" per ClusterSliceInfo component, in order
    sed -n '/record ClusterSliceInfo(/,/) {}/p' "$RECORD_SRC" \
        | tr '\n' ' ' | sed -E 's/.*record ClusterSliceInfo\(//; s/\) \{\}.*//' | tr ',' '\n' | sed -E 's/^ +//; s/ +$//'
}
KEY_ARTIFACT=$(record_components | sed -n 1p | awk '{print $2}')
KEY_TARGET=$(record_components | sed -n 2p | awk '{print $2}')
KEY_MIN=$(record_components | sed -n 3p | awk '{print $2}')
KEY_VERSION=$(record_components | sed -n 4p | awk '{print $2}')
KEY_INSTANCES=$(record_components | sed -n 5p | awk '{print $2}')

inst() { printf '{"nodeId":"%s","state":"%s","failureReason":""}' "$1" "$2"; }
slice() {  # <artifact> <target> <inst json ...>   (SliceTarget-backed: version "1.0.0")
    local a="$1" t="$2"; shift 2
    local IFS=,
    printf '{"%s":"%s","%s":%s,"%s":1,"%s":"1.0.0","%s":[%s]}' "$KEY_ARTIFACT" "$a" "$KEY_TARGET" "$t" "$KEY_MIN" "$KEY_VERSION" "$KEY_INSTANCES" "$*"
}
undeployed() {  # <artifact> <inst json ...>: no SliceTarget, so target falls back to instances.size(), version ""
    local a="$1"; shift
    local IFS=,
    printf '{"%s":"%s","%s":%s,"%s":1,"%s":"","%s":[%s]}' "$KEY_ARTIFACT" "$a" "$KEY_TARGET" "$#" "$KEY_MIN" "$KEY_VERSION" "$KEY_INSTANCES" "$*"
}
ECHO=org.pragmatica.aether.test:test-echo-echo-slice:1.0.0
PERS=org.pragmatica.aether.test:test-persistence-persistence-slice:1.0.0
echo5() { for n in 1 2 3 4 5; do inst "n$n" "$1"; echo; done | paste -sd, -; }

{
cat <<'STUB'
log_info() { echo "INFO $*"; }; log_warn() { echo "WARN $*"; }; log_fail() { echo "FAIL $*"; }
api_get() {
    case "$1" in
        /api/v1/schema/status) if [ -f "${SCHEMA:-}" ]; then cat "$SCHEMA"; else echo '{"datasources":[]}'; fi; return 0 ;;
        /api/v1/slices) ;;
        *) return 0 ;;
    esac
    [ -f "$SLICES_LATER" ] && [ $((SECONDS - START)) -ge "${LATER_AFTER:-9999}" ] && cat "$SLICES_LATER" || cat "$SLICES"
}
STUB
for f in _slices_by_artifact _slices_missing_active _schema_failed_holds _restore_slices_gate; do extract "$LIB" "$f"; done
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
if grep -q 'SliceTarget-backed' "$WORK/g.g1"; then ok "G1b the failure message names the mechanism (SliceTarget-backed, version set)"; else fail "G1b message: $(head -c 200 "$WORK/g.g1")"; fi

# T1-T3: the tripwire. The key the gate parses must be a real component of the wire record.
GATE_KEY=$(extract "$LIB" _slices_by_artifact | grep -E 'ver=\$\(' | grep -oE '"[A-Za-z]+"\[\[:space:\]\]\*:' | head -1 | tr -d '"[]:*' | sed 's/space//')
if [ "$KEY_VERSION" = "version" ] && ! record_components | awk '{print $2}' | grep -qx currentVersion; then ok "T1 the record's 4th component is 'version' (no 'currentVersion' key exists): $(record_components | awk '{print $2}' | tr '\n' ' ')"
else fail "T1 components: $(record_components | tr '\n' '|')"; fi
if record_components | awk '{print $2}' | grep -qx "$GATE_KEY" && [ -n "$GATE_KEY" ]; then ok "T2 the key the gate parses ('${GATE_KEY}') is a component of ClusterSliceInfo"
else fail "T2 the gate parses '${GATE_KEY:-<none found>}', which is not a ClusterSliceInfo component ($(record_components | awk '{print $2}' | tr '\n' ' '))"; fi
# G10: the LITERAL wire body of ScaleWaitTest (cli), not one built here: two versions of one artifact, all ACTIVE.
LITERAL=$(sed -n '/TWO_VERSIONS = """/,/"""/p' "$SCALEWAIT_SRC" | sed '1d;$d' | tr -d '\n' | sed -E 's/"""[;]?$//')
run_gate g10 "$LITERAL"
if [ "$(cat "$WORK/grc.g10")" = "0" ] && grep -q 'version' <<<"$LITERAL" && grep -q '"version":"2.0.0"' <<<"$LITERAL"; then ok "G10 the literal body from ScaleWaitTest.java (TWO_VERSIONS) parses and passes: ${#LITERAL} bytes, both versions ACTIVE"
else fail "G10 rc=$(cat "$WORK/grc.g10") literal=${#LITERAL} bytes: $(head -c 200 "$WORK/g.g10")"; fi
# G11: the same literal with every instance flipped to UNLOADING must FAIL (the gate reads the real shape).
run_gate g11 "$(printf '%s' "$LITERAL" | sed 's/"state":"ACTIVE"/"state":"UNLOADING"/g')"
if [ "$(cat "$WORK/grc.g11")" = "1" ]; then ok "G11 the literal body with every instance UNLOADING fails the gate"
else fail "G11 rc=$(cat "$WORK/grc.g11") $(head -c 200 "$WORK/g.g11")"; fi

# ---- E3: a slice held by a FAILED schema migration fails the gate FAST, naming the datasource ---------------
# Fixture built from the real record, like the slices ones: SchemaRoutes.SchemaStatusResponse(datasource,
# currentVersion, lastMigration, status, owningBlueprint, heldSlices) (aether/node/.../SchemaRoutes.java:76-81).
SCHEMA_SRC="${REPO_ROOT}/aether/node/src/main/java/org/pragmatica/aether/api/routes/SchemaRoutes.java"
schema_components() {
    sed -n '/record SchemaStatusResponse(/,/) {/p' "$SCHEMA_SRC" | tr '\n' ' ' | sed -E 's/.*record SchemaStatusResponse\(//; s/\) \{.*//' | tr ',' '\n' | sed -E 's/^ +//; s/ +$//' | awk '{print $2}'
}
SK_DS=$(schema_components | sed -n 1p); SK_VER=$(schema_components | sed -n 2p); SK_LM=$(schema_components | sed -n 3p)
SK_ST=$(schema_components | sed -n 4p); SK_OB=$(schema_components | sed -n 5p); SK_HELD=$(schema_components | sed -n 6p)
schema_entry() {  # <datasource> <status> <held slice or ''>
    local held=""; [ -n "$3" ] && held="\"$3\""
    printf '{"%s":"%s","%s":900,"%s":"V900__create_kv.sql","%s":"%s","%s":"org.pragmatica.aether.test:test-persistence:1.0.0","%s":[%s]}' \
        "$SK_DS" "$1" "$SK_VER" "$SK_LM" "$SK_ST" "$2" "$SK_OB" "$SK_HELD" "$held"
}
schema_body() { printf '{"datasources":[%s]}' "$(local IFS=,; echo "$*")"; }
PERS_SLICE=org.pragmatica.aether.test:test-persistence-persistence-slice:1.0.0
LOADED3="$(body "$(slice $ECHO 3 "$(inst n1 ACTIVE)")" "$(slice $PERS_SLICE 3 "$(inst n1 LOADED)" "$(inst n2 LOADED)" "$(inst n3 LOADED)")")"
timed_gate() {  # <label> <slices> <schema body or ''>  -> elapsed seconds in $WORK/el.<label>
    printf '%s' "$2" > "$WORK/slices.$1"; rm -f "$WORK/later.$1" "$WORK/schema.$1"
    [ -n "$3" ] && printf '%s' "$3" > "$WORK/schema.$1"
    local t0=$SECONDS
    ( export SLICES="$WORK/slices.$1" SLICES_LATER="$WORK/later.$1" SCHEMA="$WORK/schema.$1" START=$SECONDS \
             AETHER_RESTORE_SLICES_TIMEOUT=6 AETHER_RESTORE_SLICES_POLL=1 TIMEOUT_SCALE=1
      source "$WORK/gate.sh"; _restore_slices_gate ) > "$WORK/g.$1" 2>&1
    echo $? > "$WORK/grc.$1"; echo $(( SECONDS - t0 )) > "$WORK/el.$1"
}
timed_gate s1 "$LOADED3" "$(schema_body "$(schema_entry database.testpersistence FAILED "$PERS_SLICE")")"
if [ "$(cat "$WORK/grc.s1")" = "1" ] && [ "$(cat "$WORK/el.s1")" -lt 5 ] && grep -q 'FAILED schema migration' "$WORK/g.s1" \
   && grep -q 'datasource=database.testpersistence' "$WORK/g.s1" && grep -q 'V900__create_kv.sql' "$WORK/g.s1" && grep -q 'test-persistence-persistence-slice' "$WORK/g.s1"; then
    ok "S1 LOADED slice held by a FAILED schema fails in $(cat "$WORK/el.s1")s (budget 6s), naming the datasource, migration and held slice"
else fail "S1 rc=$(cat "$WORK/grc.s1") elapsed=$(cat "$WORK/el.s1")s $(head -c 300 "$WORK/g.s1")"; fi
timed_gate s2 "$LOADED3" "$(schema_body "$(schema_entry database.testpersistence COMPLETED "")")"
if [ "$(cat "$WORK/grc.s2")" = "1" ] && [ "$(cat "$WORK/el.s2")" -ge 5 ] && grep -q 'NO ACTIVE instance' "$WORK/g.s2" && ! grep -q 'FAILED schema' "$WORK/g.s2"; then
    ok "S2 LOADED with a COMPLETED schema is NOT fast-failed: it waits the budget ($(cat "$WORK/el.s2")s) and reports NO ACTIVE"
else fail "S2 rc=$(cat "$WORK/grc.s2") elapsed=$(cat "$WORK/el.s2")s $(head -c 200 "$WORK/g.s2")"; fi
timed_gate s3 "$LOADED3" "$(schema_body "$(schema_entry database.other FAILED "")")"
if [ "$(cat "$WORK/el.s3")" -ge 5 ] && ! grep -q 'FAILED schema' "$WORK/g.s3"; then ok "S3 a FAILED schema that holds NOTHING (empty heldSlices) does not fast-fail the gate"
else fail "S3 elapsed=$(cat "$WORK/el.s3")s $(head -c 200 "$WORK/g.s3")"; fi
timed_gate s4 "$ALLACTIVE" "$(schema_body "$(schema_entry database.testpersistence FAILED "$PERS_SLICE")")"
if [ "$(cat "$WORK/grc.s4")" = "0" ]; then ok "S4 every artifact ACTIVE passes even with an unrelated FAILED schema record"
else fail "S4 rc=$(cat "$WORK/grc.s4") $(head -c 200 "$WORK/g.s4")"; fi
# tripwire: the keys the probe parses must be components of the real record
GATE_SCHEMA_KEYS=$(extract "$LIB" _schema_failed_holds | grep -oE '"(datasource|status|heldSlices|lastMigration|owningBlueprint)"' | tr -d '"' | sort -u | tr '\n' ' ')
miss=""; for k in $GATE_SCHEMA_KEYS; do schema_components | grep -qx "$k" || miss="$miss $k"; done
if [ -z "$miss" ] && [ -n "$GATE_SCHEMA_KEYS" ]; then ok "T3 every key the schema probe parses is a SchemaStatusResponse component ($GATE_SCHEMA_KEYS)"
else fail "T3 keys not in the record:${miss:- <none parsed>} (record: $(schema_components | tr '\n' ' '))"; fi

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
