#!/usr/bin/env bash
# #591 sweep driver — boots worker containers against the RUNNING remote cluster A and runs
# one coordination_slope.py row per worker-count step (4 -> 8 -> 12).
#
# Mechanism mirrors suites/13-edge-cases/test-worker-join-accounting.sh's raw-worker boot
# (the shipped join path: self-asserted AETHER_ROLE=worker + PEERS + cluster name/secret read
# from a live core container). Communities stay 1 throughout — the re-scoped #591 deliverable.
#
# Usage: source ~/IdeaProjects/.env first; then ./slope_sweep.sh
# Requires: TARGET_HOST, AETHER_SSH_USER, AETHER_SSH_KEY. Never echoes secret values.
set -u
cd "$(dirname "$0")"

NETWORK="aether-a-network"
IMAGE="aether-node:local"
PREFIX="aether-a-slope-w"
WINDOW="${WINDOW:-60}"
OUT="${OUT:-slope-results.jsonl}"
CORES_CSV="http://${TARGET_HOST}:5151,http://${TARGET_HOST}:5152,http://${TARGET_HOST}:5153,http://${TARGET_HOST}:5154,http://${TARGET_HOST}:5155"
IDS_CSV="aether-a-node-1,aether-a-node-2,aether-a-node-3,aether-a-node-4,aether-a-node-5"

rexec() { ssh -i "$AETHER_SSH_KEY" -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new "${AETHER_SSH_USER}@${TARGET_HOST}" "$@"; }

peers() {
    rexec "docker ps --filter label=aether.node-id --filter network=${NETWORK} --format '{{.Label \"aether.node-id\"}}' | sort" \
        | grep -v "^${PREFIX}" \
        | awk '{ printf "%s%s:%s:6000", sep, $1, $1; sep="," } END { print "" }'
}

cluster_secret() {
    rexec "c=\$(docker ps --filter label=aether.node-id --filter network=${NETWORK} --format '{{.Names}}' | grep -v slope | head -1); docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' \"\$c\" | grep '^AETHER_CLUSTER_SECRET=' | cut -d= -f2-"
}

boot_worker() {
    local name="$1" peers_list="$2" secret="$3"
    rexec "docker rm -f ${name} >/dev/null 2>&1 || true; \
docker run -d --restart no \
  --name ${name} --hostname ${name} \
  --network ${NETWORK} \
  --label aether.cluster=a \
  --label aether.node-id=${name} \
  --label aether.role=worker \
  -e NODE_ID=${name} \
  -e AETHER_NODE_ID=${name} \
  -e CLUSTER_PORT=6000 \
  -e MANAGEMENT_PORT=8080 \
  -e PEERS='${peers_list}' \
  -e CORE_MAX=5 \
  -e AETHER_CLUSTER_NAME=a \
  -e AETHER_CLUSTER_SECRET='${secret}' \
  -e AETHER_ROLE=worker \
  -e AETHER_INSECURE_DEV_MODE=true \
  -e JAVA_OPTS='-Xmx512m -XX:+UseZGC -Djava.net.preferIPv4Stack=true' \
  ${IMAGE} >/dev/null" || { echo "FAIL: docker run ${name}"; return 1; }
}

worker_state() {
    # Mirrors the proven fsm_role_of/fsm_state_of parsing from test-worker-join-accounting.sh:
    # each fsmMembers entry carries "nodeId", "role" and "fsmState". Two separate greps — a
    # combined sed with a `t` branch is not portable to BSD sed (t consumes the rest as a label).
    local line role state
    line=$(/usr/bin/curl -s --max-time 8 -H "X-API-Key: ${AETHER_API_KEY}" "http://${TARGET_HOST}:5151/api/v1/cluster/topology" \
        | grep -o "\"nodeId\"[[:space:]]*:[[:space:]]*\"$1\"[^}]*" \
        | grep '"fsmState"' \
        | head -1)
    role=$(printf '%s' "$line" | grep -o '"role"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
    state=$(printf '%s' "$line" | grep -o '"fsmState"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
    echo "${role}:${state}"
}

wait_member() {
    local name="$1" deadline=$((SECONDS + 150))
    while [ $SECONDS -lt $deadline ]; do
        [ "$(worker_state "$name")" = "worker:Member" ] && return 0
        sleep 3
    done
    echo "TIMEOUT: ${name} state=$(worker_state "$name")"
    return 1
}

ensure_workers() {
    local target="$1" secret peers_list
    secret=$(cluster_secret); peers_list=$(peers)
    [ -n "$secret" ] || { echo "FAIL: no cluster secret"; exit 1; }
    [ -n "$peers_list" ] || { echo "FAIL: no peers"; exit 1; }
    for i in $(seq 1 "$target"); do
        local name="${PREFIX}${i}"
        if ! rexec "docker ps --format '{{.Names}}'" | grep -q "^${name}\$"; then
            echo "booting ${name}"
            boot_worker "$name" "$peers_list" "$secret" || exit 1
        fi
    done
    for i in $(seq 1 "$target"); do
        wait_member "${PREFIX}${i}" || exit 1
    done
    echo "workers=${target} all Member"
}

for COUNT in ${COUNTS:-4 8 12}; do
    echo "=== step: ${COUNT} workers ==="
    ensure_workers "$COUNT"
    echo "settling 30s before the ${WINDOW}s window"
    sleep 30
    ./coordination_slope.py --cores "$CORES_CSV" --node-ids "$IDS_CSV" \
                            --workers "$COUNT" --window "$WINDOW" --out "$OUT" \
        || { echo "FAIL: slope row at ${COUNT} workers"; exit 1; }
done
echo "SWEEP COMPLETE -> ${OUT}"
