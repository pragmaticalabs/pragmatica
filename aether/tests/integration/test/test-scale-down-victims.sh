#!/bin/bash
# test-scale-down-victims.sh — stubs only. 03-scaling bookkeeping and the opt-in reproduction aid (S-triple-prime: the
# load target, core-4, was a scale-down victim and 12 of 482 requests answered 503; nothing recorded it).
#   V1  one INFO line per scale-down step: who left, which node the load targeted, and whether it was a victim
#       (yes / no / unknown). Recording only.
#   V2  flag OFF (the default): scale_load_retarget_to_victim does NOTHING (no status read, no override file)
#   V3  SCALE_LOAD_TARGET_VICTIM=1: the load is re-aimed at a node removed from the electorate: target ∈ victims
#   V4  flag ON but nobody leaves within the budget: no override, a WARN, the load keeps its target
#   V5  start_load's loop honours the override file mid-run; without it every request hits the original endpoint
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
for f in _cluster_voters _id_set_difference _app_endpoint_for_node log_scale_down_step scale_load_retarget_to_victim; do extract "$f"; done
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

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
