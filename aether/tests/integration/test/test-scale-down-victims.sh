#!/bin/bash
# test-scale-down-victims.sh — stubs only. 03-scaling bookkeeping and the opt-in reproduction aid (S-triple-prime: the
# load target, core-4, was a scale-down victim and 12 of 482 requests answered 503; nothing recorded it).
#   V1  one INFO line per scale-down step: who left, which node the load targeted, and whether it was a victim
#       (yes / no / unknown). Recording only.
#   V2  flag OFF (the default): scale_load_retarget_to_victim does NOTHING (no status read, no override file)
#   V3  SCALE_LOAD_TARGET_VICTIM=1: the load is re-aimed at a node removed from the electorate: target ∈ victims
#   V4  flag ON but nobody leaves within the budget: no override, a WARN, the load keeps its target
#   V5  start_load's loop honours the override file mid-run; without it every request hits the original endpoint
#   V6  the override is applied once per value, so the load's re-resolve can leave a victim that halted
#   V7-V10  the retarget prefers a removed voter that HOSTS the load's slice (current owner first); falls back honestly
#   V11 the step line records whether the target hosts the slice and whether a slice host was a victim
#   P1-P3  put_artifact_retry_503 (the Seed marker PUT): body recorded, ONE retry on 503 only, 500 never retried
#   M1-M7, T2  _seed_membership_settled (the Seed gate) against the REAL node body: STABLE + members==voters settles; tripwire rc 2 (loud) when the parser is blind
#   T1  tripwire: the voter keys the helper parses are components of the real VoterReconfigurationStatus record
#   Mutations: always "no" victim reddens V1; the flag ignored reddens V2; the retarget removed reddens V3; the loop
#   not reading the override reddens V5.   INTEG_DIR_UNDER_TEST selects another copy of aether/tests/integration.
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="${INTEG_DIR_UNDER_TEST:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap '[ -n "${KEEP:-}" ] && echo "WORK=$WORK" >&2 || rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"

# Fixture keys come from the real record, not typed here (a fixture built from the premise it checks cannot falsify it).
REC="${REPO_ROOT}/integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/VoterReconfigurationStatus.java"
comps=$(sed -n '/record VoterReconfigurationStatus(/,/) {/p' "$REC" | tr '\n' ' ' | sed -E 's/.*record VoterReconfigurationStatus\(//; s/\) \{.*//' | tr ',' '\n' | sed -E 's/^ +//; s/ +$//' | awk '{print $2}')
K_INST=$(printf '%s\n' "$comps" | grep -x installedVoters); K_TGT=$(printf '%s\n' "$comps" | grep -x targetVoters)
if [ "$K_INST" = "installedVoters" ] && [ "$K_TGT" = "targetVoters" ] && grep -q '_cluster_voters installedVoters\|_cluster_voters targetVoters' "${INTEG_DIR}/lib/cluster.sh"; then
    ok "T1 the helper reads installedVoters/targetVoters, both components of VoterReconfigurationStatus ($(echo $comps))"
else fail "T1 components: $(echo $comps)"; fi

status_json() {  # <installed ids...> ; targetVoters taken from $TARGET (space separated)
    local inst="" tgt="" x
    for x in "$@"; do inst="${inst}${inst:+,}\"$x\""; done
    for x in $TARGET; do tgt="${tgt}${tgt:+,}\"$x\""; done
    printf '{"nodeId":"node-1","voterReconfiguration":{"stage":"STABLE","%s":[%s],"%s":[%s]}}' "$K_INST" "$inst" "$K_TGT" "$tgt"
}
extract() { sed -n "/^$1() {/,/^}/p" "$INTEG_DIR/lib/cluster.sh"; }
{
cat <<'STUB'
log_info() { echo "INFO $*"; }; log_warn() { echo "WARN $*"; }
CLOUD_MODE=false; TARGET_HOST=h; APP_PORT=8070
api_get() { echo x >> "$CALLS"; cat "$STATUS_BODY"; }
STUB
for f in _voters_in _cluster_voters _id_set_difference _app_endpoint_for_node log_scale_down_step scale_load_retarget_to_victim slice_hosts_for slice_owner_for; do extract "$f"; done
} > "$WORK/fns.sh"
BEFORE=$'node-1\nnode-2\nnode-3\nnode-4\nnode-5\nnode-6\nnode-7'; AFTER=$'node-1\nnode-2\nnode-3\nnode-4\nnode-5'

run() { # <label> <snippet> [VAR=val ...]
    local label="$1" snip="$2"; shift 2
    : > "$WORK/calls.$label"; rm -f /tmp/load_endpoint_override_$$
    ( export CALLS="$WORK/calls.$label" STATUS_BODY="$WORK/status.$label" "$@"; source "$WORK/fns.sh"; eval "$snip" ) > "$WORK/out.$label" 2>&1
    echo $? > "$WORK/rc.$label"
}

# V1: logging
run v1a 'log_scale_down_step "$B" "$A" "http://h:8075"' B="$BEFORE" A="$AFTER"
run v1b 'log_scale_down_step "$B" "$A" "http://h:8070"' B="$BEFORE" A="$AFTER"
run v1c 'log_scale_down_step "$B" "$A" "http://elsewhere:1"' B="$BEFORE" A="$AFTER"
if grep -q 'removed=\[node-6 node-7\]' "$WORK/out.v1a" && grep -q '(node node-6)' "$WORK/out.v1a" && grep -q 'was a victim: yes' "$WORK/out.v1a" \
   && grep -q '(node node-1)' "$WORK/out.v1b" && grep -q 'was a victim: no' "$WORK/out.v1b" && grep -q 'was a victim: unknown' "$WORK/out.v1c" \
   && [ "$(grep -c '^INFO scale-down step' "$WORK/out.v1a")" = "1" ]; then
    ok "V1 one INFO line per step names the removed nodes and the load target: victim yes (node-6) / no (node-1) / unknown"
else fail "V1 $(cat "$WORK/out.v1a" "$WORK/out.v1b" "$WORK/out.v1c" | cut -c1-200 | tr '\n' '|')"; fi

# V2: flag off -> nothing at all
TARGET="node-1 node-2 node-3 node-4 node-5" status_json $BEFORE > "$WORK/status.v2"
run v2 'scale_load_retarget_to_victim "$B" 2' B="$BEFORE"
if [ "$(cat "$WORK/rc.v2")" = "0" ] && [ ! -e /tmp/load_endpoint_override_$$ ] && [ ! -s "$WORK/calls.v2" ] && ! grep -q . "$WORK/out.v2"; then
    ok "V2 flag OFF: no status read, no override file, no output (selection and target unchanged)"
else fail "V2 rc=$(cat "$WORK/rc.v2") calls=$(grep -c . "$WORK/calls.v2") override=$([ -e /tmp/load_endpoint_override_$$ ] && echo present) out=$(cat "$WORK/out.v2")"; fi

# V3: flag on -> target is a removed node
TARGET="node-1 node-2 node-3 node-4 node-5" status_json $BEFORE > "$WORK/status.v3"
run v3 'v=$(scale_load_retarget_to_victim "$B" 3 2>/dev/null); echo "VICTIM=$v"' B="$BEFORE" SCALE_LOAD_TARGET_VICTIM=1
victim=$(sed -n 's/^VICTIM=//p' "$WORK/out.v3"); ovr=$(cat /tmp/load_endpoint_override_$$ 2>/dev/null)
case "$victim" in node-6|node-7) in_victims=1 ;; *) in_victims=0 ;; esac
if [ "$in_victims" = "1" ] && [ "$ovr" = "http://h:$((8070 + ${victim#node-} - 1))" ]; then ok "V3 flag ON: the load is re-aimed at victim ${victim} (${ovr}); target ∈ {node-6, node-7}"
else fail "V3 victim=[$victim] override=[$ovr] out=$(head -c 200 "$WORK/out.v3")"; fi
rm -f /tmp/load_endpoint_override_$$

# V4: flag on, nobody leaves
TARGET="$BEFORE" status_json $BEFORE > "$WORK/status.v4"
run v4 'v=$(scale_load_retarget_to_victim "$B" 1 2>"$WORK/err.v4"); echo "VICTIM=[$v]"; cat "$WORK/err.v4"' B="$BEFORE" SCALE_LOAD_TARGET_VICTIM=1 SCALE_VICTIM_POLL_INTERVAL_S=0.2
if grep -q 'VICTIM=\[\]' "$WORK/out.v4" && grep -q 'WARN SCALE_LOAD_TARGET_VICTIM=1: no removed node' "$WORK/out.v4" && [ ! -e /tmp/load_endpoint_override_$$ ]; then
    ok "V4 flag ON but nobody leaves: no override, a WARN, the load keeps its target"
else fail "V4 out=$(head -c 240 "$WORK/out.v4")"; fi

# V5: the load loop honours the override
cat > "$WORK/bin/curl" <<'STUB'
#!/bin/bash
echo "${*: -1}" >> "$HITS"; printf '200'
STUB
chmod +x "$WORK/bin/curl"
loop_run() {  # <label> <write override? 0|1>
    : > "$WORK/hits.$1"; rm -f /tmp/load_endpoint_override_$$
    ( export PATH="$WORK/bin:$PATH" HITS="$WORK/hits.$1" TARGET_HOST=localhost API_KEY=k APP_ENDPOINT=http://orig:8070
      source "${INTEG_DIR}/lib/load.sh" > /dev/null 2>&1
      start_load 10 3 GET /api/echo/health > /dev/null 2>&1
      sleep 1
      [ "$2" = "1" ] && printf 'http://victim:8075' > "/tmp/load_endpoint_override_$$"
      sleep 3
      stop_load > /dev/null 2>&1 )
}
loop_run vl1 1; loop_run vl0 0
if grep -q 'http://orig:8070' "$WORK/hits.vl1" && grep -q 'http://victim:8075' "$WORK/hits.vl1" && ! grep -q 'victim' "$WORK/hits.vl0" && grep -q 'orig' "$WORK/hits.vl0"; then
    ok "V5 the load loop switches to the override mid-run; without the file every request hits the original endpoint"
else fail "V5 with=$(sort "$WORK/hits.vl1" | uniq -c | tr '\n' ' ') without=$(sort "$WORK/hits.vl0" | uniq -c | tr '\n' ' ')"; fi
rm -f /tmp/load_endpoint_override_$$

# ---- #1790 harness round -----------------------------------------------------------------------------------------------
# V6: the override is applied ONCE per value. The load is aimed at a victim; the victim halts (503/000); start_load's
# re-resolve finds the new owner. Re-applying the override every tick sent it straight back to the dead victim.
cat > "$WORK/bin/curl" <<'STUB'
#!/bin/bash
url="${*: -1}"; echo "$url" >> "$HITS"
case "$url" in http://victim:8075*) printf '503' ;; *) printf '200' ;; esac
STUB
chmod +x "$WORK/bin/curl"
reresolve_run() {  # <label>
    : > "$WORK/hits.$1"; rm -f /tmp/load_endpoint_override_$$
    ( export PATH="$WORK/bin:$PATH" HITS="$WORK/hits.$1" TARGET_HOST=localhost API_KEY=k APP_ENDPOINT=http://orig:8070 ENV_TYPE=cloud
      source "${INTEG_DIR}/lib/load.sh" > /dev/null 2>&1
      slice_owner_for() { echo node-new; }; cloud_public_ip() { echo newhost; }
      start_load 10 4 GET /api/echo/health "" "g:a:v" > /dev/null 2>&1
      sleep 1
      printf 'http://victim:8075' > "/tmp/load_endpoint_override_$$"
      sleep 4
      stop_load > /dev/null 2>&1 )
}
reresolve_run rr
last=$(tail -1 "$WORK/hits.rr")
if grep -q 'http://victim:8075' "$WORK/hits.rr" && grep -q 'http://newhost:8070' "$WORK/hits.rr" && [ "${last%%/api*}" = "http://newhost:8070" ]; then
    ok "V6 aimed at a victim that fails, the load RE-RESOLVES to the new owner and STAYS there (the override is applied once, not every tick)"
else fail "V6 hits=$(sort "$WORK/hits.rr" | uniq -c | tr '\n' ' ') last=${last}"; fi
rm -f /tmp/load_endpoint_override_$$

# V7-V10: the retarget prefers a removed voter that HOSTS the load's slice. Slices fixture is line-oriented JSON like
# `aether slices --format json` (the shape slice_hosts_for's awk already reads).
slices_json() {  # <host nodes...> -> one echo artifact with those ACTIVE instances (in order), plus an unrelated artifact
    local h first=1
    printf '{\n"slices": [\n{\n"artifact": "org.other:thing:1.0.0",\n"instances": [\n{\n"nodeId": "node-3",\n"state": "ACTIVE"\n}\n]\n},\n{\n"artifact": "org.pragmatica.aether.test:test-echo:1.0.0",\n"instances": [\n'
    for h in "$@"; do
        [ "$first" = 1 ] || printf ',\n'; first=0
        printf '{\n"nodeId": "%s",\n"state": "ACTIVE"\n}' "$h"
    done
    printf '\n]\n}\n]\n}\n'
}
cat >> "$WORK/fns.sh" <<'STUB'
cluster_slices() { cat "$SLICES_BODY"; }
STUB
TARGET="node-1 node-2 node-3 node-4 node-5" status_json $BEFORE > "$WORK/status.v7"
slices_json node-7 node-2 > "$WORK/slices.v7"
run v7 'v=$(scale_load_retarget_to_victim "$B" 3 "org.pragmatica.aether.test:test-echo:1.0.0" 2>"$WORK/err.v7"); echo "VICTIM=$v"; cat "$WORK/err.v7"' B="$BEFORE" SCALE_LOAD_TARGET_VICTIM=1 SLICES_BODY="$WORK/slices.v7"
if grep -q '^VICTIM=node-7$' "$WORK/out.v7" && grep -q 'victim: yes, hosts slice: yes' "$WORK/out.v7" && [ "$(cat /tmp/load_endpoint_override_$$ 2>/dev/null)" = "http://h:8076" ]; then
    ok "V7 two victims (node-6, node-7), only node-7 hosts the slice: node-7 is chosen, not the first removed; logged 'victim: yes, hosts slice: yes'"
else fail "V7 out=$(head -c 300 "$WORK/out.v7")"; fi
rm -f /tmp/load_endpoint_override_$$
slices_json node-7 node-6 > "$WORK/slices.v8"; cp "$WORK/status.v7" "$WORK/status.v8"
run v8 'v=$(scale_load_retarget_to_victim "$B" 3 "org.pragmatica.aether.test:test-echo:1.0.0" 2>/dev/null); echo "VICTIM=$v"' B="$BEFORE" SCALE_LOAD_TARGET_VICTIM=1 SLICES_BODY="$WORK/slices.v8"
if grep -q '^VICTIM=node-7$' "$WORK/out.v8"; then ok "V8 both victims host it: the CURRENT owner (listed first, node-7) is kept as the target"
else fail "V8 out=$(head -c 200 "$WORK/out.v8")"; fi
rm -f /tmp/load_endpoint_override_$$
slices_json node-1 > "$WORK/slices.v9"; cp "$WORK/status.v7" "$WORK/status.v9"
run v9 'v=$(scale_load_retarget_to_victim "$B" 3 "org.pragmatica.aether.test:test-echo:1.0.0" 2>"$WORK/err.v9"); echo "VICTIM=$v"; cat "$WORK/err.v9"' B="$BEFORE" SCALE_LOAD_TARGET_VICTIM=1 SLICES_BODY="$WORK/slices.v9"
if grep -q '^VICTIM=node-6$' "$WORK/out.v9" && grep -q 'victim: yes, hosts slice: no' "$WORK/out.v9"; then
    ok "V9 no victim hosts the slice: falls back to the first removed voter, logged 'victim: yes, hosts slice: no'"
else fail "V9 out=$(head -c 300 "$WORK/out.v9")"; fi
rm -f /tmp/load_endpoint_override_$$
slices_json node-7 > "$WORK/slices.v10"; cp "$WORK/status.v7" "$WORK/status.v10"
run v10 'v=$(scale_load_retarget_to_victim "$B" 3 2>"$WORK/err.v10"); echo "VICTIM=$v"; cat "$WORK/err.v10"' B="$BEFORE" SCALE_LOAD_TARGET_VICTIM=1 SLICES_BODY="$WORK/slices.v10"
if grep -q '^VICTIM=node-6$' "$WORK/out.v10" && grep -q 'hosts slice: unknown' "$WORK/out.v10"; then
    ok "V10 no slice coords: the previous behaviour (first removed voter), hosts slice reported unknown, not guessed"
else fail "V10 out=$(head -c 300 "$WORK/out.v10")"; fi
rm -f /tmp/load_endpoint_override_$$

# V11: the step line says whether the target hosts the slice and whether a host was a victim
run v11a 'log_scale_down_step "$B" "$A" "http://h:8075" "$H"' B="$BEFORE" A="$AFTER" H=$'node-6\nnode-2'
run v11b 'log_scale_down_step "$B" "$A" "http://h:8070" "$H"' B="$BEFORE" A="$AFTER" H=$'node-7'
if grep -q 'load target hosts slice: yes' "$WORK/out.v11a" && grep -q 'a slice host was a victim: yes' "$WORK/out.v11a" \
   && grep -q 'load target hosts slice: no' "$WORK/out.v11b" && grep -q 'a slice host was a victim: yes' "$WORK/out.v11b"; then
    ok "V11 the step line records 'load target hosts slice' (yes/no) and 'a slice host was a victim'"
else fail "V11 $(cat "$WORK/out.v11a" "$WORK/out.v11b" | cut -c150-420 | tr '\n' '|')"; fi

# ---- Q2 harness: the seed's PUT and the membership gate ----------------------------------------------------------------
cat > "$WORK/bin/curl" <<'STUB'
#!/bin/bash
out=""; args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do [ "${args[$i]}" = "-o" ] && out="${args[$((i + 1))]}"; done
echo "OUT=$out" >> "$ARGS_LOG"
n=$(( $(cat "$CALLS" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$CALLS"
code=$(sed -n "${n}p" "$SEQ"); code="${code:-200}"
printf '{"call":%s,"code":%s}' "$n" "$code" > "$out"
printf '%s' "$code"
STUB
chmod +x "$WORK/bin/curl"
put_run() {  # <label> <status sequence...> -> status in put.<label>, calls in put.<label>.calls
    local label="$1"; shift
    printf '%s\n' "$@" > "$WORK/seq.$label"; rm -f "$WORK/calls.put.$label" "$WORK/args.$label" "$WORK/body.$label"
    ( export PATH="$WORK/bin:$PATH" CALLS="$WORK/calls.put.$label" SEQ="$WORK/seq.$label" ARGS_LOG="$WORK/args.$label" API_KEY=k PUT_RETRY_DELAY_S=0
      log_warn() { echo "WARN $*"; }
      eval "$(extract put_artifact_retry_503)"
      : > "$WORK/f.bin"; st=$(put_artifact_retry_503 "$WORK/f.bin" http://h/x "$WORK/body.$label" 2>"$WORK/err.put.$label"); echo "$st" > "$WORK/put.$label" )
}
put_run p1 503 200
put_run p2 500
put_run p3 503 503
if [ "$(cat "$WORK/put.p1")" = "200" ] && [ "$(cat "$WORK/calls.put.p1")" = "2" ] && grep -q '"call":2' "$WORK/body.p1" && ! grep -q 'OUT=/dev/null' "$WORK/args.p1"; then
    ok "P1 503 then 200: ONE retry succeeds, status 200, the body is recorded in a file (never -o /dev/null)"
else fail "P1 st=$(cat "$WORK/put.p1") calls=$(cat "$WORK/calls.put.p1") body=$(cat "$WORK/body.p1")"; fi
if [ "$(cat "$WORK/put.p2")" = "500" ] && [ "$(cat "$WORK/calls.put.p2")" = "1" ] && grep -q '"code":500' "$WORK/body.p2" && grep -q '"code":500' "$WORK/err.put.p2"; then
    ok "P2 a 500 is NOT retried (1 call), the status stays 500, and its body is recorded and warned"
else fail "P2 st=$(cat "$WORK/put.p2") calls=$(cat "$WORK/calls.put.p2") err=$(cat "$WORK/err.put.p2" | head -c 160)"; fi
if [ "$(cat "$WORK/put.p3")" = "503" ] && [ "$(cat "$WORK/calls.put.p3")" = "2" ]; then
    ok "P3 503 twice: exactly ONE retry (2 calls), the final 503 is reported"
else fail "P3 st=$(cat "$WORK/put.p3") calls=$(cat "$WORK/calls.put.p3")"; fi

# The settle gate, driven by the REAL node body (fixtures/node-status-real-stable.json: a node's actual
# /api/v1/nodes/status; STABLE, installedVoters bh-1..bh-5, NO targetVoters key — the serializer omits empty lists). The
# first version of these tests fed `_cluster_voters` a targetVoters list that the real node never sends when STABLE, so it
# shared the gate's wrong premise (settled = installed == target) and the gate never settled on a real cluster
# (driver-20261001T1307Z-diag: installed=[core-0..core-4] target=[] members=5, "not settled within 90s").
REAL_STATUS="${SCRIPT_DIR}/fixtures/node-status-real-stable.json"
# REQUESTED is DERIVED from the real body (stage + a targetVoters roster, as the record serializes a pending request), not captured.
sed 's/"stage":"STABLE"/"stage":"REQUESTED"/; s/"installedVoters":\(\[[^]]*\]\)/"installedVoters":\1,"targetVoters":["bh-1","bh-2","bh-3"]/' "$REAL_STATUS" > "$WORK/status.requested.json"
settled_run() {  # <label> <body file> <members> [first N polls answer with the REQUESTED body]
    local label="$1" body="$2" members="$3" unsettled="${4:-0}"
    echo 0 > "$WORK/polls.$label"
    ( export CALLS="$WORK/polls.$label" SEED_SETTLE_POLL_S=0.1 UNSETTLED="$unsettled" BODY="$body" REQ="$WORK/status.requested.json" M="$members"
      log_fail() { echo "FAIL $*" > "$WORK/logfail.$label"; }
      api_get() { local n; n=$(cat "$CALLS"); echo $((n + 1)) > "$CALLS"; if [ "$n" -lt "$UNSETTLED" ]; then cat "$REQ"; else [ -n "$BODY" ] && cat "$BODY"; fi; }
      cluster_member_count() { echo "$M"; }
      eval "$(extract _voters_in)"; eval "$(extract _seed_membership_settled)"
      _seed_membership_settled 2; echo $? > "$WORK/msrc.$label" )
}
printf '{"voterReconfiguration":{"installedVoters":"bh-1"}}' > "$WORK/body.nostage"                    # parser-blind shape 1
printf '{"voterReconfiguration":{"stage":"STABLE","installedVoters":"bh-1,bh-2"}}' > "$WORK/body.badlist"  # parser-blind shape 2
# CATCHING_UP is DERIVED too: a change was applied and a member has not caught up; no requested roster, so NO targetVoters key
sed 's/"stage":"STABLE"/"stage":"CATCHING_UP"/' "$REAL_STATUS" > "$WORK/status.catchingup.json"
settled_run m1 "$REAL_STATUS" 5
settled_run m8 "$WORK/status.catchingup.json" 5
settled_run m2 "$WORK/status.requested.json" 5
settled_run m3 "$REAL_STATUS" 7
settled_run m4 "$REAL_STATUS" 5 3
settled_run m5 "$WORK/body.nostage" 5
settled_run m6 "$WORK/body.badlist" 5
settled_run m7 "" 5
if [ "$(cat "$WORK/msrc.m1")" = "0" ] && [ "$(cat "$WORK/msrc.m2")" = "1" ] && [ "$(cat "$WORK/msrc.m3")" = "1" ] && [ "$(cat "$WORK/msrc.m4")" = "0" ]; then
    ok "M1-M4 the REAL STABLE body (no targetVoters key, 5 installed, 5 members) SETTLES (rc 0); a REQUESTED roster -> 1; members!=voters -> 1; settles after 3 REQUESTED polls -> 0"
else fail "M rc: m1=$(cat "$WORK/msrc.m1") m2=$(cat "$WORK/msrc.m2") m3=$(cat "$WORK/msrc.m3") m4=$(cat "$WORK/msrc.m4")"; fi
if [ "$(cat "$WORK/msrc.m8")" = "1" ]; then ok "M8 stage CATCHING_UP (no targetVoters, members==voters) is NOT settled: the stage, not only the roster, decides"
else fail "M8 rc=$(cat "$WORK/msrc.m8")"; fi
if [ "$(cat "$WORK/msrc.m5")" = "2" ] && grep -q 'no parsable stage' "$WORK/logfail.m5" \
   && [ "$(cat "$WORK/msrc.m6")" = "2" ] && grep -q 'installedVoters but the parser read none' "$WORK/logfail.m6"; then
    ok "M5-M6 tripwire: a body that names voterReconfiguration/installedVoters but parses to no stage / no installed list is a LOUD log_fail with rc 2, not a warning-and-wait"
else fail "M5/M6 rc: m5=$(cat "$WORK/msrc.m5") m6=$(cat "$WORK/msrc.m6") log5=$(cat "$WORK/logfail.m5" 2>/dev/null | head -c 120) log6=$(cat "$WORK/logfail.m6" 2>/dev/null | head -c 120)"; fi
if [ "$(cat "$WORK/msrc.m7")" = "1" ] && [ ! -e "$WORK/logfail.m7" ]; then
    ok "M7 control: an unreadable node (empty body) is NOT the tripwire: rc 1, no FAIL (keep waiting, then warn)"
else fail "M7 rc=$(cat "$WORK/msrc.m7") logfail=$(cat "$WORK/logfail.m7" 2>/dev/null)"; fi
# T2: the parser reads the real body's voters
if [ "$(_cluster_voters_real=1; extract _voters_in > "$WORK/vin.sh"; bash -c "source $WORK/vin.sh; _voters_in installedVoters < $REAL_STATUS | tr '\n' ' '")" = "bh-1 bh-2 bh-3 bh-4 bh-5 " ] \
   && [ -z "$(bash -c "source $WORK/vin.sh; _voters_in targetVoters < $REAL_STATUS")" ]; then
    ok "T2 on the REAL body: installedVoters = bh-1..bh-5, targetVoters = empty (the key is absent when STABLE)"
else fail "T2 real-body parse"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
