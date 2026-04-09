#!/bin/bash
# deploy-cloud.sh — Deploy Aether cluster on Hetzner Cloud for integration testing
#
# Usage: bash aether/tests/cloud/deploy-cloud.sh [--build] [--skip-build]
#
# Prerequisites:
#   - HCLOUD_TOKEN set in environment (NEVER echoed)
#   - hcloud CLI installed
#   - aether CLI installed
#   - Docker image pushed to GHCR (ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../" && pwd)"
CLOUD_ENV="${SCRIPT_DIR}/.cloud-env"

CLUSTER_NAME="cloud-test"
LABEL="aether-cluster=${CLUSTER_NAME}"
LOCATION="fsn1"
SERVER_TYPE="cx22"
IMAGE="ubuntu-24.04"
NETWORK_NAME="${CLUSTER_NAME}-net"
NETWORK_RANGE="10.0.1.0/24"
SSH_KEY_NAME="${CLUSTER_NAME}-key"
LB_NAME="${CLUSTER_NAME}-lb-public"
AETHER_IMAGE="ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1"
AETHER_LB_IMAGE="ghcr.io/pragmaticalabs/aether-lb:1.0.0-rc1"

DO_BUILD=false
SKIP_BUILD=false

for arg in "$@"; do
    case "$arg" in
        --build) DO_BUILD=true ;;
        --skip-build) SKIP_BUILD=true ;;
    esac
done

# --- helpers ---
log_step() { echo; echo "[$(date +%H:%M:%S)] === $* ==="; }
log_info() { echo "[$(date +%H:%M:%S)] $*"; }
log_pass() { echo "[$(date +%H:%M:%S)] ✓ $*"; }
log_fail() { echo "[$(date +%H:%M:%S)] ✗ $*" >&2; }

ssh_bastion() {
    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 \
        -i "${SSH_KEY_FILE}" root@"${BASTION_IP}" "$@"
}

ssh_via_bastion() {
    local target_ip="$1"; shift
    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 \
        -J "root@${BASTION_IP}" \
        -i "${SSH_KEY_FILE}" root@"${target_ip}" "$@"
}

wait_for_ssh() {
    local host="$1" max_wait="${2:-120}" elapsed=0
    while [ "$elapsed" -lt "$max_wait" ]; do
        ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 \
            -i "${SSH_KEY_FILE}" root@"${host}" "true" 2>/dev/null && return 0
        sleep 5
        elapsed=$((elapsed + 5))
    done
    return 1
}

# =========================================================================
# Phase 1: Validate environment
# =========================================================================
log_step "Phase 1: Validate environment"

if [ -z "${HCLOUD_TOKEN:-}" ]; then
    log_fail "HCLOUD_TOKEN not set in environment"
    exit 1
fi
log_pass "HCLOUD_TOKEN available"

command -v hcloud >/dev/null 2>&1 || { log_fail "hcloud CLI not installed"; exit 1; }
log_pass "hcloud CLI installed"

command -v aether >/dev/null 2>&1 || { log_fail "aether CLI not installed"; exit 1; }
log_pass "aether CLI installed"

log_info "Estimated cost: ~\$0.07/hr (\$1.70/day) for 7x ${SERVER_TYPE} + 1 LB"
log_info "Remember to run teardown-cloud.sh when done!"

# =========================================================================
# Phase 2: Create SSH key
# =========================================================================
log_step "Phase 2: SSH key"

SSH_KEY_FILE="${SCRIPT_DIR}/${SSH_KEY_NAME}"
if [ ! -f "${SSH_KEY_FILE}" ]; then
    ssh-keygen -t ed25519 -f "${SSH_KEY_FILE}" -N "" -C "${CLUSTER_NAME}" >/dev/null 2>&1
    log_pass "Generated SSH key pair: ${SSH_KEY_FILE}"
fi

if ! hcloud ssh-key describe "${SSH_KEY_NAME}" >/dev/null 2>&1; then
    hcloud ssh-key create --name "${SSH_KEY_NAME}" --public-key-from-file "${SSH_KEY_FILE}.pub" >/dev/null
    log_pass "Registered SSH key with Hetzner"
else
    log_info "SSH key already exists in Hetzner"
fi

# =========================================================================
# Phase 3: Create private network
# =========================================================================
log_step "Phase 3: Private network"

if ! hcloud network describe "${NETWORK_NAME}" >/dev/null 2>&1; then
    hcloud network create --name "${NETWORK_NAME}" --ip-range "${NETWORK_RANGE}" \
        --label "${LABEL}" >/dev/null
    hcloud network add-subnet "${NETWORK_NAME}" --type cloud \
        --network-zone eu-central --ip-range "${NETWORK_RANGE}" >/dev/null
    log_pass "Created private network ${NETWORK_NAME} (${NETWORK_RANGE})"
else
    log_info "Network ${NETWORK_NAME} already exists"
fi

NETWORK_ID=$(hcloud network describe "${NETWORK_NAME}" -o json | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
export CLOUD_NETWORK_ID="${NETWORK_ID}"
log_info "Network ID: ${NETWORK_ID}"

# =========================================================================
# Phase 4: Bootstrap core nodes via Aether CLI
# =========================================================================
log_step "Phase 4: Bootstrap core nodes"

if [ "$DO_BUILD" = true ]; then
    log_info "Building project..."
    (cd "$REPO_ROOT" && mvn clean install -DskipTests -q)
    log_pass "Build complete"
fi

# Check if nodes already exist
EXISTING=$(hcloud server list --selector "${LABEL},aether-role=core" -o noheader 2>/dev/null | wc -l | tr -d ' ')
if [ "$EXISTING" -ge 5 ] 2>/dev/null; then
    log_info "5 core nodes already exist — skipping bootstrap"
else
    log_info "Bootstrapping 5 core nodes via Aether CLI..."
    aether cluster bootstrap "${SCRIPT_DIR}/aether-cloud.toml" --yes --wait --timeout 600
    log_pass "Bootstrap complete"
fi

# Collect core node IPs
log_info "Collecting node information..."
for i in $(seq 1 5); do
    NODE_NAME="${CLUSTER_NAME}-${i}"
    if hcloud server describe "${NODE_NAME}" >/dev/null 2>&1; then
        NODE_IP=$(hcloud server describe "${NODE_NAME}" -o json | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["public_net"]["ipv4"]["ip"])')
        PRIVATE_IP="10.0.1.1${i}"
        log_info "  ${NODE_NAME}: public=${NODE_IP} private=${PRIVATE_IP}"
    fi
done

# =========================================================================
# Phase 5: Provision Aether LB VM
# =========================================================================
log_step "Phase 5: Aether LB VM"

LB_VM_NAME="${CLUSTER_NAME}-lb"
if ! hcloud server describe "${LB_VM_NAME}" >/dev/null 2>&1; then
    hcloud server create \
        --name "${LB_VM_NAME}" \
        --type "${SERVER_TYPE}" \
        --image "${IMAGE}" \
        --location "${LOCATION}" \
        --ssh-key "${SSH_KEY_NAME}" \
        --network "${NETWORK_NAME}" \
        --label "${LABEL}" \
        --label "aether-role=lb" >/dev/null
    log_pass "Created LB VM: ${LB_VM_NAME}"
else
    log_info "LB VM already exists"
fi

BASTION_IP=$(hcloud server describe "${LB_VM_NAME}" -o json | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["public_net"]["ipv4"]["ip"])')
log_info "Bastion/LB public IP: ${BASTION_IP}"

# Set private IP for LB
LB_PRIVATE_IP="10.0.1.20"

# Wait for SSH
log_info "Waiting for SSH on LB VM..."
wait_for_ssh "${BASTION_IP}" 120
log_pass "SSH available on LB VM"

# Install Docker and start Aether LB
PEERS=""
for i in $(seq 1 5); do
    [ -n "$PEERS" ] && PEERS="${PEERS},"
    PEERS="${PEERS}node-${i}:10.0.1.1${i}:6000"
done

ssh_bastion "bash -s" <<'SETUP_LB'
set -euo pipefail
if ! command -v docker >/dev/null 2>&1; then
    apt-get update -qq && apt-get install -y -qq docker.io >/dev/null 2>&1
    systemctl enable docker && systemctl start docker
fi
SETUP_LB

ssh_bastion "docker pull ${AETHER_LB_IMAGE} 2>/dev/null || true"
ssh_bastion "docker rm -f aether-lb 2>/dev/null || true"
ssh_bastion "docker run -d --name aether-lb --network host \
    -e LB_HTTP_PORT=8080 \
    -e LB_MANAGEMENT_PORT=8081 \
    -e LB_MANAGEMENT_MAX_CONTENT_LENGTH=16777216 \
    -e LB_CLUSTER_PORT=7000 \
    -e 'PEERS=${PEERS}' \
    ${AETHER_LB_IMAGE}"

log_pass "Aether LB started on ${BASTION_IP}:8081"

# =========================================================================
# Phase 6: Provision Postgres VM
# =========================================================================
log_step "Phase 6: Postgres VM"

PG_VM_NAME="${CLUSTER_NAME}-postgres"
PG_PRIVATE_IP="10.0.1.30"

if ! hcloud server describe "${PG_VM_NAME}" >/dev/null 2>&1; then
    hcloud server create \
        --name "${PG_VM_NAME}" \
        --type "${SERVER_TYPE}" \
        --image "${IMAGE}" \
        --location "${LOCATION}" \
        --ssh-key "${SSH_KEY_NAME}" \
        --network "${NETWORK_NAME}" \
        --label "${LABEL}" \
        --label "aether-role=db" >/dev/null
    log_pass "Created Postgres VM: ${PG_VM_NAME}"
else
    log_info "Postgres VM already exists"
fi

PG_PUBLIC_IP=$(hcloud server describe "${PG_VM_NAME}" -o json | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["public_net"]["ipv4"]["ip"])')

log_info "Waiting for SSH on Postgres VM..."
wait_for_ssh "${PG_PUBLIC_IP}" 120
log_pass "SSH available on Postgres VM"

ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 -i "${SSH_KEY_FILE}" root@"${PG_PUBLIC_IP}" "bash -s" <<'SETUP_PG'
set -euo pipefail
if ! command -v docker >/dev/null 2>&1; then
    apt-get update -qq && apt-get install -y -qq docker.io >/dev/null 2>&1
    systemctl enable docker && systemctl start docker
fi
docker rm -f forge-postgres 2>/dev/null || true
docker run -d --name forge-postgres --network host \
    -e POSTGRES_USER=forge \
    -e POSTGRES_PASSWORD=forge \
    -e POSTGRES_DB=forge \
    postgres:17-alpine
SETUP_PG

# Wait for postgres ready
log_info "Waiting for PostgreSQL..."
for i in $(seq 1 30); do
    if ssh -o StrictHostKeyChecking=no -i "${SSH_KEY_FILE}" root@"${PG_PUBLIC_IP}" \
        "docker exec forge-postgres pg_isready -U forge" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
log_pass "PostgreSQL ready on ${PG_PRIVATE_IP}:5432"

# =========================================================================
# Phase 7: Create Hetzner managed load balancer
# =========================================================================
log_step "Phase 7: Hetzner managed load balancer"

if ! hcloud load-balancer describe "${LB_NAME}" >/dev/null 2>&1; then
    hcloud load-balancer create \
        --name "${LB_NAME}" \
        --type lb11 \
        --location "${LOCATION}" \
        --label "${LABEL}" >/dev/null
    log_pass "Created Hetzner LB: ${LB_NAME}"

    # Attach to private network
    hcloud load-balancer attach-to-network "${LB_NAME}" --network "${NETWORK_NAME}" >/dev/null
    log_pass "Attached LB to private network"

    # Add app HTTP service (TCP passthrough, port 80 → 8070)
    hcloud load-balancer add-service "${LB_NAME}" \
        --protocol tcp \
        --listen-port 80 \
        --destination-port 8070 >/dev/null
    log_pass "Added app HTTP service (port 80 → 8070)"

    # Add health check
    hcloud load-balancer update-health-check "${LB_NAME}" \
        --protocol http \
        --port 8080 \
        --http-path /health/live \
        --interval 10 \
        --timeout 5 \
        --retries 3 >/dev/null 2>&1 || log_info "Health check configured via default"

    # Add core nodes as targets
    for i in $(seq 1 5); do
        NODE_NAME="${CLUSTER_NAME}-${i}"
        hcloud load-balancer add-target "${LB_NAME}" \
            --server "${NODE_NAME}" \
            --use-private-ip >/dev/null 2>&1 || true
        log_info "  Added target: ${NODE_NAME}"
    done
    log_pass "All core nodes added as LB targets"
else
    log_info "Hetzner LB already exists"
fi

HLB_IP=$(hcloud load-balancer describe "${LB_NAME}" -o json | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["public_net"]["ipv4"]["ip"])')
log_info "Hetzner LB public IP: ${HLB_IP}"

# =========================================================================
# Phase 8: Wait for cluster health
# =========================================================================
log_step "Phase 8: Cluster health"

API_KEY=$(aether cluster info "${CLUSTER_NAME}" 2>/dev/null | grep -i 'api.key\|apiKey' | head -1 | awk '{print $NF}' || echo "")
if [ -z "$API_KEY" ]; then
    log_info "API key not found in cluster info — trying environment"
    API_KEY="${AETHER_API_KEY:-aether-integration-test-key}"
fi

log_info "Waiting for cluster health via Aether LB..."
for i in $(seq 1 60); do
    HEALTH=$(curl -sf -H "X-API-Key: ${API_KEY}" "http://${BASTION_IP}:8081/api/health" 2>/dev/null || echo "")
    if echo "$HEALTH" | grep -q '"ready":true'; then
        log_pass "Cluster healthy"
        break
    fi
    sleep 5
done

if ! echo "$HEALTH" | grep -q '"ready":true'; then
    log_fail "Cluster did not become healthy within 300s"
    log_info "Last health response: ${HEALTH}"
    exit 1
fi

# Verify node count
NODE_COUNT=$(curl -sf -H "X-API-Key: ${API_KEY}" "http://${BASTION_IP}:8081/api/cluster/topology" 2>/dev/null \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("coreCount",0))' 2>/dev/null || echo "0")
log_info "Core nodes visible: ${NODE_COUNT}"

# Wait for task groups
log_info "Waiting for task group assignments..."
for i in $(seq 1 60); do
    TASKS=$(curl -sf -H "X-API-Key: ${API_KEY}" "http://${BASTION_IP}:8081/api/cluster/tasks" 2>/dev/null || echo "{}")
    ACTIVE=$(echo "$TASKS" | python3 -c "
import sys,json
try:
    data=json.load(sys.stdin)
    print(sum(1 for a in data.get('assignments',[]) if a.get('status')=='ACTIVE'))
except: print(0)
" 2>/dev/null || echo "0")
    if [ "$ACTIVE" -ge 6 ] 2>/dev/null; then
        log_pass "All 6 task groups ACTIVE"
        break
    fi
    sleep 5
done

# =========================================================================
# Phase 9: Push test artifacts
# =========================================================================
log_step "Phase 9: Push test artifacts"

AETHER_CONN="${BASTION_IP}:8081"

aether -c "${AETHER_CONN}" --api-key "${API_KEY}" artifact push \
    org.pragmatica.aether.example:url-shortener:1.0.0 2>/dev/null && \
    log_pass "Pushed url-shortener:1.0.0" || log_info "url-shortener:1.0.0 push failed (may already exist)"

aether -c "${AETHER_CONN}" --api-key "${API_KEY}" artifact push \
    org.pragmatica.aether.example:url-shortener:1.0.1 2>/dev/null && \
    log_pass "Pushed url-shortener:1.0.1" || log_info "url-shortener:1.0.1 push failed (may already exist)"

# =========================================================================
# Phase 10: Write .cloud-env
# =========================================================================
log_step "Phase 10: Environment file"

cat > "${CLOUD_ENV}" <<ENVFILE
# Generated by deploy-cloud.sh — source this before running tests
DEPLOY_START=$(date +%s)
CLUSTER_ENDPOINT=http://${BASTION_IP}:8081
APP_ENDPOINT=http://${HLB_IP}:80
TARGET_HOST=${BASTION_IP}
BASTION_IP=${BASTION_IP}
HLB_IP=${HLB_IP}
MGMT_PORT=8081
LB_PORT=8081
LB_MGMT_PORT=8081
LB_PORT=80
NODE_COUNT=5
API_KEY=${API_KEY}
AETHER_SSH_KEY=${SSH_KEY_FILE}
AETHER_SSH_USER=root
CLOUD_MODE=true
PG_HOST=${PG_PRIVATE_IP}
ENVFILE

log_pass "Wrote ${CLOUD_ENV}"

echo
echo "========================================"
echo "  Cloud cluster deployed"
echo "  Cluster:    ${CLUSTER_NAME}"
echo "  Nodes:      5 core + LB + postgres"
echo "  Mgmt API:   http://${BASTION_IP}:8081"
echo "  App HTTP:   http://${HLB_IP}:80"
echo "  Bastion:    ssh -i ${SSH_KEY_FILE} root@${BASTION_IP}"
echo "  API Key:    ${API_KEY}"
echo "========================================"
echo
echo "Next: bash aether/tests/cloud/run-cloud-tests.sh"
echo "Done: bash aether/tests/cloud/teardown-cloud.sh"
