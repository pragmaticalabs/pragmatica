#!/bin/bash
# deploy-compose.sh — Build, transfer, and deploy Aether cluster via docker-compose
#
# Builds the JAR locally, copies artifacts to a remote host, builds the Docker image
# on-target (avoids arch mismatch), and starts a 5-node cluster using docker-compose.
#
# Usage:
#   TARGET_HOST=192.168.0.71 AETHER_SSH_KEY=~/.ssh/aether_test ./deploy-compose.sh
#
# Options:
#   --skip-build     Skip local Maven build (use existing JAR)
#   --skip-examples  Skip building example slices
#   --clean          Remove all containers/images before deploying
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${SCRIPT_DIR}/.."
REPO_ROOT="$(cd "${ROOT_DIR}/../../.." && pwd)"

source "${ROOT_DIR}/lib/common.sh"
source "${ROOT_DIR}/lib/cluster.sh"

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SSH_USER="${AETHER_SSH_USER:-aether}"
HOST="${TARGET_HOST:?TARGET_HOST must be set}"
SSH_KEY="${AETHER_SSH_KEY:?AETHER_SSH_KEY must be set}"
REMOTE_DIR="${AETHER_REMOTE_DIR:-/home/${SSH_USER}}"
IMAGE_TAG="aether-node:local"

NODE_JAR="${REPO_ROOT}/aether/node/target/aether-node.jar"
DOCKERFILE="${REPO_ROOT}/aether/docker/aether-node/Dockerfile"
AETHER_TOML="${REPO_ROOT}/aether/docker/aether-node/aether.toml"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.yml"

SKIP_BUILD=false
SKIP_EXAMPLES=false
CLEAN=false

for arg in "$@"; do
    case "$arg" in
        --skip-build)    SKIP_BUILD=true ;;
        --skip-examples) SKIP_EXAMPLES=true ;;
        --clean)         CLEAN=true ;;
    esac
done

ssh_exec() { ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=10 "${SSH_USER}@${HOST}" "$@"; }
scp_file() { scp -i "$SSH_KEY" -o StrictHostKeyChecking=no "$1" "${SSH_USER}@${HOST}:$2"; }

# ---------------------------------------------------------------------------
# Step 1: Local build
# ---------------------------------------------------------------------------
if [ "$SKIP_BUILD" = false ]; then
    log_step "Building project (mvn clean install -DskipTests)"
    (cd "$REPO_ROOT" && mvn clean install -DskipTests -q)
    log_pass "Maven build complete"

    if [ "$SKIP_EXAMPLES" = false ]; then
        log_step "Building example slices"
        (cd "$REPO_ROOT" && mvn -f examples/url-shortener/pom.xml clean install -DskipTests -q)
        (cd "$REPO_ROOT" && mvn -f examples/url-shortener-v2/pom.xml clean install -DskipTests -q)
        log_pass "Example slices built"
    fi
else
    log_info "Skipping local build (--skip-build)"
fi

# Verify JAR exists
if [ ! -f "$NODE_JAR" ]; then
    log_error "Node JAR not found: $NODE_JAR"
    log_error "Run without --skip-build, or build manually: mvn clean install -DskipTests"
    exit 1
fi

LOCAL_MD5=$(md5sum "$NODE_JAR" 2>/dev/null | cut -d' ' -f1 || md5 -q "$NODE_JAR")
log_info "JAR checksum: $LOCAL_MD5"

# ---------------------------------------------------------------------------
# Step 2: Transfer artifacts
# ---------------------------------------------------------------------------
log_step "Transferring artifacts to ${HOST}"

ssh_exec "mkdir -p ${REMOTE_DIR}/node/target ${REMOTE_DIR}/docker/aether-node"

scp_file "$NODE_JAR"       "${REMOTE_DIR}/node/target/aether-node.jar"
scp_file "$DOCKERFILE"     "${REMOTE_DIR}/Dockerfile"
scp_file "$AETHER_TOML"    "${REMOTE_DIR}/docker/aether-node/aether.toml"
scp_file "$COMPOSE_FILE"   "${REMOTE_DIR}/docker-compose.yml"

# Verify checksum on remote
REMOTE_MD5=$(ssh_exec "md5sum ${REMOTE_DIR}/node/target/aether-node.jar | cut -d' ' -f1")
if [ "$LOCAL_MD5" != "$REMOTE_MD5" ]; then
    log_error "Checksum mismatch! Local: $LOCAL_MD5, Remote: $REMOTE_MD5"
    exit 1
fi
log_pass "Artifacts transferred (checksum verified)"

# ---------------------------------------------------------------------------
# Step 3: Build Docker image on target
# ---------------------------------------------------------------------------
log_step "Building Docker image on ${HOST}"

if [ "$CLEAN" = true ]; then
    log_info "Cleaning old containers and images..."
    # compose down handles named services; auto-provisioned containers from CTM
    # (aether-core-node-*) are not tracked by compose, so we nuke them explicitly.
    ssh_exec "cd ${REMOTE_DIR} && docker compose down --remove-orphans 2>/dev/null; docker rm -f \$(docker ps -aq --filter 'name=aether-core') 2>/dev/null; docker rm -f \$(docker ps -aq --filter 'name=aether-node') 2>/dev/null; docker rmi ${IMAGE_TAG} 2>/dev/null" || true
fi

# Always kill auto-provisioned CTM containers before starting, even without --clean.
# They survive `docker compose down` and break consensus on the next run.
ssh_exec "docker rm -f \$(docker ps -aq --filter 'name=aether-core') 2>/dev/null" || true

ssh_exec "cd ${REMOTE_DIR} && docker build --no-cache -t ${IMAGE_TAG} -f Dockerfile . 2>&1 | tail -3"
log_pass "Docker image built: ${IMAGE_TAG}"

# ---------------------------------------------------------------------------
# Step 4: Start cluster
# ---------------------------------------------------------------------------
log_step "Starting 5-node cluster"

ssh_exec "cd ${REMOTE_DIR} && docker compose down --remove-orphans 2>/dev/null; docker compose up -d 2>&1 | tail -5"

# ---------------------------------------------------------------------------
# Step 5: Wait for cluster health
# ---------------------------------------------------------------------------
log_step "Waiting for cluster to form"
# Wait for at least one core node to be healthy (direct access)
wait_for_cluster_direct 120

LEADER=$(cluster_leader 2>/dev/null || echo "pending")
COUNT=$(cluster_node_count 2>/dev/null || echo "?")

log_pass "Cluster healthy: ${COUNT} nodes, leader: ${LEADER}"

# Check build timestamp if available
BUILD_TS=$(api_get "/api/status" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('buildTimestamp','N/A'))" 2>/dev/null || echo "N/A")
log_info "Build timestamp: ${BUILD_TS}"

echo ""
echo "========================================"
echo "  Cluster deployed on ${HOST}"
echo "  Nodes:     ${COUNT}"
echo "  Leader:    ${LEADER}"
echo "  Build:     ${BUILD_TS}"
echo "  Mgmt API:  http://${HOST}:${MGMT_PORT}"
echo "  App HTTP:  http://${HOST}:${APP_PORT}"
echo "========================================"
