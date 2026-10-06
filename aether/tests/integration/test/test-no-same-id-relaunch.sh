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

PATTERN='(^|[^_[:alnum:]])start_node([^_[:alnum:]]|$)|cloud_revive_vm|cloud_stop_vm|server poweron|restartNode|RestartNode|NodeRestarted|[.]restart\(|docker (compose [^|;]*)?(start|restart)([^_[:alnum:]-]|$)|COMPOSE (start|restart)|systemctl (re)?start|kubectl rollout restart|down -v && docker compose [^"]*up -d|void restartCluster[(]'

# path|hit-text-regex|reason: excuses only the lines of that file matching the regex, so the rest of the
# file stays under the census.
ALLOW_TEXT=(
  'aether/tests/integration/lib/cluster.sh|down -v && docker compose|#1543 part A2 / #1968: restart_all_nodes compose down/up is a whole-cluster same-id cold start until it restarts onto fresh ids with KV restore'
)

# path-suffix|reason. A line in an allow-listed file is excused; the file must still match (C2).
ALLOW=(
  'aether/script/demo-cluster.sh|first start of a demo cluster, not a relaunch'
  'aether/script/rolling-aether-upgrade.sh|#1543 part F replaces the script with `aether cluster upgrade --wait`'
  'aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/BootstrapPhaseDeploy.java|#1543 part B: launch-once bootstrap'
  'aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapPhaseDeployCloudSshRestartTest.java|#1543 part B: pins the code above'
  'aether/aether-config/src/main/java/org/pragmatica/aether/config/cluster/NodeUserDataRenderer.java|FIRST start in cloud-init (`systemctl start`, never enabled)'
  'aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/UserDataTemplatePeersTest.java|asserts the cloud-init first start above'
  'aether/docker/scaling-test/k6/chaos-controller.sh|#1968: the soak compose has no docker.sock/auto-heal wiring, so its stop/start chaos stays until that compose can heal under fresh ids'
  'aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamCrashDurabilityTest.java|owner question: data durability across a whole-cluster restart onto fresh nodes (#1968)'
  'aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/MultiPartitionCrashDurabilityTest.java|owner question: data durability across a whole-cluster restart onto fresh nodes (#1968)'
  'aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/DurableEntityTimerDurabilityTest.java|owner question: data durability across a whole-cluster restart onto fresh nodes (#1968)'
  'aether/tests/cloud/deploy-cloud.sh|`systemctl start docker` on a fresh VM, not an aether node'
  'integrations/dht/src/test/java/org/pragmatica/dht/|in-process DHT unit cluster restart (no NodeId lifecycle, no aether runtime)'
)

# census <root> -> prints "path:line:text" for every non-comment line matching PATTERN under aether/ and
# integrations/ (tracked files; docs excluded), one per hit, before the allow-list.
census() {
    local root="$1"
    ( cd "$root" && git ls-files -z aether integrations -- '*.sh' '*.java' '*.yml' '*.yaml' 2>/dev/null \
        | tr '\0' '\n' | grep -v '/docs/' | grep -v 'tests/integration/test/test-no-same-id-relaunch.sh' \
        | tr '\n' '\0' | xargs -0 grep -nE "$PATTERN" /dev/null 2>/dev/null \
        | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(#|//)' )
}
allowed() {  # <path:line:text> -> 0 when allow-listed
    local hit="$1" p e path rx
    p="${hit%%:*}"
    for e in "${ALLOW[@]}"; do
        case "$p" in "${e%%|*}"*) return 0 ;; esac
    done
    for e in "${ALLOW_TEXT[@]}"; do
        path="${e%%|*}"; rx="${e#*|}"; rx="${rx%%|*}"
        [ "$p" = "$path" ] && printf '%s' "$hit" | grep -qE -- "$rx" && return 0
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
    pfx="${e%%|*}"
    printf '%s\n' "$CENSUS_ALL" | grep -q "^${pfx}" || stale="${stale} ${pfx}"
done
for e in "${ALLOW_TEXT[@]}"; do
    path="${e%%|*}"; rx="${e#*|}"; rx="${rx%%|*}"
    printf '%s\n' "$CENSUS_ALL" | grep "^${path}:" | grep -qE -- "$rx" || stale="${stale} ${path}(${rx})"
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

printf '%s\n' 'start_node "$X"' 'x; down -v && docker compose -f a.yml up -d' > "$G/aether/script/demo-cluster.sh"
mkdir -p "$G/aether/tests/integration/lib"; printf '%s\n' 'start_node "$X"' 'r=$(x; down -v && docker compose -f a.yml up -d)' > "$G/aether/tests/integration/lib/cluster.sh"
git -C "$G" add -A >/dev/null 2>&1
vl=$(violations "$G")
[ "$(printf '%s\n' "$vl" | grep -c .)" = 1 ] && printf '%s' "$vl" | grep -q 'cluster.sh:1:start_node' \
    && ok "C3d text allow-list excuses only the compose cycle line; a start_node in the same file is still a violation" || fail "C3d text allow-list too broad: ${vl}"

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
