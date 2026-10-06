#!/bin/bash
# test-no-same-id-relaunch.sh — stubs only (#1543 part A).
#
# A node id never returns: a killed or stopped node is REPLACED under a fresh id (CTM auto-heal), never
# relaunched. Two pins:
#   C1  census: no tracked file under aether/ (shell, compose, Java; docs excluded) relaunches a node under
#       its old id — start_node, cloud_revive_vm, cloud_stop_vm, `hcloud server poweron`, restartNode /
#       RestartNode / NodeRestarted, ComputeProvider#restart, `docker start`, `compose start`, `docker restart`,
#       `systemctl restart`, `kubectl rollout restart` — outside ALLOW below.
#   C2  every ALLOW entry still matches something (a stale entry hides the next regression: delete it when
#       the code it excuses goes — parts B and F of #1543 empty the bootstrap and upgrade-script lines).
#   C3  controls: the detector finds each pattern when planted, ignores comment lines, and reports an
#       allow-listed file only through ALLOW (a detector that cannot fail proves nothing).
#   H1  the REAL 02w cleanup(): after a confirmed kill it restores the baseline through auto-heal and issues
#       no `docker start` / `hcloud server poweron`; the replacement it ends with is a NEW id, killed id absent.
#   H2  control: with no confirmed kill, cleanup does not touch the cluster.
#   H3  mutation witness: the pre-#1543 cleanup (a `docker start` of the killed id) trips H1's recorder.
#
# What this does NOT prove: that docker auto-heal really replaces the node (02w itself, on bigboy, does) and
# that the in-process Ember/Forge restarts are gone (StreamCrashDurability / MultiPartitionCrashDurability /
# DurableEntityTimerDurability still stop() -> start() under the same ids; owner ruling pending, see the PR).
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="${REPO_ROOT_UNDER_TEST:-$(cd "${INTEG_DIR}/../../.." && pwd)}"
SUITE_02W="${SUITE_02W_UNDER_TEST:-${INTEG_DIR}/suites/02w-entity-crash/test-entity-crash-durability.sh}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

PATTERN='(^|[^_[:alnum:]])start_node([^_[:alnum:]]|$)|cloud_revive_vm|cloud_stop_vm|server poweron|restartNode|RestartNode|NodeRestarted|[.]restart\(|docker (compose [^|;]*)?(start|restart)([^_[:alnum:]-]|$)|COMPOSE (start|restart)|systemctl (re)?start|kubectl rollout restart|down -v && docker compose [^"]*up -d'

# path-prefix|hit-text-regex|reason. Every excuse is scoped to the TEXT of the hit, so a NEW relaunch added
# to an excused file (a `docker start` appended to chaos-controller.sh) is still a violation. The path is a prefix.
ALLOW=(
  'aether/script/demo-cluster.sh|start_node|first start of a demo cluster, not a relaunch'
  'aether/script/rolling-aether-upgrade.sh|systemctl restart|docker restart|kubectl rollout restart|#1543 part F replaces the script with `aether cluster upgrade --wait`'
  'aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/BootstrapPhaseDeploy.java|restartNodesWithFinalPeers|systemctl restart|#1543 part B: launch-once bootstrap'
  'aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapPhaseDeployCloudSshRestartTest.java|systemctl restart|#1543 part B: pins the code above'
  'aether/aether-config/src/main/java/org/pragmatica/aether/config/cluster/NodeUserDataRenderer.java|systemctl start|FIRST start in cloud-init (never enabled)'
  'aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/UserDataTemplatePeersTest.java|systemctl start|asserts the cloud-init first start above'
  'aether/tests/cloud/deploy-cloud.sh|systemctl start docker|`systemctl start docker` on a fresh VM, not an aether node'
  'integrations/dht/src/test/java/org/pragmatica/dht/|cluster[.]restart[(]|in-process DHT unit cluster restart (no NodeId lifecycle, no aether runtime)'
  'aether/tests/integration/lib/cluster.sh|down -v && docker compose|#1543 part A2 / #1968: restart_all_nodes compose down/up is a whole-cluster same-id cold start until it restarts onto fresh ids with KV restore'
  'aether/docker/scaling-test/k6/chaos-controller.sh|COMPOSE (stop|start)|#1968: the soak compose has no docker.sock/auto-heal wiring, so its stop/start chaos stays until that compose can heal under fresh ids'
  'aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamCrashDurabilityTest.java|stop[(][)] then .*start[(][)] in restartCluster|owner question: data durability across a whole-cluster restart onto fresh nodes (#1968)'
  'aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/MultiPartitionCrashDurabilityTest.java|stop[(][)] then .*start[(][)] in restartCluster|owner question: data durability across a whole-cluster restart onto fresh nodes (#1968)'
  'aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/DurableEntityTimerDurabilityTest.java|stop[(][)] then .*start[(][)] in restartCluster|owner question: data durability across a whole-cluster restart onto fresh nodes (#1968)'
)
# An entry is `path|regex|reason`; a regex may itself contain `|`, so the reason is the LAST field and the path
# the FIRST: everything between is the regex.
al_path()   { printf '%s' "${1%%|*}"; }
al_reason() { printf '%s' "${1##*|}"; }
al_rx()     { local m="${1#*|}"; printf '%s' "${m%|*}"; }

# The Java arm: a method that stops a cluster-like variable and then starts the SAME variable again, in its own
# body or through one same-file helper — the in-process whole-cluster restart (Ember/Forge), under the same ids.
INPROC="$WORK/inproc.py"
cat > "$INPROC" <<'PY'
import re, sys
# Print "<file>:<line>:<var>.stop() then start() in <method>" for every method that stops a cluster-like variable
# and then (in its own body, or via one same-file helper it calls) starts the SAME variable again.
HDR = re.compile(r'^( {4}| {8})(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:private|public|protected|static|final|synchronized)\s+)*[\w<>\[\], ?]+\s+(\w+)\s*\([^)]*\)\s*(?:throws [\w., ]+)?\{\s*$')
def methods(src):
    lines = src.split('\n'); out = {}; i = 0
    while i < len(lines):
        m = HDR.match(lines[i])
        if m:
            ind = m.group(1); j = i + 1
            while j < len(lines) and not re.match('^' + ind + r'\}\s*$', lines[j]): j += 1
            out.setdefault(m.group(2), []).append((i + 1, '\n'.join(lines[i:j + 1])))
            i = j
        i += 1
    return out
for f in sys.argv[1:]:
    try: src = open(f).read()
    except OSError: continue
    ms = methods(src)
    starts = {n: set(re.findall(r'\b(\w+)\.start\(', b)) for n, l in ms.items() for _, b in l}
    for name, lst in ms.items():
        for line, body in lst:
            for v in set(re.findall(r'\b([a-z]\w*)\.stop\(\)', body)):
                pos = body.index(v + '.stop()')
                # a stop() registered in a shutdown hook runs at process exit, after every start(): not a restart
                if 'ShutdownHook' in '\n'.join(body[:pos].split('\n')[-3:]): continue
                after = body[pos:]
                direct = re.search(r'\b' + v + r'\.start\(', after)
                via = [n for n in re.findall(r'\b(\w+)\(', after) if n != name and v in starts.get(n, set())]
                if direct or via:
                    print(f'{f}:{line}:{v}.stop() then {v}.start() in {name}()'); break
PY

# census <root> -> prints "path:line:text" for every non-comment line matching PATTERN under aether/ and
# integrations/ (tracked files; docs excluded), one per hit, before the allow-list.
census() {
    local root="$1"
    ( cd "$root" && git ls-files -z aether integrations 2>/dev/null \
        | tr '\0' '\n' | grep -E '[.](sh|java|ya?ml)$' | grep -v '/docs/' | grep -v 'tests/integration/test/test-no-same-id-relaunch.sh' \
        | tr '\n' '\0' | xargs -0 grep -nE "$PATTERN" /dev/null 2>/dev/null \
        | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(#|//)'
      git ls-files -z aether/ember aether/forge 2>/dev/null | tr '\0' '\n' | grep -E '[.]java$' \
        | tr '\n' '\0' | xargs -0 python3 -I "$INPROC" ) 
}
allowed() {  # <path:line:text> -> 0 when an ALLOW entry covers the path (prefix) AND the hit text
    local hit="$1" p e
    p="${hit%%:*}"
    for e in "${ALLOW[@]}"; do
        case "$p" in "$(al_path "$e")"*) printf '%s' "${hit#*:}" | grep -qE -- "$(al_rx "$e")" && return 0 ;; esac
    done
    return 1
}
violations() {  # <root>
    census "$1" | while IFS= read -r hit; do
        [ -n "$hit" ] && { allowed "$hit" || printf '%s\n' "$hit"; }
    done
}

echo "== C. census"
CENSUS_ALL=$(census "$REPO_ROOT")
total=$(printf '%s\n' "$CENSUS_ALL" | grep -c .)
v=$(printf '%s\n' "$CENSUS_ALL" | while IFS= read -r hit; do [ -n "$hit" ] && { allowed "$hit" || printf '%s\n' "$hit"; }; done)
if [ -z "$v" ]; then ok "C1 no same-id relaunch outside ALLOW (${total} excused hit(s) scanned)"
else fail "C1 same-id relaunch outside ALLOW:"; printf '%s\n' "$v" | sed 's/^/        /'; fi

stale=""
for e in "${ALLOW[@]}"; do
    pfx="$(al_path "$e")"; rx="$(al_rx "$e")"
    printf '%s\n' "$CENSUS_ALL" | grep "^${pfx}" | cut -d: -f2- | grep -qE -- "$rx" || stale="${stale} ${pfx}(${rx})"
done
if [ -z "$stale" ]; then ok "C2 every ALLOW entry still excuses a live hit"
else fail "C2 stale ALLOW entr(ies), delete them:${stale}"; fi

# C3 controls on a scratch repo: every pattern alternative detected, comments ignored, allow-list honoured.
G="$WORK/g"; mkdir -p "$G/aether/tests/integration/lib" "$G/aether/script"
git -C "$G" init -q
planted=(
  'start_node "$X"' 'cloud_revive_vm "$X"' 'cloud_stop_vm "$X"' 'hcloud server poweron 1' 'x.restartNode(n)'
  'new NodeAction.RestartNode(n)' 'new ActionResult.NodeRestarted(n)' 'provider.restart(id)' 'docker start c'
  'docker compose -f a.yml start n' '$COMPOSE start n' 'docker restart c' 'systemctl restart u' 'systemctl start u'
  'kubectl rollout restart d'
)
miss=""
for p in "${planted[@]}"; do
    printf '%s\n' "$p" > "$G/aether/tests/integration/lib/x.sh"
    git -C "$G" add -A >/dev/null 2>&1
    [ -n "$(violations "$G")" ] || miss="${miss} [${p}]"
done
[ -z "$miss" ] && ok "C3a all ${#planted[@]} patterns are detected when planted" || fail "C3a detector missed:${miss}"
printf '%s\n' '# start_node "$X"' '    // cloud_revive_vm' '/// restartNode' 'start_node_pool=1' > "$G/aether/tests/integration/lib/x.sh"
git -C "$G" add -A >/dev/null 2>&1
[ -z "$(violations "$G")" ] && ok "C3b comment lines and start_node_pool are not hits" || fail "C3b false positive: $(violations "$G")"
printf '%s\n' 'start_node "$X"' > "$G/aether/script/demo-cluster.sh"; rm -f "$G/aether/tests/integration/lib/x.sh"
git -C "$G" add -A >/dev/null 2>&1
[ -z "$(violations "$G")" ] && [ "$(census "$G" | wc -l | tr -d ' ')" = 1 ] \
    && ok "C3c an allow-listed file is census-visible but not a violation" || fail "C3c allow-list control failed"

# C3d every excuse is text-scoped: the excused line stays quiet, a NEW relaunch in the same file is a violation.
rm -rf "$G"; G="$WORK/g2"; mkdir -p "$G/aether/script" "$G/aether/tests/integration/lib" "$G/aether/docker/scaling-test/k6"
git -C "$G" init -q
printf '%s\n' 'start_node "$X"' 'docker start c' > "$G/aether/script/demo-cluster.sh"
printf '%s\n' 'r=$(x; down -v && docker compose -f a.yml up -d)' 'start_node "$X"' > "$G/aether/tests/integration/lib/cluster.sh"
printf '%s\n' '$COMPOSE start n' 'docker start c' > "$G/aether/docker/scaling-test/k6/chaos-controller.sh"
git -C "$G" add -A >/dev/null 2>&1
vl=$(violations "$G")
if [ "$(printf '%s\n' "$vl" | grep -c .)" = 3 ] && printf '%s' "$vl" | grep -q 'demo-cluster.sh:2:docker start' \
   && printf '%s' "$vl" | grep -q 'cluster.sh:2:start_node' && printf '%s' "$vl" | grep -q 'chaos-controller.sh:2:docker start'; then
    ok "C3d allow-list entries are text-scoped: the excused line is quiet, a new relaunch in the same file is a violation"
else fail "C3d allow-list too broad or detector blind: ${vl}"; fi

# C3e the Java arm: stop() then start() of the SAME cluster variable in one method (own body or via a same-file
# helper) is caught; stop() and start() of DIFFERENT variables, or start-then-stop, are not.
J="$G/aether/forge/forge-tests/src/test/java/p"; mkdir -p "$J"
cat > "$J/DirectTest.java" <<'JAVA'
class DirectTest {
    private void bounce() {
        cluster.stop().await();
        cluster.start().await();
    }
}
JAVA
cat > "$J/HelperTest.java" <<'JAVA'
class HelperTest {
    private void restartCluster() {
        cluster.stop().await();
        startAndAwaitReady();
    }

    private void startAndAwaitReady() {
        cluster.start().await();
    }
}
JAVA
cat > "$J/CleanTest.java" <<'JAVA'
class CleanTest {
    private void swap() {
        a.stop().await();
        b.start().await();
    }

    void tearDown() {
        cluster.start().await();
        cluster.stop().await();
    }
}
JAVA
git -C "$G" add -A >/dev/null 2>&1
vj=$(violations "$G" | grep 'forge-tests')
if printf '%s' "$vj" | grep -q 'DirectTest.java:2:cluster.stop() then cluster.start() in bounce' \
   && printf '%s' "$vj" | grep -q 'HelperTest.java:2:cluster.stop() then cluster.start() in restartCluster' \
   && ! printf '%s' "$vj" | grep -q 'CleanTest'; then
    ok "C3e Java arm: direct and helper-mediated stop()->start() caught; different variables and start-then-stop are not"
else fail "C3e Java arm wrong: ${vj:-<nothing>}"; fi

echo "== H. 02w cleanup"
# Run the REAL cleanup() text from the suite against recording stubs.
FN="$WORK/cleanup-fn.sh"
sed -n '/^cleanup() {/,/^}/p' "$SUITE_02W" > "$FN"
[ -s "$FN" ] && ok "H0 cleanup() extracted from $(basename "$SUITE_02W")" || fail "H0 cleanup() not found in the suite"

run_cleanup() {  # <kill_confirmed> ; prints the call record
    local rec="$WORK/rec.$RANDOM"; : > "$rec"
    (
        ORIG="node-1 node-2 node-3 node-4 node-5"
        MEMBERS="$ORIG"
        KILL_CONFIRMED="$1"; NODE_TO_KILL="node-3"; ENTITY_BP="bp"; ACKED_PRE="$WORK/a"; ACKED_DURING="$WORK/b"
        log_warn() { echo "WARN $*" >> "$rec"; }
        reap_creator() { :; }
        api_delete() { echo "api_delete $1" >> "$rec"; }
        remote_exec() { echo "remote_exec $*" >> "$rec"; }
        docker() { echo "docker $*" >> "$rec"; }
        hcloud() { echo "hcloud $*" >> "$rec"; }
        # auto-heal model: the killed id leaves, a fresh id joins — restore_cluster_baseline is the wait.
        restore_cluster_baseline() {
            echo "restore_cluster_baseline" >> "$rec"
            MEMBERS="${MEMBERS//node-3/node-FRESH01}"
        }
        # shellcheck disable=SC1090
        source "$FN"
        cleanup
        echo "MEMBERS=${MEMBERS}" >> "$rec"
    )
    echo "$rec"
}
rec=$(run_cleanup 1)
members=$(sed -n 's/^MEMBERS=//p' "$rec")
if [ "$(grep -nE '^(restore_cluster_baseline|api_delete)' "$rec" | head -1 | cut -d: -f2)" = restore_cluster_baseline ] && grep -q '^restore_cluster_baseline$' "$rec" && ! grep -qE 'docker start|poweron|docker restart' "$rec" \
   && [ "$(echo $members | wc -w | tr -d ' ')" = 5 ] && echo " $members " | grep -q ' node-FRESH01 ' && ! echo " $members " | grep -q ' node-3 '; then
    ok "H1 confirmed kill: restore_cluster_baseline (before the blueprint is removed), no docker start/poweron, 5 members incl. a NEW id, killed id gone"
else fail "H1 cleanup after a confirmed kill: $(tr '\n' '|' < "$rec")"; fi
rec=$(run_cleanup 0)
if ! grep -qE '^(restore_cluster_baseline|docker|hcloud|remote_exec)' "$rec"; then ok "H2 no confirmed kill: cleanup leaves the cluster alone"
else fail "H2 cleanup touched the cluster without a kill: $(tr '\n' '|' < "$rec")"; fi

# H3 mutation witness: the pre-#1543 body must trip the recorder, or H1 could not have failed.
OLD="$WORK/old-cleanup.sh"
cat > "$OLD" <<'OLDCLEANUP'
cleanup() {
    if [ "$KILL_CONFIRMED" -eq 1 ] && [ -n "$NODE_TO_KILL" ]; then
        remote_exec "docker start ${NODE_TO_KILL}"
    fi
}
OLDCLEANUP
FN_SAVED="$FN"; FN="$OLD"; rec=$(run_cleanup 1); FN="$FN_SAVED"
if grep -qE 'docker start' "$rec" && ! grep -q '^restore_cluster_baseline$' "$rec"; then ok "H3 the old same-id cleanup is caught by the same recorder"
else fail "H3 recorder cannot see a same-id relaunch: $(tr '\n' '|' < "$rec")"; fi

echo
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ] && [ "$PASS" -gt 0 ]
