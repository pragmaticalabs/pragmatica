#!/bin/bash
# deploy-cloud.sh — Deploy Aether cluster to cloud instances (Hetzner, AWS, GCP, etc.)
#
# Deploys to raw Linux instances (no docker-compose). Each instance runs one Aether node
# directly via Docker. Supports any cloud provider with SSH access to Linux instances.
#
# Usage:
#   # Deploy to existing instances:
#   NODES="10.0.0.1,10.0.0.2,10.0.0.3" AETHER_SSH_KEY=~/.ssh/cloud ./deploy-cloud.sh
#
#   # Hetzner-specific (creates instances via hcloud CLI):
#   PROVIDER=hetzner HCLOUD_TOKEN=xxx ./deploy-cloud.sh --create
#
# Options:
#   --skip-build     Skip local Maven build
#   --create         Create cloud instances (requires provider-specific CLI)
#   --destroy        Destroy instances after tests
#   --clean          Remove all Docker artifacts before deploying
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${SCRIPT_DIR}/.."
REPO_ROOT="$(cd "${ROOT_DIR}/../../.." && pwd)"

source "${ROOT_DIR}/lib/common.sh"

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SSH_USER="${AETHER_SSH_USER:-root}"
SSH_KEY="${AETHER_SSH_KEY:?AETHER_SSH_KEY must be set}"
IMAGE_TAG="aether-node:local"
NODE_JAR="${REPO_ROOT}/aether/node/target/aether-node.jar"
DOCKERFILE="${REPO_ROOT}/aether/docker/aether-node/Dockerfile"
AETHER_TOML="${REPO_ROOT}/aether/docker/aether-node/aether.toml"
CLUSTER_PORT=6000
MGMT_PORT_INTERNAL=8080
APP_PORT_INTERNAL=8070
API_KEY="${AETHER_API_KEY:-aether-integration-test-key}"

PROVIDER="${PROVIDER:-generic}"
CREATE=false
DESTROY=false
SKIP_BUILD=false
CLEAN=false

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=true ;;
        --create)     CREATE=true ;;
        --destroy)    DESTROY=true ;;
        --clean)      CLEAN=true ;;
    esac
done

# ---------------------------------------------------------------------------
# Instance management (provider-specific)
# ---------------------------------------------------------------------------
HETZNER_TYPE="${HETZNER_TYPE:-cx22}"
HETZNER_IMAGE="${HETZNER_IMAGE:-docker-ce}"
HETZNER_LOCATION="${HETZNER_LOCATION:-fsn1}"
HETZNER_NAME_PREFIX="${HETZNER_NAME_PREFIX:-aether-test}"
INSTANCE_COUNT="${INSTANCE_COUNT:-5}"

create_hetzner_instances() {
    log_step "Creating ${INSTANCE_COUNT} Hetzner instances"
    local ips=""
    for i in $(seq 1 "$INSTANCE_COUNT"); do
        local name="${HETZNER_NAME_PREFIX}-${i}"
        log_info "Creating instance: ${name} (${HETZNER_TYPE} in ${HETZNER_LOCATION})"
        hcloud server create \
            --name "$name" \
            --type "$HETZNER_TYPE" \
            --image "$HETZNER_IMAGE" \
            --location "$HETZNER_LOCATION" \
            --ssh-key "$(basename "$SSH_KEY" .pub)" \
            --label "cluster=aether-test" \
            > /dev/null
        local ip
        ip=$(hcloud server ip "$name")
        [ -n "$ips" ] && ips="${ips},"
        ips="${ips}${ip}"
        log_pass "Instance ${name} created: ${ip}"
    done
    echo "$ips"
}

destroy_hetzner_instances() {
    log_step "Destroying Hetzner instances"
    for i in $(seq 1 "$INSTANCE_COUNT"); do
        local name="${HETZNER_NAME_PREFIX}-${i}"
        hcloud server delete "$name" 2>/dev/null && log_pass "Destroyed ${name}" || log_warn "Instance ${name} not found"
    done
}

get_node_ips() {
    if [ -n "${NODES:-}" ]; then
        echo "$NODES"
        return
    fi
    if [ "$PROVIDER" = "hetzner" ] && [ "$CREATE" = true ]; then
        create_hetzner_instances
        return
    fi
    log_error "NODES must be set (comma-separated IPs) or use --create with a provider"
    exit 1
}

# ---------------------------------------------------------------------------
# SSH helpers
# ---------------------------------------------------------------------------
ssh_node() {
    local ip="$1"; shift
    ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=10 "${SSH_USER}@${ip}" "$@"
}

scp_to_node() {
    local ip="$1" src="$2" dst="$3"
    scp -i "$SSH_KEY" -o StrictHostKeyChecking=no "$src" "${SSH_USER}@${ip}:${dst}"
}

# ---------------------------------------------------------------------------
# Step 1: Build
# ---------------------------------------------------------------------------
if [ "$SKIP_BUILD" = false ]; then
    log_step "Building project"
    (cd "$REPO_ROOT" && mvn clean install -DskipTests -q)
    log_pass "Build complete"
fi

if [ ! -f "$NODE_JAR" ]; then
    log_error "Node JAR not found: $NODE_JAR"
    exit 1
fi

# ---------------------------------------------------------------------------
# Step 2: Get instance IPs
# ---------------------------------------------------------------------------
IFS=',' read -ra NODE_IPS <<< "$(get_node_ips)"
TOTAL_NODES=${#NODE_IPS[@]}

log_info "Deploying to ${TOTAL_NODES} nodes: ${NODE_IPS[*]}"

# Build PEERS string: node-1:IP1:6000,node-2:IP2:6000,...
PEERS=""
for i in $(seq 1 "$TOTAL_NODES"); do
    [ -n "$PEERS" ] && PEERS="${PEERS},"
    PEERS="${PEERS}node-${i}:${NODE_IPS[$((i-1))]}:${CLUSTER_PORT}"
done
log_info "PEERS: ${PEERS}"

# ---------------------------------------------------------------------------
# Step 3: Deploy to each node
# ---------------------------------------------------------------------------
log_step "Deploying to all nodes"

for i in $(seq 1 "$TOTAL_NODES"); do
    local_idx=$((i - 1))
    ip="${NODE_IPS[$local_idx]}"
    node_id="node-${i}"

    log_info "Deploying to ${node_id} (${ip})"

    # Ensure Docker is available
    ssh_node "$ip" "docker version > /dev/null 2>&1" || {
        log_info "Installing Docker on ${ip}..."
        ssh_node "$ip" "curl -fsSL https://get.docker.com | sh" > /dev/null 2>&1
    }

    # Clean if requested
    if [ "$CLEAN" = true ]; then
        ssh_node "$ip" "docker rm -f aether-node 2>/dev/null; docker rmi ${IMAGE_TAG} 2>/dev/null" || true
    fi

    # Transfer files
    ssh_node "$ip" "mkdir -p /opt/aether/node/target /opt/aether/docker/aether-node"
    scp_to_node "$ip" "$NODE_JAR"    "/opt/aether/node/target/aether-node.jar"
    scp_to_node "$ip" "$DOCKERFILE"  "/opt/aether/Dockerfile"
    scp_to_node "$ip" "$AETHER_TOML" "/opt/aether/docker/aether-node/aether.toml"

    # Build image
    ssh_node "$ip" "cd /opt/aether && docker build --no-cache -t ${IMAGE_TAG} -f Dockerfile . > /dev/null 2>&1"

    # Stop existing container
    ssh_node "$ip" "docker rm -f aether-node 2>/dev/null" || true

    # Start node
    ssh_node "$ip" "docker run -d \
        --name aether-node \
        --hostname aether-node \
        --network host \
        --restart unless-stopped \
        -e NODE_ID=${node_id} \
        -e CLUSTER_PORT=${CLUSTER_PORT} \
        -e MANAGEMENT_PORT=${MGMT_PORT_INTERNAL} \
        -e PEERS=${PEERS} \
        -e CORE_MAX=${TOTAL_NODES} \
        -e AETHER_API_KEY=${API_KEY} \
        ${IMAGE_TAG}" > /dev/null

    log_pass "${node_id} deployed on ${ip}"
done

# ---------------------------------------------------------------------------
# Step 4: Wait for cluster
# ---------------------------------------------------------------------------
log_step "Waiting for cluster to form"

# Use the first node as the management endpoint
export TARGET_HOST="${NODE_IPS[0]}"
export MGMT_PORT="${MGMT_PORT_INTERNAL}"
export CLUSTER_ENDPOINT="http://${TARGET_HOST}:${MGMT_PORT}"
export NODE_COUNT="$TOTAL_NODES"

source "${ROOT_DIR}/lib/cluster.sh"
wait_for_cluster 180

LEADER=$(cluster_leader 2>/dev/null || echo "pending")
COUNT=$(cluster_node_count 2>/dev/null || echo "?")

BUILD_TS=$(api_get "/api/status" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('buildTimestamp','N/A'))" 2>/dev/null || echo "N/A")

echo ""
echo "========================================"
echo "  Cloud cluster deployed (${PROVIDER})"
echo "  Nodes:     ${COUNT}"
echo "  Leader:    ${LEADER}"
echo "  Build:     ${BUILD_TS}"
echo "  IPs:       ${NODE_IPS[*]}"
echo "  Mgmt API:  http://${NODE_IPS[0]}:${MGMT_PORT_INTERNAL}"
echo ""
echo "  Run tests:"
echo "    TARGET_HOST=${NODE_IPS[0]} MGMT_PORT=${MGMT_PORT_INTERNAL} \\"
echo "    AETHER_SSH_KEY=${SSH_KEY} AETHER_SSH_USER=${SSH_USER} \\"
echo "    bash aether/tests/integration/scripts/run-all.sh"
echo "========================================"

# ---------------------------------------------------------------------------
# Cleanup hook
# ---------------------------------------------------------------------------
if [ "$DESTROY" = true ]; then
    log_warn "DESTROY flag set — will tear down after tests"
    trap 'log_step "Destroying instances"; destroy_${PROVIDER}_instances' EXIT
fi
