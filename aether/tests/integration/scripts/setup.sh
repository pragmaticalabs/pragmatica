#!/bin/bash
# setup.sh — Build, deploy, and prepare integration test cluster
#
# Self-contained: transfers build artifacts, builds Docker image on target,
# starts cluster, waits for health, pushes example artifacts.
#
# Handles cross-architecture: image is always built on the target host
# to avoid arm64/amd64 mismatch.
#
# Usage:
#   ./scripts/setup.sh                                    # Localhost (same arch)
#   TARGET_HOST=192.168.0.71 ./scripts/setup.sh           # Remote host via SSH
#
# Prerequisites (run on dev machine):
#   ./build.sh                                             # Build all platform modules
#   mvn -f examples/url-shortener/pom.xml install -DskipTests
#   mvn -f examples/url-shortener-v2/pom.xml install -DskipTests
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${SCRIPT_DIR}/.."
REPO_ROOT="${ROOT_DIR}/../.."

source "${ROOT_DIR}/lib/common.sh"
source "${ROOT_DIR}/lib/cluster.sh"

COMPOSE_FILE="${ROOT_DIR}/docker-compose.yml"
NODE_JAR="${REPO_ROOT}/aether/node/target/aether-node.jar"
DOCKERFILE="${REPO_ROOT}/aether/docker/aether-node/Dockerfile"
AETHER_TOML="${REPO_ROOT}/aether/docker/aether-node/aether.toml"

SSH_KEY="${SSH_KEY:-$HOME/.ssh/aether_test}"
SSH_USER="${AETHER_SSH_USER:-aether}"
HOST="${TARGET_HOST:-localhost}"

remote_exec() {
    if [ "$HOST" = "localhost" ]; then
        bash -c "$1"
    else
        ssh -i "$SSH_KEY" "${SSH_USER}@${HOST}" "$1"
    fi
}

remote_copy() {
    if [ "$HOST" = "localhost" ]; then
        cp "$1" "$2"
    else
        scp -i "$SSH_KEY" "$1" "${SSH_USER}@${HOST}:$2"
    fi
}

# ---------------------------------------------------------------------------
# Step 1: Verify prerequisites
# ---------------------------------------------------------------------------
log_step "Verifying prerequisites"

[ -f "$NODE_JAR" ] || { log_error "Node JAR not found: $NODE_JAR — run ./build.sh first"; exit 1; }
[ -f "$DOCKERFILE" ] || { log_error "Dockerfile not found: $DOCKERFILE"; exit 1; }
command -v aether &>/dev/null || { log_error "aether CLI not found — install from aether/cli/target/aether.jar"; exit 1; }
log_pass "Prerequisites OK"

# ---------------------------------------------------------------------------
# Step 2: Transfer build artifacts and build image on target
# ---------------------------------------------------------------------------
log_step "Building Docker image on target (native architecture)"

remote_exec "mkdir -p ~/aether-build"
remote_copy "$NODE_JAR" "~/aether-build/aether-node.jar"
remote_copy "$DOCKERFILE" "~/aether-build/Dockerfile"
remote_copy "$AETHER_TOML" "~/aether-build/aether.toml"
log_info "Build artifacts transferred"

remote_exec "cd ~/aether-build && docker build -t aether-node:local -f Dockerfile --build-arg JAR_PATH=aether-node.jar --build-arg CONFIG_PATH=aether.toml . -q"
log_pass "Docker image built: aether-node:local"

# ---------------------------------------------------------------------------
# Step 3: Start cluster via docker-compose on target
# ---------------------------------------------------------------------------
log_step "Starting integration test cluster"

remote_copy "$COMPOSE_FILE" "~/aether-build/docker-compose.yml"
remote_exec "cd ~/aether-build && docker compose -f docker-compose.yml down -v 2>/dev/null; docker compose -f docker-compose.yml up -d"
log_pass "Cluster started via docker-compose"

# ---------------------------------------------------------------------------
# Step 4: Wait for cluster readiness
# ---------------------------------------------------------------------------
log_step "Waiting for cluster to become ready"
wait_for_cluster 180

local_count=$(cluster_node_count)
local_leader=$(cluster_leader)
log_info "Nodes: ${local_count}, Leader: ${local_leader}"

# ---------------------------------------------------------------------------
# Step 5: Push example artifacts
# ---------------------------------------------------------------------------
log_step "Pushing example artifacts"

BLUEPRINT_V1="${TEST_BLUEPRINT_V1:-org.pragmatica.aether.example:url-shortener:1.0.0}"
BLUEPRINT_V2="${TEST_BLUEPRINT_V2:-org.pragmatica.aether.example:url-shortener:1.0.1}"

push_blueprint "$BLUEPRINT_V1" && log_pass "v1 pushed" || log_warn "v1 push failed (may already exist)"
push_blueprint "$BLUEPRINT_V2" && log_pass "v2 pushed" || log_warn "v2 push failed (may already exist)"

# ---------------------------------------------------------------------------
# Step 6: Deploy v1 as baseline
# ---------------------------------------------------------------------------
log_step "Deploying v1 as baseline"
deploy_blueprint "$BLUEPRINT_V1"
wait_for_slices_active 1 120
log_pass "Baseline deployed: ${BLUEPRINT_V1}"

log_pass "Integration test cluster ready"
