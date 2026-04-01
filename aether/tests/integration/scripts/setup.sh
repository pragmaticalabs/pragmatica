#!/bin/bash
# setup.sh — One-time setup: install prerequisites on target machine
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../lib/common.sh"

AETHER_IMAGE="${AETHER_IMAGE:-ghcr.io/pragmaticalabs/aether-node:0.25.0}"

log_info "Setting up target host: ${TARGET_HOST}"
log_info "SSH user: ${AETHER_SSH_USER}"
log_info "Image: ${AETHER_IMAGE}"

log_step "Installing Docker and prerequisites on ${TARGET_HOST}"
remote_exec << 'REMOTE'
set -e

# Install Docker if not present
if ! command -v docker &> /dev/null; then
    echo "[setup] Installing Docker..."
    apt-get update -qq
    apt-get install -y -qq docker.io curl jq
    systemctl enable docker
    systemctl start docker
    echo "[setup] Docker installed."
else
    echo "[setup] Docker already installed."
fi

# Create working directories
mkdir -p $HOME/aether/config
mkdir -p $HOME/aether/data
mkdir -p $HOME/aether/logs

# Verify Docker is running
docker info > /dev/null 2>&1 || { echo "[setup] ERROR: Docker not running"; exit 1; }
echo "[setup] Docker is running."
REMOTE

log_step "Pulling Aether image: ${AETHER_IMAGE}"
remote_exec "docker pull ${AETHER_IMAGE}"

log_step "Verifying setup"
remote_exec "docker images --filter 'reference=${AETHER_IMAGE}' --format '{{.Repository}}:{{.Tag}}'"

log_pass "Target host setup complete"
