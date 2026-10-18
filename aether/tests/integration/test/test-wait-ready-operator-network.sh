#!/bin/bash
# test-wait-ready-operator-network.sh — stubs only (no cluster, no cloud). The REAL wait_for_cluster_ready and
# _operator_network_down (lib/cluster.sh) run against stubbed readiness, entry-point refresh, wait_for and curl. Refs #2192.
#   T1  no core answers, reference endpoint unreachable  -> NOT fast-failed: warns "operator network unavailable", falls to wait_for
#   T2  no core answers, reference endpoint answers      -> still fails fast with "cluster appears down", wait_for never runs
#   T3  no core answers, reference answers with an HTTP error status -> still fails fast (any HTTP answer proves the link)
#   T4  non-cloud environment, no reference configured   -> fails fast exactly as before, curl never called
#   T5  a core answers (refresh ok)                      -> goes to wait_for, reference never probed
#   T6  OPERATOR_REFERENCE_URL overrides the default and is the URL probed
#   T7  the link never returns -> the wait_for timeout verdict is returned (rc 1), never a success
#   T8  the probe is bounded: curl gets -m 3 by default and -m <OPERATOR_REFERENCE_TIMEOUT_S> when set
#   T9  wait_for gets the readiness predicate for the EXPECTED count and the caller's timeout (all three arms)
#   T10 the sweep fails but the reference answers and a SECOND sweep finds a core (link back mid-check) -> wait_for, not fast-fail
#   T11 the default cloud reference is ${HCLOUD_API}, so overriding that knob moves the probe
# Mutations (see the PR): drop the guard reddens T1; invert the probe result reddens T2/T3; probe in the happy path reddens T5; curl -f (HTTP error == down) reddens T3; dropping the wait_for rc reddens T7;
# dropping curl -m reddens T8; a wrong wait_for timeout or predicate reddens T9; dropping the second sweep reddens T10; hard-coding the Hetzner host reddens T11.
#   LIB_UNDER_TEST selects an alternate copy.
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN HCLOUD_API OPERATOR_REFERENCE_URL OPERATOR_REFERENCE_TIMEOUT_S

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LIB="${LIB_UNDER_TEST:-${INTEG_DIR}/lib/cluster.sh}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap '[ -n "${KEEP:-}" ] && echo "WORK=$WORK" >&2 || rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"
extract() { sed -n "/^$2() {/,/^}/p" "$1"; }

{
cat <<'STUB'
log_warn() { echo "WARN $*"; }; log_fail() { echo "FAIL $*"; }; log_pass() { echo "PASS $*"; }
NODE_COUNT=3; MGMT_PORT=5151; TARGET_HOST=stub; CLOUD_MGMT_PORT=8080
_cluster_is_ready() { return 1; }
# CORE_ANSWERS: yes | no | second (the first sweep fails, the second finds a core)
_refresh_mgmt_entry_point() {
    echo "SWEEP" >> "$CALLS"
    case "$CORE_ANSWERS" in
        yes) return 0 ;;
        second) [ "$(grep -c '^SWEEP' "$CALLS")" -ge 2 ] ;;
        *) return 1 ;;
    esac
}
wait_for() { echo "WAIT_FOR $1|$2|$3" >> "$CALLS"; return "${WAIT_RC:-0}"; }
STUB
extract "$LIB" _operator_reference_url
extract "$LIB" _operator_network_down
extract "$LIB" wait_for_cluster_ready
} > "$WORK/fns.sh"

# Stub curl. REF=<mode>: down (transport failure), up (200), http500 (an HTTP error answer, curl still exits 0)
cat > "$WORK/bin/curl" <<'STUB'
#!/bin/bash
echo "CURL ${*: -1}" >> "$CALLS"; echo "ARGV $*" >> "$CALLS"; set -- " $* "
# --fail (-f) makes a real curl exit 22 on an HTTP error status; the stub honours it for the http500 mode
case "$REF" in
  down) exit 6 ;;
  http500) case "$*" in *" -sf "*|*" -fs "*|*--fail*) exit 22 ;; esac; exit 0 ;;
  *) exit 0 ;;
esac
STUB
chmod +x "$WORK/bin/curl"

run_fn() {  # <label> <ENV_TYPE> <CORE_ANSWERS> <REF> [OPERATOR_REFERENCE_URL]; env: EXTRA_ENV="NAME=value ..." exported in the run
    ( export PATH="$WORK/bin:$PATH" CALLS="$WORK/calls.$1" ENV_TYPE="$2" CORE_ANSWERS="$3" REF="$4"
      [ -n "${5:-}" ] && export OPERATOR_REFERENCE_URL="$5"
      for kv in ${EXTRA_ENV:-}; do export "$kv"; done
      : > "$CALLS"; source "$WORK/fns.sh"; wait_for_cluster_ready 17 4 ) > "$WORK/out.$1" 2> "$WORK/err.$1"
    echo $? > "$WORK/rc.$1"
}
waits() { grep -c '^WAIT_FOR' "$WORK/calls.$1"; }
sweeps() { grep -c '^SWEEP' "$WORK/calls.$1"; }
curls() { grep -c '^CURL' "$WORK/calls.$1"; }

run_fn t1 cloud no down
if [ "$(cat "$WORK/rc.t1")" = "0" ] && [ "$(waits t1)" = "1" ] && grep -q 'operator network unavailable' "$WORK/out.t1" && ! grep -q 'fast-failing$' "$WORK/out.t1"; then ok "T1 an unreachable reference means operator network unavailable: no fast-fail, falls through to wait_for"
else fail "T1 rc=$(cat "$WORK/rc.t1") waits=$(waits t1) out=$(head -c 200 "$WORK/out.t1")"; fi

run_fn t2 cloud no up
if [ "$(cat "$WORK/rc.t2")" = "1" ] && [ "$(waits t2)" = "0" ] && grep -q 'cluster appears down, fast-failing' "$WORK/out.t2"; then ok "T2 a reachable reference still fails fast with 'cluster appears down'"
else fail "T2 rc=$(cat "$WORK/rc.t2") waits=$(waits t2) out=$(head -c 200 "$WORK/out.t2")"; fi

run_fn t3 cloud no http500
if [ "$(cat "$WORK/rc.t3")" = "1" ] && [ "$(waits t3)" = "0" ] && grep -q 'cluster appears down, fast-failing' "$WORK/out.t3"; then ok "T3 an HTTP error answer from the reference still proves the link: fails fast"
else fail "T3 rc=$(cat "$WORK/rc.t3") waits=$(waits t3) out=$(head -c 200 "$WORK/out.t3")"; fi

run_fn t4 docker no down
if [ "$(cat "$WORK/rc.t4")" = "1" ] && [ "$(curls t4)" = "0" ] && grep -q 'cluster appears down, fast-failing' "$WORK/out.t4"; then ok "T4 a non-cloud run with no reference fails fast as before and never calls curl"
else fail "T4 rc=$(cat "$WORK/rc.t4") curls=$(curls t4) out=$(head -c 200 "$WORK/out.t4")"; fi

run_fn t5 cloud yes down
if [ "$(cat "$WORK/rc.t5")" = "0" ] && [ "$(waits t5)" = "1" ] && [ "$(curls t5)" = "0" ]; then ok "T5 a core that answers goes to wait_for and the reference is never probed"
else fail "T5 rc=$(cat "$WORK/rc.t5") waits=$(waits t5) curls=$(curls t5)"; fi

run_fn t6 docker no down https://ref.example/
if [ "$(cat "$WORK/rc.t6")" = "0" ] && grep -q '^CURL https://ref.example/$' "$WORK/calls.t6" && grep -q 'operator network unavailable' "$WORK/out.t6"; then ok "T6 OPERATOR_REFERENCE_URL is the URL probed, in any environment"
else fail "T6 rc=$(cat "$WORK/rc.t6") calls=$(cat "$WORK/calls.t6")"; fi

WAIT_RC=1 run_fn t7 cloud no down
if [ "$(cat "$WORK/rc.t7")" = "1" ] && [ "$(waits t7)" = "1" ]; then ok "T7 a link that never returns ends as the wait_for timeout verdict (rc 1), not success"
else fail "T7 rc=$(cat "$WORK/rc.t7") waits=$(waits t7)"; fi

run_fn t8 cloud no down
if grep -q '^ARGV .*-m 3 ' "$WORK/calls.t8"; then ok "T8a the reference probe is bounded by default: curl gets -m 3"
else fail "T8a no -m 3 in curl argv: $(grep '^ARGV' "$WORK/calls.t8")"; fi
EXTRA_ENV="OPERATOR_REFERENCE_TIMEOUT_S=7" run_fn t8b cloud no down
if grep -q '^ARGV .*-m 7 ' "$WORK/calls.t8b"; then ok "T8b OPERATOR_REFERENCE_TIMEOUT_S is honoured: curl gets -m 7"
else fail "T8b no -m 7 in curl argv: $(grep '^ARGV' "$WORK/calls.t8b")"; fi

# T9: every arm that reaches wait_for passes the predicate for the expected count (4, distinct from the stub NODE_COUNT=3) and the caller's timeout (17)
WANT='cluster ready (4+ members, canonical §1.1)|_cluster_is_ready 4|17'
run_fn t9a cloud yes down; run_fn t9b cloud no down; run_fn t9c cloud second up
t9_bad=""
for l in t9a t9b t9c; do grep -qxF "WAIT_FOR ${WANT}" "$WORK/calls.$l" || t9_bad="${t9_bad} ${l}:$(grep '^WAIT_FOR' "$WORK/calls.$l")"; done
if [ -z "$t9_bad" ]; then ok "T9 wait_for gets '_cluster_is_ready 4' (not NODE_COUNT) and timeout 17 on the slow path, the link-down arm and the recovered-mid-check arm"
else fail "T9 wrong wait_for args:${t9_bad}"; fi

run_fn t10 cloud second up
if [ "$(cat "$WORK/rc.t10")" = "0" ] && [ "$(waits t10)" = "1" ] && [ "$(sweeps t10)" = "2" ] && grep -q 'recovered mid-check' "$WORK/out.t10" && ! grep -q 'fast-failing$' "$WORK/out.t10"; then ok "T10 reference up + second sweep finds a core: waits instead of fast-failing a healthy cluster"
else fail "T10 rc=$(cat "$WORK/rc.t10") waits=$(waits t10) sweeps=$(sweeps t10) out=$(head -c 200 "$WORK/out.t10")"; fi
run_fn t10b docker second down
if [ "$(cat "$WORK/rc.t10b")" = "1" ] && [ "$(sweeps t10b)" = "1" ]; then ok "T10b with no reference there is no second sweep: a dead cluster still fast-fails after one"
else fail "T10b rc=$(cat "$WORK/rc.t10b") sweeps=$(sweeps t10b)"; fi

EXTRA_ENV="HCLOUD_API=https://hcloud.example/v1" run_fn t11 cloud no up
if grep -q '^CURL https://hcloud.example/v1$' "$WORK/calls.t11"; then ok "T11 the cloud default reference follows HCLOUD_API"
else fail "T11 probed: $(grep '^CURL' "$WORK/calls.t11")"; fi
run_fn t11b cloud no up
if grep -q '^CURL https://api.hetzner.cloud/v1$' "$WORK/calls.t11b"; then ok "T11b with HCLOUD_API unset the default is https://api.hetzner.cloud/v1"
else fail "T11b probed: $(grep '^CURL' "$WORK/calls.t11b")"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
